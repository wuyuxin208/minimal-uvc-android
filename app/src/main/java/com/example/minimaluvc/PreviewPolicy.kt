package com.example.minimaluvc

import kotlin.math.min
import kotlin.math.abs

/** STRETCH 拉伸铺满；FIT 保持原始视频宽高比例，不裁剪画面。 */
enum class DisplayMode { STRETCH, FIT }
data class VideoSize(val width: Int, val height: Int)
data class PreviewMode(val size: VideoSize, val frameFormat: Int,
    val minFps: Int = 1, val maxFps: Int = 60, val declaredFps: Double? = null)
/** current 只有绘制有效帧后才有值；preferred=null 表示自动（优先 2560×1600）。 */
data class ResolutionStatus(val available: List<VideoSize>, val current: VideoSize?, val preferred: VideoSize?,
    val previewFps: Double? = null, val deviceFps: Map<VideoSize, Double?> = emptyMap())

/** 不依赖 Android 或 Native 的显示及设备策略，可在 JVM 上独立测试。 */
object PreviewPolicy {
    // 用户指定的 2K 尺寸是 16:10 的 2560×1600，不是 2560×1440。
    val DEFAULT_SIZE = VideoSize(2560, 1600)
    fun displaySize(container: VideoSize, video: VideoSize, mode: DisplayMode): VideoSize {
        require(container.width > 0 && container.height > 0) { "容器宽高必须为正数" }
        require(video.width > 0 && video.height > 0) { "视频宽高必须为正数" }
        if (mode == DisplayMode.STRETCH) return container
        val scale = min(container.width.toDouble() / video.width,
            container.height.toDouble() / video.height)
        // 向下取整保证不溢出容器；极小窗口至少保留一个像素。
        return VideoSize(
            (video.width * scale).toInt().coerceIn(1, container.width),
            (video.height * scale).toInt().coerceIn(1, container.height))
    }

    fun rankModes(modes: List<PreviewMode>, preferred: VideoSize? = null): List<PreviewMode> = modes
        .filter { it.size.width > 0 && it.size.height > 0 && it.frameFormat in 0..1 }
        // 手动指定时只协商该尺寸的格式，绝不把低分辨率伪装成用户选择的尺寸。
        .filter { preferred == null || it.size == preferred }
        .distinct()
        .sortedWith(compareBy<PreviewMode>(
            { if (it.size == DEFAULT_SIZE) 0 else if (area(it.size) <= area(DEFAULT_SIZE)) 1 else 2 },
            // 自动模式：精确 2560×1600 → 从大到小尝试较低尺寸 → 最后尝试较高尺寸。
            { if (area(it.size) <= area(DEFAULT_SIZE)) -area(it.size) else area(it.size) },
            { it.declaredFps?.let { fps -> abs(fps - 60.0) } ?: if (it.minFps >= 59) 0.5 else 1000.0 },
            // AndroidUSBCamera 常量：MJPEG=1、YUYV=0；不引用 Native 类，避免 JVM 测试加载 .so。
            { if (it.frameFormat == 1) 0 else 1 },
            { it.size.width }, { it.size.height }))

    private fun area(size: VideoSize) = size.width.toLong() * size.height

    fun isUvc(deviceClass: Int, interfaceClasses: Iterable<Int>): Boolean =
        deviceClass == 14 || interfaceClasses.any { it == 14 }
}
