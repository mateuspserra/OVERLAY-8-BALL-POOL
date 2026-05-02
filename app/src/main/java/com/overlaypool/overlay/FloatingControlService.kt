package com.overlaypool.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import com.overlaypool.R
import com.overlaypool.capture.ScreenCaptureService
import com.overlaypool.core.AppActions
import com.overlaypool.core.DetectionStateStore
import com.overlaypool.core.RuntimeStatus
import com.overlaypool.model.DetectionResult
import com.overlaypool.model.TrajectoryResult

class FloatingControlService : Service(), DetectionStateStore.Listener {
    private var windowManager: WindowManager? = null
    private var controlView: LinearLayout? = null
    private var visibilityButton: ImageButton? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        DetectionStateStore.addListener(this)
        createControls()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == AppActions.ACTION_STOP_READING) {
            stopReading()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DetectionStateStore.removeListener(this)
        controlView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        controlView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createControls() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        if (controlView != null) return

        val density = resources.displayMetrics.density
        val buttonSize = (48 * density).toInt()
        val padding = (4 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, padding)
        }

        visibilityButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_visibility)
            contentDescription = "Mostrar ou esconder marcacoes"
            setBackgroundResource(R.drawable.floating_button_bg)
            setOnClickListener { toggleMarkings() }
        }
        root.addView(visibilityButton, LinearLayout.LayoutParams(buttonSize, buttonSize))

        val stopButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_stop)
            contentDescription = "Parar leitura"
            setBackgroundResource(R.drawable.floating_button_bg)
            setOnClickListener { stopReading() }
        }
        root.addView(stopButton, LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
            leftMargin = (8 * density).toInt()
        })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (120 * density).toInt()
        }

        runCatching {
            windowManager?.addView(root, params)
            controlView = root
        }
    }

    private fun toggleMarkings() {
        DetectionStateStore.updateStatus {
            it.copy(markingsVisible = !it.markingsVisible)
        }
    }

    private fun stopReading() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        stopService(Intent(this, OverlayService::class.java))
        DetectionStateStore.clearDetections()
        DetectionStateStore.updateStatus {
            it.copy(
                overlayActive = false,
                captureActive = false,
                aiBusy = false,
                systemState = "Leitura pausada"
            )
        }
        stopSelf()
    }

    override fun onDetectionsUpdated(
        detections: List<DetectionResult>,
        trajectory: TrajectoryResult?
    ) = Unit

    override fun onStatusUpdated(status: RuntimeStatus) {
        visibilityButton?.post {
            visibilityButton?.setImageResource(
                if (status.markingsVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            )
        }
    }
}
