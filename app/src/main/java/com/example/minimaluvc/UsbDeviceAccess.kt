package com.example.minimaluvc

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jiangdg.usb.USBMonitor

/**
 * USB 设备发现和授权。监听、枚举和授权从主线程调用；
 * USBMonitor 仅作为 libuvc 的控制块桥接，绝不注册它的广播监听。
 */
class UsbDeviceAccess(context: Context, private val onChanged: () -> Unit) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(UsbManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val permissionAction = "${this.context.packageName}.USB_PERMISSION"
    private var registered = false
    private var polling = false
    private var nextRequest = 0L
    private var lastDevices = emptySet<DeviceKey>()
    private val attempted = mutableSetOf<DeviceKey>()
    private val pending = mutableMapOf<Long, DeviceKey>()

    // 仅在 UVC 工作线程首次打开时构造、使用和销毁。
    private var monitor: USBMonitor? = null
    private val ignoredMonitorListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) = Unit
        override fun onDetach(device: UsbDevice?) = Unit
        override fun onConnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?, new: Boolean) = Unit
        override fun onDisconnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?) = Unit
        override fun onCancel(device: UsbDevice?) = Unit
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice()
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // 即使重新插入后 deviceName 被复用，旧授权结果也不能作用于新连接。
                    device?.let { detached ->
                        val key = keyOf(detached)
                        attempted.remove(key)
                        pending.entries.removeAll { it.value == key }
                        lastDevices = lastDevices - key
                    }
                    onChanged()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    findUvcDevices()
                    onChanged()
                }
                permissionAction -> {
                    val request = intent.getLongExtra(EXTRA_REQUEST, -1L)
                    val expected = pending.remove(request) ?: return
                    if (device == null || keyOf(device) != expected || !isConnected(device)) return
                    // 不仅相信广播里的 EXTRA_PERMISSION_GRANTED，还要检查系统真实授权。
                    Log.i(TAG, if (hasPermission(device)) "USB 已授权: $expected" else "USB 授权被拒绝: $expected")
                    onChanged()
                }
            }
        }
    }

    // 个别旧系统的 ATTACHED 广播不稳定，前台低频枚举补偿；后台停止轮询。
    private val poll = object : Runnable {
        override fun run() {
            if (!polling || !registered) return
            val before = lastDevices
            findUvcDevices()
            if (before != lastDevices) onChanged()
            handler.postDelayed(this, 1500L)
        }
    }

    fun register() {
        checkMainThread()
        if (registered) return
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        registered = true
        findUvcDevices()
    }

    fun setForeground(foreground: Boolean) {
        checkMainThread()
        polling = foreground
        handler.removeCallbacks(poll)
        if (foreground && registered) handler.post(poll)
    }

    fun unregister() {
        checkMainThread()
        polling = false
        handler.removeCallbacks(poll)
        if (registered) context.unregisterReceiver(receiver)
        registered = false
        pending.clear()
    }

    fun findUvcDevices(): List<UsbDevice> {
        checkMainThread()
        val devices = manager.deviceList.values.filter { device ->
            PreviewPolicy.isUvc(device.deviceClass,
                (0 until device.interfaceCount).map { device.getInterface(it).interfaceClass })
        }.sortedBy { it.deviceName }
        val current = devices.map(::keyOf).toSet()
        attempted.retainAll(current)
        pending.entries.removeAll { it.value !in current }
        lastDevices = current
        return devices
    }

    fun hasCameraPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(device: UsbDevice): Boolean = manager.hasPermission(device)

    // 该查询不修改主线程状态，也供工作线程在真正打开前使用。
    fun isConnected(device: UsbDevice): Boolean =
        manager.deviceList[device.deviceName]?.let { keyOf(it) == keyOf(device) } == true

    fun requestPermissionOnce(device: UsbDevice) {
        checkMainThread()
        val key = keyOf(device)
        if (!registered || !isConnected(device) || hasPermission(device) || !hasCameraPermission()) return
        if (!attempted.add(key)) return
        val request = ++nextRequest
        pending[request] = key
        // 每个请求都有独立 requestCode，避免旧授权框结果与新请求混淆。
        val permissionIntent = permissionPendingIntent(context, request)
        try {
            Log.i(TAG, "请求 USB 权限: $key")
            manager.requestPermission(device, permissionIntent)
        } catch (error: RuntimeException) {
            pending.remove(request)
            permissionIntent.cancel()
            Log.e(TAG, "USB 权限请求失败", error)
        }
    }

    /** 用户在菜单中主动重试。已有系统授权请求仍在等待时，不重复弹出。 */
    fun allowPermissionRetry() {
        checkMainThread()
        attempted.removeAll { it !in pending.values }
    }

    /** 必须在预览控制器的串行工作线程调用，且调用者负责关闭返回的控制块。 */
    fun openControlBlock(device: UsbDevice): USBMonitor.UsbControlBlock {
        check(Looper.myLooper() != Looper.getMainLooper())
        check(isConnected(device) && hasCameraPermission() && hasPermission(device)) {
            "设备已断开或未授权"
        }
        val current = monitor ?: USBMonitor(context, ignoredMonitorListener).also { monitor = it }
        return current.openDevice(device)
    }

    /** 相机和控制块全部关闭后，由同一工作线程调用。 */
    fun destroyMonitor() {
        check(Looper.myLooper() != Looper.getMainLooper())
        monitor?.destroy()
        monitor = null
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private data class DeviceKey(val name: String, val vendor: Int, val product: Int)
    private fun keyOf(device: UsbDevice) = DeviceKey(device.deviceName, device.vendorId, device.productId)

    companion object {
        private const val TAG = "MinimalUvc"
        private const val EXTRA_REQUEST = "minimaluvc.request"

        internal fun permissionIntent(packageName: String, request: Long): Intent =
            Intent("$packageName.USB_PERMISSION").setPackage(packageName)
                // action-only 的动态接收器无法匹配带 URI 的广播，不能在这里 setData。
                .putExtra(EXTRA_REQUEST, request)

        internal fun permissionPendingIntent(context: Context, request: Long): PendingIntent {
            val flags = PendingIntent.FLAG_ONE_SHOT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            return PendingIntent.getBroadcast(context, request.toInt(), permissionIntent(context.packageName, request), flags)
        }
    }
}
