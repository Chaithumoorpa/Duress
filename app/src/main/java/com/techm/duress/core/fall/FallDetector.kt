package com.techm.duress.core.fall

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * FallDetector – detects probable falls using a two-stage heuristic:
 *   1) FREE-FALL: |a| < FREE_FALL_THRESHOLD within past [WINDOW_MS]
 *   2) IMPACT:    |a| > IMPACT_THRESHOLD shortly after free-fall
 *
 * Emits a single-fire event via [fallDetected] with debounce and optional auto-reset.
 * Note: For background use, run this from a Foreground Service with a persistent notification.
 */
class FallDetector(
    context: Context,
    private val sensorDelay: Int = SensorManager.SENSOR_DELAY_GAME, // consider FASTEST for maximum fidelity
    private val freeFallThreshold: Float = FREE_FALL_THRESHOLD_M_S2, // m/s^2
    private val impactThreshold: Float = IMPACT_THRESHOLD_M_S2,      // m/s^2
    private val windowMs: Long = WINDOW_MS,                          // time between free-fall and impact
    private val debounceMs: Long = DEBOUNCE_MS,                      // minimum time between detections
    private val autoResetMs: Long = AUTO_RESET_MS                    // auto clear fall flag after this delay
) : SensorEventListener {

    companion object {
        private const val TAG = "DU/FALL"

        // Earth gravity ~9.81 m/s^2. Good heuristic thresholds:
        // Free-fall < ~0.5g; Impact > ~2.5g. Tune per device.
        private const val FREE_FALL_THRESHOLD_M_S2 = 0.5f * SensorManager.GRAVITY_EARTH // ≈ 4.9
        private const val IMPACT_THRESHOLD_M_S2   = 2.5f * SensorManager.GRAVITY_EARTH // ≈ 24.5

        private const val WINDOW_MS   = 1000L  // must see impact within 1s of free-fall
        private const val DEBOUNCE_MS = 2000L  // avoid multi-triggers
        private const val AUTO_RESET_MS = 5000L // auto clear fall flag after 5s (tweak/disable with 0)
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var autoResetJob: Job? = null

    private val _fallDetected = MutableStateFlow(false)
    val fallDetected: StateFlow<Boolean> get() = _fallDetected

    // Internal state for 2-stage detection
    private var lastFreeFallAt: Long = 0L
    private var lastTriggerAt: Long = 0L

    /** Expose availability so UI can disable feature and/or inform user. */
    val isSensorAvailable: Boolean = accelerometer != null

    fun start() {
        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer not available — fall detection disabled")
            return
        }
        Log.d(TAG, "Registering accelerometer listener (delay=$sensorDelay)")
        sensorManager.registerListener(this, accelerometer, sensorDelay)
    }

    fun stop() {
        Log.d(TAG, "Unregistering accelerometer listener")
        sensorManager.unregisterListener(this)
        autoResetJob?.cancel()
        scope.coroutineContext.cancelChildren()
    }

    /** Manually clear the fall flag (e.g., user dismissed the alert). */
    fun reset() {
        Log.d(TAG, "Manual reset of fallDetected")
        _fallDetected.value = false
        autoResetJob?.cancel()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        // Magnitude of acceleration vector in m/s^2
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val a = sqrt(x * x + y * y + z * z)

        val now = event.timestamp / 1_000_000L // ns → ms (approx)
        // Stage 1: detect free-fall (low acceleration)
        if (a < freeFallThreshold) {
            // Record earliest free-fall window start
            if (now - lastFreeFallAt > 50L) { // avoid writing too often
                lastFreeFallAt = now
                Log.d(TAG, "FREE-FALL detected: a=${"%.2f".format(a)} m/s² @ $now ms")
            }
            return
        }

        // Stage 2: detect impact shortly after free-fall
        val inWindow = lastFreeFallAt > 0 && (now - lastFreeFallAt) <= windowMs
        val canTrigger = (now - lastTriggerAt) >= debounceMs
        if (inWindow && a > impactThreshold && canTrigger) {
            lastTriggerAt = now
            lastFreeFallAt = 0 // consume window
            Log.d(TAG, "IMPACT detected after free-fall: a=${"%.2f".format(a)} m/s² @ $now ms (TRIGGER)")

            _fallDetected.value = true

            // Auto reset (optional)
            if (autoResetMs > 0) {
                autoResetJob?.cancel()
                autoResetJob = scope.launch {
                    delay(autoResetMs)
                    Log.d(TAG, "Auto-reset fallDetected after ${autoResetMs}ms")
                    _fallDetected.value = false
                }
            }
            return
        }

        // If we saw free-fall but no qualifying impact within the window, expire the window
        if (lastFreeFallAt != 0L && (now - lastFreeFallAt) > windowMs) {
            Log.d(TAG, "Free-fall window expired without impact")
            lastFreeFallAt = 0L
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE ->
                Log.w(TAG, "Accelerometer accuracy UNRELIABLE — readings may be noisy")
            SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                Log.w(TAG, "Accelerometer accuracy LOW — consider recalibration")
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                Log.d(TAG, "Accelerometer accuracy MEDIUM")
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                Log.d(TAG, "Accelerometer accuracy HIGH")
            else ->
                Log.d(TAG, "Accelerometer accuracy=$accuracy")
        }
    }
}
