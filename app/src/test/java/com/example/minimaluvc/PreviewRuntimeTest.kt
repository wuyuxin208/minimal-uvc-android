package com.example.minimaluvc

import android.Manifest
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Looper
import android.os.Parcelable
import android.view.Surface
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** 页面销毁与运行对象分离；验证后台、页面重建和真正退出的不同资源行为。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewRuntimeTest {
    private lateinit var runtime: PreviewRuntime
    private lateinit var screen: Surface
    private lateinit var worker: ExecutorService
    private var starts = 0
    private var closes = 0
    private lateinit var lease: FrameLease
    private lateinit var frame: () -> Unit
    private var oldFrames = 0
    private var newFrames = 0
    private lateinit var old: PreviewRuntime.Client

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
        runtime = PreviewRuntime.acquire(app, null) { _, _ -> object : PreviewSession {
            override fun open(device: UsbDevice) = Unit
            override fun supportedModes() = listOf(PreviewMode(VideoSize(2560, 1600), 1))
            override fun start(mode: PreviewMode, lease: FrameLease, onFrame: () -> Unit) {
                starts++
                this@PreviewRuntimeTest.lease = lease
                frame = { lease.noteInput(); onFrame() }
            }
            override fun close() { closes++ }
        } }
        worker = UvcPreviewController::class.java.getDeclaredField("worker").apply { isAccessible = true }
            .get(runtime.controller) as ExecutorService
        screen = Surface(SurfaceTexture(0))
        old = PreviewRuntime.Client({ oldFrames++ }, {}, {})
        runtime.start(old)
        runtime.controller.setSurface(screen)
        pump()
        frame()
        pump()
    }

    @After fun cleanup() {
        PreviewRuntime.closeCurrent()
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        screen.release()
    }

    @Test fun destroyedBackgroundPageReattachesToSameUsbSessionWithoutOldUiCallbacks() {
        runtime.stop(old)
        runtime.pageDestroyed(old, false)
        pump()
        frame()
        pump()
        assertEquals(1, oldFrames)
        assertEquals(0, closes)
        val restored = PreviewRuntime.acquire(RuntimeEnvironment.getApplication(), null)
        assertSame(runtime, restored)
        val next = PreviewRuntime.Client({ newFrames++ }, {}, {})
        restored.start(next)
        restored.controller.setSurface(screen)
        frame()
        pump()
        assertEquals(1, starts)
        assertEquals(1, oldFrames)
        assertEquals(1, newFrames)
        assertEquals(VideoSize(2560, 1600), restored.controller.resolutionStatus().current)
    }

    @Test fun explicitExitInvalidatesAndClosesUsbSession() {
        runtime.pageDestroyed(old, true)
        assertFalse(lease.isActive)
        assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(1, closes)
    }

    @Test fun lateOldPageDestroyCannotCloseNewPagesConnection() {
        runtime.stop(old)
        val next = PreviewRuntime.Client({ newFrames++ }, {}, {})
        runtime.start(next)
        runtime.controller.setSurface(screen)
        runtime.pageDestroyed(old, true)
        pump()
        assertTrue(lease.isActive)
        assertEquals(0, closes)
        frame()
        pump()
        assertEquals(1, newFrames)
    }

    @Test fun unplugInBackgroundClosesAndReleasesRuntime() {
        runtime.stop(old)
        val app = RuntimeEnvironment.getApplication()
        val usb = app.getSystemService(UsbManager::class.java)
        shadowOf(usb).removeUsbDevice(usb.deviceList.values.single())
        runtime.controller.refreshDevice()
        pump()
        assertFalse(lease.isActive)
        assertEquals(1, closes)
    }

    private fun pump() {
        repeat(4) {
            if (!worker.isShutdown) worker.submit {}.get(3, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
