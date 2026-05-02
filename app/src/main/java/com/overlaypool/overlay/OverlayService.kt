package com.overlaypool.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import com.overlaypool.core.AppActions
import com.overlaypool.core.DetectionStateStore

class OverlayService : Service() {
    private var windowManager: WindowManager? = null
    private var overlayView: DetectionOverlayView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AppActions.ACTION_STOP_READING -> stopSelf()
            AppActions.ACTION_TOGGLE_MARKINGS -> toggleMarkings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        overlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        overlayView = null
        DetectionStateStore.updateStatus {
            it.copy(overlayActive = false, systemState = "Overlay encerrado")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            DetectionStateStore.updateStatus {
                it.copy(
                    overlayActive = false,
                    systemState = "Permissao de sobreposicao pendente",
                    lastError = "SYSTEM_ALERT_WINDOW nao autorizado"
                )
            }
            stopSelf()
            return
        }

        if (overlayView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val view = DetectionOverlayView(this)
        runCatching {
            windowManager?.addView(view, params)
            overlayView = view
            DetectionStateStore.updateStatus {
                it.copy(overlayActive = true, systemState = "Overlay ativo", lastError = null)
            }
        }.onFailure { throwable ->
            DetectionStateStore.updateStatus {
                it.copy(
                    overlayActive = false,
                    systemState = "Erro ao criar overlay",
                    lastError = throwable.message
                )
            }
            stopSelf()
        }
    }

    private fun toggleMarkings() {
        DetectionStateStore.updateStatus {
            it.copy(markingsVisible = !it.markingsVisible)
        }
    }
}
