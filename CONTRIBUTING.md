# 参与贡献

欢迎提交设备兼容报告、可复现 bug 和代码改进。请先阅读 README 的范围与验证边界。

本项目保持视频预览用途，不添加录制、拍照、推流、滤镜或常驻控制面板。普通点击不打开菜单；菜单通过长按进入。

## 开发检查

```bash
python3 tools/fetch_uvc_dependencies.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

USB / Native 调用应留在串行工作线程，UI 与退出不得阻塞等待。会话与显示窗口生命周期分开，过期窗口、会话和页面回调不能更新当前画面。

涉及 UVC 驱动的修改需要真实设备验证；Robolectric 和合成 GPU 测试不替代真实 USB 输入。报告设备型号、系统 API、采集卡支持模式、操作步骤与实际结果。日志中删除个人路径、设备序列号及局域网地址，不上传视频内容。

原生库修改需保留第三方版权和许可，并提供适用的源码和构建资料。提交贡献时沿用本项目 Apache-2.0；第三方代码继续按原许可处理。
