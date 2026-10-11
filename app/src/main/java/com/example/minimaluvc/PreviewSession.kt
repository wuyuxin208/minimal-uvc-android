package com.example.minimaluvc

import android.hardware.usb.UsbDevice
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera

/** 可替换的硬件边界：USB 操作都由控制器的单个工作线程调用。 */
interface PreviewSession {
    fun open(device: UsbDevice)
    fun supportedModes(): List<PreviewMode>
    fun start(mode: PreviewMode, lease: FrameLease, onFrame: () -> Unit)
    fun close()
}

/** 只使用底层预览接口；每次模式尝试都重新创建会话，避免复用失败的 Native 状态。 */
internal class LibuvcPreviewSession(
    private val access: UsbDeviceAccess,
    private val renderer: SurfacePreviewRenderer
) : PreviewSession {
    private var camera: UVCCamera? = null
    private var block: USBMonitor.UsbControlBlock? = null
    private var sink: SurfacePreviewRenderer.FrameSink? = null
    private var lease: FrameLease? = null

    override fun open(device: UsbDevice) {
        block = access.openControlBlock(device)
        val opened = UVCCamera().also { camera = it }
        opened.open(checkNotNull(block))
    }

    override fun supportedModes(): List<PreviewMode> {
        val base = listOf(UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.FRAME_FORMAT_YUYV).flatMap { format ->
            checkNotNull(camera).getSupportedSizeList(format).orEmpty().map {
                PreviewMode(VideoSize(it.width, it.height), format)
            }
        }
        val descriptors = checkNotNull(block).rawDescriptors ?: byteArrayOf()
        return UvcFrameRates.enrich(base, descriptors)
    }

    override fun start(mode: PreviewMode, lease: FrameLease, onFrame: () -> Unit) {
        this.lease = lease
        val opened = checkNotNull(camera)
        // 依据 UVC 帧间隔协商，取消旧版最高 30fps 的限制；实际预览 fps 另行测量。
        opened.setPreviewSize(mode.size.width, mode.size.height, mode.minFps, mode.maxFps, mode.frameFormat, 1.0f)
        val output = renderer.createSink(mode.size, lease, onFrame).also { sink = it }
        opened.setPreviewDisplay(output.surface)
        opened.startPreview()
    }

    override fun close() {
        lease?.invalidate()
        // destroy 内部包含 stopPreview。离屏纹理必须活到 destroy 返回，不能提前强行关闭。
        // 若驱动阻塞，只阻塞 USB 工作线程，屏幕及 Activity 均不等待它。
        camera?.destroy()
        camera = null
        block?.close()
        block = null
        sink?.closeAfterNativeStopped()
        sink = null
    }
}
