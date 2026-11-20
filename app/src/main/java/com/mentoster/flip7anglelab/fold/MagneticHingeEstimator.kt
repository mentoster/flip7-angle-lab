package com.mentoster.flip7anglelab.fold

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Zero-setup Galaxy Z Flip7 hinge estimate from TYPE_MAGNETIC_FIELD_UNCALIBRATED.
 *
 * Calibration v2 uses a compact gravity-aware MLP trained from the 2026-09-26
 * SM-F766B master capture. The older raw-XYZ RBF-SVR remains a fallback when a
 * fresh gravity sample or public hinge state is unavailable. Estimated mode is
 * never a replacement for Samsung exact angle when the privileged bridge exists.
 */
internal class MagneticHingeEstimator {
    private var pretrainedBoost: Flip7HingeBoostModel? = null

    fun usePretrainedModel(model: Flip7HingeBoostModel) {
        pretrainedBoost = model
        reset()
    }

    internal var lastRawPrediction: Float? = null
        private set
    internal var lastPredictionSource: String = "unavailable"
        private set
    private var filteredAngle: Float? = null
    private var lastTimestampNanos = 0L
    private var gravityTimestampNanos = 0L
    private var gravityX = Float.NaN
    private var gravityY = Float.NaN
    private var gravityZ = Float.NaN
    private var openEndpointSinceNanos = 0L

    fun reset() {
        lastRawPrediction = null
        lastPredictionSource = "unavailable"
        filteredAngle = null
        lastTimestampNanos = 0L
        gravityTimestampNanos = 0L
        gravityX = Float.NaN
        gravityY = Float.NaN
        gravityZ = Float.NaN
        openEndpointSinceNanos = 0L
    }

    fun updateGravity(timestampNanos: Long, x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        gravityTimestampNanos = timestampNanos
        gravityX = x
        gravityY = y
        gravityZ = z
    }

    fun update(
        timestampNanos: Long,
        xMicroTesla: Float,
        yMicroTesla: Float,
        zMicroTesla: Float,
        publicHingeDegrees: Float?,
    ): Float? {
        if (!xMicroTesla.isFinite() || !yMicroTesla.isFinite() || !zMicroTesla.isFinite()) return null

        val coarseHinge = publicHingeDegrees?.takeIf(Float::isFinite)
        val hasFreshGravity = gravityTimestampNanos > 0L &&
            abs(timestampNanos - gravityTimestampNanos) <= GRAVITY_MAX_AGE_NANOS
        // Do not let the legacy XYZ-only SVR seed the live filter on a fresh
        // install/restart before public hinge + gravity arrive. On the real
        // Flip7 it predicted ~24–31° while the hinge was physically 179°,
        // and the rotation guard subsequently latched onto that bad value.
        if (pretrainedBoost != null && (!hasFreshGravity || coarseHinge == null)) {
            lastRawPrediction = null
            lastPredictionSource = "waiting-for-sensors"
            filteredAngle = null
            lastTimestampNanos = 0L
            openEndpointSinceNanos = 0L
            return null
        }
        val boostTarget = if (hasFreshGravity && coarseHinge != null) {
            pretrainedBoost?.predict(
                xMicroTesla, yMicroTesla, zMicroTesla,
                gravityX, gravityY, gravityZ, coarseHinge,
            ) ?: Float.NaN
        } else {
            Float.NaN
        }
        val mlpTarget = if (!boostTarget.isFinite() && hasFreshGravity && coarseHinge != null) {
            Flip7MagneticMlpModel.predict(
                xMicroTesla,
                yMicroTesla,
                zMicroTesla,
                gravityX,
                gravityY,
                gravityZ,
                coarseHinge,
            )
        } else {
            Float.NaN
        }
        lastPredictionSource = when {
            boostTarget.isFinite() -> "Boost"
            mlpTarget.isFinite() -> "MLP"
            else -> "SVR"
        }
        var target = (boostTarget.takeIf(Float::isFinite)
            ?: mlpTarget.takeIf(Float::isFinite)
            ?: Flip7MagneticSvrModel.predict(xMicroTesla, yMicroTesla, zMicroTesla))
            .coerceIn(0f, 180f)
        lastRawPrediction = target

        val magneticNorm = sqrt(
            xMicroTesla * xMicroTesla +
                yMicroTesla * yMicroTesla +
                zMicroTesla * zMicroTesla,
        )
        if (publicHingeDegrees != null && publicHingeDegrees <= 45f && magneticNorm >= CLOSED_FIELD_THRESHOLD_UT) {
            openEndpointSinceNanos = 0L
            filteredAngle = 0f
            lastTimestampNanos = timestampNanos
            return 0f
        }

        target = constrainWithPublicHinge(target, publicHingeDegrees)
        val previous = filteredAngle
        if (previous == null || lastTimestampNanos <= 0L || timestampNanos <= lastTimestampNanos) {
            val initial = assistOpenEndpoint(
                timestampNanos = timestampNanos,
                estimate = snapEndpoint(target, publicHingeDegrees),
                modelTarget = target,
                publicAngle = publicHingeDegrees,
            )
            filteredAngle = initial
            lastTimestampNanos = timestampNanos
            return initial
        }

        val dtSeconds = ((timestampNanos - lastTimestampNanos) / 1_000_000_000.0).toFloat()
            .coerceIn(0f, 0.1f)
        lastTimestampNanos = timestampNanos

        // Physical slew guard prevents an occasional magnetic outlier from producing a shader jump,
        // while still allowing a very fast hand fold without perceptible lag.
        val maxStep = max(3.5f, dtSeconds * 900f)
        val limited = previous + (target - previous).coerceIn(-maxStep, maxStep)
        val alpha = if (abs(limited - previous) >= 8f) 0.55f else 0.32f
        val smoothed = previous + (limited - previous) * alpha
        val output = assistOpenEndpoint(
            timestampNanos = timestampNanos,
            estimate = snapEndpoint(smoothed, publicHingeDegrees),
            modelTarget = target,
            publicAngle = publicHingeDegrees,
        )
        filteredAngle = output
        return output
    }

