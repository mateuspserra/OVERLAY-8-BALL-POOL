package com.overlaypool.core

import com.overlaypool.model.DetectionResult
import com.overlaypool.model.TrajectoryResult
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicReference

object DetectionStateStore {
    interface Listener {
        fun onDetectionsUpdated(detections: List<DetectionResult>, trajectory: TrajectoryResult?)
        fun onStatusUpdated(status: RuntimeStatus)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private val statusRef = AtomicReference(RuntimeStatus())

    @Volatile
    var detections: List<DetectionResult> = emptyList()
        private set

    @Volatile
    var trajectory: TrajectoryResult? = null
        private set

    val status: RuntimeStatus
        get() = statusRef.get()

    fun addListener(listener: Listener) {
        listeners.add(listener)
        listener.onStatusUpdated(status)
        listener.onDetectionsUpdated(detections, trajectory)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun updateDetections(newDetections: List<DetectionResult>, newTrajectory: TrajectoryResult?) {
        detections = newDetections
        trajectory = newTrajectory
        listeners.forEach { it.onDetectionsUpdated(newDetections, newTrajectory) }
    }

    fun clearDetections() {
        updateDetections(emptyList(), null)
        updateStatus {
            it.copy(lastDetection = "Nenhuma deteccao", lastApiLatencyMs = null)
        }
    }

    fun updateStatus(transform: (RuntimeStatus) -> RuntimeStatus) {
        val updated = statusRef.updateAndGet { current -> transform(current) }
        listeners.forEach { it.onStatusUpdated(updated) }
    }
}
