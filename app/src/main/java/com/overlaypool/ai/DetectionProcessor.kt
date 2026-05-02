package com.overlaypool.ai

import com.overlaypool.model.DetectionResult
import com.overlaypool.model.TrajectoryResult
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

object DetectionProcessor {
    fun parseDetections(jsonText: String): List<DetectionResult> {
        if (jsonText.isBlank()) return emptyList()

        val rootValue = runCatching { JSONObject(jsonText) }
            .getOrElse { JSONArray(jsonText) }
        val arrays = mutableListOf<JSONArray>()
        if (rootValue is JSONArray) {
            arrays.add(rootValue)
        }
        collectDetectionArrays(rootValue, arrays)

        val detections = mutableListOf<DetectionResult>()
        arrays.forEach { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseDetectionObject(item)?.let(detections::add)
            }
        }

        if (rootValue is JSONObject) {
            parseDetectionObject(rootValue)?.let(detections::add)
        }

        return detections.distinctBy {
            "${it.className}:${it.confidence}:${it.x}:${it.y}:${it.width}:${it.height}"
        }
    }

    fun prepareDetections(
        rawDetections: List<DetectionResult>,
        requestWidth: Int,
        requestHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        minConfidence: Float
    ): List<DetectionResult> {
        if (requestWidth <= 0 || requestHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return emptyList()
        }

        val scaleX = screenWidth.toFloat() / requestWidth.toFloat()
        val scaleY = screenHeight.toFloat() / requestHeight.toFloat()

        return rawDetections
            .asSequence()
            .filter { it.confidence >= minConfidence }
            .map { detection ->
                val requestCoordinates = if (looksNormalized(detection)) {
                    detection.normalizedTo(requestWidth, requestHeight)
                } else {
                    detection
                }
                requestCoordinates.scaled(scaleX, scaleY)
            }
            .toList()
    }

    fun summarizeDetections(
        detections: List<DetectionResult>,
        trajectory: TrajectoryResult?,
        latencyMs: Long?
    ): String {
        if (detections.isEmpty()) return "Nenhum objeto"

        val cueBall = detections.firstOrNull { it.className == "cue_ball" }
        val cueText = if (cueBall != null) {
            "cue_ball em ${cueBall.centerX.toInt()},${cueBall.centerY.toInt()}"
        } else {
            "cue_ball ausente"
        }
        val trajectoryText = if (trajectory != null) {
            "trajetoria ${trajectory.angleDegrees.toInt()} graus"
        } else {
            "sem direcao"
        }
        val latencyText = latencyMs?.let { ", IA ${it}ms" }.orEmpty()
        return "${detections.size} objetos, $cueText, $trajectoryText$latencyText"
    }

    private fun collectDetectionArrays(value: Any?, arrays: MutableList<JSONArray>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    if (child is JSONArray && (key == "detections" || key == "predictions" || key == "objects")) {
                        arrays.add(child)
                    }
                    collectDetectionArrays(child, arrays)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectDetectionArrays(value.opt(index), arrays)
                }
            }
        }
    }

    private fun parseDetectionObject(json: JSONObject): DetectionResult? {
        val className = json.optString("className")
            .ifEmpty { json.optString("class") }
            .ifEmpty { json.optString("label") }
            .ifEmpty { json.optString("name") }
            .let(::normalizeClassName)
        if (className.isBlank()) return null

        val confidence = optFloat(json, "confidence")
            ?: optFloat(json, "score")
            ?: optFloat(json, "probability")
            ?: 1f

        val box = json.optJSONObject("box") ?: json.optJSONObject("bbox")
        if (box != null) {
            return parseBoxObject(className, confidence, box)
        }

        val boxArray = json.optJSONArray("bbox") ?: json.optJSONArray("box")
        if (boxArray != null && boxArray.length() >= 4) {
            val x = boxArray.optDouble(0).toFloat()
            val y = boxArray.optDouble(1).toFloat()
            val width = boxArray.optDouble(2).toFloat()
            val height = boxArray.optDouble(3).toFloat()
            return DetectionResult(className, confidence, x, y, width, height)
        }

        val width = optFloat(json, "width") ?: optFloat(json, "w") ?: return null
        val height = optFloat(json, "height") ?: optFloat(json, "h") ?: return null
        val rawX = optFloat(json, "x") ?: optFloat(json, "left") ?: return null
        val rawY = optFloat(json, "y") ?: optFloat(json, "top") ?: return null

        val centerX = optFloat(json, "centerX") ?: optFloat(json, "cx")
        val centerY = optFloat(json, "centerY") ?: optFloat(json, "cy")
        val roboflowStyle = json.has("class") && !json.has("className") && centerX == null && centerY == null

        return if (centerX != null && centerY != null) {
            DetectionResult(
                className = className,
                confidence = confidence,
                x = centerX - width / 2f,
                y = centerY - height / 2f,
                width = width,
                height = height,
                centerX = centerX,
                centerY = centerY
            )
        } else if (roboflowStyle) {
            DetectionResult(
                className = className,
                confidence = confidence,
                x = rawX - width / 2f,
                y = rawY - height / 2f,
                width = width,
                height = height,
                centerX = rawX,
                centerY = rawY
            )
        } else {
            DetectionResult(className, confidence, rawX, rawY, width, height)
        }
    }

    private fun parseBoxObject(
        className: String,
        confidence: Float,
        box: JSONObject
    ): DetectionResult? {
        val x1 = optFloat(box, "x1") ?: optFloat(box, "left")
        val y1 = optFloat(box, "y1") ?: optFloat(box, "top")
        val x2 = optFloat(box, "x2") ?: optFloat(box, "right")
        val y2 = optFloat(box, "y2") ?: optFloat(box, "bottom")
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return DetectionResult(
                className = className,
                confidence = confidence,
                x = x1,
                y = y1,
                width = max(0f, x2 - x1),
                height = max(0f, y2 - y1)
            )
        }

        val x = optFloat(box, "x") ?: return null
        val y = optFloat(box, "y") ?: return null
        val width = optFloat(box, "width") ?: optFloat(box, "w") ?: return null
        val height = optFloat(box, "height") ?: optFloat(box, "h") ?: return null
        return DetectionResult(className, confidence, x, y, width, height)
    }

    private fun looksNormalized(detection: DetectionResult): Boolean {
        return listOf(
            detection.x,
            detection.y,
            detection.width,
            detection.height,
            detection.centerX,
            detection.centerY
        ).all { it in -0.05f..1.5f }
    }

    private fun optFloat(json: JSONObject, key: String): Float? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.optDouble(key, Double.NaN)
            .takeIf { !it.isNaN() }
            ?.toFloat()
    }

    private fun normalizeClassName(value: String): String {
        return value.trim()
            .lowercase()
            .replace(" ", "_")
            .replace("-", "_")
    }
}