    private fun assistOpenEndpoint(
        timestampNanos: Long,
        estimate: Float,
        modelTarget: Float,
        publicAngle: Float?,
    ): Float {
        val candidate = publicAngle != null &&
            publicAngle >= OPEN_PUBLIC_THRESHOLD_DEGREES &&
            modelTarget >= OPEN_MODEL_THRESHOLD_DEGREES
        if (!candidate) {
            openEndpointSinceNanos = 0L
            return estimate
        }

        if (openEndpointSinceNanos <= 0L || timestampNanos < openEndpointSinceNanos) {
            openEndpointSinceNanos = timestampNanos
            return estimate
        }

        val dwellNanos = timestampNanos - openEndpointSinceNanos
        return when {
            dwellNanos >= OPEN_ENDPOINT_SECOND_STAGE_NANOS -> max(estimate, OPEN_ENDPOINT_SECOND_STAGE_DEGREES)
            dwellNanos >= OPEN_ENDPOINT_FIRST_STAGE_NANOS -> max(estimate, OPEN_ENDPOINT_FIRST_STAGE_DEGREES)
            else -> estimate
        }
    }

    private fun constrainWithPublicHinge(estimate: Float, publicAngle: Float?): Float {
        val coarse = publicAngle?.takeIf(Float::isFinite) ?: return estimate
        return when {
            coarse >= 135f -> estimate.coerceIn(145f, 180f)
            coarse <= 45f -> estimate.coerceIn(0f, 35f)
            else -> estimate.coerceIn(15f, 170f)
        }
    }

    private fun snapEndpoint(estimate: Float, publicAngle: Float?): Float = when {
        publicAngle != null && publicAngle >= 135f && estimate >= 176f -> 180f
        publicAngle != null && publicAngle <= 45f && estimate <= 5f -> 0f
        else -> estimate
    }

