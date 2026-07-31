package expo.modules.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaysTest {

    private val t0 = 1_753_848_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    /** Roughly 111,320 m per degree of latitude, so this moves a known number of metres north. */
    private fun north(metres: Double) = 40.7128 + metres / 111_320.0

    private fun fix(minutes: Int, lat: Double = 40.7128, lon: Double = -74.0060, acc: Double = 10.0) =
        Stays.Fix(min(minutes), lat, lon, acc)

    @Test
    fun `sitting still for half an hour is a stay`() {
        val fixes = (0..30 step 5).map { fix(it) }
        val stays = Stays.of(fixes)
        assertEquals(1, stays.size)
        assertEquals(30, stays.single().minutes)
    }

    @Test
    fun `passing through is not`() {
        // Two minutes in one place on the way somewhere else.
        val fixes = listOf(fix(0), fix(1), fix(2))
        assertTrue(Stays.of(fixes).isEmpty())
    }

    @Test
    fun `two places are two stays`() {
        val here = (0..20 step 5).map { fix(it) }
        val there = (60..90 step 5).map { fix(it, lat = north(2_000.0)) }
        val stays = Stays.of(here + there)
        assertEquals(2, stays.size)
        assertTrue(stays[0].startMs < stays[1].startMs)
    }

    @Test
    fun `a slow walk is not a stay at all`() {
        // Sixty metres every five minutes for two hours. Measured from the *first* fix, a cluster
        // would never notice it had moved and the whole walk would come back as one long stay in a
        // place you never stopped. Measured from the running centre it breaks apart, and each
        // fragment is then too short to count — which is right: you did not stop anywhere.
        val fixes = (0..24).map { fix(it * 5, lat = north(it * 60.0)) }
        assertTrue("was ${Stays.of(fixes)}", Stays.of(fixes).isEmpty())
    }

    @Test
    fun `stopping in the middle of a walk is`() {
        val out = (0..5).map { fix(it * 5, lat = north(it * 60.0)) }
        val rest = (30..60 step 5).map { fix(it, lat = north(300.0)) }
        val on = (65..85 step 5).map { fix(it, lat = north(300.0 + (it - 60) * 60.0)) }
        val stays = Stays.of(out + rest + on)
        assertEquals(1, stays.size)
        assertTrue(stays.single().minutes >= 30)
    }

    @Test
    fun `a wild fix is dropped rather than dragging the centre`() {
        // An 800m accuracy circle is a fix that bounced off a building; averaging it in moves the
        // stay across a river.
        val good = (0..30 step 5).map { fix(it) }
        val wild = listOf(Stays.Fix(min(15), north(3_000.0), -74.0060, 800.0))
        val stays = Stays.of(good + wild)
        assertEquals(1, stays.size)
        assertTrue(Stays.metresBetween(stays.single().latitude, stays.single().longitude, 40.7128, -74.0060) < 20)
    }

    @Test
    fun `distance is honest about longitude`() {
        // A degree of longitude is much shorter at this latitude than a degree of latitude.
        val lat = Stays.metresBetween(40.0, -74.0, 41.0, -74.0)
        val lon = Stays.metresBetween(40.0, -74.0, 40.0, -73.0)
        assertTrue(lon < lat)
        assertEquals(111_000.0, lat, 2_000.0)
    }

    @Test
    fun `a track line parses, with or without accuracy`() {
        val withAcc = Stays.parse("1753848000000,40.7128,-74.0060,12.5")!!
        assertEquals(40.7128, withAcc.latitude, 0.0001)
        assertEquals(12.5, withAcc.accuracy, 0.001)
        // The recorder wrote three columns before accuracy was added; those are still fixes.
        val without = Stays.parse("1753848000000,40.7128,-74.0060")!!
        assertEquals(0.0, without.accuracy, 0.001)
    }

    @Test
    fun `rubbish is not a fix`() {
        assertEquals(null, Stays.parse(""))
        assertEquals(null, Stays.parse("not,a,fix"))
        assertEquals(null, Stays.parse("1753848000000,40.7128"))
    }

    @Test
    fun `an empty day has no stays`() {
        assertTrue(Stays.of(emptyList()).isEmpty())
    }

    @Test
    fun `fixes out of order are still read in order`() {
        val fixes = (0..30 step 5).map { fix(it) }.reversed()
        assertEquals(30, Stays.of(fixes).single().minutes)
    }
}
