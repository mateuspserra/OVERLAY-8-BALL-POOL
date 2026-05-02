package com.overlaypool.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.overlaypool.core.DetectionStateStore
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ManualGuideService : Service() {
    private var windowManager: WindowManager? = null
    private var guideView: ManualGuideView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createGuide()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createGuide()
        return START_STICKY
    }

    override fun onDestroy() {
        guideView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        guideView = null
        DetectionStateStore.updateStatus {
            it.copy(systemState = "Guia manual fechado")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createGuide() {
        if (guideView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val view = ManualGuideView(this) { stopSelf() }
        runCatching {
            windowManager?.addView(view, params)
            guideView = view
            DetectionStateStore.updateStatus {
                it.copy(systemState = "Guia manual ativo")
            }
        }.onFailure {
            stopSelf()
        }
    }
}

private class ManualGuideView(
    context: android.content.Context,
    private val onClose: () -> Unit
) : View(context) {
    private var guidePoint = PointF(Float.NaN, Float.NaN)
    private var mode = GuideMode.TABLE
    private var draggingPoint = false
    private val pockets = mutableListOf<PointF>()

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 165, 30, 10)
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 165, 30, 10)
        style = Paint.Style.FILL
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(230, 82, 20)
        style = Paint.Style.FILL
    }
    private val activeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 185, 0)
        style = Paint.Style.FILL
    }
    private val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 35, 12)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 38f
        isFakeBoldText = true
    }

    private var normalButton = RectF()
    private var tableButton = RectF()
    private var closeButton = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (guidePoint.x.isNaN() || guidePoint.y.isNaN()) {
            guidePoint.set(width * 0.62f, height * 0.42f)
        }
        updatePockets(width, height)
        updateButtons(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGuide(canvas)
        drawButtons(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                when {
                    closeButton.contains(event.x, event.y) -> {
                        onClose()
                        return true
                    }
                    normalButton.contains(event.x, event.y) -> {
                        mode = GuideMode.NORMAL
                        invalidate()
                        return true
                    }
                    tableButton.contains(event.x, event.y) -> {
                        mode = GuideMode.TABLE
                        invalidate()
                        return true
                    }
                    distance(event.x, event.y, guidePoint.x, guidePoint.y) <= HANDLE_TOUCH_RADIUS -> {
                        draggingPoint = true
                        return true
                    }
                    else -> {
                        guidePoint.set(event.x, event.y)
                        draggingPoint = true
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingPoint) {
                    guidePoint.set(
                        event.x.coerceIn(0f, width.toFloat()),
                        event.y.coerceIn(0f, height.toFloat())
                    )
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingPoint = false
                return true
            }
        }

        return true
    }

    private fun drawGuide(canvas: Canvas) {
        if (pockets.isEmpty()) updatePockets(width, height)
        drawTableFrame(canvas)

        val selectedPockets = if (mode == GuideMode.TABLE) pockets else nearestPockets()
        selectedPockets.forEach { pocket ->
            canvas.drawLine(guidePoint.x, guidePoint.y, pocket.x, pocket.y, guidePaint)
        }

        canvas.drawCircle(guidePoint.x, guidePoint.y, HANDLE_RADIUS, pointFillPaint)
        canvas.drawCircle(guidePoint.x, guidePoint.y, HANDLE_RADIUS, pointPaint)
    }

    private fun drawTableFrame(canvas: Canvas) {
        if (pockets.size < 6) return
        val left = min(pockets[0].x, pockets[3].x)
        val right = max(pockets[2].x, pockets[5].x)
        val top = min(pockets[0].y, pockets[2].y)
        val bottom = max(pockets[3].y, pockets[5].y)
        canvas.drawRoundRect(RectF(left, top, right, bottom), 16f, 16f, railPaint)
    }

    private fun drawButtons(canvas: Canvas) {
        drawButton(canvas, normalButton, "NORMAL", mode == GuideMode.NORMAL)
        drawButton(canvas, tableButton, "TABELA", mode == GuideMode.TABLE)
        canvas.drawRoundRect(closeButton, 8f, 8f, closeButtonPaint)
        drawCenteredText(canvas, "FECHAR", closeButton)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, text: String, active: Boolean) {
        canvas.drawRoundRect(rect, 8f, 8f, if (active) activeButtonPaint else buttonPaint)
        drawCenteredText(canvas, text, rect)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, rect: RectF) {
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), y, textPaint)
    }

    private fun nearestPockets(): List<PointF> {
        return pockets.sortedBy { distance(guidePoint.x, guidePoint.y, it.x, it.y) }.take(2)
    }

    private fun updatePockets(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val landscape = width > height
        val left = if (landscape) width * 0.12f else width * 0.08f
        val right = if (landscape) width * 0.88f else width * 0.92f
        val top = if (landscape) height * 0.18f else height * 0.24f
        val bottom = if (landscape) height * 0.90f else height * 0.78f
        val centerX = (left + right) / 2f

        pockets.clear()
        pockets.add(PointF(left, top))
        pockets.add(PointF(centerX, top))
        pockets.add(PointF(right, top))
        pockets.add(PointF(left, bottom))
        pockets.add(PointF(centerX, bottom))
        pockets.add(PointF(right, bottom))
    }

    private fun updateButtons(width: Int, height: Int) {
        val buttonHeight = 74f
        val bottom = height - 24f
        val top = bottom - buttonHeight
        val center = width / 2f
        normalButton = RectF(center - 420f, top, center - 210f, bottom)
        tableButton = RectF(center - 210f, top, center, bottom)
        closeButton = RectF(center + 360f, top, center + 640f, bottom)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x1 - x2, y1 - y2)
    }

    private enum class GuideMode {
        NORMAL,
        TABLE
    }

    companion object {
        private const val HANDLE_RADIUS = 92f
        private const val HANDLE_TOUCH_RADIUS = 140f
    }
}
