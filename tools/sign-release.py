#!/usr/bin/env python3
"""Align, sign and verify an existing release APK; all password inputs are files."""
import argparse
import importlib.util
import os
from pathlib import Path
import re
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location("build_version", Path(__file__).with_name("build-version.py"))
version_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version_module)


def run(*args, capture=False):
    return subprocess.run([str(arg) for arg in args], check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--signing-dir", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    version, code = version_module.project_version()
    version_module.validate_tag(args.tag, version)
    keystore = args.signing_dir / "release.p12"
    password = args.signing_dir / "release.password"
    for path in (args.apk, keystore, password):
        if not path.is_file():
            parser.error(f"Required file missing: {path}")
    if args.output.exists():
        parser.error(f"Refusing to overwrite {args.output}")
    badging = run(args.build_tools / "aapt2", "dump", "badging", args.apk, capture=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != ("moe.nepnep.hduhelper", str(code), version):
        parser.error("APK package/version does not match the release configuration")
    if "application-debuggable" in badging:
        parser.error("Refusing to release a debuggable APK")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hduhelper-sign-", dir=args.output.parent) as temporary:
        aligned = Path(temporary) / "aligned.apk"
        signed = Path(temporary) / "signed.apk"
        run(args.build_tools / "zipalign", "-P", "16", "-f", "4", args.apk, aligned)
        run(args.build_tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", "hduhelper",
            "--ks-pass", f"file:{password}",
            "--v4-signing-enabled", "false", "--out", signed, aligned)
        run(args.build_tools / "zipalign", "-c", "-P", "16", "4", signed)
        run(args.build_tools / "apksigner", "verify", "--verbose", "--print-certs", signed)
        os.rename(signed, args.output)
    print(f"Signed release: {args.output}")


if __name__ == "__main__":
    main()
