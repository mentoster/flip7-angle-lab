package com.mentoster.flip7anglelab.fold

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.locks.LockSupport
import kotlin.math.acos
import kotlin.math.sqrt

data class SensorInventoryItem(
    val id: Int,
    val handle: Int,
    val name: String,
    val vendor: String,
    val type: Int,
    val stringType: String,
    val requiredPermission: String,
    val registrationResult: String,
    val version: Int,
    val maxRange: Float,
    val resolution: Float,
    val powerMa: Float,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val reportingMode: Int,
    val wakeUp: Boolean,
)

data class DirectSensorLabState(
    val inventory: List<SensorInventoryItem> = emptyList(),
    val candidateCount: Int = 0,
    val mainAccelerometer: String? = null,
    val subAccelerometer: String? = null,
    val mainGyroscope: String? = null,
    val subGyroscope: String? = null,
    val mainAccelValues: List<Float> = emptyList(),
    val subAccelValues: List<Float> = emptyList(),
    val mainGyroValues: List<Float> = emptyList(),
    val subGyroValues: List<Float> = emptyList(),
    val gravityFoldEstimate: Float? = null,
    val magneticFoldEstimate: Float? = null,
    val estimateHeld: Boolean = false,
    val orientationHeld: Boolean = false,
    val usingCoarseFallback: Boolean = false,
    val usingAdaptiveCalibration: Boolean = false,
    val adaptiveSampleCount: Int = 0,
    val magneticRawPrediction: Float? = null,
    /** Latest ML inference before rotation gating or display stabilization. */
    val livePrediction: Float? = null,
    val livePredictionSource: String = "waiting",
    val orientationUncertain: Boolean = false,
    val magnetometerLive: LiveSensorRates.Reading = LiveSensorRates.Reading(0, 0f, 0),
    val gravityLive: LiveSensorRates.Reading = LiveSensorRates.Reading(0, 0f, 0),
    val gyroscopeLive: LiveSensorRates.Reading = LiveSensorRates.Reading(0, 0f, 0),
    val hingeLive: LiveSensorRates.Reading = LiveSensorRates.Reading(0, 0f, 0),
    val magneticPredictionSource: String = "unavailable",
    val adaptiveMatchMode: String = "none",
    val publicHingeDegrees: Float? = null,
    val samsungPrivateSummary: String = "",
    val recording: Boolean = false,
    val eventCount: Long = 0,
    val exactSampleCount: Long = 0,
    val recordingTag: String = "",
    val status: String = "Direct sensor scan not initialized",
    val lastLogName: String? = null,
    val lastLogUri: String? = null,
)

/**
 * Pure Android SensorManager lab for Galaxy Z Flip. It intentionally does not use ADB.
 * The goal is to prove whether Samsung exposes both main/sub IMUs to a normal APK and
 * to capture enough raw data to build a device-specific continuous hinge estimator.
 */
object DirectSensorLab : SensorEventListener {
    private val _state = MutableStateFlow(DirectSensorLabState())
    val state = _state.asStateFlow()
    private val _estimatedAngle = MutableStateFlow<Float?>(null)
    val estimatedAngle = _estimatedAngle.asStateFlow()

    private lateinit var app: Context
    private lateinit var sensorManager: SensorManager
    private lateinit var powerManager: PowerManager
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private var initialized = false
    private var allSensors: List<Sensor> = emptyList()
    private var candidateSensors: List<Sensor> = emptyList()
    private var mainAccel: Sensor? = null
    private var subAccel: Sensor? = null
    private var mainGyro: Sensor? = null
    private var subGyro: Sensor? = null
    private var gravitySensor: Sensor? = null
    private var uncalibratedMagnetometer: Sensor? = null
    private var publicHinge: Sensor? = null
    private var publicHingeDegrees: Float? = null
    private val magneticEstimator = MagneticHingeEstimator()
    private val adaptiveCalibration = AdaptiveAngleCalibration()
    private val estimateStabilizer = AngleEstimateStabilizer()
    private val orientationGuard = OrientationMotionGuard()
    private val liveSensorRates = LiveSensorRates()
    private val angleTrace = AngleDiagnosticTrace()
    private var traceEnabled = false
    private var foregroundMonitoring = false
    private var monitoringInitializedNanos = 0L
    private var lastSensorRestartNanos = 0L
    private var adaptiveSamplesSinceSave = 0
    private lateinit var adaptiveCalibrationFile: File
    private var adaptiveActive = false
    private var estimateHeld = false
    private var orientationHeld = false
    private var orientationUncertain = false
    private var coarseFallbackActive = false
    private var livePrediction: Float? = null
    private var livePredictionSource = "waiting"
    private var lastMainGyroTimestampNanos = 0L

    private val latest = IdentityHashMap<Sensor, FloatArray>()
    private val registrationResults = IdentityHashMap<Sensor, String>()
    private var lastStatePublishNanos = 0L
    private var lastDiagnosticLogNanos = 0L
    private var lastGravityTimestampNanos = 0L
    private val liveWatchdog = object : Runnable {
        override fun run() {
            if (!foregroundMonitoring) return
            ensureLiveSensors()
            handler.postDelayed(this, LIVE_WATCHDOG_INTERVAL_MS)
        }
    }

