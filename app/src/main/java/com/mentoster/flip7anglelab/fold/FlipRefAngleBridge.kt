package com.mentoster.flip7anglelab.fold

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/**
 * Runs as uid=shell through app_process and polls Samsung's Flip ref_angle sysfs node.
 *
 * Keeping the sysfs fd open avoids spawning `cat`/`sleep` processes for every sample.
 * We poll at 120 Hz but only write to the ADB pipe when the angle actually changes.
 */
object FlipRefAngleBridge {
    @JvmStatic
    fun main(args: Array<String>) {
        val fd = FlipRefAnglePoller.open()
        try {
            FlipRefAnglePoller.run(fd) { angle ->
                    System.out.println(angle)
                    System.out.flush()
            }
        } finally {
            Os.close(fd)
        }
    }
}

/**
 * Same low-allocation sysfs poller, but executed in the normal application process.
 *
 * Keep this as an opportunistic fast path for firmware that permits direct access. On the
 * verified Flip7 build, normal-app traversal of the sysfs sensor directory is blocked by
 * SELinux even though the final ref_angle node's Unix mode bits look readable, so runtime
 * falls through to the READ_LOGS or shell transport instead.
 */
class DirectFlipRefAngleReader {
    @Volatile
    private var worker: Thread? = null

    fun start(
        onAngle: (Int) -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        if (worker?.isAlive == true) return true
        val fd = try {
            FlipRefAnglePoller.open()
        } catch (_: Throwable) {
            return false
        }

        val thread = Thread({
            try {
                FlipRefAnglePoller.run(fd, onAngle)
            } catch (error: Throwable) {
                onFailure(error)
            } finally {
                runCatching { Os.close(fd) }
                worker = null
            }
        }, "Flip7AngleLab-direct-ref-angle")
        worker = thread
        thread.start()
        return true
    }

    fun stop() {
        worker?.interrupt()
        worker = null
    }
}

internal object FlipRefAnglePoller {
    fun open(): FileDescriptor = Os.open(REF_ANGLE_PATH, OsConstants.O_RDONLY, 0)

    fun run(fd: FileDescriptor, onAngle: (Int) -> Unit) {
        val buffer = ByteArray(32)
        var nextTickNanos = System.nanoTime()
        var lastChangeNanos = nextTickNanos
        var lastAngle: Int? = null
        var motionAnchor: Int? = null

        while (!Thread.currentThread().isInterrupted) {
            Os.lseek(fd, 0L, OsConstants.SEEK_SET)
            val count = Os.read(fd, buffer, 0, buffer.size)
            val angle = parseAngle(buffer, count)?.let(::normalizeEdgeAngle)

            if (angle != null && angle != lastAngle) {
                val now = System.nanoTime()
                val anchor = motionAnchor
                if (anchor == null || abs(angle - anchor) >= MOTION_THRESHOLD_DEGREES) {
                    lastChangeNanos = now
                    motionAnchor = angle
                }
                onAngle(angle)
                lastAngle = angle
            }

            val now = System.nanoTime()
            val samplePeriodNanos = if (now - lastChangeNanos <= ACTIVE_WINDOW_NANOS) {
                ACTIVE_SAMPLE_PERIOD_NANOS
            } else {
                IDLE_SAMPLE_PERIOD_NANOS
            }
            nextTickNanos += samplePeriodNanos
            if (nextTickNanos <= now) {
                // Do not burst-read trying to catch up after the process was descheduled.
                nextTickNanos = now + samplePeriodNanos
            }
            LockSupport.parkNanos((nextTickNanos - now).coerceAtLeast(1L))
        }
    }

    private fun parseAngle(bytes: ByteArray, count: Int): Int? {
        if (count <= 0) return null
        var value = 0
        var digits = 0
        var index = 0
        while (index < count) {
            val byte = bytes[index].toInt()
            if (byte in '0'.code..'9'.code) {
                value = value * 10 + (byte - '0'.code)
                digits += 1
            } else if (digits > 0) {
                break
            }
            index += 1
        }
        return if (digits > 0) value.coerceIn(0, 180) else null
    }

    private fun normalizeEdgeAngle(angle: Int): Int = when {
        angle >= OPEN_SNAP_DEGREES -> 180
        angle <= CLOSED_SNAP_DEGREES -> 0
        else -> angle
    }

    const val REF_ANGLE_PATH = "/sys/class/sensors/sub_accelerometer_sensor/ref_angle"
    private const val ACTIVE_SAMPLE_RATE_HZ = 120L
    private const val IDLE_SAMPLE_RATE_HZ = 30L
    private const val ACTIVE_SAMPLE_PERIOD_NANOS = 1_000_000_000L / ACTIVE_SAMPLE_RATE_HZ
    private const val IDLE_SAMPLE_PERIOD_NANOS = 1_000_000_000L / IDLE_SAMPLE_RATE_HZ
    private const val ACTIVE_WINDOW_NANOS = 500_000_000L
    private const val MOTION_THRESHOLD_DEGREES = 2
    private const val OPEN_SNAP_DEGREES = 177
    private const val CLOSED_SNAP_DEGREES = 3
}
