package expo.modules.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File

class RecorderModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("Recorder")

    // Returns null on success, or a reason. Refusing here rather than letting the service find
    // out is the difference between a message on screen and a process that dies on arrival:
    // startForegroundService promises a foreground service within five seconds, and a service that
    // throws instead of keeping that promise takes the app down with it.
    Function("start") { intervalMs: Int ->
      val context = appContext.reactContext ?: return@Function "no context"
      val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
      )
      val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
      )
      if (fine != PackageManager.PERMISSION_GRANTED &&
        coarse != PackageManager.PERMISSION_GRANTED
      ) {
        prefs(context).edit()
          .putBoolean(KEY_RUNNING, false)
          .putString(KEY_LAST_ERROR, "location permission not granted")
          .apply()
        return@Function "location permission not granted"
      }
      prefs(context).edit()
        .putBoolean(KEY_RUNNING, true)
        .putInt(KEY_INTERVAL, intervalMs)
        .remove(KEY_LAST_ERROR)
        .apply()
      val intent = Intent(context, RecorderService::class.java)
      val started = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      }
      if (started.isFailure) {
        prefs(context).edit()
          .putBoolean(KEY_RUNNING, false)
          .putString(KEY_LAST_ERROR, "the system refused to start the service")
          .apply()
        return@Function "the system refused to start the service"
      }
      null
    }

    /** Why recording last stopped itself, or null. */
    Function("lastError") {
      val context = appContext.reactContext ?: return@Function null
      prefs(context).getString(KEY_LAST_ERROR, null)
    }

    Function("stop") {
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit().putBoolean(KEY_RUNNING, false).apply()
      context.stopService(Intent(context, RecorderService::class.java))
      null
    }

    // User intent (prefs), not service liveness — the UI's source of truth.
    Function("isRunning") {
      val context = appContext.reactContext ?: return@Function false
      prefs(context).getBoolean(KEY_RUNNING, false)
    }

    // True only if the service process is actually alive right now.
    Function("isServiceAlive") {
      RecorderService.isServiceRunning
    }

    // Nightly tile sync (tracks -> FoW tiles -> Dropbox)
    Function("setDropboxCreds") { appKey: String, refreshToken: String ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit()
        .putString("dbxAppKey", appKey)
        .putString("dbxRefreshToken", refreshToken)
        .apply()
      null
    }

    Function("setTileSync") { enabled: Boolean ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit().putBoolean("tileSyncEnabled", enabled).apply()
      null
    }

    Function("tileSyncReport") {
      val context = appContext.reactContext ?: return@Function ""
      prefs(context).getString("tileSyncReport", "") ?: ""
    }

    AsyncFunction("fowConvertNow") {
      val context = appContext.reactContext ?: return@AsyncFunction "no context"
      try {
        FowSync.run(context)
      } catch (e: Exception) {
        "failed — ${e.message}"
      }
    }

    // Privacy zones: recording is suppressed within radiusM of these points.
    Function("setPrivacyZone") { name: String, lat: Double, lng: Double, radiusM: Double ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit().putString("zone_$name", "$lat,$lng,$radiusM").apply()
      null
    }

    // Privacy *networks*: the primary signal, because a GPS fix indoors teleports and a network name
    // does not. Marks whichever Wi-Fi you are connected to now as home or work.
    Function("setPrivacyNetwork") { name: String ->
      val context = appContext.reactContext ?: return@Function null
      val ssid = currentSsid(context) ?: return@Function null
      prefs(context).edit().putString("net_$name", ssid).apply()
      nudge(context)
      ssid
    }

    Function("clearPrivacyNetwork") { name: String ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit().remove("net_$name").apply()
      nudge(context)
      null
    }

    /**
     * Turn the radio off when nothing has moved for a while.
     *
     * The other half of the battery answer. Home and work cover the hours the phone sits on a known
     * network; this covers every other desk, restaurant and cinema seat, where a fix a minute buys
     * the same coordinate over and over. A hardware motion trigger ends it, so being paused costs a
     * fraction of a milliamp rather than the tens a fix costs.
     */
    Function("setMotionGating") { enabled: Boolean ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit().putBoolean(KEY_MOTION_GATING, enabled).apply()
      nudge(context)
      null
    }

    Function("motionGating") {
      val context = appContext.reactContext ?: return@Function true
      prefs(context).getBoolean(KEY_MOTION_GATING, true)
    }

    Function("setStillAfterMinutes") { minutes: Int ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit().putInt(KEY_STILL_AFTER_MIN, minutes.coerceIn(1, 60)).apply()
      nudge(context)
      null
    }

    Function("stillAfterMinutes") {
      val context = appContext.reactContext
        ?: return@Function RecorderService.DEFAULT_STILL_AFTER_MIN
      prefs(context).getInt(KEY_STILL_AFTER_MIN, RecorderService.DEFAULT_STILL_AFTER_MIN)
    }

    /**
     * Why the radio is off: `ACTIVE`, `PAUSED_ZONE` or `PAUSED_STILL`.
     *
     * Worth surfacing because a paused recorder and a broken recorder look identical from outside —
     * no fixes either way. Someone who cannot tell which one they have will turn the feature off.
     */
    Function("powerState") {
      val context = appContext.reactContext ?: return@Function "ACTIVE"
      prefs(context).getString(KEY_POWER_STATE, "ACTIVE") ?: "ACTIVE"
    }

    /** The network saved for a zone, so the settings screen can show what it will match. */
    Function("privacyNetwork") { name: String ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).getString("net_$name", null)
    }

    /** What you are connected to right now, or null when Android will not say. */
    Function("currentNetwork") {
      val context = appContext.reactContext ?: return@Function null
      currentSsid(context)
    }

    Function("clearPrivacyZone") { name: String ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit().remove("zone_$name").apply()
      null
    }

    Function("tracksDir") {
      val context = appContext.reactContext ?: return@Function null
      tracksDir(context).absolutePath
    }

    // Flip LightOS's forced-grayscale (accessibility daltonizer) while the
    // map is open. Requires: adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS
    Function("setGrayscale") { enabled: Boolean ->
      val context = appContext.reactContext ?: return@Function null
      try {
        if (enabled) {
          android.provider.Settings.Secure.putInt(
            context.contentResolver, "accessibility_display_daltonizer_enabled", 1)
          android.provider.Settings.Secure.putInt(
            context.contentResolver, "accessibility_display_daltonizer", 0)
        } else {
          android.provider.Settings.Secure.putInt(
            context.contentResolver, "accessibility_display_daltonizer_enabled", 0)
        }
      } catch (_: SecurityException) {
        // permission not granted; silently ignore
      }
      null
    }

    AsyncFunction("fowInspect") { path: String ->
      FowCodec.inspect(path)
    }

    AsyncFunction("fowValidate") { path: String ->
      val blocks = FowCodec.decodeTile(java.io.File(path))
      blocks != null && blocks.isNotEmpty()
    }

    AsyncFunction("fowRenderTile") { path: String, sizePx: Int, color: Double, style: Int, blurRadius: Int ->
      val context = appContext.reactContext ?: return@AsyncFunction null
      FowCodec.renderTile(context, path, sizePx, color.toLong().toInt(), style, blurRadius)
    }

    AsyncFunction("fowRenderOverview") { dirPath: String, sizePx: Int, color: Double ->
      val context = appContext.reactContext ?: return@AsyncFunction null
      FowCodec.renderOverview(context, dirPath, sizePx, color.toLong().toInt())
    }
  }

  companion object {
    const val PREFS = "recorder"
    const val KEY_RUNNING = "running"
    const val KEY_INTERVAL = "intervalMs"

    /** Why recording stopped itself, for the UI to explain rather than silently show "off". */
    const val KEY_LAST_ERROR = "lastError"

    /** Whether a phone that has not moved is allowed to switch its GPS off. */
    const val KEY_MOTION_GATING = "motionGating"

    /** Minutes of not moving before it does. */
    const val KEY_STILL_AFTER_MIN = "stillAfterMin"

    /** The service's last [Power.State], so the UI can explain a radio that is off on purpose. */
    const val KEY_POWER_STATE = "powerState"

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Tell a running recorder that something it decides on has changed.
     *
     * `onStartCommand` re-evaluates the power policy, so starting an already-running service is how
     * a settings change reaches it — naming your current Wi-Fi as home should switch the radio off
     * now, not at the next fix. **Only when it is already running**: this must never be the thing
     * that starts a recorder, because a background start of a location service is exactly the call
     * that once threw and took the phone's UI down with it.
     */
    fun nudge(context: Context) {
      if (!prefs(context).getBoolean(KEY_RUNNING, false)) return
      if (!RecorderService.isServiceRunning) return
      runCatching { context.startService(Intent(context, RecorderService::class.java)) }
    }

    /**
     * The Wi-Fi network's name, or null.
     *
     * Quoted by Android, and `<unknown ssid>` when it refuses — no location permission, or location
     * switched off. Both are null here rather than a network literally called `<unknown ssid>`, which
     * would otherwise be saved as a zone and match every time Android declined to answer.
     */
    fun currentSsid(context: Context): String? = runCatching {
      val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
      @Suppress("DEPRECATION")
      val raw = wifi.connectionInfo?.ssid ?: return null
      raw.trim('"').takeIf { it.isNotBlank() && it != RecorderService.UNKNOWN_SSID }
    }.getOrNull()

    fun tracksDir(context: Context): File =
      File(context.filesDir, "tracks").apply { mkdirs() }

    /**
     * Where zone arrivals go — "went home", "went to work" — and nothing else.
     *
     * A separate directory from the track on purpose. The track holds coordinates and syncs out as
     * tiles; this holds timestamps and a name, no position at all, and keeping them apart makes that
     * difference structural rather than a rule someone has to remember.
     */
    fun zonesDir(context: Context): File =
      File(context.filesDir, "zones").apply { mkdirs() }
  }
}
