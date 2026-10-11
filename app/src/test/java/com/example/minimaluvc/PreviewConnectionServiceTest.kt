package com.example.minimaluvc

import android.app.Service
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PreviewConnectionServiceTest {
    @Test fun foregroundServiceHasVisibleConnectionNotificationAndDoesNotAutoRestartCamera() {
        val owner = Robolectric.buildService(PreviewConnectionService::class.java).create()
        val service = owner.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 1))
            val notification = shadowOf(service).lastForegroundNotification
            assertNotNull(notification)
            assertEquals("采集卡保持连接", notification.extras.getString("android.title"))
        } finally { owner.destroy() }
    }
}
