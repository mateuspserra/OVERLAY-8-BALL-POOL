package com.overlaypool.ai

import android.graphics.Bitmap
import android.os.SystemClock
import com.overlaypool.model.DetectionResult
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object LocalHeuristicAIClient : AIClient {
    override fun detectFrame(bitmap: Bitmap): AIResponse {
        val startedAt = SystemClock.elapsedRealtime()
        val detections = PoolTableHeuristic.detect(bitmap)
        return AIResponse(
            detections = detections,
            connected = true,
            latencyMs = SystemClock.elapsedRealtime() - startedAt
        )
    }
}

private object PoolTableHeuristic {
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return emptyList()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val feltBounds = findFeltBounds(pixels, width, height) ?: return emptyList()
        val strictCueBall = findCueBall(pixels, width, feltBounds)
        val initialBallCandidates = findBallCandidates(pixels, width, feltBounds, strictCueBall)
        val cueBall = strictCueBall ?: chooseCueBallFromCandidates(pixels, width, initialBallCandidates)
        val ballCandidates = if (cueBall == null) {
            initialBallCandidates
        } else {
            initialBallCandidates.filterNot { overlaps(it, cueBall) }
        }
        val target = cueBall?.let { chooseTargetBall(it, ballCandidates) }

        val detections = mutableListOf<DetectionResult>()
        cueBall?.let { detections.add(it) }
        detections.addAll(ballCandidates.take(MAX_BALL_DETECTIONS))
        if (target != null) {
            detections.add(
                DetectionResult(
                    className = "cue_direction",
                    confidence = 0.82f,
                    x = target.centerX - 6f,
                    y = target.centerY - 6f,
                    width = 12f,
                    height = 12f
                )
            )
        }

