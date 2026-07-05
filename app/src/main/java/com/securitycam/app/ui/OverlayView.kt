package com.securitycam.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.securitycam.app.detect.Detection
import com.securitycam.app.detect.DetectionGroup

/** Draws bounding boxes + labels over the camera preview. Coordinates are in source-bitmap pixels. */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<Detection> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        isFakeBoldText = true
    }
    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        alpha = 160
    }

    fun update(detections: List<Detection>, sourceWidth: Int, sourceHeight: Int) {
        this.detections = detections
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        postInvalidate()
    }

    private fun colorFor(group: DetectionGroup): Int = when (group) {
        DetectionGroup.HUMAN -> Color.parseColor("#FF5252")
        DetectionGroup.VEHICLE -> Color.parseColor("#40C4FF")
        DetectionGroup.ANIMAL -> Color.parseColor("#FFD740")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return
        val scaleX = width.toFloat() / sourceWidth
        val scaleY = height.toFloat() / sourceHeight
        for (d in detections) {
            val color = colorFor(d.group)
            boxPaint.color = color
            textBgPaint.color = color
            val left = d.left * scaleX
            val top = d.top * scaleY
            val right = d.right * scaleX
            val bottom = d.bottom * scaleY
            canvas.drawRect(left, top, right, bottom, boxPaint)
            val label = "${d.label} ${(d.score * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(left, top - 48f, left + textWidth + 16f, top, textBgPaint)
            canvas.drawText(label, left + 8f, top - 12f, textPaint)
        }
    }
}
