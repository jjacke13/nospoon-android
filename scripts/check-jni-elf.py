#!/usr/bin/env python3
"""Check NDK llvm-readelf output; no target code is executed."""
import re
import sys
from pathlib import Path


def check(report, declarations):
    if not re.search(r"Class:\s+ELF64\b", report) or not re.search(r"Machine:\s+AArch64\b", report):
        raise ValueError("Expected an ELF64 AArch64 library")
    if not re.search(r"Type:\s+DYN\b", report):
        raise ValueError("Expected a shared object")
    if "Build ID:" in report:
        raise ValueError("Path-dependent GNU build IDs must be disabled")
    loads = [line.split() for line in report.splitlines() if line.strip().startswith("LOAD ")]
    if not loads or any(int(fields[-1], 16) < 16384 for fields in loads):
        raise ValueError("Every LOAD segment must support 16 KiB pages")
    needed = set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", report))
    unexpected = needed - {"libc.so", "libm.so", "libdl.so", "liblog.so"}
    if unexpected:
        raise ValueError("Unexpected shared dependencies: " + ", ".join(sorted(unexpected)))
    symbols = {line.split()[-1] for line in report.splitlines()
               if re.match(r"\s*\d+:", line) and " GLOBAL " in line and " UND " not in line}
    methods = re.findall(r"\bexternal\s+fun\s+(\w+)", declarations)
    if not methods:
        raise ValueError("No native declarations found")
    expected = {"Java_com_hyperdht_Native_" + name for name in methods} | {"JNI_OnLoad"}
    missing = expected - symbols
    if missing:
        raise ValueError("Missing JNI exports: " + ", ".join(sorted(missing)))


if __name__ == "__main__":
    try:
        check(Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text())
    except (ValueError, IndexError) as error:
        sys.exit(str(error))
    print("JNI ELF: AArch64, 16 KiB LOAD alignment, static dependencies and required exports verified")
