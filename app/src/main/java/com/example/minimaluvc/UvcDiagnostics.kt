package com.example.minimaluvc

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque

/** 有界状态记录。部分鸿蒙设备不输出应用 Logcat，因此另存应用私有偏好；不保存视频。 */
object UvcDiagnostics {
    private var preferences: SharedPreferences? = null
    private val history = ArrayDeque<String>()
    @Synchronized fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences("uvc_diagnostics", Context.MODE_PRIVATE)
        history.clear()
        preferences?.getString("events", null)?.lines()?.takeLast(120)?.forEach { history.addLast(it) }
        event("activity created pid=${Process.myPid()}")
    }
    @Synchronized fun event(message: String) {
        val entry = "${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()} [${Thread.currentThread().name}] $message"
        Log.i("MinimalUvc", entry)
        history.addLast(entry)
        while (history.size > 120) history.removeFirst()
        // apply 异步写盘。最大 120 条，只记录状态，不含视频像素。
        preferences?.edit()?.putString("events", history.joinToString("\n"))?.apply()
    }
}
