package com.example.minimaluvc

import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 主线程负责状态和显示；单个工作线程独占 USB / Native 资源。
 * 底层只写离屏缓冲，所以 Surface 销毁和退出都不必等待 USB 线程。
 */
class UvcPreviewController(
    private val access: UsbDeviceAccess,
    private val onVideoSize: (VideoSize) -> Unit,
    private val onIdle: () -> Unit,
    private val renderer: SurfacePreviewRenderer = SurfacePreviewRenderer(),
    private val createSession: () -> PreviewSession = { LibuvcPreviewSession(access, renderer) },
    initialPreferredSize: VideoSize? = null,
    private val onResolutionChanged: (ResolutionStatus) -> Unit = {},
    private val onConnectionHeld: (Boolean) -> Unit = {}
) {
    private enum class State { Idle, WaitingPermission, WaitingSurface, Opening, Previewing, WaitingFrames, Suspended, Stopping, Error }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "MinimalUvc-Worker") }
    private val generation = AtomicLong(0L)
    private val presentationGeneration = AtomicLong(0L)
    private var state = State.Idle
    private var foreground = false
    private var destroyed = false
    private var surface: Surface? = null
    private var selected: UsbDevice? = null
    private var failedDevice: String? = null
    private var lease: FrameLease? = null
    private var watchdog: Runnable? = null
    private var frameMonitor: Runnable? = null
    // 分辨率状态只在主线程访问；工作线程接收请求快照，不读取可变 UI 设置。
    private var preferredSize = initialPreferredSize
    private var currentSize: VideoSize? = null
    private var availableSizes = emptyList<VideoSize>()
    private var previewFps: Double? = null
    private var deviceFps = emptyMap<VideoSize, Double?>()
    private var modesDevice: String? = null
    private var lastResolutionStatus: ResolutionStatus? = null
    private var connectionHeld = false
    private data class ActiveAttempt(val device: UsbDevice, val modes: List<PreviewMode>, val index: Int,
        val mode: PreviewMode, val ticket: Long, val frames: FrameLease,
        val lastFrame: AtomicLong, val needsPresentation: AtomicBoolean, val fps: FrameRateSampler)
    private var activeAttempt: ActiveAttempt? = null

    // 只有工作线程可以访问相机；主线程从不调用 Native stop/destroy。
    private var session: PreviewSession? = null

    fun resolutionStatus(): ResolutionStatus {
        checkMainThread()
        return ResolutionStatus(availableSizes.toList(), currentSize, preferredSize, previewFps, deviceFps.toMap())
    }

    /** 菜单选择：只接受当前设备报告的尺寸；清理和重新开流仍全部交给串行线程。 */
    fun selectResolution(size: VideoSize?): Boolean {
        checkMainThread()
        if (destroyed) return false
        // 菜单打开期间也可能拔卡，不能只凭弹窗中的旧列表接受选择。
        refreshDevice()
        if (size != null && size !in availableSizes) return false
        if (preferredSize == size) return true
        preferredSize = size
        retryConnection(forceUsbRestart = true)
        return true
    }

    private fun publishResolutionStatus() {
        val status = resolutionStatus()
        if (status != lastResolutionStatus) {
            lastResolutionStatus = status
            onResolutionChanged(status)
        }
    }

    private fun setCurrentSize(size: VideoSize?) {
        currentSize = size
        if (size == null) previewFps = null
        publishResolutionStatus()
    }

    fun setForeground(foreground: Boolean) {
        checkMainThread()
        if (destroyed || this.foreground == foreground) return
        this.foreground = foreground
        UvcDiagnostics.event("foreground=$foreground selected=${selected != null} inputAlive=${lease?.hasRecentInput() == true}")
        if (foreground) {
            failedDevice = null
            refreshDevice()
        } else {
            // 返回桌面只暂停显示。部分卡完整 stop/destroy 后不能重新开流，必须物理拔插。
            // 保持同一 USB 会话和离屏生产端，GPU 继续消费纹理，前台服务保护连接。
            suspendPresentation()
        }
    }

    /** 主线程使旧屏幕代号失效；EGL 线程释放自身引用，不等待 USB 或 GPU。 */
    fun setSurface(surface: Surface?) {
        checkMainThread()
        if (destroyed) return
        if (this.surface === surface && surface != null && surface.isValid) return
        this.surface = surface
        renderer.setSurface(surface)
        presentationGeneration.incrementAndGet()
        cancelWatchdog()
        cancelFrameMonitor()
        activeAttempt?.needsPresentation?.set(true)
        activeAttempt?.fps?.reset()
        setCurrentSize(null)
        onIdle()
        UvcDiagnostics.event("screen=${surface?.isValid == true} retainedUsb=${selected != null} inputAlive=${lease?.hasRecentInput() == true}")
        if (surface == null) {
            changeState(if (selected != null) State.Suspended else State.WaitingSurface)
            return
        }
        failedDevice = null
        refreshDevice()
        if (foreground && lease?.isActive == true) awaitPresentation()
    }

    private fun suspendPresentation() {
        surface = null
        renderer.setSurface(null)
        presentationGeneration.incrementAndGet()
        cancelWatchdog()
        cancelFrameMonitor()
        activeAttempt?.needsPresentation?.set(true)
        activeAttempt?.fps?.reset()
        setCurrentSize(null)
        onIdle()
        changeState(State.Suspended)
    }

    /** 重新绑定窗口后等到真正提交的新帧再隐藏“无信号”，不复用上一轮的成功状态。 */
    private fun awaitPresentation() {
        if (!foreground || surface?.isValid != true) return
        activeAttempt?.let { active ->
            active.needsPresentation.set(true)
            active.fps.reset()
            changeState(State.Opening)
            scheduleWatchdog(FIRST_FRAME_TIMEOUT_MS, active.ticket) {
                retryOrFail(active.device, active.modes, active.index, "恢复显示首帧超时")
            }
        } ?: selected?.let { device ->
            val ticket = generation.get()
            scheduleWatchdog(OPEN_TIMEOUT_MS, ticket) { finishFailure(device, "USB 打开超时") }
        }
    }

    private fun setConnectionHeld(held: Boolean) {
        if (connectionHeld != held) { connectionHeld = held; onConnectionHeld(held) }
    }

    fun refreshDevice() {
        checkMainThread()
        if (destroyed) return
        val devices = access.findUvcDevices()
        if (modesDevice != null && devices.none { identity(it) == modesDevice }) {
            modesDevice = null
            availableSizes = emptyList()
            deviceFps = emptyMap()
            setCurrentSize(null)
        }
        if (devices.none { identity(it) == failedDevice }) failedDevice = null
        selected?.let { current ->
            if (devices.none { identity(it) == identity(current) } ||
                !access.hasPermission(current) || !access.hasCameraPermission()) stopSession()
        }
        if (!foreground) return
        if (!access.hasCameraPermission()) {
            changeState(State.WaitingPermission)
            return
        }
        if (selected != null) return // 已有设备时，不让第二台设备抢占。
        val device = devices.firstOrNull() ?: run { changeState(State.Idle); return }
        if (!access.hasPermission(device)) {
            changeState(State.WaitingPermission)
            access.requestPermissionOnce(device)
            return
        }
        if (surface?.isValid != true) {
            changeState(State.WaitingSurface)
            return
        }
        if (failedDevice == identity(device)) {
            changeState(State.Error)
            return
        }
        selected = device
        if (modesDevice != identity(device)) {
            modesDevice = identity(device)
            availableSizes = emptyList()
            deviceFps = emptyMap()
            publishResolutionStatus()
        }
        beginAttempt(device, null, 0)
    }

    /** 菜单中的重新连接：异步清理旧会话后，再扫描设备与权限。 */
    fun retryConnection(forceUsbRestart: Boolean = false) {
        checkMainThread()
        if (destroyed) return
        if (!forceUsbRestart && selected != null && lease?.hasRecentInput() == true &&
            foreground && surface?.isValid == true) {
            // USB 仍有新帧时优先重建 EGL 显示窗口，避免把活着的卡再停死。
            UvcDiagnostics.event("retry: input alive, rebind display")
            renderer.setSurface(surface)
            presentationGeneration.incrementAndGet()
            cancelFrameMonitor()
            setCurrentSize(null)
            onIdle()
            awaitPresentation()
            return
        }
        UvcDiagnostics.event("retry: restart USB, recentInput=${lease?.hasRecentInput() == true}")
        failedDevice = null
        stopSession()
        refreshDevice()
    }

    /** 每个候选只尝试一次；首次打开后读取模式列表，后续回退重新创建相机。 */
    private fun beginAttempt(device: UsbDevice, modes: List<PreviewMode>?, index: Int) {
        cancelWatchdog()
        cancelFrameMonitor()
        lease?.invalidate()
        val frames = FrameLease().also { lease = it }
        activeAttempt = null
        val ticket = generation.incrementAndGet()
        setConnectionHeld(true)
        changeState(State.Opening)
        // USB open 本身也可能阻塞。超时只让会话失效，绝不在 UI 线程强制关闭 Native。
        scheduleWatchdog(OPEN_TIMEOUT_MS, ticket) {
            finishFailure(device, "USB 打开/清理超时；取消本次会话")
        }
        val preferred = preferredSize
        worker.execute { openAttempt(device, modes, index, ticket, frames, preferred) }
    }

    private fun openAttempt(device: UsbDevice, knownModes: List<PreviewMode>?, index: Int,
                            ticket: Long, frames: FrameLease, preferred: VideoSize?) {
        // 上一次没有安全释放时，不允许并发开启新相机。
        if (!releaseResources()) {
            main.post { if (isCurrent(ticket)) finishFailure(device, "上个会话释放失败") }
            return
        }
        var modes = knownModes
        try {
            ensureCurrent(device, ticket)
            val opened = createSession().also { session = it }
            UvcDiagnostics.event("USB open begin ticket=$ticket")
            opened.open(device)
            UvcDiagnostics.event("USB open done ticket=$ticket")
            ensureCurrent(device, ticket)
            val candidates = modes ?: opened.supportedModes().let { reported ->
                // 可选列表来自采集卡描述符；MJPEG / YUYV 同尺寸去重，不虚构 2K 选项。
                val available = PreviewPolicy.rankModes(reported).map { it.size }.distinct()
                    .sortedWith(compareByDescending<VideoSize> { it.width.toLong() * it.height }
                        .thenByDescending { it.width }.thenByDescending { it.height })
                main.post {
                    if (isCurrent(ticket)) {
                        availableSizes = available
                        deviceFps = reported.groupBy { it.size }.mapValues { (_, modes) ->
                            modes.mapNotNull { it.declaredFps }.maxOrNull()
                        }
                        modesDevice = identity(device)
                        publishResolutionStatus()
                    }
                }
                PreviewPolicy.rankModes(reported, preferred).also { modes = it }
            }
            val mode = candidates.getOrNull(index) ?: error("没有可协商的 MJPEG / YUYV 模式")
            val lastFrame = AtomicLong(0L)
            val fps = FrameRateSampler()
            val needsPresentation = AtomicBoolean(true)
            val active = ActiveAttempt(device, candidates, index, mode, ticket, frames, lastFrame, needsPresentation, fps)
            main.post {
                if (isCurrent(ticket)) {
                    activeAttempt = active
                    awaitPresentation()
                }
            }
            ensureCurrent(device, ticket)
            UvcDiagnostics.event("USB start begin ticket=$ticket mode=$mode")
            opened.start(mode, frames) {
                // 底层 startPreview 返回不是成功依据；只有有效帧已经绘制才确认成功。
                // 回调可能来自任意线程；每次尝试只向 UI 投递一次首帧事件。
                if (!frames.isActive) return@start
                lastFrame.set(SystemClock.uptimeMillis())
                val screenTicket = presentationGeneration.get()
                if (needsPresentation.compareAndSet(true, false)) main.post {
                    if (isCurrent(ticket) && frames.isActive && foreground && surface?.isValid == true &&
                        presentationGeneration.get() == screenTicket) {
                        needsPresentation.set(false)
                        cancelWatchdog()
                        changeState(State.Previewing)
                        setCurrentSize(mode.size)
                        onVideoSize(mode.size)
                        watchVideoFrames(mode, ticket, lastFrame)
                        Log.i(TAG, "首帧确认: ${identity(device)}, $mode")
                        UvcDiagnostics.event("display frame accepted ticket=$ticket screen=$screenTicket")
                    } else if (isCurrent(ticket)) needsPresentation.set(true)
                }
                fps.record(SystemClock.elapsedRealtimeNanos())?.let { measured ->
                    main.post {
                        if (isCurrent(ticket) && frames.isActive && foreground && state == State.Previewing &&
                            presentationGeneration.get() == screenTicket) {
                            previewFps = measured
                            publishResolutionStatus()
                            Log.d(TAG, "预览提交帧率: %.1f fps, $mode".format(measured))
                        }
                    }
                }
            }
            UvcDiagnostics.event("USB start done ticket=$ticket")
            ensureCurrent(device, ticket)
        } catch (_: StaleSession) {
            frames.invalidate()
            releaseResources()
        } catch (error: Exception) {
            frames.invalidate()
            reportAttemptFailure(device, modes, index, ticket, error)
        } catch (error: LinkageError) {
            frames.invalidate()
            Log.e(TAG, "Native 加载失败", error)
            main.post { if (isCurrent(ticket)) finishFailure(device, "Native 加载失败") }
        }
    }

    private fun reportAttemptFailure(device: UsbDevice, modes: List<PreviewMode>?, index: Int,
                                     ticket: Long, error: Throwable) {
        Log.w(TAG, "本次开流失败", error)
        UvcDiagnostics.event("USB attempt failed ticket=$ticket ${error.javaClass.simpleName}: ${error.message}")
        main.post {
            if (isCurrent(ticket)) retryOrFail(device, modes.orEmpty(), index, error.toString())
        }
    }

    private fun retryOrFail(device: UsbDevice, modes: List<PreviewMode>, index: Int, reason: String) {
        Log.w(TAG, reason)
        lease?.invalidate() // 先禁止旧帧显示，再排队停止旧相机。
        renderer.clear()
        setCurrentSize(null)
        onIdle()
        if (index + 1 < modes.size && foreground && surface?.isValid == true &&
            access.isConnected(device) && access.hasPermission(device) && access.hasCameraPermission()) {
            beginAttempt(device, modes, index + 1)
        } else finishFailure(device, "候选模式已用完或连接已失效")
    }

    private fun finishFailure(device: UsbDevice, reason: String) {
        Log.e(TAG, reason)
        UvcDiagnostics.event("USB attempt cancelled: $reason")
        invalidateAttempt()
        selected = null
        failedDevice = identity(device)
        changeState(State.Error)
        renderer.clear()
        setCurrentSize(null)
        onIdle()
        val ticket = generation.get()
        worker.execute {
            val released = releaseResources()
            main.post { if (released && isCurrent(ticket)) setConnectionHeld(false) }
        }
    }

    /** 只观察帧是否持续到达，不通过黑色像素猜测 HDMI 状态。正常黑画面也是有效视频。 */
    private fun watchVideoFrames(mode: PreviewMode, ticket: Long, lastFrame: AtomicLong) {
        cancelFrameMonitor()
        frameMonitor = object : Runnable {
            override fun run() {
                if (!isCurrent(ticket) || !foreground) return
                val hasFrames = SystemClock.uptimeMillis() - lastFrame.get() < SIGNAL_TIMEOUT_MS
                if (!hasFrames && state == State.Previewing) {
                    UvcDiagnostics.event("display stalled inputAlive=${lease?.hasRecentInput() == true}")
                    changeState(State.WaitingFrames)
                    renderer.clear()
                    setCurrentSize(null)
                    onIdle()
                } else if (hasFrames && state == State.WaitingFrames) {
                    changeState(State.Previewing)
                    setCurrentSize(mode.size)
                    onVideoSize(mode.size)
                }
                main.postDelayed(this, 500L)
            }
        }.also { main.postDelayed(it, 500L) }
    }

    private fun cancelFrameMonitor() {
        frameMonitor?.let { main.removeCallbacks(it) }
        frameMonitor = null
    }

    private fun scheduleWatchdog(delay: Long, ticket: Long, expired: () -> Unit) {
        cancelWatchdog()
        watchdog = Runnable { if (isCurrent(ticket)) expired() }.also { main.postDelayed(it, delay) }
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    private fun invalidateAttempt(): Long {
        cancelWatchdog()
        cancelFrameMonitor()
        lease?.invalidate()
        lease = null
        activeAttempt = null
        return generation.incrementAndGet()
    }

    private fun ensureCurrent(device: UsbDevice, ticket: Long) {
        if (generation.get() != ticket || !access.isConnected(device) ||
            !access.hasCameraPermission() || !access.hasPermission(device)) throw StaleSession()
    }

    private fun stopSession() {
        val ticket = invalidateAttempt()
        selected = null
        changeState(State.Stopping)
        renderer.clear()
        setCurrentSize(null)
        onIdle()
        worker.execute {
            val released = releaseResources()
            main.post {
                if (isCurrent(ticket)) {
                    changeState(if (released) State.Idle else State.Error)
                    if (released) setConnectionHeld(false)
                }
            }
        }
    }

    /** 清理抛异常时保留资源引用，不允许把仍可能活动的 Native Surface 提前关闭。 */
    private fun releaseResources(): Boolean = try {
        session?.let {
            UvcDiagnostics.event("USB close begin")
            it.close()
            UvcDiagnostics.event("USB close done")
        }
        session = null
        true
    } catch (error: Exception) {
        Log.e(TAG, "Native 清理失败，保留离屏资源，禁止开启新会话", error)
        false
    } catch (error: LinkageError) {
        Log.e(TAG, "Native 清理接口不可用", error)
        false
    }

    fun destroy() {
        checkMainThread()
        if (destroyed) return
        destroyed = true
        invalidateAttempt()
        selected = null
        surface = null
        renderer.setSurface(null)
        renderer.release()
        worker.execute {
            if (releaseResources()) {
                try { access.destroyMonitor() } catch (error: Exception) {
                    Log.w(TAG, "USBMonitor 清理失败", error)
                }
            }
            main.post { setConnectionHeld(false) }
        }
        // 不调用 get/join/awaitTermination；也不使用 shutdownNow 中断 Native 清理。
        worker.shutdown()
    }

    private fun isCurrent(ticket: Long) = !destroyed && generation.get() == ticket
    private fun identity(device: UsbDevice) = "${device.deviceName}:${device.vendorId}:${device.productId}"
    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
    private fun changeState(next: State) {
        if (state != next) UvcDiagnostics.event("state $state -> $next")
        state = next
    }
    private class StaleSession : Exception()

    companion object {
        private const val TAG = "MinimalUvc"
        private const val FIRST_FRAME_TIMEOUT_MS = 4000L
        private const val OPEN_TIMEOUT_MS = 10000L
        private const val SIGNAL_TIMEOUT_MS = 3000L
    }
}
