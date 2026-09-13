#!/usr/bin/env python3
"""Fail-closed host runner for physical Galaxy Z Fold6 baseline QA.

Requires the target app and instrumentation APK already installed. The primary
Android user's external files directory is used. Every invocation preserves a
new evidence directory; old local or remote artifacts cannot satisfy a run.
"""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import uuid


PACKAGE = "com.sleepysoong.autobandselector"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/qa/baseline"
FILES = ("result.json", "screen.png", "hierarchy.xml")
ASSERTIONS = {"menu", "sim_mapping", "registered_band", "automatic_restore"}
ADB_FALLBACK = Path("/root/android-sdk/platform-tools/adb")


class QaError(Exception):
    """A QA prerequisite or result was not verified."""


def parse_devices(output):
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines or lines[0] != "List of devices attached":
        raise QaError("Malformed adb device enumeration")
    devices = []
    for line in lines[1:]:
        fields = line.split()
        if len(fields) < 2 or fields[1] not in {"device", "unauthorized", "offline"}:
            raise QaError("Malformed adb device entry")
        if any(":" not in field for field in fields[2:]):
            raise QaError("Malformed adb device metadata")
        devices.append((fields[0], fields[1]))
    if len(devices) != 1 or devices[0][1] != "device":
        raise QaError("Exactly one authorized physical device is required")
    return devices[0][0]


def locate_adb():
    adb = shutil.which("adb")
    if adb:
        return adb
    if ADB_FALLBACK.is_file() and os.access(ADB_FALLBACK, os.X_OK):
        return str(ADB_FALLBACK)
    raise QaError("adb not found on PATH or at the SDK fallback")


