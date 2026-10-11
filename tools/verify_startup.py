#!/usr/bin/env python3
"""在真实 Android 平板上验证应用可启动并持续存活，不清除应用数据。"""
import argparse
import subprocess
import sys
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial')
parser.add_argument('--output', default='diagnostics/startup-regression')
args = parser.parse_args()
adb = [args.adb] + (['-s', args.serial] if args.serial else [])
package = 'com.example.minimaluvc'
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)

def run(*cmd):
    return subprocess.run(adb + list(cmd), text=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, timeout=20)

state = run('get-state')
if state.returncode or state.stdout.strip() != 'device':
    sys.exit('FAIL: 平板未连接或尚未授权 USB 调试')
launch = run('shell', 'am', 'start', '-S', '-W', '-n', package + '/.MainActivity')
(out / 'launch.txt').write_text(launch.stdout)
if launch.returncode or 'Error:' in launch.stdout:
    sys.exit('FAIL: 启动命令失败，见 launch.txt')
for second in range(1, 5):
    time.sleep(1)
    pid = run('shell', 'pidof', package)
    if pid.returncode or not pid.stdout.strip():
        crash = run('logcat', '-b', 'crash', '-d', '-v', 'threadtime', '-t', '120')
        (out / 'crash.txt').write_text(crash.stdout)
        sys.exit(f'FAIL: 启动后第 {second} 秒进程已退出，见 crash.txt')
activities = run('shell', 'dumpsys', 'activity', 'activities')
matching = '\n'.join(x for x in activities.stdout.splitlines() if package in x)
(out / 'activity.txt').write_text(matching + '\n')
if 'Application Error: ' + package in matching:
    crash = run('logcat', '-b', 'crash', '-d', '-v', 'threadtime', '-t', '120')
    (out / 'crash.txt').write_text(crash.stdout)
    sys.exit('FAIL: 系统显示本应用崩溃窗口，见 crash.txt')
if package + '/.MainActivity' not in matching:
    sys.exit('FAIL: 进程存活但没有找到 MainActivity，见 activity.txt')
(out / 'result.txt').write_text('PASS: MainActivity 启动成功，进程持续存活至少 4 秒\n')
print('PASS: MainActivity 启动成功，进程持续存活至少 4 秒')
