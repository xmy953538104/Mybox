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
    if '<?xml' not in xml or '</hierarchy>' not in xml:
        raise ValueError('UI hierarchy was not captured; startup safety is unknown')
    root = ET.fromstring(xml[xml.index("<?xml"):xml.index("</hierarchy>") + len("</hierarchy>")])
    if root.tag != "hierarchy" or next(root.iter("node"), None) is None:
        raise ValueError("UI hierarchy has no nodes; startup safety is unknown")
    return any(n.get("package") == "android" and n.get("resource-id") in ERROR_IDS for n in root.iter("node"))


def anr_processes(log):
    return sorted(set(re.findall(r"\bANR in ([A-Za-z0-9_.:]+)(?=\s|$)", log)))


def setup_ready(boot, provisioned, user_setup):
    return all(value.strip() == '1' for value in (boot, provisioned, user_setup))


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
    assert not system_error(xml.replace('package="android"', 'package="test.app"'))
    assert not system_error(xml.replace('aerr_wait', 'button1'))
    for missing in ['', 'ERROR: null root node returned by UiTestAutomationBridge.', '<hierarchy/>', '<?xml version="1.0"?><hierarchy></hierarchy>']:
        try:
            system_error(missing)
        except ValueError:
            pass
        else:
            raise AssertionError('Missing UI was accepted as safe startup')
    assert setup_ready('1\n', '1', '1')
    for flags in [('1', '0', '0'), ('1', '1', '0'), ('0', '1', '1'), ('1', '1', 'null')]:
        assert not setup_ready(*flags)
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
    # Exercise the complete gate offline; no real ADB call or temporary filesystem is used.
    import types
    from unittest.mock import patch

    class Output:
        def __init__(self, files=None, name=''):
            self.files = {} if files is None else files
            self.name = name
        def __truediv__(self, name): return Output(self.files, name)
        def mkdir(self, **kwargs): pass
        def write_text(self, value, **kwargs): self.files[self.name] = value
        def write_bytes(self, value): self.files[self.name] = value

    def gate_case(never_ready=False, anr=False):
        output = Output()
        now, captures, inputs, logs = [100.0], [], [], []
        valid = '<?xml version="1.0"?><hierarchy><node package="com.example"/></hierarchy>'
        def fake_run(args, **kwargs):
            assert args[:3] == ['adb', '-s', 'emulator-9999']
            command = args[3:]
            if command[:2] == ['exec-out', 'uiautomator']:
                captures.append(True)
                value = 'ERROR: null root node returned by UiTestAutomationBridge.' if never_ready or len(captures) == 1 else valid
            elif command[:2] == ['exec-out', 'screencap']: value = 'SYNTHETIC'
            elif command[:2] == ['shell', 'getprop'] or command[:2] == ['shell', 'settings']: value = '1'
            elif command[:3] == ['shell', 'cmd', 'package']: value = 'com.example/.Home'
            elif command[:3] == ['shell', 'dumpsys', 'window']: value = 'mCurrentFocus=Window{abc u0 com.example/.Home}'
            elif command[:2] == ['shell', 'input']:
                assert len(captures) >= 2 and not never_ready and not anr
                inputs.append(True); value = ''
            elif command[0] == 'logcat':
                logs.append(True); value = 'ANR in com.example.launcher' if anr and len(logs) > 1 else ''
            else: raise AssertionError('Unexpected mocked ADB command')
            return types.SimpleNamespace(stdout=value.encode(), stderr=b'', returncode=0)
        failure = None
        with patch.object(subprocess, 'run', fake_run), patch.object(time, 'monotonic', lambda: now[0]), \
                patch.object(time, 'sleep', lambda seconds: now.__setitem__(0, now[0] + seconds)):
            try: main('emulator-9999', output)
            except (TimeoutError, RuntimeError) as error: failure = type(error).__name__
        result = json.loads(output.files['startup-qualification.json'])
        assert result['setupSamples'][0]['setupReady'] and not result['setupSamples'][0]['initialUiCaptured']
        if never_ready:
            assert failure == 'TimeoutError' and not result['passed'] and not inputs and len(captures) > 1
            assert now[0] == 190
        elif anr:
            assert failure == 'RuntimeError' and not result['passed'] and not inputs
        else:
            assert failure is None and result['passed'] and len(inputs) == 1
            assert result['setupSamples'][1]['initialUiCaptured'] and now[0] >= 118
    gate_case()
    gate_case(never_ready=True)
    gate_case(anr=True)
    print('Emulator qualification parser and gate self-checks passed; no device accessed.')


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
        result['setupSamples'] = []
        while time.monotonic() < deadline:
            log = adb('logcat', '-d', '-v', 'threadtime', 'ActivityManager:I', '*:S')
            (output / 'startup-initial-system-log.txt').write_text(log, encoding='utf-8')
            result['initialAnrProcesses'] = anr_processes(log)
            if result['initialAnrProcesses']:
                raise RuntimeError('Recorded startup ANR before setup readiness; no input sent')
            flags = [adb('shell', 'getprop', 'sys.boot_completed'),
                     adb('shell', 'settings', 'get', 'global', 'device_provisioned'),
                     adb('shell', 'settings', 'get', 'secure', 'user_setup_complete')]
            ready = setup_ready(*flags)
            sample = {'atMs': int(time.time() * 1000), 'setupReady': ready, 'ready': False,
                      'initialUiCaptured': False, 'flags': [flag.strip() for flag in flags]}
            result['setupSamples'].append(sample)
            if ready:
                initial_xml = adb('exec-out', 'uiautomator', 'dump', '/dev/tty')
                (output / ('startup-readiness-%03d.xml' % len(result['setupSamples']))).write_text(initial_xml, encoding='utf-8')
                (output / 'startup-initial-ui.xml').write_text(initial_xml, encoding='utf-8')
                try:
                    dialog = system_error(initial_xml)
                except (ValueError, ET.ParseError) as missing:
                    sample['uiFailureType'] = type(missing).__name__
                else:
                    sample['initialUiCaptured'] = True
                    result['initialSystemErrorDialog'] = dialog
                    if dialog:
                        raise RuntimeError('Startup is already in an app-error state; no input sent')
                    sample['ready'] = True
                    break
            time.sleep(3)
        else:
            raise TimeoutError('Android setup and initial UI did not become ready within the original startup budget')
        home = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a',
                   'android.intent.action.MAIN', '-c', 'android.intent.category.HOME').strip().splitlines()[-1]
        if not re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', home):
            raise RuntimeError('No unambiguous HOME component')
        result['homeComponent'] = home
        result['initialSystemErrorDialog'] = system_error(initial_xml)
        initial_log = adb('logcat', '-d', '-v', 'threadtime', 'ActivityManager:I', '*:S')
        (output / 'startup-initial-system-log.txt').write_text(initial_log, encoding='utf-8')
        result['initialAnrProcesses'] = anr_processes(initial_log)
        if result['initialSystemErrorDialog'] or result['initialAnrProcesses']:
            raise RuntimeError('Startup is already in an app-error state; no input sent')
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
                    if system_error(xml):
                        raise RuntimeError('System app-error controls are visible')
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
