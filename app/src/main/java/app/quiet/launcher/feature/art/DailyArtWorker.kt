package app.quiet.launcher.feature.art

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.quiet.launcher.container
import java.util.concurrent.TimeUnit

class DailyArtWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Quiet Underwater beside classic Quiet: no daily job unless it is the home app (it refreshes on open).
        if (!app.quiet.launcher.feature.underwater.engine.Underwater.ownsDevice(applicationContext)) return Result.success()
        try {
            applicationContext.container.art.refreshIfStale()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(ArtHttp.TAG, "daily art worker failed", e)
        }
        return Result.success()
    }
}

object ArtScheduler {
    const val UNIQUE_NAME = "quiet_daily_art"

    /** Idempotent; UPDATE so a changed constraint (e.g. mobile data allowed) reaches an existing job. */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val req = PeriodicWorkRequestBuilder<DailyArtWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
