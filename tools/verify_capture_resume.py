#!/usr/bin/env python3
"""真实采集卡已出图时，验证十次 Home / 返回后新帧恢复；不录制或保存视频。"""
import argparse
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', required=True)
parser.add_argument('--cycles', type=int, default=10)
parser.add_argument('--output', default='diagnostics/capture-resume')
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]
package = 'com.example.minimaluvc'
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
records = []


def run(*cmd):
    result = subprocess.run(adb + list(cmd), capture_output=True, text=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def trace():
    raw = run('shell', 'run-as', package, 'cat', 'shared_prefs/uvc_diagnostics.xml')
    return ET.fromstring(raw).find("string[@name='events']").text or ''


def display_events(log):
    return [line for line in log.splitlines() if 'display frame accepted' in line]


def wait_video(previous_event=None):
    deadline = time.monotonic() + 35
    last_xml = ''
    while time.monotonic() < deadline:
        run('shell', 'uiautomator', 'dump', '/sdcard/uvc-resume-check.xml')
        last_xml = run('shell', 'cat', '/sdcard/uvc-resume-check.xml')
        nodes = list(ET.fromstring(last_xml).iter('node'))
        showing_app = any(n.get('resource-id') == package + ':id/root' for n in nodes)
        no_signal = any(n.get('resource-id') == package + ':id/no_signal_overlay' for n in nodes)
        events = display_events(trace())
        if showing_app and not no_signal and events and events[-1] != previous_event:
            return events[-1], last_xml
        time.sleep(.3)
    (out / 'failed-window.xml').write_text(last_xml)
    raise RuntimeError('Video did not resume with a newly accepted GPU frame; see diagnostics.')


try:
    assert run('get-state').strip() == 'device'
    pid = run('shell', 'pidof', package).strip()
    assert pid, 'Launch the app and connect the capture card first.'
    accepted, xml = wait_video()
    (out / 'initial-window.xml').write_text(xml)
    initial = trace()
    (out / 'initial-trace.txt').write_text(initial)
    for cycle in range(1, args.cycles + 1):
        run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(1.5)
        launch = run('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN',
                     '-c', 'android.intent.category.LAUNCHER', '-n', package + '/.MainActivity')
        assert 'Error:' not in launch, launch
        accepted, xml = wait_video(accepted)
        assert run('shell', 'pidof', package).strip() == pid, 'Process restarted unexpectedly.'
        log = trace()
        records.append(f'Cycle {cycle}: PASS, same process, newly accepted display frame, no signal overlay hidden.')
        print(records[-1], flush=True)
        (out / f'cycle-{cycle}-trace.txt').write_text(log)
        (out / f'cycle-{cycle}-window.xml').write_text(xml)
    records.append('PASS: real capture video restored on every Home/resume cycle; resolution/fps and latency assessed separately.')
except Exception as error:
    records.append('FAIL: ' + str(error))
    try:
        (out / 'failure-trace.txt').write_text(trace())
    except Exception:
        pass
    raise
finally:
    (out / 'result.txt').write_text('\n'.join(records) + '\n')
