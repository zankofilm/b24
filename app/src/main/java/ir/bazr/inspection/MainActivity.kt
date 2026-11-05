package ir.bazr.inspection

import android.content.Intent
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.util.Base64
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import java.security.MessageDigest
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import javax.crypto.Cipher

/** Native Android inspector client. Server remains the source of authority. */
class MainActivity : AppCompatActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var store: SecureStore
    private lateinit var api: ApiClient
    private lateinit var keys: DeviceKeys
    private lateinit var evidenceVault: EvidenceVault
    private lateinit var outbox: SubmissionQueue
    private lateinit var receiptHistory: ReceiptHistory
    private lateinit var signatures: SignedDraft
    private var bulkPending: ArrayDeque<String>? = null
    private var bulkOk = 0
    private var bulkFailed = 0
    // Explicit native progress surface. Does not claim server acceptance until a receipt arrives.
    private var transferDialog: AlertDialog? = null
    private var transferTitle: TextView? = null
    private var transferDetail: TextView? = null
    private var transferBar: LinearProgressIndicator? = null
    private var bulkTotal = 0
    private var pendingOfficialPdf: Pair<Int, String>? = null
    private val officialPdfPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val selection = pendingOfficialPdf
        pendingOfficialPdf = null
        if (uri != null && selection != null) {
            val device = deviceToken
            if (device == null) { notifyShort("ابتدا وارد حساب بازرس شوید") }
            else work({
                val session = sessionFor(device)
                val pdf = api.downloadOfficialPdf(selection.first, selection.second, session)
                val output = contentResolver.openOutputStream(uri) ?: throw IOException("فایل مقصد قابل نوشتن نیست")
                output.use { it.write(pdf) }
            }) { notifyShort("PDF رسمی از سرور دریافت و ذخیره شد") }
        }
    }
    private var accountToken: String? = null  // Web login token: never persisted.
    private var deviceToken: String? = null   // In-memory after normal or biometric unlock.
    private var sessionToken: String? = null  // Short-lived mobile bearer.
    private var serverSnapshot = JSONObject()
    private var selectedMission: JSONObject? = null
    private var selectedMissionKey: String? = null
    private var openDraft: InspectionDraft? = null
    private var pickerQuestion: String? = null
    private var busy = false
    private lateinit var content: LinearLayout
    private lateinit var shell: LinearLayout
    private var loadingBar: LinearProgressIndicator? = null
    private var activeTab = "home"
    private var currentQuestionIndex = 0
    /** Non-certified PDF data is kept in memory only while the system file picker is open. */
    private var pendingPdfExport: JSONObject? = null
    private val pdfFilePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val report = pendingPdfExport
        pendingPdfExport = null
        if (uri != null && report != null) {
            val key = report.optString("mission_key")
            val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
            val assigned = (0 until missions.length()).any { index ->
                missions.optJSONObject(index)?.let(::missionKey) == key
            }
            if (deviceToken == null || !assigned) {
                notifyShort("برای تهیه گزارش، مجدداً به حساب مجاز بازرس وارد شوید")
            } else {
                work({
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw IOException("امکان نوشتن در فایل انتخاب‌شده وجود ندارد")
                    output.use { LocalReportPdf().write(it, report) }
                }) { notifyShort("نسخه PDF محلی و غیررسمی ذخیره شد") }
            }
        }
    }
    // Official institutional palette; the accents are intentionally restrained.
    private val navy = Color.rgb(8, 31, 56)
    private val teal = Color.rgb(170, 130, 56) // legacy accent identifier; now ceremonial gold
    private val gold = Color.rgb(197, 161, 90)
    private val muted = Color.rgb(103, 117, 135)
    private val ink = Color.rgb(20, 45, 71)
    private val pageBg = Color.rgb(245, 247, 250)
    private val line = Color.rgb(220, 227, 234)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store.putString("notify_missions_enabled", if (granted) "yes" else "no")
        notifyShort(if (granted) "اعلان مأموریت‌های تازه فعال شد" else "مجوز اعلان داده نشد")
        showHome("account")
    }
    private val evidencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val question = store.load("pending_picker_question")
        store.clear("pending_picker_question")
        if (uri != null && !question.isNullOrBlank()) importEvidence(uri, question, false)
    }
    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val pending = store.load("pending_capture")?.let { runCatching { JSONObject(it) }.getOrNull() }
        store.clear("pending_capture")
        if (pending != null) {
            val name = pending.optString("name")
            val question = pending.optString("question")
            if (!name.matches(Regex("capture-[a-f0-9-]{36}\\.jpg"))) return@registerForActivityResult
            val file = File(File(cacheDir, "camera"), name)
            if (success && file.isFile && question.isNotBlank()) {
                // FileProvider hands a camera application a temporary, app-private content Uri.
                importEvidence(FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file), question, true, file)
            } else file.delete()
        }
    }

    private data class ImportedEvidenceResult(
        val reference: String,
        val originalBytes: Int,
        val isImage: Boolean,
        val uploadBytes: Int? = null,
        val optimizationWarning: String? = null
    )

    private fun importEvidence(uri: Uri, question: String, camera: Boolean, temporary: File? = null) {
        work({
            var importedReference: String? = null
            try {
                val reference = evidenceVault.importUri(contentResolver, uri, camera)
                importedReference = reference
                val original = evidenceVault.read(reference, contentResolver)
                if (original.mime.startsWith("image/") && original.bytes.size > PhotoUploadPolicy.TARGET_BYTES) {
                    try {
                        val prepared = PhotoUploadOptimizer.prepare(original)
                        if (prepared.optimized) {
                            val cacheWarning = try {
                                evidenceVault.savePrepared(reference, prepared)
                                null
                            } catch (error: Exception) {
                                error.message ?: "ذخیره نسخه فشرده در حافظه امن انجام نشد"
                            }
                            ImportedEvidenceResult(
                                reference,
                                original.bytes.size,
                                true,
                                prepared.bytes.size,
                                cacheWarning
                            )
                        } else {
                            ImportedEvidenceResult(reference, original.bytes.size, true)
                        }
                    } catch (error: Exception) {
                        // Keep the original encrypted attachment even when the 200 KiB
                        // transport copy cannot be made without harming legibility.
                        ImportedEvidenceResult(
                            reference,
                            original.bytes.size,
                            true,
                            null,
                            error.message ?: "بهینه‌سازی عکس انجام نشد"
                        )
                    }
                } else {
                    ImportedEvidenceResult(reference, original.bytes.size, original.mime.startsWith("image/"))
                }
            } catch (error: Exception) {
                importedReference?.let { evidenceVault.delete(it) }
                throw error
            } finally {
                temporary?.delete()
            }
        }) { result ->
            val reference = result.reference
            val draft = openDraft
            if (draft == null || selectedMission == null || outbox.get(draft.key) != null) {
                evidenceVault.delete(reference)
                notifyShort("مأموریت انتخاب‌شده تغییر کرده؛ مستند اضافه نشد")
            } else {
                if (!EvidenceRefs.add(draft.attachments, question, reference)) {
                    evidenceVault.delete(reference)
                    notifyShort("برای هر سؤال حداکثر چهار مستند مستقل مجاز است")
                } else {
                    draft.save()
                    selectedMission?.let { showMission(it, currentQuestionIndex) }
                    val message = when {
                        !result.isImage -> "مستند به‌صورت رمزنگاری‌شده ذخیره شد؛ PDF بدون تغییر می‌ماند"
                        result.uploadBytes != null -> "عکس بهینه شد: ${(result.originalBytes + 1023) / 1024} ← ${(result.uploadBytes + 1023) / 1024} کیلوبایت؛ اصل عکس امن حفظ شد"
                        result.optimizationWarning != null -> "عکس ذخیره شد، اما رسیدن به ۲۰۰ کیلوبایت با حفظ خوانایی ممکن نشد؛ نسخه اصلی محفوظ است"
                        result.originalBytes > PhotoUploadPolicy.TARGET_BYTES -> "عکس ذخیره شد؛ فشرده‌سازی نهایی هنگام ارسال دوباره بررسی می‌شود"
                        else -> "عکس زیر سقف ۲۰۰ کیلوبایت بود و بدون تغییر ذخیره شد"
                    }
                    notifyShort(message)
                }
            }
        }
    }

    private fun launchCamera(question: String) {
        val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
        val name = "capture-${UUID.randomUUID()}.jpg"
        val photo = File(cameraDir, name)
        store.save("pending_capture", JSONObject().put("name", name).put("question", question).toString())
        try {
            cameraCapture.launch(FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", photo))
        } catch (_: Exception) {
            store.clear("pending_capture")
            photo.delete()
            notifyShort("برنامه دوربین در این دستگاه در دسترس نیست")
        }
    }

    private fun chooseEvidence(question: String) {
        AlertDialog.Builder(this).setTitle("افزودن مستند بازرسی")
            .setMessage("عکس‌های بیشتر از ۲۰۰ کیلوبایت هنگام افزودن بهینه می‌شوند. نسخه اصلی با رمزنگاری در گوشی حفظ می‌شود و فقط نسخه کم‌حجم برای ارسال استفاده می‌شود. فایل‌های PDF بدون تغییر می‌مانند.")
            .setItems(arrayOf("ثبت عکس با دوربین", "انتخاب عکس یا PDF از گوشی")) { _, which ->
                if (which == 0) launchCamera(question)
                else {
                    store.save("pending_picker_question", question)
                    evidencePicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf"))
                }
            }.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        keys = DeviceKeys()
        api = ApiClient(BuildConfig.API_BASE_URL)
        evidenceVault = EvidenceVault(this, store)
        outbox = SubmissionQueue(store)
        receiptHistory = ReceiptHistory(store)
        signatures = SignedDraft(store)
        // Remove unreferenced, temporary plaintext camera files left by interrupted captures.
        val pendingName = store.load("pending_capture")?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }
        File(cacheDir, "camera").listFiles()?.filter { it.name != pendingName && it.lastModified() < System.currentTimeMillis() - 86_400_000L }
            ?.forEach { it.delete() }
        store.getString("device_id") ?: store.putString("device_id", UUID.randomUUID().toString())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectedMission != null) showHome("missions")
                else if (activeTab == "outbox") showHome("drafts")
                else if (activeTab != "home" && ::content.isInitialized) showHome("home")
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        val cached = store.load("mobile_sync")
        if (cached != null) try { serverSnapshot = JSONObject(cached) } catch (_: Exception) { }
        when {
            store.biometricEnabled() -> showBiometricLock()
            store.load("device_token") != null -> {
                deviceToken = store.load("device_token")
                showHome()
                connectAndSync()
            }
            store.getString("request_id") != null -> showPending()
            else -> showLogin()
        }
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
    private fun deviceId() = store.getString("device_id") ?: error("missing device ID")
    private fun notifyShort(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun text(text: String, size: Int, bold: Boolean = false, color: Int = ink): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            includeFontPadding = true
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    private fun gap(height: Int = 12) {
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    /** Native app shell: fixed toolbar, scrollable content and native bottom navigation. */
    private fun screen(title: String, subtitle: String, withNav: Boolean = false): LinearLayout {
        window.statusBarColor = navy
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pageBg)
        }
        loadingBar = LinearProgressIndicator(this).apply {
            isIndeterminate = true
            setIndicatorColor(gold)
            trackColor = Color.rgb(230, 236, 243)
            visibility = if (busy) View.VISIBLE else View.GONE
        }
        shell.addView(loadingBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        shell.addView(appTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))
        shell.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(17), dp(18), dp(28))
        }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (withNav) shell.addView(bottomNavigation())
        setContentView(shell)
        if (title.isNotBlank()) label(title, 21, true, navy)
        if (subtitle.isNotBlank()) label(subtitle, 12, false, muted)
        if (title.isNotBlank() || subtitle.isNotBlank()) gap(10)
        return content
    }

    /** Compact institutional app bar. The full-width website banner is intentionally not used. */
    private fun appTopBar(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val mark = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = 0f
            setCardBackgroundColor(navy)
        }
        mark.addView(icon(R.drawable.ic_shield, gold, 25),
            FrameLayout.LayoutParams(dp(27), dp(27), Gravity.CENTER))
        row.addView(mark, LinearLayout.LayoutParams(dp(46), dp(46)))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), 0, dp(8), 0)
        }
        names.addView(text("سامانه بازرسی ادارات", 15, true, navy))
        names.addView(text("فرمانداری شهرستان جوانرود", 10, false, muted))
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val badge = TextView(this).apply {
            text = "بازرس"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(navy)
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(Color.rgb(248, 243, 232), 10)
        }
        row.addView(badge)
        wrapper.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        wrapper.addView(View(this).apply { setBackgroundColor(Color.rgb(226, 216, 194)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrapper
    }

    /** Subtly raised two-tone icon, consistent across dashboard and missions. */
    private fun iconBadge(drawable: Int, size: Int = 24): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(13).toFloat()
        strokeWidth = dp(1)
        strokeColor = Color.rgb(222, 200, 151)
        cardElevation = dp(1).toFloat()
        setCardBackgroundColor(Color.rgb(251, 247, 238))
        val image = icon(drawable, navy, size).apply {
            layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER)
            contentDescription = null // descriptive text immediately follows the icon
        }
        addView(image)
    }

    private fun label(value: String, size: Int = 16, bold: Boolean = false, color: Int = ink): TextView {
        val view = text(value, size, bold, color)
        view.setPadding(0, dp(3), 0, dp(6))
        content.addView(view)
        return view
    }
    private fun field(hint: String, value: String = "", password: Boolean = false, numeric: Boolean = false): EditText {
        val wrapper = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = Color.WHITE
            boxStrokeColor = gold
            setBoxCornerRadii(dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat())
            this.hint = hint
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (password) {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                startIconDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_shield)
            } else if (hint.contains("نام کاربری")) {
                startIconDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_person)
            }
            if (startIconDrawable != null) setStartIconTintList(ColorStateList.valueOf(muted))
        }
        val input = TextInputEditText(this).apply {
            setText(value)
            textSize = 15f
            setTextColor(ink)
            setHintTextColor(muted)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint.contains("توضیح") -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (hint.contains("توضیح")) { minLines = 3; maxLines = 5; gravity = Gravity.TOP or Gravity.RIGHT }
        }
        wrapper.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = dp(10)
        content.addView(wrapper, params)
        return input
    }
    private fun makeButton(caption: String, primary: Boolean = true, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = caption
            textSize = 15f
            isAllCaps = false
            cornerRadius = dp(13)
            minHeight = dp(52)
            backgroundTintList = ColorStateList.valueOf(if (primary) navy else Color.WHITE)
            setTextColor(if (primary) Color.WHITE else navy)
            strokeColor = ColorStateList.valueOf(if (primary) gold else line)
            strokeWidth = dp(1)
            elevation = if (primary) dp(1).toFloat() else 0f
            setOnClickListener { if (!busy) action() }
        }
    private fun button(caption: String, action: () -> Unit): MaterialButton {
        val b = makeButton(caption, true, action)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        params.bottomMargin = dp(9)
        content.addView(b, params)
        return b
    }
    private fun secondaryButton(caption: String, action: () -> Unit): MaterialButton {
        val b = makeButton(caption, false, action)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        params.bottomMargin = dp(9)
        content.addView(b, params)
        return b
    }
    private fun icon(res: Int, color: Int = teal, size: Int = 22): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        contentDescription = null // text is supplied by the adjacent accessible label
    }
    private fun card(textValue: String, action: () -> Unit) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1); strokeColor = line
            setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) action() }
        }
        val label = text(textValue, 15, true)
        label.setPadding(dp(16), dp(16), dp(16), dp(16))
        card.addView(label)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        content.addView(card, lp)
    }
    private fun chip(caption: String, background: Int, foreground: Int): TextView =
        text(caption, 12, true, foreground).apply {
            setBackground(rounded(background, 100))
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
    private fun sectionTitle(caption: String, actionLabel: String? = null, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(caption, 18, true, navy), LinearLayout.LayoutParams(0, dp(44), 1f))
        if (actionLabel != null && onClick != null) {
            val link = text(actionLabel, 13, true, teal)
            link.setPadding(dp(8), 0, dp(8), 0)
            link.isClickable = true; link.isFocusable = true
            link.setOnClickListener { onClick() }
            row.addView(link)
        }
        content.addView(row)
    }
    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(5), dp(8), dp(8))
            elevation = dp(8).toFloat()
        }
        val items = mutableListOf(
            Triple("home", "خانه", R.drawable.ic_home),
            Triple("missions", "مأموریت‌ها", R.drawable.ic_clipboard),
            Triple("departments", "ادارات مجاز", R.drawable.ic_building),
            Triple("drafts", "پیش‌نویس‌ها", R.drawable.ic_notes),
            Triple("account", "حساب", R.drawable.ic_person)
        )
        if (serverSnapshot.optJSONObject("supervisor")?.optBoolean("can_review", false) == true) {
            items.add(2, Triple("supervisor", "سربازرس", R.drawable.ic_shield))
        }
        for ((id, caption, drawable) in items) {
            val chosen = activeTab == id
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
                isClickable = true
                isFocusable = true
                contentDescription = caption
                setOnClickListener { if (!busy) showHome(id) }
            }
            item.addView(View(this).apply {
                setBackgroundColor(if (chosen) gold else Color.TRANSPARENT)
            }, LinearLayout.LayoutParams(dp(30), dp(3)).apply { bottomMargin = dp(5) })
            val iconHolder = FrameLayout(this).apply {
                background = rounded(if (chosen) Color.rgb(248, 243, 232) else Color.WHITE, 11)
                addView(icon(drawable, if (chosen) navy else muted, 21),
                    FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
            }
            item.addView(iconHolder, LinearLayout.LayoutParams(dp(42), dp(32)))
            val captionView = text(caption, 10, chosen, if (chosen) navy else muted)
            captionView.gravity = Gravity.CENTER
            item.addView(captionView)
            bar.addView(item, LinearLayout.LayoutParams(0, dp(61), 1f))
        }
        return bar
    }

    private fun statCard(number: String, caption: String, drawable: Int, background: Int, foreground: Int): View {
        val outer = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(14))
        }
        val badge = iconBadge(drawable, 23)
        inner.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        inner.addView(text(number, 27, true, navy).apply { setPadding(0, dp(6), 0, 0) })
        inner.addView(text(caption, 12, false, muted))
        outer.addView(inner)
        return outer
    }
    private fun statsRow(left: View, right: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val a = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
        val b = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
        row.addView(left, a); row.addView(right, b)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        content.addView(row, lp)
    }
    private fun hero(profileName: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(19).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(48, 72, 96)
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(navy)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(8, 31, 56), Color.rgb(23, 57, 86))).apply {
                cornerRadius = dp(19).toFloat()
            }
        }
        val name = profileName.trim().ifBlank { "بازرس" }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text("میز کار سازمانی", 11, true, gold))
        copy.addView(text("سلام، $name", 21, true, Color.WHITE).apply {
            setPadding(0, dp(7), 0, dp(5))
        })
        copy.addView(text("مأموریت‌ها و گزارش‌های شما", 12, false, Color.rgb(215, 225, 235)))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seal = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(179, 145, 82)
            setCardBackgroundColor(Color.rgb(20, 48, 72))
            cardElevation = 0f
        }
        seal.addView(icon(R.drawable.ic_shield, gold, 29),
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        row.addView(seal, LinearLayout.LayoutParams(dp(54), dp(54)))
        card.addView(row)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(14)
    }

    private fun emptyState(title: String, detail: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(23), dp(20), dp(23))
            setBackground(rounded(Color.WHITE, 16))
        }
        box.addView(icon(R.drawable.ic_clipboard, muted, 32))
        val titleView = text(title, 16, true, navy).apply { gravity = Gravity.CENTER }
        titleView.setPadding(0,dp(10),0,dp(6))
        box.addView(titleView)
        val detailView = text(detail, 13, false, muted).apply { gravity = Gravity.CENTER }
        box.addView(detailView)
        content.addView(box)
    }
    private fun <T> work(task: () -> T, done: (T) -> Unit) {
        if (busy) return
        busy = true
        loadingBar?.visibility = View.VISIBLE
        io.execute {
            try {
                val result = task()
                runOnUiThread {
                    busy = false
                    loadingBar?.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) done(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    loadingBar?.visibility = View.GONE
                    if (!isFinishing && !isDestroyed) notifyShort(AppErrorMessages.forUser(e))
                }
            }
        }
    }

    private fun showLogin() {
        selectedMission = null
        activeTab = "home"
        screen("ورود بازرس", "دسترسی امن به مأموریت‌های سازمانی")

        val intro = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
        }
        val introBody = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(15), dp(15), dp(15), dp(15))
        }
        val seal = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            setCardBackgroundColor(navy)
            cardElevation = 0f
        }
        seal.addView(icon(R.drawable.ic_shield, gold, 27),
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        introBody.addView(seal, LinearLayout.LayoutParams(dp(52), dp(52)))
        val introText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        introText.addView(text("ورود به میز کار بازرس", 15, true, navy))
        introText.addView(text("ثبت و پیگیری مأموریت‌های محوله", 11, false, muted).apply {
            setPadding(0, dp(5), 0, 0)
        })
        introBody.addView(introText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        intro.addView(introBody)
        content.addView(intro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(18)
        sectionTitle("اطلاعات ورود")
        val username = field("نام کاربری")
        val password = field("رمز عبور", password = true)
        button("ورود امن و ثبت دستگاه") {
            val name = username.text.toString().trim()
            val pass = password.text.toString()
            if (name.isBlank() || pass.isBlank()) {
                notifyShort("نام کاربری و رمز عبور لازم است")
                return@button
            }
            work({
                val login = api.post("login.php", JSONObject().put("kind", "inspector").put("username", name).put("password", pass))
                val token = login.getString("token")
                val req = JSONObject()
                    .put("device_id", deviceId())
                    .put("public_key_pem", keys.publicKeyPem())
                    .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(190))
                    .put("app_version", BuildConfig.VERSION_NAME)
                val result = api.post("mobile_activate_authenticated.php", req, token)
                accountToken = token
                result
            }) { response ->
                store.putString("request_id", response.getString("request_id"))
                showPending()
                if (response.optString("status") == "approved") checkApproval()
            }
        }
        val securityNote = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(Color.rgb(239, 244, 249), 12)
        }
        securityNote.addView(icon(R.drawable.ic_shield, navy, 20))
        securityNote.addView(text("رمز عبور روی دستگاه ذخیره نمی‌شود؛ ورود اولیه به اینترنت نیاز دارد.", 11, false, muted).apply {
            setPadding(dp(9), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(securityNote)
        if (store.getString("request_id") != null) {
            gap(10)
            secondaryButton("پیگیری درخواست فعال‌سازی") { showPending() }
        }
    }

    private fun showPending() {
        selectedMission = null
        screen("تأیید دستگاه", "یک مرحله تا دسترسی به مأموریت‌ها باقی مانده است")
        gap(12)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18),dp(20),dp(18),dp(20))
            setBackground(rounded(Color.WHITE,18))
        }
        panel.addView(icon(R.drawable.ic_shield, teal, 30))
        val title = text("در انتظار تأیید مدیر سامانه", 17, true, navy)
        title.setPadding(0,dp(12),0,dp(8))
        panel.addView(title)
        panel.addView(text("برای حفظ امنیت اطلاعات بازرسی، این دستگاه باید توسط مدیر تأیید شود.",13,false,muted))
        panel.addView(text("کد درخواست: ${store.getString("request_id") ?: "ثبت نشده"}",12,false,muted).apply {
            setPadding(0,dp(12),0,0)
        })
        val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=dp(16)
        content.addView(panel,lp)
        button("بررسی وضعیت تأیید") { checkApproval() }
        secondaryButton("بازگشت به ورود") { showLogin() }
    }

    private fun signedBody(prefix: String, requestId: String? = null): JSONObject {
        val ts = Instant.now().toString()
        val nonce = keys.nonce()
        val id = deviceId()
        val message = if (prefix == "session") "session|$id|$ts|$nonce"
            else "activation-status|$requestId|$id|$ts|$nonce"
        val json = JSONObject().put("device_id", id).put("timestamp", ts)
            .put("nonce", nonce).put("signature", keys.signed(message))
        if (requestId != null) json.put("request_id", requestId)
        return json
    }
    private fun checkApproval() {
        val id=store.getString("request_id") ?: return
        work({ api.post("mobile_activate_status.php", signedBody("activation-status", id)) }) { response ->
            when (response.optString("status")) {
                "approved" -> {
                    val fresh = response.getString("device_token")
                    // Status checks rotate the device token; discard any old biometric binding.
                    store.clearBio()
                    store.save("device_token", fresh)
                    deviceToken = fresh
                    notifyShort("دستگاه تأیید شد")
                    showHome("home")
                    connectAndSync()
                }
                "revoked" -> { store.clear("device_token"); store.clearBio(); showPending(); notifyShort("دستگاه لغو شده است") }
                else -> notifyShort("در انتظار تأیید مدیر")
            }
        }
    }

    private fun sessionFor(token: String): String {
        val body = signedBody("session").put("device_token", token)
        return api.post("mobile_session.php", body).getString("token")
    }
    private fun connectAndSync() {
        val token=deviceToken ?: return
        work({
            val session = sessionFor(token)
            val payload = api.get("mobile_sync.php", session)
            val supervisorInbox = try { api.get("mobile_supervisor_inbox.php", session) }
                catch (_: Exception) { JSONObject().put("ok", true).put("can_review", false).put("items", JSONArray()) }
            payload.put("supervisor", supervisorInbox)
            Pair(session, payload)
        }) { (session, payload) ->
            val previous = serverSnapshot.optJSONArray("missions") ?: JSONArray()
            val previousKeys = (0 until previous.length()).mapNotNull { idx ->
                previous.optJSONObject(idx)?.let(::missionKey)?.takeIf { it.isNotBlank() }
            }.toSet()
            val current = payload.optJSONArray("missions") ?: JSONArray()
            val newCount = if (store.getString("last_sync") == null) 0 else
                (0 until current.length()).count { idx ->
                    val key = current.optJSONObject(idx)?.let(::missionKey).orEmpty()
                    key.isNotBlank() && key !in previousKeys
                }
            sessionToken = session
            serverSnapshot = payload
            store.save("mobile_sync", payload.toString())
            store.putString("last_sync", Instant.now().toString())
            showHome(if (activeTab in setOf("home", "missions", "departments", "drafts", "account", "supervisor") && (activeTab != "supervisor" || payload.optJSONObject("supervisor")?.optBoolean("can_review", false) == true)) activeTab else "home")
            if (newCount > 0) {
                sendMissionNotification(newCount)
                notifyShort("$newCount مأموریت جدید دریافت شد؛ از فهرست مأموریت‌ها مشاهده کنید")
            }
        }
    }

    private fun missionKey(mission: JSONObject): String =
        mission.optString("_server_key", mission.optString("id"))

    private fun missionDraft(mission: JSONObject): Boolean =
        missionKey(mission).let { it.isNotBlank() && store.load("draft:$it") != null }

    private fun reportCount(): Int = (serverSnapshot.optJSONArray("reports") ?: JSONArray()).length()

    /** Reading a draft never changes server-owned mission information. */
    private fun answeredQuestions(mission: JSONObject): Pair<Int, Int> {
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val total = questions.length()
        if (!missionDraft(mission)) return Pair(0, total)
        val draft = InspectionDraft(missionKey(mission), store)
        val answered = (0 until total).count { index ->
            val key = questions.optJSONObject(index)?.optString("id") ?: ""
            val value = draft.answers.optString(key).toDoubleOrNull()
            key.isNotBlank() && value != null && value in 0.0..100.0
        }
        return Pair(answered, total)
    }

    private fun lastSyncDescription(): String {
        val raw = store.getString("last_sync") ?: return "هنوز همگام‌سازی انجام نشده است"
        return try {
            val time = Instant.parse(raw).atZone(ZoneId.systemDefault())
            "آخرین همگام‌سازی: " + DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale("fa")).format(time)
        } catch (_: Exception) { "اطلاعات آخرین همگام‌سازی ثبت شده است" }
    }

    /** Manual-sync-only notification. No push service or background polling is implied. */
    private fun sendMissionNotification(count: Int) {
        if (store.getString("notify_missions_enabled") != "yes") return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "bazr_assigned_missions"
        manager.createNotificationChannel(NotificationChannel(
            channelId, "مأموریت‌های بازرس", NotificationManager.IMPORTANCE_DEFAULT))
        val openApp = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val action = PendingIntent.getActivity(this, 45, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(action)
            .setContentTitle("مأموریت جدید بازرسی")
            .setContentText("$count مأموریت تازه در همگام‌سازی دریافت شد")
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        manager.notify(45, notice)
    }

    private fun enableMissionNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            store.putString("notify_missions_enabled", "yes")
            notifyShort("اعلان مأموریت جدید هنگام همگام‌سازی فعال شد")
            showHome("account")
        }
    }

    private fun online(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun openAttachedEvidence(reference: String) {
        try {
            val evidence = evidenceVault.read(reference, contentResolver)
            if (evidence.mime.startsWith("image/")) {
                val bitmap = evidenceVault.imagePreview(reference, contentResolver)
                    ?: throw IllegalStateException("پیش‌نمایش عکس ایجاد نشد")
                val preview = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(dp(12),dp(12),dp(12),dp(12))
                }
                AlertDialog.Builder(this).setTitle("پیش‌نمایش مستند")
                    .setView(preview).setPositiveButton("بستن", null).show()
            } else {
                AlertDialog.Builder(this).setTitle("مستند PDF")
                    .setMessage("${evidenceVault.metadata(reference)}\n\nفایل به‌صورت رمزنگاری‌شده در خود برنامه نگهداری و هنگام ارسال بارگذاری می‌شود.")
                    .setPositiveButton("تأیید",null).show()
            }
        } catch (e: Exception) {
            notifyShort("مستند قابل خواندن نیست؛ دوباره پیوست کنید")
        }
    }

    private fun missionItem(mission: JSONObject, destination: LinearLayout = content) {
        val key = missionKey(mission)
        val name = mission.optString("orgName", "دستگاه اجرایی").ifBlank { "دستگاه اجرایی" }
        val date = mission.optString("date", "").trim()
        val available = mission.optBoolean("can_execute", false)
        val state = mission.optString("schedule_label", "").ifBlank { if (available) "قابل انجام" else "زمان‌بندی مأموریت" }
        val isDraft = key.isNotBlank() && missionDraft(mission)
        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) showMission(mission) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(15),dp(14),dp(15))
        }
        val symbol = iconBadge(R.drawable.ic_clipboard, 24)
        root.addView(symbol, LinearLayout.LayoutParams(dp(49), dp(49)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12),0,dp(9),0)
        }
        info.addView(text(name,15,true,navy))
        info.addView(text(listOf(date,state).filter { it.isNotBlank() }.joinToString("  •  "),12,false,muted))
        if (isDraft) {
            val (answered, total) = answeredQuestions(mission)
            info.addView(text("پیش‌نویس · $answered از $total پاسخ",12,true,teal).apply {
                setPadding(0, dp(5), 0, dp(4))
            })
            if (total > 0) {
                val progress = LinearProgressIndicator(this).apply {
                    isIndeterminate = false
                    max = total
                    setProgressCompat(answered, false)
                    trackColor = Color.rgb(235, 239, 244)
                    setIndicatorColor(gold)
                }
                info.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
            }
        }
        root.addView(info,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        root.addView(icon(R.drawable.ic_chevron, muted, 19))
        card.addView(root)
        val lp=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin=dp(10)
        destination.addView(card,lp)
    }

    private fun departmentItem(org: JSONObject, destination: LinearLayout = content) {
        val name = org.optString("name", "اداره بدون نام").ifBlank { "اداره بدون نام" }
        val code = org.optString("code", "").ifBlank { org.optString("id", "") }
        val group = org.optString("comparisonGroup", "").ifBlank { org.optString("category", "گروه ثبت‌نشده") }
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        row.addView(iconBadge(R.drawable.ic_building, 23), LinearLayout.LayoutParams(dp(46), dp(46)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(7), 0) }
        info.addView(text(name, 14, true, navy))
        info.addView(text("کد: $code  •  گروه: $group", 11, false, muted))
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip("مجاز", Color.rgb(228, 247, 240), Color.rgb(21, 116, 77)))
        card.addView(row)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) }
        destination.addView(card, lp)
    }

    private fun showHome(tab: String = "home") {
        selectedMission = null
        openDraft = null
        activeTab = tab
        val profile = serverSnapshot.optJSONObject("profile")
        val profileName = profile?.optString("name", "بازرس") ?: "بازرس"
        val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
        val all = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }
        val available = all.filter { it.optBoolean("can_execute",false) }
        val drafted = all.filter { missionDraft(it) }
        val queued = outbox.entries()
        when (tab) {
            "home" -> {
                screen("", "", withNav = true)
                hero(profileName)
                val indicators = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                indicators.addView(chip(if (sessionToken != null) "دریافت از سرور" else "حالت اطلاعات ذخیره‌شده", 
                    if (sessionToken != null) Color.rgb(228,245,237) else Color.rgb(255,246,228),
                    if (sessionToken != null) Color.rgb(25,124,79) else Color.rgb(154,104,27)))
                val lpChip = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT)
                lpChip.marginStart=dp(7)
                indicators.addView(chip(if (store.getString("last_sync") != null) "سوابق همگام‌سازی موجود" else "نیازمند همگام‌سازی",
                    Color.rgb(235,242,251),navy),lpChip)
                content.addView(indicators)
                gap(8)
                label(lastSyncDescription(), 12, false, muted)
                if (sessionToken == null && all.isNotEmpty()) {
                    gap(8)
                    val caution = text("این مأموریت‌ها از حافظه امن دستگاه نمایش داده شده‌اند. برای دریافت تغییرات جدید همگام‌سازی کنید.", 12, false, Color.rgb(131, 90, 35)).apply {
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = rounded(Color.rgb(255, 247, 230), 12)
                    }
                    content.addView(caution)
                }
                gap(15)
                sectionTitle("خلاصه کار من")
                statsRow(
                    statCard(all.size.toString(), "کل مأموریت‌ها", R.drawable.ic_clipboard, Color.rgb(231,241,252),navy),
                    statCard(available.size.toString(), "قابل انجام", R.drawable.ic_check, Color.rgb(228,247,240),Color.rgb(21,116,77))
                )
                statsRow(
                    statCard(drafted.size.toString(), "پیش‌نویس‌ها", R.drawable.ic_notes, Color.rgb(243,238,252),Color.rgb(99,69,150)),
                    statCard(reportCount().toString(), "گزارش‌های همگام‌شده", R.drawable.ic_chart, Color.rgb(255,245,226),Color.rgb(156,99,27))
                )
                gap(5)
                button("شروع بازرسی") {
                    if (available.isNotEmpty()) showMission(available.first())
                    else showHome("missions")
                }
                val authorizedCount = serverSnapshot.optJSONArray("authorized_organizations")?.length() ?: 0
                secondaryButton("فهرست ادارات مجاز من (${authorizedCount})") { showHome("departments") }
                val batch = makeButton("ارسال همه گزارش‌های امضاشده (${queued.size})", false) {
                    if (queued.isEmpty()) showHome("drafts") else sendAllQueued()
                }
                batch.setIconResource(R.drawable.ic_send_all)
                content.addView(batch)
                if (queued.isNotEmpty()) secondaryButton("نمایش صف ارسال و خطاها") { showOutbox() }
                val official = makeButton("گزارش رسمی | دریافت PDF", false) { showOfficialReports() }
                official.setIconResource(R.drawable.ic_official_report)
                content.addView(official)
                val supervisor = serverSnapshot.optJSONObject("supervisor")
                if (supervisor?.optBoolean("can_review", false) == true) {
                    val pendingCount = supervisor.optJSONArray("items")?.length() ?: 0
                    val review = makeButton("کارتابل سربازرس  •  ${pendingCount} گزارش منتظر بررسی", false) { showHome("supervisor") }
                    review.setIconResource(R.drawable.ic_shield)
                    content.addView(review)
                }
                secondaryButton("همگام‌سازی مأموریت‌ها") {
                    if (deviceToken == null) notifyShort("نشست دستگاه فعال نیست") else connectAndSync()
                }
                gap(12)
                val nextMission = available.firstOrNull { missionDraft(it) } ?: available.firstOrNull()
                if (nextMission != null) {
                    sectionTitle("اقدام بعدی")
                    label(if (missionDraft(nextMission)) "ادامه بازرسی نیمه‌تمام" else "مأموریت قابل انجام", 12, false, muted)
                    missionItem(nextMission)
                    gap(5)
                }
                sectionTitle("مأموریت‌های من", "نمایش همه") { showHome("missions") }
                secondaryButton("سوابق بازرسی و رسیدها") { showHistory() }
                val notices = serverSnapshot.optJSONArray("notices") ?: JSONArray()
                if (notices.length() > 0) secondaryButton("اطلاعیه‌های سازمانی (${notices.length()})") { showNotices() }
                if (all.isEmpty()) emptyState("مأموریتی دریافت نشده", "اگر به شما مأموریت داده شده، با اتصال اینترنت همگام‌سازی کنید.")
                else all.filterNot { nextMission != null && missionKey(it) == missionKey(nextMission) }.take(3).forEach { missionItem(it) }
                gap(8)
                label("پیش‌نویس‌ها بدون اینترنت هم ذخیره می‌شوند؛ ارسال نهایی نیازمند اینترنت است.",12,false,muted)
            }
            "supervisor" -> showSupervisorInbox()
            "missions" -> {
                screen("مأموریت‌های من", "جست‌وجو و پیگیری مأموریت‌های ابلاغ‌شده", withNav = true)
                val search = field("جست‌وجوی نام اداره")
                search.setSingleLine(true)
                search.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0)
                search.compoundDrawablePadding = dp(8)
                val options = listOf(
                    Triple("all", "همه (${all.size})", all.size),
                    Triple("ready", "قابل انجام (${available.size})", available.size),
                    Triple("draft", "پیش‌نویس (${drafted.size})", drafted.size)
                )
                var chosenFilter = "all"
                val filterRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val buttons = mutableMapOf<String, MaterialButton>()
                val resultArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val counter = text("", 12, false, muted)
                fun renderList() {
                    val query = search.text?.toString()?.trim().orEmpty()
                    val candidates = all.filter { mission ->
                        val matchesStatus = when (chosenFilter) {
                            "ready" -> mission.optBoolean("can_execute", false)
                            "draft" -> missionDraft(mission)
                            else -> true
                        }
                        val matchesText = query.isBlank() ||
                            mission.optString("orgName", "").contains(query, ignoreCase = true) ||
                            mission.optString("date", "").contains(query, ignoreCase = true)
                        matchesStatus && matchesText
                    }
                    counter.text = "${candidates.size} مأموریت نمایش داده می‌شود"
                    resultArea.removeAllViews()
                    if (candidates.isEmpty()) {
                        val notice = text("مأموریتی با این فیلتر پیدا نشد.", 14, false, muted).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(12), dp(28), dp(12), dp(28))
                            background = rounded(Color.WHITE, 14)
                        }
                        resultArea.addView(notice)
                    } else candidates.forEach { missionItem(it, resultArea) }
                    buttons.forEach { (id, button) ->
                        val active = id == chosenFilter
                        button.backgroundTintList = ColorStateList.valueOf(if (active) navy else Color.WHITE)
                        button.setTextColor(if (active) Color.WHITE else muted)
                        button.strokeColor = ColorStateList.valueOf(if (active) gold else line)
                    }
                }
                options.forEachIndexed { index, (id, title, _) ->
                    val button = makeButton(title, false) {
                        chosenFilter = id
                        renderList()
                    }.apply {
                        textSize = 11f
                        minHeight = dp(44)
                        setPadding(dp(3), 0, dp(3), 0)
                    }
                    buttons[id] = button
                    filterRow.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                        if (index > 0) marginStart = dp(6)
                    })
                }
                content.addView(filterRow)
                gap(8)
                content.addView(counter)
                gap(10)
                content.addView(resultArea)
                gap(12)
                secondaryButton("دریافت آخرین مأموریت‌ها") {
                    if (deviceToken == null) notifyShort("برای همگام‌سازی ابتدا وارد حساب شوید")
                    else connectAndSync()
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderList()
                    override fun afterTextChanged(s: Editable?) {}
                })
                renderList()
            }
            "departments" -> {
                screen("ادارات مجاز من", "فقط اداراتی که مدیر برای شما تعیین کرده است", withNav = true)
                val orgs = serverSnapshot.optJSONArray("authorized_organizations") ?: JSONArray()
                val access = serverSnapshot.optJSONObject("access_policy")
                val mode = access?.optString("access_mode", "all") ?: "all"
                val modeLabel = when (mode) {
                    "selected" -> "دسترسی انتخابی و اختصاصی"
                    "groups" -> "دسترسی بر اساس گروه اداری"
                    else -> "دسترسی عمومی به ادارات فعال"
                }
                val banner = MaterialCardView(this).apply {
                    radius = dp(15).toFloat(); strokeWidth = dp(1); strokeColor = line
                    cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
                }
                val bannerBody = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(14), dp(15), dp(14))
                }
                bannerBody.addView(text(modeLabel, 14, true, navy))
                bannerBody.addView(text("${orgs.length()} اداره در آخرین همگام‌سازی برای شما مجاز بوده است.", 12, false, muted))
                banner.addView(bannerBody)
                content.addView(banner)
                gap(10)

                val search = field("جست‌وجوی نام اداره، کد یا گروه")
                search.setSingleLine(true)
                search.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0)
                search.compoundDrawablePadding = dp(8)
                content.addView(search)
                gap(8)

                val count = text("", 12, false, muted)
                content.addView(count)
                val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                content.addView(results)

                // The previous implementation created a View/Card for every authorized
                // organization at once. In "all" access mode this can be thousands of
                // views and exhaust the Android UI heap. Render a bounded page instead.
                val pageSize = 40
                var visibleLimit = pageSize

                fun renderDepartments() {
                    val query = search.text?.toString()?.trim().orEmpty()
                    results.removeAllViews()

                    var matchedCount = 0
                    var renderedCount = 0
                    for (index in 0 until orgs.length()) {
                        val org = orgs.optJSONObject(index) ?: continue
                        val searchable = sequenceOf(
                            "name", "organization_name", "orgName",
                            "code", "organization_code", "id",
                            "comparisonGroup", "comparison_group",
                            "category", "group_name"
                        ).map { key -> org.optString(key, "") }
                            .filter { value -> value.isNotBlank() && value != "null" }

                        val matches = query.isBlank() || searchable.any { value ->
                            value.contains(query, ignoreCase = true)
                        }
                        if (!matches) continue

                        matchedCount++
                        if (matchedCount <= visibleLimit) {
                            try {
                                departmentItem(org, results)
                                renderedCount++
                            } catch (error: Exception) {
                                android.util.Log.e(
                                    "BAZR_Departments",
                                    "Could not render authorized organization at index $index",
                                    error
                                )
                            }
                        }
                    }

                    count.text = if (matchedCount == 0) {
                        "هیچ اداره‌ای برای نمایش پیدا نشد"
                    } else {
                        "$renderedCount از $matchedCount اداره نمایش داده می‌شود"
                    }

                    if (matchedCount == 0) {
                        val message = if (orgs.length() == 0) {
                            "هنوز اداره‌ای برای شما مجاز نشده است."
                        } else {
                            "اداره‌ای با این جست‌وجو پیدا نشد."
                        }
                        val empty = text(message, 14, false, muted).apply {
                            gravity = Gravity.CENTER
                            setPadding(dp(14), dp(26), dp(14), dp(26))
                            background = rounded(Color.WHITE, 14)
                        }
                        results.addView(empty)
                    } else if (matchedCount > visibleLimit) {
                        val nextCount = minOf(pageSize, matchedCount - visibleLimit)
                        val more = makeButton("نمایش $nextCount اداره دیگر", false) {
                            visibleLimit = minOf(matchedCount, visibleLimit + pageSize)
                            renderDepartments()
                        }
                        results.addView(more)
                    }
                }

                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        visibleLimit = pageSize
                        renderDepartments()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                renderDepartments()
                gap(8)
                label(
                    "تغییر یا افزودن دسترسی فقط توسط مدیر سامانه انجام می‌شود. برای دریافت تغییرات جدید، همگام‌سازی کنید.",
                    11, false, muted
                )
                secondaryButton("همگام‌سازی دسترسی‌ها") {
                    if (deviceToken == null) notifyShort("ابتدا وارد حساب بازرس شوید") else connectAndSync()
                }
            }
            "drafts" -> {
                screen("پیش‌نویس‌های من", "کارهای ذخیره‌شده روی همین دستگاه",withNav=true)
                label("${drafted.size} پیش‌نویس موجود",18,true,navy)
                gap(6)
                val ready = makeButton("ارسال همه گزارش‌های امضاشده (${queued.size})", true) { sendAllQueued() }
                ready.setIconResource(R.drawable.ic_send_all)
                content.addView(ready)
                secondaryButton("نمایش صف ارسال و جزئیات خطاها") { showOutbox() }
                gap(10)
                if (drafted.isEmpty()) emptyState("پیش‌نویسی ندارید","هنگام پاسخ به چک‌لیست، اطلاعات به‌صورت خودکار ذخیره می‌شود.")
                else drafted.forEach { mission ->
                    val key = missionKey(mission)
                    val pending = outbox.get(key)
                    val draft = InspectionDraft(key, store)
                    val status = when {
                        pending?.optString("state") == "failed" -> "ارسال ناموفق · قابل تلاش مجدد"
                        pending != null -> "امضاشده · آماده ارسال"
                        draft.validate(mission).isNotEmpty() -> "نیازمند اصلاح امتیاز یا مستند"
                        draft.unscoredIds(mission).isNotEmpty() -> "پیش‌نویس با ${draft.unscoredIds(mission).size} بند بی‌امتیاز"
                        else -> "آماده مرور و امضا"
                    }
                    label("وضعیت: $status", 12, false, if (pending != null) teal else muted)
                    missionItem(mission)
                }
                gap(12)
                label("برای جلوگیری از حذف اطلاعات، داده‌های برنامه را از تنظیمات گوشی پاک نکنید.",12,false,muted)
            }
            "account" -> {
                screen("حساب بازرس", "تنظیمات امنیت و ارتباط",withNav=true)
                val panel = LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(18),dp(20),dp(18),dp(20))
                    setBackground(rounded(Color.WHITE,18))
                }
                panel.addView(iconBadge(R.drawable.ic_person, 28), LinearLayout.LayoutParams(dp(48), dp(48)))
                panel.addView(text(profileName,21,true,navy).apply { setPadding(0,dp(12),0,dp(4)) })
                panel.addView(text("حساب سازمانی بازرس",13,false,muted))
                content.addView(panel)
                gap(20)
                sectionTitle("امنیت حساب")
                if (deviceToken != null && !store.biometricEnabled()) {
                    button("فعال‌سازی ورود با اثرانگشت") { enableBiometric() }
                } else if (store.biometricEnabled()) {
                    label("ورود با اثرانگشت برای این دستگاه فعال است.",14,false,teal)
                }
                sectionTitle("سوابق و اطلاعیه‌ها")
                secondaryButton("سوابق بازرسی و رسیدها") { showHistory() }
                secondaryButton("اطلاعیه‌های سازمانی") { showNotices() }
                sectionTitle("اعلان مأموریت جدید")
                if (store.getString("notify_missions_enabled") == "yes") {
                    label("اعلان در زمان همگام‌سازی دستی فعال است؛ دریافت خودکار پس‌زمینه‌ای هنوز وجود ندارد.", 12, false, muted)
                    secondaryButton("غیرفعال‌کردن اعلان") {
                        store.putString("notify_missions_enabled", "no"); showHome("account")
                    }
                } else {
                    secondaryButton("فعال‌سازی اعلان مأموریت‌های جدید") { enableMissionNotifications() }
                }
                sectionTitle("ارتباط با سامانه")
                secondaryButton("همگام‌سازی اطلاعات") { connectAndSync() }
                secondaryButton("خروج از حساب") { confirmLogout() }
                gap(12)
                label("نسخه ${BuildConfig.VERSION_NAME}  •  اتصال امن HTTPS",12,false,muted)
            }
            else -> showHome("home")
        }
    }

    private fun showSupervisorInbox() {
        activeTab = "supervisor"
        selectedMission = null
        openDraft = null
        val supervisor = serverSnapshot.optJSONObject("supervisor") ?: JSONObject()
        screen("کارتابل سربازرس", "بررسی گزارش‌های ارسال‌شده توسط بازرسان", withNav = true)
        if (!supervisor.optBoolean("can_review", false)) {
            emptyState("دسترسی سربازرس فعال نیست", "این حساب باید توسط مدیرکل به‌عنوان سربازرس تعیین شود.")
            secondaryButton("بازگشت به خانه") { showHome("home") }
            return
        }
        val items = supervisor.optJSONArray("items") ?: JSONArray()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(Color.WHITE, 16)
        }
        header.addView(text("${items.length()} گزارش در انتظار بررسی", 18, true, navy))
        header.addView(text("تأیید سربازرس مرحله اول است؛ پس از آن گزارش برای تأیید نهایی مدیریت ارسال می‌شود.", 12, false, muted).apply {
            setPadding(0, dp(7), 0, 0)
        })
        content.addView(header)
        gap(12)
        secondaryButton("به‌روزرسانی کارتابل") { refreshSupervisorInbox() }
        if (items.length() == 0) {
            emptyState("گزارش منتظر بررسی ندارید", "در حال حاضر گزارشی برای تأیید سربازرس وجود ندارد.")
        }
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val org = item.optString("org_name", "دستگاه اجرایی")
            val inspector = item.optString("inspector_name", "بازرس")
            val receipt = item.optString("receipt_code", "—")
            val score = if (item.has("score") && !item.isNull("score")) item.optDouble("score").let { String.format(Locale.US, "%.1f", it) } else "—"
            val answers = item.optJSONObject("evaluation")?.optInt("answered_count", 0) ?: 0
            val total = item.optJSONObject("evaluation")?.optInt("question_count", 0) ?: 0
            val panel = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                strokeWidth = dp(1)
                strokeColor = line
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener { showSupervisorReport(item) }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
            }
            box.addView(text(org, 17, true, navy))
            box.addView(text("بازرس: $inspector  •  کد: ${item.optString("inspector_ref", "—")}", 12, false, muted).apply { setPadding(0, dp(6), 0, 0) })
            box.addView(text("امتیاز: $score  •  پاسخ‌ها: $answers از $total", 13, true, ink).apply { setPadding(0, dp(8), 0, 0) })
            box.addView(text("رسید: $receipt  •  وضعیت: ${if (item.optString("review_status") == "admin_review") "در انتظار تأیید سربازرس" else "در انتظار بررسی"}", 11, false, muted).apply { setPadding(0, dp(6), 0, 0) })
            box.addView(text("مشاهده جزئیات و بررسی  ←", 12, true, Color.rgb(150, 111, 43)).apply { setPadding(0, dp(9), 0, 0) })
            panel.addView(box)
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }

        val history = supervisor.optJSONArray("history") ?: JSONArray()
        if (history.length() > 0) {
            gap(10)
            sectionTitle("سوابق گزارش‌ها (${history.length()})")
            label("گزارش‌های قبلاً بررسی‌شده در این بخش فقط برای مشاهده هستند.", 11, false, muted)
            for (index in 0 until history.length()) {
                val item = history.optJSONObject(index) ?: continue
                val org = item.optString("org_name", "دستگاه اجرایی")
                val inspector = item.optString("inspector_name", "بازرس")
                val receipt = item.optString("receipt_code", "—")
                val statusText = when (item.optString("review_status")) {
                    "supervisor_approved" -> "تأیید سربازرس؛ در انتظار مدیریت"
                    "approved", "accepted" -> "تأیید نهایی مدیریت"
                    "returned", "rejected" -> "بازگشت برای اصلاح"
                    else -> "بررسی‌شده"
                }
                val panel = MaterialCardView(this).apply {
                    radius = dp(15).toFloat()
                    strokeWidth = dp(1)
                    strokeColor = line
                    cardElevation = dp(1).toFloat()
                    setCardBackgroundColor(Color.WHITE)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { showSupervisorReport(item) }
                }
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                box.addView(text(org, 15, true, navy))
                box.addView(text("بازرس: $inspector", 11, false, muted).apply { setPadding(0, dp(5), 0, 0) })
                box.addView(text("وضعیت: $statusText", 12, true, Color.rgb(143, 105, 39)).apply { setPadding(0, dp(6), 0, 0) })
                box.addView(text("رسید: $receipt  •  مشاهده جزئیات ←", 11, false, muted).apply { setPadding(0, dp(5), 0, 0) })
                panel.addView(box)
                content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
            }
        } else if (items.length() == 0) {
            gap(8)
            emptyState("سابقه‌ای برای نمایش وجود ندارد", "گزارش‌های جدید پس از ارسال بازرس در این کارتابل ثبت می‌شوند.")
        }
    }

    private fun showSupervisorReport(report: JSONObject) {
        if (!report.has("payload")) {
            val id = report.optInt("id", 0)
            val device = deviceToken ?: run { notifyShort("نشست دستگاه فعال نیست؛ دوباره وارد شوید"); return }
            if (id <= 0) { notifyShort("شناسه گزارش نامعتبر است"); return }
            work({ api.get("mobile_supervisor_report.php?submission_id=$id", sessionFor(device)) }) { detail -> showSupervisorReport(detail) }
            return
        }
        activeTab = "supervisor"
        selectedMission = null
        val payload = report.optJSONObject("payload") ?: JSONObject()
        val org = report.optString("org_name", "دستگاه اجرایی")
        val inspector = report.optString("inspector_name", "بازرس")
        val receipt = report.optString("receipt_code", "—")
        val score = if (report.has("score") && !report.isNull("score")) String.format(Locale.US, "%.1f", report.optDouble("score")) else "—"
        screen("بررسی گزارش بازرس", org, withNav = true)
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            background = rounded(Color.WHITE, 16)
        }
        summary.addView(text(org, 18, true, navy))
        summary.addView(text("نام بازرس: $inspector  •  کد پرسنلی: ${report.optString("inspector_ref", "—")}", 12, false, muted).apply { setPadding(0, dp(8), 0, 0) })
        summary.addView(text("امتیاز نهایی بازرس: $score", 16, true, ink).apply { setPadding(0, dp(9), 0, 0) })
        val submittedAt = report.optString("submitted_at", "").replace('T', ' ').take(19).ifBlank { "ثبت نشده" }
        val evaluation = report.optJSONObject("evaluation") ?: payload.optJSONObject("evaluation") ?: JSONObject()
        val coverage = if (evaluation.has("coverage_percent")) String.format(Locale.US, "%.0f%%", evaluation.optDouble("coverage_percent")) else "—"
        val unscoredCount = evaluation.optJSONArray("unscored_question_ids")?.length() ?: 0
        summary.addView(text("تاریخ ارسال: $submittedAt", 11, false, muted).apply { setPadding(0, dp(5), 0, 0) })
        summary.addView(text("پوشش ارزیابی: $coverage  •  بندهای بدون امتیاز: $unscoredCount", 12, true, Color.rgb(143, 105, 39)).apply { setPadding(0, dp(6), 0, 0) })
        summary.addView(text("کد رسید: $receipt", 11, false, muted).apply { setPadding(0, dp(5), 0, 0) })
        content.addView(summary)
        gap(14)
        sectionTitle("جزئیات چک‌لیست")
        val snapshot = payload.optJSONObject("checklist_snapshot") ?: payload.optJSONObject("checklistSnapshot") ?: JSONObject()
        val questions = snapshot.optJSONArray("questions") ?: JSONArray()
        val answers = payload.optJSONObject("answers") ?: JSONObject()
        val notes = payload.optJSONObject("notes") ?: JSONObject()
        if (questions.length() == 0) {
            emptyState("جزئیات بندها در گزارش موجود نیست", "خلاصه گزارش و امتیاز ثبت‌شده در دسترس است.")
        } else {
            for (index in 0 until questions.length()) {
                val question = questions.optJSONObject(index) ?: continue
                val qid = question.optString("id", "")
                val title = question.optString("text", question.optString("title", question.optString("question", "بند ارزیابی ${index + 1}")))
                val rawAnswer = if (qid.isNotBlank()) answers.opt(qid) else null
                val answer = when (rawAnswer) {
                    null, JSONObject.NULL -> "بدون امتیاز"
                    is JSONObject -> rawAnswer.optString("score", rawAnswer.optString("value", rawAnswer.toString()))
                    else -> rawAnswer.toString()
                }
                val note = if (qid.isNotBlank()) notes.optString(qid, "") else ""
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(13), dp(12), dp(13), dp(12))
                    background = rounded(Color.WHITE, 13)
                }
                panel.addView(text("${index + 1}. $title", 13, true, navy))
                panel.addView(text("امتیاز ثبت‌شده: $answer", 12, true, Color.rgb(143, 105, 39)).apply { setPadding(0, dp(6), 0, 0) })
                if (note.isNotBlank()) panel.addView(text("توضیح بازرس: $note", 11, false, muted).apply { setPadding(0, dp(5), 0, 0) })
                content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
        val findings = payload.optJSONArray("findings") ?: JSONArray()
        if (findings.length() > 0) {
            sectionTitle("نواقص و موارد ثبت‌شده (${findings.length()})")
            for (index in 0 until findings.length()) {
                val finding = findings.optJSONObject(index)
                val findingText = if (finding != null) finding.optString("title", finding.optString("description", finding.optString("text", "مورد ثبت‌شده"))) else findings.optString(index)
                val findingView = text("${index + 1}. $findingText", 12, false, ink).apply { setPadding(dp(12), dp(9), dp(12), dp(9)); background = rounded(Color.WHITE, 10) }
                content.addView(findingView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            }
        }
        val uploads = report.optJSONArray("uploads") ?: JSONArray()
        sectionTitle("مستندات و تصاویر (${uploads.length()})")
        if (uploads.length() == 0) label("برای این گزارش مستندی ثبت نشده است.", 12, false, muted)
        for (index in 0 until uploads.length()) {
            val file = uploads.optJSONObject(index) ?: continue
            val id = file.optInt("id", 0)
            val name = file.optString("name", "مستند ${index + 1}")
            val sizeKb = file.optLong("size", 0L) / 1024L
            secondaryButton("مشاهده / دریافت: $name  (${sizeKb} KB)") { openSupervisorEvidence(id, name, file.optString("mime", "application/octet-stream")) }
        }
        gap(12)
        val status = report.optString("review_status", "admin_review")
        if (status == "admin_review" || status == "pending_review") {
            button("تأیید گزارش توسط سربازرس") {
                AlertDialog.Builder(this).setTitle("تأیید گزارش")
                    .setMessage("گزارش این بازرس تأیید شود و برای تأیید نهایی به مدیریت ارسال شود؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("تأیید و ارجاع به مدیریت") { _, _ -> submitSupervisorDecision(report, "approve", "") }
                    .show()
            }
            secondaryButton("بازگشت برای اصلاح") {
                val reason = EditText(this).apply {
                    hint = "دلیل بازگشت برای بازرس"
                    minLines = 3
                    maxLines = 5
                    gravity = Gravity.TOP or Gravity.RIGHT
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                AlertDialog.Builder(this).setTitle("بازگشت گزارش برای اصلاح")
                    .setView(reason)
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("ثبت بازگشت") { _, _ ->
                        val note = reason.text?.toString()?.trim().orEmpty()
                        if (note.isBlank()) notifyShort("نوشتن دلیل بازگشت الزامی است")
                        else submitSupervisorDecision(report, "return", note)
                    }.show()
            }
        } else {
            val statusLabel = when (status) {
                "supervisor_approved" -> "تأیید سربازرس؛ در انتظار مدیریت"
                "approved", "accepted" -> "تأیید نهایی مدیریت"
                "returned", "rejected" -> "بازگشت برای اصلاح"
                else -> "خارج از صف بررسی"
            }
            label("وضعیت گزارش: $statusLabel. این گزارش در حالت فقط‌خواندنی است.", 12, false, muted)
            val reviewer = report.optString("reviewed_by", "")
            if (reviewer.isNotBlank()) label("ثبت‌کننده آخرین بررسی: $reviewer", 11, false, muted)
            val reviewNote = report.optString("review_note", "")
            if (reviewNote.isNotBlank()) label("توضیح بررسی: $reviewNote", 12, false, ink)
        }
        secondaryButton("بازگشت به کارتابل سربازرس") { showHome("supervisor") }
    }

    private fun submitSupervisorDecision(report: JSONObject, action: String, note: String) {
        val id = report.optInt("id", 0)
        val device = deviceToken ?: run { notifyShort("نشست دستگاه فعال نیست؛ دوباره وارد شوید"); return }
        if (id <= 0) { notifyShort("شناسه گزارش نامعتبر است"); return }
        work({
            val session = sessionFor(device)
            val result = api.post("mobile_supervisor_action.php", JSONObject()
                .put("submission_id", id).put("action", action).put("note", note), session)
            if (!result.optBoolean("ok", false)) throw UserActionException("پاسخ تأیید سربازرس از سرور معتبر نبود")
            val inbox = try { api.get("mobile_supervisor_inbox.php", session) } catch (_: Exception) { null }
            Pair(result, inbox)
        }) { (_, inbox) ->
            if (inbox != null) {
                serverSnapshot.put("supervisor", inbox)
            } else {
                val old = serverSnapshot.optJSONObject("supervisor") ?: JSONObject()
                val oldItems = old.optJSONArray("items") ?: JSONArray()
                val kept = JSONArray()
                for (index in 0 until oldItems.length()) {
                    val item = oldItems.optJSONObject(index) ?: continue
                    if (item.optInt("id", 0) != id) kept.put(item)
                }
                old.put("items", kept).put("count", kept.length())
                serverSnapshot.put("supervisor", old)
            }
            store.save("mobile_sync", serverSnapshot.toString())
            showHome("supervisor")
            notifyShort(if (action == "approve") "گزارش تأیید شد و برای مدیریت ارسال شد" else "گزارش برای اصلاح به بازرس برگشت داده شد")
        }
    }

    private fun refreshSupervisorInbox() {
        val device = deviceToken ?: run { notifyShort("ابتدا وارد حساب شوید"); return }
        work({
            val session = sessionFor(device)
            api.get("mobile_supervisor_inbox.php", session)
        }) { inbox ->
            serverSnapshot.put("supervisor", inbox)
            store.save("mobile_sync", serverSnapshot.toString())
            showHome("supervisor")
        }
    }

    private fun openSupervisorEvidence(uploadId: Int, displayName: String, mimeHint: String) {
        val device = deviceToken ?: run { notifyShort("نشست دستگاه فعال نیست"); return }
        if (uploadId <= 0) { notifyShort("شناسه مستند نامعتبر است"); return }
        work({
            val session = sessionFor(device)
            val (bytes, downloadedMime) = api.downloadSupervisorEvidence(uploadId, session)
            val mime = if (downloadedMime == "application/octet-stream" && mimeHint.isNotBlank()) mimeHint else downloadedMime
            val dir = File(cacheDir, "supervisor_evidence").apply { mkdirs() }
            val ext = when (mime) {
                "image/jpeg" -> ".jpg"
                "image/png" -> ".png"
                "image/webp" -> ".webp"
                "application/pdf" -> ".pdf"
                else -> ".bin"
            }
            val file = File(dir, "evidence-$uploadId$ext")
            file.writeBytes(bytes)
            Pair(file, mime)
        }) { (file, mime) ->
            try {
                val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            } catch (_: Exception) {
                notifyShort("برای باز کردن این نوع فایل، برنامه نمایشگر سازگار روی گوشی لازم است")
            }
        }
    }

    private fun showMission(mission: JSONObject, questionIndex: Int = 0) {
        val key=missionKey(mission)
        if (key.isBlank()) { notifyShort("شناسه مأموریت نامعتبر است"); return }
        if (outbox.get(key) != null) {
            showOutbox()
            notifyShort("این گزارش در صف ارسال است؛ برای ویرایش، ابتدا از صف خارج کنید")
            return
        }
        selectedMission=mission; selectedMissionKey=key
        val draft=if(openDraft?.key == key) openDraft!! else InspectionDraft(key,store)
        openDraft=draft
        val enabled=mission.optBoolean("can_execute",false)
        val questions=mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val count=questions.length()
        currentQuestionIndex=questionIndex.coerceIn(0,(count-1).coerceAtLeast(0))
        screen("ثبت بازرسی", mission.optString("orgName", "دستگاه اجرایی"))
        secondaryButton("بازگشت به مأموریت‌ها") { showHome("missions") }
        val summary=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(14),dp(16),dp(14))
            setBackground(rounded(Color.WHITE,16))
        }
        summary.addView(text(mission.optString("orgName", "بازرسی"),16,true,navy))
        summary.addView(text(listOf(mission.optString("date"),mission.optString("schedule_label")).filter { it.isNotBlank() }.joinToString("  •  "),12,false,muted))
        content.addView(summary)
        gap(14)
        if (!enabled) label("این مأموریت فعلاً قابل ارسال نیست؛ امکان ثبت پیش‌نویس وجود دارد.",13,true,Color.rgb(157,99,29))
        if (count == 0) {
            emptyState("چک‌لیست خالی است","برای دریافت اطلاعات جدید، مأموریت‌ها را همگام‌سازی کنید.")
            secondaryButton("بازگشت") { showHome("missions") }
            return
        }
        val validAnswers = (0 until count).count { idx ->
            val q=questions.optJSONObject(idx)
            val number=draft.answers.optString(q?.optString("id") ?: "").toDoubleOrNull()
            number != null && number in 0.0..100.0
        }
        val stepTitle=label("سؤال ${currentQuestionIndex+1} از $count",15,true,navy)
        val progressLabel=label("$validAnswers از $count سؤال امتیازدهی شده",12,false,muted)
        val progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=count; this.progress=validAnswers
            progressTintList=ColorStateList.valueOf(gold)
            progressBackgroundTintList=ColorStateList.valueOf(line)
        }
        val progressParams=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(6))
        progressParams.bottomMargin=dp(18)
        content.addView(progress,progressParams)
        val q=questions.optJSONObject(currentQuestionIndex) ?: JSONObject()
        val id=q.optString("id")
        if (id.isBlank()) {
            label("شناسه سؤال نامعتبر است؛ اطلاعات چک‌لیست را با مدیر بررسی کنید.",14,true)
            return
        }
        val questionText=q.optString("text",q.optString("question",q.optString("title",q.optString("label",id))))
        val questionCard=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(20),dp(16),dp(18))
            setBackground(rounded(Color.WHITE,18))
        }
        questionCard.addView(text("عنوان سؤال",12,true,teal))
        questionCard.addView(text(questionText,18,true,navy).apply { setPadding(0,dp(9),0,dp(4)) })
        content.addView(questionCard)
        gap(18)
        label("امتیاز و مستندات",16,true,navy)
        val score=field("امتیاز از ۰ تا ۱۰۰",draft.answers.optString(id),numeric=true)
        val note=field("توضیح در صورت نیاز",draft.notes.optString(id))
        fun refreshProgress() {
            val filled=(0 until count).count { idx ->
                val qid=questions.optJSONObject(idx)?.optString("id") ?: ""
                val value=draft.answers.optString(qid).toDoubleOrNull()
                value != null && value in 0.0..100.0
            }
            progress.progress=filled
            progressLabel.text="$filled از $count سؤال امتیازدهی شده"
        }
        score.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.answers.put(id,s?.toString() ?: "")
                draft.clearAcknowledgement()
                draft.save(); refreshProgress()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        note.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                draft.notes.put(id,s?.toString() ?: ""); draft.save()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val attached = EvidenceRefs.references(draft.attachments, id)
        if (attached.size < EvidenceRefs.MAX_PER_QUESTION) {
            val evidenceAction = secondaryButton("افزودن مستند (${attached.size}/${EvidenceRefs.MAX_PER_QUESTION})") {
                chooseEvidence(id)
            }
            evidenceAction.setIconResource(R.drawable.ic_camera)
            evidenceAction.iconTint = ColorStateList.valueOf(navy)
        } else label("سقف چهار مستند برای این سؤال تکمیل است.", 12, true, teal)
        attached.forEachIndexed { index, reference ->
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            panel.addView(text("مستند ${index + 1}: ${evidenceVault.metadata(reference)}", 12, false, ink))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(makeButton("مشاهده مستند", false) { openAttachedEvidence(reference) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
            actions.addView(makeButton("حذف پیوست", false) {
                AlertDialog.Builder(this)
                    .setTitle("حذف مستند")
                    .setMessage("فقط نسخه رمزنگاری‌شده این مستند از پیش‌نویس حذف می‌شود؛ فایل اصلی گوشی دست‌نخورده می‌ماند.")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف مستند") { _, _ ->
                        if (EvidenceRefs.remove(draft.attachments, id, reference)) {
                            draft.save()
                            // Shared local references are retained if another question still uses them.
                            if (reference !in EvidenceRefs.all(draft.attachments)) evidenceVault.delete(reference)
                        }
                        showMission(mission, currentQuestionIndex)
                    }.show()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
            panel.addView(actions)
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        gap(13)
        val nav=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        if (currentQuestionIndex > 0) {
            val prev=makeButton("سؤال قبلی",false) { showMission(mission,currentQuestionIndex-1) }
            nav.addView(prev,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginEnd=dp(5) })
        }
        if (currentQuestionIndex < count-1) {
            val next=makeButton("سؤال بعدی",true) { showMission(mission,currentQuestionIndex+1) }
            nav.addView(next,LinearLayout.LayoutParams(0,dp(52),1f).apply { marginStart=dp(5) })
        }
        content.addView(nav)
        if (currentQuestionIndex == count - 1 && validAnswers < count) {
            gap(9)
            secondaryButton("رفتن به اولین بند بدون امتیاز (اختیاری)") {
                val firstMissing = (0 until count).firstOrNull { idx ->
                    val questionId = questions.optJSONObject(idx)?.optString("id") ?: ""
                    val value = draft.answers.optString(questionId).toDoubleOrNull()
                    questionId.isBlank() || value == null || value !in 0.0..100.0
                }
                if (firstMissing == null) notifyShort("امتیاز همه سؤال‌ها تکمیل شده است")
                else showMission(mission, firstMissing)
            }
        }
        gap(15)
        secondaryButton("ذخیره پیش‌نویس") { draft.save(); notifyShort("پیش‌نویس روی دستگاه ذخیره شد") }
        label("پاسخ‌ها هنگام تایپ به‌صورت خودکار و رمزنگاری‌شده ذخیره می‌شوند.",12,false,muted)
        if (enabled) {
            gap(8)
            button("پایان بازرسی و مرور گزارش") { showReportReview(mission, draft) }
            label("ارسال نهایی تنها با اتصال اینترنت انجام می‌شود.",12,false,muted)
        }
    }

    /** A dedicated native review screen before the user intentionally queues a report. */
    private fun showReportReview(mission: JSONObject, draft: InspectionDraft) {
        val issues = draft.validate(mission)
        if (issues.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("موارد امتیازدار نیازمند اصلاح هستند")
                .setMessage(issues.take(8).joinToString("\n\n"))
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        selectedMission = mission
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        screen("بازبینی گزارش", "پیش از قرار گرفتن در صف ارسال، اطلاعات را مرور کنید")
        val total = questions.length()
        label(mission.optString("orgName", "دستگاه اجرایی"), 19, true, navy)
        gap(4)
        val evaluated = draft.answeredCount(mission)
        val missing = draft.unscoredIds(mission)
        content.addView(chip("$evaluated از $total بند امتیازدهی شده · ${EvidenceRefs.count(draft.attachments)} مستند", Color.rgb(234, 242, 251), navy))
        if (missing.isNotEmpty()) {
            gap(8)
            label("${missing.size} بند ارزیابی‌نشده است؛ این موارد امتیاز صفر نمی‌گیرند.", 13, true, Color.rgb(150, 96, 31))
        }
        gap(16)
        for (i in 0 until questions.length()) {
            val q = questions.optJSONObject(i) ?: continue
            val id = q.optString("id")
            val title = q.optString("text", q.optString("question", q.optString("title", id)))
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 14)
                setPadding(dp(14), dp(13), dp(14), dp(13))
            }
            panel.addView(text("${i + 1}. $title", 14, true, navy))
            val rawScore = draft.answers.optString(id)
            panel.addView(text(if (rawScore.isBlank()) "امتیاز: ارزیابی‌نشده" else "امتیاز: $rawScore از ۱۰۰", 13, true, if (rawScore.isBlank()) muted else teal))
            val note = draft.notes.optString(id)
            if (note.isNotBlank()) panel.addView(text("توضیح: $note", 12, false, muted))
            val attachments = EvidenceRefs.references(draft.attachments, id)
            if (attachments.isNotEmpty()) panel.addView(text("مستندات (${attachments.size}):", 12, true, muted))
            attachments.forEachIndexed { index, reference ->
                panel.addView(text("${index + 1}. ${evidenceVault.metadata(reference)}", 12, false, muted))
            }
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(9)
            })
        }
        gap(10)
        val onlineNow = online()
        label(if (onlineNow) "اتصال اینترنت برقرار است؛ می‌توانید بعد از ثبت در صف، گزارش را ارسال کنید."
            else "اینترنت در دسترس نیست. گزارش رمزنگاری‌شده در صف دستگاه می‌ماند تا خودتان آن را ارسال کنید.",
            12, false, muted)
        gap(10)
        button("تأیید گزارش و امضای لمسی") { askBeforeSignature(mission, draft) }
        secondaryButton("بازگشت به ویرایش") { showMission(mission, currentQuestionIndex) }
    }

    /** Warn about missing scores and record an explicit, content-bound decision. */
    private fun askBeforeSignature(mission: JSONObject, draft: InspectionDraft) {
        val problems = draft.validate(mission)
        if (problems.isNotEmpty()) {
            AlertDialog.Builder(this).setTitle("مواردی نیازمند اصلاح است")
                .setMessage(problems.take(12).joinToString("\n") + if (problems.size > 12) "\nو ${problems.size-12} مورد دیگر" else "")
                .setPositiveButton("بازگشت به ویرایش", null).show()
            return
        }
        val missing = draft.unscoredIds(mission)
        if (missing.isEmpty()) {
            draft.clearAcknowledgement(); draft.save()
            showSignatureScreen(mission, draft)
            return
        }
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val unscored = missing.toSet()
        val descriptions = (0 until questions.length()).mapNotNull { idx ->
            val q = questions.optJSONObject(idx) ?: return@mapNotNull null
            if (q.optString("id") in unscored) "${idx+1}. ${q.optString("text", q.optString("title", q.optString("id")))}" else null
        }
        // Android AlertDialog makes a long message scrollable: show every omitted item.
        val summary = descriptions.joinToString("\n")
        val remaining = ""
        AlertDialog.Builder(this)
            .setTitle("هشدار: ${missing.size} بند بدون امتیاز")
            .setMessage("${draft.answeredCount(mission)} بند ارزیابی شده است. بندهای زیر بدون امتیاز می‌مانند و صفر محسوب نمی‌شوند:\n\n$summary$remaining\n\nآیا مایل هستید گزارش را با همین وضعیت امضا و ارسال کنید؟")
            .setNegativeButton("خیر، بازگشت") { _, _ -> showReportReview(mission, draft) }
            .setNeutralButton("اصلاح اولین بند") { _, _ ->
                val first = (0 until questions.length()).firstOrNull { questions.optJSONObject(it)?.optString("id") in unscored } ?: 0
                showMission(mission, first)
            }
            .setPositiveButton("بله، ادامه و امضا") { _, _ ->
                draft.acknowledgeUnscored(mission)
                showSignatureScreen(mission, draft)
            }.show()
    }

    /** Native signature capture; content changes invalidate this signature. */
    private fun showSignatureScreen(mission: JSONObject, draft: InspectionDraft) {
        if (draft.validate(mission).isNotEmpty() || !draft.hasAcknowledgedUnscored(mission)) {
            askBeforeSignature(mission, draft); return
        }
        // A dedicated Native touch canvas, filling the available app height.
        window.statusBarColor = navy
        window.navigationBarColor = Color.WHITE
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(pageBg)
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(12))
        }
        heading.addView(text("امضای نهایی بازرس", 23, true, navy))
        heading.addView(text(mission.optString("orgName", "دستگاه اجرایی"), 15, true, ink))
        heading.addView(text("با انگشت یا قلم در کادر سفید امضا کنید.", 13, false, muted))
        val remaining = draft.unscoredIds(mission).size
        if (remaining > 0) {
            heading.addView(text("با تأیید شما، $remaining بند بدون امتیاز ثبت خواهد شد.", 12, true, Color.rgb(150,96,31)))
        }
        shell.addView(heading)
        val signatureFrame = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            strokeWidth = dp(2)
            strokeColor = gold
            setCardBackgroundColor(Color.WHITE)
            cardElevation = 0f
        }
        val signaturePad = SignaturePad(this)
        signatureFrame.addView(signaturePad, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shell.addView(signatureFrame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(12) })
        val note = text("امضای لمسی تصویر دست‌نویس است و جایگزین گواهی امضای دیجیتال نیست.", 11, false, muted)
        shell.addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        val clear = makeButton("پاک کردن امضا", false) { signaturePad.clearSignature() }
        shell.addView(clear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            bottomMargin = dp(8)
        })
        val confirm = makeButton("ثبت امضا و افزودن به صف ارسال", true) {
            if (!signaturePad.hasInk()) {
                notifyShort("لطفاً امضای خود را در کادر ثبت کنید")
            } else if (!draft.hasAcknowledgedUnscored(mission) || draft.validate(mission).isNotEmpty()) {
                notifyShort("اطلاعات گزارش تغییر کرده؛ لطفاً دوباره بررسی کنید")
                showReportReview(mission, draft)
            } else try {
                draft.save()
                signatures.save(draft, signaturePad.toPng())
                outbox.enqueue(mission, draft)
                showOutbox()
                notifyShort("گزارش امضاشده در صف ارسال ثبت شد")
            } catch (error: Exception) { notifyShort(AppErrorMessages.forUser(error)) }
        }
        shell.addView(confirm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(55)).apply {
            bottomMargin = dp(8)
        })
        shell.addView(makeButton("بازگشت به مرور گزارش", false) {
            showReportReview(mission, draft)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        setContentView(shell)
    }

    /** Native, modal progress UI: reports transmitted bytes, stages and the active file. */
    private fun showTransferDialog(title: String) {
        if (transferDialog != null) return
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(16), dp(22), dp(22))
        }
        val headline = text(title, 17, true, navy)
        val detail = text("آماده‌سازی اتصال امن…", 13, false, muted)
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            max = 100
            setProgressCompat(0, false)
            setIndicatorColor(gold)
            trackColor = Color.rgb(225, 230, 236)
        }
        column.addView(headline)
        column.addView(detail, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12); bottomMargin = dp(12)
        })
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
        column.addView(text("این نوار روند انتقال از دستگاه را نشان می‌دهد. ثبت نهایی فقط با رسید سرور قطعی است.",
            11, false, muted), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        transferTitle = headline
        transferDetail = detail
        transferBar = bar
        transferDialog = AlertDialog.Builder(this)
            .setTitle("وضعیت ارسال گزارش")
            .setView(column)
            .setCancelable(false)
            .create().also { it.show() }
    }

    /** Data comes from the IO thread; progress is marshalled back to the main thread. */
    private fun updateTransferProgress(title: String, detail: String, percent: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed || transferDialog == null) return@runOnUiThread
            transferTitle?.text = title
            transferDetail?.text = detail
            transferBar?.setProgressCompat(percent.coerceIn(0, 100), true)
        }
    }

    private fun dismissTransferDialog() {
        transferDialog?.dismiss()
        transferDialog = null
        transferTitle = null
        transferDetail = null
        transferBar = null
    }

    private fun transferScope(): String {
        val queue = bulkPending
        return if (queue == null) "گزارش جاری" else
            "گزارش ${bulkTotal - queue.size} از $bulkTotal"
    }

    /** User-initiated sequential batch: one receipt per mission, no background uploads. */
    private fun sendAllQueued() {
        val entries = outbox.entries()
        if (entries.isEmpty()) { notifyShort("گزارشی برای ارسال وجود ندارد"); return }
        if (!online() || deviceToken == null) { notifyShort("اتصال اینترنت و ورود امن لازم است"); return }
        if (busy) { notifyShort("عملیات دیگری در حال انجام است؛ پس از پایان آن ارسال کنید"); return }
        val ready = entries.filter { item ->
            val draft = InspectionDraft(item.optString("mission_key"), store)
            val mission = item.optJSONObject("mission")
            mission != null && draft.validate(mission).isEmpty() && draft.hasAcknowledgedUnscored(mission) && signatures.get(draft) != null
        }
        if (ready.isEmpty()) { notifyShort("گزارش امضاشده و آماده ارسال وجود ندارد"); return }
        AlertDialog.Builder(this).setTitle("ارسال گروهی گزارش‌ها")
            .setMessage("${ready.size} گزارش امضاشده به‌ترتیب ارسال می‌شوند. گزارش‌های دارای بند بدون امتیاز فقط با تأیید قبلی بازرس ارسال خواهند شد. ادامه می‌دهید؟")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("شروع ارسال") { _, _ ->
                bulkOk = 0; bulkFailed = 0
                bulkTotal = ready.size
                bulkPending = ArrayDeque(ready.map { it.optString("mission_key") })
                showTransferDialog("ارسال گروهی ${ready.size} گزارش")
                continueBulk()
            }.show()
    }
    private fun continueBulk() {
        val queue = bulkPending ?: return
        if (queue.isEmpty()) {
            bulkPending = null
            bulkTotal = 0
            dismissTransferDialog()
            showOutbox()
            AlertDialog.Builder(this).setTitle("نتیجه ارسال گروهی")
                .setMessage("موفق: $bulkOk گزارش\nناموفق: $bulkFailed گزارش\nگزارش‌های ناموفق در دستگاه محفوظ مانده‌اند.")
                .setPositiveButton("تأیید", null).show()
            return
        }
        val key = queue.removeFirst()
        sendQueued(key) { success ->
            if (success) bulkOk++ else bulkFailed++
            continueBulk()
        }
    }

    private fun showOutbox() {
        selectedMission = null
        openDraft = null
        activeTab = "outbox"
        screen("صف ارسال گزارش‌ها", "ارسال کنترل‌شده و قابل پیگیری", withNav = true)
        val entries = outbox.entries()
        if (entries.isEmpty()) {
            emptyState("صف ارسال خالی است", "گزارش‌های تأییدشده در این قسمت قرار می‌گیرند.")
            secondaryButton("بازگشت به خانه") { showHome() }
            return
        }
        label("${entries.size} گزارش در انتظار اقدام", 17, true, navy)
        gap(5)
        label(if (online()) "اتصال شبکه برقرار است" else "حالت آفلاین؛ برای ارسال به اینترنت نیاز دارید",
            12, false, if (online()) Color.rgb(24, 121, 80) else Color.rgb(155, 105, 32))
        gap(12)
        val batch = makeButton("ارسال همه گزارش‌های امضاشده", true) { sendAllQueued() }
        batch.setIconResource(R.drawable.ic_send_all)
        batch.iconTint = ColorStateList.valueOf(Color.WHITE)
        content.addView(batch)
        gap(10)
        entries.forEach { item ->
            val key = item.optString("mission_key")
            val mission = item.optJSONObject("mission") ?: JSONObject()
            val state = item.optString("state")
            val status = when (state) {
                "failed" -> "نیازمند تلاش مجدد"
                "uploading" -> "ارسال ناتمام؛ قابل تکرار"
                else -> "در انتظار ارسال"
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 16)
                setPadding(dp(15), dp(16), dp(15), dp(16))
            }
            panel.addView(text(item.optString("organization", "دستگاه اجرایی"), 16, true, navy))
            panel.addView(text(status, 13, true, if (state == "failed") Color.rgb(166, 82, 35) else teal))
            val unscored = InspectionDraft(key, store).unscoredIds(mission).size
            if (unscored > 0) panel.addView(text("$unscored بند ارزیابی‌نشده · ادامه ارسال با تأیید امضاشده بازرس", 12, false, Color.rgb(150,96,31)))
            val attempts = item.optInt("attempts", 0)
            if (attempts > 0) panel.addView(text("تعداد تلاش‌های ارسال: $attempts", 11, false, muted))
            if (item.optString("error").isNotBlank()) panel.addView(text("آخرین خطا: ${item.optString("error")}", 11, false, muted))
            val launch = makeButton("ارسال اکنون", true) { sendQueued(key) }
            launch.setIconResource(R.drawable.ic_send)
            launch.iconTint = ColorStateList.valueOf(Color.WHITE)
            panel.addView(launch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(47)).apply { topMargin = dp(13) })
            val edit = makeButton("خروج از صف و ویرایش", false) {
                AlertDialog.Builder(this).setTitle("بازگشت به پیش‌نویس")
                    .setMessage("گزارش از صف خارج می‌شود اما پاسخ‌ها و مستندات در دستگاه باقی می‌مانند.")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("بازگشت به ویرایش") { _, _ ->
                        outbox.remove(key)
                        signatures.clear(key)
                        showMission(mission)
                    }.show()
            }
            panel.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(7) })
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        label("ارسال گروهی فقط گزارش‌های امضاشده را ارسال می‌کند. بندهای بدون امتیاز تنها پس از تأیید صریح بازرس مجازند.", 12, false, muted)
        secondaryButton("بازگشت به مأموریت‌ها") { showHome("missions") }
    }

    private fun sendQueued(key: String, onFinished: ((Boolean) -> Unit)? = null) {
        if (!online()) { notifyShort("برای ارسال گزارش، اتصال معتبر اینترنت لازم است"); onFinished?.invoke(false); return }
        if (busy) { notifyShort("عملیات دیگری در حال انجام است؛ برای جلوگیری از ارسال تکراری صبر کنید"); onFinished?.invoke(false); return }
        val token = deviceToken ?: run { notifyShort("ابتدا با حساب امن بازرس وارد شوید"); onFinished?.invoke(false); return }
        val record = outbox.get(key) ?: run { notifyShort("رکورد صف پیدا نشد"); onFinished?.invoke(false); return }
        val mission = record.optJSONObject("mission") ?: run { notifyShort("اطلاعات مأموریت ناقص است"); onFinished?.invoke(false); return }
        if (!mission.optBoolean("can_execute", false)) {
            notifyShort("مجوز اجرای مأموریت در اطلاعات ذخیره‌شده وجود ندارد؛ با مدیر بررسی کنید")
            onFinished?.invoke(false)
            return
        }
        val draft = InspectionDraft(key, store)
        val issues = draft.validate(mission)
        if (issues.isNotEmpty() || !draft.hasAcknowledgedUnscored(mission)) {
            notifyShort("گزارش نیازمند اصلاح یا تأیید بندهای بدون امتیاز است")
            onFinished?.invoke(false); return
        }
        val signature = signatures.get(draft)
        if (signature == null) { notifyShort("امضا موجود نیست یا گزارش پس از امضا تغییر کرده است؛ مجدداً امضا کنید"); onFinished?.invoke(false); return }
        if (transferDialog == null) showTransferDialog("ارسال گزارش ${record.optString("organization", "بازرسی")}")
        val scope = transferScope()
        work({
            var currentStage = "دریافت نشست امن"
            try {
                outbox.setState(key, "uploading")
                val session = sessionFor(token)
                val uploads = JSONArray()
                val saved = outbox.get(key)?.optJSONObject("uploaded") ?: JSONObject()
                val documentCount = EvidenceRefs.count(draft.attachments)
                val stages = documentCount + 2 // Evidence, JSON/signature, receipt acknowledgement.
                var completedStages = 0
                var documentNumber = 0
                updateTransferProgress(scope, "در حال احراز هویت و بررسی مستندات ($documentCount فایل)", 0)
                val hashes = mutableMapOf<String, String>()
                val ids = draft.attachments.keys()
                while (ids.hasNext()) {
                    val question = ids.next()
                    for (reference in EvidenceRefs.references(draft.attachments, question)) {
                        documentNumber++
                        val position = documentNumber
                        currentStage = "آپلود مستند $position از $documentCount"
                        updateTransferProgress(scope,
                            "آماده‌سازی مستند $position از $documentCount؛ بند $question",
                            (completedStages * 100 / stages))
                        // Transport-only optimization also covers photos in older saved drafts.
                        // The encrypted original remains untouched until server receipt.
                        val attachment = evidenceVault.read(reference, contentResolver)
                        val prepared = evidenceVault.readPrepared(reference) ?: PhotoUploadOptimizer.prepare(attachment).also { candidate ->
                            if (candidate.optimized) runCatching { evidenceVault.savePrepared(reference, candidate) }
                        }
                        if (prepared.optimized) {
                            updateTransferProgress(scope,
                                "بهینه‌سازی عکس $position از $documentCount: " +
                                    "${(prepared.originalBytes + 1023) / 1024} ← ${(prepared.bytes.size + 1023) / 1024} کیلوبایت\n" +
                                    "نسخه اصلی مستند در دستگاه حفظ شده است",
                                (completedStages * 100 / stages))
                        }
                        val sha = MessageDigest.getInstance("SHA-256").digest(prepared.bytes)
                            .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
                        val usedBy = hashes.putIfAbsent(sha, question)
                        if (usedBy != null) {
                            throw UserActionException(if (usedBy == question)
                                "دو مستند یکسان برای یک سؤال انتخاب شده‌اند؛ یکی را حذف کنید"
                                else "یک فایل یکسان به دو سؤال مختلف پیوست شده است؛ مستند مستقل انتخاب کنید")
                        }
                        val existingId = saved.optInt("$question:$sha", 0)
                        val fileId = if (existingId > 0) existingId else {
                            val response = api.uploadEvidence(
                                session, prepared.bytes, prepared.mime, prepared.name, key, question
                            ) { sent, total ->
                                val completePart = if (total > 0L) (sent * 100L / total).toInt() else 0
                                updateTransferProgress(scope,
                                    "مستند $position از $documentCount: ${prepared.name.take(60)}\n" +
                                        "ارسال‌شده از گوشی: ${sent / 1024} از ${total / 1024} کیلوبایت",
                                    ((completedStages * 100) + completePart) / stages)
                            }
                            val id = response.getInt("file_id")
                            val acceptedQuestion = response.optString("question_key", question)
                            if (acceptedQuestion.isNotBlank() && acceptedQuestion != question) {
                                throw UserActionException("فایل قبلاً به سؤال دیگری نسبت داده شده است")
                            }
                            outbox.rememberUpload(key, question, sha, id)
                            id
                        }
                        uploads.put(JSONObject().put("file_id", fileId).put("question_id", question))
                        completedStages++
                        updateTransferProgress(scope,
                            "مستند $position از $documentCount در سرور تأیید شد · ${completedStages} از $stages مرحله",
                            (completedStages * 100 / stages))
                    }
                }
                val request = JSONObject().put("mission_key", key).put("answers", draft.answers)
                    .put("notes", draft.notes).put("uploads", uploads)
                    .put("started_at", draft.startedAt)
                    .put("finished_at", record.optString("finished_at", Instant.now().toString()))
                    .put("signature_png_base64", signature.getString("signature_png_base64"))
                    .put("signed_at_device", signature.optString("signed_at"))
                    .put("allow_unscored", draft.unscoredIds(mission).isNotEmpty())
                    .put("acknowledged_unscored_ids", draft.acknowledgedIds())
                    .put("acknowledged_at_device", draft.acknowledgedAt)
                currentStage = "ثبت گزارش، پاسخ‌ها و امضا"
                val answered = draft.answeredCount(mission)
                val omitted = draft.unscoredIds(mission).size
                updateTransferProgress(scope,
                    "ارسال پاسخ‌ها: $answered بند · ارزیابی‌نشده: $omitted بند · امضای لمسی",
                    completedStages * 100 / stages)
                val response = api.post(
                    "mobile_submit.php", request, session, draft.submissionKey,
                    onProgress = { sent, total ->
                        val partial = if (total > 0L) (sent * 100L / total).toInt() else 0
                        updateTransferProgress(scope,
                            "ارسال گزارش و امضا: ${sent / 1024} از ${total / 1024} کیلوبایت\n" +
                                "$answered بند امتیازدار · $omitted بند بدون امتیاز",
                            ((completedStages * 100) + partial) / stages)
                    },
                    onAwaitingResponse = {
                        updateTransferProgress(scope,
                            "بسته گزارش، پاسخ‌ها و امضا از دستگاه ارسال شد.\nدر انتظار بررسی و رسید سرور…",
                            (((completedStages + 1) * 100) / stages).coerceAtMost(95))
                    }
                )
                completedStages++
                updateTransferProgress(scope, "انتظار برای دریافت و ثبت رسید سرور…", completedStages * 100 / stages)
                if (!response.optBoolean("ok")) throw UserActionException("تأیید نهایی سرور دریافت نشد؛ گزارش حفظ شد")
                updateTransferProgress(scope,
                    "رسید سرور دریافت شد: ${response.optString("receipt_code", "ثبت شد")}", 100)
                Pair(response, null as Exception?)
            } catch (e: Exception) {
                // A server-side cleanup may invalidate file IDs saved after an earlier attempt.
                // Do not retry automatically; on the next user-initiated attempt re-upload them.
                if (e is ApiException && e.error == "invalid_upload_reference") {
                    outbox.forgetUploadedReferences(key)
                }
                val safeError = "مرحله: $currentStage\n" + AppErrorMessages.forUser(e)
                outbox.setState(key, "failed", safeError)
                updateTransferProgress(scope, "ارسال ناموفق؛ داده‌ها و امضا حفظ شدند\n${safeError.take(600)}", 0)
                Pair(null as JSONObject?, e)
            }
        }) { result ->
            val response = result.first
            if (response != null) {
                if (onFinished == null) dismissTransferDialog()
                store.save("receipt:$key", response.toString())
                // A local history write failure must not turn a server-confirmed success into a retry.
                runCatching { receiptHistory.save(mission, draft, response) }
                EvidenceRefs.all(draft.attachments).forEach { evidenceVault.delete(it) }
                draft.clear()
                outbox.remove(key)
                signatures.clear(key)
                if (onFinished == null) {
                    AlertDialog.Builder(this).setTitle("گزارش با موفقیت ثبت شد")
                        .setMessage("کد رسید: ${response.optString("receipt_code", "ثبت‌شده")}\nرسید در حافظه امن برنامه نگهداری شد.")
                        .setPositiveButton("تأیید") { _, _ -> showOutbox() }.show()
                } else onFinished(true)
            } else {
                if (onFinished == null) {
                    dismissTransferDialog()
                    showOutbox()
                    val message = outbox.get(key)?.optString("error").orEmpty().ifBlank {
                        "اطلاعات و مستندات در گوشی محفوظ ماندند."
                    }
                    AlertDialog.Builder(this).setTitle("علت ناموفق بودن ارسال")
                        .setMessage(message + "\n\nپیش‌نویس، امضا و مستندات حذف نشده‌اند.")
                        .setPositiveButton("متوجه شدم", null).show()
                } else onFinished(false)
            }
        }
    }

    private fun showOfficialReports() {
        val device = deviceToken ?: run { notifyShort("برای مشاهده گزارش رسمی ابتدا وارد حساب شوید"); return }
        activeTab = "official_reports"
        screen("گزارش رسمی", "فهرست ادارات و PDFهای تأییدشده از سرور")
        label("در حال دریافت فهرست رسمی از سرور…", 13, false, muted)
        work({ api.get("mobile_reports.php", sessionFor(device)) }) { result ->
            screen("گزارش رسمی", "گزارش‌های خودتان و وضعیت آماده‌سازی")
            val reports = result.optJSONArray("reports") ?: JSONArray()
            label("${reports.length()} گزارش ثبت‌شده", 16, true, navy)
            gap(12)
            if (reports.length() == 0) emptyState("گزارشی موجود نیست", "گزارش‌ها پس از ثبت و تأیید مدیر در این بخش قابل دریافت هستند.")
            for (i in 0 until reports.length()) {
                val report = reports.optJSONObject(i) ?: continue
                val id = report.optInt("id", 0)
                if (id <= 0) continue
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(Color.WHITE, 16)
                    setPadding(dp(15),dp(15),dp(15),dp(15))
                }
                panel.addView(text(report.optString("org_name", "دستگاه اجرایی"), 16, true, navy))
                panel.addView(text("شماره رسید: ${report.optString("receipt_code")}", 12, false, muted))
                panel.addView(text("تاریخ: ${report.optString("submitted_at")}", 12, false, muted))
                val status = report.optString("evaluation_status", "legacy")
                val scored = report.optInt("answered_count", 0)
                val total = report.optInt("question_count", 0)
                if (total > 0) {
                    panel.addView(text("پوشش ارزیابی: $scored از $total بند · " +
                        if (status == "partial") "دارای بندهای بدون امتیاز" else "کامل", 12, false,
                        if (status == "partial") Color.rgb(150, 96, 31) else muted))
                }
                val available = report.optBoolean("official_ready", false)
                panel.addView(text(if (available) "گزارش رسمی قابل دریافت است" else "در انتظار تأیید مدیر یا آماده‌سازی PDF", 12, true, if (available) Color.rgb(25,124,79) else muted))
                if (available) {
                    panel.addView(makeButton("دانلود PDF خلاصه مدیریتی", true) {
                        pendingOfficialPdf = id to "summary"
                        officialPdfPicker.launch("BAZR-$id-summary.pdf")
                    })
                    panel.addView(makeButton("دانلود PDF تفصیلی همه بندها", false) {
                        pendingOfficialPdf = id to "detailed"
                        officialPdfPicker.launch("BAZR-$id-detailed.pdf")
                    })
                }
                content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin=dp(10) })
            }
            gap(10)
            secondaryButton("بازگشت به خانه") { showHome() }
        }
    }

    /** Server history is authoritative; local receipt details are optional offline copies. */
    private fun showHistory() {
        selectedMission = null
        openDraft = null
        activeTab = "history"
        screen("سوابق بازرسی", "گزارش‌های ثبت‌شده و وضعیت بررسی مدیر")
        val serverReports = serverSnapshot.optJSONArray("reports") ?: JSONArray()
        val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
        val orgNames = mutableMapOf<String, String>()
        for (i in 0 until missions.length()) {
            val m = missions.optJSONObject(i) ?: continue
            orgNames[missionKey(m)] = m.optString("orgName", "دستگاه اجرایی")
        }
        val reports = mutableListOf<Pair<JSONObject, Boolean>>()
        val codes = mutableSetOf<String>()
        for (i in 0 until serverReports.length()) {
            val report = serverReports.optJSONObject(i) ?: continue
            reports.add(report to true)
            report.optString("receipt_code").takeIf { it.isNotBlank() }?.let { codes.add(it) }
        }
        // Never expose a locally cached receipt from a mission outside this session's assigned set.
        receiptHistory.entries().filter { it.optString("mission_key") in orgNames.keys }.forEach { copy ->
            if (copy.optString("receipt_code").isBlank() || copy.optString("receipt_code") !in codes) {
                reports.add(copy to false)
            }
        }
        reports.sortByDescending { (report, _) ->
            report.optString("submitted_at", "")
        }
        label("${reports.size} گزارش ثبت‌شده", 16, true, navy)
        gap(10)
        if (reports.isEmpty()) {
            emptyState("سابقه‌ای موجود نیست", "سوابق سرور پس از همگام‌سازی و رسیدهای جدید پس از ثبت موفق نمایش داده می‌شوند.")
        } else reports.forEach { (report, fromServer) ->
            val key = report.optString("mission_key")
            val organization = orgNames[key] ?: report.optString("organization", "دستگاه اجرایی")
            val card = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                cardElevation = 0f
                strokeColor = line
                strokeWidth = dp(1)
                setCardBackgroundColor(Color.WHITE)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(14), dp(15), dp(14))
            }
            inner.addView(text(organization, 16, true, navy))
            inner.addView(text("شماره رسید: ${report.optString("receipt_code", "ثبت‌شده")}", 13, false, ink))
            inner.addView(text("تاریخ: ${report.optString("submitted_at", "نامشخص")}", 12, false, muted))
            val status = when (report.optString("review_status")) {
                "approved", "accepted" -> "تأیید نهایی مدیریت ثبت شده"
                "supervisor_approved" -> "تأیید سربازرس؛ در انتظار مدیریت"
                "admin_review", "pending_review" -> "در انتظار تأیید سربازرس"
                "rejected", "returned" -> "برای اصلاح به بازرس برگشت داده شد"
                else -> if (fromServer) "وضعیت بررسی ثبت نشده" else "رسید ذخیره‌شده محلی"
            }
            inner.addView(text(status, 12, true, teal))
            inner.addView(makeButton("مشاهده جزئیات", false) { showHistoryDetail(report, fromServer, organization) })
            card.addView(inner)
            content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        gap(8)
        label("وضعیت تأیید مدیر صرفاً از آخرین اطلاعات سرور خوانده می‌شود. رسید محلی به‌تنهایی تأیید اداری محسوب نمی‌شود.", 12, false, muted)
        secondaryButton("همگام‌سازی سوابق") { if (deviceToken != null) connectAndSync() else notifyShort("ابتدا وارد حساب شوید") }
        secondaryButton("گزارش رسمی و دریافت PDF") { showOfficialReports() }
        secondaryButton("بازگشت به خانه") { showHome() }
    }

    private fun showHistoryDetail(report: JSONObject, fromServer: Boolean, organization: String) {
        activeTab = "history"
        screen("جزئیات گزارش", "رسید بازرسی · نسخه نمایشی درون برنامه")
        label(organization, 19, true, navy)
        label("کد رسید: ${report.optString("receipt_code", "ثبت‌شده")}", 15, true, teal)
        label("تاریخ ثبت: ${report.optString("submitted_at", "نامشخص")}", 13, false, muted)
        if (fromServer) {
            label("وضعیت گردش تأیید: ${when(report.optString("review_status")){"admin_review","pending_review"->"در انتظار سربازرس";"supervisor_approved"->"تأیید سربازرس؛ در انتظار مدیریت";"approved","accepted"->"تأیید نهایی مدیریت";"returned","rejected"->"بازگشت برای اصلاح";else->"نامشخص"}}", 14, true, navy)
            report.optString("review_note").takeIf { it.isNotBlank() }?.let {
                label("یادداشت بررسی: $it", 13, false, ink)
            }
        }
        val key = report.optString("mission_key")
        val detail = receiptHistory.find(key)?.takeIf { copy ->
            val localCode = copy.optString("receipt_code")
            localCode.isNotBlank() && localCode == report.optString("receipt_code")
        }
        if (detail != null) {
            gap(12)
            sectionTitle("امتیازات ثبت‌شده")
            val answers = detail.optJSONObject("answers") ?: JSONObject()
            val notes = detail.optJSONObject("notes") ?: JSONObject()
            val titles = detail.optJSONObject("question_titles") ?: JSONObject()
            val ids = answers.keys()
            while (ids.hasNext()) {
                val question = ids.next()
                val panel = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(Color.WHITE, 12)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                panel.addView(text("${titles.optString(question, "سؤال $question")} · امتیاز ${answers.optString(question)}", 14, true, navy))
                notes.optString(question).takeIf { it.isNotBlank() }?.let { panel.addView(text(it, 12, false, muted)) }
                content.addView(panel)
                gap(8)
            }
            label("تعداد مستندات ارسال‌شده: ${detail.optInt("attachment_count")}", 13, false, muted)
            gap(12)
            button("ذخیره PDF محلی (غیررسمی)") {
                if (deviceToken == null) {
                    notifyShort("برای خروجی گزارش باید وارد حساب خود باشید")
                    return@button
                }
                AlertDialog.Builder(this)
                    .setTitle("خروجی محلی گزارش")
                    .setMessage("این فایل از نسخه محلی ثبت‌شده روی گوشی ساخته می‌شود، امضای دیجیتال ندارد و جایگزین گزارش رسمی یا تأیید مدیریت نیست. فقط در محل مطمئن ذخیره کنید.")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("انتخاب محل ذخیره") { _, _ ->
                        pendingPdfExport = JSONObject(detail.toString())
                        val receiptCode = detail.optString("receipt_code").replace(Regex("[^a-zA-Z0-9_-]"), "_").take(35)
                        pdfFilePicker.launch("bazr-local-$receiptCode.pdf")
                    }.show()
            }

        } else {
            label("جزئیات پاسخ‌ها از طریق API فعلی در دسترس بازرس نیست؛ کد رسید و وضعیت از سرور دریافت شده است.", 13, false, muted)
        }
        gap(12)
        label("این نمایش، سند امضاشده یا PDF رسمی صادرشده از سرور نیست.", 12, false, muted)
        secondaryButton("بازگشت به سوابق") { showHistory() }
    }

    private fun showNotices() {
        selectedMission = null
        openDraft = null
        activeTab = "notices"
        screen("اطلاعیه‌های سازمانی", "پیام‌های مجاز دریافت‌شده از سرور")
        val notices = serverSnapshot.optJSONArray("notices") ?: JSONArray()
        if (notices.length() == 0) emptyState("اطلاعیه‌ای موجود نیست", "برای دریافت اطلاعیه‌ها، اطلاعات برنامه را همگام‌سازی کنید.")
        for (i in 0 until notices.length()) {
            val notice = notices.optJSONObject(i) ?: continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.WHITE, 14)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            card.addView(text(notice.optString("title", "اطلاعیه سازمانی"), 16, true, navy))
            val body = listOf("message", "body", "text", "description")
                .map { notice.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            if (body.isNotBlank()) card.addView(text(body.take(1200), 13, false, ink))
            notice.optString("date").takeIf { it.isNotBlank() }?.let { card.addView(text(it, 11, false, muted)) }
            content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        secondaryButton("بازگشت به خانه") { showHome() }
    }

    private fun biometricsAvailable(): Boolean = BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun biometricPrompt(title:String, cipher:Cipher, callback:(Cipher)->Unit) {
        val prompt=BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                val authorized=result.cryptoObject?.cipher
                if (authorized != null) callback(authorized) else notifyShort("تأیید بیومتریک ناقص بود")
            }
            override fun onAuthenticationError(errorCode:Int, errString:CharSequence) {
                super.onAuthenticationError(errorCode,errString)
                notifyShort(errString.toString())
            }
        })
        val info=BiometricPrompt.PromptInfo.Builder()
            .setTitle(title).setSubtitle("سامانه بازرسی")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("انصراف").build()
        prompt.authenticate(info,BiometricPrompt.CryptoObject(cipher))
    }
    private fun enableBiometric() {
        val token=deviceToken ?: return
        if (!biometricsAvailable()) {notifyShort("اثر انگشت قوی در تنظیمات دستگاه فعال نشده است");return}
        try {
            biometricPrompt("فعال‌سازی ورود با اثرانگشت",store.bioEncryptCipher()) { cipher ->
                try { store.finalizeBioEncryption(cipher,token); notifyShort("اثر انگشت فعال شد");showHome() }
                catch (_:Exception) { notifyShort("ذخیره کلید بیومتریک ناموفق بود") }
            }
        }catch(_ :Exception) {notifyShort("فعالسازی اثرانگشت ممکن نشد")}
    }
    private fun showBiometricLock() {
        screen("ورود ایمن", "دسترسی با تأیید هویت دستگاه")
        label("برای مشاهده مأموریت‌ها، اثر انگشت خود را تأیید کنید.",14,false,muted)
        gap(18)
        button("ورود با اثرانگشت") {
            if(!biometricsAvailable()) {notifyShort("اثر انگشت قوی در دسترس نیست");return@button}
            try {
                val cipher=store.bioDecryptCipher() ?: error("token missing")
                biometricPrompt("ورود با اثرانگشت",cipher) { authorized ->
                    try { deviceToken=store.decryptBio(authorized);showHome();connectAndSync() }
                    catch (_:Exception) {notifyShort("کلید دستگاه نامعتبر است؛ ورود مجدد لازم است");showLogin()}
                }
            } catch (_: Exception) {notifyShort("اطلاعات بیومتریک نیازمند فعال‌سازی مجدد است");showLogin()}
        }
        secondaryButton("ورود با نام کاربری و رمز عبور") { showLogin() }
    }
    private fun confirmLogout() {
        AlertDialog.Builder(this).setTitle("خروج")
            .setMessage("از حساب خارج می‌شوید. پیش‌نویس‌های ذخیره‌شده روی دستگاه حذف نمی‌شوند. برای حذف کامل داده‌ها باید از تنظیمات اندروید اقدام کنید.")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("خروج") { _,_ ->
                deviceToken=null;sessionToken=null;accountToken=null
                store.clear("device_token");store.clearBio()
                store.clear("mobile_sync")
                store.removeString("last_sync")
                serverSnapshot = JSONObject()
                showLogin()
            }.show()
    }
}
