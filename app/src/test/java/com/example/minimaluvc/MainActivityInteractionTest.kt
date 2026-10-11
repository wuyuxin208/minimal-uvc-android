package com.example.minimaluvc

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import org.junit.Assert.assertNotNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

/** Android 模拟框架验证触摸及退出流程；不模拟 UVC 视频帧或鸿蒙系统栏。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityInteractionTest {
    private lateinit var owner: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before fun launchWithoutUsbDevice() {
        owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        activity = owner.get()
    }

    @After fun releaseActivity() {
        if (!activity.isDestroyed) owner.pause().stop().destroy()
        shadowOf(activity.mainLooper).idle()
    }

    @Test fun singleTapDoesNotOpenAnyControlDialog() {
        val time = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, time)
        touch(MotionEvent.ACTION_UP, time)
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(700))
        assertNull(ShadowDialog.getLatestDialog())
        assertFalse(activity.isFinishing)
    }

    @Test fun noDeviceShowsNoSignalAndLongPressHint() {
        val labelId = activity.resources.getIdentifier("no_signal_label", "id", activity.packageName)
        val label = activity.findViewById<TextView>(labelId)
        assertNotNull("无设备时应该显示无信号", label)
        assertEquals("无信号", label.text.toString())
        assertEquals(View.VISIBLE, label.visibility)
        val hintId = activity.resources.getIdentifier("menu_hint", "id", activity.packageName)
        val hint = activity.findViewById<TextView>(hintId)
        assertNotNull("无信号页面应提示长按打开菜单", hint)
        assertEquals("长按屏幕打开菜单", hint.text.toString())
    }

    @Test fun longPressShowsReconnectAndExitMenuAndCancelKeepsActivityAlive() {
        holdScreen()
        val dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        assertNotNull(dialog.findViewById<View>(id("menu_reconnect")))
        assertNotNull(dialog.findViewById<View>(id("menu_exit")))
        dialog.cancel()
        assertFalse(activity.isFinishing)
    }

    @Test fun reconnectMenuItemKeepsActivityAliveWithoutUsb() {
        holdScreen()
        val row = ShadowDialog.getLatestDialog().findViewById<View>(id("menu_reconnect"))
        assertNotNull("菜单应有重新连接选项", row)
        row.performClick()
        shadowOf(activity.mainLooper).idle()
        assertFalse(activity.isFinishing)
        assertFalse(ShadowDialog.getLatestDialog().isShowing)
        assertEquals(View.VISIBLE, activity.findViewById<View>(id("no_signal_overlay")).visibility)
    }

    @Test fun exitItemFinishesActivity() {
        holdScreen()
        val row = ShadowDialog.getLatestDialog().findViewById<View>(id("menu_exit"))
        assertNotNull("菜单中需要退出选项", row)
        row.performClick()
        shadowOf(activity.mainLooper).idle()
        assertTrue(activity.isFinishing)
    }

    @Test fun menuShowsNoSignalInsteadOfClaimingRequestedResolutionIsCurrent() {
        holdScreen()
        val label = ShadowDialog.getLatestDialog().findViewById<TextView>(id("menu_resolution_description"))
        assertNotNull("菜单应显示实际分辨率及当前策略", label)
        assertTrue(label.text.toString().contains("无信号"))
        assertTrue(label.text.toString().contains("2560 × 1600"))
    }

    @Test fun noCardResolutionPickerShowsAutoAndConnectionHintWithoutFakeSizes() {
        holdScreen()
        val row = ShadowDialog.getLatestDialog().findViewById<View>(id("menu_resolution"))
        assertNotNull("菜单需要分辨率入口", row)
        row.performClick()
        val picker = ShadowDialog.getLatestDialog()
        assertTrue(picker.isShowing)
        val options = picker.findViewById<android.widget.LinearLayout>(id("resolution_options"))
        assertEquals("无采集卡时只提供自动策略，不伪造设备模式", 1, options.childCount)
        val empty = picker.findViewById<TextView>(id("resolution_empty"))
        assertEquals(View.VISIBLE, empty.visibility)
        options.getChildAt(0).performClick()
        assertFalse(picker.isShowing)
        assertFalse(activity.isFinishing)
    }

    @Test fun savedResolutionIsRestoredWithoutClaimingItIsCurrentlyReceivingVideo() {
        activity.getSharedPreferences("preview_settings", 0).edit()
            .putInt("resolution_width", 1920).putInt("resolution_height", 1080).commit()
        owner.pause().stop().destroy()
        owner = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        activity = owner.get()
        holdScreen()
        val label = ShadowDialog.getLatestDialog().findViewById<TextView>(id("menu_resolution_description"))
        assertNotNull(label)
        assertTrue(label.text.toString().contains("1920 × 1080"))
        assertTrue(label.text.toString().contains("无信号"))
    }

    @Test fun switchingDialogThenDestroyingActivityClosesResolutionPicker() {
        holdScreen()
        ShadowDialog.getLatestDialog().findViewById<View>(id("menu_resolution")).performClick()
        val picker = ShadowDialog.getLatestDialog()
        // 旧菜单的 onDismiss 通过 Handler 延迟执行，不能清掉新弹窗的生命周期引用。
        shadowOf(activity.mainLooper).idle()
        owner.pause().stop().destroy()
        assertFalse("Activity 销毁应关闭正在显示的分辨率弹窗", picker.isShowing)
    }

    private fun id(name: String): Int = activity.resources.getIdentifier(name, "id", activity.packageName)

    private fun holdScreen() {
        touch(MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis())
        shadowOf(activity.mainLooper).idleFor(Duration.ofMillis(700))
    }

    private fun touch(action: Int, time: Long) {
        val event = MotionEvent.obtain(time, time, action, 100f, 100f, 0)
        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
    }
}
