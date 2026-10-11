package com.example.minimaluvc

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 极简预览页面：有效视频全屏显示；无画面时显示无信号，长按打开菜单。
 * USB 和 Native 生命周期分别委托给 UsbDeviceAccess / UvcPreviewController。
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    // ================== 【拉伸铺满 / 保持比例】切换位置 ==================
    // STRETCH：拉伸视频填满屏幕，输入比例不同会变形（默认）。
    // FIT：保持采集分辨率的宽高比例完整显示，多余空间为黑边。
    // 改为 DisplayMode.FIT 后重新编译即可；不在页面添加切换按钮。
    private val displayMode = DisplayMode.STRETCH
    // =================================================================

    private lateinit var root: FrameLayout
    private lateinit var preview: SurfaceView
    private lateinit var noSignal: View
    private lateinit var usbAccess: UsbDeviceAccess
    private lateinit var controller: UvcPreviewController
    private lateinit var runtime: PreviewRuntime
    private lateinit var runtimeClient: PreviewRuntime.Client
    private lateinit var gestures: GestureDetector
    private var videoSize: VideoSize? = null
    private var menuDialog: Dialog? = null
    private var cameraPermissionRequested = false
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UvcDiagnostics.initialize(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContentView(R.layout.activity_main)
        // 请求 60Hz 显示节奏；这是显示偏好，实际视频 fps 由有效帧单独统计。
        window.attributes = window.attributes.apply { preferredRefreshRate = 60f }
        // 先创建 DecorView 和页面，再设置沉浸式；API 30+ 不可在窗口尚未创建时读取控制器。
        enterImmersive(window)
        root = findViewById(R.id.root)
        preview = findViewById(R.id.preview)
        noSignal = findViewById(R.id.no_signal_overlay)

        runtime = PreviewRuntime.acquire(applicationContext, readSavedResolution())
        usbAccess = runtime.access
        controller = runtime.controller
        runtimeClient = PreviewRuntime.Client(
            video = { size ->
                videoSize = size
                noSignal.visibility = View.GONE
                // 缓冲尺寸使用真实采集尺寸；显示缩放只通过 View 的布局尺寸实现。
                preview.holder.setFixedSize(size.width, size.height)
                updatePreviewLayout()
            },
            idle = {
                videoSize = null
                noSignal.visibility = View.VISIBLE
                // 控制器已清空屏幕，这里恢复无视频时的整屏黑色布局。
                updatePreviewLayout()
            },
            resolution = { status -> updateResolutionMenu(status) })
        runtime.attach(runtimeClient)

        preview.holder.addCallback(this)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePreviewLayout() }
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onLongPress(event: MotionEvent) = showPreviewMenu()
            // 没有 onSingleTap / onDoubleTap 操作：点击不显示任何界面。
        })

        // 正常预览隐藏系统栏；系统边缘手势仍由操作系统管理。
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (started && visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    window.decorView.post { if (started && !isFinishing) enterImmersive(window) }
                }
            }
        }
        cameraPermissionRequested = savedInstanceState?.getBoolean("cameraRequested") ?: false
        requestCameraPermissionIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        started = true
        runtime.start(runtimeClient)
        enterImmersive(window)
    }

    override fun onResume() {
        super.onResume()
        // 部分鸿蒙设备返回桌面后会保留 SurfaceHolder，不再回调 surfaceCreated。
        // 每次恢复主动确认并绑定当前有效 Surface；若窗口尚未创建，则由 surfaceCreated 接续。
        // 控制器会对同一次前台中的重复绑定去重，不会重复打开采集卡。
        preview.holder.surface?.takeIf { it.isValid }?.let { controller.setSurface(it) }
        controller.refreshDevice()
    }

    override fun onStop() {
        started = false
        runtime.stop(runtimeClient)
        super.onStop()
    }

    override fun onDestroy() {
        menuDialog?.setOnDismissListener(null)
        menuDialog?.dismiss()
        menuDialog = null
        preview.holder.removeCallback(this)
        // Home 或系统重建页面只移除 UI 回调；用户明确退出时才释放应用级 USB 会话。
        runtime.pageDestroyed(runtimeClient, isFinishing)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("cameraRequested", cameraPermissionRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 冷启动已有 onStart；singleTask 重用 Activity 时通过这里再次扫描。
        controller.refreshDevice()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive(window)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!started) return
        if (Build.VERSION.SDK_INT >= 30) {
            try { holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) }
            catch (error: RuntimeException) { Log.w(TAG, "系统未接受 60Hz 显示偏好", error) }
        }
        controller.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!started) return
        // 尺寸变化不会再创建相机；已有会话由控制器去重。
        updatePreviewLayout()
        controller.refreshDevice()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!started) return
        // 只解绑屏幕，保持 USB 离屏流；返回前台时同一会话绑定新窗口。
        controller.setSurface(null)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Activity 级手势覆盖视频及 FIT 黑边；所有普通触摸均消费，不弹控制栏。
        if (::gestures.isInitialized) gestures.onTouchEvent(event)
        return true
    }

    @Deprecated("预览页面通过长按菜单中的退出结束")
    override fun onBackPressed() {
        // 预览时按系统返回键没有操作；预览菜单仍可以用返回键取消。
    }

    private fun requestCameraPermissionIfNeeded() {
        // Android 9+、target >= 28 的 USB 视频设备也需要 CAMERA 权限。
        // 不使用系统 Camera2；这个权限只是 UsbManager 授权的系统前置条件。
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
            !cameraPermissionRequested) {
            cameraPermissionRequested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "CAMERA 权限未授予；显示无信号，可在菜单重试或系统设置中授权")
            }
            controller.refreshDevice()
            enterImmersive(window)
        }
    }

    private fun updatePreviewLayout() {
        if (root.width <= 0 || root.height <= 0) return
        val size = videoSize
        val fitted = size?.let {
            PreviewPolicy.displaySize(VideoSize(root.width, root.height), it, displayMode)
        }
        val width = if (displayMode == DisplayMode.FIT && fitted != null) fitted.width else FrameLayout.LayoutParams.MATCH_PARENT
        val height = if (displayMode == DisplayMode.FIT && fitted != null) fitted.height else FrameLayout.LayoutParams.MATCH_PARENT
        val params = preview.layoutParams as FrameLayout.LayoutParams
        // 避免在 SurfaceChanged / Layout 回调中无意义地反复触发布局。
        if (params.width != width || params.height != height || params.gravity != Gravity.CENTER) {
            preview.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
        }
    }

    private fun showPreviewMenu() {
        if (!started || isFinishing || menuDialog?.isShowing == true) return
        val dialog = Dialog(this, R.style.Theme_MinimalUvc_Menu)
        dialog.setContentView(R.layout.dialog_preview_menu)
        dialog.findViewById<View>(R.id.menu_resolution).setOnClickListener { showResolutionPicker() }
        dialog.findViewById<View>(R.id.menu_reconnect).setOnClickListener {
            dialog.dismiss()
            // 这是用户主动重试，允许重新申请上次拒绝的权限；尚在等待授权时不重复申请。
            usbAccess.allowPermissionRetry()
            cameraPermissionRequested = false
            requestCameraPermissionIfNeeded()
            controller.retryConnection()
        }
        dialog.findViewById<View>(R.id.menu_exit).setOnClickListener {
            dialog.dismiss()
            finishAndRemoveTask()
        }
        presentDialog(dialog)
        updateResolutionMenu(controller.resolutionStatus())
    }

    /** 唯一入口仍然是长按；分辨率选项只存在于菜单弹窗中，不覆盖正常视频。 */
    private fun showResolutionPicker() {
        val dialog = Dialog(this, R.style.Theme_MinimalUvc_Menu)
        dialog.setContentView(R.layout.dialog_resolution)
        presentDialog(dialog)
        updateResolutionMenu(controller.resolutionStatus())
    }

    private fun updateResolutionMenu(status: ResolutionStatus) {
        val dialog = menuDialog ?: return
        val actual = status.current?.let { sizeLabel(it) } ?: getString(R.string.no_signal)
        val selection = status.preferred?.let { getString(R.string.resolution_selected, sizeLabel(it)) }
            ?: getString(R.string.resolution_auto_summary)
        val rate = status.previewFps?.let { getString(R.string.resolution_preview_rate, it) }
            ?: getString(R.string.resolution_target_rate)
        dialog.findViewById<TextView>(R.id.menu_resolution_description)?.text =
            getString(R.string.resolution_menu_summary, actual, selection, rate)
        val options = dialog.findViewById<LinearLayout>(R.id.resolution_options)
            ?: run { sizeDialog(dialog); return }
        dialog.findViewById<TextView>(R.id.resolution_status).text =
            getString(R.string.resolution_current, actual, rate)
        dialog.findViewById<TextView>(R.id.resolution_source).text = getString(
            if (status.preferred != null && status.available.isNotEmpty() && status.preferred !in status.available)
                R.string.resolution_unavailable else R.string.resolution_source)
        dialog.findViewById<View>(R.id.resolution_empty).visibility =
            if (status.available.isEmpty()) View.VISIBLE else View.GONE
        // 帧率每秒更新时只更新文字，不重建选项或打断用户正在滚动的位置。
        val optionsKey = Triple(status.available, status.preferred, status.deviceFps)
        if (options.tag == optionsKey) return
        options.tag = optionsKey
        options.removeAllViews()
        // null 是“自动”策略；后面的尺寸全部由 USB 描述符生成，不加入固定的虚假选项。
        (listOf<VideoSize?>(null) + status.available).forEach { size ->
            val row = layoutInflater.inflate(R.layout.item_resolution, options, false)
            row.findViewById<TextView>(R.id.resolution_option_title).text =
                size?.let { sizeLabel(it) } ?: getString(R.string.resolution_auto)
            row.findViewById<TextView>(R.id.resolution_option_description).text =
                if (size == null) getString(R.string.resolution_auto_description)
                else status.deviceFps[size]?.let { getString(R.string.resolution_device_rate, it) }
                    ?: getString(R.string.resolution_unknown_rate)
            row.findViewById<View>(R.id.resolution_option_check).visibility =
                if (size == status.preferred) View.VISIBLE else View.INVISIBLE
            row.isSelected = size == status.preferred
            row.setOnClickListener {
                if (controller.selectResolution(size)) {
                    // 记住选择，下次启动沿用；“自动”删除固定尺寸，恢复默认 2560×1600 优先。
                    getSharedPreferences("preview_settings", MODE_PRIVATE).edit().apply {
                        if (size == null) {
                            remove("resolution_width"); remove("resolution_height")
                        } else {
                            putInt("resolution_width", size.width); putInt("resolution_height", size.height)
                        }
                    }.apply()
                    dialog.dismiss()
                } else updateResolutionMenu(controller.resolutionStatus()) // 弹窗打开时拔卡的情况。
            }
            options.addView(row)
        }
        sizeDialog(dialog)
    }

    private fun sizeLabel(size: VideoSize) = getString(R.string.resolution_size, size.width, size.height)

    private fun readSavedResolution(): VideoSize? {
        val settings = getSharedPreferences("preview_settings", MODE_PRIVATE)
        val width = settings.getInt("resolution_width", 0)
        val height = settings.getInt("resolution_height", 0)
        return if (width > 0 && height > 0) VideoSize(width, height) else null
    }

    private fun presentDialog(dialog: Dialog) {
        menuDialog?.dismiss()
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            // Android 会异步回调旧弹窗的关闭事件，不能清掉随后显示的新分辨率弹窗。
            if (menuDialog === dialog) {
                menuDialog = null
                if (started && !isFinishing) enterImmersive(window)
            }
        }
        menuDialog = dialog
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.show()
        sizeDialog(dialog)
        dialog.window?.let { target ->
            target.attributes = target.attributes.apply { dimAmount = 0.55f }
            enterImmersive(target)
            target.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            enterImmersive(target)
        }
    }

    private fun sizeDialog(dialog: Dialog) {
        if (!dialog.isShowing) return
        val target = dialog.window ?: return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val screenWidth = root.width.takeIf { it > 0 } ?: metrics.widthPixels
        val screenHeight = root.height.takeIf { it > 0 } ?: metrics.heightPixels
        val width = minOf((400 * density).roundToInt(), screenWidth - (48 * density).roundToInt()).coerceAtLeast(1)
        val maxHeight = (screenHeight - (64 * density).roundToInt()).coerceAtLeast(1)
        target.decorView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        // 限制弹窗高度，尺寸较多时在 ScrollView 内滚动，保持标题和状态可见。
        target.setLayout(width, target.decorView.measuredHeight.coerceIn(1, maxHeight))
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive(target: Window) {
        // getDecorView 会确保窗口视图已创建。部分系统的 Window.getInsetsController()
        // 会直接访问尚未初始化的 mDecor，启动时调用即触发空指针。
        // 从 View 获取控制器：未附着到窗口时可以为空，获得焦点后会再次执行。
        val decor = target.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            target.setDecorFitsSystemWindows(false)
            decor.windowInsetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            decor.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    companion object {
        private const val TAG = "MinimalUvc"
        private const val CAMERA_REQUEST = 100
    }
}
