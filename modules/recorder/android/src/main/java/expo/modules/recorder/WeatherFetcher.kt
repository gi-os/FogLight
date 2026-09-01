package expo.modules.recorder

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One GET to Open-Meteo, riding on a coordinate the recorder already paid for.
 *
 * Keyless and free; the whole request is a rounded coordinate. The response is cached in prefs
 * as one JSON blob with its own timestamp, and a failure of any kind leaves the previous blob
 * exactly where it was — [WeatherProvider] serves stale weather with an honest `updatedAt` and
 * lets the reader decide, which beats a blank line every time the network blinks.
 */
object WeatherFetcher {

    private const val TAG = "WeatherFetcher"

    /** The cache blob: updatedAt, lat, lon, tempC, hiC, loC, code, precipPct. */
    const val KEY_CACHE = "weatherCache"

    /** Last rounded coordinate seen, "lat,lon" — what the hourly batch refresh reuses. */
    const val KEY_LAST_COORD = "weatherLastCoord"

    private val inFlight = AtomicBoolean(false)

    /**
     * Called with every fix the recorder handles. Free unless the cache has gone stale:
     * remembers the rounded coordinate, and only when [Weather.due] says so spends one request
     * on a background thread. Never blocks the caller, never doubles up.
     */
    fun onFix(context: Context, latitude: Double, longitude: Double) {
        val lat = Weather.round2(latitude)
        val lon = Weather.round2(longitude)
        val prefs = RecorderModule.prefs(context)
        prefs.edit().putString(KEY_LAST_COORD, "$lat,$lon").apply()

        if (!Weather.due(cachedUpdatedAt(context), System.currentTimeMillis())) return
        if (!inFlight.compareAndSet(false, true)) return
        Thread {
            try {
                fetchNow(context, lat, lon)
            } finally {
                inFlight.set(false)
            }
        }.apply { name = "weather-fetch" }.start()
    }

    /** When the cache was written, or 0 when there is none. */
    fun cachedUpdatedAt(context: Context): Long = runCatching {
        val raw = RecorderModule.prefs(context).getString(KEY_CACHE, null) ?: return 0L
        JSONObject(raw).optLong("updatedAt", 0L)
    }.getOrDefault(0L)

    /** The last rounded coordinate, or null if the recorder has never seen a fix. */
    fun lastCoordinate(context: Context): Pair<Double, Double>? {
        val raw = RecorderModule.prefs(context).getString(KEY_LAST_COORD, null) ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        return lat to lon
    }

    /**
     * The request itself — blocking, call off the main thread. Returns whether the cache was
     * refreshed. Any failure logs and returns false with the old cache untouched.
     */
    fun fetchNow(context: Context, lat: Double, lon: Double): Boolean {
        return runCatching {
            val url = String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=1",
                lat, lon,
            )
            val conn = URL(url).openConnection() as HttpURLConnection
            val body = try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                if (conn.responseCode != 200) {
                    throw IllegalStateException("open-meteo answered ${conn.responseCode}")
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val json = JSONObject(body)
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val cache = JSONObject()
                .put("updatedAt", System.currentTimeMillis())
                .put("lat", lat)
                .put("lon", lon)
                .put("tempC", current.getDouble("temperature_2m"))
                .put("code", current.getInt("weather_code"))
                .put("hiC", daily.getJSONArray("temperature_2m_max").getDouble(0))
                .put("loC", daily.getJSONArray("temperature_2m_min").getDouble(0))
                .put(
                    "precipPct",
                    daily.getJSONArray("precipitation_probability_max").optInt(0, 0)
                        .coerceIn(0, 100),
                )

            RecorderModule.prefs(context).edit()
                .putString(KEY_CACHE, cache.toString())
                .apply()
            true
        }.getOrElse {
            Log.w(TAG, "weather fetch failed, keeping the stale cache: ${it.message}")
            false
        }
    }
}