    private companion object {
        const val CLOSED_FIELD_THRESHOLD_UT = 3_000f
        const val GRAVITY_MAX_AGE_NANOS = 100_000_000L
        const val OPEN_PUBLIC_THRESHOLD_DEGREES = 179f
        const val OPEN_MODEL_THRESHOLD_DEGREES = 160f
        const val OPEN_ENDPOINT_FIRST_STAGE_NANOS = 100_000_000L
        const val OPEN_ENDPOINT_SECOND_STAGE_NANOS = 300_000_000L
        const val OPEN_ENDPOINT_FIRST_STAGE_DEGREES = 175f
        const val OPEN_ENDPOINT_SECOND_STAGE_DEGREES = 177f
    }
}

internal object Flip7MagneticMlpModel {
    private val featureMean = floatArrayOf(
        250.950637f, 194.641426f, -231.930051f, -38.8120685f, 414.649282f, 88.6384507f,
    )
    private val featureScale = floatArrayOf(
        637.826442f, 73.3128587f, 275.668164f, 502.661592f, 459.032049f, 48.4509469f,
    )
    private val hiddenWeights = floatArrayOf(
        29.347327f, -0.3791682f, -2.1272395f, 1.1400000f, -13.227672f, -0.8763579f, 6.312728f, -3.096065f,
        5.6974163f, 0.0351908f, -4.3558936f, 0.2603464f, -2.4159370f, 0.6228209f, 2.5405336f, 1.2596478f,
        14.914469f, 0.1779079f, 41.342266f, 0.0807530f, -10.399099f, -5.216910f, 4.536751f, -2.226055f,
        5.558944f, -0.0000033f, -10.244727f, 0.7255775f, 1.1935402f, 1.0306844f, 0.8002607f, 0.0696494f,
        9.615437f, -0.1028856f, -6.6167693f, 0.5077674f, -5.8241377f, -0.4980853f, -0.6810975f, -3.0675611f,
        3.6880877f, 5.2722570f, -8.816454f, -1.7436153f, 12.131978f, 4.2837663f, -9.954528f, -1.7348819f,
    )
    private val hiddenBias = floatArrayOf(
        -10.328495f, 9.949399f, 11.686054f, 13.534000f,
        22.730534f, -2.015172f, -26.522972f, -14.088607f,
    )
    private val outputWeights = floatArrayOf(
        -1.2759309f, 11.907170f, -18.461336f, 4.4171605f,
        -0.9275917f, 50.105590f, -1.9900286f, -23.853659f,
    )
    private const val outputBias = 56.067383f

    /**
     * Calibration-v2 model trained on the 2026-09-26 SM-F766B master capture.
     * Features: raw magnetic XYZ, magnetic projection onto gravity,
     * gravity-perpendicular magnetic magnitude, and public coarse hinge angle.
     * Architecture: 6 -> 8 tanh -> 1. Leave-one-orientation-out validation:
     * about 8.98 degree mean MAE and 24.72 degree mean p95.
     */
    fun predict(
        x: Float,
        y: Float,
        z: Float,
        gravityX: Float,
        gravityY: Float,
        gravityZ: Float,
        publicHinge: Float,
    ): Float {
        val gravityNorm = sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ)
        if (gravityNorm < 0.1f) return Float.NaN
        val magneticNormSquared = x * x + y * y + z * z
        val magneticDotGravity =
            (x * gravityX + y * gravityY + z * gravityZ) / gravityNorm
        val magneticPerpendicular = sqrt(
            max(0f, magneticNormSquared - magneticDotGravity * magneticDotGravity),
        )
        val raw = floatArrayOf(x, y, z, magneticDotGravity, magneticPerpendicular, publicHinge)

        var output = outputBias
        for (hidden in 0 until HIDDEN_COUNT) {
            var activation = hiddenBias[hidden]
            for (feature in 0 until FEATURE_COUNT) {
                val scaled = (raw[feature] - featureMean[feature]) / featureScale[feature]
                activation += scaled * hiddenWeights[feature * HIDDEN_COUNT + hidden]
            }
            output += tanh(activation.toDouble()).toFloat() * outputWeights[hidden]
        }
        return output
    }

    private const val FEATURE_COUNT = 6
    private const val HIDDEN_COUNT = 8
}

