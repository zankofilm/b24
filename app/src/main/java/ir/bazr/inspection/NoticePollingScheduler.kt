package ir.bazr.inspection

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Android may defer periodic work under Doze/battery rules; this is not instant FCM push. */
object NoticePollingScheduler {
    private const val UNIQUE_WORK = "bazr_org_notice_poll_v1"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<NoticePollWorker>(NoticeRules.POLL_INTERVAL_MINUTES.toLong(), TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setInitialDelay(NoticeRules.POLL_INTERVAL_MINUTES.toLong(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
    }
}
