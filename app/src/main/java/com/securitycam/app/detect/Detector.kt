package com.securitycam.app.detect

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * Thin wrapper around the TFLite Task Vision ObjectDetector.
 * Loads the EfficientDet-Lite0 (COCO, 80 classes) model bundled in assets.
 */
class Detector(
    context: Context,
    modelFileName: String = "model.tflite",
    maxResults: Int = 10,
    numThreads: Int = 4,
) {
    private val detector: ObjectDetector

    init {
        val baseOptions = BaseOptions.builder().setNumThreads(numThreads).build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(baseOptions)
            .setMaxResults(maxResults)
            .setScoreThreshold(0.25f) // coarse filter; per-group thresholds applied by caller
            .build()
        detector = ObjectDetector.createFromFileAndOptions(context, modelFileName, options)
    }

    /** Runs detection on an upright bitmap; returns raw detections above the coarse threshold. */
    fun detect(bitmap: Bitmap): List<Detection> {
        val tensorImage = TensorImage.fromBitmap(bitmap)
        val results = detector.detect(tensorImage)
        val out = ArrayList<Detection>(results.size)
        for (r in results) {
            val cat = r.categories.maxByOrNull { it.score } ?: continue
            val group = DetectionGroup.fromLabel(cat.label) ?: continue
            val box = r.boundingBox
            out.add(
                Detection(
                    group = group,
                    label = cat.label,
                    score = cat.score,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                )
            )
        }
        return out
    }

    fun close() = detector.close()
}
