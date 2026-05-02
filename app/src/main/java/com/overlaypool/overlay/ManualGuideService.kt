package com.overlaypool.overlay

import android.app.Service
import android.content.Context
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
import com.overlaypool.core.AppActions
import com.overlaypool.core.DetectionStateStore
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ManualGuideService : Service() {
    private var windowManager: WindowManager? = null
    private var guideView: ManualGuideView? = null
    private var guideParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AppActions.ACTION_CLOSE_MANUAL_GUIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            AppActions.ACTION_TOGGLE_MANUAL_GUIDE -> {
                if (guideView == null) {
                    createGuide()
                } else {
                    setGuideLocked(!(guideView?.isInputLocked ?: false))
                }
            }
            else -> createGuide()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        guideView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        guideView = null
        guideParams = null
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
            guideWindowFlags(locked = false),
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val view = ManualGuideView(
            context = this,
            onClose = { stopSelf() },
            onLockChanged = { locked -> setGuideLocked(locked) }
        )
        runCatching {
            windowManager?.addView(view, params)
            guideView = view
            guideParams = params
            DetectionStateStore.updateStatus {
                it.copy(systemState = "Guia manual em ajuste")
            }
        }.onFailure {
            stopSelf()
        }
    }

    private fun setGuideLocked(locked: Boolean) {
        val view = guideView ?: return
        val params = guideParams ?: return
        view.setInputLocked(locked)
        params.flags = guideWindowFlags(locked)
        runCatching { windowManager?.updateViewLayout(view, params) }
        DetectionStateStore.updateStatus {
            it.copy(
                systemState = if (locked) {
                    "Guia manual travado; toque liberado para o jogo"
                } else {
                    "Guia manual em ajuste"
                }
            )
        }
    }

    private fun guideWindowFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (locked) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return flags
    }
}

