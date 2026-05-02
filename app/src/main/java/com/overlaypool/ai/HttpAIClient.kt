package com.overlaypool.ai

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.system.measureTimeMillis

class HttpAIClient(
    private val endpoint: String,
    private val apiKey: String,
    private val provider: String
) : AIClient {
    override fun detectFrame(bitmap: Bitmap): AIResponse {
        val encodedImage = encodeJpeg(bitmap)
        var parsedDetections = emptyList<com.overlaypool.model.DetectionResult>()
        var error: String? = null

        val latencyMs = measureTimeMillis {
            try {
                val roboflowModelProvider = provider.equals("roboflow_model", ignoreCase = true) ||
                    provider.equals("roboflow_detect", ignoreCase = true)
                val requestUrl = if (roboflowModelProvider) buildRoboflowModelUrl() else endpoint
                val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        if (roboflowModelProvider) "application/x-www-form-urlencoded" else "application/json"
                    )
                    setRequestProperty("Accept", "application/json")
                    if (apiKey.isNotEmpty() && !roboflowModelProvider) {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                        setRequestProperty("X-Api-Key", apiKey)
                    }
                }

                val body = if (roboflowModelProvider) {
                    encodedImage
                } else {
                    buildRequestBody(encodedImage, bitmap.width, bitmap.height).toString()
                }
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()

                if (responseCode !in 200..299) {
                    error = "HTTP $responseCode: ${responseText.take(180)}"
                    return@measureTimeMillis
                }

                parsedDetections = DetectionProcessor.parseDetections(responseText)
            } catch (throwable: Throwable) {
                error = throwable.message ?: throwable.javaClass.simpleName
            }
        }

        return AIResponse(
            detections = parsedDetections,
            connected = error == null,
            latencyMs = latencyMs,
            error = error
        )
    }

    private fun buildRequestBody(encodedImage: String, width: Int, height: Int): JSONObject {
        return if (provider.equals("roboflow_workflow", ignoreCase = true)) {
            JSONObject()
                .put("api_key", apiKey)
                .put(
                    "inputs",
                    JSONObject().put(
                        "image",
                        JSONObject()
                            .put("type", "base64")
                            .put("value", encodedImage)
                    )
                )
        } else {
            JSONObject()
                .put("image", encodedImage)
                .put("imageBase64", encodedImage)
                .put("imageWidth", width)
                .put("imageHeight", height)
                .put("apiKey", apiKey)
        }
    }

    private fun buildRoboflowModelUrl(): String {
        val separator = if (endpoint.contains("?")) "&" else "?"
        return endpoint +
            separator +
            "api_key=${urlEncode(apiKey)}&confidence=25&overlap=30&format=json"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }

    private fun encodeJpeg(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
