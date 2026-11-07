package ir.bazr.inspection

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.time.Instant

/** Notification-only device-authenticated poll. It does not obtain a general mobile session. */
class NoticePollWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val store = try { SecureStore(applicationContext) } catch (_: Exception) { return Result.retry() }
        if (store.getString("notify_notices_enabled") != "yes") return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        val deviceToken = try { store.loadStrict("notification_device_token") } catch (_: Exception) { return Result.retry() }
            ?: return Result.success()
        val deviceId = store.getString("device_id")?.takeIf { it.isNotBlank() } ?: return Result.success()

        return try {
            val keys = DeviceKeys()
            val timestamp = Instant.now().toString()
            val nonce = keys.nonce()
            val signedMessage = "notice-poll|$deviceId|$timestamp|$nonce"
            val request = JSONObject()
                .put("device_id", deviceId)
                .put("device_token", deviceToken)
                .put("timestamp", timestamp)
                .put("nonce", nonce)
                .put("signature", keys.signed(signedMessage))
            val response = ApiClient(BuildConfig.API_BASE_URL).post("mobile_notice_poll.php", request)
            val notices = response.optJSONArray("notices") ?: return Result.success()
            NoticeNotificationDispatcher.dispatch(applicationContext, notices, store)
            Result.success()
        } catch (error: ApiException) {
            // Expired/revoked devices must not retry forever. The next foreground login/sync
            // re-establishes the device session; transient server/network errors are retried.
            if (error.status in setOf(400, 401, 403, 404, 409, 422)) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
