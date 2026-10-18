"""Safety checks use mocked tools only; no native compilation."""
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('elf_check', ROOT / 'scripts/check-jni-elf.py')
ELF = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ELF)
DECLARATIONS = (ROOT / 'app/src/main/java/com/hyperdht/Native.kt').read_text()
METHODS = re.findall(r'\bexternal\s+fun\s+(\w+)', DECLARATIONS)
REPORT = '''Class: ELF64
Type: DYN (Shared object file)
Machine: AArch64
 LOAD 0x000000 0x000000 0x000000 0x000100 0x000100 R E 0x4000
 0x000001 (NEEDED) Shared library: [libc.so]
 1: 00001000 8 FUNC GLOBAL DEFAULT 12 JNI_OnLoad
''' + ''.join(f' {i+2}: 00002000 8 FUNC GLOBAL DEFAULT 12 Java_com_hyperdht_Native_{name}\n'
              for i, name in enumerate(METHODS))


class ElfChecks(unittest.TestCase):
    def test_valid_and_incompatible_libraries(self):
        ELF.check(REPORT, DECLARATIONS)
        for broken in (REPORT.replace('0x4000', '0x1000'), REPORT.replace('AArch64', 'X86-64'),
                       REPORT.replace('[libc.so]', '[libc++_shared.so]'),
                       REPORT.replace('12 JNI_OnLoad', 'UND JNI_OnLoad')):
            with self.subTest(report=broken), self.assertRaises(ValueError):
                ELF.check(broken, DECLARATIONS)
        with self.assertRaises(ValueError):
            ELF.check(REPORT, '')


class MetadataChecks(unittest.TestCase):
    def test_fdroid_and_ci_share_pins_and_scripts(self):
        pins = json.loads((ROOT / 'fdroid/native-sources.json').read_text())
        metadata = yaml.safe_load((ROOT / 'fdroid/native-build.yml').read_text())
        names = {'hyperdht': 'HyperDHT', 'libudx': 'libudx', 'libsodium': 'libsodium', 'libuv': 'libuv'}
        self.assertEqual(str(metadata['ndk']), pins['ndk'])
        self.assertEqual(set(metadata['srclibs']),
                         {f"{names[name]}@{value['commit']}" for name, value in pins['sources'].items()})
        for name in ('hyperdht', 'libudx', 'libuv'):
            source = yaml.safe_load((ROOT / f'fdroid/srclibs/{names[name]}.yml').read_text())
            self.assertEqual(source['Repo'], pins['sources'][name]['url'])
        workflow = (ROOT / '.github/workflows/native-source.yml').read_text()
        yaml.safe_load(workflow)
        self.assertIn('scripts/prepare-native-sources.sh', metadata['prebuild'])
        self.assertNotIn('build-jni', metadata['prebuild'])
        self.assertIn('scripts/build-jni-from-source.sh', metadata['build'])
        for script in ('prepare-native-sources.sh', 'build-jni-from-source.sh'):
            self.assertIn('scripts/' + script, workflow)
        self.assertNotIn('scanignore', metadata)


class RecipeChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='native recipe ')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / 'app'
        for directory in ('scripts', 'fdroid', 'app/src/main/java/com/hyperdht'):
            shutil.copytree(ROOT / directory, self.repo / directory,
                            ignore=shutil.ignore_patterns('__pycache__', 'tests'))
        self.hyper = self.base / 'hyperdht'
        wrappers = self.hyper / 'wrappers/kotlin/src/main/kotlin/com/hyperdht'
        shutil.copytree(ROOT / 'app/src/main/java/com/hyperdht', wrappers)
        cpp = self.hyper / 'wrappers/kotlin/src/main/cpp/hyperdht_jni.cpp'
        cpp.parent.mkdir(parents=True)
        cpp.write_text('''    JNIEnv* env = nullptr;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    pthread_once(&g_jni_key_once, make_key);
    g_jvm->AttachCurrentThread((void**)&env, nullptr);
    pthread_setspecific(g_jni_key, env);  // triggers detach_thread on exit
    return env;
}
''')
        self.udx = self.base / 'libudx'
        (self.udx / 'src').mkdir(parents=True)
        (self.udx / 'include').mkdir()
        (self.udx / 'src/udx.c').write_text('fixture, not compiled')
        (self.udx / 'LICENSE').write_text('fixture')
        self.sodium = self.base / 'sodium'
        self.sodium.mkdir()
        self.executable(self.sodium / 'configure', "#!/bin/sh\nPACKAGE_VERSION='1.0.22'\nexit 0\n")
        self.uv = self.base / 'uv'
        (self.uv / 'include/uv').mkdir(parents=True)
        (self.uv / 'include/uv/version.h').write_text(
            '#define UV_VERSION_MAJOR 1\n#define UV_VERSION_MINOR 51\n#define UV_VERSION_PATCH 0\n')
        self.ndk = self.base / 'ndk'
        self.ndk.mkdir()
        (self.ndk / 'source.properties').write_text('Pkg.Revision = 27.3.13750724\n')
        tools = self.ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
        tools.mkdir(parents=True)
        for name in ('aarch64-linux-android26-clang', 'aarch64-linux-android26-clang++',
                     'llvm-ar', 'llvm-ranlib', 'llvm-strip'):
            self.executable(tools / name, '#!/bin/sh\nexit 99\n')
        self.executable(tools / 'llvm-readelf', '#!/bin/sh\ncat "$FAKE_REPORT"\n')
        self.executable(tools / 'llvm-strip', '#!/bin/sh\nexit 0\n')
        fakebin = self.base / 'bin'
        fakebin.mkdir()
        self.executable(fakebin / 'make', '#!/bin/sh\nexit 0\n')
        self.executable(fakebin / 'cmake', '''#!/bin/sh
if [ "$1" = --build ] && [ "$4" = hyperdht_jni ]; then
    [ "${FAIL_BUILD:-0}" = 0 ] || exit 9
    mkdir -p "$2"
    printf 'mock library, not compiled' > "$2/libhyperdht_jni.so"
fi
''')
        self.report = self.base / 'elf.txt'
        self.report.write_text(REPORT)
        self.env = dict(os.environ, PATH=str(fakebin) + os.pathsep + os.environ['PATH'],
                        FAKE_REPORT=str(self.report))
        self.work = self.base / 'not yet created/work'
        self.output = self.base / 'out/lib.so'

    def executable(self, path, text):
        path.write_text(text)
        path.chmod(0o755)

    def run_script(self, name, *args):
        return subprocess.run([str(self.repo / 'scripts' / name), *map(str, args)],
                              env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def prepare(self):
        return self.run_script('prepare-native-sources.sh', self.hyper, self.udx)

    def build(self):
        return self.run_script('build-jni-from-source.sh', self.hyper, self.sodium, self.uv,
                               self.ndk, self.work, self.output)

    def test_preparation_repeats_and_rejects_drift(self):
        for _ in range(2):
            result = self.prepare()
            self.assertEqual(result.returncode, 0, result.stdout)
        cpp = self.hyper / 'wrappers/kotlin/src/main/cpp/hyperdht_jni.cpp'
        self.assertIn('AttachCurrentThread(&env, nullptr)', cpp.read_text())
        cpp.write_text(cpp.read_text().replace('AttachCurrentThread(&env, nullptr)', 'unexpected_call()'))
        self.assertNotEqual(self.prepare().returncode, 0)
        wrapper = self.hyper / 'wrappers/kotlin/src/main/kotlin/com/hyperdht/Native.kt'
        wrapper.write_text('incompatible JNI declarations')
        self.assertNotEqual(self.prepare().returncode, 0)

    def test_mock_build_packages_only_after_validation(self):
        self.assertEqual(self.prepare().returncode, 0)
        result = self.build()
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.output.read_bytes(), b'mock library, not compiled')
        self.assertNotEqual(self.build().returncode, 0, 'stale work must fail')

    def test_bad_toolchain_build_failure_and_bad_elf_do_not_package(self):
        self.assertEqual(self.prepare().returncode, 0)
        props = self.ndk / 'source.properties'
        props.write_text('Pkg.Revision = 27X3X13750724\n')
        self.assertNotEqual(self.build().returncode, 0)
        self.assertFalse(self.work.exists())
        props.write_text('Pkg.Revision = 27.3.13750724\n')
        uv_version = self.uv / 'include/uv/version.h'
        original = uv_version.read_text()
        uv_version.write_text(original.replace('MINOR 51', 'MINOR 50'))
        self.assertNotEqual(self.build().returncode, 0)
        self.assertFalse(self.work.exists())
        uv_version.write_text(original)
        self.env['FAIL_BUILD'] = '1'
        self.assertNotEqual(self.build().returncode, 0)
        self.assertFalse(self.output.exists())
        shutil.rmtree(self.work)
        self.env['FAIL_BUILD'] = '0'
        self.report.write_text(REPORT.replace('0x4000', '0x1000'))
        self.assertNotEqual(self.build().returncode, 0)
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
