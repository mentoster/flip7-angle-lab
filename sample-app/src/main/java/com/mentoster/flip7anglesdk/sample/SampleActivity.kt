package com.mentoster.flip7anglesdk.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import com.mentoster.flip7angle.sdk.Flip7HingeSensor
import com.mentoster.flip7angle.sdk.HingeAngleStatus
import java.util.Locale

/** Independent APK proving the library integrates without any privileged sensor. */
class SampleActivity : Activity() {
    private lateinit var sdk: Flip7HingeSensor
    private lateinit var display: TextView
    private var logged = 0L
    private var previousStatus: HingeAngleStatus? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        display = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(24, 40, 24, 40)
            text = "Waiting for public hinge and magnetic sensors..."
        }
        setContentView(display)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        sdk = Flip7HingeSensor(this)
    }

    override fun onStart() {
        super.onStart()
        val started = sdk.start { reading ->
            display.text = buildString {
                append("Flip7 Hinge SDK — separate APK\n\n")
                append("Angle: ")
                append(reading.angleDegrees?.let { String.format(Locale.US, "%.1f°", it) } ?: "—")
                append("\nRaw ML: ")
                append(reading.rawModelDegrees?.let { String.format(Locale.US, "%.1f°", it) } ?: "—")
                append("\nStatus: ").append(reading.status.name)
                append("\nCoarse hinge: ").append(reading.coarseHingeDegrees)
                append("\nReason: ").append(reading.reason)
                append("\nSM-F766 model tested: ").append(reading.testedOnDevice)
                append("\n\nADB is NOT used for inference.")
            }
            val statusChanged = previousStatus != reading.status
            previousStatus = reading.status
            if (++logged % 30L == 1L || statusChanged) {
                // Log only an aggregate status and angle for QA, no raw XYZ.
                Log.i("Flip7HingeSdkSample",
                    "angle=${reading.angleDegrees} status=${reading.status} " +
                    "raw=${reading.rawModelDegrees} coarse=${reading.coarseHingeDegrees}")
            }
        }
        if (!started) {
            display.text = "Required public sensors missing: ${sdk.availability}"
        }
    }

    override fun onStop() {
        sdk.stop()
        super.onStop()
    }
}
