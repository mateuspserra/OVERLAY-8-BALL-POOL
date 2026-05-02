package com.overlaypool.trajectory

import com.overlaypool.model.DetectionResult
import com.overlaypool.model.ScreenPoint
import com.overlaypool.model.TrajectoryLine
import com.overlaypool.model.TrajectoryResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

object TrajectoryEngine {
    fun calculate(
        detections: List<DetectionResult>,
        screenWidth: Int,
        screenHeight: Int
    ): TrajectoryResult? {
        val cueBall = detections.firstOrNull { it.className == "cue_ball" } ?: return null
        val direction = inferDirection(cueBall, detections)?.normalized() ?: return null
        val start = Vector(cueBall.centerX, cueBall.centerY)

        val firstBallImpact = findFirstBallImpact(cueBall, direction, detections)
        val boundaryImpact = findBoundaryImpact(start, direction, screenWidth, screenHeight)
        val impact = listOfNotNull(firstBallImpact, boundaryImpact).minByOrNull { it.distance }
            ?: return null

        val startPoint = ScreenPoint(start.x, start.y)
        val endPoint = ScreenPoint(impact.point.x, impact.point.y)
        val angle = Math.toDegrees(atan2(direction.y, direction.x).toDouble()).toFloat()

        return TrajectoryResult(
            primaryLine = TrajectoryLine(startPoint, endPoint),
            impactPoint = endPoint,
            impactClassName = impact.className,
            angleDegrees = angle
        )
    }

    private fun inferDirection(
        cueBall: DetectionResult,
        detections: List<DetectionResult>
    ): Vector? {
        val aimLine = detections
            .filter { it.className == "aim_line" }
            .maxByOrNull { it.confidence }
        if (aimLine != null) {
            val towardLine = Vector(
                aimLine.centerX - cueBall.centerX,
                aimLine.centerY - cueBall.centerY
            )
            if (towardLine.length > cueBall.radius) return towardLine

            return if (aimLine.width >= aimLine.height) {
                Vector(1f, 0f)
            } else {
                Vector(0f, -1f)
            }
        }

        val cueDirection = detections
            .filter { it.className == "cue_direction" }
            .maxByOrNull { it.confidence }
        if (cueDirection != null) {
            val vector = Vector(
                cueDirection.centerX - cueBall.centerX,
                cueDirection.centerY - cueBall.centerY
            )
            if (vector.length > cueBall.radius) return vector
        }

        val target = detections
            .filter { it.className == "target_ball" || it.className == "ghost_ball" }
            .maxByOrNull { it.confidence }
        if (target != null) {
            val vector = Vector(target.centerX - cueBall.centerX, target.centerY - cueBall.centerY)
            if (vector.length > cueBall.radius) return vector
        }

        return null
    }

    private fun findFirstBallImpact(
        cueBall: DetectionResult,
        direction: Vector,
        detections: List<DetectionResult>
    ): Impact? {
        val start = Vector(cueBall.centerX, cueBall.centerY)
        return detections
            .asSequence()
            .filter { it !== cueBall }
            .filter { it.className !in nonCollisionClasses }
            .mapNotNull { candidate ->
                val target = Vector(candidate.centerX, candidate.centerY)
                val toTarget = target - start
                val projection = toTarget.dot(direction)
                if (projection <= cueBall.radius) return@mapNotNull null

                val closest = start + direction * projection
                val perpendicular = (target - closest).length
                val effectiveRadius = max(candidate.radius + cueBall.radius * 0.6f, candidate.radius)
                if (perpendicular > effectiveRadius) return@mapNotNull null

                val offset = sqrt(max(0f, effectiveRadius * effectiveRadius - perpendicular * perpendicular))
                val distance = projection - offset
                if (distance <= 0f) return@mapNotNull null

                Impact(
                    point = start + direction * distance,
                    distance = distance,
                    className = candidate.className
                )
            }
            .minByOrNull { it.distance }
    }

    private fun findBoundaryImpact(
        start: Vector,
        direction: Vector,
        screenWidth: Int,
        screenHeight: Int
    ): Impact? {
        val margin = 0f
        val candidates = mutableListOf<Float>()

        if (abs(direction.x) > EPSILON) {
            candidates.add((margin - start.x) / direction.x)
            candidates.add((screenWidth - margin - start.x) / direction.x)
        }
        if (abs(direction.y) > EPSILON) {
            candidates.add((margin - start.y) / direction.y)
            candidates.add((screenHeight - margin - start.y) / direction.y)
        }

        val distance = candidates
            .filter { it > EPSILON }
            .minOrNull()
            ?: return null

        return Impact(
            point = start + direction * distance,
            distance = distance,
            className = "edge"
        )
    }

    private data class Impact(
        val point: Vector,
        val distance: Float,
        val className: String?
    )

    private data class Vector(
        val x: Float,
        val y: Float
    ) {
        val length: Float
            get() = sqrt(x * x + y * y)

        fun normalized(): Vector? {
            if (length <= EPSILON) return null
            return Vector(x / length, y / length)
        }

        fun dot(other: Vector): Float = x * other.x + y * other.y

        operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
        operator fun minus(other: Vector): Vector = Vector(x - other.x, y - other.y)
        operator fun times(value: Float): Vector = Vector(x * value, y * value)
    }

    private val nonCollisionClasses = setOf(
        "cue_ball",
        "pocket",
        "ghost_ball",
        "spin",
        "aim_line",
        "cue_direction"
    )

    private const val EPSILON = 0.001f
}
