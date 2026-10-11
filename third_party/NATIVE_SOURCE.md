# 原生源码与修改库

## 版本与来源

Release 附带 `AndroidUSBCamera-native-source-a2d8fe0.zip`。它包含上游首次加入 `libuvc-3.2.9.aar` 的提交 `a2d8fe03665d611d8ff7cd45dd54feed2e19dc56` 中的 `libuvc/src/main/jni/`、Java 包装源码、libuvccommon 源码、模块和根构建配置、许可与 Gradle Wrapper；不包含预编译 `.so`。

来源：[AAR 加入提交](https://github.com/jiangdongguo/AndroidUSBCamera/commit/a2d8fe03665d611d8ff7cd45dd54feed2e19dc56)。应用没有修改第三方 Native 源码。

已核对该提交与依赖下载固定提交：182 个 libusb 文件均无变化，全部 865 个 JNI 源文件及构建文件中仅顶层 `Application.mk` 改变，因此归档使用 AAR 首次加入时的原版构建文件。AAR 中两种 ARM ABI 的 libusb 与该提交随附 JNI 二进制的代码、只读数据及编译器标识相同；整文件哈希因节表元数据不同而不同。未验证逐字节可重现构建。

libusb 按 LGPL-2.1-or-later 分发，完整许可见 `licenses/libusb-COPYING`。包装库动态链接 `libusb100.so`；源码归档包含 libusb 的源码、Android 配置、头文件及构建脚本，以及外围 JNI 工程。用户可以修改和替换该库，允许为调试库修改进行所需逆向分析。

## 获取完整上游树

```bash
python3 tools/fetch_uvc_dependencies.py --sources
```

也可从上面的固定提交下载完整源码。Release 中的 Native 源码包提供所需模块源码；若希望构建整个上游示例项目，请使用完整上游树。本应用自己的 Gradle 工程位于本仓库。

## 单独重建 libusb

解压 Native 源码包到 `AndroidUSBCamera-native-source-a2d8fe0/`，使用兼容的 Android NDK。原二进制编译标识是 Android Clang 9.0.8，可从 NDK r21 系列开始核对。下面命令未在本项目中实测，不承诺现代 NDK 或逐字节重现；保留的原版 Android.mk 与配置文件是构建依据。

```bash
cd AndroidUSBCamera-native-source-a2d8fe0/libuvc/src/main/jni/libusb/android
/path/to/android-ndk/ndk-build \
  NDK_PROJECT_PATH=. \
  APP_BUILD_SCRIPT=jni/Android.mk \
  NDK_APPLICATION_MK=jni/Application.mk \
  APP_ABI="arm64-v8a armeabi-v7a" \
  APP_PLATFORM=android-23
```

输出通常为当前目录的 `libs/arm64-v8a/libusb100.so` 与 `libs/armeabi-v7a/libusb100.so`。切换 NDK 时若旧编译选项不再支持，需相应调整并记录修改。

## 在应用中替换并重新签名

先获取原版依赖，再用重新构建的两种 ABI 库替换 AAR 内的动态库：

```bash
python3 tools/fetch_uvc_dependencies.py
python3 tools/replace_libusb.py /path/to/rebuilt/libs
./gradlew :app:assembleDebug
```

替换脚本保留其他 AAR 内容，检查 ELF 架构并原子写回。修改后不要用下载脚本覆盖自己的 AAR；下载脚本遇到非原版文件会停止并提醒备份。自行构建的 APK 使用自己的调试或正式签名，若与已安装版本签名不同，需要卸载旧版本再安装。

再次分发修改库或 APK 时，保留版权与许可、标注修改，并提供与修改二进制对应的源码、构建资料和适用的重新链接 / 替换库条件。本项目不提供私钥，也不限制使用者自行构建和签名。
