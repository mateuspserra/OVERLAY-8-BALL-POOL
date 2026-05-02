package com.overlaypool.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import com.overlaypool.BuildConfig
import com.overlaypool.MainActivity
import com.overlaypool.R
import com.overlaypool.ai.AIClient
import com.overlaypool.ai.AIClientFactory
import com.overlaypool.ai.DetectionProcessor
import com.overlaypool.core.AppActions
import com.overlaypool.core.DetectionStateStore
import com.overlaypool.overlay.ManualGuideService
import com.overlaypool.trajectory.TrajectoryEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ScreenCaptureService : Service() {
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var captureRunnable: Runnable? = null
    private var aiClient: AIClient? = null

    private val processing = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var protectedFrameCount = 0
    private var keepBlockedStatusOnStop = false
    private var captureStartedAtMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        aiClient = AIClientFactory.create()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: return START_NOT_STICKY

        if (command.action == AppActions.ACTION_STOP_READING) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        if (command.action != AppActions.ACTION_START_CAPTURE) {
            return START_NOT_STICKY
        }

        startForegroundForCapture()

        val resultCode = command.getIntExtra(
            AppActions.EXTRA_MEDIA_PROJECTION_RESULT_CODE,
            0
        )
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            command.getParcelableExtra(
                AppActions.EXTRA_MEDIA_PROJECTION_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            command.getParcelableExtra(AppActions.EXTRA_MEDIA_PROJECTION_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            DetectionStateStore.updateStatus {
                it.copy(
                    captureActive = false,
                    systemState = "Dados de captura ausentes",
                    lastError = "MediaProjection result data vazio"
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching {
            startCapture(resultCode, resultData)
        }.onFailure { throwable ->
            DetectionStateStore.updateStatus {
                it.copy(
                    captureActive = false,
                    systemState = "Erro ao iniciar captura",
                    lastError = throwable.message ?: throwable.javaClass.simpleName
                )
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCapture(resultCode: Int, resultData: Intent) {
        if (mediaProjection != null) return
        stopping.set(false)
        protectedFrameCount = 0
        keepBlockedStatusOnStop = false
        captureStartedAtMs = SystemClock.elapsedRealtime()

        readScreenMetrics()

        captureThread = HandlerThread("ScreenCaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = manager.getMediaProjection(resultCode, resultData).apply {
            registerCallback(projectionCallback, captureHandler)
        }

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "OverlayInteligenteCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler
        )

        if (virtualDisplay == null) {
            DetectionStateStore.updateStatus {
                it.copy(
                    captureActive = false,
                    systemState = "Erro ao criar captura",
                    lastError = "VirtualDisplay nao foi criado"
                )
            }
            stopSelf()
            return
        }

        DetectionStateStore.updateStatus {
            it.copy(
                captureActive = true,
                aiConnected = isAiConfigured(),
                systemState = "Captura ativa",
                lastError = null
            )
        }

        scheduleCapture()
    }

    private fun scheduleCapture() {
        val handler = captureHandler ?: return
        val intervalMs = BuildConfig.CAPTURE_INTERVAL_MS.coerceAtLeast(100L)
        captureRunnable = object : Runnable {
            override fun run() {
                val startedAt = SystemClock.elapsedRealtime()
                captureAndProcessFrame()
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val nextDelayMs = (intervalMs - elapsedMs).coerceAtLeast(0L)
                if (!stopping.get()) {
                    handler.postDelayed(this, nextDelayMs)
                }
            }
        }
        handler.post(captureRunnable!!)
    }

    private fun captureAndProcessFrame() {
        if (!processing.compareAndSet(false, true)) return

        var workingBitmap: Bitmap? = null
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                processing.set(false)
                return
            }

            val fullBitmap = image.useToBitmap()
            workingBitmap = downscaleForUpload(fullBitmap)
            if (workingBitmap !== fullBitmap) fullBitmap.recycle()

            if (workingBitmap.isLikelyProtectedFrame()) {
                if (isInInitialCaptureGracePeriod()) {
                    DetectionStateStore.updateDetections(emptyList(), null)
                    DetectionStateStore.updateStatus {
                        it.copy(
                            captureActive = true,
                            aiBusy = false,
                            aiConnected = isAiConfigured(),
                            systemState = "Aguardando tela do jogo",
                            lastDetection = "Tela preta inicial ignorada enquanto o jogo volta ao primeiro plano.",
                            lastApiLatencyMs = null,
                            lastError = null
                        )
                    }
                    return
                }

                protectedFrameCount += 1
                val shouldPauseCapture = protectedFrameCount >= PROTECTED_FRAME_STOP_THRESHOLD
                DetectionStateStore.updateDetections(emptyList(), null)
                DetectionStateStore.updateStatus {
                    it.copy(
                        captureActive = !shouldPauseCapture,
                        aiBusy = false,
                        aiConnected = isAiConfigured(),
                        systemState = if (shouldPauseCapture) {
                            "Captura pausada: tela protegida"
                        } else {
                            "Captura bloqueada pelo app"
                        },
                        lastDetection = if (shouldPauseCapture) {
                            "A tela veio preta por varios frames. Abrindo guia manual; o modo automatico nao consegue detectar sem pixels reais."
                        } else {
                            "Frame preto/protegido. O app exibido provavelmente bloqueia MediaProjection."
                        },
                        lastApiLatencyMs = null,
                        lastError = "Captura de tela bloqueada ou protegida"
                    )
                }
                if (shouldPauseCapture) {
                    keepBlockedStatusOnStop = true
                    openManualGuideFallback()
                    stopCapture()
                    stopSelf()
                }
                return
            }

            protectedFrameCount = 0

            DetectionStateStore.updateStatus {
                it.copy(aiBusy = true, systemState = "Enviando frame para IA", lastError = null)
            }

            val response = aiClient?.detectFrame(workingBitmap) ?: return
            val detections = DetectionProcessor.prepareDetections(
                rawDetections = response.detections,
                requestWidth = workingBitmap.width,
                requestHeight = workingBitmap.height,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                minConfidence = BuildConfig.MIN_DETECTION_CONFIDENCE
            )
            val trajectory = TrajectoryEngine.calculate(detections, screenWidth, screenHeight)
            DetectionStateStore.updateDetections(detections, trajectory)

            val endpointConfigured = isAiConfigured()
            val nextState = when {
                !endpointConfigured -> "IA desconectada"
                response.error != null -> "Erro na API"
                detections.isNotEmpty() && trajectory != null -> "Trajetoria calculada"
                detections.isNotEmpty() -> "Deteccao recebida"
                else -> "Aguardando deteccoes"
            }

            DetectionStateStore.updateStatus {
                it.copy(
                    aiConnected = response.connected,
                    aiBusy = false,
                    systemState = nextState,
                    lastApiLatencyMs = response.latencyMs,
                    lastDetection = if (!endpointConfigured && response.error != null) {
                        response.error
                    } else {
                        DetectionProcessor.summarizeDetections(
                            detections,
                            trajectory,
                            response.latencyMs
                        )
                    },
                    lastError = response.error
                )
            }
        } catch (throwable: Throwable) {
            DetectionStateStore.updateStatus {
                it.copy(
                    aiBusy = false,
                    systemState = "Erro na captura",
                    lastError = throwable.message ?: throwable.javaClass.simpleName
                )
            }
        } finally {
            workingBitmap?.recycle()
            processing.set(false)
        }
    }

    private fun stopCapture() {
        if (!stopping.compareAndSet(false, true)) return

        captureRunnable?.let { captureHandler?.removeCallbacks(it) }
        captureRunnable = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        val projection = mediaProjection
        mediaProjection = null
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }

        aiClient?.close()
        aiClient = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        if (!keepBlockedStatusOnStop) {
            DetectionStateStore.updateStatus {
                it.copy(
                    captureActive = false,
                    aiBusy = false,
                    systemState = "Servico encerrado"
                )
            }
        }
    }

    private fun startForegroundForCapture() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun downscaleForUpload(bitmap: Bitmap): Bitmap {
        val maxSize = BuildConfig.MAX_UPLOAD_IMAGE_SIZE.coerceAtLeast(320)
        val longestSide = maxOf(bitmap.width, bitmap.height)
        if (longestSide <= maxSize) return bitmap

        val scale = maxSize.toFloat() / longestSide.toFloat()
        val targetWidth = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun isAiConfigured(): Boolean {
        val provider = BuildConfig.AI_PROVIDER.trim().lowercase()
        return BuildConfig.AI_ENDPOINT.isNotBlank() ||
            provider == "local_heuristic" ||
            provider == "local" ||
            provider == "offline"
    }

    private fun openManualGuideFallback() {
        runCatching {
            startService(Intent(this, ManualGuideService::class.java))
        }
    }

    private fun isInInitialCaptureGracePeriod(): Boolean {
        return SystemClock.elapsedRealtime() - captureStartedAtMs < INITIAL_CAPTURE_GRACE_PERIOD_MS
    }

    private fun Bitmap.isLikelyProtectedFrame(): Boolean {
        val stepX = (width / PROTECTED_FRAME_SAMPLE_COLUMNS).coerceAtLeast(1)
        val stepY = (height / PROTECTED_FRAME_SAMPLE_ROWS).coerceAtLeast(1)
        var sampled = 0
        var veryDark = 0
        var transparent = 0
        var minLuma = 255
        var maxLuma = 0

        var y = stepY / 2
        while (y < height) {
            var x = stepX / 2
            while (x < width) {
                val pixel = getPixel(x, y)
                val alpha = pixel ushr 24 and 0xff
                val red = pixel ushr 16 and 0xff
                val green = pixel ushr 8 and 0xff
                val blue = pixel and 0xff
                val luma = (red * 299 + green * 587 + blue * 114) / 1000

                sampled++
                if (alpha < 8) transparent++
                if (luma <= PROTECTED_FRAME_DARK_LUMA) veryDark++
                if (luma < minLuma) minLuma = luma
                if (luma > maxLuma) maxLuma = luma

                x += stepX
            }
            y += stepY
        }

        if (sampled == 0) return false

        val darkRatio = veryDark.toFloat() / sampled.toFloat()
        val transparentRatio = transparent.toFloat() / sampled.toFloat()
        val lumaRange = maxLuma - minLuma

        return transparentRatio >= PROTECTED_FRAME_BLOCKED_RATIO ||
            darkRatio >= PROTECTED_FRAME_BLOCKED_RATIO ||
            (darkRatio >= PROTECTED_FRAME_MOSTLY_DARK_RATIO && lumaRange <= PROTECTED_FRAME_LOW_VARIANCE)
    }

    private fun readScreenMetrics() {
        val metrics = resources.displayMetrics
        screenDensity = metrics.densityDpi
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            screenWidth = metrics.widthPixels
            @Suppress("DEPRECATION")
            screenHeight = metrics.heightPixels
        }
    }

    private fun Image.useToBitmap(): Bitmap {
        return try {
            val plane = planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val paddedBitmap = Bitmap.createBitmap(
                paddedWidth,
                height,
                Bitmap.Config.ARGB_8888
            )
            buffer.rewind()
            paddedBitmap.copyPixelsFromBuffer(buffer)

            if (paddedWidth == width) {
                paddedBitmap
            } else {
                Bitmap.createBitmap(paddedBitmap, 0, 0, width, height).also {
                    paddedBitmap.recycle()
                }
            }
        } finally {
            close()
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 4108
        private const val PROTECTED_FRAME_SAMPLE_COLUMNS = 24
        private const val PROTECTED_FRAME_SAMPLE_ROWS = 24
        private const val PROTECTED_FRAME_DARK_LUMA = 8
        private const val PROTECTED_FRAME_LOW_VARIANCE = 12
        private const val PROTECTED_FRAME_BLOCKED_RATIO = 0.98f
        private const val PROTECTED_FRAME_MOSTLY_DARK_RATIO = 0.92f
        private const val PROTECTED_FRAME_STOP_THRESHOLD = 10
        private const val INITIAL_CAPTURE_GRACE_PERIOD_MS = 3500L
    }
}
