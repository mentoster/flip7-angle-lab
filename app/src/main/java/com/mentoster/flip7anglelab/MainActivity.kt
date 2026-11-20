package com.mentoster.flip7anglelab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.mentoster.flip7anglelab.fold.DirectSensorLab
import com.mentoster.flip7anglelab.fold.SamsungAdbAngleRuntime
import com.mentoster.flip7anglelab.ui.AngleLabScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocalNetworkPermissionIfNeeded()
        enableEdgeToEdge()
        // Keep the live sensor laboratory visible while the activity is in
        // use. Android still switches the inner display off when folded.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme {
                AngleLabScreen(
                    samsungRuntime = SamsungAdbAngleRuntime,
                    autoStartCalibration = intent.getBooleanExtra(EXTRA_START_CALIBRATION, false),
                )
            }
        }
    }

    private fun requestLocalNetworkPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 37 &&
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), LOCAL_NETWORK_REQUEST)
        }
    }

    override fun onStop() {
        DirectSensorLab.saveLearnedCalibration()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        DirectSensorLab.onForeground()
        SamsungAdbAngleRuntime.onForeground()
    }

    override fun onPause() {
        DirectSensorLab.onBackground()
        super.onPause()
    }

    private companion object {
        const val LOCAL_NETWORK_REQUEST = 37
        const val EXTRA_START_CALIBRATION = "start_calibration"
    }
}
