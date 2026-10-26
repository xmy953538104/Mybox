#!/usr/bin/env python3
"""Exercise the original Android-persisted job across a real emulator reboot, without resubmission."""
import json
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

HOST = "io.github.xmy953538104.mybox"
FIXTURE = HOST + ".fixture"
FILES = "files/vlite/user/0/" + FIXTURE + "/files/"


def fixture_button(root):
    buttons = [node for node in root.iter("node") if node.get("text", "").casefold() == FIXTURE
               and node.get("clickable") == "true" and node.get("enabled") == "true"]
    if len(buttons) > 1:
        raise RuntimeError("Multiple exact fixture buttons make the UI target ambiguous")
    if not buttons:
        return None
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", buttons[0].get("bounds", ""))
    if not match:
        raise RuntimeError("The exact fixture button has invalid bounds")
    x1, y1, x2, y2 = map(int, match.groups())
    return (x1, y1, x2, y2) if x2 > x1 and y2 > y1 else None


def self_test():
    def node(text, bounds="[10,20][110,70]", **extra):
        return ET.Element("node", {"text": text, "clickable": "true", "enabled": "true", "bounds": bounds, **extra})
    root = ET.Element("hierarchy"); root.append(node(FIXTURE.upper()))
    assert fixture_button(root) == (10, 20, 110, 70)
    root[0].set("text", FIXTURE + ".other"); assert fixture_button(root) is None
    root[0].set("text", FIXTURE); root[0].set("enabled", "false"); assert fixture_button(root) is None
    root[0].set("enabled", "true"); root[0].set("bounds", "[0,0][0,0]"); assert fixture_button(root) is None
    root[0].set("bounds", "[10,20][110,70]"); root.append(node(FIXTURE))
    try:
        fixture_button(root)
        raise AssertionError("Duplicate UI target accepted")
    except RuntimeError:
        pass
    root.remove(root[1]); root[0].set("bounds", "malformed")
    try:
        fixture_button(root)
        raise AssertionError("Invalid UI bounds accepted")
    except RuntimeError:
        pass
    print("Exact visible fixture selector checks passed; no ADB or reboot was invoked")


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r"emulator-\d+", sys.argv[1]):
        raise ValueError("An explicit emulator serial is required; physical devices are never selected")
    target = sys.argv[1]
    output = Path("work/runtime")
    output.mkdir(parents=True, exist_ok=True)

    def adb(*args, required=True, timeout=15):
        result = subprocess.run(["adb", "-s", target, *args], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=timeout)
        if required and result.returncode:
            raise RuntimeError("ADB operation failed: " + " ".join(args[:3]))
        return result.stdout if result.returncode == 0 else ""

    def report(name):
        value = adb("shell", "run-as", HOST, "cat", FILES + name, required=False).strip()
        return json.loads(value) if value.startswith("{") else None

    def jobs():
        value = adb("shell", "dumpsys", "jobscheduler")
        return re.findall(r"(?ms)^  JOB #[^\n]+\n.*?(?=^  JOB #|^[^ \n]|\Z)", value)

    if adb("get-state").strip() != "device":
        raise RuntimeError("Emulator is not online")
    previous = report("job-persisted-scheduled.json") or {}
    adb("shell", "am", "force-stop", HOST)
    mode = output / "persisted-job-mode.txt"
    mode.write_text("jobs-persisted", encoding="utf-8")
    remote = "/data/local/tmp/mylite-reboot-" + uuid.uuid4().hex
    adb("push", str(mode), remote)
    adb("shell", "run-as", HOST, "cp", remote, FILES + "fixture-mode")
    adb("shell", "am", "start", "-W", "-n", HOST + "/.MainActivity")
    ui = "/sdcard/mylite-reboot-" + uuid.uuid4().hex + ".xml"
    sizes = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    if not sizes:
        raise RuntimeError("Cannot measure emulator screen for the actual fixture UI")
    width, height = map(int, sizes[-1])
    bounds = None
    for attempt in range(7):
        adb("shell", "uiautomator", "dump", ui)
        xml = adb("shell", "cat", ui)
        bounds = fixture_button(ET.fromstring(xml))
        if bounds is not None:
            break
        if attempt < 6:
            adb("shell", "input", "swipe", str(width // 2), str(height * 4 // 5),
                str(width // 2), str(height // 3), "300")
    if bounds is None:
        (output / "persisted-job-missing-button.xml").write_text(xml, encoding="utf-8")
        screenshot = subprocess.run(["adb", "-s", target, "exec-out", "screencap", "-p"],
                                    capture_output=True, timeout=15)
        if screenshot.returncode == 0:
            (output / "persisted-job-missing-button.png").write_bytes(screenshot.stdout)
        raise RuntimeError("The exact installed fixture button is unavailable after bounded actual scrolling")
    x1, y1, x2, y2 = bounds
    if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
        raise RuntimeError("Fixture button is outside the actual emulator screen")
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    scheduled = None
    end = time.monotonic() + 15
    while time.monotonic() < end:
        candidate = report("job-persisted-scheduled.json")
        if candidate and candidate.get("nonce") != previous.get("nonce"):
            scheduled = candidate
            break
        time.sleep(0.1)
    if not scheduled or "failure" in scheduled or scheduled.get("boot", -1) < 0:
        raise RuntimeError("The fixture did not submit a fresh persistent job")
    # AOSP JobStore batches writes for two seconds; do not reboot before Android saves the job.
    time.sleep(3)
    owned = [job for job in jobs() if "Service: " + HOST + "/.engine.OsJobService" in job]
    if len(owned) != 1 or "PERSISTED" not in owned[0]:
        raise RuntimeError("No unique actual persistent OS job")
    physical = re.search(r"^  JOB #(\S+):", owned[0]).group(1)
    (output / "persisted-job-before.txt").write_text(owned[0], encoding="utf-8")
    if (report("job-persisted-executed.json") or {}).get("nonce") == scheduled["nonce"]:
        raise RuntimeError("The current job already ran before reboot; acceptance refused")
    adb("reboot")
    adb("wait-for-device", timeout=45)
    executed = None
    end = time.monotonic() + 90
    while time.monotonic() < end:
        if adb("shell", "getprop", "sys.boot_completed", required=False).strip() == "1":
            candidate = report("job-persisted-executed.json")
            if candidate and candidate.get("nonce") == scheduled["nonce"]:
                executed = candidate
                break
        time.sleep(1)
    if not executed or executed.get("boot", -1) <= scheduled["boot"] or executed.get("jobId") != 7010 or executed.get("pid", 0) <= 0:
        raise RuntimeError("Android did not cold-start and finish the original job after reboot")
    if any(job.startswith("  JOB #" + physical + ":") for job in jobs()):
        raise RuntimeError("The original completed OS job remains pending")
    if adb("shell", "pidof", HOST, required=False).strip():
        raise RuntimeError("The host UI process unexpectedly started after reboot")
    packages = adb("shell", "cmd", "package", "list", "packages", "-U", HOST)
    uid = re.search(r"(?m)^package:" + re.escape(HOST) + r" uid:(\d+)\s*$", packages).group(1)
    crash = adb("logcat", "-b", "crash", "-d", "--uid=" + uid, "-v", "threadtime", "GmsServicesProvider:S", "*:V")
    (output / "persisted-job-crash.txt").write_text(crash, encoding="utf-8")
    if re.search(r"FATAL EXCEPTION:|Fatal signal \d+|Abort message:", crash):
        raise RuntimeError("An owned process crashed after reboot")
    result = {"passed": True, "scheduled": scheduled, "executed": executed, "physicalJob": physical,
              "originalOsJobRemoved": True, "hostUiNotStarted": True}
    (output / "persisted-job-reboot.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        main()
