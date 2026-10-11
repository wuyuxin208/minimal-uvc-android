# 第三方依赖与源码

Git 源码树不跟踪第三方预编译 AAR；GitHub Release 分发 APK、应用源码和上游 Native 源码包。运行 `python3 tools/fetch_uvc_dependencies.py` 从上游取得固定依赖并校验；本机 `app/libs/*.aar` 被 Git 忽略。

## 固定来源

上游：[jiangdongguo/AndroidUSBCamera](https://github.com/jiangdongguo/AndroidUSBCamera)。固定提交：`cd2a301ff5bd76f70930fccfe4b8e1073edc51f7`。

| 文件 | 上游路径 | SHA-256 |
| --- | --- | --- |
| `libuvc-3.2.9.aar` | `libuvc/aar/libuvc-3.2.9.aar` | `9ef78c44d204fe319b1cde45adec8b7c812a3a42bfbb2a9b263e6df61b1bc940` |
| `libuvccommon-3.2.9.aar` | `libuvccommon/aar/libuvccommon-3.2.9.aar` | `f9a3803a397e1c7cd8f9a73b85dd6333bdd7b0276f2a33edb14fc2ce885f2003` |

应用未修改 AAR。AAR 首次加入提交及 Native 源码核对结果见 [原生源码与修改库](NATIVE_SOURCE.md)；尚未验证逐字节可重现构建。原计划的 JitPack 3.3.3 构建不可用，故采用上游随附的 3.2.9 AAR。

## 许可证

- AndroidUSBCamera / UVCCamera 包装代码：Apache-2.0；保留上游及 saki 的版权声明，见 `licenses/AndroidUSBCamera-LICENSE`。
- libusb：LGPL-2.1，见 `licenses/libusb-COPYING`。
- libuvc：BSD-3-Clause，许可及版权头见 `licenses/libuvc-LICENSE`。
- libjpeg-turbo：BSD / IJG 等许可，见 `licenses/libjpeg-turbo-LICENSE.md`。
- RapidJSON：MIT，见 `licenses/rapidjson-LICENSE`；其测试附带组件和其他源码保留各自上游许可。
- 运行时 Maven 依赖：AndroidX appcompat 1.4.2、legacy-support-v4 1.0.0、recyclerview 1.0.0、preference 1.0.0，以及 xlog 1.11.0（Apache-2.0）。它们由 Gradle 下载，不随本仓库分发。
- 开发工具及测试依赖由各自许可约束；Gradle Wrapper 为 Apache-2.0，JUnit 4 为 EPL-1.0，Robolectric 为 MIT。

## 原生源码与构建说明

完整源码：[固定提交树](https://github.com/jiangdongguo/AndroidUSBCamera/tree/cd2a301ff5bd76f70930fccfe4b8e1073edc51f7)。下载归档：

```bash
python3 tools/fetch_uvc_dependencies.py --sources
```

归档存入本机 `third_party/source-download/`，不自动解压或执行，不进入 Git。上游源码入口：

- `libuvc/src/main/jni/libusb/`：libusb 源码与 LGPL 文本。
- `libuvc/src/main/jni/libuvc/`：libuvc 源码与组件许可。
- `libuvc/src/main/jni/libjpeg-turbo-1.5.0/`：JPEG 依赖与许可。
- `libuvc/src/main/jni/Android.mk` / `Application.mk`：Native 模块、ABI 和链接配置。
- `libuvc/build.gradle`、`libuvccommon/build.gradle`：Java / JNI 包装模块。

重建原生库需要与上游源码相匹配的 NDK 和构建工具；本应用源码的常规构建使用校验下载的 AAR，不要求 NDK。本项目尚未验证旧 Native 工程在当前 NDK 上可重建。

## 分发二进制前

如果另行发布 AAR 或 APK，需先核对实际包含的每个组件及其构建来源。对 LGPL 组件提供适用的完整对应源码（包括所需构建脚本和修改），并按实际链接方式满足替换库或重新链接的条件，同时保留版权、许可及所需声明。仅给一个上游首页链接不能替代这些义务。

本次 Release 包含 APK、完整应用源码包、上游 Native 源码包及 SHA-256 校验值。APK 内嵌许可文本，Release 与源码保留第三方声明。源码包与重新构建 / 替换库步骤见 [NATIVE_SOURCE.md](NATIVE_SOURCE.md)。
