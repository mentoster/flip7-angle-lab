package com.mentoster.flip7anglelab.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mentoster.flip7anglelab.fold.CalibrationPhase
import com.mentoster.flip7anglelab.fold.CalibrationV2Guide
import com.mentoster.flip7anglelab.fold.DirectSensorLab
import com.mentoster.flip7anglelab.fold.LiveSensorRates
import com.mentoster.flip7anglelab.fold.SamsungAngleReferencePolicy
import com.mentoster.flip7anglelab.fold.SamsungEndpointPresentation
import com.mentoster.flip7anglelab.fold.SamsungAdbAngleRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import java.util.Locale

/** Live hinge visualization, sensor comparison and training-data capture. */
@Composable
fun AngleLabScreen(
    samsungRuntime: SamsungAdbAngleRuntime,
    autoStartCalibration: Boolean = false,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sensors by DirectSensorLab.state.collectAsState()
    val exact by samsungRuntime.sample.collectAsState()
    val exactSourceActive by samsungRuntime.sourceActive.collectAsState()
    val exactStatus by samsungRuntime.status.collectAsState()
    val pairingEndpoint by samsungRuntime.pairEndpoint.collectAsState()
    val connectionEndpoint by samsungRuntime.connectEndpoint.collectAsState()
    val guide = remember { CalibrationV2Guide() }
    var calibration by remember { mutableStateOf(guide.state) }
    var pairingCode by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var autoStarted by remember { mutableStateOf(false) }
    var nowNanos by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    LaunchedEffect(Unit) {
        // This is a UI freshness heartbeat, NOT fake sensor data. It makes
        // missing sensor events visible even if Android stops dispatching.
        while (coroutineContext.isActive) {
            delay(200L)
            nowNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
    // Samsung is an ON-CHANGE hinge sensor. Its last angle remains valid
    // while its event reader is connected; 350 ms wall-clock age is NOT a
    // reason to fall back to a wrong autonomous model on a stationary phone.
    val trustedRaw = SamsungAngleReferencePolicy.trustedAngle(
        exact, exactSourceActive, sensors.publicHingeDegrees,
    )
    val trustedExact = exact?.takeIf { trustedRaw != null }
    val samsungPresentation = remember { SamsungEndpointPresentation() }
    val freshExact = trustedExact?.takeIf {
        LiveSensorRates.isFresh(nowNanos, it.timestampNanos, 350_000_000L)
    }
    val displayedAngle = samsungPresentation.render(
        trustedRaw, sensors.publicHingeDegrees, sensors.orientationHeld,
    )
        ?: sensors.magneticFoldEstimate
        ?: sensors.publicHingeDegrees
    val displayedSource = when {
        trustedExact != null -> "Samsung · exact on-change hinge angle"
        sensors.orientationHeld -> "Last hinge angle · orientation changed (held)"
        sensors.orientationUncertain -> "Live ML · phone rotation makes angle uncertain"
        sensors.estimateHeld -> "Last calibrated estimate · no matching orientation"
        sensors.usingCoarseFallback -> "Android posture · coarse estimate only"
        sensors.magneticFoldEstimate != null ->
            if (sensors.usingAdaptiveCalibration) "Adaptive model · reference-trained"
            else "Offline trained model · estimate"
        sensors.publicHingeDegrees != null -> "Android · coarse angle"
        else -> "Waiting for sensors"
    }

    fun startCalibration() {
        val sample = freshExact ?: return
        DirectSensorLab.startCalibrationRecording().fold(
            onSuccess = {
                calibration = guide.start(sample.angle, sample.timestampNanos)
                DirectSensorLab.setRecordingTag(calibration.recordingTag)
                message = ""
            },
            onFailure = { message = it.message.orEmpty() },
        )
    }

    fun stopAndSave() {
        scope.launch {
            DirectSensorLab.stopRecordingAndSave().fold(
                onSuccess = { message = "Dataset saved to Downloads/Flip7AngleLab" },
                onFailure = { message = it.message.orEmpty() },
            )
        }
    }

    LaunchedEffect(autoStartCalibration, exact?.timestampNanos, sensors.recording) {
        if (autoStartCalibration && !autoStarted && freshExact != null && !sensors.recording) {
            startCalibration()
            autoStarted = true
        }
    }

    LaunchedEffect(exact?.timestampNanos) {
        val sample = exact ?: return@LaunchedEffect
        if (calibration.running) {
            calibration = guide.update(sample.angle, sample.timestampNanos)
            DirectSensorLab.setRecordingTag(calibration.recordingTag)
        }
    }

    LaunchedEffect(calibration.phase) {
        if (calibration.phase != CalibrationPhase.STATIC_HOLD) return@LaunchedEffect
        while (coroutineContext.isActive && guide.state.phase == CalibrationPhase.STATIC_HOLD) {
            samsungRuntime.sample.value?.let { sample ->
                calibration = guide.update(sample.angle, SystemClock.elapsedRealtimeNanos())
                DirectSensorLab.setRecordingTag(calibration.recordingTag)
            }
            delay(50)
        }
    }

    LaunchedEffect(calibration.complete) {
        if (calibration.complete && sensors.recording) {
            DirectSensorLab.setRecordingTag(calibration.recordingTag)
            delay(500)
            stopAndSave()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Flip7 Angle Lab", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Live hinge angle · Samsung reference · trained ML estimate",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()
        Text("Hinge opening angle", style = MaterialTheme.typography.titleMedium)
        HingeAnglePreview(displayedAngle)
        Text(
            displayedAngle.degrees(1),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            displayedSource,
            style = MaterialTheme.typography.labelLarge,
        )
        if (trustedExact != null && displayedAngle != trustedExact.angle) {
            Text(
                "Fully open · Samsung raw sensor: ${trustedExact.angle.degrees(1)} (display normalized to 180°)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(exactStatus, style = MaterialTheme.typography.bodySmall)
        if (trustedExact != null && freshExact == null) {
            val age = LiveSensorRates.ageMillis(nowNanos, trustedExact.timestampNanos)
            Text(
                "Samsung last event: ${age ?: 0} ms ago · on-change reader connected",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val oldExact = exact
        if (oldExact != null && trustedExact == null) {
            val age = LiveSensorRates.ageMillis(nowNanos, oldExact.timestampNanos)
            Text(
                "Last Samsung reference: ${oldExact.angle.degrees(1)} · ${age ?: 0} ms ago (reader disconnected or posture mismatch)",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when {
                        sensors.orientationHeld -> "Held · new phone pose is not hinge evidence"
                        sensors.orientationUncertain -> "Live ML · rotation uncertainty"
                        sensors.estimateHeld -> "Last learned angle (held)"
                        sensors.usingCoarseFallback -> "Coarse posture fallback"
                        sensors.usingAdaptiveCalibration -> "Adaptive estimate"
                        else -> "Offline ML estimate"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(sensors.magneticFoldEstimate.degrees(1), style = MaterialTheme.typography.headlineSmall)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Android hinge sensor", style = MaterialTheme.typography.labelLarge)
                Text(sensors.publicHingeDegrees.degrees(1), style = MaterialTheme.typography.headlineSmall)
            }
        }
        Text(
            "Model error vs. Samsung reference: " +
                if (trustedExact != null && sensors.magneticFoldEstimate != null) {
                    abs(trustedExact.angle - sensors.magneticFoldEstimate!!).degrees(1)
                } else "—",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Raw offline ${sensors.magneticPredictionSource}: " +
                sensors.magneticRawPrediction.degrees(1) +
                " · learned samples: ${sensors.adaptiveSampleCount}" +
                " · match: ${sensors.adaptiveMatchMode}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "LIVE model (before stabilizer): ${sensors.livePrediction.degrees(1)}" +
                " · ${sensors.livePredictionSource}",
            style = MaterialTheme.typography.bodySmall,
        )
        val magAge = LiveSensorRates.ageMillis(
            nowNanos, sensors.magnetometerLive.lastTimestampNanos,
        )
        val gravAge = LiveSensorRates.ageMillis(
            nowNanos, sensors.gravityLive.lastTimestampNanos,
        )
        val gyroAge = LiveSensorRates.ageMillis(
            nowNanos, sensors.gyroscopeLive.lastTimestampNanos,
        )
        fun formatStream(label: String, hz: Float, count: Long, age: Long?): String =
            "$label: ${"%.0f".format(java.util.Locale.US, hz)} Hz · $count samples · " +
                (if (age == null) "no readings" else "${age} ms ago")
        Text(
            "Live sensor streams (measured from Android events):\n" +
                formatStream("Magnetometer", sensors.magnetometerLive.hz,
                    sensors.magnetometerLive.count, magAge) + "\n" +
                formatStream("Gravity", sensors.gravityLive.hz,
                    sensors.gravityLive.count, gravAge) + "\n" +
                formatStream("Gyroscope", sensors.gyroscopeLive.hz,
                    sensors.gyroscopeLive.count, gyroAge) + "\n" +
                "Hinge posture: on-change sensor (${sensors.hingeLive.count} events)",
            style = MaterialTheme.typography.bodySmall,
            color = if (magAge == null || magAge > 500L) {
                MaterialTheme.colorScheme.error
            } else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "The bundled Boost model is trained on Samsung-labeled measurements " +
                "and runs without ADB. During whole-phone rotation, the " +
                "last hinge estimate is held (explicitly labeled) while " +
                "raw LIVE model values continue to update below. Only new " +
                "hinge evidence releases the hold; a stable new phone pose " +
                "is not an angle measurement. Simultaneous slow folding " +
                "and rotation can remain ambiguous without Samsung's sensor.",
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Text("Training dataset", style = MaterialTheme.typography.titleMedium)
        Text(
            "Guided calibration: 6 orientations × 3 speeds × 4 cycles, followed by " +
                "holds every 10°. Requires a live Samsung reference angle.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (calibration.running || calibration.complete) {
            Text(calibration.instruction, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Cycles ${calibration.completedCycles}/${calibration.totalCycles} · " +
                    "hold points ${calibration.staticIndex}/${calibration.staticCount}",
                style = MaterialTheme.typography.labelLarge,
            )
            if (calibration.feedback.isNotBlank()) {
                Text(calibration.feedback, style = MaterialTheme.typography.bodySmall)
            }
            if (calibration.phase == CalibrationPhase.WAIT_FOR_OPEN) {
                Button(
                    onClick = {
                        calibration = guide.confirmReady()
                        DirectSensorLab.setRecordingTag(calibration.recordingTag)
                    },
                ) { Text("Ready — start cycle") }
            }
        }
        if (!sensors.recording) {
            Button(
                enabled = freshExact != null,
                onClick = { startCalibration() },
            ) { Text("Start calibration") }
        } else {
            Button(
                onClick = {
                    if (calibration.running) {
                        calibration = guide.stop()
                        DirectSensorLab.setRecordingTag(calibration.recordingTag)
                    }
                    stopAndSave()
                },
            ) { Text("Stop and save") }
        }

        HorizontalDivider()
        Text("Sensor recording", style = MaterialTheme.typography.titleMedium)
        Text(sensors.status, style = MaterialTheme.typography.bodySmall)
        Text(
            "Events: ${sensors.eventCount} · reference samples: ${sensors.exactSampleCount}",
            style = MaterialTheme.typography.labelMedium,
        )
        if (!sensors.recording) {
            OutlinedButton(onClick = {
                DirectSensorLab.startRecording().onFailure { message = it.message.orEmpty() }
            }) { Text("Record raw dataset") }
        }
        OutlinedButton(onClick = {
            scope.launch {
                DirectSensorLab.saveSnapshot().fold(
                    onSuccess = { message = "Sensor inventory saved" },
                    onFailure = { message = it.message.orEmpty() },
                )
            }
        }) { Text("Save sensor inventory") }
        sensors.lastLogUri?.let {
            OutlinedButton(onClick = {
                val uri = DirectSensorLab.lastLogUri() ?: return@OutlinedButton
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = if (sensors.lastLogName?.endsWith(".csv") == true) "text/csv" else "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Share Flip7 measurements",
                ))
            }) { Text("Share last file") }
        }
        Text("Files: Downloads/Flip7AngleLab", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Available sensors", style = MaterialTheme.typography.titleMedium)
        Text(
            "Total: ${sensors.inventory.size} · relevant: ${sensors.candidateCount}\n" +
                "Main accelerometer: ${sensors.mainAccelerometer ?: "unavailable"}\n" +
                "Secondary accelerometer: ${sensors.subAccelerometer ?: "unavailable"}\n" +
                "Main gyroscope: ${sensors.mainGyroscope ?: "unavailable"}\n" +
                "Secondary gyroscope: ${sensors.subGyroscope ?: "unavailable"}\n" +
                "Gravity-only estimate: ${sensors.gravityFoldEstimate.degrees(1)}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (sensors.samsungPrivateSummary.isNotBlank()) {
            Text(sensors.samsungPrivateSummary, style = MaterialTheme.typography.bodySmall)
        }

        if (exact == null) {
            HorizontalDivider()
            Text("Enable exact-angle readings", style = MaterialTheme.typography.titleMedium)
            Text(
                "Grant READ_LOGS once through USB ADB. " +
                    "Alternatively, use Wireless debugging with a pairing code.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "adb shell pm grant --user 0 com.mentoster.flip7anglelab android.permission.READ_LOGS",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                when {
                    pairingEndpoint != null -> "Pairing service found on port ${pairingEndpoint?.port}"
                    connectionEndpoint != null -> "ADB service found on port ${connectionEndpoint?.port}"
                    else -> "Waiting for Wireless debugging"
                },
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(6) },
                label = { Text("Pairing code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = pairingCode.length == 6 && pairingEndpoint != null,
                    onClick = {
                        scope.launch {
                            samsungRuntime.pair(pairingCode).fold(
                                onSuccess = { pairingCode = "" },
                                onFailure = { message = it.message.orEmpty() },
                            )
                        }
                    },
                ) { Text("Pair") }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }) { Text("Developer options") }
            }
        }

        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun Float?.degrees(precision: Int): String =
    this?.let { String.format(Locale.US, "%.${precision}f°", it) } ?: "—"

/**
 * Live side-view schematic of the actual clamshell angle:
 * 0° = halves together, 90° = perpendicular, 180° = flat.
 * Renders the actual measured/estimated hinge angle without simulated screen effects.
 */
@Composable
private fun HingeAnglePreview(angleDegrees: Float?) {
    val accent = MaterialTheme.colorScheme.primary
    val fixed = MaterialTheme.colorScheme.onSurface
    val guide = MaterialTheme.colorScheme.outlineVariant
    // Reflect the latest sensor estimate on the next Compose frame without
    // the previous 90 ms tween lag when opening or closing the hinge.
    val animatedAngle = (angleDegrees ?: 180f).coerceIn(0f, 180f)

    Box(
        modifier = Modifier.fillMaxWidth().height(210.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val hinge = Offset(size.width / 2f, size.height * 0.76f)
            val armLength = min(size.width * 0.40f, size.height * 0.65f)
            val radians = Math.toRadians(animatedAngle.toDouble())
            val end = Offset(
                hinge.x - cos(radians).toFloat() * armLength,
                hinge.y - sin(radians).toFloat() * armLength,
            )
            val fixedEnd = Offset(hinge.x - armLength, hinge.y)
            val stroke = 11.dp.toPx()

            // Static reference line makes the angle readable even at 0° and 180°.
            drawLine(
                color = guide,
                start = hinge,
                end = Offset(hinge.x + armLength, hinge.y),
                strokeWidth = 1.5.dp.toPx(),
            )
            drawLine(
                color = fixed,
                start = fixedEnd,
                end = hinge,
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            if (angleDegrees != null) {
                drawLine(
                    color = accent,
                    start = hinge,
                    end = end,
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            drawCircle(color = fixed, radius = 9.dp.toPx(), center = hinge)
            drawCircle(color = accent, radius = 4.dp.toPx(), center = hinge)
        }
    }
    Text(
        "Side view: 0° closed · 90° half open · 180° fully open",
        style = MaterialTheme.typography.labelSmall,
    )
}
