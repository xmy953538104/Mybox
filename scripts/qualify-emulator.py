#!/usr/bin/env python3
"""Check startup errors, request HOME once, then observe CI readiness without recovery attempts."""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ERROR_IDS = {"android:id/aerr_" + name for name in ("close", "wait", "restart", "report")}


def system_error(xml):
    if xml.strip() in ("ERROR: null root node returned by UiTestAutomationBridge.", "ERROR: could not get idle state."):
        return None
    root = ET.fromstring(xml[xml.index("<?xml"):xml.index("</hierarchy>") + len("</hierarchy>")])
    return any(n.get("package") == "android" and n.get("resource-id") in ERROR_IDS for n in root.iter("node"))


def anr_processes(log):
    return sorted(set(re.findall(r"\bANR in ([A-Za-z0-9_.:]+)(?=\s|$)", log)))


def home_focused(windows, home):
    if 'mCurrentFocus=' not in windows:
        raise ValueError('Window dump does not contain the actual focus field')
    match = re.search(r'mCurrentFocus=Window\{[^\n]*\s([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)(?:\s|\})', windows)
    def expanded(component):
        package, name = component.split('/', 1)
        return package, package + name if name.startswith('.') else name
    return bool(match and expanded(match.group(1)) == expanded(home))


def self_test():
    xml = '<?xml version="1.0"?><hierarchy><node package="android" resource-id="android:id/aerr_wait"/></hierarchy>'
    assert system_error(xml)
    assert system_error('ERROR: null root node returned by UiTestAutomationBridge.\\n'.replace('\\n', '\n')) is None
    assert system_error('ERROR: could not get idle state.') is None
    assert not system_error(xml.replace('package="android"', 'package="test.app"'))
    assert not system_error(xml.replace('aerr_wait', 'button1'))
    assert anr_processes('ActivityManager: ANR in com.example.launcher\ntext: ANR in pkg:service\n') == ['com.example.launcher', 'pkg:service']
    assert not anr_processes('ANR in invalid/package')
    assert home_focused(' mCurrentFocus=Window{abc u0 com.example/.Home}', 'com.example/.Home')
    assert not home_focused(' mCurrentFocus=Window{abc u0 com.example/.HomeOther}', 'com.example/.Home')
    assert not home_focused(' mCurrentFocus=null\n mFocusedApp=ActivityRecord{abc com.example/.Home}', 'com.example/.Home')
    try:
        home_focused('WINDOW MANAGER WINDOWS\nWindow #0 Window{abc com.example/.Home}', 'com.example/.Home')
        raise AssertionError('A dump without focus metadata must not be treated as an unfocused HOME')
    except ValueError:
        pass
    assert home_focused(' mCurrentFocus=Window{abc u0 com.example/com.example.Home}', 'com.example/.Home')
    print('Emulator qualification parser self-check passed; no device accessed.')


