import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_manifest
import publish_release

COMMIT = "a" * 40
BADGING = "package: name='dev.air.browser' versionCode='1009' versionName='0.1.2+1009'\nsdkVersion:'29'\nnative-code: 'arm64-v8a'\n"


class ReleasesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.apk = self.directory / "AIR.apk"
        self.apk.write_bytes(b"APK metadata fixture")
        self.info = release_manifest.from_badging(BADGING, self.apk)
        (self.directory / "update.json").write_text(json.dumps(self.info))
        (self.directory / "SHA256SUMS").write_text(f"{self.info['sha256']}  AIR.apk\n")
    def tearDown(self):
        self.temp.cleanup()

    def test_manifest_uses_actual_apk_size_hash_version_and_pinned_url(self):
        self.assertEqual(1009, self.info["versionCode"])
        self.assertEqual(self.apk.stat().st_size, self.info["sizeBytes"])
        self.assertEqual(release_manifest.digest(self.apk), self.info["sha256"])
        self.assertTrue(self.info["apkUrl"].endswith("/air-v1009/AIR.apk"))
    def test_wrong_package_abi_and_debug_apks_are_rejected(self):
        for badging in (BADGING.replace("dev.air.browser", "dev.other"), BADGING.replace("arm64-v8a", "x86_64"),
                        BADGING + "application-debuggable\n"):
            with self.assertRaises(ValueError):
                release_manifest.from_badging(badging, self.apk)
    def test_actual_apk_sms_permissions_prevent_release_manifest_generation(self):
        for permission in ("RECEIVE_SMS", "READ_SMS", "SEND_SMS"):
            for declaration in ("uses-permission", "uses-permission-sdk-23"):
                badging = BADGING + f"{declaration}: name='android.permission.{permission}'\n"
                with self.assertRaisesRegex(ValueError, "must not declare SMS permissions"):
                    release_manifest.from_badging(badging, self.apk)
    def test_corrupt_metadata_and_modified_apk_are_rejected(self):
        for field, value in (("versionCode", True), ("sizeBytes", self.info["sizeBytes"] + 1),
                             ("schemaVersion", True), ("apkUrl", "https://example.com/AIR.apk")):
            info = dict(self.info); info[field] = value
            with self.assertRaises(ValueError): release_manifest.validate(info, self.apk)
        self.apk.write_bytes(b"changed APK")
        with self.assertRaises(ValueError): release_manifest.validate(self.info, self.apk)

    def fake_github(self, existing=None, latest_code=0, fail_upload=False):
        state = {"release": copy.deepcopy(existing), "calls": [], "latest": latest_code, "fail_upload": fail_upload}
        def command(arguments, allow_missing=False):
            state["calls"].append(arguments)
            if arguments[0] == "api":
                if arguments[1].endswith("/latest"):
                    return {"tag_name": f"air-v{state['latest']}"} if state["latest"] else None
                return state["release"]
            action = arguments[1]
            if action == "create":
                self.assertIn("--draft", arguments)
                state["release"] = {"draft": True, "target_commitish": COMMIT, "assets": []}
            elif action == "upload":
                self.assertTrue(state["release"]["draft"], "Public assets must never be replaced")
                if state["fail_upload"]: raise RuntimeError("Interrupted upload")
                state["release"]["assets"] = [{"name": name} for name in ("AIR.apk", "update.json", "SHA256SUMS")]
            elif action == "edit":
                self.assertEqual(3, len(state["release"]["assets"]))
                state["release"]["draft"] = False
                if "--latest=true" in arguments: state["latest"] = 1009
            return ""
        return state, command

    def test_release_is_public_only_after_all_assets_are_uploaded(self):
        state, command = self.fake_github()
        publish_release.publish(self.directory, COMMIT, release_manifest.REPOSITORY, command)
        self.assertFalse(state["release"]["draft"])
        self.assertEqual(1009, state["latest"])
        self.assertEqual(["create", "upload", "edit"], [c[1] for c in state["calls"] if c[0] == "release"])
    def test_interrupted_upload_stays_private_and_retry_finishes_draft(self):
        state, command = self.fake_github(fail_upload=True)
        with self.assertRaises(RuntimeError): publish_release.publish(self.directory, COMMIT, release_manifest.REPOSITORY, command)
        self.assertTrue(state["release"]["draft"])
        state["fail_upload"] = False
        publish_release.publish(self.directory, COMMIT, release_manifest.REPOSITORY, command)
        self.assertFalse(state["release"]["draft"])
        self.assertEqual(1, sum(c[:2] == ["release", "create"] for c in state["calls"]))
    def test_old_late_workflow_cannot_move_latest_backwards(self):
        state, command = self.fake_github(latest_code=1010)
        publish_release.publish(self.directory, COMMIT, release_manifest.REPOSITORY, command)
        self.assertEqual(1010, state["latest"])
        self.assertIn("--latest=false", state["calls"][-1])
    def test_rerun_preserves_existing_public_assets(self):
        public = {"draft": False, "target_commitish": COMMIT,
                  "assets": [{"name": name} for name in ("AIR.apk", "update.json", "SHA256SUMS")]}
        state, command = self.fake_github(existing=public)
        publish_release.publish(self.directory, COMMIT, release_manifest.REPOSITORY, command)
        self.assertEqual(public, state["release"])
        self.assertFalse(any(c[0] == "release" for c in state["calls"]))
    def test_foreign_draft_and_invalid_checksums_stop_publication(self):
        state, command = self.fake_github(existing={"draft": True, "target_commitish": "b" * 40})
        with self.assertRaises(ValueError): publish_release.publish(self.directory, COMMIT, release_manifest.REPOSITORY, command)
        self.assertFalse(any(c[0] == "release" for c in state["calls"]))
        (self.directory / "SHA256SUMS").write_text("incorrect")
        state, command = self.fake_github()
        with self.assertRaises(ValueError): publish_release.publish(self.directory, COMMIT, release_manifest.REPOSITORY, command)
        self.assertEqual([], state["calls"])
    def test_permission_failures_are_not_treated_as_missing_releases(self):
        from subprocess import CompletedProcess
        with patch("publish_release.subprocess.run", return_value=CompletedProcess([], 1, "", "HTTP 403")):
            with self.assertRaises(RuntimeError): publish_release.gh(["api", "fixture"], allow_missing=True)
        with patch("publish_release.subprocess.run", return_value=CompletedProcess([], 1, "", "HTTP 404")):
            self.assertIsNone(publish_release.gh(["api", "fixture"], allow_missing=True))


if __name__ == "__main__":
    unittest.main()
