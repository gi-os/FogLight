package expo.modules.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTest {

    private val still = Power.DEFAULT_STILL_AFTER_MS

    @Test
    fun `moving about with nothing else true keeps the radio on`() {
        assertEquals(
            Power.State.ACTIVE,
            Power.decide(inZone = false, gating = true, canSenseMotion = true, stillFor = null),
        )
    }

    @Test
    fun `a named network switches the radio off`() {
        assertEquals(
            Power.State.PAUSED_ZONE,
            Power.decide(inZone = true, gating = true, canSenseMotion = true, stillFor = null),
        )
    }

    @Test
    fun `a zone beats stillness, because leaving it needs no wake`() {
        assertEquals(
            Power.State.PAUSED_ZONE,
            Power.decide(inZone = true, gating = true, canSenseMotion = true, stillFor = still * 10),
        )
    }

    @Test
    fun `sitting still long enough switches the radio off`() {
        assertEquals(
            Power.State.PAUSED_STILL,
            Power.decide(inZone = false, gating = true, canSenseMotion = true, stillFor = still),
        )
    }

    @Test
    fun `not yet still enough is still recording`() {
        assertEquals(
            Power.State.ACTIVE,
            Power.decide(inZone = false, gating = true, canSenseMotion = true, stillFor = still - 1),
        )
    }

    @Test
    fun `without a motion sensor a still pause is never entered`() {
        // Nothing would end it but the watchdog, so an hour of track would be lost per hour still.
        assertEquals(
            Power.State.ACTIVE,
            Power.decide(inZone = false, gating = true, canSenseMotion = false, stillFor = still * 5),
        )
    }

    @Test
    fun `gating off leaves only the zones`() {
        assertEquals(
            Power.State.ACTIVE,
            Power.decide(inZone = false, gating = false, canSenseMotion = true, stillFor = still * 5),
        )
        assertEquals(
            Power.State.PAUSED_ZONE,
            Power.decide(inZone = true, gating = false, canSenseMotion = true, stillFor = null),
        )
    }

    @Test
    fun `a custom wait is honoured`() {
        val twoMinutes = 2L * 60_000L
        assertEquals(
            Power.State.PAUSED_STILL,
            Power.decide(
                inZone = false, gating = true, canSenseMotion = true,
                stillFor = twoMinutes, stillAfterMs = twoMinutes,
            ),
        )
        assertEquals(
            Power.State.ACTIVE,
            Power.decide(
                inZone = false, gating = true, canSenseMotion = true,
                stillFor = twoMinutes, stillAfterMs = twoMinutes + 1,
            ),
        )
    }

    @Test
    fun `the still radius is wider than GPS noise and narrower than a walk`() {
        assertTrue(Power.stillAt(0.0))
        assertTrue(Power.stillAt(40.0))
        assertFalse(Power.stillAt(200.0))
    }

    @Test
    fun `a paused notification says which pause it is`() {
        assertEquals("Recording your path", Power.describe(Power.State.ACTIVE, null))
        assertTrue(Power.describe(Power.State.PAUSED_ZONE, "work").contains("work"))
        assertTrue(Power.describe(Power.State.PAUSED_STILL, null).contains("moving"))
    }
}
