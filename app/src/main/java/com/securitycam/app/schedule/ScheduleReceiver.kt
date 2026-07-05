package com.securitycam.app.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fired by [ScheduleManager]'s alarm at each scheduled start/stop transition. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleManager.onAlarmFired(context)
    }
}
