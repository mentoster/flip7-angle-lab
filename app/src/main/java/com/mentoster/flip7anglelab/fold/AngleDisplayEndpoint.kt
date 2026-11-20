package com.mentoster.flip7anglelab.fold

/**
 * Presentation-only endpoint normalization. Samsung's factory reference may
 * report 177–179° at the mechanical fully-open stop. Preserve the real raw
 * reference in diagnostics, calibration and training CSV files.
 */
internal object AngleDisplayEndpoint {
    fun fromSamsung(raw: Float?, publicHinge: Float?): Float? {
        val angle = raw?.takeIf(Float::isFinite) ?: return null
        return if (angle >= 177f && publicHinge != null && publicHinge >= 179f) 180f else angle
    }
}