internal object Flip7MagneticSvrModel {
    private data class Model(
        val supportCount: Int,
        val featureMean: FloatArray,
        val featureScale: FloatArray,
        val gamma: Float,
        val intercept: Float,
        val supportVectors: FloatArray,
        val dualCoefficients: FloatArray,
    )

    private val model: Model by lazy(LazyThreadSafetyMode.PUBLICATION) { decodeModel() }

    fun predict(x: Float, y: Float, z: Float): Float {
        val data = model
        val sx = (x - data.featureMean[0]) / data.featureScale[0]
        val sy = (y - data.featureMean[1]) / data.featureScale[1]
        val sz = (z - data.featureMean[2]) / data.featureScale[2]

        var sum = data.intercept.toDouble()
        for (index in 0 until data.supportCount) {
            val offset = index * FEATURE_COUNT
            val dx = sx - data.supportVectors[offset]
            val dy = sy - data.supportVectors[offset + 1]
            val dz = sz - data.supportVectors[offset + 2]
            val squaredDistance = dx * dx + dy * dy + dz * dz
            val kernel = exp(-data.gamma * squaredDistance.toDouble())
            sum += data.dualCoefficients[index] * kernel
        }
        return sum.toFloat()
    }

    private fun decodeModel(): Model {
        val bytes = MODEL_HEX_FIXED.hexToBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val supportCount = buffer.int
        require(supportCount > 0)
        val mean = FloatArray(FEATURE_COUNT) { buffer.float }
        val scale = FloatArray(FEATURE_COUNT) { buffer.float }
        val gamma = buffer.float
        val intercept = buffer.float
        val support = FloatArray(supportCount * FEATURE_COUNT) { buffer.float }
        val dual = FloatArray(supportCount) { buffer.float }
        require(!buffer.hasRemaining())
        return Model(supportCount, mean, scale, gamma, intercept, support, dual)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private const val FEATURE_COUNT = 3

    // sklearn StandardScaler + RBF SVR(C=100, epsilon=1.5, gamma="scale").
    // Payload: little-endian [supportCount:u32][mean:3f][scale:3f][gamma:f][intercept:f]
    //          [supportVectors:supportCount*3f][dualCoefficients:supportCount*f].
    private const val MODEL_HEX_FIXED =
        "7800000070d151433bc83a43bcb681c322eaf243f5413c42d8219d42abaaaa3e42af2742339d02406cd2713f287acc3f64ca8d3f296509407fe7b93f" +
        "ad14c4be89488a3dbe34dc3d05d9c2be0b80833e893ee33dfe7fc2be4a771c3d6ce8743df52cc4bedc172abbf1db243b2ba5c9be99f0b2bdb6f19bbd" +
        "cb87cabe56a1e0bd5d0d10be7928d0be619182beda738cbe27c9d5bed396cfbeeb70b8be5e38d8be26e7c0be7471d2bec7abd6be45e9b0bee024e8be" +
        "4d92dbbe388df0be158a02bfe51eddbea7fb05bf1da90ebf64a3debeb71a08bfafec18bf1c91e0be134d16bfc52a31bfd381e3be13982fbfb7d248bf" +
        "f2e5e4be2c7b44bfb5ac60bfc2d5e4beaabe47bfd01871bf339fe2beab9138bff5b580bf3499e0be672435bfec888abf0486dfbee12b24bf9f4893bf" +
        "f4d6dfbe13982fbf02c59ebfe424dfbe862625bf2044a3bf214a72bda9ab973f96ad723f508a66be50953e3f54f1253fa9fca0bee03fdd3e758bcf3e" +
        "6eead8beea1c5ebd0b9999bdd9e6e7be4862aabeb5ca97be0800ebbe44cba6beb4b7a3be4e2df2bedcd034bf156a26bfcfa5efbe27c636bf944f3cbf" +
        "9f8fedbef5b349bfa68b50bfcfa5efbeba2863bf6f886fbfc0edecbe753361bfa67385bfb038ebbe9a0869bf962e91bf30368a40429f5ec0cf9c7340" +
        "db6c6b40ff2709c02c4d3840f1afb7bed4513b3ffdc0bd3e1de7c4be2cfd1b3f279c743e89d7cfbe0b15e53e92b8ab3d0768d5be2a81a23ec1bc5ebd" +
        "b605dabe366c443e097d0ebee421debe409cbb3cf0c090bea9d3e6be044c1ebfe2943dbfc2d8e5bea76429bfc1de60bfa274e4be35f43dbf2c7b7abf" +
        "a274e4bea02744bf942885bf13bc47407367b0bf83cbe53fa6be1c4044b31cbe30b5ac3f811a283f97491440d541b33fd7a10c3e90ddf73f7df4a13f" +
        "fadbb6bd4334b93fdbad7f3f5f4f8cbe7f00793fa3301b3f3bf2e0be95788abd3cc3a1be4ba4e1beb93234be854a00bf5203e4bee34caebef4fd15bf" +
        "22f6e4bea548cebe802820bf62b5e4be9f48cebe73e127bf93c2e3bead57fabe040352bf5300e3be02100abf901e70bf339fe2be5c511dbf04eb86bf" +
        "9efad8be5b8d0f3e0c7632bee615dabe89488a3d34ce85bec5bddcbef58d86bd616c96be95b0ddbe6b76e8bd4c14aebe0486dfbeffbd1abecbf9c3be" +
        "a36be1bebfb48bbe448bf9bed37ee2be7f28d4be167d1abfeb86e2be87db0bbfe29239bf7361e3be0dc517bf944050bf735ee2becfdf12bf891258bf" +
        "b31de2be5d6f27bfd82f6dbf6b02e1be45aa1cbf7dc07bbf24e7dfbec78423bff7b682bf1432debe5229b8bd8f0eafbee427e0bed62427be180fc9be" +
        "d478e0be817946be16f8ccbe1438e0be2f475fbeed56e8be3499e0be1b9c80be3055f1be0be2e0bedc2ea4beff2b0ebf93bfe2bec8e1c1beb80315bf" +
        "830de2bea8c1c7be95711cbf8407e0be119adebe00ff49bf5c4ddfbe189900bf502067bfc4c3debec1b10bbf1d327ebf96a1d8beb34041be6c6dcabe" +
        "a656dabecf9c4fbed81cd8bef5c7dabe2f475fbe14d2e4be057adbbeb7b869be7981f2be453cdcbe3e2a74be0cbff0be24e7dfbe25c9b6be80370cbf" +
        "7bb4e1be7f801cbf3ee656bf53fde1bed97616bfb7c35cbf735be1bed86711bf3fea5ebf830de2be0ee321bfe4616dbfacc1e0bee23a29bfb08678bf" +
        "133be1bedbb22abfdd5282bf8407e0be068832bfca0986bf0ecad8be537e05bfdeb011bf0674d9be1c04fabe3b260fbf8401debe3b1319bfe8cb15bf" +
        "25dbdbbed8580cbfc75019bff6c4d9bed70ef8be46451bbf0677dabe557e05bfc02221bf1623d9be7072f5bea2a724bf7646dabec09301bf945e28bf" +
        "b605dabe324f06bfb6f62cbf6df0dabeceb203bf441f33bf55e8dabe223004bfabaf34bfd472debefac31fbfd0493dbf3493debe57ba19bf430c3fbf" +
        "4d98ddbe5d240ebff2c841bfb50bdcbe9e820cbf8f3a44bf55ebdbbe234e0ebfdb6849bf358ddcbef99610bfffeb55bf659ddcbe981818bf88ff63bf" +
        "754cdcbe258a22bf05ce7dbf7646dabe09012cbf8afc90bfc7aed7be672435bfb07f9dbf5ddb5dc172a8183e0000c8420000c8420000c8420000c842" +
        "0000c8420000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c2" +
        "0000c8c20000c8c20000c8c20000c8c20000c8c254b5b0420000c8c20000c8c2f7187941eaaeb4410000c8420000c8420000c8420000c8420000c842" +
        "191703c2ff1654c148d70bc20000c8c20000c8c20000c8c20000c8c20000c8c20000c8420000c8420000c8420000c8427d02b7c1787b18c19a6a16c2" +
        "6ee11141dbd0be420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c842" +
        "0000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c842b3a284c20000c8420000c842" +
        "0000c8420000c8420000c8420000c8420000c8420000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8420000c8420000c8420000c842" +
        "0000c8420000c8420000c8420000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c2" +
        "0000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c2"
    private const val MODEL_HEX = "7800000070d151433bc83a43bcb681c322eaf243f5413c42d8219d42abaaaa3e42af2742339d02406cd2713f287acc3f64ca8d3f296509407fe7b93fad14c4be89488a3dbe34dc3d05d9c2be0b80833e893ee33dfe7fc2be4a771c3d6ce8743df52cc4bedc172abbf1db243b2ba5c9be99f0b2bdb6f19bbdcb87cabe56a1e0bd5d0d10be7928d0be619182beda738cbe27c9d5bed396cfbeeb70b8be5e38d8be26e7c0be7471d2bec7abd6be45e9b0bee024e8be4d92dbbe388df0be158a02bfe51eddbea7fb05bf1da90ebf64a3debeb71a08bfafec18bf1c91e0be134d16bfc52a31bfd381e3be13982fbfb7d248bff2e5e4be2c7b44bfb5ac60bfc2d5e4beaabe47bfd01871bf339fe2beab9138bff5b580bf3499e0be672435bfec888abf0486dfbee12b24bf9f4893bff4d6dfbe13982fbf02c59ebfe424dfbe862625bf2044a3bf214a72bda9ab973f96ad723f508a66be50953e3f54f1253fa9fca0bee03fdd3e758bcf3e6eead8beea1c5ebd0b9999bdd9e6e7be4862aabeb5ca97be0800ebbe44cba6beb4b7a3be4e2df2bedcd034bf156a26bfcfa5efbe27c636bf944f3cbf9f8fedbef5b349bfa68b50bfcfa5efbeba2863bf6f886fbfc0edecbe753361bfa67385bfb038ebbe9a0869bf962e91bf30368a40429f5ec0cf9c7340db6c6b40ff2709c02c4d3840f1afb7bed4513b3ffdc0bd3e1de7c4be2cfd1b3f279c743e89d7cfbe0b15e53e92b8ab3d0768d5be2a81a23ec1bc5ebdb605dabe366c443e097d0ebee421debe409cbb3cf0c090bea9d3e6be044c1ebfe2943dbfc2d8e5bea76429bfc1de60bfa274e4be35f43dbf2c7b7abfa274e4bea02744bf942885bf13bc47407367b0bf83cbe53fde1bed86711bf3fea5ebf830de2be0ee321bfe4616dbfacc1e0bee23a29bfb08678bf133be1bedbb22abfdd5282bf8407e0be068832bfca0986bf0ecad8be537e05bfdeb011bf0674d9be1c04fabe3b260fbf8401debe3b1319bfe8cb15bf25dbdbbed8580cbfc75019bff6c4d9bed70ef8be46451bbf0677dabe557e05bfc02221bf1623d9be7072f5bea2a724bf7646dabec09301bf945e28bfb605dabe324f06bfb6f62cbf6df0dabeceb203bf441f33bf55e8dabe223004bfabaf34bfd472debefac31fbfd0493dbf3493debe57ba19bf430c3fbf4d98ddbe5d240ebff2c841bfb50bdcbe9e820cbf8f3a44bf55ebdbbe234e0ebfdb6849bf358ddcbef99610bfffeb55bf659ddcbe981818bf88ff63bf754cdcbe258a22bf05ce7dbf7646dabe09012cbf8afc90bfc7aed7be672435bfb07f9dbf5ddb5dc172a8183e0000c8420000c8420000c8420000c8420000c8420000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c254b5b0420000c8c20000c8c2f7187941eaaeb4410000c8420000c8420000c8420000c8420000c842191703c2ff1654c148d70bc20000c8c20000c8c20000c8c20000c8c20000c8c20000c8420000c8420000c8420000c8427d02b7c1787b18c19a6a16c26ee11141dbd0be420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c842b3a284c20000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8420000c8420000c8420000c8420000c8420000c8420000c8420000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c20000c8c2"
}
