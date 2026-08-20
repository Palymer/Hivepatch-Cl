package ru.hivepatch.client

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import ru.hivepatch.client.data.Prefs
import ru.hivepatch.client.vpn.XrayCore

class HiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        XrayCore.init(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_VPN, "HivePatch VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val CHANNEL_VPN = "hivepatch-vpn"
        lateinit var instance: HiveApp
            private set
    }
}
