#!/usr/bin/env bash
# CI only: run fdroidserver's source scanner/build inside its official buildserver image.
set -euo pipefail
[[ "${GITHUB_ACTIONS:-}" == true && -f /.dockerenv && $(id -u) == 0 ]] || {
    echo 'Run this only in the dedicated GitHub Actions buildserver container.' >&2; exit 1;
}
[[ $# == 1 && "$1" =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected the app commit SHA' >&2; exit 2; }
repo_root=$(cd "$(dirname "$0")/.." && pwd)
git -c safe.directory="$repo_root" -C "$repo_root" rev-parse --show-toplevel
# Pin the same F-Droid tools/configuration for repeatable rehearsal inputs.
git clone --no-checkout https://gitlab.com/fdroid/fdroidserver.git /tmp/fdroid-tools
git -C /tmp/fdroid-tools rev-parse --show-toplevel
git -C /tmp/fdroid-tools checkout --detach 5806784d0d43bed7b938fd4b327162da68927060
git clone --depth=1 --no-checkout https://gitlab.com/fdroid/fdroiddata.git /tmp/fdroiddata
git -C /tmp/fdroiddata rev-parse --show-toplevel
git -C /tmp/fdroiddata fetch --depth=1 origin 024de33958252e3c740b874a92122357fe4746dd
git -C /tmp/fdroiddata sparse-checkout set config srclibs
git -C /tmp/fdroiddata checkout --detach 024de33958252e3c740b874a92122357fe4746dd
mkdir -p /tmp/fdroiddata/metadata
cp "$repo_root/fdroid/metadata/com.nospoon.vpn.yml" /tmp/fdroiddata/metadata/
cp "$repo_root"/fdroid/srclibs/*.yml /tmp/fdroiddata/srclibs/
printf '{}\n' > /tmp/fdroiddata/config.yml
chmod 600 /tmp/fdroiddata/config.yml
python3 - "$1" <<'PY'
from pathlib import Path
import sys
from ruamel.yaml import YAML
path = Path('/tmp/fdroiddata/metadata/com.nospoon.vpn.yml')
yaml = YAML()
yaml.indent(mapping=2, sequence=4, offset=2)
data = yaml.load(path)
# Test this exact commit without creating/publishing the release tag.
data['Builds'][-1]['commit'] = sys.argv[1]
with path.open('w') as out:
    yaml.dump(data, out)
Path('/tmp/fdroiddata/build-spec.txt').write_text('com.nospoon.vpn:' + str(data['Builds'][-1]['versionCode']))
PY
chown -R vagrant:vagrant /tmp/fdroid-tools /tmp/fdroiddata
# F-Droid --on-server removes sudo/locks root inside this disposable container.
# shellcheck disable=SC2016 # Expand these variables in the vagrant shell, not the root shell.
runuser --login vagrant --command 'bash -euo pipefail <<"INNER"
source /etc/profile.d/bsenv.sh
export PATH="/tmp/fdroid-tools:$PATH"
export PYTHONPATH="/tmp/fdroid-tools:/tmp/fdroid-tools/examples"
cd /tmp/fdroiddata
sdkmanager "platforms;android-36" "build-tools;36.0.0"
fdroid readmeta
fdroid lint com.nospoon.vpn
build_spec=$(cat build-spec.txt)
fdroid fetchsrclibs "$build_spec"
fdroid build --verbose --test --on-server --no-tarball --refresh-scanner "$build_spec"
INNER'
