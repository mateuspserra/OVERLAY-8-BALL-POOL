package com.overlaypool

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.overlaypool.capture.ScreenCaptureService
import com.overlaypool.core.AppActions
import com.overlaypool.core.DetectionStateStore
import com.overlaypool.core.RuntimeStatus
import com.overlaypool.model.DetectionResult
import com.overlaypool.model.TrajectoryResult
import com.overlaypool.overlay.FloatingControlService
import com.overlaypool.overlay.OverlayService
import com.overlaypool.permissions.PermissionManager

class MainActivity : Activity(), DetectionStateStore.Listener {
    private lateinit var permissionManager: PermissionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var overlayStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var aiStatus: TextView
    private lateinit var lastDetectionStatus: TextView
    private lateinit var systemStateStatus: TextView

    private var pendingStart = false
    private var notificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)
        setContentView(createContentView())
    }

    override fun onStart() {
        super.onStart()
        DetectionStateStore.addListener(this)
    }

    override fun onStop() {
        DetectionStateStore.removeListener(this)
        super.onStop()
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        val title = TextView(this).apply {
            text = "Overlay inteligente"
            textSize = 26f
            setTextColor(0xFF10201C.toInt())
        }
        root.addView(title)

        val description = TextView(this).apply {
            text = "Permita a sobreposicao e a captura de tela. O app fica transparente sobre outros apps, captura frames em intervalo controlado e desenha marcacoes com base na IA."
            textSize = 15f
            setTextColor(0xFF49534F.toInt())
            setPadding(0, dp(10), 0, dp(18))
        }
        root.addView(description)

        val startButton = Button(this).apply {
            text = "Iniciar leitura"
            setOnClickListener { beginPermissionFlow() }
        }
        root.addView(startButton, matchWidthParams())

        val stopButton = Button(this).apply {
            text = "Parar leitura"
            setOnClickListener { stopReading() }
        }
        root.addView(stopButton, matchWidthParams())

        systemStateStatus = statusLine("Estado", DetectionStateStore.status.systemState)
        overlayStatus = statusLine("Overlay", activeText(DetectionStateStore.status.overlayActive))
        captureStatus = statusLine("Captura", activeText(DetectionStateStore.status.captureActive))
        aiStatus = statusLine("IA", aiText(DetectionStateStore.status))
        lastDetectionStatus = statusLine("Ultima deteccao", DetectionStateStore.status.lastDetection)

        root.addView(systemStateStatus)
        root.addView(overlayStatus)
        root.addView(captureStatus)
        root.addView(aiStatus)
        root.addView(lastDetectionStatus)

        val warning = TextView(this).apply {
            text = "Aviso: alguns apps bloqueiam captura de tela e podem retornar imagem preta. A trajetoria depende de linha de mira, taco/guia visual ou bola alvo detectada; o app nao assume direcao sem referencia."
            textSize = 13f
            setTextColor(0xFF5C4730.toInt())
            setPadding(0, dp(20), 0, 0)
        }
        root.addView(warning)

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun statusLine(label: String, value: String): TextView {
        return TextView(this).apply {
            text = "$label: $value"
            textSize = 16f
            setTextColor(0xFF1D2D2A.toInt())
            setPadding(0, dp(12), 0, 0)
            gravity = Gravity.START
        }
    }

    private fun matchWidthParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        }
    }

    private fun beginPermissionFlow() {
        pendingStart = true
        DetectionStateStore.updateStatus {
            it.copy(systemState = "Aguardando permissoes", lastError = null)
        }

        if (!permissionManager.hasOverlayPermission()) {
            permissionManager.requestOverlayPermission(REQUEST_OVERLAY_PERMISSION)
            return
        }

        if (!permissionManager.hasNotificationPermission() && !notificationPermissionRequested) {
            notificationPermissionRequested = true
            permissionManager.requestNotificationPermission(REQUEST_NOTIFICATION_PERMISSION)
            return
        }

        startOverlayServices()
        requestScreenCapture()
    }

    private fun startOverlayServices() {
        startService(Intent(this, OverlayService::class.java))
        startService(Intent(this, FloatingControlService::class.java))
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            permissionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    private fun stopReading() {
        pendingStart = false
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        stopService(Intent(this, FloatingControlService::class.java))
        DetectionStateStore.clearDetections()
        DetectionStateStore.updateStatus {
            it.copy(
                overlayActive = false,
                captureActive = false,
                aiBusy = false,
                systemState = "Leitura pausada"
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_OVERLAY_PERMISSION -> {
                if (pendingStart) {
                    if (permissionManager.hasOverlayPermission()) {
                        beginPermissionFlow()
                    } else {
                        pendingStart = false
                        DetectionStateStore.updateStatus {
                            it.copy(
                                systemState = "Permissao de sobreposicao pendente",
                                lastError = "Ative a sobreposicao para iniciar"
                            )
                        }
                    }
                }
            }

            REQUEST_MEDIA_PROJECTION -> {
                pendingStart = false
                if (resultCode == RESULT_OK && data != null) {
                    val intent = Intent(this, ScreenCaptureService::class.java)
                        .setAction(AppActions.ACTION_START_CAPTURE)
                        .putExtra(AppActions.EXTRA_MEDIA_PROJECTION_RESULT_CODE, resultCode)
                        .putExtra(AppActions.EXTRA_MEDIA_PROJECTION_DATA, data)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                } else {
                    stopService(Intent(this, OverlayService::class.java))
                    stopService(Intent(this, FloatingControlService::class.java))
                    DetectionStateStore.updateStatus {
                        it.copy(
                            overlayActive = false,
                            captureActive = false,
                            systemState = "Permissao de captura negada",
                            lastError = "MediaProjection nao autorizado"
                        )
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION && pendingStart) {
            beginPermissionFlow()
        }
    }

    override fun onDetectionsUpdated(
        detections: List<DetectionResult>,
        trajectory: TrajectoryResult?
    ) = Unit

    override fun onStatusUpdated(status: RuntimeStatus) {
        mainHandler.post {
            systemStateStatus.text = "Estado: ${status.systemState}"
            overlayStatus.text = "Overlay: ${activeText(status.overlayActive)}"
            captureStatus.text = "Captura: ${activeText(status.captureActive)}"
            aiStatus.text = "IA: ${aiText(status)}"
            lastDetectionStatus.text = "Ultima deteccao: ${status.lastDetection}"
        }
    }

    private fun activeText(active: Boolean): String = if (active) "ativo" else "inativo"

    private fun aiText(status: RuntimeStatus): String {
        return when {
            status.aiBusy -> "processando"
            status.aiConnected -> "conectada"
            else -> "desconectada"
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1001
        private const val REQUEST_MEDIA_PROJECTION = 1002
        private const val REQUEST_NOTIFICATION_PERMISSION = 1003
    }
}
