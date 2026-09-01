package expo.modules.recorder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
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
 *
 * **The radio is off whenever a fix would be thrown away.** Home and work were already excluded
 * from the track, but the GPS was still asked for a position every ten seconds while the phone sat
 * on a desk — bought and then discarded, all day. That was most of a battery. Now the zone check and
 * a stillness check both stop the request instead of filtering the result; see [Power].
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

  private var state = Power.State.ACTIVE
  private var updatesRequested = false

  /** The fix stillness is measured from, and when we arrived at it. Null until the first fix. */
  private var anchor: Location? = null
  private var anchorAtMs = 0L

  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var motionListener: TriggerEventListener? = null

  /** The last network name we acted on. Null is a real answer, hence the separate flag. */
  private var lastSeenSsid: String? = null
  private var haveSeenSsid = false

  /** Re-reads spent on an unnamed network since it was last readable. Bounded, so it is not a poll. */
  private var settleTries = 0

  /**
   * Looked up once, not per policy run.
   *
   * `getSystemService` + `getDefaultSensor` on every decision was cheap individually and not cheap
   * multiplied by an office network's capability updates. The answer cannot change while the process
   * lives, so it is cached — including the null, which is why this is `lazy` and not `?:`.
   */
  private val motionSensor: Sensor? by lazy {
    runCatching {
      (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
        ?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    }.getOrNull()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createChannel()
    // KEEP policy: this line is a no-op on every start after the first. The worker itself does
    // nothing until a fix has been seen, so scheduling it here costs the drawer-phone zero.
    WeatherWorker.schedule(this)
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
    isServiceRunning = true

    // A woken still-pause starts over: the phone is somewhere new, or on its way there, and either
    // way the old anchor is not where it is. Clearing it here rather than trusting the trigger means
    // a wake that arrived as a restarted process still counts.
    if (intent?.action == ACTION_WAKE) {
      anchor = null
      anchorAtMs = 0L
    }

    watchNetworks()
    applyPolicy("start")

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
    handler.removeCallbacks(settleCheck)
    stopLocationUpdates()
    disarmMotion()
    cancelWatchdog()
    unwatchNetworks()
    isServiceRunning = false
    reportState(Power.State.ACTIVE)
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
    val notification = buildNotification(Power.State.ACTIVE, null)
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

  // --- power policy ---------------------------------------------------------

  /**
   * Work out what the recorder should be doing, and make it so.
   *
   * Called from everything that could change the answer — a start, a Wi-Fi change, a fix, a motion
   * trigger, the watchdog — because each of those is a fact about whether a position is worth
   * asking for, and none of them is a timer. That matters on a phone asleep: a `postDelayed` loop
   * does not run in Doze, so a policy that re-evaluated on a schedule would be a policy that only
   * worked while you were looking at it.
   *
   * Idempotent by state comparison, so the same answer arriving four ways costs nothing.
   */
  private fun applyPolicy(reason: String) {
    val zone = pausingZone()
    val gating = RecorderModule.prefs(this).getBoolean(RecorderModule.KEY_MOTION_GATING, true)
    val next = Power.decide(
      inZone = zone != null,
      gating = gating,
      canSenseMotion = motionSensor != null,
      stillFor = stillFor(),
      stillAfterMs = stillAfterMs(),
    )
    if (next == state && (next == Power.State.ACTIVE) == updatesRequested) {
      // Nothing to change — except that one zone can be entered directly from another, which is a
      // new arrival with no change of state to announce it.
      if (next == Power.State.PAUSED_ZONE && zone != null) {
        noteZoneArrival(zone, System.currentTimeMillis())
      }
      return
    }
    Log.i(TAG, "policy: $state -> $next ($reason)")
    state = next
    when (next) {
      Power.State.ACTIVE -> {
        disarmMotion()
        cancelWatchdog()
        setLastZone(null)
        startLocationUpdates()
      }
      Power.State.PAUSED_ZONE -> {
        // **The arrival is written here, not from a fix.** With the radio off there will be no fix
        // to notice it — and that is the point: the phone records that you went home without ever
        // having asked where home is.
        stopLocationUpdates()
        disarmMotion()
        cancelWatchdog()
        // **Forget where you were stood.** Stillness is only knowable from fixes, and there will not
        // be any: an anchor left behind here is eight hours old by the time the Wi-Fi drops, so
        // walking out of the door would decide the phone had been still all night and switch the
        // radio straight back off. A still pause keeps its anchor for exactly the opposite reason —
        // it *is* what says the pause should continue.
        anchor = null
        anchorAtMs = 0L
        zone?.let { noteZoneArrival(it, System.currentTimeMillis()) }
      }
      Power.State.PAUSED_STILL -> {
        stopLocationUpdates()
        armMotion()
        armWatchdog()
      }
    }
    updateNotification(next, zone)
    reportState(next)
  }

  /**
   * The zone that is allowed to turn the radio off: a named network, and only that.
   *
   * **A radius cannot pause the radio, and this is not an oversight.** Leaving a circle is something
   * only a fix can tell you, so a radius pause could never end — the phone would stop recording at
   * the first fix near home and stay stopped until something else woke it. A network drops on its
   * own when you walk out of range, which is why it can be trusted to switch something off. The
   * radius keeps doing the job it can do, filtering fixes in [zoneAt].
   */
  private fun pausingZone(): String? = networkZone()

  /** How long fixes have stayed put, or null if the phone has moved or has no anchor yet. */
  private fun stillFor(): Long? {
    if (anchor == null || anchorAtMs == 0L) return null
    return System.currentTimeMillis() - anchorAtMs
  }

  private fun stillAfterMs(): Long {
    val minutes = RecorderModule.prefs(this)
      .getInt(RecorderModule.KEY_STILL_AFTER_MIN, DEFAULT_STILL_AFTER_MIN)
    return minutes.coerceAtLeast(1).toLong() * 60_000L
  }

  /**
   * Ask the hardware to tell us when the phone is carried somewhere.
   *
   * `TYPE_SIGNIFICANT_MOTION` runs in the sensor hub, not on the CPU, and costs a fraction of a
   * milliamp against the tens a GPS fix costs — it is the whole reason a still pause is worth
   * having. It is one-shot: the trigger is consumed when it fires and has to be asked for again,
   * which is exactly what re-entering the pause does.
   */
  private fun armMotion() {
    if (motionListener != null) return
    val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
    val sensor = motionSensor ?: return
    val listener = object : TriggerEventListener() {
      override fun onTrigger(event: TriggerEvent?) {
        motionListener = null // consumed by the framework, so forget it before re-arming anything
        anchor = null
        anchorAtMs = 0L
        applyPolicy("motion")
      }
    }
    if (runCatching { manager.requestTriggerSensor(listener, sensor) }.getOrDefault(false)) {
      motionListener = listener
    }
  }

  private fun disarmMotion() {
    val listener = motionListener ?: return
    motionListener = null
    val manager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
    val sensor = motionSensor ?: return
    runCatching { manager.cancelTriggerSensor(listener, sensor) }
  }

  /**
   * The one thing that ends a still pause without a sensor.
   *
   * A one-shot trigger is a chain with no redundancy: consumed and lost to a process kill, or simply
   * never fired by a phone carried smoothly in a bag, and recording is over without a symptom.
   * `setAndAllowWhileIdle` is the only alarm that fires in Doze, and its inexactness is fine for an
   * hourly "look again". Nothing re-arms it but the next pause, so a wake that finds the phone still
   * moving does not leave an alarm behind.
   */
  private fun armWatchdog() {
    val alarms = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    runCatching {
      alarms.setAndAllowWhileIdle(
        AlarmManager.RTC_WAKEUP,
        System.currentTimeMillis() + Power.STILL_WATCHDOG_MS,
        wakeIntent(),
      )
    }
  }

  private fun cancelWatchdog() {
    val alarms = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    runCatching { alarms.cancel(wakeIntent()) }
  }

  /**
   * The alarm's way back in — as a *foreground* service start, which is not a detail.
   *
   * `getService` would be a plain `startService`, and a plain background start of a service is
   * refused on 26+ and throws in the framework's delivery of the alarm, where nothing of ours can
   * catch it. The service is foreground when the alarm is armed, but it need not still be alive an
   * hour later; `getForegroundService` is the form the alarm's temporary allowlist permits, and
   * `onStartCommand` already goes foreground or stops, which is the promise it has to keep.
   */
  private fun wakeIntent(): PendingIntent {
    val intent = Intent(this, RecorderService::class.java).setAction(ACTION_WAKE)
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      PendingIntent.getForegroundService(this, WAKE_REQUEST, intent, flags)
    } else {
      PendingIntent.getService(this, WAKE_REQUEST, intent, flags)
    }
  }

  /**
   * Notice the Wi-Fi changing, because that is what ends a zone pause.
   *
   * A callback on a Wi-Fi network request rather than a poll: the pause has to end the moment you
   * walk out of the door, and it has to end without the radio being on to notice.
   *
   * **`onCapabilitiesChanged` is not an event, it is a firehose.** `WifiInfo` rides inside
   * `NetworkCapabilities`, so every RSSI and link-speed update on the connected network is delivered
   * here — seconds apart, all day. The first version of this ran the whole policy on each one, plus a
   * second run 2.5s later, and each run made a `WifiManager` binder call (which notes an app-op in
   * system_server), read prefs and looked up a sensor. On a quiet home router that is wasteful. On an
   * office network — dozens of APs, constant roaming, a hundred other clients moving the RSSI around —
   * it is a process that is never allowed to go idle, which is how the GPS could be genuinely off at
   * work and the battery still gone.
   *
   * So the SSID is read from the capabilities object that was already handed to us, and if it has not
   * changed this returns having done nothing at all.
   */
  private fun watchNetworks() {
    if (networkCallback != null) return
    val manager = getSystemService(ConnectivityManager::class.java) ?: return
    val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ZoneWatch(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
    } else {
      ZoneWatch()
    }
    val request = NetworkRequest.Builder()
      .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
      .build()
    if (runCatching { manager.registerNetworkCallback(request, callback) }.isSuccess) {
      networkCallback = callback
    }
  }

  /**
   * The Wi-Fi watcher, in two constructor flavours because the useful one did not always exist.
   *
   * Since API 31 the objects delivered to a `NetworkCallback` have location-sensitive fields stripped
   * *even from an app holding the permission*, unless the callback was built asking for them with
   * `FLAG_INCLUDE_LOCATION_INFO` — and asking is what makes `transportInfo` usable instead of a
   * `WifiManager` round trip per event. That flags constructor is itself API 31, so on anything older
   * the no-arg one has to be used: `minSdk` here is 24, and a constructor that does not exist is a
   * `NoSuchMethodError`, not a graceful degradation. The pre-31 path then falls back to reading the
   * SSID the old way, which on those versions was never redacted like this in the first place.
   */
  private inner class ZoneWatch : ConnectivityManager.NetworkCallback {
    constructor() : super()

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    constructor(flags: Int) : super(flags)

    override fun onAvailable(network: Network) = recheck("wifi available", null)
    override fun onLost(network: Network) = recheck("wifi lost", null)
    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
      recheck("wifi changed", caps)

    private fun recheck(reason: String, caps: NetworkCapabilities?) {
      onNetworkNamed(reason, ssidFrom(caps) ?: currentSsid())
    }
  }

  /** The SSID already inside the capabilities we were given, without a binder call of our own. */
  private fun ssidFrom(caps: NetworkCapabilities?): String? = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val info = caps?.transportInfo as? WifiInfo ?: return null
    info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
  }.getOrNull()

  /**
   * Act on the network's name, and only when it is news.
   *
   * The early return is the whole point: an unchanged SSID means an unchanged answer, so the firehose
   * of capability updates costs one string comparison. `haveSeenSsid` is separate from the value
   * because null is a real answer — no Wi-Fi — and has to be distinguishable from "not asked yet",
   * or the very first event after losing Wi-Fi would be swallowed.
   *
   * **A name we could not read is not a network we are not on.** This is the expensive distinction.
   * `getSSID()` returns `<unknown ssid>` whenever the platform declines — a momentary app-op or
   * location-attribution hiccup is enough — and treating that as "you left work" resumes the GPS,
   * then the next event puts it away again. Flapping is far worse than either state: a cold GPS
   * reacquisition is the most expensive thing that radio does, and doing it repeatedly all day costs
   * more than simply leaving the receiver on would have. So while Wi-Fi is still up, an unreadable
   * name keeps the last known one and asks again shortly. Only Wi-Fi actually going away clears it —
   * and that is read from the transport, which carries no location-sensitive field to be redacted.
   */
  private fun onNetworkNamed(reason: String, ssid: String?) {
    val unreadable = ssid == null && wifiIsUp()
    val resolved = if (unreadable && haveSeenSsid) lastSeenSsid else ssid

    // A network is available a moment before it can be named, so an unnamed reading deserves another
    // look — but a *bounded* number of them. An unconditional re-check is how a poll gets built by
    // accident: if the name never becomes readable, a 2.5s retry is a 2.5s poll forever, which is the
    // very cost this whole change exists to remove. Two tries, then live with the last known name
    // until a readable event or a genuine Wi-Fi loss arrives on its own.
    handler.removeCallbacks(settleCheck)
    if (ssid == null && settleTries < MAX_SETTLE_TRIES) {
      settleTries++
      handler.postDelayed(settleCheck, SSID_SETTLE_MS)
    }
    if (ssid != null) settleTries = 0

    if (haveSeenSsid && resolved == lastSeenSsid) return
    lastSeenSsid = resolved
    haveSeenSsid = true
    handler.post { applyPolicy(reason) }
  }

  private val settleCheck = Runnable { onNetworkNamed("wifi settled", currentSsid()) }

  /**
   * Whether Wi-Fi is up at all, which is the one part of this that cannot be redacted.
   *
   * `hasTransport` is plain `ACCESS_NETWORK_STATE` — no location permission, no app-op, no
   * foreground requirement — so it is the trustworthy half of the question. Presence comes from here;
   * identity comes from the SSID. Keeping them apart is what stops an unreadable name from being
   * mistaken for an absent network.
   */
  private fun wifiIsUp(): Boolean = runCatching {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    val active = manager.activeNetwork ?: return false
    manager.getNetworkCapabilities(active)
      ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
  }.getOrDefault(false)

  private fun unwatchNetworks() {
    val callback = networkCallback ?: return
    networkCallback = null
    val manager = getSystemService(ConnectivityManager::class.java) ?: return
    runCatching { manager.unregisterNetworkCallback(callback) }
  }

  // --- location -------------------------------------------------------------

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
      updatesRequested = true
    } catch (_: IllegalArgumentException) {
      // GPS provider missing (emulator edge case)
      stopSelf()
    } catch (_: SecurityException) {
      standDown("location permission revoked")
    }
  }

  /** Let go of the radio. The service stays foreground; only the fixes stop. */
  private fun stopLocationUpdates() {
    updatesRequested = false
    runCatching { locationManager?.removeUpdates(this) }
  }

  override fun onLocationChanged(location: Location) {
    trackStillness(location)

    // Weather rides on the fix the track already paid for — rounded to ~1 km first, refreshed
    // only when the cache has gone stale, and never the reason a radio is on. A fix inside a
    // privacy zone still feeds this: the zone promise is about the track, and what leaves here
    // is a neighbourhood-sized rounding, not a place.
    WeatherFetcher.onFix(this, location.latitude, location.longitude)

    val zone = zoneAt(location)
    if (zone != null) {
      // **The fact, never the place.** A fix inside home or work still does not reach the track —
      // that promise is why the zones exist, and the tiles that sync out depend on it. What is
      // written instead is one line saying you arrived somewhere you had named, with no latitude
      // and no longitude anywhere in it. "Went home at 19:40" locates you only if you already know
      // where home is, which whoever reads this file already does.
      noteZoneArrival(zone, location.time)
      applyPolicy("fix in zone")
      return
    }
    // Out of every zone: remember that, so coming back writes an arrival rather than being
    // swallowed as "still there".
    setLastZone(null)
    val file = File(RecorderModule.tracksDir(this), "${dayFormat.format(Date())}.csv")
    file.appendText(
      "${location.time},${location.latitude},${location.longitude},${location.accuracy}\n"
    )
    applyPolicy("fix")
  }

  /**
   * Keep the anchor a fix has to wander from before the phone counts as moving.
   *
   * Measured from a fixed anchor rather than from the previous fix on purpose. Consecutive fixes are
   * always metres apart even on a desk, so "the last one was close" would call a walk down the
   * street still; and a slow drift measured pairwise never accumulates. The anchor moves only when
   * something genuinely left it, and its timestamp is what stillness is counted from.
   */
  private fun trackStillness(location: Location) {
    val previous = anchor
    if (previous == null) {
      anchor = location
      anchorAtMs = System.currentTimeMillis()
      return
    }
    val results = FloatArray(1)
    Location.distanceBetween(
      previous.latitude, previous.longitude,
      location.latitude, location.longitude, results,
    )
    if (!Power.stillAt(results[0].toDouble())) {
      anchor = location
      anchorAtMs = System.currentTimeMillis()
    }
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

  /** So the settings screen can say why the radio is off instead of looking broken. */
  private fun reportState(next: Power.State) {
    RecorderModule.prefs(this).edit()
      .putString(RecorderModule.KEY_POWER_STATE, next.name)
      .apply()
  }

  /**
   * Which named zone you are in, or null for anywhere else.
   *
   * **The network first, the radius only as a backstop.** A GPS fix indoors drifts or disappears
   * altogether, so a circle round your front door both misses the arrival and then teleports you out
   * of it again a minute later — which is exactly what a privacy zone must never do. The name of the
   * network you are connected to has none of that: it is exact, it is instant, and it is true the
   * moment you walk in the door.
   *
   * The radius is kept rather than replaced, and deliberately. If it were removed, turning Wi-Fi off
   * would quietly start writing your home address to the track — a privacy guarantee that can be
   * disabled by a toggle in the status bar is not one. Either signal suppresses; both have to be
   * absent to record.
   */
  private fun zoneAt(location: Location): String? = networkZone() ?: radiusZone(location)

  /**
   * The zone whose network you are connected to.
   *
   * `WifiManager.connectionInfo` is deprecated and still the only way to read this from a service —
   * the replacement, `NetworkCapabilities.transportInfo`, needs a callback registered against a
   * network request, which is a lot of machinery to answer a question asked once per fix.
   *
   * Android quotes the SSID and returns `<unknown ssid>` when it will not say — no location
   * permission, or location switched off entirely. Both come back as null here rather than as a
   * network called `<unknown ssid>`, which would otherwise match a zone saved under that name.
   */
  private fun networkZone(): String? {
    val ssid = currentSsid() ?: return null
    val prefs = RecorderModule.prefs(this)
    return ZONE_NAMES.firstOrNull { prefs.getString("net_$it", null) == ssid }
  }

  private fun currentSsid(): String? = runCatching {
    val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return null
    @Suppress("DEPRECATION")
    val raw = wifi.connectionInfo?.ssid ?: return null
    raw.trim('"').takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
  }.getOrNull()

  /** The old test, unchanged: which zone's circle the fix falls inside. */
  private fun radiusZone(location: Location): String? {
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

  private fun updateNotification(next: Power.State, zone: String?) {
    runCatching {
      (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .notify(NOTIFICATION_ID, buildNotification(next, zone))
    }
  }

  private fun buildNotification(next: Power.State, zone: String?): Notification {
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
      .setContentText(Power.describe(next, zone))
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

    /**
     * Metres a fix must differ by before the framework hands it to us.
     *
     * Raised from 5m. A fog cell is about ten metres across and the trail between two fixes is
     * filled in with Bresenham, so fixes five metres apart drew nothing the previous one had not
     * already drawn — they only woke the process to append a line. Twenty metres still lands in a
     * neighbouring cell and cuts the callbacks a walk produces by roughly three quarters.
     */
    const val MIN_DISTANCE_M = 20f

    val ZONE_NAMES = listOf("home", "work")

    /** The zone the last fix was in, so a restart does not invent an arrival. */
    const val KEY_LAST_ZONE = "last_zone"

    /** What Android says instead of a name when it will not tell you the network. */
    const val UNKNOWN_SSID = "<unknown ssid>"

    /** Minutes without moving before the radio is switched off. */
    const val DEFAULT_STILL_AFTER_MIN = 6

    /** Sent to ourselves by the watchdog alarm to end a still pause. */
    const val ACTION_WAKE = "expo.modules.recorder.WAKE"
    private const val WAKE_REQUEST = 4208

    /** How long the SSID takes to become readable after the network is available. */
    private const val SSID_SETTLE_MS = 2_500L

    /** Re-reads before accepting that the name is not coming. Two, not a poll. */
    private const val MAX_SETTLE_TRIES = 2
  }
}
