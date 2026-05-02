package com.overlaypool.model

data class ScreenPoint(
    val x: Float,
    val y: Float
)

data class TrajectoryLine(
    val start: ScreenPoint,
    val end: ScreenPoint
)

data class TrajectoryResult(
    val primaryLine: TrajectoryLine,
    val impactPoint: ScreenPoint,
    val impactClassName: String?,
    val angleDegrees: Float,
    val secondaryLine: TrajectoryLine? = null
)
