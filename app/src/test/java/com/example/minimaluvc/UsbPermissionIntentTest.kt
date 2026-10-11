package com.example.minimaluvc

import android.content.IntentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UsbPermissionIntentTest {
    @Test fun systemPermissionResultMatchesRegisteredBroadcastFilter() {
        val intent = UsbDeviceAccess.permissionIntent("com.example.minimaluvc", 12L)
        val filter = IntentFilter("com.example.minimaluvc.USB_PERMISSION")
        // 接收器按 action 注册；如果授权 Intent 带不匹配的 URI，真实系统会丢弃结果。
        assertEquals(IntentFilter.MATCH_CATEGORY_EMPTY + IntentFilter.MATCH_ADJUSTMENT_NORMAL,
            filter.match(intent.action, intent.type, intent.scheme, intent.data, intent.categories, "test"))
    }

    @Test fun callbackCarriesRequestIdentityAndStaysWithinAppPackage() {
        val intent = UsbDeviceAccess.permissionIntent("com.example.minimaluvc", 12L)
        assertEquals("com.example.minimaluvc", intent.`package`)
        assertEquals(12L, intent.getLongExtra("minimaluvc.request", -1L))
    }

    @Test fun replugRequestDoesNotReuseAnOlderPermissionIntent() {
        val context = RuntimeEnvironment.getApplication()
        val old = UsbDeviceAccess.permissionPendingIntent(context, 12L)
        val next = UsbDeviceAccess.permissionPendingIntent(context, 13L)
        try {
            assertNotEquals(old, next)
        } finally {
            old.cancel()
            next.cancel()
        }
    }
}
