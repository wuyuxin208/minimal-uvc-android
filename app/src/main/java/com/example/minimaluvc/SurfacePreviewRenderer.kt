package com.example.minimaluvc

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 每个会话独立的帧通行证，失效不调用任何 Native 或 GPU 阻塞接口。 */
class FrameLease {
    private val enabled = AtomicBoolean(true)
    private val inputAt = AtomicLong(Long.MIN_VALUE)
    val isActive: Boolean get() = enabled.get()
    fun invalidate() { enabled.set(false) }
    /** 即使没有屏幕，也记录实际消费到的 USB 输出帧，供重连判断流是否仍在工作。 */
    fun noteInput() { inputAt.set(SystemClock.uptimeMillis()) }
    internal val lastInputUptime: Long get() = inputAt.get()
    fun hasRecentInput(): Boolean = isActive && inputAt.get().let {
        it != Long.MIN_VALUE && SystemClock.uptimeMillis() - it in 0 until 3000L
    }
}

/**
 * UVC → 离屏 SurfaceTexture → OES 纹理 → SurfaceView。
 * 应用层不再逐帧整理 ByteBuffer、复制 Bitmap 或在 UI 线程绘制 Canvas。
 * Native 只持有离屏生产端。屏幕更换只重绑显示窗口，活跃 USB 会话可以继续显示。
 * 真正停止或切换采集会话时，帧通行证失效，旧会话不能画进新屏幕。
 */
class SurfacePreviewRenderer {
    internal data class Screen(val surface: Surface?, val epoch: Long)
    private val epoch = AtomicLong()
    private val screen = AtomicReference(Screen(null, 0))
    private val guard = Any()
    @Volatile private var handler: Handler? = null
    private var thread: HandlerThread? = null
    private var releasing = false
    // 以下对象只能在 EGL 专用线程访问。
    private var graphics: GpuPreviewContext? = null
    private var sinkCount = 0

    fun setSurface(surface: Surface?) {
        check(Looper.myLooper() == Looper.getMainLooper())
        screen.set(Screen(surface, epoch.incrementAndGet()))
        // 主线程不等待 eglSwapBuffers / USB stop；EGL 持有自己的 NativeWindow 引用。
        handler?.post { graphics?.detachWindow() }
    }

    fun clear() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val target = screen.get()
        handler?.post {
            if (screen.get() === target) try { graphics?.clear(target) }
            catch (error: RuntimeException) { Log.w(TAG, "GPU 清屏暂不可用", error) }
        }
    }

    /** 只在 USB 工作线程等待 GPU 资源创建；Activity 从不等待此方法。 */
    fun createSink(size: VideoSize, lease: FrameLease, onFrame: () -> Unit): FrameSink {
        check(Looper.myLooper() != Looper.getMainLooper())
        val gpuHandler = synchronized(guard) {
            check(!releasing) { "渲染器已关闭" }
            handler ?: HandlerThread("MinimalUvc-GL").let { worker ->
                worker.start()
                thread = worker
                Handler(worker.looper).also { handler = it }
            }
        }
        val task = FutureTask {
            synchronized(guard) { check(!releasing) { "渲染器已关闭" } }
            val gpu = graphics ?: GpuPreviewContext().also { graphics = it }
            FrameSink(size, lease, onFrame, gpuHandler, gpu).also { sinkCount++ }
        }
        check(gpuHandler.post(task)) { "GPU 线程已退出" }
        return task.get()
    }

    /** Activity 销毁时禁止新资源；Native 停止前继续消费离屏纹理，避免生产端堵塞。 */
    fun release() {
        synchronized(guard) { releasing = true }
        handler?.post { releaseIfFinished() }
    }

    private fun releaseIfFinished() {
        val done = synchronized(guard) { releasing }
        if (done && sinkCount == 0) {
            graphics?.release()
            graphics = null
            thread?.quitSafely()
        }
    }

    inner class FrameSink internal constructor(
        size: VideoSize,
        private val lease: FrameLease,
        private val onFrame: () -> Unit,
        private val gpuHandler: Handler,
        private val gpu: GpuPreviewContext
    ) {
        private val textureId = gpu.newExternalTexture()
        private val texture = SurfaceTexture(textureId).apply { setDefaultBufferSize(size.width, size.height) }
        val surface = Surface(texture)
        private var closed = false
        private var reportedError = false
        private var lastTimestamp = Long.MIN_VALUE
        private val matrix = FloatArray(16)
        private val frames = LatestFrameDispatcher({ gpuHandler.post(it) }) { consumeLatest() }

        init {
            texture.setOnFrameAvailableListener({ frames.request() }, gpuHandler)
        }

        private fun consumeLatest() {
            if (closed) return
            try {
                gpu.makeOffscreenCurrent()
                // updateTexImage 取得最新可用纹理，过期通知不会变成逐帧回放队列。
                texture.updateTexImage()
                val timestamp = texture.timestamp
                if (timestamp != 0L && timestamp == lastTimestamp) return
                lastTimestamp = timestamp
                if (lease.isActive) lease.noteInput()
                val target = screen.get()
                // 无屏幕时也持续 updateTexImage 排空生产端，不让 Native 在 BufferQueue 中堵住。
                if (!lease.isActive || target.surface?.isValid != true) return
                texture.getTransformMatrix(matrix)
                if (gpu.draw(target, textureId, matrix) {
                    lease.isActive && screen.get() === target
                }) onFrame()
            } catch (error: RuntimeException) {
                if (!reportedError) {
                    reportedError = true
                    Log.w(TAG, "GPU 视频绘制失败，等待控制器超时或重连", error)
                }
            }
        }

        /** 仅在 Native destroy 返回后释放生产 Surface，绝不提前释放仍被驱动持有的缓冲。 */
        fun closeAfterNativeStopped() {
            lease.invalidate()
            gpuHandler.post {
                if (!closed) {
                    closed = true
                    frames.close()
                    texture.setOnFrameAvailableListener(null)
                    surface.release()
                    texture.release()
                    gpu.deleteTexture(textureId)
                    sinkCount--
                    releaseIfFinished()
                }
            }
        }
    }

    companion object { private const val TAG = "MinimalUvc" }
}
