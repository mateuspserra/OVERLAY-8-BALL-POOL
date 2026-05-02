package com.overlaypool.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.overlaypool.core.DetectionStateStore
import com.overlaypool.core.RuntimeStatus
import com.overlaypool.model.DetectionResult
import com.overlaypool.model.TrajectoryResult

class DetectionOverlayView(context: Context) : View(context), DetectionStateStore.Listener {
    private var detections: List<DetectionResult> = emptyList()
    private var trajectory: TrajectoryResult? = null
    private var markingsVisible: Boolean = true

    private val cueBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(40, 220, 180)
    }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.rgb(80, 170, 255)
    }
    private val trajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(230, 255, 60)
        alpha = 220
    }
    private val impactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 70, 70)
        alpha = 230
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        DetectionStateStore.addListener(this)
    }

    override fun onDetachedFromWindow() {
        DetectionStateStore.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!markingsVisible) return

        drawDetections(canvas)
        drawTrajectory(canvas)
    }

    private fun drawDetections(canvas: Canvas) {
        detections.forEach { detection ->
            when (detection.className) {
                "cue_ball" -> {
                    canvas.drawCircle(
                        detection.centerX,
                        detection.centerY,
                        detection.radius + 6f,
                        cueBallPaint
                    )
                }

                "pocket" -> {
                    canvas.drawCircle(
                        detection.centerX,
                        detection.centerY,
                        detection.radius + 5f,
                        pocketPaint
                    )
                }

                else -> {
                    canvas.drawRoundRect(RectF(detection.rect), 8f, 8f, objectPaint)
                }
            }

            val label = "${detection.className} ${(detection.confidence * 100f).toInt()}%"
            canvas.drawText(label, detection.x, (detection.y - 8f).coerceAtLeast(28f), textPaint)
        }
    }

    private fun drawTrajectory(canvas: Canvas) {
        val currentTrajectory = trajectory ?: return
        val line = currentTrajectory.primaryLine
        canvas.drawLine(
            line.start.x,
            line.start.y,
            line.end.x,
            line.end.y,
            trajectoryPaint
        )
        canvas.drawCircle(
            currentTrajectory.impactPoint.x,
            currentTrajectory.impactPoint.y,
            10f,
            impactPaint
        )
    }

    override fun onDetectionsUpdated(
        detections: List<DetectionResult>,
        trajectory: TrajectoryResult?
    ) {
        this.detections = detections
        this.trajectory = trajectory
        postInvalidate()
    }

    override fun onStatusUpdated(status: RuntimeStatus) {
        markingsVisible = status.markingsVisible
        postInvalidate()
    }
}
