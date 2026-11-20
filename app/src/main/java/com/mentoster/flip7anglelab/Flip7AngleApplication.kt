package com.mentoster.flip7anglelab

import android.app.Application
import com.mentoster.flip7anglelab.fold.DirectSensorLab
import com.mentoster.flip7anglelab.fold.SamsungAdbAngleRuntime

/** Initialize sensor recording and Samsung's exact-angle source once per process. */
class Flip7AngleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DirectSensorLab.init(this)
        SamsungAdbAngleRuntime.init(this)
    }
}
