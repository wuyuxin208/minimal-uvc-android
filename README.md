# Minimal UVC Android · 极简采集卡预览

Kotlin Android UVC 视频预览应用，面向仍支持安装 Android APK 的鸿蒙 4 / 老鸿蒙平板。使用 AndroidUSBCamera 的底层 libuvc，通过 SurfaceTexture / OpenGL ES 绘制到 SurfaceView。

当前版本 **1.3.2**。已在 Huawei DBY-W09（鸿蒙 4、Android API 31）验证启动和菜单；用户真实接卡确认返回桌面再打开可以恢复视频。完整边界见 [验证说明](docs/public/VALIDATION.md)。

## 下载与安装

**[下载最新版 APK](https://github.com/wuyuxin208/minimal-uvc-android/releases/latest)** · [v1.3.2 发布页](https://github.com/wuyuxin208/minimal-uvc-android/releases/tag/v1.3.2)

下载发布页中的 `MinimalUvc-1.3.2-debug.apk`，传到平板并打开，按系统提示允许安装。APK 面向 ARM32 / ARM64，是当前测试使用的调试签名版本，不是应用商店正式签名包。首次启动及插入采集卡时，请允许系统相机与 USB 权限。

Release 同时提供应用源码、上游原生库源码和 SHA-256 校验值。原生库修改与重新构建说明见 [第三方依赖](third_party/README.md)。

## 功能

- OTG 自动发现 USB Video Class 14 采集卡，按需申请系统相机和 USB 权限。
- 正常预览只有全屏视频；无设备或持续无新帧时显示“无信号”。
- 单击不做操作，**只有长按屏幕**打开菜单：分辨率、重新连接、退出。
- 默认拉伸铺满，可在 `MainActivity.kt` 的中文注释处将 `DisplayMode.STRETCH` 改为 `DisplayMode.FIT` 保持原始比例。
- 自动优先 2560×1600 / 60fps；菜单仅提供采集卡报告的尺寸，显示实际提交帧率和设备报告能力。请求值不等于实测值。
- 返回桌面保留已打开的 USB 流，暂停屏幕显示，重新进入恢复窗口。系统显示“采集卡保持连接”通知；后台会继续耗电，退出或移除任务可关闭连接。
- 不录制、拍照、推流、保存图片、播放音频或添加滤镜。

## 编译

需要 Android Studio、JDK 17、Android SDK Platform 34 / Build-Tools 34.0.0，以及 Python 3。固定构建版本：Gradle 8.2.1、AGP 8.2.2、Kotlin 1.9.22。

```bash
git clone https://github.com/wuyuxin208/minimal-uvc-android.git
cd minimal-uvc-android
python3 tools/fetch_uvc_dependencies.py
```

下载脚本从固定上游提交取回两个 3.2.9 AAR，并验证 SHA-256。它们不进入本仓库；来源、许可和原生源码说明见 [第三方依赖](third_party/README.md)。已有有效文件时脚本不重复下载。

在 Android Studio 打开项目并指定 SDK，或创建只保留在本机的 `local.properties`：

```properties
sdk.dir=/your/android/sdk
```

然后构建：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 使用 `gradlew.bat`。输出：`app/build/outputs/apk/debug/app-debug.apk`。首次运行需要网络下载 Gradle、AndroidX 和测试依赖；不依赖 JitPack，也无需本地 NDK 编译预览库。

构建配置为 `minSdk 23 / compileSdk 34 / targetSdk 28`，ABI 包含 `armeabi-v7a` 和 `arm64-v8a`。target 28 用于旧设备 APK 侧载，不适用于当前 Google Play 上架要求；提高 target 前应重新适配 USB、权限及前台服务行为。

## 安装与使用

通过本机 ADB 安装自建调试包：

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.minimaluvc/.MainActivity
```

也可以将 APK 传到平板后手动允许安装。首次相机权限和 USB 授权由操作系统弹窗，需手动允许。只有一个 USB 口时，先安装，随后连接采集卡与视频源；不必为了正常预览开启无线调试。

接线：视频源 HDMI 输出 → 采集卡 HDMI 输入 → 采集卡 USB 输出 → 平板 OTG。Mac 如果作为视频源，也属于这条 HDMI 链路。

## 源码入口

| 文件 | 职责 |
| --- | --- |
| `MainActivity.kt` | 全屏、权限、长按菜单和显示比例 |
| `UsbDeviceAccess.kt` | USB 发现、授权、插拔和控制块 |
| `UvcPreviewController.kt` / `PreviewSession.kt` | 串行 Native 会话、模式协商及释放 |
| `SurfacePreviewRenderer.kt` / `GpuPreviewContext.kt` | EGL 线程与最新帧绘制 |
| `PreviewRuntime.kt` / `PreviewConnectionService.kt` | 跨页面保持连接和系统通知 |
| `PreviewPolicy.kt` / `LowLatencyPolicy.kt` | 几何、模式排序、帧间隔和帧率统计 |
| `UvcDiagnostics.kt` | 最多 120 条私有状态事件，不保存视频 |

Kotlin 文件位于 `app/src/main/java/com/example/minimaluvc/`；布局位于 `app/src/main/res/layout/`。这些文件构成完整实现，不能只复制 MainActivity。设计细节见 [架构说明](docs/public/ARCHITECTURE.md)。

## 兼容性与限制

- 需要支持 Android APK、USB Host 的 ARM 平板。HarmonyOS NEXT 原生应用不在适用范围内。
- 当前读取 MJPEG / YUYV，不承诺 H.264-only 摄像头、所有 USB 采集卡或厂商特殊模式。
- 原生 2560×1600 / 60fps 取决于源输出、采集卡 USB 输出、带宽、供电及解码能力；真实 2K60 和端到端延迟尚未完整实测。
- 合成 GPU 约 60fps 的结果绕过真实 USB 输入和 Native 解码，不代表采集性能。
- 采集卡若持续输出黑画面或自身无信号提示，它仍是有效 UVC 流，不能按黑色像素判断 HDMI 断开。
- 系统边缘手势可能暂时唤起系统栏；系统权限框和隐私提示由系统控制。
- Native 内部仍有 CPU 解码、转换和缓冲；永久阻塞不能在同一进程内安全强杀。

## 调试

```bash
adb logcat -s MinimalUvc AndroidRuntime
adb shell run-as com.example.minimaluvc cat shared_prefs/uvc_diagnostics.xml
```

`tools/verify_startup.py` 检查启动；`tools/verify_capture_resume.py` 在真实视频已经显示、ADB 连接稳定时检查十次 Home / 返回。`app/src/debug/` 提供合成 GPU 探针，release 不包含该探针。

提交问题时注明设备型号、Android API、应用版本、采集卡格式与重现步骤。分享日志前删除设备序列号、局域网地址和个人路径；不要上传视频内容。

## 许可证与第三方代码

本项目原创应用代码及公开文档采用 **Apache-2.0**，见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。第三方代码保留原许可，不受本项目许可替代。

AndroidUSBCamera / UVCCamera 包装代码采用 Apache-2.0，其原生子组件包含 LGPL、BSD、IJG 和 MIT 等许可。详细文本、固定来源和下载说明见 [third_party/README.md](third_party/README.md)。Release 附带测试使用的 APK、应用源码、上游 Native 源码和校验值。仓库不跟踪 AAR，使用下载脚本取得固定版本；修改和再次分发时请保留许可、提供所需对应源码，并满足相关重新构建 / 重新链接要求。
