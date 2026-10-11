#!/bin/bash
# 在 Mac 上双击运行。只采集诊断信息、重新启动本应用，不清除应用数据。
set -u
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ADB_BIN=""
for candidate in "$(command -v adb 2>/dev/null || true)" "/tmp/minimaluvc-toolchain/sdk/platform-tools/adb" "$HOME/Library/Android/sdk/platform-tools/adb"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
        ADB_BIN="$candidate"
        break
    fi
done
fail() {
    printf '%s\n' "$1"
    read -r -p '按回车结束。' ignored
    exit 1
}
[ -n "$ADB_BIN" ] || fail '找不到 adb，请先在 Android Studio 的 SDK Manager 安装 Android SDK Platform-Tools。'
"$ADB_BIN" devices -l
"$ADB_BIN" get-state >/dev/null 2>&1 || fail '未找到已授权的平板。请开启 USB 调试，用数据线连接 Mac，并在平板上允许调试。多台设备时只保留目标平板连接。'
OUTPUT_DIR="$PROJECT_DIR/diagnostics/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUTPUT_DIR"
{
    printf 'model: '; "$ADB_BIN" shell getprop ro.product.model
    printf 'manufacturer: '; "$ADB_BIN" shell getprop ro.product.manufacturer
    printf 'sdk: '; "$ADB_BIN" shell getprop ro.build.version.sdk
    printf 'android_release: '; "$ADB_BIN" shell getprop ro.build.version.release
    printf 'display: '; "$ADB_BIN" shell getprop ro.build.display.id
    printf 'abi: '; "$ADB_BIN" shell getprop ro.product.cpu.abilist
} > "$OUTPUT_DIR/设备信息.txt" 2>&1
"$ADB_BIN" shell dumpsys package com.example.minimaluvc > "$OUTPUT_DIR/安装版本与权限.txt" 2>&1
# 必须先取出旧故障记录，避免强制重启或重新安装之后覆盖关键状态。
"$ADB_BIN" shell run-as com.example.minimaluvc cat shared_prefs/uvc_diagnostics.xml > "$OUTPUT_DIR/启动前私有状态记录.txt" 2>&1
"$ADB_BIN" shell dumpsys usb > "$OUTPUT_DIR/USB设备状态.txt" 2>&1
"$ADB_BIN" logcat -b crash -d -v threadtime > "$OUTPUT_DIR/启动前崩溃记录.txt" 2>&1
# 从当前日志末尾开始捕获应用、Java 异常及 Native 崩溃标签，不清空系统日志。
"$ADB_BIN" logcat -b main -b system -b crash -v threadtime -T 1 -s MinimalUvc AndroidRuntime libc DEBUG ActivityManager ImageReader BufferQueueProducer lmkd > "$OUTPUT_DIR/启动过程.txt" 2>&1 &
LOG_PID=$!
stop_capture() {
    kill "$LOG_PID" 2>/dev/null || true
    wait "$LOG_PID" 2>/dev/null || true
}
trap stop_capture EXIT
printf '%s\n' '正在重新启动 UVC预览，并记录 30 秒。请在平板上处理出现的系统权限框，保留崩溃提示。'
"$ADB_BIN" shell am start -S -W -n com.example.minimaluvc/.MainActivity > "$OUTPUT_DIR/启动结果.txt" 2>&1
sleep 30
stop_capture
trap - EXIT
"$ADB_BIN" logcat -b crash -d -v threadtime > "$OUTPUT_DIR/启动后崩溃记录.txt" 2>&1
"$ADB_BIN" shell dumpsys activity exit-info com.example.minimaluvc > "$OUTPUT_DIR/进程退出记录.txt" 2>&1
"$ADB_BIN" shell run-as com.example.minimaluvc cat shared_prefs/uvc_diagnostics.xml > "$OUTPUT_DIR/启动后私有状态记录.txt" 2>&1
ZIP_PATH="$OUTPUT_DIR.zip"
if /usr/bin/zip -jq "$ZIP_PATH" "$OUTPUT_DIR"/*.txt; then
    printf '诊断日志已保存：%s\n' "$ZIP_PATH"
else
    printf '日志已保存到目录：%s\n' "$OUTPUT_DIR"
fi
read -r -p '按回车结束。' ignored
