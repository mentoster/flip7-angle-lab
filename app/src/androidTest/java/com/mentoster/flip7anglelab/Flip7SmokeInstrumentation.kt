package com.mentoster.flip7anglelab.test

import android.app.Activity
import android.app.Instrumentation
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle

/**
 * Tiny platform-only hinge smoke runner. AndroidJUnitRunner is intentionally
 * avoided here because this host may need pure TCG (`-accel off`), where
 * loading the full AndroidX test stack can exceed the instrumentation startup
 * watchdog. This checks only the public hinge sensor; physical-angle accuracy
 * still requires independent testing on a Galaxy Z Flip7.
 */
class Flip7SmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val result = Bundle()
        try {
            val context = targetContext
            val sensorManager = context.getSystemService(SensorManager::class.java)
            val hinge = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
            checkNotNull(hinge) { "TYPE_HINGE_ANGLE is missing" }

            result.putString("stream", "PASS: hinge sensor=${hinge.name}\n")
            result.putString("smoke", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error::class.java.simpleName}: ${error.message}\n")
            result.putString("smoke", "FAIL")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
