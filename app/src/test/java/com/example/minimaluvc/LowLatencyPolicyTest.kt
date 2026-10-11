package com.example.minimaluvc

import org.junit.Assert.*
import org.junit.Test

class LowLatencyPolicyTest {
    @Test fun burstsScheduleOnlyOneDrawAndReadLatestState() {
        val queue = ArrayDeque<() -> Unit>()
        var latest = 0
        val shown = mutableListOf<Int>()
        val dispatcher = LatestFrameDispatcher({ queue.addLast(it); true }) { shown.add(latest) }
        repeat(100) { latest = it; dispatcher.request() }
        assertEquals(1, queue.size)
        queue.removeFirst().invoke()
        assertEquals(listOf(99), shown)
        latest = 100
        dispatcher.request()
        queue.removeFirst().invoke()
        assertEquals(listOf(99, 100), shown)
    }

    @Test fun closingCancelsPendingDrawAndFutureNotifications() {
        val queue = ArrayDeque<() -> Unit>()
        var calls = 0
        val dispatcher = LatestFrameDispatcher({ queue.addLast(it); true }) { calls++ }
        dispatcher.request()
        dispatcher.close()
        queue.removeFirst().invoke()
        dispatcher.request()
        assertEquals(0, calls)
        assertTrue(queue.isEmpty())
    }

    @Test fun measuredFpsComesFromFramesAndElapsedTimeInsteadOfRequestedSixty() {
        val fps = FrameRateSampler()
        assertNull(fps.record(0))
        for (i in 1..29) assertNull(fps.record(i * 33_333_334L))
        assertEquals(30.0, fps.record(1_000_000_020L)!!, 0.01)
    }

    @Test fun descriptorSixtyAndThirtyRemainSeparateModes() {
        val size = VideoSize(2560, 1600)
        val modes = UvcFrameRates.enrich(listOf(PreviewMode(size, 1)), stream(166666, 333333))
        assertEquals(2, modes.size)
        assertTrue(modes.any { it.minFps == 60 && it.maxFps == 60 })
        assertTrue(modes.any { it.minFps == 30 && it.maxFps == 30 })
        assertEquals(60.0, PreviewPolicy.rankModes(modes).first().declaredFps!!, 0.01)
    }

    @Test fun thirtyOnlyCardDoesNotClaimSixtySupport() {
        val modes = UvcFrameRates.enrich(listOf(PreviewMode(VideoSize(2560, 1600), 1)), stream(333333))
        assertEquals(1, modes.size)
        assertEquals(30, modes.single().maxFps)
        assertEquals(30.0, modes.single().declaredFps!!, 0.01)
    }

    @Test fun fractionalSixtyUsesIntegerRangeAcceptedByLibuvc() {
        val mode = UvcFrameRates.enrich(listOf(PreviewMode(VideoSize(2560, 1600), 1)), stream(166833)).single()
        assertEquals(59, mode.minFps)
        assertEquals(59, mode.maxFps)
        assertEquals(59.94, mode.declaredFps!!, 0.02)
    }

    @Test fun missingOrTruncatedDescriptorsKeepCapabilityUnknownAndTrySixtyFirst() {
        val base = listOf(PreviewMode(VideoSize(2560, 1600), 1))
        for (raw in listOf(byteArrayOf(), stream(166666).dropLast(1).toByteArray(), byteArrayOf(0, 36))) {
            val modes = UvcFrameRates.enrich(base, raw)
            assertTrue(modes.all { it.declaredFps == null })
            assertTrue(modes.first().minFps >= 59)
            assertTrue(modes.first().maxFps >= 60)
        }
    }

    @Test fun continuousSixtyRangeUsesAnIntervalTheNativeIntegerFpsApiCanRequest() {
        val raw = stream().copyOf(20 + 38)
        raw[20] = 38
        listOf(26 to 166666, 30 to 333333, 34 to 1).forEach { (offset, value) ->
            repeat(4) { b -> raw[20 + offset + b] = (value ushr (8 * b)).toByte() }
        }
        val modes = UvcFrameRates.enrich(listOf(PreviewMode(VideoSize(2560, 1600), 1)), raw)
        assertEquals(60, PreviewPolicy.rankModes(modes).first().minFps)
        assertTrue(modes.all { mode ->
            val nativeInterval = 10_000_000L / mode.maxFps
            kotlin.math.abs(10_000_000.0 / nativeInterval - mode.declaredFps!!) < 0.000001
        })
    }

    private fun stream(vararg intervals: Int): ByteArray {
        val iface = byteArrayOf(9, 4, 1, 0, 1, 14, 2, 0, 0)
        val format = byteArrayOf(11, 36, 6, 1, 1, 0, 1, 0, 0, 0, 0)
        val frame = ByteArray(26 + intervals.size * 4)
        frame[0] = frame.size.toByte(); frame[1] = 36; frame[2] = 7; frame[3] = 1
        frame[5] = 0; frame[6] = 10 // 2560
        frame[7] = 0x40; frame[8] = 6 // 1600
        frame[25] = intervals.size.toByte()
        intervals.forEachIndexed { index, value ->
            repeat(4) { b -> frame[26 + index * 4 + b] = (value ushr (8 * b)).toByte() }
        }
        return iface + format + frame
    }
}
