package com.example.minimaluvc

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** 多次帧通知合并为一个待绘制任务；任务执行时读取最新纹理，不排队播放旧画面。 */
class LatestFrameDispatcher(private val post: (() -> Unit) -> Boolean, private val draw: () -> Unit) {
    private val pending = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    fun request() {
        if (closed.get() || !pending.compareAndSet(false, true)) return
        if (!post {
            pending.set(false)
            if (!closed.get()) draw()
        }) pending.set(false)
    }
    fun close() { closed.set(true) }
}

/** 统计成功提交到显示 Surface 的帧率，不把请求帧率或屏幕 Hz 当作实测 fps。 */
class FrameRateSampler {
    private var start: Long? = null
    private var count = 0
    @Synchronized fun reset() { start = null; count = 0 }
    @Synchronized fun record(nanos: Long): Double? {
        val before = start
        if (before == null || nanos < before) {
            start = nanos; count = 0
            return null
        }
        count++
        val elapsed = nanos - before
        if (elapsed < 1_000_000_000L) return null
        val fps = count * 1_000_000_000.0 / elapsed
        start = nanos; count = 0
        return fps
    }
}

/** UVC VideoStreaming 帧描述符中的 100ns 帧间隔；不根据 HDMI 宣传规格推断 USB 帧率。 */
object UvcFrameRates {
    private data class Key(val size: VideoSize, val format: Int)

    fun enrich(base: List<PreviewMode>, raw: ByteArray): List<PreviewMode> {
        val intervals = linkedMapOf<Key, MutableSet<Long>>()
        var offset = 0
        var video = false
        var format = -1
        fun u8(i: Int) = raw[offset + i].toInt() and 255
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u32(i: Int) = (0..3).fold(0L) { value, b -> value or (u8(i + b).toLong() shl (8 * b)) }
        while (offset + 2 <= raw.size) {
            val length = u8(0)
            if (length < 2 || offset + length > raw.size) break
            val type = u8(1)
            if (type == 4 && length >= 9) {
                video = u8(5) == 14 && u8(6) == 2
                format = -1
            } else if (video && type == 36 && length >= 3) {
                when (u8(2)) {
                    6 -> format = if (length >= 11) 1 else -1 // MJPEG
                    4 -> { // 只接受 YUY2 GUID；NV12 等不能当作当前库的 YUYV。
                        val guid = byteArrayOf(89, 85, 89, 50, 0, 0, 16, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
                        format = if (length >= 27 && raw.copyOfRange(offset + 5, offset + 21).contentEquals(guid)) 0 else -1
                    }
                    5, 7 -> if (length >= 26 && ((format == 0 && u8(2) == 5) || (format == 1 && u8(2) == 7))) {
                        val key = Key(VideoSize(u16(5), u16(7)), format)
                        val count = u8(25)
                        val values = mutableListOf<Long>()
                        if (count > 0 && length >= 26 + count * 4) {
                            repeat(count) { values.add(u32(26 + it * 4)) }
                        } else if (count == 0 && length >= 38) {
                            val min = u32(26); val max = u32(30); val step = u32(34)
                            if (min > 0 && max >= min) {
                                // Native 的连续区间按整数 fps 计算 interval，必须筛出它真正能请求的点。
                                // 不能把四舍五入的 166667 当成 60fps 传入后变成 59fps。
                                val nativeIntervals = (1..240).map { 10_000_000L / it }.filter {
                                    it in min..max && (it == min || (step > 0 && (it - min) % step == 0L))
                                }
                                nativeIntervals.minByOrNull { abs(10_000_000.0 / it - 60) }?.let(values::add)
                                nativeIntervals.minByOrNull { abs(10_000_000.0 / it - 30) }?.let(values::add)
                                nativeIntervals.minOrNull()?.let(values::add)
                                nativeIntervals.maxOrNull()?.let(values::add)
                            }
                        }
                        values.filter { it > 0 && 10_000_000.0 / it in 1.0..240.5 }.forEach {
                            intervals.getOrPut(key) { linkedSetOf() }.add(it)
                        }
                    }
                    // 不支持的格式必须中断旧格式归属，不能误解其后面的帧。
                    0x10, 0x12 -> format = -1
                }
            }
            offset += length
        }
        return base.distinctBy { Key(it.size, it.frameFormat) }.flatMap { original ->
            val known = intervals[Key(original.size, original.frameFormat)].orEmpty()
            if (known.isEmpty()) listOf(
                original.copy(minFps = 59, maxFps = 61, declaredFps = null),
                original.copy(minFps = 1, maxFps = 60, declaredFps = null))
            else known.sortedBy { abs(10_000_000.0 / it - 60) }.map { interval ->
                // libuvc 用整数 10000000 / interval 匹配 fps；59.94 的整数值为 59。
                val integerFps = (10_000_000L / interval).toInt().coerceAtLeast(1)
                original.copy(minFps = integerFps, maxFps = integerFps, declaredFps = 10_000_000.0 / interval)
            }
        }
    }
}
