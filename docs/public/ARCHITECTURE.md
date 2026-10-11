# 架构与生命周期

数据路径：UVC → Native 解码 → SurfaceTexture → OpenGL ES OES 纹理 → EGL → SurfaceView。

- `MainActivity` 管理沉浸式、系统权限、长按菜单、SurfaceHolder 和显示比例。
- `UsbDeviceAccess` 管理 USB Video Class 14 发现、授权、插拔广播与控制块。
- `UvcPreviewController` 在主线程维护状态，在单个工作线程打开、启动和释放 Native 会话。
- `PreviewSession` 封装底层 UVCCamera 和 USB 控制块所有权。
- `SurfacePreviewRenderer` / `GpuPreviewContext` 在专用 EGL 线程消费最新输入并绘制。
- `PreviewRuntime` 使用 applicationContext 持有会话；页面停止时移除 UI 回调，新页面接入同一运行对象。
- `PreviewConnectionService` 在存在 USB 会话时显示保持连接通知；不在进程被杀后自动重启相机。
- `UvcDiagnostics` 保存最多 120 条应用私有状态事件，不保存视频像素。

## Home / 返回恢复

1.3.1 接卡测试发现：完整停止并重新打开 USB 后可能无法恢复，菜单重连也无效，拔插才恢复。上游包含阻塞停止路径，但没有获得故障时的完整 Native 堆栈，因此不能断言具体阻塞位置。

1.3.2 将 USB 生命周期与显示生命周期分开。Home 时仅解绑显示窗口，继续消费离屏纹理，保持同一 USB 流；返回后绑定有效 Surface，等待新提交帧再隐藏“无信号”。活动 USB 会话可跨显示窗口继续工作，真正停止会话时才使其帧通行证失效。

最近仍有离屏输入时，“重新连接”优先重建显示窗口；输入中断时才重新打开 USB。改变采集分辨率、退出、拔卡或任务移除仍释放会话。后台保持采集会继续耗电。

## 约束

- Native 仅持有离屏生产 Surface；释放它必须等待 Native 停止完成。
- 主线程不等待 Native stop、join 或 GPU 阻塞调用。
- 旧页面的迟到销毁、旧会话帧和旧窗口提交不能影响当前页面。
- 无信号根据帧到达情况判断，不能通过黑色像素判断 HDMI 是否断开。
- 应用层减少了像素复制；第三方 Native 内部仍有 CPU 解码、转换和缓冲。