        return detections
    }

    private fun findFeltBounds(pixels: IntArray, width: Int, height: Int): Bounds? {
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var count = 0

        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                if (isFelt(pixels[y * width + x])) {
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = max(maxX, x)
                    maxY = max(maxY, y)
                    count++
                }
            }
        }

        if (count < MIN_FELT_PIXELS) return null

        return Bounds(
            left = (minX + 6).coerceIn(0, width - 1),
            top = (minY + 6).coerceIn(0, height - 1),
            right = (maxX - 6).coerceIn(0, width - 1),
            bottom = (maxY - 6).coerceIn(0, height - 1)
        ).takeIf { it.width > width / 4 && it.height > height / 4 }
    }

    private fun findCueBall(
        pixels: IntArray,
        width: Int,
        bounds: Bounds
    ): DetectionResult? {
        findCueBallByCircleScan(pixels, width, bounds)?.let { return it }
        findCueBallByLooseComponents(pixels, width, bounds)?.let { return it }

        val components = findComponents(
            bounds = bounds,
            stride = WHITE_COMPONENT_STRIDE,
            include = { x, y ->
                val color = pixels[y * width + x]
                isCueWhite(color)
            }
        )

        return components
            .asSequence()
            .filter { it.area >= 8 }
            .filter { it.width in 6..60 && it.height in 6..60 }
            .filter { it.aspectRatio <= 1.65f }
            .filter { it.fillRatio >= 0.22f }
            .maxByOrNull { it.area * it.fillRatio }
            ?.toDetection("cue_ball", 0.88f)
    }

    private fun findCueBallByLooseComponents(
        pixels: IntArray,
        width: Int,
        bounds: Bounds
    ): DetectionResult? {
        val components = findComponents(
            bounds = bounds,
            stride = WHITE_COMPONENT_STRIDE,
            include = { x, y ->
                val color = pixels[y * width + x]
                isCueLike(color) && !isFelt(color)
            }
        )

        return components
            .asSequence()
            .filter { it.area >= 6 }
            .filter { it.width in 6..72 && it.height in 6..72 }
            .filter { it.aspectRatio <= 1.85f }
            .filter { it.fillRatio >= 0.14f }
            .maxByOrNull { it.area * it.fillRatio }
            ?.toDetection("cue_ball", 0.76f)
    }

    private fun findCueBallByCircleScan(
        pixels: IntArray,
        width: Int,
        bounds: Bounds
    ): DetectionResult? {
        val radius = (bounds.height / CUE_BALL_RADIUS_DIVISOR)
            .coerceIn(CUE_BALL_MIN_RADIUS, CUE_BALL_MAX_RADIUS)
        val radiusSquared = radius * radius
        val innerRadiusSquared = (radius * CUE_BALL_INNER_RADIUS_FACTOR).toInt().let { it * it }
        val step = (radius / 3).coerceAtLeast(2)

        var best: CircleCandidate? = null

        var centerY = bounds.top + radius
        while (centerY <= bounds.bottom - radius) {
            var centerX = bounds.left + radius
            while (centerX <= bounds.right - radius) {
                var diskPixels = 0
                var whitePixels = 0
                var innerWhitePixels = 0
                var feltPixels = 0

                var dy = -radius
                while (dy <= radius) {
                    val yy = centerY + dy
                    var dx = -radius
                    while (dx <= radius) {
                        val distanceSquared = dx * dx + dy * dy
                        if (distanceSquared <= radiusSquared) {
                            val color = pixels[yy * width + centerX + dx]
                            diskPixels++
                            if (isCueWhite(color)) {
                                whitePixels++
                                if (distanceSquared <= innerRadiusSquared) {
                                    innerWhitePixels++
                                }
                            }
                            if (isFelt(color)) {
                                feltPixels++
                            }
                        }
                        dx += 2
                    }
                    dy += 2
                }

                if (diskPixels > 0) {
                    val whiteRatio = whitePixels.toFloat() / diskPixels.toFloat()
                    val innerWhiteRatio = innerWhitePixels.toFloat() / (diskPixels * CUE_BALL_INNER_AREA_RATIO)
                    val feltRatio = feltPixels.toFloat() / diskPixels.toFloat()
                    if (whiteRatio >= CUE_BALL_MIN_WHITE_RATIO &&
                        innerWhiteRatio >= CUE_BALL_MIN_INNER_WHITE_RATIO &&
                        feltRatio <= CUE_BALL_MAX_FELT_RATIO
                    ) {
                        val score = whiteRatio * 0.65f + innerWhiteRatio * 0.35f - feltRatio * 0.15f
                        if (best == null || score > best.score) {
                            best = CircleCandidate(centerX, centerY, radius, score)
                        }
                    }
                }

                centerX += step
            }
            centerY += step
        }

        return best?.let {
            DetectionResult(
                className = "cue_ball",
                confidence = it.score.coerceIn(0.62f, 0.92f),
                x = (it.centerX - it.radius).toFloat(),
                y = (it.centerY - it.radius).toFloat(),
                width = (it.radius * 2).toFloat(),
                height = (it.radius * 2).toFloat()
            )
        }
    }

    private fun findBallCandidates(
        pixels: IntArray,
        width: Int,
        bounds: Bounds,
        cueBall: DetectionResult?
    ): List<DetectionResult> {
        val cueRadius = cueBall?.let { max(it.width, it.height) * 1.5f } ?: 0f
        val components = findComponents(
            bounds = bounds,
            stride = BALL_COMPONENT_STRIDE,
            include = { x, y ->
                val nearCueBall = cueBall != null &&
                    (x - cueBall.centerX).let { dx ->
                        (y - cueBall.centerY).let { dy -> dx * dx + dy * dy }
                    } < cueRadius * cueRadius
                if (nearCueBall) {
                    false
                } else {
                    val color = pixels[y * width + x]
                    isBallColor(color) && !isFelt(color)
                }
            }
        )

        return components
            .asSequence()
            .filter { it.area >= 5 }
            .filter { it.width in 5..70 && it.height in 5..70 }
            .filter { it.aspectRatio <= 2.2f }
            .filter { it.fillRatio >= 0.12f }
            .sortedWith(compareByDescending<Component> { it.area }.thenBy { it.centerX })
            .map { it.toDetection("target_ball", 0.70f) }
            .toList()
    }

    private fun chooseCueBallFromCandidates(
        pixels: IntArray,
        width: Int,
        candidates: List<DetectionResult>
    ): DetectionResult? {
        return candidates
            .asSequence()
            .map { candidate -> candidate to whitenessScore(pixels, width, candidate) }
            .filter { (_, score) -> score >= MIN_CUE_CANDIDATE_WHITENESS }
            .maxByOrNull { (_, score) -> score }
            ?.first
            ?.copy(className = "cue_ball", confidence = 0.68f)
    }

    private fun whitenessScore(
        pixels: IntArray,
        width: Int,
        detection: DetectionResult
    ): Float {
        val left = detection.x.toInt().coerceAtLeast(0)
        val top = detection.y.toInt().coerceAtLeast(0)
        val right = (detection.x + detection.width).toInt().coerceAtMost(width - 1)
        val bottom = (detection.y + detection.height).toInt()
        var sampled = 0
        var cueLike = 0

        var y = top
        while (y <= bottom && y * width < pixels.size) {
            var x = left
            while (x <= right) {
                sampled++
                if (isCueLike(pixels[y * width + x])) cueLike++
                x += 2
            }
            y += 2
        }

        return if (sampled == 0) 0f else cueLike.toFloat() / sampled.toFloat()
    }

    private fun overlaps(first: DetectionResult, second: DetectionResult): Boolean {
        val dx = first.centerX - second.centerX
        val dy = first.centerY - second.centerY
        val minDistance = max(first.radius, second.radius) * 0.85f
        return dx * dx + dy * dy <= minDistance * minDistance
    }

    private fun chooseTargetBall(
        cueBall: DetectionResult,
        candidates: List<DetectionResult>
    ): DetectionResult? {
        return candidates
            .filter {
                abs(it.centerX - cueBall.centerX) > cueBall.radius * 2f ||
                    abs(it.centerY - cueBall.centerY) > cueBall.radius * 2f
            }
            .minByOrNull {
                val dx = it.centerX - cueBall.centerX
                val dy = it.centerY - cueBall.centerY
                dx * dx + dy * dy
            }
    }

    private fun findComponents(
        bounds: Bounds,
        stride: Int,
        include: (x: Int, y: Int) -> Boolean
    ): List<Component> {
        val gridWidth = ((bounds.width + stride - 1) / stride).coerceAtLeast(1)
        val gridHeight = ((bounds.height + stride - 1) / stride).coerceAtLeast(1)
        val accepted = BooleanArray(gridWidth * gridHeight)
        val visited = BooleanArray(gridWidth * gridHeight)

        for (gy in 0 until gridHeight) {
            val y = (bounds.top + gy * stride).coerceAtMost(bounds.bottom)
            for (gx in 0 until gridWidth) {
                val x = (bounds.left + gx * stride).coerceAtMost(bounds.right)
                accepted[gy * gridWidth + gx] = include(x, y)
            }
        }

        val components = mutableListOf<Component>()
        val queue = ArrayDeque<Int>()
        for (index in accepted.indices) {
            if (!accepted[index] || visited[index]) continue

            visited[index] = true
            queue.add(index)
            var count = 0
            var minGX = gridWidth
            var minGY = gridHeight
            var maxGX = 0
            var maxGY = 0

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val gx = current % gridWidth
                val gy = current / gridWidth
                count++
                minGX = min(minGX, gx)
                minGY = min(minGY, gy)
                maxGX = max(maxGX, gx)
                maxGY = max(maxGY, gy)

                visitNeighbor(gx - 1, gy, gridWidth, gridHeight, accepted, visited, queue)
                visitNeighbor(gx + 1, gy, gridWidth, gridHeight, accepted, visited, queue)
                visitNeighbor(gx, gy - 1, gridWidth, gridHeight, accepted, visited, queue)
                visitNeighbor(gx, gy + 1, gridWidth, gridHeight, accepted, visited, queue)
            }

            components.add(
                Component(
                    x = bounds.left + minGX * stride,
                    y = bounds.top + minGY * stride,
                    width = ((maxGX - minGX + 1) * stride).coerceAtLeast(stride),
                    height = ((maxGY - minGY + 1) * stride).coerceAtLeast(stride),
                    area = count
                )
            )
        }

        return components
    }

    private fun visitNeighbor(
        gx: Int,
        gy: Int,
        width: Int,
        height: Int,
        accepted: BooleanArray,
        visited: BooleanArray,
        queue: ArrayDeque<Int>
    ) {
        if (gx !in 0 until width || gy !in 0 until height) return
        val index = gy * width + gx
        if (!accepted[index] || visited[index]) return
        visited[index] = true
        queue.add(index)
    }

    private fun isFelt(color: Int): Boolean {
        val red = color.red
        val green = color.green
        val blue = color.blue
        return blue > 95 &&
            green > 80 &&
            red < 120 &&
            blue >= red + 35 &&
            green >= red + 20
    }

    private fun isCueWhite(color: Int): Boolean {
        val red = color.red
        val green = color.green
        val blue = color.blue
        val luma = color.luma
        val chroma = maxOf(abs(red - green), abs(red - blue), abs(green - blue))
        return luma >= 168 && chroma <= 58
    }

    private fun isCueLike(color: Int): Boolean {
        val red = color.red
        val green = color.green
        val blue = color.blue
        val luma = color.luma
        val chroma = maxOf(abs(red - green), abs(red - blue), abs(green - blue))
        val minChannel = minOf(red, green, blue)
        return color.alpha > 32 &&
            luma >= 132 &&
            minChannel >= 92 &&
            chroma <= 118
    }

    private fun isBallColor(color: Int): Boolean {
        val red = color.red
        val green = color.green
        val blue = color.blue
        val luma = color.luma
        val saturation = maxOf(red, green, blue) - minOf(red, green, blue)
        return color.alpha > 32 &&
            luma in 25..235 &&
            (saturation >= 42 || luma <= 80)
    }

    private fun Component.toDetection(className: String, confidence: Float): DetectionResult {
        return DetectionResult(
            className = className,
            confidence = confidence,
            x = x.toFloat(),
            y = y.toFloat(),
            width = width.toFloat(),
            height = height.toFloat()
        )
    }

    private val Int.alpha: Int get() = this ushr 24 and 0xff
    private val Int.red: Int get() = this ushr 16 and 0xff
    private val Int.green: Int get() = this ushr 8 and 0xff
    private val Int.blue: Int get() = this and 0xff
    private val Int.luma: Int get() = (red * 299 + green * 587 + blue * 114) / 1000

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int = right - left + 1
        val height: Int = bottom - top + 1
    }

    private data class Component(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val area: Int
    ) {
        val centerX: Float = x + width / 2f
        val centerY: Float = y + height / 2f
        val aspectRatio: Float = max(width, height).toFloat() / min(width, height).coerceAtLeast(1)
        val fillRatio: Float = area.toFloat() / ((width * height).coerceAtLeast(1).toFloat())
    }

    private data class CircleCandidate(
        val centerX: Int,
        val centerY: Int,
        val radius: Int,
        val score: Float
    )

    private const val MIN_FELT_PIXELS = 800
    private const val WHITE_COMPONENT_STRIDE = 2
    private const val BALL_COMPONENT_STRIDE = 3
    private const val MAX_BALL_DETECTIONS = 16
    private const val CUE_BALL_RADIUS_DIVISOR = 42
    private const val CUE_BALL_MIN_RADIUS = 7
    private const val CUE_BALL_MAX_RADIUS = 22
    private const val CUE_BALL_INNER_RADIUS_FACTOR = 0.62f
    private const val CUE_BALL_INNER_AREA_RATIO = 0.38f
    private const val CUE_BALL_MIN_WHITE_RATIO = 0.18f
    private const val CUE_BALL_MIN_INNER_WHITE_RATIO = 0.22f
    private const val CUE_BALL_MAX_FELT_RATIO = 0.78f
    private const val MIN_CUE_CANDIDATE_WHITENESS = 0.16f
}
