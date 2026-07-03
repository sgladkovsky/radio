#!/usr/bin/env python3
"""Realign arm64-v8a ELF LOAD segments to 16 KB for Android 15+ compatibility."""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import lief
except ImportError as exc:
    raise SystemExit(
        "lief is required: pip install lief\n"
        "This script realigns prebuilt .so files from dab2 for 16 KB page devices."
    ) from exc

PAGE_SIZE = 0x4000
JNI_LIBS = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "jniLibs"


def align_library(path: Path) -> bool:
    binary = lief.parse(str(path))
    changed = False

    for segment in binary.segments:
        if segment.type == lief.ELF.Segment.TYPE.LOAD and segment.alignment < PAGE_SIZE:
            segment.alignment = PAGE_SIZE
            changed = True

    if not changed:
        return False

    temp_path = path.with_suffix(path.suffix + ".tmp")
    binary.write(str(temp_path))
    temp_path.replace(path)
    return True


def main() -> int:
    arm64_dir = JNI_LIBS / "arm64-v8a"
    if not arm64_dir.is_dir():
        print(f"Missing directory: {arm64_dir}", file=sys.stderr)
        return 1

    updated = 0
    for library in sorted(arm64_dir.glob("*.so")):
        if align_library(library):
            print(f"realigned {library.name}")
            updated += 1
        else:
            print(f"ok {library.name}")

    print(f"Done. Updated {updated} libraries.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
