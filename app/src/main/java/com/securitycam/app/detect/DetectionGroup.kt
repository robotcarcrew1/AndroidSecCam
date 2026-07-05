package com.securitycam.app.detect

/**
 * The three alert groups the app cares about, mapped from COCO labels
 * produced by the TFLite detection models.
 */
enum class DetectionGroup(val labels: Set<String>) {
    HUMAN(setOf("person")),
    VEHICLE(setOf("bicycle", "car", "motorcycle", "bus", "truck", "train", "boat")),
    ANIMAL(
        setOf(
            "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe"
        )
    );

    companion object {
        fun fromLabel(label: String): DetectionGroup? {
            val l = label.trim().lowercase()
            return entries.firstOrNull { l in it.labels }
        }
    }
}

/** One filtered detection in upright-bitmap pixel coordinates. */
data class Detection(
    val group: DetectionGroup,
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /**
     * Intersection-over-union with another detection's bounding box, 0 (no overlap) to
     * 1 (identical) — used to tell "the same physical object, still there" apart from
     * "a different object of the same category, in a different spot".
     */
    fun boxOverlap(other: Detection): Float {
        val left = maxOf(left, other.left)
        val top = maxOf(top, other.top)
        val right = minOf(right, other.right)
        val bottom = minOf(bottom, other.bottom)
        val interArea = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val aArea = (this.right - this.left) * (this.bottom - this.top)
        val bArea = (other.right - other.left) * (other.bottom - other.top)
        val unionArea = aArea + bArea - interArea
        return if (unionArea <= 0f) 0f else interArea / unionArea
    }
}
