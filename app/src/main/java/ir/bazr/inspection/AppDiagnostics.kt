package ir.bazr.inspection

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Minimal on-device diagnostics for recoverable UI failures.
 * Stores only the exception type/message and stack trace; never stores draft contents,
 * authentication tokens, organization names, or evidence files.
 */
object AppDiagnostics {
    private const val FILE_NAME = "last_app_diagnostic.txt"
    private const val MAX_CHARS = 18_000

    fun record(context: Context, area: String, error: Exception) {
        val body = buildString {
            appendLine("BAZR Android diagnostic")
            appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("area=${area.take(80)}")
            appendLine("exception=${error.javaClass.name}")
            appendLine("message=${(error.message ?: "").take(500)}")
            appendLine("stacktrace:")
            append(error.stackTraceToString().take(MAX_CHARS))
        }
        runCatching {
            context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use {
                it.write(body.toByteArray(Charsets.UTF_8))
                it.flush()
            }
        }
    }

    fun read(context: Context): String =
        runCatching {
            File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText(Charsets.UTF_8)
                ?.take(MAX_CHARS)
        }.getOrNull() ?: "گزارش فنی هنوز ثبت نشده است."
}
