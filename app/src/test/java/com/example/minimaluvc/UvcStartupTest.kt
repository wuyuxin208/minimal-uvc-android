package com.example.minimaluvc

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.os.Parcelable
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** 用可控制的硬件替身验证真实控制器的首帧、超时、回退及过期回调逻辑。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class UvcStartupTest {
    private val mjpeg = PreviewMode(VideoSize(1280, 720), 1)
    private val yuyv = PreviewMode(VideoSize(640, 480), 0)
    private val attempts = CopyOnWriteArrayList<Attempt>()
    private val sizes = mutableListOf<VideoSize>()
    private var supported = listOf(mjpeg, yuyv)
    private var displaying = false
    private var rejectStart = false
    private var rejectClose = false
    private var closeCalls = 0
    private lateinit var controller: UvcPreviewController
    private lateinit var worker: ExecutorService
    private lateinit var screen: Surface

    private class Attempt(val mode: PreviewMode, val lease: FrameLease, onFrame: () -> Unit) {
        val frame: () -> Unit = { if (lease.isActive) lease.noteInput(); onFrame() }
        var closed = false
    }

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val constructor = UsbDevice::class.java.getDeclaredConstructor(String::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, String::class.java, String::class.java, String::class.java)
        val device = constructor.newInstance("/dev/bus/usb/001/002", 1234, 5678, 14, 0, 0,
            "Test", "UVC", "1.0", "test")
        UsbDevice::class.java.getMethod("setConfigurations", Array<Parcelable>::class.java)
            .invoke(device, emptyArray<Parcelable>())
        shadowOf(app.getSystemService(UsbManager::class.java)).addOrUpdateUsbDevice(device, true)
        val access = UsbDeviceAccess(app) {}
        controller = UvcPreviewController(access, { sizes.add(it); displaying = true },
            { displaying = false }, createSession = {
            object : PreviewSession {
                var attempt: Attempt? = null
                override fun open(device: UsbDevice) = Unit
                override fun supportedModes() = supported
                override fun start(mode: PreviewMode, lease: FrameLease, onFrame: () -> Unit) {
                    attempt = Attempt(mode, lease, onFrame).also { attempts.add(it) }
                    if (rejectStart) throw IllegalArgumentException("模拟设备拒绝模式")
                }
                override fun close() {
                    closeCalls++
                    if (rejectClose) throw IllegalStateException("模拟 Native 释放失败")
                    attempt?.closed = true
                }
            }
        })
        worker = UvcPreviewController::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(controller) as ExecutorService
        screen = Surface(SurfaceTexture(0))
        assertTrue(screen.isValid)
        controller.setSurface(screen)
    }

    @After fun teardown() {
        controller.destroy()
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        screen.release()
    }

    @Test fun startReturnIsNotTreatedAsFirstVideoFrame() {
        start()
        assertEquals(1, attempts.size)
        assertTrue("没有帧时不能报告预览成功", sizes.isEmpty())
        attempts.single().frame()
        pump()
        assertEquals(listOf(mjpeg.size), sizes)
        attempts.single().frame()
        pump()
        assertEquals("连续帧不重复触发布局", 1, sizes.size)
    }

    @Test fun noFirstFrameFallsBackToYuyvAndClosesPreviousSession() {
        start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        pump()
        assertEquals(listOf(mjpeg, yuyv), attempts.map { it.mode })
        assertTrue(attempts.first().closed)
        assertFalse(attempts.first().lease.isActive)
        // 已超时的旧回调不能把新会话误报为成功。
        attempts.first().frame()
        pump()
        assertTrue(sizes.isEmpty())
        attempts.last().frame()
        pump()
        assertEquals(listOf(yuyv.size), sizes)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        pump()
        assertEquals(2, attempts.size)
    }

    @Test fun allModesFailOnceThenStopInsteadOfRetryingForever() {
        start()
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            pump()
        }
        assertEquals(listOf(mjpeg, yuyv), attempts.map { it.mode })
        assertTrue(attempts.all { it.closed && !it.lease.isActive })
        assertTrue(sizes.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        controller.refreshDevice()
        pump()
        assertEquals(2, attempts.size)
    }

    @Test fun surfaceLossCancelsTimeoutAndKeepsUsbStreamWithoutDisplayingLateFrame() {
        start()
        controller.setSurface(null)
        attempts.single().frame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        pump()
        assertEquals(1, attempts.size)
        assertTrue(sizes.isEmpty())
        assertFalse(attempts.single().closed)
        assertTrue(attempts.single().lease.isActive)
    }

    @Test fun firstFrameAfterBackgroundCannotRestorePreview() {
        start()
        controller.setForeground(false)
        attempts.single().frame()
        pump()
        assertTrue(sizes.isEmpty())
        assertFalse(attempts.single().closed)
    }

    @Test fun returningFromHomeWaitsForReboundScreenAndRestoresVideo() {
        start()
        val old = attempts.single()
        old.frame()
        pump()
        assertTrue(displaying)
        controller.setForeground(false)
        pump()
        assertFalse(old.closed)
        controller.setForeground(true)
        pump()
        assertEquals("返回前台不能拿上一轮的 Surface 提前开流", 1, attempts.size)
        controller.setSurface(screen)
        pump()
        assertEquals("恢复显示不能重开采集卡", 1, attempts.size)
        attempts.last().frame()
        pump()
        assertTrue(displaying)
        assertEquals(mjpeg.size, controller.resolutionStatus().current)
    }

    @Test fun tenHomeRoundTripsRestoreBothRetainedAndRecreatedSurfaces() {
        start()
        attempts.last().frame()
        pump()
        repeat(10) { cycle ->
            val old = attempts.last()
            controller.setForeground(false)
            pump()
            assertTrue(old.lease.isActive)
            assertFalse(old.closed)
            controller.setForeground(true)
            pump()
            assertEquals(1, attempts.size)
            if (cycle % 2 == 0) {
                screen.release()
                screen = Surface(SurfaceTexture(0))
            }
            controller.setSurface(screen)
            controller.setSurface(screen) // surfaceCreated 与 onResume 同时绑定也只能开一次。
            pump()
            assertEquals(1, attempts.size)
            assertFalse(displaying)
            attempts.last().frame()
            pump()
            assertTrue(displaying)
        }
    }

    @Test fun homeAndReconnectReuseLiveStreamEvenIfNativeCloseWouldFail() {
        start()
        val active = attempts.single()
        active.frame()
        pump()
        rejectClose = true // 模拟停止会话无法完成的采集卡；Home 不应调用它。
        try {
            controller.setForeground(false)
            controller.setSurface(null)
            pump()
            assertEquals("返回桌面不应停止 USB", 0, closeCalls)
            controller.setForeground(true)
            controller.setSurface(screen)
            active.frame()
            pump()
            assertTrue(displaying)
            controller.retryConnection()
            pump()
            assertEquals("有活跃 USB 帧时重连应重绑显示，不破坏采集连接", 0, closeCalls)
            assertEquals(1, attempts.size)
            active.frame()
            pump()
            assertTrue(displaying)
        } finally { rejectClose = false }
    }

    @Test fun synchronousModeRejectionAlsoFallsBackAndStops() {
        rejectStart = true
        start()
        assertEquals(listOf(mjpeg, yuyv), attempts.map { it.mode })
        assertTrue(attempts.all { it.closed && !it.lease.isActive })
        assertTrue(sizes.isEmpty())
    }

    @Test fun cleanupFailureMustNotOpenAnotherCameraOverOldResources() {
        start()
        rejectClose = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        pump()
        assertEquals(1, attempts.size)
        assertFalse(attempts.single().lease.isActive)
        assertTrue(sizes.isEmpty())
        rejectClose = false // 测试结束后允许释放。
    }

    @Test fun losingFramesShowsNoSignalAndFreshFramesRestoreWithoutReopening() {
        start()
        attempts.single().frame()
        pump()
        assertTrue(displaying)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        pump()
        assertFalse("持续没有新帧时应显示无信号", displaying)
        attempts.single().frame()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        pump()
        assertTrue("恢复视频帧后应自动隐藏无信号", displaying)
        assertEquals("恢复视频不应反复重开 USB", 1, attempts.size)
    }

    @Test fun regularVideoFramesKeepSignalAvailable() {
        start()
        repeat(6) {
            attempts.single().frame()
            pump()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            pump()
            assertTrue(displaying)
        }
        assertEquals(1, sizes.size)
    }

    @Test fun defaultOpens2560By1600AndPublishesActualUniqueDeviceResolutions() {
        val target = PreviewMode(VideoSize(2560, 1600), 1)
        supported = listOf(mjpeg, target, target.copy(frameFormat = 0), yuyv)
        start()
        assertEquals(target, attempts.single().mode)
        assertEquals(listOf(target.size, mjpeg.size, yuyv.size), controller.resolutionStatus().available)
        assertNull("首帧之前不能把请求尺寸当作实际尺寸", controller.resolutionStatus().current)
        attempts.single().frame()
        pump()
        assertEquals(target.size, controller.resolutionStatus().current)
    }

    @Test fun resolutionSwitchClosesOldStreamAndLateOldFrameCannotReportNewSize() {
        val target = PreviewMode(VideoSize(2560, 1600), 1)
        supported = listOf(target, mjpeg)
        start()
        val old = attempts.single()
        old.frame()
        pump()
        assertTrue(controller.selectResolution(mjpeg.size))
        assertNull(controller.resolutionStatus().current)
        pump()
        assertTrue(old.closed)
        assertFalse(old.lease.isActive)
        assertEquals(listOf(target, mjpeg), attempts.map { it.mode })
        old.frame()
        pump()
        assertNull(controller.resolutionStatus().current)
        attempts.last().frame()
        pump()
        assertEquals(mjpeg.size, controller.resolutionStatus().current)
        assertEquals(mjpeg.size, controller.resolutionStatus().preferred)
    }

    @Test fun unavailableResolutionIsRejectedWithoutRestartingPreview() {
        start()
        assertFalse(controller.selectResolution(VideoSize(2560, 1600)))
        pump()
        assertEquals(1, attempts.size)
        assertNull(controller.resolutionStatus().preferred)
    }

    @Test fun manualResolutionFailureKeepsNoSignalAndAutoCanRecover() {
        supported = listOf(mjpeg, mjpeg.copy(frameFormat = 0), yuyv)
        start()
        assertTrue(controller.selectResolution(mjpeg.size))
        pump()
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            pump()
        }
        assertEquals(listOf(mjpeg, mjpeg, mjpeg.copy(frameFormat = 0)), attempts.map { it.mode })
        assertNull(controller.resolutionStatus().current)
        assertFalse(displaying)
        assertTrue(controller.selectResolution(null))
        pump()
        assertEquals(mjpeg, attempts.last().mode)
    }

    @Test fun disconnectedCardClearsChoicesSoOldMenuCannotSelectItsResolution() {
        start()
        val app = RuntimeEnvironment.getApplication()
        val usb = shadowOf(app.getSystemService(UsbManager::class.java))
        usb.removeUsbDevice(app.getSystemService(UsbManager::class.java).deviceList.values.single())
        controller.refreshDevice()
        pump()
        assertTrue(controller.resolutionStatus().available.isEmpty())
        assertNull(controller.resolutionStatus().current)
        assertFalse(controller.selectResolution(mjpeg.size))
    }

    @Test fun menuFrameRateIsMeasuredFromRenderedFramesAndClearedWhenSignalStops() {
        start()
        attempts.single().frame()
        pump()
        var previousMs = 0L
        repeat(30) { index ->
            val nowMs = (index + 1) * 1000L / 30
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(nowMs - previousMs))
            previousMs = nowMs
            attempts.single().frame()
            pump()
        }
        assertNotNull(controller.resolutionStatus().previewFps)
        assertEquals(30.0, controller.resolutionStatus().previewFps!!, 0.1)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        pump()
        assertNull(controller.resolutionStatus().previewFps)
    }

    private fun start() {
        controller.setForeground(true)
        pump()
    }

    private fun pump() {
        repeat(4) {
            worker.submit {}.get(3, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
