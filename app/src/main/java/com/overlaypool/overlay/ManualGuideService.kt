package com.overlaypool.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
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
            PixelFormat.TRANSLUCENT
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
    private var bankPoint = PointF(Float.NaN, Float.NaN)
    private var mode = GuideMode.NORMAL
    private var dragTarget = DragTarget.NONE
    private var showSecondBankLine = true
    private val tableRect = RectF()
    private val pockets = mutableListOf<PointF>()

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 255, 255, 255)
        strokeWidth = 24f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val reflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 18f), 0f)
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val railPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(125, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 165, 30, 10)
        strokeWidth = 8f
        style = Paint.Style.STROKE
    }
    private val pointFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(34, 165, 30, 10)
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 165, 30, 10)
        style = Paint.Style.FILL
    }
    private val centerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bankPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
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
        textSize = 34f
        isFakeBoldText = true
    }

    private var normalButton = RectF()
    private var tableButton = RectF()
    private var secondLineButton = RectF()
    private var closeButton = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateTable(width, height)
        if (guidePoint.x.isNaN() || guidePoint.y.isNaN()) {
            guidePoint.set(tableRect.centerX(), tableRect.centerY())
        }
        if (bankPoint.x.isNaN() || bankPoint.y.isNaN()) {
            bankPoint.set(tableRect.right, tableRect.centerY())
        }
        updateButtons(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTableFrame(canvas)
        when (mode) {
            GuideMode.NORMAL -> drawPocketGuide(canvas)
            GuideMode.TABLE -> drawBankGuide(canvas)
        }
        drawButtons(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (closeButton.contains(event.x, event.y)) {
                    onClose()
                    return true
                }
                if (normalButton.contains(event.x, event.y)) {
                    mode = GuideMode.NORMAL
                    dragTarget = DragTarget.NONE
                    invalidate()
                    return true
                }
                if (tableButton.contains(event.x, event.y)) {
                    mode = GuideMode.TABLE
                    dragTarget = DragTarget.NONE
                    invalidate()
                    return true
                }
                if (mode == GuideMode.TABLE && secondLineButton.contains(event.x, event.y)) {
                    showSecondBankLine = !showSecondBankLine
                    invalidate()
                    return true
                }

                dragTarget = chooseDragTarget(event.x, event.y)
                if (dragTarget == DragTarget.NONE) {
                    dragTarget = if (mode == GuideMode.TABLE) DragTarget.BANK else DragTarget.GUIDE
                }
                updateDraggedPoint(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragTarget != DragTarget.NONE) {
                    updateDraggedPoint(event.x, event.y)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragTarget = DragTarget.NONE
                return true
            }
        }

        return true
    }

    private fun chooseDragTarget(x: Float, y: Float): DragTarget {
        val guideDistance = distance(x, y, guidePoint.x, guidePoint.y)
        val bankDistance = distance(x, y, bankPoint.x, bankPoint.y)
        return when {
            mode == GuideMode.TABLE &&
                bankDistance <= BANK_TOUCH_RADIUS &&
                bankDistance <= guideDistance -> DragTarget.BANK
            guideDistance <= GUIDE_TOUCH_RADIUS -> DragTarget.GUIDE
            mode == GuideMode.TABLE && bankDistance <= BANK_TOUCH_RADIUS -> DragTarget.BANK
            else -> DragTarget.NONE
        }
    }

    private fun updateDraggedPoint(x: Float, y: Float) {
        when (dragTarget) {
            DragTarget.GUIDE -> guidePoint.set(
                x.coerceIn(tableRect.left, tableRect.right),
                y.coerceIn(tableRect.top, tableRect.bottom)
            )
            DragTarget.BANK -> updateBankPoint(x, y)
            DragTarget.NONE -> Unit
        }
        invalidate()
    }

    private fun updateBankPoint(x: Float, y: Float) {
        var nextX = x.coerceIn(tableRect.left, tableRect.right)
        var nextY = y.coerceIn(tableRect.top, tableRect.bottom)
        val edgeDistances = listOf(
            Wall.LEFT to abs(nextX - tableRect.left),
            Wall.RIGHT to abs(tableRect.right - nextX),
            Wall.TOP to abs(nextY - tableRect.top),
            Wall.BOTTOM to abs(tableRect.bottom - nextY)
        )
        val nearest = edgeDistances.minBy { it.second }
        val outside = x < tableRect.left ||
            x > tableRect.right ||
            y < tableRect.top ||
            y > tableRect.bottom

        if (outside || nearest.second <= EDGE_SNAP_DISTANCE) {
            when (nearest.first) {
                Wall.LEFT -> nextX = tableRect.left
                Wall.RIGHT -> nextX = tableRect.right
                Wall.TOP -> nextY = tableRect.top
                Wall.BOTTOM -> nextY = tableRect.bottom
            }
        }

        bankPoint.set(nextX, nextY)
    }

    private fun drawPocketGuide(canvas: Canvas) {
        pockets.forEach { pocket ->
            val start = offsetToward(guidePoint, pocket, GUIDE_RADIUS * 0.72f)
            canvas.drawLine(start.x, start.y, pocket.x, pocket.y, shadowPaint)
            canvas.drawLine(start.x, start.y, pocket.x, pocket.y, guidePaint)
        }
        drawGuideHandle(canvas)
    }

    private fun drawBankGuide(canvas: Canvas) {
        canvas.drawLine(guidePoint.x, guidePoint.y, bankPoint.x, bankPoint.y, guidePaint)
        canvas.drawCircle(bankPoint.x, bankPoint.y, BALL_RADIUS, bankPointPaint)

        val wall = wallAt(bankPoint)
        if (wall != null) {
            val dx = bankPoint.x - guidePoint.x
            val dy = bankPoint.y - guidePoint.y
            val firstVector = reflectedVector(dx, dy, wall)
            val firstHit = intersectRay(bankPoint, firstVector.x, firstVector.y)
            if (firstHit != null) {
                canvas.drawLine(
                    bankPoint.x,
                    bankPoint.y,
                    firstHit.point.x,
                    firstHit.point.y,
                    reflectionPaint
                )
                canvas.drawCircle(firstHit.point.x, firstHit.point.y, BALL_RADIUS, bankPointPaint)

                if (showSecondBankLine) {
                    val secondVector = reflectedVector(firstVector.x, firstVector.y, firstHit.wall)
                    val secondHit = intersectRay(firstHit.point, secondVector.x, secondVector.y)
                    if (secondHit != null) {
                        canvas.drawLine(
                            firstHit.point.x,
                            firstHit.point.y,
                            secondHit.point.x,
                            secondHit.point.y,
                            reflectionPaint
                        )
                        canvas.drawCircle(secondHit.point.x, secondHit.point.y, BALL_RADIUS, bankPointPaint)
                    }
                }
            }
        }

        drawGuideHandle(canvas)
    }

    private fun drawGuideHandle(canvas: Canvas) {
        canvas.drawCircle(guidePoint.x, guidePoint.y, GUIDE_RADIUS, pointFillPaint)
        canvas.drawCircle(guidePoint.x, guidePoint.y, GUIDE_RADIUS, pointPaint)
        canvas.drawCircle(guidePoint.x, guidePoint.y, 10f, centerBorderPaint)
        canvas.drawCircle(guidePoint.x, guidePoint.y, 7f, centerPaint)
    }

    private fun drawTableFrame(canvas: Canvas) {
        canvas.drawRoundRect(tableRect, 16f, 16f, railPaint)
        pockets.forEach { pocket ->
            canvas.drawCircle(pocket.x, pocket.y, 12f, pocketPaint)
        }
    }

    private fun drawButtons(canvas: Canvas) {
        drawButton(canvas, normalButton, "NORMAL", mode == GuideMode.NORMAL)
        drawButton(canvas, tableButton, "TABELA", mode == GuideMode.TABLE)
        if (mode == GuideMode.TABLE) {
            drawButton(canvas, secondLineButton, "2 LINHAS", showSecondBankLine)
        }
        canvas.drawRoundRect(closeButton, 8f, 8f, closeButtonPaint)
        drawCenteredText(canvas, "FECHAR", closeButton)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, text: String, active: Boolean) {
        canvas.drawRoundRect(rect, 8f, 8f, if (active) activeButtonPaint else buttonPaint)
        drawCenteredText(canvas, text, rect)
    }

    private fun drawCenteredText(canvas: Canvas, text: String, rect: RectF) {
        val originalSize = textPaint.textSize
        val maxWidth = rect.width() - 20f
        if (textPaint.measureText(text) > maxWidth) {
            textPaint.textSize = max(18f, originalSize * maxWidth / textPaint.measureText(text))
        }
        val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, rect.centerX(), y, textPaint)
        textPaint.textSize = originalSize
    }

    private fun updateTable(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val landscape = width > height
        val left = if (landscape) width * 0.12f else width * 0.08f
        val right = if (landscape) width * 0.88f else width * 0.92f
        val top = if (landscape) height * 0.14f else height * 0.24f
        val bottom = if (landscape) height * 0.84f else height * 0.78f
        tableRect.set(left, top, right, bottom)

        val centerX = tableRect.centerX()
        pockets.clear()
        pockets.add(PointF(tableRect.left, tableRect.top))
        pockets.add(PointF(centerX, tableRect.top))
        pockets.add(PointF(tableRect.right, tableRect.top))
        pockets.add(PointF(tableRect.left, tableRect.bottom))
        pockets.add(PointF(centerX, tableRect.bottom))
        pockets.add(PointF(tableRect.right, tableRect.bottom))
    }

    private fun updateButtons(width: Int, height: Int) {
        val side = max(14f, width * 0.018f)
        val gap = 8f
        val buttonHeight = min(74f, max(54f, height * 0.08f))
        val bottom = height - max(18f, height * 0.025f)
        val top = bottom - buttonHeight
        val closeWidth = min(250f, max(112f, width * 0.22f))
        val modeWidth = max(98f, min(190f, (width - side * 2f - closeWidth - gap * 4f) / 2f))

        normalButton = RectF(side, top, side + modeWidth, bottom)
        tableButton = RectF(normalButton.right + gap, top, normalButton.right + gap + modeWidth, bottom)
        closeButton = RectF(width - side - closeWidth, top, width - side, bottom)

        val secondWidth = min(180f, max(118f, width * 0.16f))
        secondLineButton = RectF(
            width - side - secondWidth,
            max(18f, height * 0.025f),
            width - side,
            max(18f, height * 0.025f) + buttonHeight
        )
    }

    private fun offsetToward(from: PointF, to: PointF, offset: Float): PointF {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy).coerceAtLeast(1f)
        return PointF(from.x + dx / length * offset, from.y + dy / length * offset)
    }

    private fun wallAt(point: PointF): Wall? {
        return when {
            abs(point.x - tableRect.left) <= WALL_EPSILON -> Wall.LEFT
            abs(point.x - tableRect.right) <= WALL_EPSILON -> Wall.RIGHT
            abs(point.y - tableRect.top) <= WALL_EPSILON -> Wall.TOP
            abs(point.y - tableRect.bottom) <= WALL_EPSILON -> Wall.BOTTOM
            else -> null
        }
    }

    private fun reflectedVector(dx: Float, dy: Float, wall: Wall): PointF {
        return when (wall) {
            Wall.LEFT, Wall.RIGHT -> PointF(-dx, dy)
            Wall.TOP, Wall.BOTTOM -> PointF(dx, -dy)
        }
    }

    private fun intersectRay(start: PointF, dx: Float, dy: Float): RayHit? {
        val rect = RectF(
            tableRect.left + BALL_RADIUS,
            tableRect.top + BALL_RADIUS,
            tableRect.right - BALL_RADIUS,
            tableRect.bottom - BALL_RADIUS
        )
        var bestT = Float.POSITIVE_INFINITY
        var bestWall: Wall? = null

        fun consider(t: Float, wall: Wall, cross: Float, minCross: Float, maxCross: Float) {
            if (t > RAY_EPSILON && t < bestT && cross >= minCross && cross <= maxCross) {
                bestT = t
                bestWall = wall
            }
        }

        if (dx < -RAY_EPSILON) {
            val t = (rect.left - start.x) / dx
            consider(t, Wall.LEFT, start.y + t * dy, rect.top, rect.bottom)
        }
        if (dx > RAY_EPSILON) {
            val t = (rect.right - start.x) / dx
            consider(t, Wall.RIGHT, start.y + t * dy, rect.top, rect.bottom)
        }
        if (dy < -RAY_EPSILON) {
            val t = (rect.top - start.y) / dy
            consider(t, Wall.TOP, start.x + t * dx, rect.left, rect.right)
        }
        if (dy > RAY_EPSILON) {
            val t = (rect.bottom - start.y) / dy
            consider(t, Wall.BOTTOM, start.x + t * dx, rect.left, rect.right)
        }

        val wall = bestWall ?: return null
        return RayHit(PointF(start.x + bestT * dx, start.y + bestT * dy), wall)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return hypot(x1 - x2, y1 - y2)
    }

    private data class RayHit(val point: PointF, val wall: Wall)

    private enum class DragTarget {
        NONE,
        GUIDE,
        BANK
    }

    private enum class GuideMode {
        NORMAL,
        TABLE
    }

    private enum class Wall {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
    }

    companion object {
        private const val GUIDE_RADIUS = 92f
        private const val GUIDE_TOUCH_RADIUS = 140f
        private const val BANK_TOUCH_RADIUS = 70f
        private const val BALL_RADIUS = 22f
        private const val EDGE_SNAP_DISTANCE = 42f
        private const val WALL_EPSILON = 1.5f
        private const val RAY_EPSILON = 0.001f
    }
}
