package expo.modules.recorder

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The hourly half of the weather bargain, and the whole of its ambition.
 *
 * WorkManager, hourly, network required — which means it runs inside the system's existing
 * maintenance windows and **never wakes the phone for weather**. It never touches a location
 * API: the coordinate is whatever rounded one the recorder last saw, and if there has never
 * been one it does nothing at all. A phone left in a drawer costs zero.
 *
 * Always reports success: a failed fetch keeps the stale cache on purpose, and a WorkManager
 * retry with backoff would just be a second request the next hour was going to make anyway.
 */
class WeatherWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val coord = WeatherFetcher.lastCoordinate(applicationContext) ?: return Result.success()
        if (!Weather.due(WeatherFetcher.cachedUpdatedAt(applicationContext), System.currentTimeMillis())) {
            return Result.success()
        }
        WeatherFetcher.fetchNow(applicationContext, coord.first, coord.second)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "weather-refresh"

        /** Idempotent; KEEP means calling this on every service start reschedules nothing. */
        fun schedule(context: Context) {
            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<WeatherWorker>(1, TimeUnit.HOURS)
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        .build(),
                )
            }
        }
    }
}
