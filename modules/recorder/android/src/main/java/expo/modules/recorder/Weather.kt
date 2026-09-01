package expo.modules.recorder

/**
 * The decisions behind the weather cache, with no Android in them.
 *
 * This app is the only thing on the phone that ever holds a coordinate, so it is the one place
 * weather can be fetched without anything else asking the OS where the phone is. But it is also
 * the app that once crash-looped a location service and bricked the phone, so the rules are
 * strict and they live here where a test can hold them down:
 *
 * - Weather is **piggybacked, never chased**. A fetch happens only when a fix has already been
 *   bought for the track, or when WorkManager's hourly batch window comes around with a
 *   coordinate we already had. Nothing ever turns the GPS on for weather, and nothing ever wakes
 *   the phone for it.
 * - The coordinate is **rounded to two decimals (~1 km) before it leaves the phone**. Open-Meteo
 *   gets a neighbourhood, not a doorstep. That rounding is also all the API needs — weather does
 *   not change across a street.
 * - A failed fetch **keeps the stale cache**. The provider serves whatever is cached, with its
 *   timestamp, and the reader decides how old is too old; here, staleness only gates whether a
 *   new fetch is worth the network.
 */
object Weather {

    /** How old the cache may be before a passing fix is worth spending a request on. */
    const val REFRESH_AFTER_MS = 45L * 60L * 1000L

    /** Round a coordinate to two decimal places — roughly a kilometre. The privacy line. */
    fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0

    /** Whether a new fetch is due. A missing cache (updatedAt <= 0) is always due. */
    fun due(updatedAtMs: Long, nowMs: Long): Boolean =
        updatedAtMs <= 0L || nowMs - updatedAtMs >= REFRESH_AFTER_MS

    /**
     * A short human name for a WMO weather-interpretation code, the vocabulary Open-Meteo speaks.
     *
     * Open-Meteo only emits the codes below; the fallback is for a spec change, not for weather.
     */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Cloudy"
    }
}
