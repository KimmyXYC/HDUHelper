#!/usr/bin/env python3
"""Resolve CI/release versions without needing signing secrets or a Gradle daemon."""
import argparse
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent
VERSION = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+", re.ASCII)


def project_version():
    source = (ROOT / "app/build.gradle.kts").read_text()
    name = re.search(r'^\s*versionName = "([0-9]+\.[0-9]+\.[0-9]+)"\s*$', source, re.M)
    code = re.search(r'^\s*versionCode = ([0-9]+)\s*$', source, re.M)
    if not name or not code:
        raise ValueError("Expected one explicit versionName and versionCode in defaultConfig")
    return name[1], int(code[1])


def validate_tag(tag, version):
    if not VERSION.fullmatch(tag):
        raise ValueError("Release tag must be vMAJOR.MINOR.PATCH with numeric components only")
    if tag != f"v{version}":
        raise ValueError(f"Tag {tag} does not match project version v{version}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("ci", "release"))
    parser.add_argument("--tag")
    args = parser.parse_args()
    version, _ = project_version()
    if args.mode == "ci":
        sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        print(f"v{version}.{sha[:7]}")
    else:
        validate_tag(args.tag or "", version)
        print(args.tag)


if __name__ == "__main__":
    main()
