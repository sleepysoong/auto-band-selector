"""Stdlib unit and subprocess integration tests; all devices here are fakes."""

import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import band_qa as qa


HEADER = "List of devices attached\n"
SUCCESS = "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
VALID = {
    "schema_version": 1, "scenario": "baseline", "status": "passed",
    "assertions": {name: True for name in qa.ASSERTIONS},
    "cleanup": {"status": "complete"},
}
SCRIPT = Path(qa.__file__).resolve()


class DeviceTests(unittest.TestCase):
    def test_authorized_plus_unauthorized_is_rejected(self):
        with self.assertRaises(qa.QaError):
            qa.parse_devices(HEADER + "fold device model:SM_F956B\nother unauthorized\n")

    def test_parse_authorized_device(self):
        self.assertEqual(qa.parse_devices(HEADER + "\nfold\tdevice usb:1-1 product:q6 model:SM_F956B transport_id:2\n"), "fold")

    def test_reject_invalid_enumerations(self):
        for output in (
            "", "fold device\n", HEADER, HEADER + "fold unauthorized\n",
            HEADER + "fold offline\n", HEADER + "fold device\nother device\n",
            HEADER + "fold device\nfold device\n", HEADER + "fold\n",
            HEADER + "fold unknown\n", HEADER + "fold device garbage\n",
            HEADER + "fold no permissions (udev)\n",
        ):
            with self.subTest(output=output), self.assertRaises(qa.QaError):
                qa.parse_devices(output)

    def test_adb_path_and_fallback(self):
        with patch.object(qa.shutil, "which", return_value="/path/adb"):
            self.assertEqual(qa.locate_adb(), "/path/adb")
        with tempfile.TemporaryDirectory() as directory:
            fallback = Path(directory) / "adb"
            fallback.write_text("#!/bin/sh\nexit 0\n")
            fallback.chmod(0o700)
            with patch.object(qa.shutil, "which", return_value=None), patch.object(qa, "ADB_FALLBACK", fallback):
                self.assertEqual(qa.locate_adb(), str(fallback))
                fallback.unlink()
                with self.assertRaises(qa.QaError):
                    qa.locate_adb()


class ResultTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        for name in qa.FILES:
            (self.directory / name).write_bytes(b"nonempty-test-artifact")
        self.write_result(VALID)

    def write_result(self, result):
        (self.directory / "result.json").write_text(json.dumps(result))

    def test_valid_result(self):
        self.assertEqual(qa.verify_result(self.directory), VALID)

    def test_named_list_assertions(self):
        result = copy.deepcopy(VALID)
        result["assertions"] = [{"name": name, "passed": True} for name in qa.ASSERTIONS]
        self.write_result(result)
        self.assertEqual(qa.verify_result(self.directory), result)
        result["assertions"].append(result["assertions"][0])
        self.write_result(result)
        with self.assertRaises(qa.QaError):
            qa.verify_result(self.directory)

    def test_invalid_result_fields(self):
        for key, value in (
            ("schema_version", True), ("schema_version", 2), ("schema_version", None),
            ("scenario", "other"), ("status", "failed"), ("status", "skipped"),
            ("status", "unverified"), ("assertions", {}), ("assertions", None),
            ("assertions", [None]), ("assertions", [{"name": [], "passed": True}]),
            ("cleanup", None), ("cleanup", {"status": "passed"}),
            ("cleanup", {"status": "unverified"}),
        ):
            with self.subTest(key=key, value=value):
                result = copy.deepcopy(VALID)
                result[key] = value
                self.write_result(result)
                with self.assertRaises(qa.QaError):
                    qa.verify_result(self.directory)
        for value in (False, None, "unknown", "true", 1):
            with self.subTest(assertion=value):
                result = copy.deepcopy(VALID)
                result["assertions"]["menu"] = value
                self.write_result(result)
                with self.assertRaises(qa.QaError):
                    qa.verify_result(self.directory)
        result = copy.deepcopy(VALID)
        del result["assertions"]["automatic_restore"]
        self.write_result(result)
        with self.assertRaises(qa.QaError):
            qa.verify_result(self.directory)

    def test_invalid_json_and_missing_or_empty_files(self):
        for data in (b"not json", b"[]", b"null", b"\xff"):
            with self.subTest(data=data):
                (self.directory / "result.json").write_bytes(data)
                with self.assertRaises(qa.QaError):
                    qa.verify_result(self.directory)
        self.write_result(VALID)
        for name in qa.FILES:
            path = self.directory / name
            original = path.read_bytes()
            for absent in (False, True):
                with self.subTest(name=name, absent=absent):
                    path.write_bytes(b"")
                    if absent:
                        path.unlink()
                    with self.assertRaises(qa.QaError):
                        qa.verify_result(self.directory)
            path.write_bytes(original)

    def test_instrumentation_requires_positive_completion(self):
        qa.verify_instrumentation(SUCCESS)
        for output in (
            "", "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n",
            SUCCESS.replace("CODE: 0", "CODE: -2"),
            SUCCESS.replace("CODE: 0", "CODE: -3"),
            SUCCESS.replace("CODE: 0", "CODE: -4"),
            SUCCESS.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0"),
            SUCCESS + "INSTRUMENTATION_FAILED: error\n", SUCCESS + "FAILURES!!!\n",
            SUCCESS + "skipped\n", SUCCESS + "ignored\n",
            SUCCESS.replace("OK (1 test)", ""),
        ):
            with self.subTest(output=output), self.assertRaises(qa.QaError):
                qa.verify_instrumentation(output)


class SubprocessTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="band-qa-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.evidence = self.root / "evidence"
        self.audit = self.root / "calls.jsonl"
        self.config = self.root / "config.json"
        self.fake = self.root / "adb"
        self.fake.write_text(
            f"#!{sys.executable}\n"
            "import json, pathlib, sys\n"
            f"config = json.loads(pathlib.Path({str(self.config)!r}).read_text())\n"
            f"with open({str(self.audit)!r}, 'a') as audit:\n"
            "    audit.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "args = sys.argv[1:]\n"
            "if args == ['devices', '-l']:\n"
            "    print(config['devices'])\n"
            "elif args[2:5] == ['shell', 'getprop', 'ro.product.model']:\n"
            "    print(config.get('model', 'SM-F956B'))\n"
            "elif args[2:5] == ['shell', 'getprop', 'ro.kernel.qemu']:\n"
            "    print(config.get('qemu', '0'))\n"
            "elif args[2:5] == ['shell', 'rm', '-f']:\n"
            "    sys.exit(config.get('clear_exit', 0))\n"
            "elif args[2] == 'shell' and args[3].startswith('test ! -e '):\n"
            "    sys.exit(config.get('absence_exit', 0))\n"
            "elif args[2:5] == ['shell', 'am', 'instrument']:\n"
            "    print(config['instrumentation'])\n"
            "    sys.exit(config.get('instrument_exit', 0))\n"
            "elif args[2] == 'pull':\n"
            "    name = pathlib.Path(args[3]).name\n"
            "    if name not in config.get('omit', []):\n"
            "        data = json.dumps(config['result']) if name == 'result.json' else 'fake-artifact'\n"
            "        pathlib.Path(args[4]).write_text(data)\n"
            "else:\n"
            "    sys.exit(91)\n"
        )
        self.fake.chmod(0o700)

    def run_cli(self, scenario="baseline", **changes):
        config = {"devices": HEADER + "fold device model:SM_F956B\n",
                  "instrumentation": SUCCESS, "result": VALID}
        config.update(changes)
        self.config.write_text(json.dumps(config))
        if self.audit.exists():
            self.audit.unlink()
        completed = subprocess.run(
            [sys.executable, str(SCRIPT), scenario, "--evidence", str(self.evidence)],
            capture_output=True, text=True, timeout=10,
            env={**os.environ, "PATH": str(self.root) + os.pathsep + os.environ.get("PATH", "")},
        )
        self.assertEqual(completed.stderr, "")
        report = json.loads(completed.stdout)
        saved = json.loads((Path(report["evidence"]) / "report.json").read_text())
        self.assertEqual(saved["status"], report["status"])
        calls = [json.loads(line) for line in self.audit.read_text().splitlines()] if self.audit.exists() else []
        return completed, report, calls

    def test_baseline_full_subprocess_path(self):
        completed, report, calls = self.run_cli()
        self.assertEqual(completed.returncode, 0)
        self.assertEqual(report["status"], "passed")
        instrument = next(call for call in calls if "instrument" in call)
        self.assertEqual(instrument, [
            "-s", "fold", "shell", "am", "instrument", "-w", "-r", "-e", "class",
            qa.PACKAGE + ".qa.NativeBandQa", "-e", "scenario", "baseline",
            qa.PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner",
        ])
        clear_index = next(i for i, call in enumerate(calls) if "rm" in call)
        self.assertLess(clear_index, calls.index(instrument))
        self.assertTrue(calls[clear_index + 1][3].startswith("test ! -e "))
        pulls = [call for call in calls if "pull" in call]
        self.assertEqual([call[3] for call in pulls], [qa.REMOTE + "/" + name for name in qa.FILES])
        self.assertEqual(qa.verify_result(Path(report["evidence"]) / "baseline"), VALID)

    def test_cli_device_rejections_never_instrument(self):
        for changes in (
            {"devices": HEADER}, {"devices": HEADER + "fold unauthorized\n"},
            {"devices": HEADER + "one device\ntwo device\n"}, {"devices": "bad"},
            {"devices": HEADER + "emulator-5554 device\n"},
            {"model": "SM-S928B"}, {"model": "SM-F956FAKE"}, {"qemu": "1"},
        ):
            with self.subTest(changes=changes):
                completed, report, calls = self.run_cli(**changes)
                self.assertEqual(completed.returncode, 1)
                self.assertEqual(report["status"], "rejected")
                self.assertFalse(any("instrument" in call or "rm" in call or "pull" in call for call in calls))
                if "devices" in changes:
                    self.assertEqual(calls, [["devices", "-l"]])

    def test_stale_artifacts_and_invalid_instrumentation_rejected(self):
        for changes in (
            {"clear_exit": 1}, {"absence_exit": 1}, {"instrument_exit": 1},
            {"instrumentation": SUCCESS.replace("CODE: 0", "CODE: -3")},
        ):
            with self.subTest(changes=changes):
                completed, report, calls = self.run_cli(**changes)
                self.assertEqual(completed.returncode, 1)
                self.assertEqual(report["status"], "rejected")
                self.assertFalse(any("pull" in call for call in calls))
                if "clear_exit" in changes or "absence_exit" in changes:
                    self.assertFalse(any("instrument" in call for call in calls))

    def test_cli_missing_invalid_and_old_local_results_rejected(self):
        _, previous, _ = self.run_cli()
        previous_directory = Path(previous["evidence"])
        for changes in ({"omit": list(qa.FILES)}, {"omit": ["screen.png"]},
                        {"result": {**VALID, "status": "unverified"}}):
            with self.subTest(changes=changes):
                completed, report, _ = self.run_cli(**changes)
                self.assertEqual(completed.returncode, 1)
                self.assertNotEqual(Path(report["evidence"]), previous_directory)
                self.assertEqual(qa.verify_result(previous_directory / "baseline"), VALID)

    def test_negative_cli_preserves_audit_and_cleans_fake(self):
        completed, report, calls = self.run_cli("preflight-negative")
        self.assertEqual(completed.returncode, 1)
        self.assertEqual(report["status"], "rejected")
        self.assertEqual(calls, [])  # It must not use even our PATH adb.
        directory = Path(report["evidence"])
        proof = json.loads((directory / "negative-proof.json").read_text())
        self.assertEqual(proof["calls"], [["devices", "-l"]])
        self.assertTrue(proof["no_device_calls"])
        self.assertTrue(proof["no_shell_or_instrument_calls"])
        self.assertTrue(proof["temporary_directory_removed"])
        self.assertFalse(Path(proof["temporary_directory"]).exists())
        self.assertEqual(len(json.loads((directory / "adb-calls.json").read_text())), 1)

    def test_timeout_kills_and_reaps_real_subprocess(self):
        # Time itself is under test. No sleeps/polling: child blocks on a signal.
        self.fake.write_text(f"#!{sys.executable}\nimport os, signal\nprint(os.getpid(), flush=True)\nsignal.pause()\n")
        adb = qa.Adb(self.fake, timeout=2)
        with self.assertRaises(qa.QaError):
            adb.run("devices", "-l")
        self.assertTrue(adb.calls[0]["timed_out"])
        pid = int(adb.calls[0]["stdout"].strip())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)
        with self.assertRaises(ChildProcessError):
            os.waitpid(pid, os.WNOHANG)


if __name__ == "__main__":
    unittest.main()
