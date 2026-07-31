package expo.modules.recorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground location-logging service (the gpslogger survival pattern:
 * typed foreground service + persistent notification). Appends fixes to
 * a crash-safe daily CSV: epochMs,lat,lng,accuracy
 */
class RecorderService : Service(), LocationListener {

  private var locationManager: LocationManager? = null
  private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
  private val handler = android.os.Handler(android.os.Looper.getMainLooper())
  private val nightlyCheck = object : Runnable {
    override fun run() {
      FowSync.runIfDue(this@RecorderService)
      handler.postDelayed(this, 30L * 60L * 1000L)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (!RecorderModule.prefs(this).getBoolean(RecorderModule.KEY_RUNNING, false)) {
      stopSelf()
      return START_NOT_STICKY
    }
    // Permission first, and this order is the whole fix.
    //
    // It used to go foreground and *then* check, which is exactly backwards: a location-typed
    // foreground service started without the location permission throws SecurityException out of
    // startForeground, so the process died before reaching the check that would have stopped it
    // politely. START_STICKY then brought it back to do the same thing again — a crash loop that
    // took the phone down with it, because every restart also queued another permission dialog and
    // the task stack filled with hundreds of them.
    if (!hasLocationPermission()) {
      standDown("location permission not granted")
      return START_NOT_STICKY
    }
    if (!startAsForeground()) {
      standDown("foreground start refused")
      return START_NOT_STICKY
    }
    startLocationUpdates()
    isServiceRunning = true
    handler.removeCallbacks(nightlyCheck)
    handler.postDelayed(nightlyCheck, 60L * 1000L)
    return START_STICKY
  }

  /** Whether we may log a fix at all. Coarse is enough to be allowed the service type. */
  private fun hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
  }