class Adb:
    def __init__(self, executable, timeout=30):
        self.executable = str(executable)
        self.timeout = timeout
        self.calls = []

    def run(self, *args, timeout=None):
        entry = {"args": list(args)}
        self.calls.append(entry)
        try:
            result = subprocess.run(
                [self.executable, *args], capture_output=True, text=True,
                timeout=self.timeout if timeout is None else timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            # subprocess.run kills and reaps its child before raising.
            entry["timed_out"] = True
            for name in ("stdout", "stderr"):
                value = getattr(exc, name) or ""
                entry[name] = value.decode(errors="replace") if isinstance(value, bytes) else value
            raise QaError("adb command timed out") from exc
        except OSError as exc:
            entry["error"] = str(exc)
            raise QaError(f"adb could not execute: {exc}") from exc
        entry.update(returncode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        if result.returncode:
            raise QaError(f"adb command exited {result.returncode}: {result.stderr.strip()}")
        return result.stdout


def verify_instrumentation(output):
    codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE:\s*(-?\d+)\s*$", output, re.M)
    final = re.findall(r"^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$", output, re.M)
    if (not codes or "0" not in codes or any(code not in {"0", "1", "2"} for code in codes)
            or final != ["-1"]
            or not re.search(r"^OK \([1-9]\d* tests?\)\s*$", output, re.M)
            or re.search(r"INSTRUMENTATION_FAILED|FAILURES!!!|\b(?:failed|skipped|ignored|assumption failure)\b",
                         output, re.I)):
        raise QaError("Instrumentation failed, skipped, or did not prove test completion")


def verify_result(directory):
    directory = Path(directory)
    for name in FILES:
        path = directory / name
        if not path.is_file() or path.stat().st_size == 0:
            raise QaError(f"Missing or empty artifact: {name}")
    try:
        result = json.loads((directory / "result.json").read_text(encoding="utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise QaError("Invalid result JSON") from exc
    if (not isinstance(result, dict) or type(result.get("schema_version")) is not int
            or result["schema_version"] != 1 or result.get("scenario") != "baseline"
            or result.get("status") != "passed"):
        raise QaError("Result schema, scenario, or status is not verified")
    assertions = result.get("assertions")
    if isinstance(assertions, list):
        mapped = {}
        for item in assertions:
            if (not isinstance(item, dict) or not isinstance(item.get("name"), str)
                    or item["name"] in mapped):
                raise QaError("Malformed or duplicate assertion")
            mapped[item["name"]] = item.get("passed")
        assertions = mapped
    if (not isinstance(assertions, dict) or not ASSERTIONS.issubset(assertions)
            or any(value is not True for value in assertions.values())):
        raise QaError("All named assertions must be explicitly true")
    cleanup = result.get("cleanup")
    if not isinstance(cleanup, dict) or cleanup.get("status") != "complete":
        raise QaError("Native cleanup is not complete")
    return result


def baseline(adb, evidence):
    serial = parse_devices(adb.run("devices", "-l"))
    if serial.startswith("emulator-"):
        raise QaError("Emulators are not physical Fold6 devices")
    model = adb.run("-s", serial, "shell", "getprop", "ro.product.model").strip()
    if not re.fullmatch(r"SM-F956(?:B|N|U1?|W|0)(?:/DS)?", model):
        raise QaError(f"Not a supported Galaxy Z Fold6 model: {model!r}")
    if adb.run("-s", serial, "shell", "getprop", "ro.kernel.qemu").strip() == "1":
        raise QaError("Emulators are not physical Fold6 devices")
    # Remove only this scenario's three artifacts, then prove absence before
    # instrumentation. Fresh local storage also prevents a no-op pull passing.
    paths = [f"{REMOTE}/{name}" for name in FILES]
    adb.run("-s", serial, "shell", "rm", "-f", *paths)
    adb.run("-s", serial, "shell", " && ".join(f"test ! -e {path}" for path in paths))
    output = adb.run(
        "-s", serial, "shell", "am", "instrument", "-w", "-r",
        "-e", "class", f"{PACKAGE}.qa.NativeBandQa",
        "-e", "scenario", "baseline",
        f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner", timeout=180,
    )
    (evidence / "instrumentation.txt").write_text(output, encoding="utf-8")
    verify_instrumentation(output)
    artifacts = evidence / "baseline"
    artifacts.mkdir()  # Never reuse a previous run's artifacts.
    for name in FILES:
        adb.run("-s", serial, "pull", f"{REMOTE}/{name}", str(artifacts / name))
    verify_result(artifacts)
    return {"serial": serial, "model": model}


def preflight_negative(evidence):
    """Run the real subprocess path without accessing any actual adb/device."""
    audit_path = evidence / "fake-adb-calls.jsonl"
    with tempfile.TemporaryDirectory(prefix="band-qa-fake-adb-") as temporary:
        executable = Path(temporary) / "adb"
        executable.write_text(
            f"#!{sys.executable}\nimport json, sys\n"
            f"with open({str(audit_path)!r}, 'a') as audit:\n"
            "    audit.write(json.dumps(sys.argv[1:]) + '\\n')\n"
            "if sys.argv[1:] != ['devices', '-l']:\n"
            "    sys.exit(90)\n"
            "print('List of devices attached\\n')\n", encoding="utf-8",
        )
        executable.chmod(0o700)
        adb = Adb(executable)
        rejection = None
        try:
            baseline(adb, evidence)
        except QaError as exc:
            rejection = str(exc)
        finally:
            (evidence / "adb-calls.json").write_text(json.dumps(adb.calls, indent=2) + "\n")
        calls = [json.loads(line) for line in audit_path.read_text().splitlines()]
    proof = {
        "status": "rejected" if rejection else "failed",
        "reason": rejection,
        "calls": calls,
        "no_device_calls": calls == [["devices", "-l"]],
        "no_shell_or_instrument_calls": not any("shell" in call or "instrument" in call for call in calls),
        "temporary_directory": temporary,
        "temporary_directory_removed": not Path(temporary).exists(),
    }
    (evidence / "negative-proof.json").write_text(json.dumps(proof, indent=2) + "\n")
    if not (rejection and proof["no_device_calls"] and proof["no_shell_or_instrument_calls"]
            and proof["temporary_directory_removed"]):
        raise QaError("Negative preflight audit did not prove safe rejection and cleanup")
    raise QaError(f"Expected negative preflight rejection: {rejection}")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=("baseline", "preflight-negative"))
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args(argv)
    evidence = args.evidence.resolve() / f"run-{uuid.uuid4().hex}"
    report = {"scenario": args.scenario, "status": "rejected"}
    adb = None
    try:
        evidence.mkdir(parents=True)
        if args.scenario == "preflight-negative":
            preflight_negative(evidence)
        else:
            adb = Adb(locate_adb())
            report.update(baseline(adb, evidence))
            report["status"] = "passed"
    except (QaError, OSError) as exc:
        report["reason"] = str(exc)
    try:
        if adb is not None:
            (evidence / "adb-calls.json").write_text(json.dumps(adb.calls, indent=2) + "\n")
        (evidence / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    except OSError as exc:
        print(f"Cannot preserve evidence: {exc}", file=sys.stderr)
        return 1
    print(json.dumps({**report, "evidence": str(evidence)}))
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
