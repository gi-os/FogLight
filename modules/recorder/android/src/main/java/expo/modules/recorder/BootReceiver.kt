package expo.modules.recorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restarts recording after a reboot if it was on — and only if it can actually work.
 *
 * The permission check is here as well as in the service because this is the path that turns a
 * broken recorder into a broken phone. `startForegroundService` is a promise that a foreground
 * service will appear within five seconds; a location-typed one thrown out by a missing permission
 * breaks that promise by dying, gets restarted, and does it again — at boot, unattended, with the
 * user asleep. Nothing here is worth that, so a recorder that cannot record stays off and says so.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    if (!RecorderModule.prefs(context).getBoolean(RecorderModule.KEY_RUNNING, false)) return

    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse =
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "not restarting recorder: location permission not granted")
      RecorderModule.prefs(context).edit()
        .putBoolean(RecorderModule.KEY_RUNNING, false)
        .putString(RecorderModule.KEY_LAST_ERROR, "location permission not granted")
        .apply()
      return
    }

    val serviceIntent = Intent(context, RecorderService::class.java)
    // A receiver that throws is a boot that logs a crash. It can only be handled by not caring.
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(serviceIntent)
      } else {
        context.startService(serviceIntent)
      }
    }.onFailure { Log.w(TAG, "boot restart refused", it) }
  }

  private companion object {
    const val TAG = "RecorderBoot"
  }
}
