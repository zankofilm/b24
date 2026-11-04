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
        is ApiException -> when {
            cause.error in setOf("signature_migration_required", "report_migration_required") ->
                "سرور هنوز برای امضای لمسی و گزارش رسمی ارتقا نیافته است. از مدیر سامانه پیگیری کنید."
            cause.error in setOf("pdf_renderer_not_configured", "mpdf_missing", "official_pdf_unavailable") ->
                "صدور گزارش رسمی هنوز روی سرور فعال نشده است؛ با مدیر سامانه هماهنگ کنید."
            cause.error == "manager_approval_required" ->
                "گزارش هنوز به تأیید مدیر نرسیده و PDF رسمی قابل دانلود نیست."
            cause.error in setOf("signature_required", "invalid_signature_png", "unsigned_report") ->
                "امضای گزارش معتبر نیست؛ گزارش را بازبینی و دوباره امضا کنید."
            cause.error == "historical_checklist_snapshot_missing" ->
                "این گزارش قدیمی، اطلاعات تفصیلی لازم برای خروجی رسمی را ندارد."
            cause.error == "invalid_server_response" || cause.error == "response_too_large" ->
                "پاسخ غیرمنتظره API (کد ${cause.status})؛ تنظیمات سرور و مجوزها را بررسی کنید."
            cause.status == 401 -> "نشست ورود منقضی شده است؛ دوباره وارد شوید و سپس تلاش کنید."
            cause.status == 403 -> "دسترسی این حساب یا دستگاه مجاز نیست؛ از مدیر سامانه پیگیری کنید."
            cause.status == 404 -> "سرویس موردنیاز روی سرور پیدا نشد؛ نسخه API را بررسی کنید."
            cause.status == 408 || cause.status == 429 -> "سرور فعلاً پاسخ‌گو نیست؛ کمی بعد دوباره تلاش کنید."
            cause.status in 500..599 -> "خطای موقت سرور؛ اطلاعات محلی شما حفظ شده است."
            else -> "درخواست پذیرفته نشد (کد ${cause.status})؛ اطلاعات را بررسی کنید."
        }
        is SocketTimeoutException -> "زمان پاسخ‌گویی سرور تمام شد؛ اتصال را بررسی و مجدداً تلاش کنید."
        is UnknownHostException, is ConnectException -> "ارتباط با سرور برقرار نشد؛ اتصال اینترنت را بررسی کنید."
        is SSLException -> "اتصال امن HTTPS برقرار نشد؛ گواهی سرور را بررسی کنید."
        else -> "عملیات تکمیل نشد؛ اطلاعات ذخیره‌شده حفظ شده‌اند."
    }
}
