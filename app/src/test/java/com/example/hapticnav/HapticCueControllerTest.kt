package com.example.hapticnav

import org.junit.Assert.*
import org.junit.Test

class HapticCueControllerTest {
    private val controller = HapticCueController()
    private fun cue(distance: Double?, now: Long = 1_000, step: Int = 0,
                    side: TurnSide = TurnSide.LEFT, isFinal: Boolean = false,
                    active: Boolean = true) =
        controller.update(distance, step, side, isFinal, active, now)

    @Test fun approachUsesCorrectSideAndHundredMeterBoundary() {
        assertEquals("0", cue(100.1).left)
        assertEquals("1", cue(100.0).left)
        assertEquals("1", cue(50.1).left)
        assertEquals("0", cue(60.0, side = TurnSide.RIGHT).left)
        assertEquals("1", cue(60.0, side = TurnSide.RIGHT).right)
        assertEquals("0", cue(60.0, side = TurnSide.NONE).right)
    }

    @Test fun warningStartsAtFiftyNotSixtyAndResumesWithoutNewLocation() {
        assertEquals("1", cue(60.0).left)
        assertEquals("1", cue(50.1).left)
        assertEquals("3", cue(50.0).left)
        assertEquals("3", cue(50.0, now = 2_499).right)
        assertEquals("2", cue(50.0, now = 2_500).left)
        assertEquals("0", cue(50.0, now = 2_500).right)
    }

    @Test fun gpsJitterDoesNotRepeatWarningForSameWaypoint() {
        cue(49.0)
        assertEquals("2", cue(49.0, now = 3_000).left)
        assertEquals("1", cue(51.0, now = 4_000).left)
        assertEquals("2", cue(49.0, now = 5_000).left)
    }

    @Test fun nextWaypointGetsItsOwnWarning() {
        cue(40.0)
        cue(40.0, now = 3_000)
        assertEquals("3", cue(40.0, now = 4_000, step = 1).left)
    }

    @Test fun intermediateWaypointWaitsForPulseBeforeAdvancing() {
        assertEquals("3", cue(10.0).left)
        assertFalse(cue(9.0, now = 2_499).advanceWaypoint)
        assertTrue(cue(9.0, now = 2_500).advanceWaypoint)
    }

    @Test fun arrivalCueIsHeldBeforeStopIncludingSingleWaypointDemo() {
        assertEquals("2", cue(10.0, isFinal = true).left)
        assertFalse(cue(9.0, now = 1_999, isFinal = true).arrived)
        val finish = cue(9.0, now = 2_000, isFinal = true)
        assertTrue(finish.arrived)
        assertEquals("0", finish.left)
        assertEquals("0", finish.right)
    }

    @Test fun cancelStopsPendingPulseAndAllowsFreshSession() {
        cue(45.0)
        assertEquals("0", cue(45.0, active = false).left)
        assertEquals("3", cue(45.0, now = 3_000).left)
    }

    @Test fun invalidPositionStopsOutput() {
        for (distance in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(HapticCue(), cue(distance))
        }
    }
}
