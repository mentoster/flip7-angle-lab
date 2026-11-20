package com.mentoster.flip7anglelab.fold

/**
 * Presentation-only, one-degree hysteresis at Samsung's open mechanical stop.
 *
 * On SM-F766B a rigid whole-device rotation occasionally changes the factory
 * reported angle between 177 and 176 degrees, while the physical hinge does
 * not move. Our existing 177->180 open-stop normalization made that tiny
 * vendor fluctuation appear as an abrupt 180->176 visual jump.
 *
 * Do not alter the exact source, raw on-change events, or training labels.
 * Only suppress this specific near-open fluctuation when the separate
 * orientation guard has already identified whole-phone rotation AND this
 * display was previously snapped to the open stop. Any angle <=175°,
 * coarse hinge transition, or stable-phone movement is reported directly.
 */
internal class SamsungEndpointPresentation {
    private var lastDisplayed: Float? = null

    fun render(raw: Float?, publicHinge: Float?, phoneRotating: Boolean): Float? {
        val next = AngleDisplayEndpoint.fromSamsung(raw, publicHinge)
        if (next == null) {
            lastDisplayed = null
            return null
        }
        val holdingOneDegreeOfVendorNoise = phoneRotating &&
            publicHinge != null && publicHinge >= 179f &&
            lastDisplayed == 180f && raw != null && raw >= 176f &&
            raw < 177f
        val displayed = if (holdingOneDegreeOfVendorNoise) 180f else next
        lastDisplayed = displayed
        return displayed
    }
}