    private val recordLock = Any()
    private var recordingFile: File? = null
    private var recordingWriter: BufferedWriter? = null
    private var exactRecordingFile: File? = null
    private var exactRecordingWriter: BufferedWriter? = null
    @Volatile private var exactRecordingThread: Thread? = null
    private var recordingEvents = 0L
    private var recordingExactSamples = 0L
    private var recordingStartedNanos = 0L
    private var recordingTag = "raw"

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        // Detailed raw sensor tracing is deliberately limited to debuggable
        // APKs; release builds should not log sensitive physical telemetry.
        traceEnabled = app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (traceEnabled) {
            Log.i("Flip7AngleLab", "Angle decision trace enabled: tag=Flip7AngleTrace " +
                "events=transitions+~8Hz-motion+1Hz-idle")
        }
        sensorManager = app.getSystemService(SensorManager::class.java)
        powerManager = app.getSystemService(PowerManager::class.java)
        runCatching {
            app.assets.open(Flip7HingeBoostModel.ASSET_NAME).use(Flip7HingeBoostModel::load)
        }.onSuccess { model ->
            magneticEstimator.usePretrainedModel(model)
            Log.i("Flip7AngleLab", "Loaded offline Boost model: ${model.treeCount} trees")
        }.onFailure { error ->
            Log.e("Flip7AngleLab", "Could not load offline Boost model; using legacy fallback", error)
        }
        adaptiveCalibrationFile = File(app.filesDir, "flip7-angle-calibration-v1.csv")
        runCatching {
            if (adaptiveCalibrationFile.isFile) {
                val restored = adaptiveCalibrationFile.useLines { lines ->
                    lines.mapNotNull(AdaptiveAngleCalibration.Sample::parse).toList()
                }
                adaptiveCalibration.restore(restored.asSequence())
            }
        }.onFailure { error -> Log.w("Flip7AngleLab", "Could not load learned samples: ${error.javaClass.simpleName}") }
        thread = HandlerThread("Flip7AngleLab-sensors").apply { start() }
        handler = Handler(thread.looper)

        allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
        candidateSensors = allSensors.filter(::isCandidate)
        mainAccel = allSensors.firstOrNull { it.type == Sensor.TYPE_ACCELEROMETER && !isSub(it) }
        subAccel = allSensors.firstOrNull(::isSubAccelerometer)
        mainGyro = allSensors.firstOrNull { it.type == Sensor.TYPE_GYROSCOPE && !isSub(it) }
        subGyro = allSensors.firstOrNull(::isSubGyroscope)
        gravitySensor = allSensors.firstOrNull { it.type == Sensor.TYPE_GRAVITY }
        uncalibratedMagnetometer = allSensors.firstOrNull {
            Build.MODEL.uppercase(Locale.US).startsWith("SM-F766") &&
                it.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED
        }
        publicHinge = allSensors.firstOrNull { it.type == Sensor.TYPE_HINGE_ANGLE }

