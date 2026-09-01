package expo.modules.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {

    // --- round2: the privacy line ---

    @Test
    fun `rounds a coordinate to two decimals`() {
        assertEquals(48.86, Weather.round2(48.8566), 0.0)
        assertEquals(2.35, Weather.round2(2.3522), 0.0)
    }

    @Test
    fun `rounding works west of Greenwich and south of the equator`() {
        assertEquals(-73.99, Weather.round2(-73.9857), 0.0)
        assertEquals(-33.87, Weather.round2(-33.8688), 0.0)
    }

    @Test
    fun `a coordinate already at two decimals survives untouched`() {
        assertEquals(40.71, Weather.round2(40.71), 0.0)
    }

    // --- due: when a passing fix is worth a request ---

    @Test
    fun `no cache at all is always due`() {
        assertTrue(Weather.due(updatedAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `a fresh cache is not refetched`() {
        val now = 10_000_000_000L
        assertFalse(Weather.due(updatedAtMs = now - 44L * 60L * 1000L, nowMs = now))
    }

    @Test
    fun `a stale cache is due at the threshold`() {
        val now = 10_000_000_000L
        assertTrue(Weather.due(updatedAtMs = now - Weather.REFRESH_AFTER_MS, nowMs = now))
        assertTrue(Weather.due(updatedAtMs = now - 46L * 60L * 1000L, nowMs = now))
    }

    // --- describe: the WMO vocabulary ---

    @Test
    fun `the codes a lock face will actually meet`() {
        assertEquals("Clear", Weather.describe(0))
        assertEquals("Partly cloudy", Weather.describe(2))
        assertEquals("Overcast", Weather.describe(3))
        assertEquals("Fog", Weather.describe(45))
        assertEquals("Light rain", Weather.describe(61))
        assertEquals("Rain", Weather.describe(63))
        assertEquals("Snow", Weather.describe(73))
        assertEquals("Showers", Weather.describe(81))
        assertEquals("Thunderstorm", Weather.describe(95))
        assertEquals("Thunderstorm with hail", Weather.describe(99))
    }

    @Test
    fun `an unassigned code degrades to something calm, not to a crash or a blank`() {
        assertEquals("Cloudy", Weather.describe(42))
    }
}
