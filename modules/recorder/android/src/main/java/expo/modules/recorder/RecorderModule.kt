package expo.modules.recorder

import android.content.Context
import android.content.Intent
import android.os.Build
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File

class RecorderModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("Recorder")

    Function("start") { intervalMs: Int ->
      val context = appContext.reactContext ?: return@Function null
      prefs(context).edit()
        .putBoolean(KEY_RUNNING, true)
        .putInt(KEY_INTERVAL, intervalMs)
        .apply()
      val intent = Intent(context, RecorderService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
      null
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

    fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tracksDir(context: Context): File =
      File(context.filesDir, "tracks").apply { mkdirs() }
  }
}
