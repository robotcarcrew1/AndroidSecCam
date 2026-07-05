package com.securitycam.app.detect

/**
 * Decides when a detection becomes an alert event.
 *
 * A group triggers when it has been present in [consecutiveFrames] successive
 * analyzed frames and its previous trigger was more than [cooldownMs] ago.
 * Pure logic, no Android dependencies, unit tested.
 */
class TriggerLogic(
    private val consecutiveFrames: Int,
    private val cooldownMs: Long,
) {
    private val streak = HashMap<DetectionGroup, Int>()
    private val lastTrigger = HashMap<DetectionGroup, Long>()

    /**
     * Feed the set of groups present in the current frame.
     * Returns the groups that newly trigger an event on this frame.
     */
    fun onFrame(present: Set<DetectionGroup>, nowMs: Long): Set<DetectionGroup> {
        val triggered = mutableSetOf<DetectionGroup>()
        for (group in DetectionGroup.entries) {
            if (group in present) {
                val count = (streak[group] ?: 0) + 1
                streak[group] = count
                val last = lastTrigger[group]
                if (count >= consecutiveFrames && (last == null || nowMs - last >= cooldownMs)) {
                    lastTrigger[group] = nowMs
                    triggered.add(group)
                }
            } else {
                streak[group] = 0
            }
        }
        return triggered
    }

    fun reset() {
        streak.clear()
        lastTrigger.clear()
    }
}
