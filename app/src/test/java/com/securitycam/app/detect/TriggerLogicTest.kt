package com.securitycam.app.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerLogicTest {

    @Test
    fun `does not trigger on first frame when consecutiveFrames is 2`() {
        val logic = TriggerLogic(consecutiveFrames = 2, cooldownMs = 60_000)
        val triggered = logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 0)
        assertTrue(triggered.isEmpty())
    }

    @Test
    fun `triggers once consecutiveFrames threshold is reached`() {
        val logic = TriggerLogic(consecutiveFrames = 2, cooldownMs = 60_000)
        logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 0)
        val triggered = logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 400)
        assertEquals(setOf(DetectionGroup.HUMAN), triggered)
    }

    @Test
    fun `streak resets when group disappears from a frame`() {
        val logic = TriggerLogic(consecutiveFrames = 2, cooldownMs = 60_000)
        logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 0)
        logic.onFrame(emptySet(), nowMs = 400) // gap resets streak
        val triggered = logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 800)
        assertTrue(triggered.isEmpty())
    }

    @Test
    fun `does not re-trigger within cooldown window`() {
        val logic = TriggerLogic(consecutiveFrames = 1, cooldownMs = 60_000)
        val first = logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 0)
        assertEquals(setOf(DetectionGroup.HUMAN), first)
        val second = logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 30_000)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `re-triggers after cooldown elapses`() {
        val logic = TriggerLogic(consecutiveFrames = 1, cooldownMs = 60_000)
        logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 0)
        val second = logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 61_000)
        assertEquals(setOf(DetectionGroup.HUMAN), second)
    }

    @Test
    fun `groups are tracked independently`() {
        val logic = TriggerLogic(consecutiveFrames = 1, cooldownMs = 60_000)
        val triggered = logic.onFrame(setOf(DetectionGroup.HUMAN, DetectionGroup.VEHICLE), nowMs = 0)
        assertEquals(setOf(DetectionGroup.HUMAN, DetectionGroup.VEHICLE), triggered)
        // HUMAN in cooldown, but a fresh ANIMAL should still trigger
        val second = logic.onFrame(setOf(DetectionGroup.HUMAN, DetectionGroup.ANIMAL), nowMs = 1000)
        assertEquals(setOf(DetectionGroup.ANIMAL), second)
    }

    @Test
    fun `reset clears streaks and cooldowns`() {
        val logic = TriggerLogic(consecutiveFrames = 1, cooldownMs = 60_000)
        logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 0)
        logic.reset()
        val triggered = logic.onFrame(setOf(DetectionGroup.HUMAN), nowMs = 100)
        assertEquals(setOf(DetectionGroup.HUMAN), triggered)
    }
}
