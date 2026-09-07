package com.staysafeai.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Transparent overlay drawn on top of the camera preview.
 * Paints IR sources (purple), lens glints (gold), and suspicious objects (red) in real time.
 */
class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Detection(
        val cx: Float,   // normalized 0..1
        val cy: Float,   // normalized 0..1
        val type: Type,
        val confidence: Float = 1f,
        val label: String = ""
    ) {
        enum class Type { IR, GLINT, SUSPECT }
    }

    private val detections = mutableListOf<Detection>()

    // Paints
    private val irFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99CC88FF")
        style = Paint.Style.FILL
    }
    private val irStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFCC88FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val glintFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FFD700")
        style = Paint.Style.FILL
    }
    private val glintStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFD700")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val suspectFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99FF4444")
        style = Paint.Style.FILL
    }
    private val suspectStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF4444")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pulseRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }

    private var pulseRadius = 0f
    private var pulseGrowing = true

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // Animate pulse rings
        post(object : Runnable {
            override fun run() {
                if (pulseGrowing) {
                    pulseRadius += 2f
                    if (pulseRadius > 40f) pulseGrowing = false
                } else {
                    pulseRadius -= 2f
                    if (pulseRadius < 0f) pulseGrowing = true
                }
                if (detections.isNotEmpty()) invalidate()
                postDelayed(this, 16)
            }
        })
    }

    fun updateDetections(newDetections: List<Detection>) {
        detections.clear()
        detections.addAll(newDetections)
        postInvalidate()
    }

    fun clearDetections() {
        detections.clear()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        for (det in detections) {
            val cx = det.cx * w
            val cy = det.cy * h
            val baseRadius = 18f + (det.confidence * 20f)

            val (fillPaint, strokePaint, pulseColor) = when (det.type) {
                Detection.Type.IR ->
                    Triple(irFillPaint, irStrokePaint, Color.parseColor("#88CC88FF"))
                Detection.Type.GLINT ->
                    Triple(glintFillPaint, glintStrokePaint, Color.parseColor("#88FFD700"))
                Detection.Type.SUSPECT ->
                    Triple(suspectFillPaint, suspectStrokePaint, Color.parseColor("#88FF4444"))
            }

            // Pulse ring
            pulseRingPaint.color = pulseColor
            pulseRingPaint.alpha = (255 * (1f - pulseRadius / 40f)).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, baseRadius + pulseRadius, pulseRingPaint)

            // Outer ring
            canvas.drawCircle(cx, cy, baseRadius + 6f, strokePaint)

            // Fill circle
            canvas.drawCircle(cx, cy, baseRadius, fillPaint)

            // Inner bright dot
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                alpha = 200
            }
            canvas.drawCircle(cx, cy, 5f, dotPaint)

            // Label
            if (det.label.isNotEmpty()) {
                val textW = labelPaint.measureText(det.label)
                val labelX = cx - textW / 2f
                val labelY = cy - baseRadius - 16f
                val rect = RectF(labelX - 6f, labelY - 24f, labelX + textW + 6f, labelY + 4f)
                canvas.drawRoundRect(rect, 6f, 6f, labelBgPaint)
                canvas.drawText(det.label, labelX, labelY, labelPaint)
            }
        }
    }
}
