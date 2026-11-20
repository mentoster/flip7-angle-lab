package com.mentoster.flip7anglelab.fold

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.InflaterInputStream
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A pre-trained, portable gradient-boosted tree ensemble.
 *
 * The packaged .b64 asset contains only split thresholds, tree edges and
 * leaf weights. It does NOT contain the 1,500 original labeled sensor rows.
 * The model runs with public Android sensors, offline, without ADB, Python
 * or a neural-network runtime. Validation is specific to one Galaxy Z Flip7.
 */
class Flip7HingeBoostModel private constructor(
    private val baseline: Double,
    private val trees: List<Array<Node>>,
) {
    private data class Node(
        val feature: Int,
        val left: Int,
        val right: Int,
        val threshold: Double,
        val value: Double,
    )

    val treeCount: Int get() = trees.size

    fun predict(
        x: Float,
        y: Float,
        z: Float,
        gx: Float,
        gy: Float,
        gz: Float,
        publicHinge: Float,
    ): Float {
        if (!listOf(x, y, z, gx, gy, gz, publicHinge).all(Float::isFinite)) {
            return Float.NaN
        }
        val gNorm = sqrt(
            gx.toDouble() * gx + gy.toDouble() * gy + gz.toDouble() * gz,
        )
        if (gNorm < 0.5) return Float.NaN
        val mNormSquared = x.toDouble() * x + y.toDouble() * y + z.toDouble() * z
        val mNorm = sqrt(mNormSquared)
        val projection = (x.toDouble() * gx + y.toDouble() * gy + z.toDouble() * gz) / gNorm
        val perpendicular = sqrt(max(0.0, mNormSquared - projection * projection))
        val features = doubleArrayOf(
            x.toDouble(), y.toDouble(), z.toDouble(),
            gx.toDouble(), gy.toDouble(), gz.toDouble(),
            publicHinge.toDouble(), mNorm, projection, perpendicular,
        )
        var prediction = baseline
        for (tree in trees) {
            var position = 0
            while (true) {
                val node = tree[position]
                if (node.feature == -1) {
                    prediction += node.value
                    break
                }
                position = if (features[node.feature] <= node.threshold) node.left else node.right
            }
        }
        return prediction.coerceIn(0.0, 180.0).toFloat()
    }

    companion object {
        const val ASSET_NAME = "flip7_hinge_boost_v2.b64"

        fun load(asset: InputStream): Flip7HingeBoostModel {
            val encoded = asset.bufferedReader(Charsets.US_ASCII).use { it.readText() }
                .filterNot(Char::isWhitespace)
            val compressed = Base64.getDecoder().decode(encoded)
            val data = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
            val reader = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4)
            reader.get(magic)
            require(magic.contentEquals(byteArrayOf(70, 55, 71, 66))) {
                "Unexpected pretrained model signature"
            }
            require(reader.int == 1) { "Unsupported pretrained model format" }
            require(reader.int == 10) { "Unexpected pretrained input feature count" }
            val treeCount = reader.int
            require(treeCount in 1..512)
            val baseline = reader.double
            require(baseline.isFinite())
            val trees = ArrayList<Array<Node>>(treeCount)
            repeat(treeCount) {
                val size = reader.int
                require(size in 1..1024)
                val nodes = Array(size) {
                    Node(reader.int, reader.int, reader.int, reader.double, reader.double)
                }
                nodes.forEach { node ->
                    require(node.threshold.isFinite() && node.value.isFinite())
                    require(
                        node.feature == -1 ||
                            (node.feature in 0..9 && node.left in nodes.indices &&
                                node.right in nodes.indices),
                    )
                }
                trees += nodes
            }
            require(!reader.hasRemaining()) { "Unexpected trailing model bytes" }
            return Flip7HingeBoostModel(baseline, trees)
        }
    }
}
