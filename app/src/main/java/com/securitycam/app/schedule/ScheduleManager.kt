package com.securitycam.app.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.securitycam.app.service.MonitorService
import com.securitycam.app.settings.Prefs
import java.util.Calendar

/**
 * Drives the per-day monitoring schedule using [AlarmManager] rather than an in-process
 * timer, so the next start/stop still fires even if the app process was killed in the
 * background in the meantime.
 *
 * Manual start/stop is meant to override the schedule in between transitions (e.g. if you
 * stop early mid-window, it should stay off until the *next* window rather than snapping
 * back on) — so forcing the current armed state to match the schedule only happens at an
 * actual transition ([onAlarmFired], and [reschedule] with `applyNow = true` for recovery
 * after a reboot where an alarm may have been missed). Routine calls to [reschedule] (app
 * launch, settings changes) just re-arm the next alarm without touching the current state.
 */
object ScheduleManager {
    private const val TAG = "ScheduleManager"
    private const val REQUEST_CODE = 5001

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Re-arms the alarm for the next schedule transition. Call whenever schedule settings
     * change, on app launch, and on boot. Safe to call anytime (no-op if the schedule is
     * disabled). Pass `applyNow = true` only when recovering state after being away
     * (boot) — it forces the current armed state to match the schedule right now, which
     * would otherwise override a manual start/stop mid-window.
     */
    fun reschedule(context: Context, applyNow: Boolean = false) {
        val prefs = Prefs(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        alarmManager.cancel(pi)

        if (!prefs.scheduleEnabled) return

        if (applyNow) applyCurrentState(context, prefs)

        val now = Calendar.getInstance()
        val nowDay = isoDayOfWeek(now)
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val next = ScheduleCalculator.nextTransition(prefs.allDaySchedules(), nowDay, nowMinute) ?: return
        val (nextDay, nextMinute) = next
        val triggerAt = timestampFor(now, nowDay, nextDay, nextMinute)

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        Log.i(TAG, "Next schedule transition armed for $triggerAt")
    }

    /** Called by [ScheduleReceiver] when an alarm fires — this *is* an actual scheduled
     *  transition, so the armed state is forced to match. */
    fun onAlarmFired(context: Context) {
        val prefs = Prefs(context)
        if (!prefs.scheduleEnabled) return
        applyCurrentState(context, prefs)
        reschedule(context) // queue up the following transition
    }

    private fun applyCurrentState(context: Context, prefs: Prefs) {
        val now = Calendar.getInstance()
        val nowDay = isoDayOfWeek(now)
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val shouldBeActive = ScheduleCalculator.isActiveAt(prefs.allDaySchedules(), nowDay, nowMinute)
        if (shouldBeActive) {
            ContextCompat.startForegroundService(context, MonitorService.startIntent(context))
        } else {
            context.startService(MonitorService.stopIntent(context))
        }
    }

    /** Converts Calendar.DAY_OF_WEEK (SUNDAY=1..SATURDAY=7) to ISO (MONDAY=1..SUNDAY=7). */
    private fun isoDayOfWeek(cal: Calendar): Int {
        val c = cal.get(Calendar.DAY_OF_WEEK)
        return if (c == Calendar.SUNDAY) 7 else c - 1
    }

    private fun timestampFor(base: Calendar, fromDay: Int, targetDay: Int, targetMinute: Int): Long {
        val daysAhead = ((targetDay - fromDay) + 7) % 7
        val cal = base.clone() as Calendar
        cal.add(Calendar.DAY_OF_YEAR, daysAhead)
        cal.set(Calendar.HOUR_OF_DAY, targetMinute / 60)
        cal.set(Calendar.MINUTE, targetMinute % 60)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= base.timeInMillis) {
            cal.add(Calendar.DAY_OF_YEAR, 7)
        }
        return cal.timeInMillis
    }
}
