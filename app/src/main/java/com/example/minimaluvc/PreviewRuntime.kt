package com.example.minimaluvc

import android.content.Context
import android.annotation.SuppressLint
import android.os.Looper

/**
 * 应用级采集运行对象，随前台服务保持存活，不随 Activity 的显示窗口销毁。
 * 页面退后台时移除回调引用，避免持有已销毁 Activity；新页面接入同一 USB / GPU 会话。
 */
internal class PreviewRuntime private constructor(context: Context, preferred: VideoSize?,
    sessions: ((UsbDeviceAccess, SurfacePreviewRenderer) -> PreviewSession)?) {
    data class Client(val video: (VideoSize) -> Unit, val idle: () -> Unit, val resolution: (ResolutionStatus) -> Unit)
    private val app = context.applicationContext
    private var client: Client? = null
    private var held = false
    private val renderer = SurfacePreviewRenderer()
    val access: UsbDeviceAccess = UsbDeviceAccess(app) { controller.refreshDevice() }
    val controller: UvcPreviewController = UvcPreviewController(access,
        onVideoSize = { client?.video?.invoke(it) },
        onIdle = { client?.idle?.invoke() },
        initialPreferredSize = preferred,
        renderer = renderer,
        createSession = { sessions?.invoke(access, renderer) ?: LibuvcPreviewSession(access, renderer) },
        onResolutionChanged = { client?.resolution?.invoke(it) },
        onConnectionHeld = { live ->
            held = live
            // 旧运行对象的异步清理不得关掉新对象已启动的前台服务。
            if (current === this) {
                PreviewConnectionService.update(app, live)
                if (!live && client == null) release()
            }
        })

    init { access.register() }

    fun attach(next: Client) {
        checkMain()
        client = next
        next.resolution(controller.resolutionStatus())
    }

    fun start(owner: Client) {
        attach(owner)
        access.setForeground(true)
        controller.setForeground(true)
    }

    fun stop(owner: Client) {
        checkMain()
        if (client !== owner) return
        controller.setForeground(false)
        access.setForeground(false)
        client = null
    }

    fun pageDestroyed(owner: Client, finishing: Boolean) {
        checkMain()
        // 页面重建期间，旧 Activity 的迟到 onDestroy 不能释放新页面正在使用的运行对象。
        if (client != null && client !== owner) return
        if (client === owner) stop(owner)
        if (finishing || !held) release()
    }

    private fun release() {
        checkMain()
        if (current !== this) return
        current = null
        client = null
        access.unregister()
        controller.destroy()
        PreviewConnectionService.update(app, false)
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())

    companion object {
        // 这里只保存 applicationContext，UI 回调在 onStop 移除；不会将 Activity 放入静态字段。
        @SuppressLint("StaticFieldLeak")
        private var current: PreviewRuntime? = null
        fun acquire(context: Context, preferred: VideoSize?,
            sessions: ((UsbDeviceAccess, SurfacePreviewRenderer) -> PreviewSession)? = null): PreviewRuntime {
            check(Looper.myLooper() == Looper.getMainLooper())
            return current ?: PreviewRuntime(context, preferred, sessions).also { current = it }
        }
        fun closeCurrent() { current?.release() }
    }
}
