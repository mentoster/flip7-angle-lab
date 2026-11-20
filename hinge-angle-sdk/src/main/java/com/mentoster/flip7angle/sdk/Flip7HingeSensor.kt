package com.mentoster.flip7angle.sdk

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.Locale
import kotlin.math.sqrt

/** Availability of the public sensors needed by the experimental estimator. */
data class HingeSensorAvailability(
    val magneticUncalibrated: Boolean,
    val gravity: Boolean,
    val gyroscope: Boolean,
    val coarseHinge: Boolean,
) {
    val ready: Boolean get() =
        magneticUncalibrated && gravity && gyroscope && coarseHinge
}

/**
 * Drop-in sensor observer for ordinary Android apps on Galaxy Z Flip7.
 *
 * Call [start] from Activity/Fragment onStart and [stop] from onStop.
 * Listeners run on the Android MAIN thread (up to ~30 fps), while the
 * model runs on a dedicated sensor thread. No READ_LOGS, ADB, root or
 * network permission is requested or used.
 *
 * Never use the result as a safety-critical or certified hinge sensor:
 * rotating the phone can make the raw model incorrect, and simultaneous
 * slow folding+turning may keep the last angle held until there is
 * independent hinge evidence.
 */
class Flip7HingeSensor(context: Context) : SensorEventListener, AutoCloseable {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val magneticSensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
    private val gravitySensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gyroscopeSensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val hingeSensor = manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val engine = HingeAngleEngine(
        Flip7HingePredictor.from(context),
        testedOnDevice = Build.MODEL.uppercase(Locale.US).startsWith("SM-F766"),
    )

    val availability = HingeSensorAvailability(
        magneticUncalibrated = magneticSensor != null,
        gravity = gravitySensor != null,
        gyroscope = gyroscopeSensor != null,
        coarseHinge = hingeSensor != null,
    )

    /** True only for the SM-F766* device family used to train this model. */
    val testedOnDevice: Boolean = Build.MODEL.uppercase(Locale.US).startsWith("SM-F766")

    @Volatile private var active = false
    @Volatile private var lastReading: HingeAngleReading? = null
    private var listener: HingeAngleListener? = null
    private var worker: HandlerThread? = null
    private var gravity: FloatArray? = null
    private var gravityTimestampNanos = 0L
    private var gyroscope: FloatArray? = null
    private var gyroscopeTimestampNanos = 0L
    private var coarseHingeDegrees: Float? = null
    private var lastCallbackNanos = 0L

    val isRunning: Boolean get() = active
    val latest: HingeAngleReading? get() = lastReading

    /**
     * Subscribes to public sensors, using a dedicated thread.
     *
     * @return false if a required sensor is unavailable or registration
     *         fails. This is common on non-folding Android devices.
     */
    @Synchronized
    fun start(onAngle: HingeAngleListener): Boolean {
        if (active) {
            listener = onAngle
            return true
        }
        if (!availability.ready) return false
        engine.reset()
        gravity = null
        gyroscope = null
        gravityTimestampNanos = 0L
        gyroscopeTimestampNanos = 0L
        coarseHingeDegrees = null
        lastCallbackNanos = 0L
        lastPublishedStatus = null
        lastReading = null
        listener = onAngle
        val thread = HandlerThread("Flip7HingeSDK-sensors").apply { start() }
        worker = thread
        val handler = Handler(thread.looper)
        active = true
        val sensors = listOfNotNull(
            magneticSensor, gravitySensor, gyroscopeSensor, hingeSensor,
        )
        // SENSOR_DELAY_FASTEST (0 us) requires the separate Android
        // HIGH_SAMPLING_RATE_SENSORS permission. We deliberately request
        // no permissions at all: ~50 Hz GAME is sufficient for the
        // standalone hinge estimator and available to ordinary apps.
        val allRegistered = try {
            sensors.all {
                manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!allRegistered) {
            stop()
            return false
        }
        return true
    }

    @Synchronized
    fun stop() {
        active = false
        manager.unregisterListener(this)
        worker?.quitSafely()
        worker = null
        listener = null
        lastReading = null
    }

    override fun close() = stop()

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!active) return
        when (event.sensor) {
            gravitySensor -> if (event.values.size >= 3) {
                gravity = event.values.copyOf(3)
                gravityTimestampNanos = event.timestamp
            }
            gyroscopeSensor -> if (event.values.size >= 3) {
                gyroscope = event.values.copyOf(3)
                gyroscopeTimestampNanos = event.timestamp
            }
            hingeSensor -> {
                coarseHingeDegrees = event.values.firstOrNull()?.takeIf(Float::isFinite)
            }
            magneticSensor -> {
                if (event.values.size < 3) return
                val g = gravity
                val gravityIsFresh =
                    event.timestamp - gravityTimestampNanos in -20_000_000L..100_000_000L
                val gyro = gyroscope?.takeIf {
                    event.timestamp - gyroscopeTimestampNanos in -30_000_000L..150_000_000L
                }
                val speed = gyro?.let {
                    sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
                }
                // Do not seed the predictor with incomplete sensor data.
                val reading = if (g != null && gravityIsFresh) {
                    engine.frame(
                        event.timestamp,
                        event.values[0], event.values[1], event.values[2],
                        g[0], g[1], g[2],
                        speed, coarseHingeDegrees,
                    )
                } else HingeAngleReading(
                    null, null, HingeAngleStatus.WAITING_FOR_SENSORS,
                    coarseHingeDegrees, event.timestamp, "waiting-for-gravity",
                    testedOnDevice,
                )
                lastReading = reading
                if (lastCallbackNanos == 0L ||
                    event.timestamp - lastCallbackNanos >= CALLBACK_INTERVAL_NS ||
                    reading.status != lastPublishedStatus
                ) {
                    lastPublishedStatus = reading.status
                    lastCallbackNanos = event.timestamp
                    callbackHandler.post {
                        if (active) listener?.onAngle(reading)
                    }
                }
            }
        }
    }

    private var lastPublishedStatus: HingeAngleStatus? = null

    private companion object {
        const val CALLBACK_INTERVAL_NS = 33_000_000L
    }
}
