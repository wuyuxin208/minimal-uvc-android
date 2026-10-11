package com.example.minimaluvc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewPolicyTest {
    @Test fun stretchUsesEntireContainerEvenWhenRatiosDiffer() {
        assertEquals(VideoSize(1920, 1080), PreviewPolicy.displaySize(
            VideoSize(1920, 1080), VideoSize(640, 480), DisplayMode.STRETCH))
    }

    @Test fun fitFourByThreeInFullHdLeavesSideBars() {
        assertEquals(VideoSize(1440, 1080), PreviewPolicy.displaySize(
            VideoSize(1920, 1080), VideoSize(640, 480), DisplayMode.FIT))
    }

    @Test fun fitWideVideoInTabletLeavesTopAndBottomBars() {
        assertEquals(VideoSize(2560, 1440), PreviewPolicy.displaySize(
            VideoSize(2560, 1600), VideoSize(1280, 720), DisplayMode.FIT))
    }

    @Test fun fitSameRatioUsesWholeContainer() {
        assertEquals(VideoSize(1920, 1080), PreviewPolicy.displaySize(
            VideoSize(1920, 1080), VideoSize(1280, 720), DisplayMode.FIT))
    }

    @Test fun fitPortraitContainerDoesNotCropWideVideo() {
        assertEquals(VideoSize(1080, 607), PreviewPolicy.displaySize(
            VideoSize(1080, 1920), VideoSize(1920, 1080), DisplayMode.FIT))
    }

    @Test fun tinyContainerNeverCreatesZeroSizedSurface() {
        assertEquals(VideoSize(1, 1), PreviewPolicy.displaySize(
            VideoSize(1, 1), VideoSize(4096, 1), DisplayMode.FIT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroContainerIsRejected() {
        PreviewPolicy.displaySize(VideoSize(0, 1080), VideoSize(640, 480), DisplayMode.FIT)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeVideoSizeIsRejected() {
        PreviewPolicy.displaySize(VideoSize(1920, 1080), VideoSize(-1, 480), DisplayMode.STRETCH)
    }

    @Test fun automaticPrefers2560By1600ThenLowerResolutionWithMjpegFirstAtEachSize() {
        val mjpeg720 = PreviewMode(VideoSize(1280, 720), 1)
        val mjpeg480 = PreviewMode(VideoSize(640, 480), 1)
        val mjpeg1080 = PreviewMode(VideoSize(1920, 1080), 1)
        val yuyv720 = PreviewMode(VideoSize(1280, 720), 0)
        val target = PreviewMode(VideoSize(2560, 1600), 0)
        val targetMjpeg = target.copy(frameFormat = 1)
        val fourK = PreviewMode(VideoSize(3840, 2160), 1)
        assertEquals(listOf(targetMjpeg, target, mjpeg1080, mjpeg720, yuyv720, mjpeg480, fourK),
            PreviewPolicy.rankModes(listOf(yuyv720, mjpeg1080, fourK, mjpeg480,
                target, mjpeg720, targetMjpeg)))
    }

    @Test fun manualResolutionTriesBothFormatsButNeverSilentlyFallsBackToAnotherSize() {
        val wanted = VideoSize(2560, 1600)
        val target = PreviewMode(wanted, 0)
        val compressed = target.copy(frameFormat = 1)
        assertEquals(listOf(compressed, target), PreviewPolicy.rankModes(listOf(
            PreviewMode(VideoSize(1280, 720), 1), target, compressed), wanted))
        assertTrue(PreviewPolicy.rankModes(listOf(target), VideoSize(1920, 1080)).isEmpty())
    }

    @Test fun invalidDuplicateAndUnknownModesAreNotTried() {
        val valid = PreviewMode(VideoSize(640, 480), 0)
        assertEquals(listOf(valid), PreviewPolicy.rankModes(listOf(
            valid, valid, PreviewMode(VideoSize(0, 480), 1),
            PreviewMode(VideoSize(640, -1), 1), PreviewMode(VideoSize(640, 480), 99))))
    }

    @Test fun noDeviceModesDoesNotInventAResolution() {
        assertTrue(PreviewPolicy.rankModes(emptyList()).isEmpty())
    }

    @Test fun areaOrderingUsesLongAndIsDeterministic() {
        val normal = PreviewMode(VideoSize(1280, 720), 1)
        val huge = PreviewMode(VideoSize(Int.MAX_VALUE, Int.MAX_VALUE), 1)
        assertEquals(listOf(normal, huge), PreviewPolicy.rankModes(listOf(huge, normal)))
        val a = PreviewMode(VideoSize(640, 480), 1)
        val b = PreviewMode(VideoSize(480, 640), 1)
        assertEquals(listOf(b, a), PreviewPolicy.rankModes(listOf(a, b)))
    }

    @Test fun compositeVideoDeviceIsDetectedByItsInterface() {
        assertTrue(PreviewPolicy.isUvc(0, listOf(1, 14)))
        assertTrue(PreviewPolicy.isUvc(239, listOf(14, 1)))
        assertTrue(PreviewPolicy.isUvc(14, emptyList()))
        assertFalse(PreviewPolicy.isUvc(0, listOf(8, 3)))
        assertFalse(PreviewPolicy.isUvc(1, listOf(1)))
    }
}
