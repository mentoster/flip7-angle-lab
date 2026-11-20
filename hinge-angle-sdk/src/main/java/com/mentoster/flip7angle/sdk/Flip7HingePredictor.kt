package com.mentoster.flip7angle.sdk

import android.content.Context
import com.mentoster.flip7anglelab.fold.Flip7HingeBoostModel
import java.io.InputStream

/**
 * Offline SM-F766B hinge-angle regression.
 *
 * No ADB, Samsung privileges, permissions, files, server or network access.
 * All seven inputs must come from one instant of ordinary Android sensors.
 * The public hinge value is a COARSE 0/90/180 posture, not physical truth.
 *
 * This API returns the RAW Boost estimate: it does not compensate for
 * rotations, magnetic interference, smoothing or the exact Samsung sensor.
 * Use [Flip7HingeSensor] when rotation awareness matters.
 */
class Flip7HingePredictor private constructor(
    private val model: Flip7HingeBoostModel,
) {
    val treeCount: Int get() = model.treeCount

    fun predict(
        magneticX: Float,
        magneticY: Float,
        magneticZ: Float,
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
        coarseHingeDegrees: Float,
    ): Float? = model.predict(
        magneticX, magneticY, magneticZ,
        gravityX, gravityY, gravityZ, coarseHingeDegrees,
    ).takeIf(Float::isFinite)

    companion object {
        const val MODEL_VERSION: String = "boost-v2"

        /** Loads the model asset embedded inside the AAR once. */
        @JvmStatic
        fun from(context: Context): Flip7HingePredictor =
            context.applicationContext.assets.open(Flip7HingeBoostModel.ASSET_NAME).use(::from)

        /** Useful for testing exported model binaries without any hardware. */
        @JvmStatic
        fun from(stream: InputStream): Flip7HingePredictor =
            Flip7HingePredictor(Flip7HingeBoostModel.load(stream))
    }
}
