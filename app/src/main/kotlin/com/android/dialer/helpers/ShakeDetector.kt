package com.android.dialer.helpers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * Simple shake detector.
 *
 * Usage:
 *  val detector = ShakeDetector(onShake = { /* answer call */ })
 *  detector.start(context)
 *  detector.stop()
 *
 * Constructor params let you tune sensitivity and cooldown:
 *  - thresholdG: shake strength threshold (g). Lower = more sensitive.
 *  - windowSize: smoothing window multiplier for the low-pass filter.
 *  - cooldownMs: minimum time between onShake callbacks.
 */
class ShakeDetector(
    private val thresholdG: Float = 12f,    // shake threshold (approx in m/s^2 units)
    private val windowSize: Float = 0.9f,   // smoothing factor (0..1). Higher = smoother
    private val cooldownMs: Long = 1500L,   // debounce between callbacks
    private val onShake: () -> Unit
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelCurrent = SensorManager.GRAVITY_EARTH
    private var accelLast = SensorManager.GRAVITY_EARTH
    private var filteredAccel = 0f

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastShakeTimestamp = 0L
    @Volatile private var isRunning = false

    /**
     * Start listening for shakes. Safe to call multiple times.
     */
    fun start(context: Context) {
        if (isRunning) return
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) return
        val accelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensorManager = sm
        // SENSOR_DELAY_UI is a good default for UX-sensitive interactions
        sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        isRunning = true
    }

    /**
     * Stop listening.
     */
    fun stop() {
        if (!isRunning) return
        sensorManager?.unregisterListener(this)
        sensorManager = null
        isRunning = false
    }

    fun isStarted(): Boolean = isRunning

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    override fun onSensorChanged(event: SensorEvent) {
        // Defensive: require 3 values
        if (event.values.size < 3) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        accelLast = accelCurrent
        accelCurrent = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        // high-pass-ish delta
        val delta = accelCurrent - accelLast

        // low-pass filter to smooth short spikes
        filteredAccel = filteredAccel * windowSize + delta * (1 - windowSize)

        // Use absolute because direction doesn't matter
        val magnitude = kotlin.math.abs(filteredAccel)

        if (magnitude > thresholdG) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTimestamp > cooldownMs) {
                lastShakeTimestamp = now
                // Post to main thread just in case consumer touches UI
                handler.post { onShake.invoke() }
            }
        }
    }
}
