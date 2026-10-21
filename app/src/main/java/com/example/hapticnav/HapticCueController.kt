package com.example.hapticnav

internal const val WARNING_DISTANCE_METERS = 50.0
internal const val WAYPOINT_DISTANCE_METERS = 10.0
internal const val WARNING_DURATION_MS = 1_500L
internal const val ARRIVAL_DURATION_MS = 1_000L

internal enum class TurnSide { LEFT, RIGHT, NONE }

internal data class HapticCue(
    val left: String = "0",
    val right: String = "0",
    val label: String = "STOP",
    val advanceWaypoint: Boolean = false,
    val arrived: Boolean = false,
    val resumeAtMillis: Long? = null
)

/** Keeps warning and arrival cues alive independently of the GPS update interval. */
internal class HapticCueController {
    private var step: Int? = null
    private var warned = false
    private var pulseUntil = 0L
    private var arrivalUntil = 0L
    private var advanceAfterPulse = false

    fun reset() {
        step = null
        warned = false
        pulseUntil = 0L
        arrivalUntil = 0L
        advanceAfterPulse = false
    }

    fun update(
        distanceMeters: Double?,
        stepIndex: Int,
        side: TurnSide,
        finalWaypoint: Boolean,
        active: Boolean,
        nowMillis: Long
    ): HapticCue {
        if (!active || distanceMeters == null || !distanceMeters.isFinite() || distanceMeters < 0) {
            reset()
            return HapticCue()
        }
        if (step != stepIndex) {
            reset()
            step = stepIndex
        }
        if (arrivalUntil > 0) {
            return if (nowMillis < arrivalUntil) {
                HapticCue("2", "2", "ARRIVED", resumeAtMillis = arrivalUntil)
            } else {
                HapticCue(label = "ARRIVED", arrived = true)
            }
        }
        if (nowMillis < pulseUntil) return HapticCue("3", "3", "WARNING", resumeAtMillis = pulseUntil)
        if (advanceAfterPulse) {
            advanceAfterPulse = false
            return HapticCue(label = "NEXT STEP", advanceWaypoint = true)
        }
        if (distanceMeters <= WAYPOINT_DISTANCE_METERS) {
            if (finalWaypoint) {
                arrivalUntil = nowMillis + ARRIVAL_DURATION_MS
                return HapticCue("2", "2", "ARRIVED", resumeAtMillis = arrivalUntil)
            }
            pulseUntil = nowMillis + WARNING_DURATION_MS
            advanceAfterPulse = true
            return HapticCue("3", "3", "NEXT STEP", resumeAtMillis = pulseUntil)
        }
        if (distanceMeters <= WARNING_DISTANCE_METERS && !warned) {
            warned = true
            pulseUntil = nowMillis + WARNING_DURATION_MS
            return HapticCue("3", "3", "WARNING (50m)", resumeAtMillis = pulseUntil)
        }
        val intensity = when {
            distanceMeters <= WARNING_DISTANCE_METERS -> "2"
            distanceMeters <= 100.0 -> "1"
            else -> "0"
        }
        return when (side) {
            TurnSide.LEFT -> HapticCue(intensity, "0", "LEFT")
            TurnSide.RIGHT -> HapticCue("0", intensity, "RIGHT")
            TurnSide.NONE -> HapticCue()
        }
    }
}
