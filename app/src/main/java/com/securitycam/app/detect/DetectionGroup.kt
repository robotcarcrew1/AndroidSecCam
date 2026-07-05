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
)
