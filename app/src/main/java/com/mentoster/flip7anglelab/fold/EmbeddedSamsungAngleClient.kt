package com.mentoster.flip7anglelab.fold

import android.os.SystemClock
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import java.io.Closeable

/**
 * Galaxy Z Flip7 fallback: read Samsung's factory ref_angle through a local ADB
 * shell. No screen capture, Fold wallpaper services, or UI hooks are involved.
 */
class EmbeddedSamsungAngleClient(
    private val onStatus: (String) -> Unit,
    private val onAngle: (angle: Float, timestampNanos: Long) -> Unit,
) : Closeable {
    @Volatile
    private var session: Session? = null

    val isConnected: Boolean get() = session != null

    fun shell(command: String): Result<String> {
        val current = session ?: return Result.failure(IllegalStateException("ADB is not connected"))
        return runCatching { current.control.shell(command).allOutput }
    }

    fun connect(host: String, port: Int) {
        close()
        onStatus("Connecting to Flip7 over local ADB…")
        val adb = Kadb.create(host, port, connectTimeout = 5_000)
        var control: Kadb? = null
        var stream: AdbStream? = null
        try {
            control = Kadb.create(host, port, connectTimeout = 5_000)
            val hasRefAngle = control.shell(
                "test -r $REF_ANGLE_PATH && echo yes || echo no",
            ).allOutput.trim() == "yes"
            check(hasRefAngle) { "Flip7 ref_angle sensor is unavailable over ADB" }

            stream = adb.open("shell:$REF_ANGLE_COMMAND")
            val active = Session(adb, control, stream)
            session = active
            Thread({ readAngles(active) }, "Flip7AngleLab-adb-angle").start()
            onStatus("Flip7 exact angle · local ADB ref_angle")
        } catch (error: Throwable) {
            runCatching { stream?.close() }
            runCatching { control?.close() }
            runCatching { adb.close() }
            throw error
        }
    }

    override fun close() {
        val current = session ?: return
        session = null
        current.close()
    }

    private fun readAngles(current: Session) {
        try {
            while (session === current) {
                val line = current.stream.source.readUtf8Line() ?: error("ref_angle stream stopped")
                val angle = FlipRefAngleParser.parse(line) ?: continue
                onAngle(angle, SystemClock.elapsedRealtimeNanos())
            }
        } catch (error: Throwable) {
            if (session === current) {
                session = null
                onStatus("Flip7 angle disconnected: ${error.message ?: error.javaClass.simpleName}")
            }
        } finally {
            current.close()
        }
    }

    private class Session(
        val adb: Kadb,
        val control: Kadb,
        val stream: AdbStream,
    ) {
        fun close() {
            runCatching { stream.close() }
            runCatching { control.close() }
            runCatching { adb.close() }
        }
    }

    private companion object {
        const val REF_ANGLE_PATH = "/sys/class/sensors/sub_accelerometer_sensor/ref_angle"
        const val REF_ANGLE_COMMAND =
            "CLASSPATH=${'$'}(pm path com.mentoster.flip7anglelab | head -n 1 | cut -d: -f2) " +
                "exec app_process /system/bin com.mentoster.flip7anglelab.fold.FlipRefAngleBridge"
    }
}

object FlipRefAngleParser {
    /** Factory payload is `angle,flags`, e.g. `178,0`. */
    fun parse(line: String): Float? = line
        .substringBefore(',')
        .trim()
        .toFloatOrNull()
        ?.takeIf(Float::isFinite)
        ?.coerceIn(0f, 180f)
}
