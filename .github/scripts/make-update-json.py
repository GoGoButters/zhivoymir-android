#!/usr/bin/env python3
"""update.json для самообновления лаунчера (читает AppUpdate.parse в приложении).

make-update-json.py --apk dist/ZhivoyMir-0.2.0.apk --version-name 0.2.0-zl2.8.2 \
    --version-code 200 --notes-file notes.txt [--url https://.../ZhivoyMir-0.2.0.apk] --out dist/update.json

versionCode = MAJOR*10000 + MINOR*100 + PATCH (как в ZalithLauncher/build.gradle.kts);
--version-code (из самого APK) обязан совпасть с формулой и быть > 1 (у v0.1.0 был 1).
"""
import argparse
import hashlib
import json
import os
import re
import sys


def version_code_of(name):
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)", name)
    if not m:
        return None
    major, minor, patch = (int(g) for g in m.groups())
    if minor >= 100 or patch >= 100:
        return None
    return major * 10000 + minor * 100 + patch


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--apk", required=True)
    p.add_argument("--version-name", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--notes-file")
    p.add_argument("--url")
    p.add_argument("--out", required=True)
    a = p.parse_args()

    expected = version_code_of(a.version_name)
    if expected is None:
        sys.exit(f"versionName {a.version_name!r} is not MAJOR.MINOR.PATCH")
    if a.version_code != expected:
        sys.exit(f"APK versionCode {a.version_code} != formula {expected} for {a.version_name}")
    if a.version_code <= 1:
        sys.exit(f"versionCode {a.version_code} must be > 1")

    h = hashlib.sha256()
    with open(a.apk, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    size = os.path.getsize(a.apk)
    if size <= 0:
        sys.exit("empty APK")

    notes = ""
    if a.notes_file:
        with open(a.notes_file, encoding="utf-8") as f:
            notes = f.read().strip()

    data = {
        "versionCode": a.version_code,
        "versionName": a.version_name,
        "apk": os.path.basename(a.apk),
        "size": size,
        "sha256": h.hexdigest(),
        "notes": notes,
    }
    if a.url:
        if not a.url.startswith("https://") or not a.url.endswith("/" + data["apk"]):
            sys.exit(f"bad url {a.url!r}")
        data["url"] = a.url
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(json.dumps(data, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
