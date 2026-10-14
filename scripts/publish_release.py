#!/usr/bin/env python3
"""Publish complete releases, keeping a late older workflow from moving latest backwards."""
import argparse
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from release_manifest import REPOSITORY, validate


def gh(arguments, allow_missing=False):
    result = subprocess.run(["gh", *arguments], text=True, capture_output=True)
    if result.returncode:
        if allow_missing and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError("GitHub release operation failed: " + result.stderr.strip())
    return json.loads(result.stdout) if arguments[0] == "api" else result.stdout


def publish(directory, commit, repository, command=gh):
    if repository != REPOSITORY or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Unexpected AIR release repository or commit")
    apk = directory / "AIR.apk"
    manifest = directory / "update.json"
    info = json.loads(manifest.read_text())
    validate(info, apk)
    checksum = directory / "SHA256SUMS"
    if checksum.read_text().strip() != f"{info['sha256']}  AIR.apk":
        raise ValueError("Release checksum mismatch")
    tag = f"air-v{info['versionCode']}"
    existing = command(["api", f"repos/{repository}/releases/tags/{tag}"], allow_missing=True)
    if existing and not existing["draft"]:
        if existing["target_commitish"] != commit or not {"AIR.apk", "update.json", "SHA256SUMS"}.issubset(
                {asset["name"] for asset in existing["assets"]}):
            raise ValueError("Existing public release does not match this build")
        print(f"Preserved already published {tag}; reruns never replace public assets.")
        return
    if existing and existing["target_commitish"] != commit:
        raise ValueError("Existing draft belongs to a different commit")
    latest = command(["api", f"repos/{repository}/releases/latest"], allow_missing=True)
    latest_code = 0
    if latest:
        match = re.fullmatch(r"air-v([1-9][0-9]*)", latest["tag_name"])
        if not match:
            raise ValueError("Latest release is outside the AIR update channel")
        latest_code = int(match[1])
    if not existing:
        with tempfile.TemporaryDirectory(prefix="air-release-") as temporary:
            notes = Path(temporary) / "notes.md"
            notes.write_text(f"AIR {info['versionName']} for ARM64 devices running Android 10 or later.\n\n"
                             "Install AIR.apk, or choose Update in AIR. Updates to an existing release retain settings and sign-in storage.\n\n"
                             "SHA256SUMS verifies AIR.apk. update.json is used by the app's update checker.\n")
            command(["release", "create", tag, "--repo", repository, "--draft", "--target", commit,
                     "--title", f"AIR {info['versionName']}", "--notes-file", str(notes)])
    command(["release", "upload", tag, "--repo", repository, str(apk), str(manifest), str(checksum), "--clobber"])
    # Publishing the draft only after every upload succeeds avoids exposing an incomplete manifest/APK pair.
    command(["release", "edit", tag, "--repo", repository, "--draft=false",
             "--latest=" + ("true" if info["versionCode"] > latest_code else "false")])
    print(f"Published {tag} with complete AIR update assets.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=Path("artifacts/release"))
    args = parser.parse_args()
    publish(args.directory, os.environ["GITHUB_SHA"], os.environ["GITHUB_REPOSITORY"])
