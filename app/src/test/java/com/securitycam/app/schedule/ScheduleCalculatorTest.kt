package com.securitycam.app.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCalculatorTest {

    private val monday = Weekday.MONDAY.isoIndex
    private val tuesday = Weekday.TUESDAY.isoIndex
    private val friday = Weekday.FRIDAY.isoIndex
    private val saturday = Weekday.SATURDAY.isoIndex

    @Test
    fun `same-day window is active only within start-end`() {
        val schedules = mapOf(monday to DaySchedule(enabled = true, startMinutes = 9 * 60, endMinutes = 17 * 60))
        assertTrue(ScheduleCalculator.isActiveAt(schedules, monday, 10 * 60))
        assertTrue(ScheduleCalculator.isActiveAt(schedules, monday, 9 * 60))
        assertFalse(ScheduleCalculator.isActiveAt(schedules, monday, 17 * 60)) // end is exclusive
        assertFalse(ScheduleCalculator.isActiveAt(schedules, monday, 8 * 60))
    }

    @Test
    fun `disabled day is never active regardless of time`() {
        val schedules = mapOf(monday to DaySchedule(enabled = false, startMinutes = 0, endMinutes = 24 * 60 - 1))
        assertFalse(ScheduleCalculator.isActiveAt(schedules, monday, 12 * 60))
    }

    @Test
    fun `day with no configured schedule is inactive`() {
        assertFalse(ScheduleCalculator.isActiveAt(emptyMap(), monday, 12 * 60))
    }

    @Test
    fun `overnight window active late on start day`() {
        val schedules = mapOf(friday to DaySchedule(enabled = true, startMinutes = 22 * 60, endMinutes = 6 * 60))
        assertTrue(ScheduleCalculator.isActiveAt(schedules, friday, 23 * 60))
    }

    @Test
    fun `overnight window active early on the following day via carryover`() {
        val schedules = mapOf(friday to DaySchedule(enabled = true, startMinutes = 22 * 60, endMinutes = 6 * 60))
        // Saturday itself has no entry, but Friday's overnight window should still cover early Saturday morning
        assertTrue(ScheduleCalculator.isActiveAt(schedules, saturday, 3 * 60))
        assertFalse(ScheduleCalculator.isActiveAt(schedules, saturday, 10 * 60))
    }

    @Test
    fun `overnight window does not leak into an unrelated following day beyond its end`() {
        val schedules = mapOf(friday to DaySchedule(enabled = true, startMinutes = 22 * 60, endMinutes = 6 * 60))
        assertFalse(ScheduleCalculator.isActiveAt(schedules, saturday, 6 * 60))
        assertFalse(ScheduleCalculator.isActiveAt(schedules, saturday, 23 * 60))
    }

    @Test
    fun `independent per-day schedules do not affect each other`() {
        val schedules = mapOf(
            monday to DaySchedule(enabled = true, startMinutes = 9 * 60, endMinutes = 17 * 60),
            tuesday to DaySchedule(enabled = false, startMinutes = 0, endMinutes = 24 * 60 - 1),
        )
        assertTrue(ScheduleCalculator.isActiveAt(schedules, monday, 10 * 60))
        assertFalse(ScheduleCalculator.isActiveAt(schedules, tuesday, 10 * 60))
    }

    @Test
    fun `nextTransition finds the start of a same-day window`() {
        val schedules = mapOf(monday to DaySchedule(enabled = true, startMinutes = 9 * 60, endMinutes = 17 * 60))
        val next = ScheduleCalculator.nextTransition(schedules, monday, 8 * 60)
        assertEquals(monday to 9 * 60, next)
    }

    @Test
    fun `nextTransition finds the end of a same-day window`() {
        val schedules = mapOf(monday to DaySchedule(enabled = true, startMinutes = 9 * 60, endMinutes = 17 * 60))
        val next = ScheduleCalculator.nextTransition(schedules, monday, 10 * 60)
        assertEquals(monday to 17 * 60, next)
    }

    @Test
    fun `nextTransition crosses into the next day for an overnight window`() {
        val schedules = mapOf(friday to DaySchedule(enabled = true, startMinutes = 22 * 60, endMinutes = 6 * 60))
        val next = ScheduleCalculator.nextTransition(schedules, friday, 23 * 60)
        assertEquals(saturday to 6 * 60, next)
    }

    @Test
    fun `nextTransition returns null when nothing is ever scheduled`() {
        assertNull(ScheduleCalculator.nextTransition(emptyMap(), monday, 12 * 60))
    }
}
