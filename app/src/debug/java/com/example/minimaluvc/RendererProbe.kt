package com.example.minimaluvc

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.Surface
import android.view.SurfaceView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 真机检查 OES 纹理的颜色、方向和资源释放；不是 USB 或端到端延迟测试。 */
class RendererProbeActivity : Activity() {
    lateinit var preview: SurfaceView
    val ready = CountDownLatch(1)
    var renderer: SurfacePreviewRenderer? = null
    @Volatile var stopped = true
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        preview = SurfaceView(this)
        preview.holder.setFixedSize(2560, 1600)
        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                renderer?.setSurface(holder.surface)
                ready.countDown()
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) { renderer?.setSurface(null) }
        })
        setContentView(preview)
    }
    override fun onResume() {
        super.onResume()
        preview.holder.surface?.takeIf { it.isValid }?.let { renderer?.setSurface(it) }
        stopped = false
    }
    override fun onStop() { renderer?.setSurface(null); stopped = true; super.onStop() }
}

class RendererProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    @android.annotation.TargetApi(26)
    override fun onStart() {
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        var activity: RendererProbeActivity? = null
        var renderer: SurfacePreviewRenderer? = null
        var sink: SurfacePreviewRenderer.FrameSink? = null
        val lease = FrameLease()
        try {
            check(android.os.Build.VERSION.SDK_INT >= 26) { "PixelCopy 检查需要 API 26+" }
            val page = startActivitySync(Intent(targetContext, RendererProbeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as RendererProbeActivity
            activity = page
            check(page.ready.await(5, TimeUnit.SECONDS)) { "测试屏幕创建超时" }
            val output = SurfacePreviewRenderer().also { renderer = it }
            runOnMainSync { page.renderer = output; output.setSurface(page.preview.holder.surface) }
            val frames = AtomicInteger()
            val firstNs = AtomicLong()
            val lastNs = AtomicLong()
            val first = CountDownLatch(1)
            val input = output.createSink(VideoSize(2560, 1600), lease) {
                val now = SystemClock.elapsedRealtimeNanos()
                firstNs.compareAndSet(0, now)
                lastNs.set(now)
                frames.incrementAndGet()
                first.countDown()
            }
            sink = input
            val paint = Paint()
            val started = SystemClock.elapsedRealtimeNanos()
            repeat(61) { index ->
                val canvas = input.surface.lockCanvas(null)
                try {
                    val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
                    paint.color = Color.RED; canvas.drawRect(0f, 0f, w / 2, h / 2, paint)
                    paint.color = Color.GREEN; canvas.drawRect(w / 2, 0f, w, h / 2, paint)
                    paint.color = Color.BLUE; canvas.drawRect(0f, h / 2, w / 2, h, paint)
                    paint.color = Color.WHITE; canvas.drawRect(w / 2, h / 2, w, h, paint)
                } finally { input.surface.unlockCanvasAndPost(canvas) }
                val remaining = started + (index + 1) * 1_000_000_000L / 60 - SystemClock.elapsedRealtimeNanos()
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
            }
            check(first.await(5, TimeUnit.SECONDS)) { "GPU 未提交有效帧" }
            val count = frames.get()
            val elapsed = lastNs.get() - firstNs.get()
            val fps = if (elapsed > 0) (count - 1) * 1_000_000_000.0 / elapsed else 0.0

            // 同一离屏生产端跨十次真实 Home / 返回，不新建 Sink，验证后台排空和窗口重绑。
            // 测试纹理模拟 USB 生产端；不代表真实 UVC 驱动或端到端延迟。
            PreviewConnectionService.update(targetContext, true)
            waitUntil("前台服务通知未出现") {
                targetContext.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 1 }
            }
            repeat(10) { cycle ->
                targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // Home 动画期间失去焦点早于 onStop；等待真正解绑显示后再判断后台帧。
                waitUntil("测试页面没有退到后台") { page.stopped }
                SystemClock.sleep(150)
                val before = frames.get()
                val beforeInput = lease.lastInputUptime
                val hidden = input.surface.lockCanvas(null)
                try { hidden.drawColor(Color.MAGENTA) } finally { input.surface.unlockCanvasAndPost(hidden) }
                waitUntil("第 ${cycle + 1} 次后台未消费新输入") { lease.lastInputUptime > beforeInput }
                SystemClock.sleep(100)
                check(frames.get() == before) { "后台仍向屏幕提交视频" }
                targetContext.startActivity(Intent(targetContext, RendererProbeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                waitUntil("测试页面没有恢复") { !page.stopped && page.hasWindowFocus() && page.preview.holder.surface?.isValid == true }
                drawPattern(input.surface, paint)
                waitUntil("第 ${cycle + 1} 次恢复未显示同一生产端的新帧") { frames.get() > before }
            }
            val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            try {
                val copied = CountDownLatch(1)
                var status = -1
                PixelCopy.request(page.preview.holder.surface, image, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                check(copied.await(5, TimeUnit.SECONDS) && status == PixelCopy.SUCCESS) { "PixelCopy 失败: $status" }
                val expected = listOf(Triple(16, 16, Color.RED), Triple(48, 16, Color.GREEN),
                    Triple(16, 48, Color.BLUE), Triple(48, 48, Color.WHITE))
                for ((x, y, color) in expected) {
                    val actual = image.getPixel(x, y)
                    check(kotlin.math.abs(Color.red(actual) - Color.red(color)) < 25 &&
                        kotlin.math.abs(Color.green(actual) - Color.green(color)) < 25 &&
                        kotlin.math.abs(Color.blue(actual) - Color.blue(color)) < 25) {
                        "颜色或方向错误 ($x,$y): ${Integer.toHexString(actual)}"
                    }
                }
            } finally { image.recycle() }
            result.putString("result", "PASS: 2560x1600 GPU 颜色/方向正确，初始提交 $count 帧 / %.1f fps；同一生产端跨 10 次真实 Home/返回恢复，后台消费不显示，前台服务通知正常；未测试真实 USB 输入或端到端延迟".format(fps))
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("result", "FAIL: ${error.stackTraceToString()}")
        } finally {
            lease.invalidate()
            val output = renderer
            if (output != null) runOnMainSync { output.setSurface(null) }
            sink?.closeAfterNativeStopped()
            output?.release()
            PreviewConnectionService.update(targetContext, false)
            activity?.let { page -> runOnMainSync { page.finish() } }
            finish(code, result)
        }
    }

    private fun waitUntil(message: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000L
        while (!ready()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(20)
        }
    }

    private fun drawPattern(surface: Surface, paint: Paint) {
        val canvas = surface.lockCanvas(null)
        try {
            val w = canvas.width.toFloat(); val h = canvas.height.toFloat()
            paint.color = Color.RED; canvas.drawRect(0f, 0f, w / 2, h / 2, paint)
            paint.color = Color.GREEN; canvas.drawRect(w / 2, 0f, w, h / 2, paint)
            paint.color = Color.BLUE; canvas.drawRect(0f, h / 2, w / 2, h, paint)
            paint.color = Color.WHITE; canvas.drawRect(w / 2, h / 2, w, h, paint)
        } finally { surface.unlockCanvasAndPost(canvas) }
    }
}
