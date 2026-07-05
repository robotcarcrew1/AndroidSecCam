package com.securitycam.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.securitycam.app.service.MonitorService
import com.securitycam.app.settings.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs(context).startOnBoot) {
            ContextCompat.startForegroundService(context, MonitorService.startIntent(context))
        }
    }
}