private class ManualGuideView(
    context: android.content.Context,
    private val onClose: () -> Unit,
    private val onLockChanged: (Boolean) -> Unit
) : View(context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var guidePoint = PointF(Float.NaN, Float.NaN)
    private var aimPoint = PointF(Float.NaN, Float.NaN)
    private var bankPoint = PointF(Float.NaN, Float.NaN)
    private var mode = GuideMode.NORMAL
    private var dragTarget = DragTarget.NONE
    private var showSecondBankLine = true
    var isInputLocked: Boolean = false
        private set
    private val tableRect = RectF()
    private val pockets = mutableListOf<PointF>()

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 255, 255, 255)
        strokeWidth = 9f
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
    private val cornerHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 165, 30, 10)
        strokeWidth = 5f
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
    private val aimPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    private val backAimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(16f, 16f), 0f)
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
    private var lockButton = RectF()
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
            guidePoint.set(tableRect.left + tableRect.width() * 0.28f, tableRect.centerY())
        }
        if (aimPoint.x.isNaN() || aimPoint.y.isNaN()) {
            aimPoint.set(tableRect.left + tableRect.width() * 0.70f, tableRect.centerY())
        }
        if (bankPoint.x.isNaN() || bankPoint.y.isNaN()) {
            bankPoint.set(tableRect.right, tableRect.centerY())
        }
        updateButtons(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isInputLocked) {
            drawTableFrame(canvas)
        }
        when (mode) {
            GuideMode.NORMAL -> drawAimGuide(canvas)
            GuideMode.TABLE -> drawBankGuide(canvas)
        }
        if (!isInputLocked) {
            drawButtons(canvas)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isInputLocked) return false
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
                if (lockButton.contains(event.x, event.y)) {
                    onLockChanged(true)
                    return true
                }
                if (mode == GuideMode.TABLE && secondLineButton.contains(event.x, event.y)) {
                    showSecondBankLine = !showSecondBankLine
                    invalidate()
                    return true
                }

                dragTarget = chooseDragTarget(event.x, event.y)
                if (dragTarget == DragTarget.NONE) {
                    dragTarget = if (mode == GuideMode.TABLE) DragTarget.BANK else DragTarget.AIM
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
                if (dragTarget.isTableCorner()) {
                    saveTableCalibration()
                }
                dragTarget = DragTarget.NONE
                return true
            }
        }

        return true
    }

    fun setInputLocked(locked: Boolean) {
        isInputLocked = locked
        dragTarget = DragTarget.NONE
        invalidate()
    }

    private fun chooseDragTarget(x: Float, y: Float): DragTarget {
        cornerDragTarget(x, y)?.let { return it }

        val guideDistance = distance(x, y, guidePoint.x, guidePoint.y)
        val aimDistance = distance(x, y, aimPoint.x, aimPoint.y)
        val bankDistance = distance(x, y, bankPoint.x, bankPoint.y)
        return when {
            mode == GuideMode.TABLE &&
                bankDistance <= BANK_TOUCH_RADIUS &&
                bankDistance <= guideDistance -> DragTarget.BANK
            mode == GuideMode.NORMAL &&
                aimDistance <= AIM_TOUCH_RADIUS &&
                aimDistance <= guideDistance -> DragTarget.AIM
            guideDistance <= GUIDE_TOUCH_RADIUS -> DragTarget.GUIDE
            mode == GuideMode.NORMAL && aimDistance <= AIM_TOUCH_RADIUS -> DragTarget.AIM
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
            DragTarget.AIM -> aimPoint.set(
                x.coerceIn(tableRect.left, tableRect.right),
                y.coerceIn(tableRect.top, tableRect.bottom)
            )
            DragTarget.BANK -> updateBankPoint(x, y)
            DragTarget.TABLE_TOP_LEFT,
            DragTarget.TABLE_TOP_RIGHT,
            DragTarget.TABLE_BOTTOM_LEFT,
            DragTarget.TABLE_BOTTOM_RIGHT -> updateTableCorner(dragTarget, x, y)
            DragTarget.NONE -> Unit
        }
        invalidate()
    }

    private fun cornerDragTarget(x: Float, y: Float): DragTarget? {
        val corners = listOf(
            DragTarget.TABLE_TOP_LEFT to PointF(tableRect.left, tableRect.top),
            DragTarget.TABLE_TOP_RIGHT to PointF(tableRect.right, tableRect.top),
            DragTarget.TABLE_BOTTOM_LEFT to PointF(tableRect.left, tableRect.bottom),
            DragTarget.TABLE_BOTTOM_RIGHT to PointF(tableRect.right, tableRect.bottom)
        )
        return corners
            .filter { (_, point) -> distance(x, y, point.x, point.y) <= TABLE_CORNER_TOUCH_RADIUS }
            .minByOrNull { (_, point) -> distance(x, y, point.x, point.y) }
            ?.first
    }

    private fun updateTableCorner(target: DragTarget, x: Float, y: Float) {
        val minWidth = max(width * 0.35f, 320f)
        val minHeight = max(height * 0.30f, 220f)
        val nextX = x.coerceIn(0f, width.toFloat())
        val nextY = y.coerceIn(0f, height.toFloat())

        when (target) {
            DragTarget.TABLE_TOP_LEFT -> {
                tableRect.left = nextX.coerceAtMost(tableRect.right - minWidth)
                tableRect.top = nextY.coerceAtMost(tableRect.bottom - minHeight)
            }
            DragTarget.TABLE_TOP_RIGHT -> {
                tableRect.right = nextX.coerceAtLeast(tableRect.left + minWidth)
                tableRect.top = nextY.coerceAtMost(tableRect.bottom - minHeight)
            }
            DragTarget.TABLE_BOTTOM_LEFT -> {
                tableRect.left = nextX.coerceAtMost(tableRect.right - minWidth)
                tableRect.bottom = nextY.coerceAtLeast(tableRect.top + minHeight)
            }
            DragTarget.TABLE_BOTTOM_RIGHT -> {
                tableRect.right = nextX.coerceAtLeast(tableRect.left + minWidth)
                tableRect.bottom = nextY.coerceAtLeast(tableRect.top + minHeight)
            }
            else -> Unit
        }

        updatePocketsFromTable()
        guidePoint.set(
            guidePoint.x.coerceIn(tableRect.left, tableRect.right),
            guidePoint.y.coerceIn(tableRect.top, tableRect.bottom)
        )
        aimPoint.set(
            aimPoint.x.coerceIn(tableRect.left, tableRect.right),
            aimPoint.y.coerceIn(tableRect.top, tableRect.bottom)
        )
        updateBankPoint(bankPoint.x, bankPoint.y)
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

    private fun drawAimGuide(canvas: Canvas) {
        val dx = aimPoint.x - guidePoint.x
        val dy = aimPoint.y - guidePoint.y
        val forwardHit = intersectRay(guidePoint, dx, dy)
        val backwardHit = intersectRay(guidePoint, -dx, -dy)

        if (backwardHit != null) {
            canvas.drawLine(
                backwardHit.point.x,
                backwardHit.point.y,
                guidePoint.x,
                guidePoint.y,
                backAimPaint
            )
        }

        if (forwardHit != null) {
            canvas.drawLine(guidePoint.x, guidePoint.y, forwardHit.point.x, forwardHit.point.y, shadowPaint)
            canvas.drawLine(guidePoint.x, guidePoint.y, forwardHit.point.x, forwardHit.point.y, guidePaint)
        }

        drawGuideHandle(canvas)
        drawAimHandle(canvas)
    }

    private fun drawBankGuide(canvas: Canvas) {
        canvas.drawLine(guidePoint.x, guidePoint.y, bankPoint.x, bankPoint.y, guidePaint)
        canvas.drawCircle(bankPoint.x, bankPoint.y, if (isInputLocked) LOCKED_BALL_RADIUS else BALL_RADIUS, bankPointPaint)

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
                canvas.drawCircle(firstHit.point.x, firstHit.point.y, if (isInputLocked) LOCKED_BALL_RADIUS else BALL_RADIUS, bankPointPaint)

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
                        canvas.drawCircle(secondHit.point.x, secondHit.point.y, if (isInputLocked) LOCKED_BALL_RADIUS else BALL_RADIUS, bankPointPaint)
                    }
                }
            }
        }

        drawGuideHandle(canvas)
    }

    private fun drawGuideHandle(canvas: Canvas) {
        val radius = if (isInputLocked) LOCKED_GUIDE_RADIUS else GUIDE_RADIUS
        canvas.drawCircle(guidePoint.x, guidePoint.y, radius, pointFillPaint)
        canvas.drawCircle(guidePoint.x, guidePoint.y, radius, pointPaint)
        canvas.drawCircle(guidePoint.x, guidePoint.y, 10f, centerBorderPaint)
        canvas.drawCircle(guidePoint.x, guidePoint.y, 7f, centerPaint)
    }

    private fun drawAimHandle(canvas: Canvas) {
        val radius = if (isInputLocked) LOCKED_AIM_RADIUS else AIM_RADIUS
        canvas.drawCircle(aimPoint.x, aimPoint.y, radius, aimPointPaint)
        canvas.drawLine(aimPoint.x - radius, aimPoint.y, aimPoint.x + radius, aimPoint.y, aimPointPaint)
        canvas.drawLine(aimPoint.x, aimPoint.y - radius, aimPoint.x, aimPoint.y + radius, aimPointPaint)
    }

    private fun drawTableFrame(canvas: Canvas) {
        canvas.drawRoundRect(tableRect, 16f, 16f, railPaint)
        pockets.forEach { pocket ->
            canvas.drawCircle(pocket.x, pocket.y, 12f, pocketPaint)
        }
        canvas.drawCircle(tableRect.left, tableRect.top, TABLE_CORNER_HANDLE_RADIUS, cornerHandlePaint)
        canvas.drawCircle(tableRect.right, tableRect.top, TABLE_CORNER_HANDLE_RADIUS, cornerHandlePaint)
        canvas.drawCircle(tableRect.left, tableRect.bottom, TABLE_CORNER_HANDLE_RADIUS, cornerHandlePaint)
        canvas.drawCircle(tableRect.right, tableRect.bottom, TABLE_CORNER_HANDLE_RADIUS, cornerHandlePaint)
    }

    private fun drawButtons(canvas: Canvas) {
        drawButton(canvas, normalButton, "MIRA", mode == GuideMode.NORMAL)
        drawButton(canvas, tableButton, "TABELA", mode == GuideMode.TABLE)
        drawButton(canvas, lockButton, "TRAVAR", active = false)
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
        val leftRatio = preferences.getFloat(PREF_TABLE_LEFT, defaultLeftRatio(width, height))
        val rightRatio = preferences.getFloat(PREF_TABLE_RIGHT, defaultRightRatio(width, height))
        val topRatio = preferences.getFloat(PREF_TABLE_TOP, defaultTopRatio(width, height))
        val bottomRatio = preferences.getFloat(PREF_TABLE_BOTTOM, defaultBottomRatio(width, height))
        val left = width * leftRatio
        val right = width * rightRatio
        val top = height * topRatio
        val bottom = height * bottomRatio
        tableRect.set(left, top, right, bottom)
        updatePocketsFromTable()
    }

    private fun updatePocketsFromTable() {
        val centerX = tableRect.centerX()
        pockets.clear()
        pockets.add(PointF(tableRect.left, tableRect.top))
        pockets.add(PointF(centerX, tableRect.top))
        pockets.add(PointF(tableRect.right, tableRect.top))
        pockets.add(PointF(tableRect.left, tableRect.bottom))
        pockets.add(PointF(centerX, tableRect.bottom))
        pockets.add(PointF(tableRect.right, tableRect.bottom))
    }

    private fun saveTableCalibration() {
        if (width <= 0 || height <= 0) return
        preferences.edit()
            .putFloat(PREF_TABLE_LEFT, tableRect.left / width.toFloat())
            .putFloat(PREF_TABLE_RIGHT, tableRect.right / width.toFloat())
            .putFloat(PREF_TABLE_TOP, tableRect.top / height.toFloat())
            .putFloat(PREF_TABLE_BOTTOM, tableRect.bottom / height.toFloat())
            .apply()
    }

    private fun defaultLeftRatio(width: Int, height: Int): Float {
        return if (width > height) 0.175f else 0.08f
    }

    private fun defaultRightRatio(width: Int, height: Int): Float {
        return if (width > height) 0.89f else 0.92f
    }

    private fun defaultTopRatio(width: Int, height: Int): Float {
        return if (width > height) 0.205f else 0.24f
    }

    private fun defaultBottomRatio(width: Int, height: Int): Float {
        return if (width > height) 0.935f else 0.78f
    }

    private fun updateButtons(width: Int, height: Int) {
        val side = max(14f, width * 0.018f)
        val gap = 8f
        val buttonHeight = min(74f, max(54f, height * 0.08f))
        val bottom = height - max(18f, height * 0.025f)
        val top = bottom - buttonHeight
        val closeWidth = min(220f, max(112f, width * 0.18f))
        val availableWidth = width - side * 2f - closeWidth - gap * 5f
        val modeWidth = max(88f, min(170f, availableWidth / 3f))

        normalButton = RectF(side, top, side + modeWidth, bottom)
        tableButton = RectF(normalButton.right + gap, top, normalButton.right + gap + modeWidth, bottom)
        lockButton = RectF(tableButton.right + gap, top, tableButton.right + gap + modeWidth, bottom)
        closeButton = RectF(width - side - closeWidth, top, width - side, bottom)

        val secondWidth = min(180f, max(118f, width * 0.16f))
        secondLineButton = RectF(
            width - side - secondWidth,
            max(18f, height * 0.025f),
            width - side,
            max(18f, height * 0.025f) + buttonHeight
        )
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
        AIM,
        BANK,
        TABLE_TOP_LEFT,
        TABLE_TOP_RIGHT,
        TABLE_BOTTOM_LEFT,
        TABLE_BOTTOM_RIGHT
    }

    private fun DragTarget.isTableCorner(): Boolean {
        return this == DragTarget.TABLE_TOP_LEFT ||
            this == DragTarget.TABLE_TOP_RIGHT ||
            this == DragTarget.TABLE_BOTTOM_LEFT ||
            this == DragTarget.TABLE_BOTTOM_RIGHT
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
        private const val GUIDE_RADIUS = 42f
        private const val LOCKED_GUIDE_RADIUS = 13f
        private const val AIM_RADIUS = 28f
        private const val LOCKED_AIM_RADIUS = 13f
        private const val GUIDE_TOUCH_RADIUS = 90f
        private const val AIM_TOUCH_RADIUS = 82f
        private const val BANK_TOUCH_RADIUS = 70f
        private const val BALL_RADIUS = 22f
        private const val LOCKED_BALL_RADIUS = 12f
        private const val TABLE_CORNER_HANDLE_RADIUS = 24f
        private const val TABLE_CORNER_TOUCH_RADIUS = 58f
        private const val EDGE_SNAP_DISTANCE = 42f
        private const val WALL_EPSILON = 1.5f
        private const val RAY_EPSILON = 0.001f
        private const val PREFS_NAME = "manual_guide"
        private const val PREF_TABLE_LEFT = "table_left_ratio"
        private const val PREF_TABLE_RIGHT = "table_right_ratio"
        private const val PREF_TABLE_TOP = "table_top_ratio"
        private const val PREF_TABLE_BOTTOM = "table_bottom_ratio"
    }
}