def main(serial, output):
    if not re.fullmatch(r'emulator-[0-9]+', serial):
        raise ValueError('Only an explicitly selected emulator is permitted')
    output.mkdir(parents=True, exist_ok=True)
    result = {'schema': 1, 'passed': False, 'startedAtMs': int(time.time() * 1000),
              'stableFocusSeconds': 15, 'readinessBudgetSeconds': 90, 'samples': []}
    deadline = time.monotonic() + 90

    def adb(*args, binary=False):
        budget = 15 if binary else min(15, deadline - time.monotonic())
        if budget <= 0:
            raise TimeoutError('Startup readiness budget expired')
        reply = subprocess.run(['adb', '-s', serial, *args], stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=budget, check=True)
        return reply.stdout if binary else reply.stdout.decode('utf-8', errors='replace')

    try:
        result['initialUiAttempts'] = []
        while time.monotonic() < deadline:
            initial_log = adb('logcat', '-d', '-v', 'threadtime', 'ActivityManager:I', '*:S')
            (output / 'startup-initial-system-log.txt').write_text(initial_log, encoding='utf-8')
            result['initialAnrProcesses'] = anr_processes(initial_log)
            if result['initialAnrProcesses']:
                raise RuntimeError('Recorded startup ANR; no input sent')
            provisioned = adb('shell', 'settings', 'get', 'global', 'device_provisioned').strip() == '1'
            user_ready = adb('shell', 'settings', 'get', 'secure', 'user_setup_complete').strip() == '1'
            attempt = {'atMs': int(time.time()*1000), 'deviceProvisioned': provisioned,
                       'userSetupComplete': user_ready, 'uiRootAvailable': False}
            result['initialUiAttempts'].append(attempt)
            if provisioned and user_ready:
                home = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a',
                           'android.intent.action.MAIN', '-c', 'android.intent.category.HOME').strip().splitlines()[-1]
                if not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', home):
                    raise RuntimeError('No unambiguous HOME component')
                initial_xml = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
                (output / ('startup-initial-ui-attempt-' + str(len(result['initialUiAttempts'])) + '.xml')).write_text(initial_xml, encoding='utf-8')
                error = system_error(initial_xml)
                attempt['uiRootAvailable'] = error is not None
                attempt['systemErrorDialog'] = error
                result['initialSystemErrorDialog'] = error
                if error is True:
                    raise RuntimeError('Startup app-error controls; no input sent')
                if error is False:
                    root = ET.fromstring(initial_xml[initial_xml.index('<?xml'):initial_xml.index('</hierarchy>') + len('</hierarchy>')])
                    if any(node.get('package') == home.split('/')[0] for node in root.iter('node')):
                        result['homeComponent'] = home
                        result['initialSystemErrorDialog'] = False
                        (output / 'startup-initial-ui.xml').write_text(initial_xml, encoding='utf-8')
                        break
            time.sleep(3)
        else:
            raise RuntimeError('Initial HOME UI did not become available within the readiness budget')
        # The runner unlock key can leave HOME's ordinary menu open; establish the initial screen once.
        adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        result['initialHomeRequested'] = True
        stable = None
        while time.monotonic() < deadline:
            boot = adb('shell', 'getprop', 'sys.boot_completed').strip() == '1'
            windows = adb('shell', 'dumpsys', 'window', 'displays')
            log = adb('logcat', '-d', '-v', 'threadtime', 'ActivityManager:I', '*:S')
            (output / 'startup-window.txt').write_text(windows, encoding='utf-8')
            (output / 'startup-system-log.txt').write_text(log, encoding='utf-8')
            failures = anr_processes(log)
            focused = home_focused(windows, home)
            result['samples'].append({'atMs': int(time.time() * 1000), 'bootComplete': boot,
                                      'homeFocused': focused, 'anrProcesses': failures})
            if failures:
                raise RuntimeError('Recorded startup ANR; no dismissal or recovery attempted')
            if boot and focused:
                if stable is None:
                    stable = time.monotonic()
                if time.monotonic() - stable >= 15:
                    xml = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
                    (output / 'startup-ui.xml').write_text(xml, encoding='utf-8')
                    if system_error(xml) is not False:
                        raise RuntimeError('System app-error controls or unavailable final UI capture')
                    # UI collection may itself overlap a newly reported ANR; retain that final sample too.
                    final_log = adb('logcat', '-d', '-v', 'threadtime', 'ActivityManager:I', '*:S')
                    final_windows = adb('shell', 'dumpsys', 'window', 'displays')
                    (output / 'startup-final-system-log.txt').write_text(final_log, encoding='utf-8')
                    (output / 'startup-final-window.txt').write_text(final_windows, encoding='utf-8')
                    if anr_processes(final_log) or not home_focused(final_windows, home):
                        raise RuntimeError('System readiness changed during UI collection')
                    result['passed'] = True
                    return
            else:
                stable = None
            time.sleep(3)
        raise RuntimeError('HOME did not retain focus within the startup budget')
    except BaseException as failure:
        result['failureType'] = type(failure).__name__
        raise
    finally:
        try:
            (output / 'startup-screen.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
        except Exception as capture:
            result['screenshotFailureType'] = type(capture).__name__
            result['passed'] = False
        result['finishedAtMs'] = int(time.time() * 1000)
        (output / 'startup-qualification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        if not result['passed'] and sys.exc_info()[0] is None:
            raise RuntimeError('Startup qualification evidence was not captured')


if __name__ == '__main__':
    if sys.argv[1:] == ['--self-test']:
        self_test()
    elif len(sys.argv) == 3:
        main(sys.argv[1], Path(sys.argv[2]))
    else:
        raise SystemExit('Usage: qualify-emulator.py --self-test | emulator-N OUTPUT')