  /**
   * Give up, and give up in a way that stays given up.
   *
   * Clearing the running flag matters as much as stopping: it is what `BootReceiver` and every
   * sticky restart consult, so leaving it set is how a service that cannot possibly work gets
   * started again at every boot forever. The user turned recording on and it could not be done —
   * the flag now says so, and the UI reads the same flag.
   */
  private fun standDown(reason: String) {
    Log.w(TAG, "recorder standing down: $reason")
    RecorderModule.prefs(this).edit()
      .putBoolean(RecorderModule.KEY_RUNNING, false)
      .putString(RecorderModule.KEY_LAST_ERROR, reason)
      .apply()
    isServiceRunning = false
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    }
    stopSelf()
  }

  override fun onDestroy() {
    handler.removeCallbacks(nightlyCheck)
    locationManager?.removeUpdates(this)
    isServiceRunning = false
    super.onDestroy()
  }

  /**
   * Go foreground, and never crash doing it.
   *
   * Three ways this throws, all of them survivable and none of them worth a dead process:
   * SecurityException when the location permission or `FOREGROUND_SERVICE_LOCATION` is missing (14+
   * validates the *type*, not just the call), ForegroundServiceStartNotAllowedException when the
   * start came from the background outside an exemption (12+), and IllegalStateException on older
   * timing edges. A logger that cannot log is a notification that should go away — not a phone that
   * needs rebooting.
   */
  private fun startAsForeground(): Boolean = try {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    true
  } catch (t: Throwable) {
    Log.w(TAG, "startForeground refused", t)
    false
  }

  private fun startLocationUpdates() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
      != PackageManager.PERMISSION_GRANTED
    ) {
      stopSelf()
      return
    }
    // Idempotent: onStartCommand can run repeatedly (self-healing restarts).
    locationManager?.removeUpdates(this)
    val intervalMs = RecorderModule.prefs(this)
      .getInt(RecorderModule.KEY_INTERVAL, 10_000).toLong()
    locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    try {
      locationManager?.requestLocationUpdates(
        LocationManager.GPS_PROVIDER,
        intervalMs,
        MIN_DISTANCE_M,
        this,
      )
    } catch (_: IllegalArgumentException) {
      // GPS provider missing (emulator edge case)
      stopSelf()
    }
  }

  override fun onLocationChanged(location: Location) {
    val zone = zoneAt(location)
    if (zone != null) {
      // **The fact, never the place.** A fix inside home or work still does not reach the track —
      // that promise is why the zones exist, and the tiles that sync out depend on it. What is
      // written instead is one line saying you arrived somewhere you had named, with no latitude
      // and no longitude anywhere in it. "Went home at 19:40" locates you only if you already know
      // where home is, which whoever reads this file already does.
      noteZoneArrival(zone, location.time)
      return
    }
    // Out of every zone: remember that, so coming back writes an arrival rather than being
    // swallowed as "still there".
    setLastZone(null)
    val file = File(RecorderModule.tracksDir(this), "${dayFormat.format(Date())}.csv")
    file.appendText(
      "${location.time},${location.latitude},${location.longitude},${location.accuracy}\n"
    )
  }

  /**
   * Note an arrival, once.
   *
   * **Only on a change of zone.** A phone sitting at home overnight produces hundreds of fixes and
   * one arrival; writing a line per fix would be a file full of the same minute. The last zone is
   * persisted rather than held in memory because this service is restarted by the system and by
   * every reboot, and a fresh process seeing its first fix at home would otherwise invent an
   * arrival for a place you had not moved from.
   */
  private fun noteZoneArrival(name: String, atMs: Long) {
    if (lastZone() == name) return
    setLastZone(name)
    runCatching {
      val dir = RecorderModule.zonesDir(this)
      File(dir, "${dayFormat.format(Date(atMs))}.csv").appendText("$atMs,$name\n")
    }
  }

  private fun lastZone(): String? =
    RecorderModule.prefs(this).getString(KEY_LAST_ZONE, null)

  private fun setLastZone(name: String?) {
    RecorderModule.prefs(this).edit().apply {
      if (name == null) remove(KEY_LAST_ZONE) else putString(KEY_LAST_ZONE, name)
    }.apply()
  }

  /**
   * Which named zone the fix falls inside, or null for anywhere else.
   *
   * Was a boolean; it has to name the zone now so an arrival can be recorded without the
   * coordinates. The comparison itself is unchanged.
   */
  private fun zoneAt(location: Location): String? {
    val prefs = RecorderModule.prefs(this)
    for (name in ZONE_NAMES) {
      val raw = prefs.getString("zone_$name", null) ?: continue
      val parts = raw.split(",")
      if (parts.size < 3) continue
      try {
        val results = FloatArray(1)
        Location.distanceBetween(
          location.latitude, location.longitude,
          parts[0].toDouble(), parts[1].toDouble(), results,
        )
        if (results[0] <= parts[2].toFloat()) return name
      } catch (_: NumberFormatException) {
        // malformed zone; ignore
      }
    }
    return null
  }

  @Deprecated("Deprecated in API 29, still required for older targets")
  override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
  override fun onProviderEnabled(provider: String) {}
  override fun onProviderDisabled(provider: String) {}

  private fun createChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Recording",
        NotificationManager.IMPORTANCE_LOW,
      )
      channel.setShowBadge(false)
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .createNotificationChannel(channel)
    }
  }

  private fun buildNotification(): Notification {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    val contentIntent = PendingIntent.getActivity(
      this, 0, launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION") Notification.Builder(this)
    }
    return builder
      .setContentTitle("LightFog")
      .setContentText("Recording your path")
      .setSmallIcon(android.R.drawable.ic_menu_mylocation)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      .build()
  }

  companion object {
    private const val TAG = "RecorderService"
    @Volatile var isServiceRunning = false
    const val CHANNEL_ID = "lightfog_recording"
    const val NOTIFICATION_ID = 4207
    const val MIN_DISTANCE_M = 5f
    val ZONE_NAMES = listOf("home", "work")

    /** The zone the last fix was in, so a restart does not invent an arrival. */
    const val KEY_LAST_ZONE = "last_zone"
  }
}
