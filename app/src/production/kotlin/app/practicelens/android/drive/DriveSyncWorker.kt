package app.practicelens.android.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Production-only placeholder. Demo builds do not package WorkManager.
        return Result.success()
    }
}
