#!/usr/bin/env python3
"""Produce the updater's manifest from the actual signed APK, not workflow guesses."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

REPOSITORY = "imjunseong958-droid/browser_air"
MAX_APK_BYTES = 384 * 1024 * 1024


def digest(apk):
    result = hashlib.sha256()
    with apk.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def validate(info, apk):
    code = info.get("versionCode")
    size = info.get("sizeBytes")
    sdk = info.get("minSdk")
    if (type(code) is not int or not 1 <= code <= 2_100_000_000
            or type(size) is not int or not 0 < size <= MAX_APK_BYTES
            or type(sdk) is not int or not 29 <= sdk <= 10_000
            or type(info.get("schemaVersion")) is not int or info["schemaVersion"] != 1
            or info.get("applicationId") != "dev.air.browser" or info.get("abi") != "arm64-v8a"
            or not isinstance(info.get("versionName"), str)
            or not re.fullmatch(r"[0-9A-Za-z.+_-]{1,64}", info["versionName"])
            or info.get("apkUrl") != f"https://github.com/{REPOSITORY}/releases/download/air-v{code}/AIR.apk"
            or not isinstance(info.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", info["sha256"])
            or size != apk.stat().st_size or info["sha256"] != digest(apk)):
        raise ValueError("Release manifest does not match AIR.apk")


def from_badging(badging, apk):
    if re.search(r"^uses-permission(?:-sdk-\d+)?: name='android\.permission\.(?:RECEIVE_SMS|READ_SMS|SEND_SMS)'", badging, re.M):
        raise ValueError("AIR APK must use one-message consent without SMS permissions")
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    native = re.search(r"^native-code:(.*)$", badging, re.M)
    if (not package or not sdk or not native or package[1] != "dev.air.browser"
            or re.findall(r"'([^']+)'", native[1]) != ["arm64-v8a"]
            or re.search(r"^application-debuggable", badging, re.M)):
        raise ValueError("Expected a non-debuggable AIR ARM64 APK")
    code = int(package[2])
    info = {"schemaVersion": 1, "applicationId": package[1], "versionCode": code,
            "versionName": package[3], "minSdk": int(sdk[1]), "abi": "arm64-v8a",
            "apkUrl": f"https://github.com/{REPOSITORY}/releases/download/air-v{code}/AIR.apk",
            "sha256": digest(apk), "sizeBytes": apk.stat().st_size}
    validate(info, apk)
    return info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    badging = subprocess.check_output([args.aapt, "dump", "badging", str(args.apk)], text=True)
    info = from_badging(badging, args.apk)
    args.output.write_text(json.dumps(info, indent=2, sort_keys=True) + "\n")
    print(f"Prepared AIR {info['versionName']} (versionCode {info['versionCode']}) update manifest.")


if __name__ == "__main__":
    main()
