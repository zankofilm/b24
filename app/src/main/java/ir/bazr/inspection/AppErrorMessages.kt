package ir.bazr.inspection

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Error from a local, verified validation rule; safe to show in the UI. */
class UserActionException(message: String) : IOException(message)

/** Only actionable Persian text is shown or persisted; server HTML and stack traces stay private. */
object AppErrorMessages {
    fun forUser(cause: Throwable): String = when (cause) {
        is UserActionException -> cause.message ?: "اطلاعات واردشده معتبر نیست"
        is ApiException -> {
            val message = when (cause.error) {
                "invalid_json" -> "سرور محتوای JSON گزارش را نپذیرفت؛ نسخه API و محدودیت‌های هاست را بررسی کنید."
                "invalid_submission" -> "ساختار اطلاعات مأموریت یا پاسخ‌ها در سرور پذیرفته نشد."
                "invalid_uploads" -> "ساختار فهرست مستندات گزارش معتبر نیست."
                "invalid_upload_reference" -> "یکی از شناسه‌های مستندات در سرور معتبر نیست؛ مستند را بررسی کنید."
                "unscored_acknowledgement_required" -> "تأیید بندهای بدون امتیاز با سرور مطابقت ندارد؛ گزارش را دوباره بازبینی و امضا کنید."
                "quality_gate_failed" -> "برخی امتیازها، توضیحات یا مستندات نیاز به اصلاح دارند."
                "signature_migration_required", "report_migration_required" ->
                    "سرور هنوز برای امضای لمسی و گزارش رسمی ارتقا نیافته است."
                "pdf_renderer_not_configured", "mpdf_missing", "official_pdf_unavailable" ->
                    "صدور گزارش رسمی هنوز روی سرور فعال نشده است."
                "manager_approval_required" -> "گزارش هنوز به تأیید مدیر نرسیده است."
                "signature_required", "invalid_signature_png", "invalid_signature_dimensions", "unsigned_report" ->
                    "امضای گزارش معتبر نیست؛ گزارش را دوباره امضا کنید."
                "historical_checklist_snapshot_missing" -> "گزارش قدیمی اطلاعات تفصیلی کافی ندارد."
                "invalid_server_response", "response_too_large" ->
                    "پاسخ سرور JSON معتبر نیست؛ احتمال محدودیت امنیتی هاست یا خطای سمت سرور وجود دارد."
                else -> when (cause.status) {
                    400 -> "سرور ساختار درخواست را نپذیرفت."
                    401 -> "نشست ورود منقضی شده است؛ مجدداً وارد شوید."
                    403 -> "حساب یا دستگاه مجاز به ارسال این گزارش نیست."
                    404 -> "سرویس موردنیاز روی سرور پیدا نشد."
                    408, 429 -> "سرور فعلاً پاسخ‌گو نیست؛ بعداً دوباره تلاش کنید."
                    in 500..599 -> "خطای سرور؛ گزارش در دستگاه حفظ شده است."
                    else -> "درخواست پذیرفته نشد (کد ${cause.status})."
                }
            }
            val code = cause.error.take(64).takeIf { it.matches(Regex("[a-zA-Z0-9_:-]+")) }.orEmpty()
            val request = cause.requestId.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{8,128}")) }.orEmpty()
            val versionHint = if (cause.status == 400 && cause.submitVersion.isBlank())
                "\nنسخه API امضا/گزارش روی سرور تأیید نشده است." else ""
            message + "\nHTTP ${cause.status}" + (if (code.isNotEmpty()) " · خطا: $code" else "") +
                (if (request.isNotEmpty()) "\nشناسه پیگیری: $request" else "") + versionHint
        }
        is SocketTimeoutException -> "زمان پاسخ‌گویی سرور تمام شد؛ اتصال را بررسی و مجدداً تلاش کنید."
        is UnknownHostException, is ConnectException -> "ارتباط با سرور برقرار نشد؛ اتصال اینترنت را بررسی کنید."
        is SSLException -> "اتصال امن HTTPS برقرار نشد؛ گواهی سرور را بررسی کنید."
        else -> "عملیات تکمیل نشد؛ اطلاعات ذخیره‌شده حفظ شده‌اند." +
            "\nجزئیات فنی: ${cause.javaClass.simpleName}" +
            (cause.message?.take(120)?.let { " · $it" } ?: "")
    }
}
