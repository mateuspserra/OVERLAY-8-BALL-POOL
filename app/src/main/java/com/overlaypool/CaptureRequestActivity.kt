package com.overlaypool

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.overlaypool.capture.ScreenCaptureService
import com.overlaypool.core.AppActions
import com.overlaypool.core.DetectionStateStore
import com.overlaypool.overlay.FloatingControlService
import com.overlaypool.overlay.OverlayService
import com.overlaypool.permissions.PermissionManager

class CaptureRequestActivity : Activity() {
    private lateinit var permissionManager: PermissionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)

        if (savedInstanceState == null) {
            requestCaptureFromGame()
        }
    }

    private fun requestCaptureFromGame() {
        if (captureRequested) return
        captureRequested = true

        DetectionStateStore.updateStatus {
            it.copy(
                systemState = "Solicitando captura no jogo",
                lastDetection = "Aceite a captura; o overlay preparado continua ativo.",
                lastError = null
            )
        }

        if (!permissionManager.hasNotificationPermission()) {
            permissionManager.requestNotificationPermission(REQUEST_NOTIFICATION_PERMISSION)
            return
        }

        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        startActivityForResult(
            permissionManager.createScreenCaptureIntent(),
            REQUEST_MEDIA_PROJECTION
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MEDIA_PROJECTION) return

        if (resultCode == RESULT_OK && data != null) {
            startCaptureAfterReturningToGame(resultCode, data)
        } else {
            startFloatingControlService()
            DetectionStateStore.updateStatus {
                it.copy(
                    captureActive = false,
                    systemState = "Permissao de captura negada",
                    lastError = "MediaProjection nao autorizado"
                )
            }
            finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            ) {
                requestScreenCapture()
            } else {
                startFloatingControlService()
                DetectionStateStore.updateStatus {
                    it.copy(
                        systemState = "Permissao de notificacao pendente",
                        lastError = "Permita notificacoes para iniciar captura"
                    )
                }
                finish()
            }
        }
    }

    private fun startCaptureAfterReturningToGame(resultCode: Int, data: Intent) {
        val captureIntent = Intent(this, ScreenCaptureService::class.java)
            .setAction(AppActions.ACTION_START_CAPTURE)
            .putExtra(AppActions.EXTRA_MEDIA_PROJECTION_RESULT_CODE, resultCode)
            .putExtra(AppActions.EXTRA_MEDIA_PROJECTION_DATA, data)

        DetectionStateStore.updateStatus {
            it.copy(
                systemState = "Iniciando captura no jogo",
                lastDetection = "A captura comeca sem fechar o jogo.",
                lastError = null
            )
        }

        mainHandler.postDelayed({
            startOverlayServices()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(captureIntent)
            } else {
                startService(captureIntent)
            }
            finish()
        }, CAPTURE_START_DELAY_MS)
    }

    private fun startOverlayServices() {
        startService(Intent(this, OverlayService::class.java))
        startFloatingControlService()
    }

    private fun startFloatingControlService() {
        startService(Intent(this, FloatingControlService::class.java))
    }

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1102
        private const val REQUEST_NOTIFICATION_PERMISSION = 1103
        private const val CAPTURE_START_DELAY_MS = 250L
    }
}
