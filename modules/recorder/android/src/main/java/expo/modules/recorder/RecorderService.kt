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
    startAsForeground()
    startLocationUpdates()
    isServiceRunning = true
    return START_STICKY
  }

  override fun onDestroy() {
    locationManager?.removeUpdates(this)
    isServiceRunning = false
    super.onDestroy()
  }

  private fun startAsForeground() {
    val notification = buildNotification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
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
    val file = File(RecorderModule.tracksDir(this), "${dayFormat.format(Date())}.csv")
    file.appendText(
      "${location.time},${location.latitude},${location.longitude},${location.accuracy}\n"
    )
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
    @Volatile var isServiceRunning = false
    const val CHANNEL_ID = "lightfog_recording"
    const val NOTIFICATION_ID = 4207
    const val MIN_DISTANCE_M = 5f
  }
}
