package com.securitycam.app.schedule

/** Days keyed 1=Monday..7=Sunday (ISO-8601 convention). */
enum class Weekday(val isoIndex: Int, val keyPrefix: String, val label: String) {
    MONDAY(1, "mon", "Monday"),
    TUESDAY(2, "tue", "Tuesday"),
    WEDNESDAY(3, "wed", "Wednesday"),
    THURSDAY(4, "thu", "Thursday"),
    FRIDAY(5, "fri", "Friday"),
    SATURDAY(6, "sat", "Saturday"),
    SUNDAY(7, "sun", "Sunday"),
}

/**
 * One day's configured active window. [startMinutes]/[endMinutes] are minutes since
 * midnight (0..1439). If `endMinutes <= startMinutes` the window wraps past midnight
 * into the next calendar day (e.g. start=22:00, end=06:00).
 */
data class DaySchedule(
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int,
)

/**
 * Pure calendar/time logic for the monitoring schedule feature — no Android dependencies,
 * so it can be unit tested directly.
 */
object ScheduleCalculator {

    private fun previousDay(dayOfWeekIso: Int): Int = if (dayOfWeekIso == 1) 7 else dayOfWeekIso - 1
    private fun nextDay(dayOfWeekIso: Int): Int = if (dayOfWeekIso == 7) 1 else dayOfWeekIso + 1

    fun isActiveAt(schedules: Map<Int, DaySchedule>, dayOfWeekIso: Int, minuteOfDay: Int): Boolean {
        val today = schedules[dayOfWeekIso] ?: DaySchedule(false, 0, 0)
        val yesterday = schedules[previousDay(dayOfWeekIso)] ?: DaySchedule(false, 0, 0)

        val activeFromToday = today.enabled && if (today.startMinutes < today.endMinutes) {
            minuteOfDay in today.startMinutes until today.endMinutes
        } else {
            minuteOfDay >= today.startMinutes
        }

        val activeFromYesterdaysOvernightWindow = yesterday.enabled &&
            yesterday.startMinutes >= yesterday.endMinutes &&
            minuteOfDay < yesterday.endMinutes

        return activeFromToday || activeFromYesterdaysOvernightWindow
    }

    /**
     * Finds the next (dayOfWeekIso, minuteOfDay) strictly after the given time at which
     * [isActiveAt] flips to a different value than it has right now. Searches minute by
     * minute up to [maxMinutesAhead] (default ~15 days) to guarantee termination even if
     * every day is disabled — in which case it returns null.
     */
    fun nextTransition(
        schedules: Map<Int, DaySchedule>,
        fromDayOfWeekIso: Int,
        fromMinuteOfDay: Int,
        maxMinutesAhead: Int = 60 * 24 * 15,
    ): Pair<Int, Int>? {
        val currentState = isActiveAt(schedules, fromDayOfWeekIso, fromMinuteOfDay)
        var day = fromDayOfWeekIso
        var minute = fromMinuteOfDay
        repeat(maxMinutesAhead) {
            minute++
            if (minute >= 24 * 60) {
                minute = 0
                day = nextDay(day)
            }
            if (isActiveAt(schedules, day, minute) != currentState) {
                return day to minute
            }
        }
        return null
    }
}
