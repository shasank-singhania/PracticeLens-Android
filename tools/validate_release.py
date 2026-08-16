#!/usr/bin/env python3
from pathlib import Path
import argparse
import hashlib
import os
import re
import sys

parser = argparse.ArgumentParser()
parser.add_argument("--tag", required=True)
parser.add_argument("--apk", required=True)
parser.add_argument("--notes", required=True)
parser.add_argument("--expected-cert-sha256", default=os.environ.get("PRACTICELENS_EXPECTED_CERT_SHA256", ""))
args = parser.parse_args()

root = Path(__file__).resolve().parents[1]
version = dict(
    line.strip().split("=", 1)
    for line in (root / "version.properties").read_text().splitlines()
    if line.strip() and not line.startswith("#")
)
errors = []
expected_tag = f"v{version['versionName']}"
if args.tag != expected_tag:
    errors.append(f"tag {args.tag} does not match versionName {version['versionName']}")
if int(version["versionCode"]) < 1:
    errors.append("versionCode must be positive and must increase before later releases")
apk = Path(args.apk)
if not apk.name.startswith(f"PracticeLens-v{version['versionName']}") or not apk.name.endswith(".apk"):
    errors.append("APK filename must include version and .apk suffix")
notes = Path(args.notes)
if not notes.exists() or not notes.read_text(encoding="utf-8").strip():
    errors.append("release notes are missing")
if not apk.exists():
    errors.append("APK does not exist")
else:
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (apk.with_suffix(apk.suffix + ".sha256")).write_text(f"{digest}  {apk.name}\n", encoding="utf-8")
if args.expected_cert_sha256 and not re.fullmatch(r"[A-Fa-f0-9:]{64,95}", args.expected_cert_sha256):
    errors.append("expected certificate SHA-256 fingerprint has an invalid format")
if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print("Release metadata validation passed.")