        registerCandidates(fastest = false)
        monitoringInitializedNanos = SystemClock.elapsedRealtimeNanos()
        publishState("Monitoring ${candidateSensors.size} candidate sensors without ADB")
    }

    /** Refresh live subscriptions if Android suspended non-wake-up IMUs. */
    fun onForeground() {
        if (!initialized) return
        handler.post {
            foregroundMonitoring = true
            ensureLiveSensors()
            handler.removeCallbacks(liveWatchdog)
            handler.postDelayed(liveWatchdog, LIVE_WATCHDOG_INTERVAL_MS)
        }
    }

    fun onBackground() {
        if (!initialized) return
        handler.post {
            foregroundMonitoring = false
            handler.removeCallbacks(liveWatchdog)
        }
    }

    private fun ensureLiveSensors() {
        // A folded Flip7 can put the inner display into Dozing even while
        // an ActivityRecord remains at the top of the task stack. Retrying
        // SensorManager registrations while the display is not interactive
        // adds work and cannot wake non-wake-up sensors.
        if (!foregroundMonitoring || !powerManager.isInteractive) return
        synchronized(recordLock) {
            if (recordingWriter != null) return
        }
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - monitoringInitializedNanos < LIVE_SENSOR_STARTUP_GRACE_NS ||
            now - lastSensorRestartNanos < LIVE_SENSOR_RETRY_INTERVAL_NS
        ) return
        val lastMag = liveSensorRates.reading(LiveSensorRates.Channel.MAGNETOMETER)
            .lastTimestampNanos
        if (lastMag != 0L && now - lastMag < LIVE_SENSOR_STALE_NS) return
        lastSensorRestartNanos = now
        sensorManager.unregisterListener(this)
        registrationResults.clear()
        registerCandidates(fastest = false)
        Log.w(
            "Flip7AngleLab",
            "Live magnetometer stalled; refreshed SensorManager registration " +
                "lastAgeMs=${LiveSensorRates.ageMillis(now, lastMag)}",
        )
        publishState("Reconnecting live sensor streams")
    }

    fun startRecording(): Result<Unit> = startRecordingInternal(calibration = false)

    fun startCalibrationRecording(): Result<Unit> = startRecordingInternal(calibration = true)

    private fun startRecordingInternal(calibration: Boolean): Result<Unit> {
        checkInitialized()?.let { return Result.failure(it) }
        synchronized(recordLock) {
            if (recordingWriter != null) return Result.failure(IllegalStateException("Recording already active"))
            return runCatching {
                val stamp = fileStamp()
                val stem = if (calibration) "flip7-calibration-v2-$stamp" else "flip7-sensor-$stamp"
                val file = File(app.cacheDir, "$stem-sensors.csv")
                val exactFile = File(app.cacheDir, "$stem-exact.csv")
                val writer = BufferedWriter(FileWriter(file, false), 128 * 1024)
                val exactWriter = BufferedWriter(FileWriter(exactFile, false), 32 * 1024)
                sensorManager.unregisterListener(this)
                registrationResults.clear()
                registerRecordingSensors()
                recordingStartedNanos = SystemClock.elapsedRealtimeNanos()
                recordingTag = if (calibration) "cal-v2|waiting" else "raw"
                writeHeader(writer, if (calibration) "calibration-v2" else "raw")
                writeExactHeader(exactWriter, if (calibration) "calibration-v2" else "raw")
                recordingFile = file
                recordingWriter = writer
                exactRecordingFile = exactFile
                exactRecordingWriter = exactWriter
                recordingEvents = 0L
                recordingExactSamples = 0L
                startExactSampler()
                publishState(
                    if (calibration) "Calibration v2 recording · sensors + 120 Hz exact stream"
                    else "Recording every registerable continuous/on-change sensor",
                )
            }
        }
    }

    fun setRecordingTag(value: String) {
        recordingTag = value.take(240)
    }

    fun saveLearnedCalibration() {
        if (!initialized) return
        handler.post {
            if (adaptiveSamplesSinceSave > 0) {
                persistAdaptiveCalibration()
                adaptiveSamplesSinceSave = 0
            }
        }
    }

    suspend fun stopRecordingAndSave(): Result<Uri> {
        checkInitialized()?.let { return Result.failure(it) }
        val files = synchronized(recordLock) {
            val current = recordingFile ?: return Result.failure(IllegalStateException("No active recording"))
            exactRecordingThread?.interrupt()
            exactRecordingThread = null
            runCatching { recordingWriter?.flush() }
            runCatching { recordingWriter?.close() }
            runCatching { exactRecordingWriter?.flush() }
            runCatching { exactRecordingWriter?.close() }
            recordingWriter = null
            recordingFile = null
            exactRecordingWriter = null
            val exact = exactRecordingFile
            exactRecordingFile = null
            sensorManager.unregisterListener(this)
            registerCandidates(fastest = false)
            current to exact
        }
        files.second?.let { exact ->
            publishToDownloads(exact, "Flip7 exact-angle stream")
        }
        return publishToDownloads(files.first, "Flip7 sensor recording")
    }

    suspend fun saveSnapshot(): Result<Uri> {
        checkInitialized()?.let { return Result.failure(it) }
        val file = withContext(Dispatchers.IO) {
            File(app.cacheDir, "flip7-sensor-snapshot-${fileStamp()}.txt").also { target ->
                target.bufferedWriter().use { writer ->
                    writeDeviceMetadata(writer)
                    writer.appendLine()
                    writer.appendLine("=== SENSOR INVENTORY (${allSensors.size}) ===")
                    inventory().forEach { item -> writer.appendLine(formatInventory(item)) }
                    writer.appendLine()
                    writer.appendLine("=== REGISTRATION RESULTS ===")
                    allSensors.forEach { sensor ->
                        writer.appendLine(
                            "handle=${sensorHandle(sensor)}; type=${sensor.type}; name=${sensor.name}; " +
                                "requiredPermission=${requiredPermission(sensor)}; " +
                                "result=${registrationResults[sensor] ?: "not attempted"}",
                        )
                    }
                    writer.appendLine()
                    writer.appendLine("=== LIVE CANDIDATES ===")
                    writer.appendLine(liveSummary())
                }
            }
        }
        return publishToDownloads(file, "Flip7 sensor snapshot")
    }

    fun lastLogUri(): Uri? = _state.value.lastLogUri?.let(Uri::parse)

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor) {
            uncalibratedMagnetometer -> liveSensorRates.record(
                LiveSensorRates.Channel.MAGNETOMETER, event.timestamp,
            )
            gravitySensor -> liveSensorRates.record(LiveSensorRates.Channel.GRAVITY, event.timestamp)
            mainGyro -> liveSensorRates.record(LiveSensorRates.Channel.GYROSCOPE, event.timestamp)
            publicHinge -> liveSensorRates.record(LiveSensorRates.Channel.HINGE, event.timestamp)
        }
        latest[event.sensor] = event.values.copyOf()
        if (event.sensor === publicHinge) {
            publicHingeDegrees = event.values.firstOrNull()?.takeIf(Float::isFinite)
        }
        if (event.sensor === gravitySensor && event.values.size >= 3) {
            lastGravityTimestampNanos = event.timestamp
            magneticEstimator.updateGravity(
                timestampNanos = event.timestamp,
                x = event.values[0],
                y = event.values[1],
                z = event.values[2],
            )
        }
        if (event.sensor === mainGyro && event.values.size >= 3) {
            lastMainGyroTimestampNanos = event.timestamp
        }
        if (event.sensor === uncalibratedMagnetometer && event.values.size >= 3) {
            val offlineEstimate = magneticEstimator.update(
                timestampNanos = event.timestamp,
                xMicroTesla = event.values[0],
                yMicroTesla = event.values[1],
                zMicroTesla = event.values[2],
                publicHingeDegrees = publicHingeDegrees,
            )
            val gravity = gravitySensor?.let(latest::get)
            val gravityFresh = gravity != null && gravity.size >= 3 &&
                lastGravityTimestampNanos > 0L &&
                event.timestamp - lastGravityTimestampNanos in -20_000_000L..100_000_000L
            val hinge = publicHingeDegrees
            val features = if (gravityFresh && hinge != null) {
                AdaptiveAngleCalibration.Sample(
                    magneticX = event.values[0],
                    magneticY = event.values[1],
                    magneticZ = event.values[2],
                    gravityX = gravity[0],
                    gravityY = gravity[1],
                    gravityZ = gravity[2],
                    publicHinge = hinge,
                    exactAngle = 0f, // Query only; exact angle is never used for prediction.
                )
            } else null
            val adaptiveEstimate = features?.let(adaptiveCalibration::predict)
            // Keep true current ML readings even if the displayed estimate
            // is temporarily uncertain or the public hinge is coarse.
            livePrediction = adaptiveEstimate ?: magneticEstimator.lastRawPrediction
                ?.takeIf { it.isFinite() }
            livePredictionSource = if (adaptiveEstimate != null) {
                "adaptive ${adaptiveCalibration.lastMatchMode}"
            } else magneticEstimator.lastPredictionSource
            val gyro = mainGyro?.let(latest::get)
            val gyroSpeed = gyro
                ?.takeIf {
                    it.size >= 3 && lastMainGyroTimestampNanos > 0L &&
                        event.timestamp - lastMainGyroTimestampNanos in
                        -30_000_000L..150_000_000L
                }
                ?.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) }
            // Do not seed either filter with incomplete startup data: the
            // old XYZ-only fallback produced ~31° before the 179° hinge and
            // gravity data arrived, then the rotation guard froze that value.
            if (features != null && (adaptiveEstimate != null || offlineEstimate != null)) {
                val rotationGateResult = orientationGuard.update(
                    timestampNanos = event.timestamp,
                    magneticX = features.magneticX,
                    magneticY = features.magneticY,
                    magneticZ = features.magneticZ,
                    gravityX = features.gravityX,
                    gravityY = features.gravityY,
                    gravityZ = features.gravityZ,
                    gyroSpeedRadiansPerSecond = gyroSpeed,
                    publicHinge = publicHingeDegrees,
                    modelAngle = adaptiveEstimate ?: offlineEstimate,
                    displayedAngle = _estimatedAngle.value,
                    offlineRawAngle = magneticEstimator.lastRawPrediction
                        ?.takeIf { magneticEstimator.lastPredictionSource == "Boost" },
                    adaptiveAngle = adaptiveEstimate,
                    adaptiveMatchMode = adaptiveCalibration.lastMatchMode,
                )
                orientationHeld = rotationGateResult.held
                orientationUncertain = rotationGateResult.uncertain
                // While the base half rotates, a model value from a different
                // phone pose is NOT new hinge-angle evidence.
                val immediate = rotationGateResult.hingeMotionEvidence ||
                    rotationGateResult.reason == "rotation-endpoint-consensus"
                val stabilized = if (orientationHeld) {
                    // IMPORTANT: Do not even FEED the orientation-sensitive
                    // ML estimate into the median/EMA while turning the
                    // phone. Previously that silently moved the filter's
                    // internal angle although the on-screen hold looked
                    // steady, allowing a deferred jump on release.
                    estimateHeld = true
                    coarseFallbackActive = false
                    _estimatedAngle.value = rotationGateResult.angle ?: _estimatedAngle.value
                    null
                } else {
                    if (rotationGateResult.reanchored && !rotationGateResult.uncertain) {
                        estimateStabilizer.reset()
                    }
                    val next = estimateStabilizer.update(
                        timestampNanos = event.timestamp,
                        adaptiveEstimate = adaptiveEstimate,
                        offlineEstimate = if (immediate &&
                            magneticEstimator.lastPredictionSource == "Boost"
                        ) magneticEstimator.lastRawPrediction ?: offlineEstimate else offlineEstimate,
                        publicHinge = publicHingeDegrees,
                        hasLearnedSamples = adaptiveCalibration.sampleCount > 0,
                        immediateMotion = immediate,
                    )
                    adaptiveActive = next.adaptive
                    estimateHeld = next.held
                    coarseFallbackActive = next.coarseFallback
                    _estimatedAngle.value = next.angle
                    next
                }
                if (traceEnabled) {
                    val reference = SamsungAdbAngleRuntime.sample.value
                    val ageMs = reference?.let {
                        (event.timestamp - it.timestampNanos) / 1_000_000L
                    }
                    val trustedReference = SamsungAngleReferencePolicy.trustedAngle(
                        reference, SamsungAdbAngleRuntime.sourceActive.value,
                        publicHingeDegrees,
                    )
                    val rendered = AngleDisplayEndpoint.fromSamsung(
                        trustedReference, publicHingeDegrees,
                    ) ?: _estimatedAngle.value
                    val traceLine = angleTrace.offer(
                        AngleDiagnosticTrace.Frame(
                            timestampNanos = event.timestamp,
                            magnet = AngleDiagnosticTrace.Vector(
                                event.values[0], event.values[1], event.values[2],
                            ),
                            gravity = AngleDiagnosticTrace.Vector(
                                features.gravityX, features.gravityY, features.gravityZ,
                            ),
                            gyro = gyro?.takeIf {
                                it.size >= 3 && gyroSpeed != null
                            }?.let {
                                AngleDiagnosticTrace.Vector(it[0], it[1], it[2])
                            },
                            gravityAgeMs = LiveSensorRates.ageMillis(
                                event.timestamp, lastGravityTimestampNanos,
                            ),
                            gyroAgeMs = LiveSensorRates.ageMillis(
                                event.timestamp, lastMainGyroTimestampNanos,
                            ),
                            hingeAgeMs = LiveSensorRates.ageMillis(
                                event.timestamp,
                                liveSensorRates.reading(
                                    LiveSensorRates.Channel.HINGE,
                                ).lastTimestampNanos,
                            ),
                            publicHinge = publicHingeDegrees,
                            reference = reference?.angle,
                            referenceAgeMs = ageMs,
                            referenceTrusted = trustedReference != null,
                            boost = magneticEstimator.lastRawPrediction,
                            offlineSmoothed = offlineEstimate,
                            adaptive = adaptiveEstimate,
                            matchMode = adaptiveCalibration.lastMatchMode,
                            guardReason = rotationGateResult.reason,
                            guardAngle = rotationGateResult.angle,
                            stabilized = stabilized?.angle ?: _estimatedAngle.value,
                            displayed = rendered,
                            held = estimateHeld,
                            rotationHeld = orientationHeld,
                            adaptiveHeld = stabilized?.held ?: false,
                            uncertain = orientationUncertain,
                            immediate = immediate,
                            reanchored = rotationGateResult.reanchored,
                            coarseFallback = coarseFallbackActive,
                            magnetometerHz = liveSensorRates.reading(
                                LiveSensorRates.Channel.MAGNETOMETER,
                            ).hz,
                            gyroscopeHz = liveSensorRates.reading(
                                LiveSensorRates.Channel.GYROSCOPE,
                            ).hz,
                            learnedSamples = adaptiveCalibration.sampleCount,
                        ),
                    )
                    if (traceLine != null) Log.d("Flip7AngleTrace", traceLine)
                }
            }

            // Learn AFTER prediction, using only a fresh timestamp-aligned Samsung label.
            val reference = SamsungAdbAngleRuntime.sample.value
            val ageNanos = reference?.let { event.timestamp - it.timestampNanos }
            if (features != null && reference != null && ageNanos != null &&
                ageNanos in -30_000_000L..150_000_000L &&
                adaptiveCalibration.observe(features.copy(exactAngle = reference.angle))
            ) {
                adaptiveSamplesSinceSave++
                if (adaptiveSamplesSinceSave >= 16) {
                    adaptiveSamplesSinceSave = 0
                    persistAdaptiveCalibration()
                }
            }

            if (event.timestamp - lastDiagnosticLogNanos >= 1_000_000_000L) {
                lastDiagnosticLogNanos = event.timestamp
                val ageMs = reference?.let { (event.timestamp - it.timestampNanos) / 1_000_000L }
                Log.i(
                    "Flip7AngleLab",
                    "angle: reference=${reference?.angle} ageMs=$ageMs public=$publicHingeDegrees " +
                        "raw=${magneticEstimator.lastRawPrediction} " +
                        "source=${magneticEstimator.lastPredictionSource} offline=$offlineEstimate " +
                        "adaptive=$adaptiveEstimate match=${adaptiveCalibration.lastMatchMode} " +
                        "learned=${adaptiveCalibration.sampleCount} " +
                        "adaptiveSelected=$adaptiveActive held=$estimateHeld coarseFallback=$coarseFallbackActive " +
                        "rotationHeld=$orientationHeld gyro=$gyroSpeed " +
                        "uncertain=$orientationUncertain " +
                        "magHz=${liveSensorRates.reading(LiveSensorRates.Channel.MAGNETOMETER).hz} " +
                        "magCount=${liveSensorRates.reading(LiveSensorRates.Channel.MAGNETOMETER).count} " +
                        "gyroHz=${liveSensorRates.reading(LiveSensorRates.Channel.GYROSCOPE).hz} " +
                        "estimated=${_estimatedAngle.value}",
                )
            }
        }
        synchronized(recordLock) {
            recordingWriter?.let { writer ->
                val elapsedMs = (event.timestamp - recordingStartedNanos) / 1_000_000.0
                val reference = SamsungAdbAngleRuntime.sample.value
                val referenceAgeMs = reference?.let { sample ->
                    (event.timestamp - sample.timestampNanos) / 1_000_000.0
                }
                writer.append(event.timestamp.toString()).append(',')
                writer.append(String.format(Locale.US, "%.3f", elapsedMs)).append(',')
                writer.append(event.sensor.id.toString()).append(',')
                writer.append(sensorHandle(event.sensor).toString()).append(',')
                writer.append(csv(event.sensor.name)).append(',')
                writer.append(event.sensor.type.toString()).append(',')
                writer.append(csv(event.sensor.stringType)).append(',')
                writer.append(csv(requiredPermission(event.sensor))).append(',')
                writer.append(csv(registrationResults[event.sensor] ?: "event received")).append(',')
                writer.append(event.accuracy.toString()).append(',')
                writer.append(reference?.angle?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()).append(',')
                writer.append(referenceAgeMs?.let { String.format(Locale.US, "%.3f", it) }.orEmpty())
                writer.append(',').append(csv(recordingTag))
                event.values.forEach { value ->
                    writer.append(',').append(String.format(Locale.US, "%.9f", value))
                }
                writer.newLine()
                recordingEvents += 1
                if (recordingEvents % 512L == 0L) writer.flush()
            }
        }

        if (event.timestamp - lastStatePublishNanos >= STATE_PUBLISH_PERIOD_NS) {
            lastStatePublishNanos = event.timestamp
            publishState(if (recordingWriter != null) "Recording raw direct sensors" else "Direct sensors live")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerCandidates(fastest: Boolean) {
        candidateSensors.forEach { sensor ->
            val period = if (fastest || sensor === uncalibratedMagnetometer) {
                SensorManager.SENSOR_DELAY_FASTEST
            } else {
                SensorManager.SENSOR_DELAY_GAME
            }
            registerSensor(sensor, period)
        }
    }

    private fun registerRecordingSensors() {
        allSensors
            .filter { sensor ->
                sensor.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS ||
                    sensor.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE
            }
            .forEach { sensor ->
                registerSensor(sensor, SensorManager.SENSOR_DELAY_FASTEST)
            }
    }

    private fun registerSensor(sensor: Sensor, period: Int) {
        val result = try {
            if (sensorManager.registerListener(this, sensor, period, 0, handler)) "registered" else "register=false"
        } catch (error: SecurityException) {
            "SecurityException:${error.message.orEmpty()}"
        } catch (error: Throwable) {
            "${error.javaClass.simpleName}:${error.message.orEmpty()}"
        }
        registrationResults[sensor] = result
    }

    private fun isSubAccelerometer(sensor: Sensor): Boolean {
        val text = sensorText(sensor)
        return isSub(sensor) && text.contains("accelerometer") && !text.contains("uncalibrated")
    }

    private fun isSubGyroscope(sensor: Sensor): Boolean {
        val text = sensorText(sensor)
        return isSub(sensor) && text.contains("gyro") && !text.contains("uncalibrated")
    }

    private fun isSub(sensor: Sensor): Boolean {
        val text = sensorText(sensor)
        return text.contains("sub") || text.contains("secondary") || text.contains("2nd")
    }

    private fun sensorText(sensor: Sensor): String =
        "${sensor.name} ${sensor.stringType}".lowercase(Locale.US)

    private fun isCandidate(sensor: Sensor): Boolean {
        if (sensor.type in STANDARD_RELEVANT_TYPES) return true
        val text = sensorText(sensor)
        return RELEVANT_WORDS.any(text::contains)
    }

    private fun inventory(): List<SensorInventoryItem> = allSensors.map { sensor ->
        SensorInventoryItem(
            id = sensor.id,
            handle = sensorHandle(sensor),
            name = sensor.name,
            vendor = sensor.vendor,
            type = sensor.type,
            stringType = sensor.stringType,
            requiredPermission = requiredPermission(sensor),
            registrationResult = registrationResults[sensor] ?: "not attempted",
            version = sensor.version,
            maxRange = sensor.maximumRange,
            resolution = sensor.resolution,
            powerMa = sensor.power,
            minDelayUs = sensor.minDelay,
            maxDelayUs = sensor.maxDelay,
            reportingMode = sensor.reportingMode,
            wakeUp = sensor.isWakeUpSensor,
        )
    }

    private fun publishState(status: String) {
        val current = _state.value
        _state.value = current.copy(
            inventory = if (current.inventory.isEmpty()) inventory() else current.inventory,
            candidateCount = candidateSensors.size,
            mainAccelerometer = mainAccel?.name,
            subAccelerometer = subAccel?.name,
            mainGyroscope = mainGyro?.name,
            subGyroscope = subGyro?.name,
            mainAccelValues = values(mainAccel),
            subAccelValues = values(subAccel),
            mainGyroValues = values(mainGyro),
            subGyroValues = values(subGyro),
            gravityFoldEstimate = gravityFoldEstimate(),
            magneticFoldEstimate = _estimatedAngle.value,
            estimateHeld = estimateHeld,
            orientationHeld = orientationHeld,
            usingCoarseFallback = coarseFallbackActive,
            usingAdaptiveCalibration = adaptiveActive,
            adaptiveSampleCount = adaptiveCalibration.sampleCount,
            magneticRawPrediction = magneticEstimator.lastRawPrediction,
            livePrediction = livePrediction,
            livePredictionSource = livePredictionSource,
            orientationUncertain = orientationUncertain,
            magnetometerLive = liveSensorRates.reading(LiveSensorRates.Channel.MAGNETOMETER),
            gravityLive = liveSensorRates.reading(LiveSensorRates.Channel.GRAVITY),
            gyroscopeLive = liveSensorRates.reading(LiveSensorRates.Channel.GYROSCOPE),
            hingeLive = liveSensorRates.reading(LiveSensorRates.Channel.HINGE),
            magneticPredictionSource = magneticEstimator.lastPredictionSource,
            adaptiveMatchMode = adaptiveCalibration.lastMatchMode,
            publicHingeDegrees = publicHingeDegrees,
            samsungPrivateSummary = samsungPrivateSummary(),
            recording = recordingWriter != null,
            eventCount = recordingEvents,
            exactSampleCount = recordingExactSamples,
            recordingTag = recordingTag,
            status = status,
        )
    }

    private fun values(sensor: Sensor?): List<Float> = sensor
        ?.let { latest[it] }
        ?.take(3)
        ?.toList()
        ?: emptyList()

    private fun persistAdaptiveCalibration() {
        runCatching {
            val temp = File(app.filesDir, "${adaptiveCalibrationFile.name}.tmp")
            temp.bufferedWriter().use { writer ->
                adaptiveCalibration.snapshot().forEach { writer.appendLine(it.serialize()) }
            }
            check(temp.renameTo(adaptiveCalibrationFile)) { "Atomic rename failed" }
        }.onFailure { error ->
            Log.w("Flip7AngleLab", "Could not save learned samples: ${error.javaClass.simpleName}")
        }
    }

    /** 180° when gravity vectors are aligned; useful as a first-pass diagnostic, not final fusion. */
    private fun gravityFoldEstimate(): Float? {
        val a = mainAccel?.let { latest[it] } ?: return null
        val b = subAccel?.let { latest[it] } ?: return null
        return DirectImuMath.gravityFoldAngle(a, b)
    }

    private fun writeHeader(writer: BufferedWriter, recordingMode: String) {
        writeDeviceMetadata(writer)
        writer.appendLine("# recordingMode=$recordingMode")
        writer.appendLine("# candidateSensors=${candidateSensors.size}")
        writer.appendLine("# mainAccel=${mainAccel?.name.orEmpty()}")
        writer.appendLine("# subAccel=${subAccel?.name.orEmpty()}")
        writer.appendLine("# mainGyro=${mainGyro?.name.orEmpty()}")
        writer.appendLine("# subGyro=${subGyro?.name.orEmpty()}")
        inventory().forEach { writer.appendLine("# sensor=${formatInventory(it)}") }
        writer.appendLine(
            "timestamp_nanos,elapsed_ms,sensor_id,sensor_handle,sensor_name,type,string_type," +
                "required_permission,registration_result,accuracy,reference_angle_deg,reference_age_ms," +
                "recording_tag," +
                (0..15).joinToString(",") { "v$it" },
        )
        writer.flush()
    }

    private fun writeExactHeader(writer: BufferedWriter, recordingMode: String) {
        writeDeviceMetadata(writer)
        writer.appendLine("# recordingMode=$recordingMode")
        writer.appendLine(
            "timestamp_nanos,elapsed_ms,angle_deg,source_timestamp_nanos,source_age_ms,source,recording_tag",
        )
        writer.flush()
    }

    private fun startExactSampler() {
        exactRecordingThread?.interrupt()
        val thread = Thread({
            var nextTick = SystemClock.elapsedRealtimeNanos()
            while (!Thread.currentThread().isInterrupted) {
                val now = SystemClock.elapsedRealtimeNanos()
                val sample = SamsungAdbAngleRuntime.sample.value
                val writer = synchronized(recordLock) { exactRecordingWriter }
                if (writer != null && sample != null && now >= recordingStartedNanos) {
                    synchronized(recordLock) {
                        exactRecordingWriter?.let { activeWriter ->
                            val elapsedMs = (now - recordingStartedNanos) / 1_000_000.0
                            val sourceAgeMs = (now - sample.timestampNanos) / 1_000_000.0
                            activeWriter.append(now.toString()).append(',')
                            activeWriter.append(String.format(Locale.US, "%.3f", elapsedMs)).append(',')
                            activeWriter.append(String.format(Locale.US, "%.6f", sample.angle)).append(',')
                            activeWriter.append(sample.timestampNanos.toString()).append(',')
                            activeWriter.append(String.format(Locale.US, "%.3f", sourceAgeMs)).append(',')
                            activeWriter.append(csv(SamsungAdbAngleRuntime.status.value)).append(',')
                            activeWriter.append(csv(recordingTag))
                            activeWriter.newLine()
                            recordingExactSamples += 1
                            if (recordingExactSamples % 256L == 0L) activeWriter.flush()
                        }
                    }
                }

                nextTick += EXACT_SAMPLE_PERIOD_NANOS
                val afterWrite = SystemClock.elapsedRealtimeNanos()
                if (nextTick <= afterWrite) nextTick = afterWrite + EXACT_SAMPLE_PERIOD_NANOS
                LockSupport.parkNanos((nextTick - afterWrite).coerceAtLeast(1L))
            }
        }, "Flip7AngleLab-calibration-exact")
        exactRecordingThread = thread
        thread.start()
    }

    private fun writeDeviceMetadata(writer: BufferedWriter) {
        val version = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull().orEmpty()
        writer.appendLine("# generated=${Date()}")
        writer.appendLine("# app=${app.packageName} version=$version")
        writer.appendLine("# manufacturer=${Build.MANUFACTURER}")
        writer.appendLine("# model=${Build.MODEL}")
        writer.appendLine("# device=${Build.DEVICE}")
        writer.appendLine("# product=${Build.PRODUCT}")
        writer.appendLine("# build=${Build.DISPLAY}")
        writer.appendLine("# sdk=${Build.VERSION.SDK_INT}")
    }

    private fun liveSummary(): String = buildString {
        appendLine("mainAccel=${mainAccel?.name}: ${formatValues(values(mainAccel))}")
        appendLine("subAccel=${subAccel?.name}: ${formatValues(values(subAccel))}")
        appendLine("mainGyro=${mainGyro?.name}: ${formatValues(values(mainGyro))}")
        appendLine("subGyro=${subGyro?.name}: ${formatValues(values(subGyro))}")
        appendLine(samsungPrivateSummary())
        appendLine("gravityFoldEstimate=${gravityFoldEstimate()}")
        append("magneticFoldEstimate=${_estimatedAngle.value}")
    }

    private fun samsungPrivateSummary(): String = allSensors
        .filter { sensor ->
            sensor.type in SAMSUNG_FOLD_TYPES ||
                sensorText(sensor).contains("fold") ||
                sensorText(sensor).contains("lid angle")
        }
        .joinToString(separator = "\n") { sensor ->
            "${sensor.name}[type=${sensor.type},handle=${sensorHandle(sensor)},perm=${requiredPermission(sensor).ifBlank { "none" }}," +
                "register=${registrationResults[sensor] ?: "not attempted"},value=${formatValues(values(sensor))}]"
        }

    // Android 15+ blocks non-SDK Sensor.getHandle()/mHandle reflection.
    // Keep the legacy CSV field for backwards compatibility but clearly mark
    // the private handle as unavailable. Use the public Sensor.type and id
    // for diagnostics and training instead.
    private fun sensorHandle(@Suppress("UNUSED_PARAMETER") sensor: Sensor): Int = -1

    // Android does not expose Sensor.getRequiredPermission() as a public API.
    // This permission is known for the private Samsung fold sensor family on
    // the tested Flip7, but may differ on other devices and firmware.
    private fun requiredPermission(sensor: Sensor): String =
        if (sensor.type in SAMSUNG_FOLD_TYPES) "com.samsung.permission.SSENSOR" else ""

    private suspend fun publishToDownloads(file: File, label: String): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = app.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (file.extension == "csv") "text/csv" else "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Flip7AngleLab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads/Flip7AngleLab log")
            try {
                resolver.openOutputStream(uri, "w")?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                } ?: error("Could not open Downloads output stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                file.delete()
                _state.value = _state.value.copy(
                    recording = false,
                    status = "$label saved to Downloads/Flip7AngleLab",
                    lastLogName = file.name,
                    lastLogUri = uri.toString(),
                )
                uri
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        }.onFailure { error ->
            _state.value = _state.value.copy(
                recording = false,
                status = "Saving log failed: ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }

    private fun formatInventory(item: SensorInventoryItem): String =
        "id=${item.id}; handle=${item.handle}; name=${item.name}; vendor=${item.vendor}; type=${item.type}; " +
            "stringType=${item.stringType}; requiredPermission=${item.requiredPermission}; " +
            "registrationResult=${item.registrationResult}; version=${item.version}; maxRange=${item.maxRange}; " +
            "resolution=${item.resolution}; powerMa=${item.powerMa}; minDelayUs=${item.minDelayUs}; " +
            "maxDelayUs=${item.maxDelayUs}; reportingMode=${item.reportingMode}; wakeUp=${item.wakeUp}"

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""

    private fun formatValues(values: List<Float>): String =
        if (values.isEmpty()) "—" else values.joinToString(prefix = "[", postfix = "]") {
            String.format(Locale.US, "%.5f", it)
        }

    private fun checkInitialized(): Throwable? =
        if (initialized) null else IllegalStateException("DirectSensorLab is not initialized")

    private fun fileStamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    // At most one full Compose state publication per display frame, instead
    // of waiting 50 ms between visibly different hinge-angle readings.
    private const val STATE_PUBLISH_PERIOD_NS = 16_000_000L
    private const val EXACT_SAMPLE_PERIOD_NANOS = 1_000_000_000L / 120L
    private const val LIVE_WATCHDOG_INTERVAL_MS = 600L
    private const val LIVE_SENSOR_STALE_NS = 1_000_000_000L
    private const val LIVE_SENSOR_STARTUP_GRACE_NS = 1_500_000_000L
    private const val LIVE_SENSOR_RETRY_INTERVAL_NS = 3_000_000_000L

    private val SAMSUNG_FOLD_TYPES = setOf(65686, 65687, 65688, 65689, 65690, 65695, 65697, 65700, 65711)

    private val STANDARD_RELEVANT_TYPES = setOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_HINGE_ANGLE,
    )

    private val RELEVANT_WORDS = listOf(
        "accel", "gyro", "hall", "hinge", "fold", "magnet", "rotation", "gravity", "orientation", "sub",
    )
}

object DirectImuMath {
    /**
     * First-pass static estimate: open halves have aligned gravity vectors (180° fold).
     * It cannot observe relative rotation around gravity itself, so gyro fusion is still required.
     */
    fun gravityFoldAngle(main: FloatArray, sub: FloatArray): Float? {
        if (main.size < 3 || sub.size < 3) return null
        val mainNorm = sqrt(main[0] * main[0] + main[1] * main[1] + main[2] * main[2])
        val subNorm = sqrt(sub[0] * sub[0] + sub[1] * sub[1] + sub[2] * sub[2])
        if (mainNorm < 0.1f || subNorm < 0.1f) return null
        val cosine = (
            (main[0] * sub[0] + main[1] * sub[1] + main[2] * sub[2]) /
                (mainNorm * subNorm)
            ).coerceIn(-1f, 1f)
        val relativeDegrees = Math.toDegrees(acos(cosine.toDouble())).toFloat()
        return (180f - relativeDegrees).coerceIn(0f, 180f)
    }
}
