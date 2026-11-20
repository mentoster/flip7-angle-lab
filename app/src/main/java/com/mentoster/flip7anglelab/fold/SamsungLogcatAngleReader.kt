package com.mentoster.flip7anglelab.fold

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Reads Samsung's own SensorsHub FoldingAngle telemetry from logcat.
 *
 * Galaxy Z Flip7 logs the private 65686 folding-angle sensor as:
 *   I/SensorsHub: FoldingAngle: event = 123, time = ...
 *
 * READ_LOGS is a development permission on the verified firmware, so it can be granted
 * once from USB ADB and then used without Wireless debugging or a local ADB transport.
 */
class SamsungLogcatAngleReader {
    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var process: Process? = null

    val isRunning: Boolean get() = worker?.isAlive == true

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun start(
        context: Context,
        onAngle: (Float, Long) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        if (worker?.isAlive == true) return true
        if (!hasPermission(context)) return false

        val thread = Thread({
            try {
                readBootstrapAngle()?.let { event ->
                    // Preserve the ORIGINAL sensor-event timestamp from the
                    // system log. A historical bootstrap angle must not be
                    // mislabeled as a fresh training observation just
                    // because the app was launched now.
                    onAngle(event.angle, event.timestampNanos ?: 0L)
                }
                streamAngles(onAngle)
            } catch (error: Throwable) {
                if (!Thread.currentThread().isInterrupted) onFailure(error)
            } finally {
                process?.destroy()
                process = null
                worker = null
            }
        }, "Flip7AngleLab-sensorshub-logcat")
        worker = thread
        thread.start()
        return true
    }

    fun stop() {
        worker?.interrupt()
        process?.destroy()
        process = null
        worker = null
    }

    private fun readBootstrapAngle(): SamsungSensorsHubAngleParser.Event? {
        val bootstrap = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "brief",
            "--regex=$FOLDING_ANGLE_MARKER",
            "$SENSORSHUB_TAG:I",
            "*:S",
        ).redirectErrorStream(true).start()

        var lastAngle: SamsungSensorsHubAngleParser.Event? = null
        BufferedReader(InputStreamReader(bootstrap.inputStream)).useLines { lines ->
            lines.forEach { line ->
                SamsungSensorsHubAngleParser.parseEvent(line)?.let { lastAngle = it }
            }
        }
        val exitCode = bootstrap.waitFor()
        if (exitCode != 0) error("logcat bootstrap exited with code $exitCode")
        return lastAngle
    }

    private fun streamAngles(onAngle: (Float, Long) -> Unit) {
        val live = ProcessBuilder(
            "logcat",
            "-v",
            "brief",
            "-T",
            "1",
            "--regex=$FOLDING_ANGLE_MARKER",
            "$SENSORSHUB_TAG:I",
            "*:S",
        ).redirectErrorStream(true).start()
        process = live

        BufferedReader(InputStreamReader(live.inputStream)).useLines { lines ->
            lines.forEach { line ->
                if (Thread.currentThread().isInterrupted) return
                val event = SamsungSensorsHubAngleParser.parseEvent(line) ?: return@forEach
                onAngle(event.angle, event.timestampNanos ?: SystemClock.elapsedRealtimeNanos())
            }
        }
        if (!Thread.currentThread().isInterrupted) {
            error("logcat stream exited with code ${live.waitFor()}")
        }
    }

    private companion object {
        const val SENSORSHUB_TAG = "SensorsHub"
        const val FOLDING_ANGLE_MARKER = "FoldingAngle: event ="
    }
}

object SamsungSensorsHubAngleParser {
    data class Event(val angle: Float, val timestampNanos: Long?)

    private val angleRegex = Regex("""FoldingAngle:\s*event\s*=\s*(-?\d+(?:\.\d+)?)""")
    private val timestampRegex = Regex("""\btime\s*=\s*(\d+)""")

    fun parseEvent(line: String): Event? {
        val angle = angleRegex.find(line)?.groupValues?.getOrNull(1)
            ?.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 180f) ?: return null
        val timestamp = timestampRegex.find(line)?.groupValues?.getOrNull(1)
            ?.toLongOrNull()?.takeIf { it > 0L }
        return Event(angle, timestamp)
    }

    fun parse(line: String): Float? = parseEvent(line)?.angle
}
