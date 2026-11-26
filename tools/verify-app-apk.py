#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify the host window bridge and its manifest in the final CI APK."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile

LIB = "lib/arm64-v8a/"
RENDERER = "liba15vm-desktop-window.so"
ALIAS = "liba10vm-desktop-window.so"
MANIFEST = "assets/native-display-inputs.json"


def verify(apk: Path) -> None:
    with zipfile.ZipFile(apk) as archive:
        members = archive.namelist()
        unique = set(members)
        if len(members) != len(unique):
            raise ValueError("duplicate ZIP members")
        required = {MANIFEST, "assets/kanadex-rom/index.json", LIB + RENDERER,
                    LIB + ALIAS, LIB + "libversebox_kanadex_logo.so"}
        missing = sorted(required - unique)
        if missing:
            raise ValueError("missing required members: " + ", ".join(missing))
        manifest = json.loads(archive.read(MANIFEST))
        entries = manifest["files"]
        names = [entry["name"] for entry in entries]
        if RENDERER not in names or len(names) != len(set(names)):
            raise ValueError("renderer manifest must list the A15 window library exactly once")
        for entry in entries:
            name = entry["name"]
            if not re.fullmatch(r"lib[A-Za-z0-9_.+-]+.so", name):
                raise ValueError(f"invalid renderer manifest member: {name}")
            data = archive.read(LIB + name)
            if data[:7] != b"\x7fELF\x02\x01\x01" or data[18:20] != b"\xb7\x00":
                raise ValueError(f"{name} is not a little-endian AArch64 ELF")
            if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise ValueError(f"{name} differs from its manifest size or SHA-256")
        renderer = archive.read(LIB + RENDERER)
        if archive.read(LIB + ALIAS) != renderer:
            raise ValueError("A10 renderer alias differs from the source-built A15 renderer")
        codec = manifest["codec"]
        if not re.fullmatch(r"[0-9a-f]{64}", codec) or codec.encode("ascii") not in renderer:
            raise ValueError("renderer does not contain its manifest codec")
        peer = LIB + "liba15vm-desktop-gpu-host.so"
        if peer in unique and codec.encode("ascii") not in archive.read(peer):
            raise ValueError("renderer codec differs from the packaged A15 desktop GPU host")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        verify(args.apk)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(1, f"{args.apk}: {error}\n")
    print(f"{args.apk}: host renderer and manifest verified")


if __name__ == "__main__":
    main()
