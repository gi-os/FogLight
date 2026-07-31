package expo.modules.recorder

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where you stopped, worked out from where you went.
 *
 * A track is a list of fixes; a journal wants somewhere you *were*. "Fasan Cafe, 08:12 to 09:40" is
 * a thing that happened to you, and a thousand coordinates are not — so the clustering lives here,
 * in the app that owns the data, and the rest of the collection is handed stays rather than raw
 * points it would have to interpret. Tiles are the wrong shape for this: a tile says which square
 * of the world you crossed, and crossing is exactly what a stay is not.
 *
 * Free of Android imports so the arithmetic can be tested without a device.
 */
object Stays {

    /** How far you can drift and still be in the same place. Wider than a GPS fix is accurate. */
    const val RADIUS_METRES = 80.0

    /** Shorter than this and you were passing through, not stopping. */
    const val MIN_MINUTES = 8

    /**
     * A fix so far off that using it would move a stay to another street.
     *
     * Dropped rather than averaged in: an eight-hundred metre accuracy circle in a city is a fix
     * that has bounced off a building, and one of them can drag a cluster's centre across a river.
     */
    const val MAX_ACCURACY_METRES = 120.0

    data class Fix(val atMs: Long, val latitude: Double, val longitude: Double, val accuracy: Double)

    data class Stay(
        val startMs: Long,
        val endMs: Long,
        val latitude: Double,
        val longitude: Double,
        val fixes: Int,
    ) {
        val minutes: Int get() = ((endMs - startMs) / 60_000L).toInt()
    }

    /**
     * The stays in a day's fixes.
     *
     * A cluster grows while each new fix is within [RADIUS_METRES] of the **running centre** rather
     * than of the first fix. Measuring from the first would let a slow walk stretch one stay across
     * a whole street, one fix at a time, and never notice it had moved; measuring from the centre
     * means the cluster has to keep being about one place.
     *
     * The centre itself is a running mean, which is enough at these distances — a hundred metres of
     * latitude is a hundred metres wherever you are, and the longitude is scaled by the cosine of
     * the latitude in [metresBetween] rather than being pretended to be the same.
     */
    fun of(
        fixes: List<Fix>,
        radiusMetres: Double = RADIUS_METRES,
        minMinutes: Int = MIN_MINUTES,
    ): List<Stay> {
        val usable = fixes
            .filter { it.accuracy <= MAX_ACCURACY_METRES || it.accuracy <= 0.0 }
            .sortedBy { it.atMs }
        if (usable.isEmpty()) return emptyList()

        val out = ArrayList<Stay>()
        var cluster = ArrayList<Fix>()
        var sumLat = 0.0
        var sumLon = 0.0

        fun flush() {
            if (cluster.isEmpty()) return
            val start = cluster.first().atMs
            val end = cluster.last().atMs
            if ((end - start) / 60_000L >= minMinutes) {
                out.add(
                    Stay(
                        startMs = start,
                        endMs = end,
                        latitude = sumLat / cluster.size,
                        longitude = sumLon / cluster.size,
                        fixes = cluster.size,
                    ),
                )
            }
            cluster = ArrayList()
            sumLat = 0.0
            sumLon = 0.0
        }

        usable.forEach { fix ->
            if (cluster.isEmpty()) {
                cluster.add(fix)
                sumLat = fix.latitude
                sumLon = fix.longitude
                return@forEach
            }
            val centreLat = sumLat / cluster.size
            val centreLon = sumLon / cluster.size
            if (metresBetween(centreLat, centreLon, fix.latitude, fix.longitude) <= radiusMetres) {
                cluster.add(fix)
                sumLat += fix.latitude
                sumLon += fix.longitude
            } else {
                flush()
                cluster.add(fix)
                sumLat = fix.latitude
                sumLon = fix.longitude
            }
        }
        flush()
        return out
    }

    /** Haversine. Exact enough at street distances, and honest about longitude near the poles. */
    fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** One line of a track CSV: `timestamp,lat,lng,accuracy`. Null when it is not one. */
    fun parse(line: String): Fix? {
        val parts = line.split(',')
        if (parts.size < 3) return null
        val at = parts[0].trim().toDoubleOrNull()?.toLong() ?: return null
        val lat = parts[1].trim().toDoubleOrNull() ?: return null
        val lon = parts[2].trim().toDoubleOrNull() ?: return null
        // Accuracy was added later; a line without it is still a fix.
        val accuracy = parts.getOrNull(3)?.trim()?.toDoubleOrNull() ?: 0.0
        return Fix(at, lat, lon, accuracy)
    }
}
