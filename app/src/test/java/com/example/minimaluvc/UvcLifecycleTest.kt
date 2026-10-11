package com.example.minimaluvc

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class UvcLifecycleTest {
    @Test fun destroyDoesNotWaitForBlockedUsbWorker() = withBlockedWorker { it.destroy() }

    @Test fun losingScreenSurfaceDoesNotWaitForBlockedUsbWorker() = withBlockedWorker {
        it.setSurface(null)
    }

    private fun withBlockedWorker(action: (UvcPreviewController) -> Unit) {
        val access = UsbDeviceAccess(RuntimeEnvironment.getApplication()) {}
        val controller = UvcPreviewController(access, {}, {})
        val field = UvcPreviewController::class.java.getDeclaredField("worker").apply { isAccessible = true }
        val worker = field.get(controller) as ExecutorService
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        worker.execute { entered.countDown(); unblock.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        // 模拟底层 USB 操作迟迟不返回；兜底释放避免旧实现使测试永久挂起。
        val rescue = Thread { Thread.sleep(800); unblock.countDown() }.apply { start() }
        try {
            val started = System.nanoTime()
            action(controller)
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("主线程等待了 ${elapsedMs}ms，不应等待 USB 工作线程", elapsedMs < 300)
        } finally {
            unblock.countDown()
            controller.destroy()
            rescue.join()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
