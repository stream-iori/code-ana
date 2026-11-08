#!/usr/bin/env python3
"""Resolve an explicit, version-checked SDKMAN compiler without changing SDKMAN state."""
import argparse
import os
from pathlib import Path
import re
import sys


def resolve(root: Path, major: int, selection: str | None = None) -> Path:
    if major not in (8, 17):
        raise ValueError(f"unsupported compiler version: {major}")
    candidates = [Path(selection) if Path(selection).is_absolute() else root / selection] if selection else sorted(root.iterdir())
    matches = set()
    for candidate in candidates:
        if not selection and candidate.name == "current":
            continue
        try:
            home = candidate.resolve(strict=True)
            release = (home / "release").read_text()
        except OSError:
            continue
        version = re.search(r'^JAVA_VERSION="(?:1\.)?(\d+)(?:[.\-+"_])', release, re.M)
        if not version or int(version[1]) != major:
            continue
        if all(os.access(home / "bin" / name, os.X_OK) and (home / "bin" / name).is_file() for name in ("java", "javac")):
            matches.add(home)
    if len(matches) != 1:
        raise ValueError(f"Java {major}: expected one SDKMAN compiler, found {len(matches)}; select CODEANA_JAVA{major}_HOME explicitly")
    return matches.pop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("major", type=int, choices=(8, 17))
    parser.add_argument("--root", type=Path, default=Path(os.environ.get("SDKMAN_CANDIDATES_DIR", str(Path.home() / ".sdkman/candidates"))) / "java")
    args = parser.parse_args()
    try:
        print(resolve(args.root, args.major, os.environ.get(f"CODEANA_JAVA{args.major}_HOME")))
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
