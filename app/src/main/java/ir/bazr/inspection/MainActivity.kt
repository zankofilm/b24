package ir.bazr.inspection

import android.content.Intent
import android.Manifest
import android.app.Notification
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Paint
import android.graphics.Canvas
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
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
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
    private lateinit var deficiencies: DeficiencyStore
    private var selectedDeficiencyId: String? = null
    private var pendingNoticeId: String? = null
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
    private var currentSectionIndex = 0
    private var checklistOverviewVisible = false
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
    private val gold = Color.rgb(221, 177, 72)
    private val muted = Color.rgb(103, 117, 135)
    private val ink = Color.rgb(20, 45, 71)
    private val pageBg = Color.rgb(243, 246, 250)
    private val line = Color.rgb(216, 225, 235)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        store.putString("notify_missions_enabled", if (granted) "yes" else "no")
        store.putString("notify_notices_enabled", if (granted) "yes" else "no")
        if (granted) NoticePollingScheduler.schedule(this) else NoticePollingScheduler.cancel(this)
        notifyShort(if (granted) "اعلان‌های سازمانی فعال شد" else "مجوز اعلان داده نشد")
        showHome("notices")
    }
    private val evidencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val question = store.load("pending_picker_question")
        store.clearDurable("pending_picker_question")
        if (uri != null && !question.isNullOrBlank()) importEvidence(uri, question, false)
    }
    private val cameraCapture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val pending = store.load("pending_capture")?.let { runCatching { JSONObject(it) }.getOrNull() }
        store.clearDurable("pending_capture")
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

    private fun importEvidence(uri: Uri, question: String, camera: Boolean, temporary: File? = null) {
        work({
            try { evidenceVault.importUri(contentResolver, uri, camera) }
            finally { temporary?.delete() }
        }) { reference ->
            val draft = openDraft
            if (draft == null || selectedMission == null || outbox.get(draft.key) != null) {
                evidenceVault.delete(reference)
                notifyShort("مأموریت انتخاب‌شده تغییر کرده؛ مستند اضافه نشد")
            } else {
                if (!EvidenceRefs.add(draft.attachments, question, reference)) {
                    evidenceVault.delete(reference)
                    notifyShort("برای هر سؤال حداکثر چهار مستند مستقل مجاز است")
                } else {
                    if (saveDraftSafely(draft)) {
                        selectedMission?.let { showMission(it, currentQuestionIndex) }
                        notifyShort("مستند جدید به گاوصندوق رمزنگاری‌شده افزوده شد")
                    } else {
                        EvidenceRefs.remove(draft.attachments, question, reference)
                        evidenceVault.delete(reference)
                    }
                }
            }
        }
    }

    private fun launchCamera(question: String) {
        val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
        val name = "capture-${UUID.randomUUID()}.jpg"
        val photo = File(cameraDir, name)
        store.saveDurable("pending_capture", JSONObject().put("name", name).put("question", question).toString())
        try {
            cameraCapture.launch(FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", photo))
        } catch (_: Exception) {
            store.clearDurable("pending_capture")
            photo.delete()
            notifyShort("برنامه دوربین در این دستگاه در دسترس نیست")
        }
    }

    /** Launch Android's real file picker, not an instructional pop-up. */
    private fun launchEvidencePicker(question: String) {
        if (question.isBlank()) { notifyShort("شناسه سؤال نامعتبر است"); return }
        store.saveDurable("pending_picker_question", question)
        try {
            evidencePicker.launch(arrayOf("image/*", "application/pdf"))
        } catch (error: Exception) {
            store.clearDurable("pending_picker_question")
            AlertDialog.Builder(this)
                .setTitle("باز کردن گالری ناموفق بود")
                .setMessage("در این گوشی برنامه انتخاب فایل در دسترس نیست. لطفاً یک گالری یا فایل‌منیجر نصب یا فعال کنید.")
                .setPositiveButton("متوجه شدم", null).show()
        }
    }

    /** Short native action sheet; a 200 KB warning must never replace picker actions. */
    private fun chooseEvidence(question: String) {
        AlertDialog.Builder(this).setTitle("ثبت مستند بازرسی")
            .setItems(arrayOf("عکس‌برداری با دوربین", "انتخاب تصویر یا PDF از گوشی")) { _, which ->
                if (which == 0) launchCamera(question) else launchEvidencePicker(question)
            }.setNegativeButton("انصراف", null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        // Keep a separate encrypted device credential for notification-only polling.
        // It is used only by mobile_notice_poll.php and is cleared on explicit logout.
        if (store.load("notification_device_token") == null) {
            runCatching { store.load("device_token") }.getOrNull()?.let {
                runCatching { store.saveDurable("notification_device_token", it) }
            }
        }
        if (store.getString("notify_notices_enabled") == null &&
            store.getString("notify_missions_enabled") == "yes") {
            store.putString("notify_notices_enabled", "yes")
        }
        if (store.getString("notify_notices_enabled") == "yes") NoticePollingScheduler.schedule(this)
        keys = DeviceKeys()
        api = ApiClient(BuildConfig.API_BASE_URL)
        evidenceVault = EvidenceVault(this, store)
        evidenceVault.cleanupOrphans()
        outbox = SubmissionQueue(store)
        receiptHistory = ReceiptHistory(store)
        signatures = SignedDraft(store)
        deficiencies = DeficiencyStore(store)
        // Remove unreferenced, temporary plaintext camera files left by interrupted captures.
        val pendingName = store.load("pending_capture")?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }
        File(cacheDir, "camera").listFiles()?.filter { it.name != pendingName && it.lastModified() < System.currentTimeMillis() - 86_400_000L }
            ?.forEach { it.delete() }
        store.getString("device_id") ?: store.putStringDurable("device_id", UUID.randomUUID().toString())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (activeTab) {
                    "deficiency_detail" -> showHome("deficiencies")
                    "deficiency_editor" -> {
                        val mission = selectedMission
                        if (mission != null) showMission(mission, currentQuestionIndex) else showHome("deficiencies")
                    }
                    "deficiencies", "notices", "evidence" -> showHome("home")
                    else -> if (checklistOverviewVisible && selectedMission != null) {
                        val mission = selectedMission!!
                        checklistOverviewVisible = false
                        showMission(mission, currentQuestionIndex)
                    } else if (selectedMission != null) showHome("missions")
                    else if (activeTab == "outbox") showHome("drafts")
                    else if (activeTab != "home" && ::content.isInitialized) showHome("home")
                    else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        val cached = store.load("mobile_sync")
        if (cached != null) try { serverSnapshot = JSONObject(cached) } catch (_: Exception) { }
        showSplash {
            pendingNoticeId = intent?.getStringExtra("notice_id")
            when {
                store.biometricEnabled() -> showBiometricLock()
                store.load("device_token") != null -> {
                    deviceToken = store.load("device_token")
                    val requestedTab = if (intent?.getStringExtra("open_tab") == "notices") "notices" else "home"
                    pendingNoticeId = intent?.getStringExtra("notice_id")
                    showHome(requestedTab)
                    connectAndSync()
                }
                store.getString("request_id") != null -> showPending()
                else -> showLogin()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("open_tab") == "notices") {
            pendingNoticeId = intent.getStringExtra("notice_id")
            if (deviceToken != null) {
                showHome("notices")
                connectAndSync()
            } else if (store.biometricEnabled()) showBiometricLock()
            else notifyShort("برای مشاهده ابلاغیه‌ها ابتدا وارد حساب بازرس شوید")
        }
    }

    override fun onDestroy() {
        dismissTransferDialog()
        io.shutdown()
        super.onDestroy()
    }
    private fun deviceId() = store.getString("device_id") ?: error("missing device ID")
    private fun notifyShort(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun saveDraftSafely(draft: InspectionDraft): Boolean {
        if (draft.integrityError) return false
        return try {
            draft.save()
            true
        } catch (error: Exception) {
            notifyShort(AppErrorMessages.forUser(error))
            false
        }
    }
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()
    private fun faDigits(value: String): String = value.map { c ->
        if (c in '0'..'9') ('۰'.code + (c - '0')).toChar() else c
    }.joinToString("")

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
    /** Native app shell: navy institutional header, scrollable white canvas and navy/gold bottom navigation. */
    private fun screen(title: String, subtitle: String, withNav: Boolean = false): LinearLayout {
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
        shell.addView(appTopBar(title, subtitle), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(65)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(13), dp(16), dp(24))
        }
        scroll.addView(content)
        shell.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (withNav) shell.addView(bottomNavigation())
        setContentView(shell)
        applyShellInsets(shell)
        return content
    }

    /** Draw system bars deliberately so Android 15 edge-to-edge does not cover controls. */
    private fun applyShellInsets(root: LinearLayout) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use the Java setters explicitly; Kotlin synthetic property resolution
            // differs for Window's isX()/setX() boolean accessors.
            window.setStatusBarContrastEnforced(false)
            window.setNavigationBarContrastEnforced(false)
        }
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        val topScrim = View(this).apply { setBackgroundColor(navy) }
        val bottomScrim = View(this).apply { setBackgroundColor(Color.WHITE) }
        root.addView(topScrim, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.addView(bottomScrim, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topScrim.layoutParams = topScrim.layoutParams.apply { height = safe.top }
            bottomScrim.layoutParams = bottomScrim.layoutParams.apply { height = safe.bottom }
            root.setPadding(safe.left + baseLeft, baseTop, safe.right + baseRight, baseBottom)
            insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun applySplashInsets(root: FrameLayout) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use the Java setters explicitly; Kotlin synthetic property resolution
            // differs for Window's isX()/setX() boolean accessors.
            window.setStatusBarContrastEnforced(false)
            window.setNavigationBarContrastEnforced(false)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Version 4.14 reference toolbar: navy surface, gold shield and hamburger navigation. */
    private fun appTopBar(pageTitle: String = "", pageSubtitle: String = ""): View {
        val isHome = pageTitle.isBlank() || pageTitle == "میز کار بازرس"
        val heading = when (pageTitle) {
            "", "میز کار بازرس" -> "سامانه بازرسی ادارات"
            "ثبت بازرسی" -> "فرم بازرسی"
            "بازبینی گزارش" -> "مرور گزارش"
            else -> pageTitle
        }
        val subheading = when {
            pageSubtitle.isNotBlank() && !isHome -> pageSubtitle
            isHome -> "فرمانداری شهرستان جوانرود"
            else -> "سامانه بازرسی ادارات"
        }
        val backPages = setOf(
            "مأموریت‌های من", "فرم بازرسی", "ثبت بازرسی", "بازبینی گزارش", "مرور گزارش", "نمای کلی شاخص‌ها",
            "پیش‌نویس‌های من", "گزارش‌ها و تحلیل‌ها", "گزارش رسمی", "سوابق بازرسی",
            "جزئیات گزارش", "ادارات مجاز من", "صف ارسال گزارش‌ها", "ارسال گزارش‌ها", "صندوق ابلاغیه‌ها", "کارتابل سربازرس",
            "بررسی گزارش بازرس", "تأیید دستگاه", "اطلاعیه‌های سازمانی", "تأیید هویت", "مدیریت نواقص",
            "جزئیات نقص", "ثبت نقص جدید"
        )
        val showBack = pageTitle in backPages
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(navy)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        val mark = MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = 0f
            setCardBackgroundColor(Color.rgb(13, 43, 72))
            contentDescription = "نشان سامانه بازرسی"
        }
        mark.addView(icon(R.drawable.ic_shield, gold, 25),
            FrameLayout.LayoutParams(dp(29), dp(29), Gravity.CENTER))
        row.addView(mark, LinearLayout.LayoutParams(dp(43), dp(43)))

        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        names.addView(text(heading, 15, true, Color.WHITE).apply { maxLines = 1 })
        names.addView(text(subheading, 9, false, Color.rgb(205, 218, 232)).apply {
            setPadding(0, dp(2), 0, 0); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val leading = ImageView(this).apply {
            setImageResource(if (showBack) R.drawable.ic_arrow_back else R.drawable.ic_menu)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = if (showBack) "بازگشت" else "باز کردن منوی برنامه"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (showBack) navigateBackFromTopBar(pageTitle) else showAppMenu()
            }
        }
        row.addView(leading, LinearLayout.LayoutParams(dp(42), dp(44)))
        wrapper.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)))
        wrapper.addView(View(this).apply { setBackgroundColor(gold) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        return wrapper
    }

    private fun navigateBackFromTopBar(pageTitle: String) {
        when (pageTitle) {
            "فرم بازرسی", "ثبت بازرسی" -> showHome("missions")
            "بازبینی گزارش", "مرور گزارش", "نمای کلی شاخص‌ها" -> {
                val mission = selectedMission
                if (mission != null) showMission(mission, currentQuestionIndex) else showHome("missions")
            }
            "جزئیات گزارش" -> showHistory()
            "گزارش رسمی", "سوابق بازرسی" -> showHome("analytics")
            "صف ارسال گزارش‌ها" -> showHome("drafts")
            "کارتابل سربازرس", "بررسی گزارش بازرس" -> showHome("supervisor")
            "تأیید دستگاه", "تأیید هویت" -> showLogin()
            "اطلاعیه‌های سازمانی", "صندوق ابلاغیه‌ها" -> showHome("home")
            "مدیریت نواقص" -> showHome("home")
            "جزئیات نقص" -> showHome("deficiencies")
            "ثبت نقص جدید" -> {
                val mission = selectedMission
                if (mission != null) showMission(mission, currentQuestionIndex) else showHome("deficiencies")
            }
            else -> showHome("home")
        }
    }

    private fun showAppMenu() {
        val labels = mutableListOf("خانه", "مأموریت‌ها", "ادارات مجاز", "پیش‌نویس‌ها", "نواقص و پیگیری اصلاحات", "صندوق ابلاغیه‌ها", "گزارش‌ها و تحلیل‌ها", "گزارش رسمی", "حساب کاربری")
        val actions = mutableListOf<() -> Unit>(
            { showHome("home") }, { showHome("missions") }, { showHome("departments") },
            { showHome("drafts") }, { showHome("deficiencies") }, { showHome("notices") }, { showHome("analytics") }, { showOfficialReports() }, { showHome("account") }
        )
        if (serverSnapshot.optJSONObject("supervisor")?.optBoolean("can_review", false) == true) {
            labels.add(4, "کارتابل سربازرس")
            actions.add(4) { showHome("supervisor") }
        }
        AlertDialog.Builder(this).setTitle("ناوبری سامانه")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("بستن", null).show()
    }

    /** Login and biometric screens use the same approved light native shell as the dashboard. */
    private fun authScreen(): LinearLayout = screen("", "", withNav = false)

    private fun showSplash(next: () -> Unit) {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(4, 22, 41)) }
        val banner = ImageView(this).apply {
            setImageResource(R.drawable.official_brand_banner)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170), Gravity.TOP))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(0, 4, 22, 41), Color.rgb(4, 22, 41)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220), Gravity.TOP))
        val building = ImageView(this).apply {
            setImageResource(R.drawable.login_building)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.82f
        }
        root.addView(building, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210), Gravity.BOTTOM))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.rgb(4, 22, 41)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250), Gravity.BOTTOM))
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val emblem = MaterialCardView(this).apply {
            radius = dp(34).toFloat(); strokeWidth = dp(2); strokeColor = gold
            cardElevation = dp(8).toFloat(); setCardBackgroundColor(Color.rgb(7, 32, 56))
        }
        emblem.addView(icon(R.drawable.ic_shield, Color.rgb(240, 190, 76), 72),
            FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
        center.addView(emblem, LinearLayout.LayoutParams(dp(112), dp(112)).apply { bottomMargin = dp(18) })
        center.addView(text("سامانه بازرسی ادارات", 24, true, Color.WHITE).apply { gravity = Gravity.CENTER })
        center.addView(text("فرمانداری شهرستان جوانرود", 15, true, Color.rgb(244, 194, 74)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        })
        center.addView(text("در مسیر نظارت دقیق و شفاف", 12, false, Color.rgb(222, 231, 240)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(30), 0, 0)
        })
        root.addView(center, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        applySplashInsets(root)
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing && !isDestroyed) next() }, 1250L)
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
            boxStrokeColor = line
            setBoxCornerRadii(dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat(),dp(13).toFloat())
            this.hint = hint
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (password) {
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                startIconDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_lock)
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
    private fun goldButton(caption: String, action: () -> Unit): MaterialButton {
        val b = makeButton(caption, true, action).apply {
            backgroundTintList = ColorStateList.valueOf(Color.rgb(225, 177, 67))
            setTextColor(Color.rgb(8, 31, 56))
            strokeColor = ColorStateList.valueOf(Color.rgb(194, 145, 44))
            textSize = 15f
            cornerRadius = dp(14)
            elevation = dp(2).toFloat()
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54))
        params.bottomMargin = dp(10)
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
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
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
    /** Version 4.15 light bottom navigation: navy active capsule, gold active glyph. */
    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(6), dp(5), dp(6), dp(5))
            elevation = dp(9).toFloat()
        }
        val items = listOf(
            Triple("home", "خانه", R.drawable.ic_home),
            Triple("missions", "بازرسی", R.drawable.ic_clipboard),
            Triple("drafts", "پیش‌نویس‌ها", R.drawable.ic_notes),
            Triple("analytics", "گزارش‌ها", R.drawable.ic_chart),
            Triple("notices", "ابلاغیه‌ها", R.drawable.ic_bell),
            Triple("account", "بیشتر", R.drawable.ic_person)
        )
        for ((id, caption, drawable) in items) {
            val chosen = activeTab == id ||
                (id == "drafts" && activeTab == "outbox") ||
                (id == "analytics" && activeTab in setOf("official_reports", "history")) ||
                (id == "account" && activeTab in setOf("departments", "supervisor", "deficiencies", "evidence"))
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(2), dp(2), dp(2))
                isClickable = true
                isFocusable = true
                contentDescription = caption
                setOnClickListener {
                    if (!busy) {
                        when (id) {
                            "analytics" -> showHome("analytics")
                            else -> showHome(id)
                        }
                    }
                }
            }
            val iconHolder = FrameLayout(this).apply {
                background = if (chosen) rounded(navy, 11) else rounded(Color.TRANSPARENT, 11)
                addView(icon(drawable, if (chosen) gold else navy, 22),
                    FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
            }
            if (id == "notices") {
                val unread = noticeUnreadCount()
                if (unread > 0) {
                    val badge = text(if (unread > 99) "۹۹+" else faDigits(unread.toString()), 8, true, Color.WHITE).apply {
                        gravity = Gravity.CENTER
                        background = rounded(Color.rgb(184, 54, 48), 12)
                        setPadding(dp(4), 0, dp(4), 0)
                    }
                    iconHolder.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(16), Gravity.TOP or Gravity.END))
                }
            }
            item.addView(iconHolder, LinearLayout.LayoutParams(dp(43), dp(34)))
            val captionView = text(caption, 9, chosen, if (chosen) navy else muted)
            captionView.gravity = Gravity.CENTER
            captionView.maxLines = 1
            captionView.setPadding(0, dp(2), 0, 0)
            item.addView(captionView)
            bar.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        val divider = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        divider.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        divider.addView(bar)
        return divider
    }

    private fun statCard(number: String, caption: String, drawable: Int, background: Int, foreground: Int): View {
        val outer = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumHeight = dp(112)
        }
        val badge = FrameLayout(this).apply {
            setBackground(rounded(background, 12))
            addView(icon(drawable, foreground, 22),
                FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
        }
        inner.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)))
        inner.addView(text(number, 25, true, navy).apply {
            setPadding(0, dp(5), 0, 0)
        })
        inner.addView(text(caption, 12, false, muted))
        outer.addView(inner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return outer
    }
    private fun statsRow(left: View, right: View) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val a = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }
        val b = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) }
        row.addView(left, a); row.addView(right, b)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        content.addView(row, lp)
    }
    /** Building-backed welcome card from the approved 4.14 reference. */
    private fun hero(profileName: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(19).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(navy)
            contentDescription = "میز کار بازرس، ${profileName.trim()}"
        }
        val frame = FrameLayout(this)
        val building = ImageView(this).apply {
            setImageResource(R.drawable.login_building)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.30f
        }
        frame.addView(building, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(126)))
        frame.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(40, 8, 31, 56), Color.rgb(8, 31, 56)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(126)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(13), dp(17), dp(13))
        }
        val avatar = MaterialCardView(this).apply {
            radius = dp(32).toFloat()
            strokeWidth = dp(2)
            strokeColor = Color.WHITE
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.rgb(247, 249, 252))
        }
        avatar.addView(icon(R.drawable.ic_person, navy, 35),
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        row.addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginStart = dp(12) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        copy.addView(text("میز کار سازمانی", 11, true, Color.rgb(238, 193, 92)))
        copy.addView(text("سلام، ${profileName.ifBlank { "بازرس گرامی" }}", 19, true, Color.WHITE).apply {
            setPadding(0, dp(4), 0, dp(3)); maxLines = 1
        })
        copy.addView(text("مأموریت‌ها و گزارش‌های شما", 11, false, Color.rgb(222, 232, 242)))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val seal = FrameLayout(this).apply {
            background = rounded(Color.argb(24, 230, 184, 83), 12)
            addView(icon(R.drawable.ic_shield, gold, 26), FrameLayout.LayoutParams(dp(31), dp(31), Gravity.CENTER))
        }
        row.addView(seal, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
        frame.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(126)))
        card.addView(frame)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(126)))
        gap(12)
    }

    private fun quickActionCard(title: String, subtitle: String, drawable: Int, action: () -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) action() }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(10), dp(13), dp(10), dp(13))
        }
        val badge = iconBadge(drawable, 23)
        body.addView(badge, LinearLayout.LayoutParams(dp(46), dp(46)).apply { bottomMargin = dp(8) })
        body.addView(text(title, 14, true, navy).apply { gravity = Gravity.CENTER })
        body.addView(text(subtitle, 10, false, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) })
        card.addView(body)
        return card
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
                    if (!isFinishing && !isDestroyed) {
                        try { done(result) }
                        catch (callbackError: Exception) { notifyShort(AppErrorMessages.forUser(callbackError)) }
                    }
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
        val root = FrameLayout(this).apply { setBackgroundColor(pageBg) }
        val hero = FrameLayout(this)
        val building = ImageView(this).apply {
            setImageResource(R.drawable.login_building)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.62f
        }
        hero.addView(building, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(55, 4, 20, 38), Color.rgb(5, 28, 51)))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310)))
        val flag = ImageView(this).apply {
            setImageResource(R.drawable.iran_flag_ribbon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "پرچم جمهوری اسلامی ایران"
        }
        hero.addView(flag, FrameLayout.LayoutParams(dp(105), dp(100), Gravity.TOP or Gravity.LEFT))
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(25), dp(24), dp(10))
        }
        val emblem = MaterialCardView(this).apply {
            radius = dp(22).toFloat(); strokeWidth = dp(2); strokeColor = gold
            cardElevation = dp(5).toFloat(); setCardBackgroundColor(Color.rgb(8, 38, 66))
        }
        emblem.addView(icon(R.drawable.ic_shield, gold, 42),
            FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        brand.addView(emblem, LinearLayout.LayoutParams(dp(76), dp(76)).apply { bottomMargin = dp(10) })
        brand.addView(text("سامانه بازرسی ادارات", 23, true, Color.WHITE).apply { gravity = Gravity.CENTER })
        brand.addView(text("فرمانداری شهرستان جوانرود", 13, true, Color.rgb(238, 197, 111)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(5), 0, 0)
        })
        hero.addView(brand, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270), Gravity.TOP))
        root.addView(hero, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(310), Gravity.TOP))

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(20))
        }
        val loginCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(23), dp(22), dp(24))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = floatArrayOf(dp(25).toFloat(), dp(25).toFloat(), dp(25).toFloat(), dp(25).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        content = loginCard
        loginCard.addView(text("ورود به سامانه", 19, true, navy))
        loginCard.addView(text("برای دسترسی به مأموریت‌های بازرسی، اطلاعات حساب خود را وارد کنید.", 11, false, muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        })
        val remembered = store.getString("remembered_username").orEmpty()
        val username = field("نام کاربری", remembered)
        username.setAutofillHints(View.AUTOFILL_HINT_USERNAME)
        val password = field("رمز عبور", password = true)
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        val rememberUsername = CheckBox(this).apply {
            text = "مرا به خاطر بسپار"
            textSize = 12f
            setTextColor(muted)
            isChecked = remembered.isNotBlank()
            buttonTintList = ColorStateList.valueOf(gold)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        loginCard.addView(rememberUsername, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        button("ورود به سامانه") {
            val name = username.text.toString().trim()
            val pass = password.text.toString()
            if (name.isBlank() || pass.isBlank()) {
                notifyShort("نام کاربری و رمز عبور را وارد کنید")
                return@button
            }
            val remember = rememberUsername.isChecked
            work({
                val loginResponse = api.post("login.php", JSONObject().put("kind", "inspector").put("username", name).put("password", pass))
                val token = loginResponse.getString("token")
                val req = JSONObject()
                    .put("device_id", deviceId())
                    .put("public_key_pem", keys.publicKeyPem())
                    .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(190))
                    .put("app_version", BuildConfig.VERSION_NAME)
                api.post("mobile_activate_authenticated.php", req, token)
            }) { response ->
                if (remember) store.putString("remembered_username", name)
                else store.removeString("remembered_username")
                store.putStringDurable("request_id", response.getString("request_id"))
                showPending()
                if (response.optString("status") == "approved") checkApproval()
            }
        }
        if (store.biometricEnabled() && biometricsAvailable()) {
            val bio = secondaryButton("ورود با اثر انگشت") { showBiometricLock() }
            bio.setIconResource(R.drawable.ic_fingerprint)
            bio.iconTint = ColorStateList.valueOf(navy)
            bio.iconGravity = MaterialButton.ICON_GRAVITY_END
        }
        val securityNote = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(Color.rgb(241, 245, 249), 12)
        }
        val securityCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        securityCopy.addView(text("ورود امن سازمانی", 12, true, navy))
        securityCopy.addView(text("رمز عبور شما روی دستگاه ذخیره نمی‌شود.", 10, false, muted))
        securityNote.addView(securityCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        securityNote.addView(icon(R.drawable.ic_shield, gold, 22))
        loginCard.addView(securityNote)
        if (store.getString("request_id") != null) {
            gap(8)
            secondaryButton("پیگیری درخواست فعال‌سازی دستگاه") { showPending() }
        }
        scroll.addView(loginCard)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(250)
        })
        setContentView(root)
        applySplashInsets(root)
        WindowInsetsControllerCompat(window, root).isAppearanceLightNavigationBars = true
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
                    store.saveDurable("device_token", fresh)
                    store.saveDurable("notification_device_token", fresh)
                    store.removeStringDurable("request_id")
                    deviceToken = fresh
                    notifyShort("دستگاه تأیید شد")
                    showHome("home")
                    connectAndSync()
                }
                "revoked" -> {
                    deviceToken = null
                    sessionToken = null
                    store.clearDurable("device_token")
                    store.clearDurable("notification_device_token")
                    store.clearDurable("pending_notice_reads")
                    store.clearDurable("notice_notified_ids")
                    NoticePollingScheduler.cancel(this)
                    store.clearBio()
                    store.removeStringDurable("request_id")
                    showLogin()
                    notifyShort("دسترسی این دستگاه لغو شده است؛ برای فعال‌سازی دوباره وارد شوید")
                }
                else -> notifyShort("در انتظار تأیید مدیر")
            }
        }
    }

    private fun sessionFor(token: String): String {
        val body = signedBody("session").put("device_token", token)
        return api.post("mobile_session.php", body).getString("token")
    }
    private fun connectAndSync() {
        val token = deviceToken ?: return
        sessionToken = null
        if (activeTab == "home") showHome("home")
        work({
            val session = sessionFor(token)
            val syncedDeficiencies = syncPendingDeficiencies(session)
            syncPendingNoticeReads(session)
            val payload = api.get("mobile_sync.php", session)
            payload.put("deficiency_sync_count", syncedDeficiencies)
            val noticeInbox = try { api.get("mobile_notices.php", session) }
                catch (error: ApiException) {
                    if (error.status == 404) JSONObject().put("ok", true).put("notices", payload.optJSONArray("notices") ?: JSONArray())
                    else throw error
                }
            val mergedNotices = linkedMapOf<String, JSONObject>()
            for (source in listOf(payload.optJSONArray("notices") ?: JSONArray(),
                noticeInbox.optJSONArray("notices") ?: JSONArray())) {
                for (index in 0 until source.length()) {
                    val item = source.optJSONObject(index) ?: continue
                    val key = noticeKey(item)
                    if (key.isNotBlank()) mergedNotices[key] = JSONObject(item.toString())
                }
            }
            val normalizedNotices = JSONArray()
            mergedNotices.values.forEach { normalizedNotices.put(it) }
            payload.put("notices", normalizedNotices)
            payload.put("notice_unread_count", (0 until normalizedNotices.length()).count {
                normalizedNotices.optJSONObject(it)?.optBoolean("is_read") != true
            })
            val supervisorInbox = try { api.get("mobile_supervisor_inbox.php", session) }
                catch (error: ApiException) {
                    if (error.status in setOf(403, 404)) JSONObject().put("ok", true).put("can_review", false).put("items", JSONArray())
                    else throw error
                }
            payload.put("supervisor", supervisorInbox)
            val serverFindings = payload.optJSONArray("findings") ?: JSONArray()
            for (i in 0 until serverFindings.length()) {
                serverFindings.optJSONObject(i)?.let { deficiencies.mergeServerFinding(it) }
            }
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
            showHome(if (activeTab in setOf("home", "missions", "departments", "drafts", "account", "supervisor", "deficiencies", "notices", "evidence") && (activeTab != "supervisor" || payload.optJSONObject("supervisor")?.optBoolean("can_review", false) == true)) activeTab else "home")
            NoticeNotificationDispatcher.dispatch(this, payload.optJSONArray("notices") ?: JSONArray(), store)
            if (newCount > 0) {
                sendMissionNotification(newCount)
                notifyShort("$newCount مأموریت جدید دریافت شد؛ از فهرست مأموریت‌ها مشاهده کنید")
            }
        }
    }

    private fun findingPayload(record: JSONObject): JSONObject {
        val id = record.optString("id").toLongOrNull() ?: throw UserActionException("شناسه نقص معتبر نیست")
        val missionKey = record.optString("mission_key")
        val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
        val mission = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }.firstOrNull { missionKey(it) == missionKey }
        val missionId = mission?.opt("id") ?: missionKey
        return JSONObject()
            .put("id", id)
            .put("missionId", missionId)
            .put("missionKey", missionKey)
            .put("orgId", record.optString("organization_id"))
            .put("orgName", record.optString("organization"))
            .put("questionId", record.optString("question_id"))
            .put("questionIndex", record.optInt("question_index"))
            .put("sectionId", record.optString("section_id"))
            .put("questionTitle", record.optString("question_title"))
            .put("title", record.optString("question_title"))
            .put("description", record.optString("description"))
            .put("severity", DeficiencyWorkflowRules.serverSeverity(record.optString("severity")))
            .put("status", DeficiencyWorkflowRules.serverStatus(record.optString("status")))
            .put("dueDate", record.optString("due_date_jalali"))
            .put("resolutionNote", record.optString("resolution_note"))
            .put("createdAt", record.optString("created_at"))
            .put("updatedAt", record.optString("updated_at"))
            .put("expectedServerUpdatedAt", record.optString("server_updated_at"))
            .put("source", "android_inspector")
            .put("inspectorRef", serverSnapshot.optJSONObject("profile")?.optString("inspector_ref", "").orEmpty())
            .put("createdBy", record.optString("created_by", deficiencyActor()))
    }

    private fun syncOneDeficiency(record: JSONObject, bearer: String) {
        val id = record.optString("id")
        val action = if (record.optString("server_id").isBlank()) "create" else "update"
        val request = JSONObject()
            .put("action", action)
            .put("mission_key", record.optString("mission_key"))
            .put("finding_key", id)
            .put("payload", findingPayload(record))
        try {
            val response = api.post("mobile_finding.php", request, bearer, "finding-$id")
            deficiencies.markSynced(id, id, response.optString("updated_at"))
        } catch (error: Exception) {
            deficiencies.markSyncError(id, AppErrorMessages.forUser(error))
            throw error
        }
    }

    private fun syncPendingDeficiencies(bearer: String): Int {
        var synced = 0
        deficiencies.pendingEntries().forEach { record ->
            try { syncOneDeficiency(record, bearer); synced++ }
            catch (_: Exception) { /* Keep this item pending; do not block mission sync. */ }
        }
        return synced
    }

    private fun missionKey(mission: JSONObject): String =
        mission.optString("_server_key", mission.optString("id"))

    private fun missionDraft(mission: JSONObject): Boolean =
        missionKey(mission).let { it.isNotBlank() && store.hasEncryptedRecord("draft:$it") }

    private fun reportCount(): Int = (serverSnapshot.optJSONArray("reports") ?: JSONArray()).length()

    /** Reading a draft never changes server-owned mission information. */
    private fun answeredQuestions(mission: JSONObject): Pair<Int, Int> {
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val total = questions.length()
        if (!missionDraft(mission)) return Pair(0, total)
        val draft = InspectionDraft(missionKey(mission), store)
        val answered = (0 until total).count { index ->
            val key = questions.optJSONObject(index)?.optString("id") ?: ""
            val value = ScoreInput.parse(draft.answers.optString(key))
            key.isNotBlank() && value != null && value in 0.0..100.0
        }
        return Pair(answered, total)
    }

    private fun lastSyncShort(): String {
        val raw = store.getString("last_sync") ?: return "هنوز همگام‌سازی نشده"
        return try {
            val time = Instant.parse(raw).atZone(ZoneId.systemDefault())
            "آخرین همگام‌سازی: " + DateTimeFormatter.ofPattern("HH:mm", Locale("fa")).format(time)
        } catch (_: Exception) { "سوابق همگام‌سازی موجود" }
    }

    private fun dashboardMissionItem(mission: JSONObject) {
        val key = missionKey(mission)
        val name = mission.optString("orgName", "دستگاه اجرایی").ifBlank { "دستگاه اجرایی" }
        val date = faDigits(mission.optString("date", "").trim())
        val isDraft = key.isNotBlank() && missionDraft(mission)
        val canExecute = mission.optBoolean("can_execute", false)
        val status = when {
            isDraft -> "پیش‌نویس ذخیره‌شده"
            canExecute -> "در انتظار انجام"
            else -> mission.optString("schedule_label", "زمان‌بندی‌شده").ifBlank { "زمان‌بندی‌شده" }
        }
        val statusBg = if (isDraft) Color.rgb(242, 237, 249) else if (canExecute) Color.rgb(250, 243, 229) else Color.rgb(235, 240, 246)
        val statusFg = if (isDraft) Color.rgb(112, 83, 154) else if (canExecute) Color.rgb(163, 112, 42) else muted
        val card = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener { if (!busy) showMission(mission) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val symbol = FrameLayout(this).apply {
            background = rounded(Color.rgb(249, 244, 232), 12)
            addView(icon(R.drawable.ic_clipboard, navy, 22), FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
        }
        row.addView(symbol, LinearLayout.LayoutParams(dp(43), dp(43)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(9), 0)
        }
        info.addView(text(name, 14, true, navy).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(text(date.ifBlank { "تاریخ ابلاغ ثبت نشده" }, 10, false, muted).apply { setPadding(0, dp(4), 0, 0) })
        if (isDraft) {
            val (answered, total) = answeredQuestions(mission)
            if (total > 0) {
                info.addView(text("${faDigits(answered.toString())} از ${faDigits(total.toString())} شاخص تکمیل‌شده", 9, true, teal).apply {
                    setPadding(0, dp(5), 0, dp(3))
                })
                val progress = LinearProgressIndicator(this).apply {
                    isIndeterminate = false
                    max = total
                    setProgressCompat(answered, false)
                    trackColor = Color.rgb(235, 239, 244)
                    setIndicatorColor(gold)
                }
                info.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            }
        }
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(status, statusBg, statusFg).apply {
            textSize = 9f
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(7), dp(9), dp(7))
        })
        card.addView(row)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(9)
        })
    }

    private fun lastSyncDescription(): String {
        val raw = store.getString("last_sync") ?: return "هنوز همگام‌سازی انجام نشده است"
        return try {
            val time = Instant.parse(raw).atZone(ZoneId.systemDefault())
            "آخرین همگام‌سازی: " + DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale("fa")).format(time)
        } catch (_: Exception) { "اطلاعات آخرین همگام‌سازی ثبت شده است" }
    }

    /** Persian local timestamp for local draft metadata; never used as server authority. */
    private fun formatLocalTimestamp(raw: String): String {
        if (raw.isBlank()) return "زمان ذخیره ثبت نشده"
        return try {
            DateTimeFormatter.ofPattern("yyyy/MM/dd · HH:mm", Locale("fa"))
                .format(Instant.parse(raw).atZone(ZoneId.systemDefault()))
        } catch (_: Exception) { "زمان ذخیره ثبت شده" }
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
            store.putString("notify_notices_enabled", "yes")
            NoticePollingScheduler.schedule(this)
            notifyShort("اعلان‌های سازمانی و بررسی دوره‌ای ابلاغیه‌ها فعال شد")
            showHome("notices")
        }
    }

    private fun disableNoticeNotifications() {
        store.putString("notify_notices_enabled", "no")
        NoticePollingScheduler.cancel(this)
        notifyShort("اعلان‌های پس‌زمینه ابلاغیه غیرفعال شد؛ صندوق پیام همچنان در برنامه در دسترس است")
        showHome("notices")
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
                val bitmap = evidenceVault.imagePreview(evidence)
                    ?: throw IllegalStateException("پیش‌نمایش عکس ایجاد نشد")
                val preview = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setPadding(dp(12),dp(12),dp(12),dp(12))
                }
                AlertDialog.Builder(this).setTitle("پیش‌نمایش مستند")
                    .setView(preview).setPositiveButton("بستن", null).create().also { dialog ->
                        dialog.setOnDismissListener { preview.setImageDrawable(null); if (!bitmap.isRecycled) bitmap.recycle() }
                        dialog.show()
                    }
            } else if (evidence.mime == "application/pdf") {
                val dir = File(cacheDir, "evidence_archive").apply { mkdirs() }
                val file = File(dir, "evidence-${UUID.randomUUID()}.pdf")
                file.writeBytes(evidence.bytes)
                val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                else notifyShort("برای مشاهده PDF، یک نمایشگر PDF روی گوشی نصب کنید")
            } else {
                AlertDialog.Builder(this).setTitle("مستند")
                    .setMessage(evidenceVault.metadata(reference))
                    .setPositiveButton("تأیید",null).show()
            }
        } catch (e: Exception) {
            notifyShort("مستند قابل خواندن نیست؛ دوباره پیوست کنید")
        }
    }

    private fun missionItem(mission: JSONObject, destination: LinearLayout = content) {
        val key = missionKey(mission)
        val name = mission.optString("orgName", "دستگاه اجرایی").ifBlank { "دستگاه اجرایی" }
        val date = faDigits(mission.optString("date", "").trim())
        val available = mission.optBoolean("can_execute", false)
        val isDraft = key.isNotBlank() && missionDraft(mission)
        val state = when {
            isDraft -> "پیش‌نویس"
            available -> "قابل انجام"
            else -> "زمان‌بندی‌شده"
        }
        val stateBg = when {
            isDraft -> Color.rgb(242, 237, 249)
            available -> Color.rgb(229, 246, 237)
            else -> Color.rgb(237, 241, 246)
        }
        val stateFg = when {
            isDraft -> Color.rgb(112, 83, 154)
            available -> Color.rgb(26, 126, 78)
            else -> muted
        }
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(Color.WHITE)
            isClickable = true; isFocusable = true
            setOnClickListener { if (!busy) showMission(mission) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val symbol = iconBadge(R.drawable.ic_building, 23)
        root.addView(symbol, LinearLayout.LayoutParams(dp(46), dp(46)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        info.addView(text(name, 13, true, navy).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        info.addView(text("کد مأموریت: ${faDigits(key.take(12))}", 9, false, muted).apply { setPadding(0, dp(3), 0, 0) })
        info.addView(text(date.ifBlank { "تاریخ ابلاغ ثبت نشده" }, 10, false, muted).apply { setPadding(0, dp(2), 0, 0) })
        if (isDraft) {
            val (answered, total) = answeredQuestions(mission)
            info.addView(text("${faDigits(answered.toString())} از ${faDigits(total.toString())} پاسخ", 10, true, teal).apply {
                setPadding(0, dp(4), 0, dp(3))
            })
            if (total > 0) {
                val progress = LinearProgressIndicator(this).apply {
                    isIndeterminate = false
                    max = total
                    setProgressCompat(answered, false)
                    trackColor = Color.rgb(235, 239, 244)
                    setIndicatorColor(gold)
                }
                info.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)))
            }
        }
        root.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(chip(state, stateBg, stateFg).apply {
            textSize = 9f; maxLines = 1; gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        })
        card.addView(root)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(9)
        destination.addView(card, lp)
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

    /**
     * Guard the drafts destination separately: a malformed local record must never
     * terminate the whole Activity. The original encrypted records are left untouched.
     */
    private fun showHome(tab: String = "home") {
        try {
            renderHome(tab)
        } catch (error: Exception) {
            if (tab != "drafts") throw error
            AppDiagnostics.record(this, "open_drafts", error)
            showDraftsRecoveryScreen(error)
        }
    }

    private fun showDraftsRecoveryScreen(error: Exception) {
        activeTab = "drafts"
        selectedMission = null
        openDraft = null
        try {
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(22), dp(28), dp(22), dp(24))
                setBackgroundColor(pageBg)
            }
            root.addView(text("پیش‌نویس‌ها موقتاً باز نشدند", 20, true, navy))
            root.addView(text(
                "اطلاعات ذخیره‌شده حذف نشده است. برنامه برای جلوگیری از آسیب به پیش‌نویس‌ها، نمایش این صفحه را متوقف کرد.",
                14, false, muted
            ).apply { setPadding(0, dp(12), 0, dp(18)) })
            root.addView(text(
                "کد خطا: ${error.javaClass.simpleName}",
                12, true, Color.rgb(153, 75, 35)
            ).apply { setPadding(0, 0, 0, dp(16)) })
            val retry = makeButton("تلاش دوباره", true) { showHome("drafts") }
            root.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
                bottomMargin = dp(10)
            })
            val share = makeButton("ارسال گزارش فنی خطا", false) {
                val report = AppDiagnostics.read(this)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "گزارش خطای پیش‌نویس‌های سامانه بازرسی")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                runCatching { startActivity(Intent.createChooser(send, "ارسال گزارش خطا")) }
                    .onFailure { notifyShort("امکان بازکردن برنامه اشتراک‌گذاری وجود ندارد") }
            }
            root.addView(share, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
            root.addView(text(
                "لطفاً داده‌های برنامه را پاک نکنید و برنامه را حذف نکنید.",
                12, true, Color.rgb(153, 75, 35)
            ).apply { setPadding(0, dp(20), 0, 0) })
            setContentView(root)
        } catch (_: Exception) {
            notifyShort("پیش‌نویس‌ها باز نشدند؛ اطلاعات ذخیره‌شده پاک نشده‌اند. گزارش خطا در برنامه ثبت شد.")
        }
    }

    private fun renderHome(tab: String) {
        selectedMission = null
        openDraft = null
        checklistOverviewVisible = false
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
                screen("میز کار بازرس", "خلاصه مأموریت‌ها و وضعیت فعالیت شما", withNav = true)
                hero(profileName)

                val indicators = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val connection = chip(
                    if (sessionToken != null) "اتصال به سامانه برقرار است" else "اتصال به سامانه برقرار نیست",
                    if (sessionToken != null) Color.rgb(231, 245, 238) else Color.rgb(250, 242, 226),
                    if (sessionToken != null) Color.rgb(35, 124, 83) else Color.rgb(154, 104, 27)
                ).apply {
                    gravity = Gravity.CENTER
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                indicators.addView(connection, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) })
                val sync = chip(lastSyncShort(), Color.rgb(235, 240, 246), Color.rgb(75, 94, 116)).apply {
                    gravity = Gravity.CENTER
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { if (deviceToken == null) notifyShort("نشست دستگاه فعال نیست") else connectAndSync() }
                }
                indicators.addView(sync, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(5) })
                content.addView(indicators)
                if (sessionToken == null && all.isNotEmpty()) {
                    gap(7)
                    val caution = text("اطلاعات زیر از حافظه امن دستگاه نمایش داده شده است؛ برای دریافت آخرین تغییرات، همگام‌سازی کنید.", 11, false, Color.rgb(131, 90, 35)).apply {
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        background = rounded(Color.rgb(255, 247, 230), 12)
                    }
                    content.addView(caution)
                }
                gap(13)
                sectionTitle("خلاصه عملکرد", "نمای کلی") { showHome("missions") }
                val authorizedCount = serverSnapshot.optJSONArray("authorized_organizations")?.length() ?: 0
                statsRow(
                    statCard(faDigits(all.size.toString()), "مأموریت‌ها", R.drawable.ic_clipboard, Color.rgb(234, 241, 249), navy),
                    statCard(faDigits(drafted.size.toString()), "پیش‌نویس‌ها", R.drawable.ic_notes, Color.rgb(242, 237, 249), Color.rgb(112, 83, 154))
                )
                statsRow(
                    statCard(faDigits(reportCount().toString()), "گزارش‌های ثبت‌شده", R.drawable.ic_chart, Color.rgb(232, 245, 238), Color.rgb(38, 126, 86)),
                    statCard(faDigits(authorizedCount.toString()), "ادارات مجاز", R.drawable.ic_building, Color.rgb(250, 243, 229), Color.rgb(163, 112, 42))
                )
                gap(2)
                goldButton("شروع بازرسی جدید  +") {
                    val nextMission = available.firstOrNull()
                    if (nextMission != null) showMission(nextMission)
                    else showHome("missions")
                }
                val deficiencyCount = runCatching { deficiencies.entries().count { !it.optBoolean("integrity_error") } }.getOrDefault(0)
                secondaryButton("مدیریت نواقص و پیگیری اصلاحات · ${faDigits(deficiencyCount.toString())}") {
                    showHome("deficiencies")
                }
                val localEvidenceCount = runCatching { EvidenceArchive(store, evidenceVault, deficiencies).localEntries(missions).size }.getOrDefault(0)
                secondaryButton("مرکز مستندات و تصاویر · ${faDigits(localEvidenceCount.toString())}") {
                    showHome("evidence")
                }
                sectionTitle("مأموریت‌های اخیر", "مشاهده همه") { showHome("missions") }
                if (all.isEmpty()) {
                    emptyState("مأموریتی دریافت نشده", "اگر مأموریتی به شما ابلاغ شده، اتصال اینترنت را بررسی و همگام‌سازی کنید.")
                } else {
                    all.take(2).forEach { dashboardMissionItem(it) }
                }
                if (queued.isNotEmpty()) {
                    gap(4)
                    secondaryButton("ارسال گزارش‌های امضاشده (${queued.size})") { sendAllQueued() }
                }
                if (serverSnapshot.optJSONObject("supervisor")?.optBoolean("can_review", false) == true) {
                    val pendingCount = serverSnapshot.optJSONObject("supervisor")?.optJSONArray("items")?.length() ?: 0
                    secondaryButton("کارتابل سربازرس · $pendingCount گزارش") { showHome("supervisor") }
                }
                gap(5)
                label("پیش‌نویس‌ها به‌صورت امن روی دستگاه ذخیره می‌شوند؛ ارسال نهایی گزارش نیازمند اتصال اینترنت است.", 10, false, muted)
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
            "analytics" -> showAnalytics()
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
                val bannerBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)) }
                bannerBody.addView(text(modeLabel, 14, true, navy))
                bannerBody.addView(text("${orgs.length()} اداره در آخرین همگام‌سازی برای شما مجاز بوده است.", 12, false, muted))
                banner.addView(bannerBody); content.addView(banner)
                gap(10)
                val search = field("جست‌وجوی نام اداره، کد یا گروه")
                search.setSingleLine(true)
                search.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0)
                search.compoundDrawablePadding = dp(8)
                gap(8)
                val count = text("", 12, false, muted)
                content.addView(count)
                val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                content.addView(results)
                fun renderDepartments() {
                    val query = search.text?.toString()?.trim().orEmpty()
                    val filtered = (0 until orgs.length()).mapNotNull { orgs.optJSONObject(it) }.filter { org ->
                        query.isBlank() || listOf(org.optString("name"), org.optString("code"), org.optString("comparisonGroup"), org.optString("category"))
                            .any { it.contains(query, ignoreCase = true) }
                    }
                    count.text = "${filtered.size} اداره نمایش داده می‌شود"
                    results.removeAllViews()
                    if (filtered.isEmpty()) {
                        val empty = text(if (orgs.length() == 0) "هنوز اداره‌ای برای شما مجاز نشده است." else "اداره‌ای با این جست‌وجو پیدا نشد.", 14, false, muted).apply {
                            gravity = Gravity.CENTER; setPadding(dp(14), dp(26), dp(14), dp(26)); background = rounded(Color.WHITE, 14)
                        }
                        results.addView(empty)
                    } else filtered.forEach { departmentItem(it, results) }
                }
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderDepartments()
                    override fun afterTextChanged(s: Editable?) {}
                })
                renderDepartments()
                gap(8)
                label("تغییر یا افزودن دسترسی فقط توسط مدیر سامانه انجام می‌شود. برای دریافت تغییرات جدید، همگام‌سازی کنید.", 11, false, muted)
                secondaryButton("همگام‌سازی دسترسی‌ها") {
                    if (deviceToken == null) notifyShort("ابتدا وارد حساب بازرس شوید") else connectAndSync()
                }
            }
            "drafts" -> {
                screen("پیش‌نویس‌های من", "ذخیره امن، ادامه بازرسی و پیگیری ارسال", withNav = true)
                val failedCount = queued.count { it.optString("state") == "failed" || it.optBoolean("integrity_error") }
                val uploadingCount = queued.count { it.optString("state") == "uploading" }
                val activeDraftCount = drafted.count { mission ->
                    val key = missionKey(mission)
                    key.isNotBlank() && outbox.get(key) == null
                }
                val readyCount = queued.count { !it.optBoolean("integrity_error") }

                val overview = MaterialCardView(this).apply {
                    radius = dp(18).toFloat()
                    strokeWidth = dp(1)
                    strokeColor = line
                    cardElevation = dp(1).toFloat()
                    setCardBackgroundColor(Color.WHITE)
                }
                val overviewBody = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(14), dp(15), dp(14))
                }
                overviewBody.addView(text("وضعیت کارهای ذخیره‌شده", 14, true, navy))
                val overviewRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    setPadding(0, dp(10), 0, 0)
                }
                fun miniMetric(value: Int, title: String, tone: Int, bg: Int) {
                    val cell = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        background = rounded(bg, 12)
                        setPadding(dp(5), dp(9), dp(5), dp(9))
                    }
                    cell.addView(text(faDigits(value.toString()), 20, true, tone).apply { gravity = Gravity.CENTER })
                    cell.addView(text(title, 9, true, muted).apply { gravity = Gravity.CENTER })
                    overviewRow.addView(cell, LinearLayout.LayoutParams(0, dp(61), 1f).apply {
                        marginStart = dp(4); marginEnd = dp(4)
                    })
                }
                miniMetric(activeDraftCount, "در حال تکمیل", navy, Color.rgb(239, 243, 248))
                miniMetric(readyCount, "در صف ارسال", Color.rgb(29, 125, 82), Color.rgb(231, 246, 238))
                miniMetric(failedCount, "نیازمند پیگیری", Color.rgb(169, 91, 47), Color.rgb(252, 241, 231))
                overviewBody.addView(overviewRow)
                overview.addView(overviewBody)
                content.addView(overview)
                gap(10)

                val sendReady = makeButton("ارسال گروهی گزارش‌های آماده (${queued.size})", true) {
                    if (queued.isEmpty()) notifyShort("گزارش امضاشده‌ای در صف ارسال وجود ندارد") else sendAllQueued()
                }
                sendReady.setIconResource(R.drawable.ic_send_all)
                sendReady.iconTint = ColorStateList.valueOf(Color.WHITE)
                sendReady.isEnabled = queued.any { !it.optBoolean("integrity_error") }
                content.addView(sendReady)
                secondaryButton("مشاهده جزئیات صف ارسال و تلاش مجدد") { showOutbox() }
                gap(9)

                val draftSearch = field("جست‌وجوی نام اداره یا مأموریت")
                draftSearch.setSingleLine(true)
                draftSearch.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0)
                draftSearch.compoundDrawablePadding = dp(8)
                val filterRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val resultHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val resultCount = text("", 11, false, muted)
                var selectedDraftFilter = "all"
                val filterButtons = linkedMapOf<String, MaterialButton>()
                var renderDraftCards: () -> Unit = {}
                listOf("all" to "همه", "progress" to "در حال تکمیل", "ready" to "آماده ارسال", "attention" to "نیازمند پیگیری")
                    .forEachIndexed { index, pair ->
                        val (filterId, caption) = pair
                        val filterButton = makeButton(caption, false) {
                            selectedDraftFilter = filterId
                            renderDraftCards()
                        }.apply {
                            textSize = 9f
                            minHeight = dp(39)
                            setPadding(dp(2), 0, dp(2), 0)
                        }
                        filterButtons[filterId] = filterButton
                        filterRow.addView(filterButton, LinearLayout.LayoutParams(0, dp(41), 1f).apply {
                            marginStart = if (index == 0) 0 else dp(4)
                        })
                    }
                // field() has already attached its TextInputLayout wrapper to content.
                gap(8)
                content.addView(filterRow)
                gap(8)
                content.addView(resultCount)
                gap(7)
                content.addView(resultHost)

                renderDraftCards = {
                    val query = draftSearch.text?.toString()?.trim().orEmpty()
                    val models = drafted.mapNotNull { mission ->
                        try {
                            val key = missionKey(mission)
                            if (key.isBlank()) return@mapNotNull null
                            val draft = InspectionDraft(key, store)
                            val queuedRecord = outbox.get(key)
                            val (answered, total) = answeredQuestions(mission)
                            val unscored = if (draft.integrityError) emptyList() else runCatching { draft.unscoredIds(mission) }.getOrDefault(emptyList())
                            val issues = if (draft.integrityError) listOf("خطای بازیابی امن") else runCatching { draft.validate(mission) }.getOrDefault(listOf("ساختار پیش‌نویس نیازمند بررسی است"))
                            val failed = queuedRecord?.optString("state") == "failed" || queuedRecord?.optBoolean("integrity_error") == true
                            val queuedNow = queuedRecord != null && !queuedRecord.optBoolean("integrity_error")
                            val category = when {
                                draft.integrityError || failed || issues.isNotEmpty() -> "attention"
                                queuedNow -> "ready"
                                else -> "progress"
                            }
                            val status = when (category) {
                                "attention" -> if (draft.integrityError || queuedRecord?.optBoolean("integrity_error") == true) "خطای بازیابی · فقط‌خواندنی" else "نیازمند تکمیل یا اصلاح"
                                "ready" -> "امضاشده · آماده ارسال"
                                else -> "در حال تکمیل"
                            }
                            Triple(mission, JSONObject()
                                .put("key", key)
                                .put("answered", answered)
                                .put("total", total)
                                .put("unscored", unscored.size)
                                .put("issues", issues.size)
                                .put("category", category)
                                .put("status", status)
                                .put("queued", queuedNow)
                                .put("failed", failed)
                                .put("integrity", draft.integrityError || queuedRecord?.optBoolean("integrity_error") == true)
                                .put("updated", draft.updatedAt)
                                .put("attachments", EvidenceRefs.count(draft.attachments)), category)
                        } catch (error: Exception) {
                            AppDiagnostics.record(this, "draft_card_model", error)
                            null
                        }
                    }.filter { (mission, meta, _) ->
                        val matchesText = query.isBlank() || mission.optString("orgName").contains(query, true) ||
                            mission.optString("date").contains(query, true) || meta.optString("key").contains(query, true)
                        val matchesFilter = selectedDraftFilter == "all" || meta.optString("category") == selectedDraftFilter
                        matchesText && matchesFilter
                    }
                    resultCount.text = "${faDigits(models.size.toString())} مورد از ${faDigits(drafted.size.toString())} پیش‌نویس"
                    resultHost.removeAllViews()
                    filterButtons.forEach { (id, btn) ->
                        val selected = id == selectedDraftFilter
                        btn.backgroundTintList = ColorStateList.valueOf(if (selected) navy else Color.WHITE)
                        btn.setTextColor(if (selected) Color.WHITE else muted)
                        btn.strokeColor = ColorStateList.valueOf(if (selected) gold else line)
                    }
                    if (models.isEmpty()) {
                        val emptyTitle = when {
                            drafted.isEmpty() -> "هنوز پیش‌نویسی ندارید"
                            query.isNotBlank() -> "موردی با این جست‌وجو پیدا نشد"
                            else -> "در این دسته موردی وجود ندارد"
                        }
                        val emptyDetail = if (drafted.isEmpty())
                            "پاسخ‌های فرم بازرسی پس از ثبت، به‌صورت خودکار و رمزنگاری‌شده ذخیره می‌شوند."
                        else "فیلتر دیگری را انتخاب کنید یا عبارت جست‌وجو را تغییر دهید."
                        val empty = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            background = rounded(Color.WHITE, 16)
                            setPadding(dp(18), dp(24), dp(18), dp(24))
                        }
                        empty.addView(icon(R.drawable.ic_notes, muted, 32))
                        empty.addView(text(emptyTitle, 15, true, navy).apply { gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(5)) })
                        empty.addView(text(emptyDetail, 11, false, muted).apply { gravity = Gravity.CENTER })
                        resultHost.addView(empty)
                    } else models.forEach { (mission, meta, _) ->
                        val card = MaterialCardView(this).apply {
                            radius = dp(17).toFloat()
                            strokeWidth = dp(1)
                            strokeColor = line
                            cardElevation = dp(1).toFloat()
                            setCardBackgroundColor(Color.WHITE)
                        }
                        val body = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(13), dp(13), dp(13), dp(13))
                        }
                        val heading = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutDirection = View.LAYOUT_DIRECTION_RTL
                        }
                        heading.addView(iconBadge(R.drawable.ic_building, 22), LinearLayout.LayoutParams(dp(43), dp(43)))
                        val identity = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(9), 0, dp(8), 0)
                        }
                        identity.addView(text(mission.optString("orgName", "دستگاه اجرایی"), 13, true, navy).apply {
                            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        identity.addView(text("کد: ${faDigits(meta.optString("key").take(12))}", 9, false, muted))
                        identity.addView(text(faDigits(mission.optString("date", "تاریخ ثبت نشده")), 9, false, muted))
                        heading.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        val statusColor = when (meta.optString("category")) {
                            "ready" -> Color.rgb(27, 130, 83)
                            "attention" -> Color.rgb(164, 91, 43)
                            else -> navy
                        }
                        val statusBg = when (meta.optString("category")) {
                            "ready" -> Color.rgb(229, 246, 237)
                            "attention" -> Color.rgb(252, 241, 231)
                            else -> Color.rgb(238, 242, 248)
                        }
                        heading.addView(chip(meta.optString("status"), statusBg, statusColor).apply {
                            textSize = 8f; maxLines = 2; gravity = Gravity.CENTER
                            setPadding(dp(7), dp(6), dp(7), dp(6))
                        })
                        body.addView(heading)

                        val answered = meta.optInt("answered")
                        val total = meta.optInt("total")
                        val percent = if (total > 0) ((answered * 100) / total).coerceIn(0, 100) else 0
                        val progressRow = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            layoutDirection = View.LAYOUT_DIRECTION_RTL
                            setPadding(0, dp(12), 0, dp(4))
                        }
                        progressRow.addView(text("پیشرفت بازرسی", 10, true, muted), LinearLayout.LayoutParams(0, dp(25), 1f))
                        progressRow.addView(text("${faDigits(percent.toString())}٪", 11, true, navy))
                        body.addView(progressRow)
                        val progress = LinearProgressIndicator(this).apply {
                            isIndeterminate = false
                            max = 100
                            setProgressCompat(percent, false)
                            trackColor = Color.rgb(235, 239, 244)
                            setIndicatorColor(if (percent >= 100) Color.rgb(34, 151, 97) else gold)
                        }
                        body.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
                        val detail = text(
                            "${faDigits(answered.toString())} از ${faDigits(total.toString())} شاخص · ${faDigits(meta.optInt("attachments").toString())} مستند · ذخیره ${formatLocalTimestamp(meta.optString("updated"))}",
                            9, false, muted
                        ).apply { setPadding(0, dp(7), 0, dp(8)); maxLines = 2 }
                        body.addView(detail)
                        if (meta.optInt("unscored") > 0) body.addView(text(
                            "${faDigits(meta.optInt("unscored").toString())} شاخص بدون امتیاز؛ در مرحله مرور به شما اعلام می‌شود.",
                            10, false, Color.rgb(153, 98, 34)
                        ).apply { setPadding(0, 0, 0, dp(6)) })
                        if (meta.optBoolean("failed")) body.addView(text(
                            "آخرین تلاش ارسال ناموفق بوده است؛ اطلاعات و امضا حفظ شده‌اند.", 10, true, Color.rgb(164, 76, 48)
                        ).apply { setPadding(0, 0, 0, dp(7)) })

                        val actions = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutDirection = View.LAYOUT_DIRECTION_RTL
                        }
                        val primaryCaption = when {
                            meta.optBoolean("integrity") -> "جزئیات خطا"
                            meta.optBoolean("queued") -> "مشاهده صف ارسال"
                            else -> "ادامه بازرسی"
                        }
                        val primary = makeButton(primaryCaption, true) {
                            when {
                                meta.optBoolean("integrity") -> {
                                    val diagnostic = AppDiagnostics.read(this@MainActivity)
                                    AlertDialog.Builder(this@MainActivity).setTitle("پیش‌نویس فقط‌خواندنی")
                                        .setMessage("برای حفاظت از اطلاعات رمزنگاری‌شده، ویرایش متوقف شده است.\n\n$diagnostic")
                                        .setPositiveButton("متوجه شدم", null).show()
                                }
                                meta.optBoolean("queued") -> showOutbox()
                                else -> showMission(mission)
                            }
                        }.apply { textSize = 10f; minHeight = dp(43) }
                        actions.addView(primary, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(3) })
                        if (meta.optBoolean("queued") && !meta.optBoolean("integrity")) {
                            val retry = makeButton("تلاش ارسال", false) { showOutbox() }.apply {
                                textSize = 10f; minHeight = dp(43)
                            }
                            actions.addView(retry, LinearLayout.LayoutParams(0, dp(44), 0.72f).apply { marginStart = dp(3) })
                        }
                        body.addView(actions)
                        card.addView(body)
                        resultHost.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = dp(10)
                        })
                    }
                }
                draftSearch.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderDraftCards() }
                    override fun afterTextChanged(s: Editable?) {}
                })
                renderDraftCards()
                gap(8)
                label("ذخیره خودکار روی دستگاه فعال است. گزارش تا زمان دریافت رسید معتبر سرور، ارسال‌شده محسوب نمی‌شود.", 10, false, muted)
            }
            "deficiencies" -> showDeficiencies()
            "notices" -> showNotices()
            "evidence" -> showEvidenceLibrary()
            "account" -> {
                screen("پروفایل و تنظیمات", "مدیریت حساب و امنیت دستگاه", withNav = true)
                val profileCard = MaterialCardView(this).apply {
                    radius = dp(19).toFloat(); strokeWidth = dp(1); strokeColor = gold
                    cardElevation = dp(2).toFloat(); setCardBackgroundColor(navy)
                }
                val profileBody = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                }
                val avatar = MaterialCardView(this).apply {
                    radius = dp(34).toFloat(); strokeWidth = dp(2); strokeColor = Color.WHITE
                    cardElevation = 0f; setCardBackgroundColor(Color.rgb(246, 249, 252))
                }
                avatar.addView(icon(R.drawable.ic_person, navy, 38), FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
                profileBody.addView(avatar, LinearLayout.LayoutParams(dp(68), dp(68)).apply { bottomMargin = dp(10) })
                profileBody.addView(text(profileName, 18, true, Color.WHITE).apply { gravity = Gravity.CENTER })
                profileBody.addView(text("بازرس ادارات · فرمانداری شهرستان جوانرود", 11, false, Color.rgb(218, 229, 240)).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(12))
                })
                val editProfile = makeButton("مدیریت پروفایل", false) { notifyShort("اطلاعات هویتی از سامانه مرکزی دریافت می‌شود") }.apply {
                    backgroundTintList = ColorStateList.valueOf(gold)
                    setTextColor(navy); strokeColor = ColorStateList.valueOf(gold)
                    minHeight = dp(40); textSize = 12f
                }
                profileBody.addView(editProfile, LinearLayout.LayoutParams(dp(170), dp(42)))
                profileCard.addView(profileBody)
                content.addView(profileCard)
                gap(15)
                sectionTitle("تنظیمات و امنیت")
                fun settingRow(title: String, detail: String, drawable: Int, action: () -> Unit) {
                    val rowCard = MaterialCardView(this).apply {
                        radius = dp(13).toFloat(); strokeWidth = dp(1); strokeColor = line
                        cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
                        isClickable = true; isFocusable = true; setOnClickListener { action() }
                    }
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(12), dp(11), dp(12), dp(11))
                    }
                    row.addView(iconBadge(drawable, 21), LinearLayout.LayoutParams(dp(43), dp(43)))
                    val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(8), 0) }
                    copy.addView(text(title, 13, true, navy))
                    copy.addView(text(detail, 10, false, muted))
                    row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(icon(R.drawable.ic_chevron, muted, 18))
                    rowCard.addView(row)
                    content.addView(rowCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
                settingRow("اعلان‌ها و ابلاغیه‌ها", "صندوق پیام و دریافت اعلان در پس‌زمینه", R.drawable.ic_bell) { showHome("notices") }
                settingRow("امنیت و ورود", if (store.biometricEnabled()) "ورود با اثر انگشت فعال است" else "مدیریت رمز و اثر انگشت", R.drawable.ic_lock) {
                    if (deviceToken != null && !store.biometricEnabled()) enableBiometric() else showBiometricLock()
                }
                settingRow("مدیریت اثر انگشت", "فعال‌سازی یا ورود بیومتریک", R.drawable.ic_fingerprint) {
                    if (deviceToken != null && !store.biometricEnabled()) enableBiometric() else showBiometricLock()
                }
                settingRow("سوابق و رسیدها", "مشاهده گزارش‌های ثبت‌شده", R.drawable.ic_chart) { showHistory() }
                settingRow("مرکز مستندات و تصاویر", "آرشیو امن عکس‌ها و PDFهای بازرسی", R.drawable.ic_official_report) { showHome("evidence") }
                settingRow("پشتیبانی و راهنما", "اطلاعات سامانه و راه‌های ارتباطی", R.drawable.ic_person) { showNotices() }
                gap(6)
                secondaryButton("خروج از حساب کاربری") { confirmLogout() }
                label("نسخه ${BuildConfig.VERSION_NAME}  ·  اتصال امن HTTPS", 10, false, muted)
            }


            else -> showHome("home")
        }
    }

    private fun reportDateBucket(report: JSONObject, range: String): String? {
        val raw = report.optString("submitted_at").ifBlank { report.optString("date") }
            .trim().replace('/', '-').replace('T', ' ')
        val date = raw.substringBefore(' ').take(10)
        if (date.length < 7) return null
        return when (range) {
            "weekly" -> date.take(10).takeIf { it.length >= 10 }
            "yearly" -> date.take(4).takeIf { it.length == 4 }
            else -> date.take(7).takeIf { it.length == 7 }
        }
    }

    private fun reportScore(report: JSONObject): Double? {
        val keys = listOf("final_score", "total_score", "score", "percentage")
        for (key in keys) {
            if (!report.has(key) || report.isNull(key)) continue
            val value = when (val raw = report.opt(key)) {
                is Number -> raw.toDouble()
                is String -> ScoreInput.parse(raw)
                else -> null
            }
            if (value != null && value.isFinite() && value in 0.0..100.0) return value
        }
        return null
    }

    private fun analyticsDonut(values: List<Int>, colors: List<Int>): View = object : View(this) {
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val stroke = dp(17).toFloat()
            val radius = (minOf(width, height) / 2f - stroke).coerceAtLeast(dp(24).toFloat())
            val bounds = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            arc.strokeWidth = stroke
            arc.color = Color.rgb(235, 239, 244)
            canvas.drawArc(bounds, 0f, 360f, false, arc)
            val total = values.sum().coerceAtLeast(0)
            if (total > 0) {
                var startAngle = -90f
                values.forEachIndexed { index, value ->
                    if (value > 0) {
                        val sweep = 360f * value.toFloat() / total.toFloat()
                        arc.color = colors.getOrElse(index) { navy }
                        canvas.drawArc(bounds, startAngle + 1.5f, (sweep - 3f).coerceAtLeast(0.5f), false, arc)
                        startAngle += sweep
                    }
                }
            }
            center.textSize = dp(22).toFloat()
            canvas.drawText(faDigits(total.toString()), cx, cy + dp(1), center)
            caption.textSize = dp(10).toFloat()
            canvas.drawText("مأموریت", cx, cy + dp(19), caption)
        }
    }.apply { contentDescription = "نمودار حلقه‌ای وضعیت مأموریت‌ها" }

    private fun analyticsLineChart(points: List<Pair<String, Int>>): View = object : View(this) {
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = line; strokeWidth = dp(1).toFloat() }
        private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(39, 126, 199); strokeWidth = dp(3).toFloat(); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; style = Paint.Style.FILL }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted; textSize = dp(9).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = navy; textSize = dp(10).toFloat(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val left = dp(22).toFloat()
            val right = (width - dp(12)).toFloat()
            val top = dp(18).toFloat()
            val bottom = (height - dp(34)).toFloat()
            val plotHeight = (bottom - top).coerceAtLeast(dp(30).toFloat())
            for (i in 0..3) {
                val y = top + plotHeight * i / 3f
                canvas.drawLine(left, y, right, y, grid)
            }
            if (points.isEmpty()) return
            val maxValue = (points.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
            val path = Path()
            val coords = points.mapIndexed { index, entry ->
                val x = if (points.size == 1) (left + right) / 2f else left + (right - left) * index / (points.size - 1).toFloat()
                val y = bottom - plotHeight * entry.second / maxValue.toFloat()
                x to y
            }
            coords.forEachIndexed { index, pair ->
                if (index == 0) path.moveTo(pair.first, pair.second) else path.lineTo(pair.first, pair.second)
            }
            canvas.drawPath(path, curve)
            coords.forEachIndexed { index, pair ->
                canvas.drawCircle(pair.first, pair.second, dp(4).toFloat(), dot)
                canvas.drawText(faDigits(points[index].second.toString()), pair.first, (pair.second - dp(9)).coerceAtLeast(top + dp(10)), valuePaint)
                canvas.drawText(faDigits(points[index].first), pair.first, height - dp(10).toFloat(), labelPaint)
            }
        }
    }.apply { contentDescription = "نمودار روند گزارش‌های ثبت‌شده" }

    private fun showAnalytics() {
        activeTab = "analytics"
        selectedMission = null
        openDraft = null
        val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
        val reports = serverSnapshot.optJSONArray("reports") ?: JSONArray()
        val allMissions = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }
        val reportObjects = (0 until reports.length()).mapNotNull { reports.optJSONObject(it) }
        val available = allMissions.count { it.optBoolean("can_execute", false) }
        val drafted = allMissions.count { missionDraft(it) }
        val submitted = reportObjects.size
        val submittedKeys = reportObjects.map { it.optString("mission_key").trim() }.filter { it.isNotBlank() }.toSet()
        val hasMissionKeys = submittedKeys.isNotEmpty()
        val visited = if (hasMissionKeys) allMissions.count { missionKey(it) in submittedKeys } else null
        val notVisited = visited?.let { (allMissions.size - it).coerceAtLeast(0) }
        val scoreEntries = reportObjects.mapNotNull { report -> reportScore(report)?.let { report to it } }
        val highest = scoreEntries.maxByOrNull { it.second }
        val lowest = scoreEntries.minByOrNull { it.second }

        screen("گزارش‌ها و تحلیل‌ها", "نمای کلی عملکرد بازرسی", withNav = true)
        sectionTitle("خلاصه عملکرد", "آمار دریافتی از سرور")
        statsRow(
            statCard(faDigits(allMissions.size.toString()), "کل مأموریت‌ها", R.drawable.ic_clipboard, Color.rgb(232, 240, 249), navy),
            statCard(faDigits(submitted.toString()), "گزارش ثبت‌شده", R.drawable.ic_chart, Color.rgb(230, 246, 237), Color.rgb(27, 132, 83))
        )
        statsRow(
            statCard(faDigits(available.toString()), "در انتظار اجرا", R.drawable.ic_building, Color.rgb(250, 243, 226), Color.rgb(161, 111, 34)),
            statCard(faDigits(drafted.toString()), "پیش‌نویس محلی", R.drawable.ic_notes, Color.rgb(241, 237, 249), Color.rgb(112, 83, 154))
        )

        gap(10)
        val distributionCard = MaterialCardView(this).apply {
            radius = dp(17).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
        }
        val distributionBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        distributionBody.addView(text("وضعیت مأموریت‌ها", 14, true, navy))
        if (visited != null) {
            val visitedForChart = visited
            val draftOnly = allMissions.count { missionKey(it) !in submittedKeys && missionDraft(it) }
            val availableOnly = allMissions.count {
                missionKey(it) !in submittedKeys && !missionDraft(it) && it.optBoolean("can_execute", false)
            }
            val other = (allMissions.size - visitedForChart - draftOnly - availableOnly).coerceAtLeast(0)
            val chartValues = listOf(visitedForChart, draftOnly, availableOnly, other)
            val chartColors = listOf(Color.rgb(42, 139, 205), gold, Color.rgb(39, 154, 105), Color.rgb(160, 173, 188))
            val chartRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(0, dp(8), 0, 0)
            }
            chartRow.addView(analyticsDonut(chartValues, chartColors), LinearLayout.LayoutParams(dp(148), dp(148)))
            val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
            fun legendLine(caption: String, count: Int, color: Int) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                row.addView(View(this).apply { background = rounded(color, 20) }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(8) })
                row.addView(text(caption, 11, false, muted), LinearLayout.LayoutParams(0, dp(32), 1f))
                row.addView(text(faDigits(count.toString()), 12, true, navy))
                legend.addView(row)
            }
            legendLine("بازدید انجام‌شده", visitedForChart, chartColors[0])
            legendLine("دارای پیش‌نویس", draftOnly, chartColors[1])
            legendLine("قابل اجرا", availableOnly, chartColors[2])
            legendLine("سایر وضعیت‌ها", other, chartColors[3])
            chartRow.addView(legend, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            distributionBody.addView(chartRow)
        } else {
            distributionBody.addView(text(
                "برای تفکیک دقیق وضعیت مأموریت‌ها، شناسه mission_key باید در تمام گزارش‌های دریافتی از سرور موجود باشد. تا آن زمان نمودار توزیع نمایش داده نمی‌شود تا آمار اشتباه ارائه نشود.",
                11, false, muted
            ).apply { setPadding(0, dp(10), 0, dp(3)) })
        }
        distributionCard.addView(distributionBody)
        content.addView(distributionCard)

        if (visited != null && notVisited != null) {
            gap(10)
            val visitCard = MaterialCardView(this).apply {
                radius = dp(16).toFloat(); strokeWidth = dp(1); strokeColor = line
                cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
            }
            val visitBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)) }
            visitBody.addView(text("وضعیت بازدید ادارات", 13, true, navy))
            val visitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            visitRow.addView(statCard(faDigits(visited.toString()), "بازدید انجام‌شده", R.drawable.ic_check, Color.rgb(230, 246, 237), Color.rgb(27, 132, 83)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
            visitRow.addView(statCard(faDigits(notVisited.toString()), "بدون بازدید", R.drawable.ic_building, Color.rgb(250, 243, 226), Color.rgb(161, 111, 34)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(5) })
            visitBody.addView(visitRow)
            visitCard.addView(visitBody)
            content.addView(visitCard)
        } else if (submitted > 0) {
            gap(8)
            content.addView(text("برای تفکیک دقیق ادارات بازدیدشده و بازدیدنشده، سرور باید شناسه mission_key را در هر گزارش برگرداند.", 10, false, muted).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(Color.rgb(245, 247, 250), 10)
            })
        }

        gap(10)
        val trendCard = MaterialCardView(this).apply {
            radius = dp(17).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
        }
        val trendBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)) }
        trendBody.addView(text("روند گزارش‌های ثبت‌شده", 14, true, navy))
        var selectedRange = "monthly"
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(3))
        }
        val trendHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun trendPoints(range: String): List<Pair<String, Int>> {
            val counts = linkedMapOf<String, Int>()
            reportObjects.forEach { report ->
                val key = reportDateBucket(report, range) ?: return@forEach
                counts[key] = (counts[key] ?: 0) + 1
            }
            val limit = when (range) { "weekly" -> 7; "yearly" -> 5; else -> 6 }
            return counts.toSortedMap().entries.toList().takeLast(limit).map { entry ->
                val label = when (range) {
                    "weekly" -> entry.key.takeLast(5)
                    "yearly" -> entry.key
                    else -> entry.key.takeLast(2)
                }
                label to entry.value
            }
        }
        fun renderTrend() {
            rangeRow.removeAllViews()
            listOf("weekly" to "هفتگی", "monthly" to "ماهانه", "yearly" to "سالانه").forEach { (key, caption) ->
                val active = selectedRange == key
                val button = TextView(this).apply {
                    text = caption; textSize = 10f; gravity = Gravity.CENTER
                    setTextColor(if (active) navy else muted)
                    typeface = Typeface.create("sans-serif-medium", if (active) Typeface.BOLD else Typeface.NORMAL)
                    background = rounded(if (active) gold else Color.rgb(242, 245, 249), 10)
                    setPadding(dp(13), dp(9), dp(13), dp(9))
                    isClickable = true; isFocusable = true
                    setOnClickListener { selectedRange = key; renderTrend() }
                }
                rangeRow.addView(button, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(4); marginStart = dp(4) })
            }
            trendHost.removeAllViews()
            val points = trendPoints(selectedRange)
            if (points.isEmpty()) {
                trendHost.addView(text("برای نمایش روند، تاریخ گزارش‌های ثبت‌شده باید از سرور دریافت شود.", 11, false, muted).apply {
                    setPadding(0, dp(18), 0, dp(10))
                })
            } else {
                trendHost.addView(analyticsLineChart(points), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(178)))
            }
        }
        trendBody.addView(rangeRow)
        trendBody.addView(trendHost)
        renderTrend()
        trendCard.addView(trendBody)
        content.addView(trendCard)

        gap(10)
        val scoreCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
        }
        val scoreBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)) }
        scoreBody.addView(text("مقایسه بالاترین و پایین‌ترین امتیاز", 13, true, navy))
        if (highest != null && lowest != null) {
            fun scoreLine(title: String, pair: Pair<JSONObject, Double>, color: Int) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                row.addView(View(this).apply { background = rounded(color, 20) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(8) })
                val name = pair.first.optString("org_name").ifBlank { pair.first.optString("organization", "دستگاه اجرایی") }
                row.addView(text("$title · $name", 10, false, muted), LinearLayout.LayoutParams(0, dp(38), 1f))
                row.addView(text("${faDigits(String.format(Locale.US, "%.1f", pair.second))}٪", 13, true, navy))
                scoreBody.addView(row)
            }
            scoreLine("بالاترین", highest, Color.rgb(36, 151, 99))
            scoreLine("پایین‌ترین", lowest, Color.rgb(198, 83, 75))
        } else {
            scoreBody.addView(text("امتیاز نهایی در داده‌های فعلی سرور موجود نیست؛ پس از دریافت فیلد score یا final_score این مقایسه فعال می‌شود.", 10, false, muted).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        scoreCard.addView(scoreBody)
        content.addView(scoreCard)

        gap(12)
        goldButton("مشاهده گزارش‌های رسمی") { showOfficialReports() }
        secondaryButton("سوابق بازرسی و رسیدها") { showHistory() }
        secondaryButton("همگام‌سازی آمار با سرور") { connectAndSync() }
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

    private data class ChecklistSectionPage(
        val id: String,
        val title: String,
        val category: String,
        val questionIndices: List<Int>
    )

    /** One independent, vertically-scrollable page per checklist section (شاخص). */
    private fun showMission(mission: JSONObject, questionIndex: Int = 0) {
        activeTab = "mission"
        val key = missionKey(mission)
        if (key.isBlank()) { notifyShort("شناسه مأموریت نامعتبر است"); return }
        if (outbox.get(key) != null) {
            showOutbox()
            notifyShort("این گزارش در صف ارسال است؛ برای ویرایش، ابتدا از صف خارج کنید")
            return
        }
        selectedMission = mission
        selectedMissionKey = key
        checklistOverviewVisible = false
        val draft = if (openDraft?.key == key) openDraft!! else InspectionDraft(key, store)
        if (draft.integrityError) {
            openDraft = null
            selectedMission = null
            AlertDialog.Builder(this).setTitle("پیش‌نویس قابل بازیابی نیست")
                .setMessage("اطلاعات رمزنگاری‌شده این مأموریت موجود است اما اعتبارسنجی یا رمزگشایی آن ناموفق بود. برای جلوگیری از بازنویسی و حذف احتمالی اطلاعات، ویرایش و ارسال متوقف شد.")
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        openDraft = draft
        val enabled = mission.optBoolean("can_execute", false)
        val snapshot = mission.optJSONObject("checklistSnapshot") ?: JSONObject()
        val questions = snapshot.optJSONArray("questions") ?: JSONArray()
        val count = questions.length()
        if (count == 0) {
            screen("فرم بازرسی", mission.optString("orgName", "دستگاه اجرایی"))
            emptyState("چک‌لیست خالی است", "برای دریافت اطلاعات جدید، مأموریت‌ها را همگام‌سازی کنید.")
            return
        }

        // The same evaluator builds section navigation and the review/validation summaries.
        // Server question IDs, section IDs and scoring rules remain unchanged.
        val initialAssessment = draft.checklistOverview(mission)
        val pages = initialAssessment.sections.map { section ->
            ChecklistSectionPage(
                section.section.id,
                section.section.title,
                section.section.category,
                section.section.questionIndices
            )
        }.toMutableList()
        if (pages.isEmpty()) {
            pages.add(ChecklistSectionPage("__all__", "شاخص ارزیابی", "عمومی", (0 until count).toList()))
        }

        val requestedQuestionIndex = questionIndex.coerceIn(0, count - 1)
        val resolvedPageIndex = pages.indexOfFirst { requestedQuestionIndex in it.questionIndices }
            .takeIf { it >= 0 } ?: 0
        currentSectionIndex = resolvedPageIndex
        currentQuestionIndex = requestedQuestionIndex.takeIf { it in pages[resolvedPageIndex].questionIndices }
            ?: pages[resolvedPageIndex].questionIndices.first()
        val page = pages[currentSectionIndex]
        val pageQuestionCount = page.questionIndices.size

        screen("فرم بازرسی", mission.optString("orgName", "دستگاه اجرایی"))

        val summary = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
        }
        val summaryInside = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        summaryInside.addView(text(mission.optString("orgName", "دستگاه اجرایی"), 16, true, navy))
        summaryInside.addView(text(
            listOf(mission.optString("date"), mission.optString("schedule_label"))
                .filter { it.isNotBlank() }.joinToString("  •  "), 12, false, muted
        ))
        summary.addView(summaryInside)
        content.addView(summary)
        gap(12)

        if (!enabled) {
            val schedule = mission.optString("schedule_label", "نامشخص")
            val serverToday = mission.optString("server_today", "")
            label("وضعیت مجوز مأموریت: $schedule" +
                if (serverToday.isNotBlank()) " · تاریخ سرور: $serverToday" else "",
                12, true, Color.rgb(157, 99, 29))
            secondaryButton("به‌روزرسانی وضعیت و مجوز مأموریت") {
                if (!saveDraftSafely(draft)) return@secondaryButton
                showHome("missions")
                connectAndSync()
            }
        }

        val validAnswers = initialAssessment.answered
        val sectionAnswered = page.questionIndices.count { idx ->
            val qid = questions.optJSONObject(idx)?.optString("id").orEmpty()
            val value = ScoreInput.parse(draft.answers.optString(qid))
            value != null && value.isFinite() && value in 0.0..100.0
        }

        // Overall mission progress and current section progress are shown separately.
        val progressCard = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
        }
        val progressInside = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }
        val overallLabel = text("پیشرفت کل مأموریت · $validAnswers از $count بند", 12, true, muted)
        progressInside.addView(overallLabel)
        val overallProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = count
            progress = validAnswers
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        progressInside.addView(overallProgress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(7)
        ).apply { topMargin = dp(7); bottomMargin = dp(10) })
        val sectionLabel = text("این شاخص · $sectionAnswered از $pageQuestionCount آیتم پاسخ داده شده", 12, true, navy)
        progressInside.addView(sectionLabel)
        val qualityLine = text(
            "بدون امتیاز: ${initialAssessment.unscored}  •  نیازمند اصلاح: ${initialAssessment.attentionCount}",
            11, true, if (initialAssessment.attentionCount > 0) Color.rgb(157, 99, 29) else muted
        ).apply { setPadding(0, dp(8), 0, 0) }
        progressInside.addView(qualityLine)
        val sectionProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = pageQuestionCount
            progress = sectionAnswered
            progressTintList = ColorStateList.valueOf(Color.rgb(35, 153, 103))
            progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        progressInside.addView(sectionProgress, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(7)
        ).apply { topMargin = dp(7) })
        progressCard.addView(progressInside)
        content.addView(progressCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        secondaryButton("نمای کلی شاخص‌ها و موارد ناقص · ${pages.size} شاخص") {
            if (!saveDraftSafely(draft)) return@secondaryButton
            showChecklistOverview(mission, draft, pages)
        }

        val sectionHero = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(navy)
        }
        val heroRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(17), dp(16), dp(17))
        }
        val heroIcon = iconBadge(R.drawable.ic_building, 27)
        heroRow.addView(heroIcon, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(12) })
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        heroText.addView(text("شاخص ${currentSectionIndex + 1} از ${pages.size} · ${page.category}", 11, true, gold))
        heroText.addView(text(page.title, 18, true, Color.WHITE).apply {
            setPadding(0, dp(5), 0, dp(3))
        })
        heroText.addView(text("${pageQuestionCount} آیتم ارزیابی در این شاخص", 11, false, Color.rgb(220, 228, 237)))
        heroRow.addView(heroText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sectionNumber = TextView(this).apply {
            text = String.format(Locale.US, "%02d", currentSectionIndex + 1)
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(navy)
            gravity = Gravity.CENTER
            background = rounded(gold, 12)
            setPadding(dp(11), dp(8), dp(11), dp(8))
        }
        heroRow.addView(sectionNumber)
        sectionHero.addView(heroRow)
        content.addView(sectionHero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(15) })

        fun refreshProgress() {
            val latest = draft.checklistOverview(mission)
            val filledAll = latest.answered
            val filledSection = page.questionIndices.count { idx ->
                val qid = questions.optJSONObject(idx)?.optString("id").orEmpty()
                val value = ScoreInput.parse(draft.answers.optString(qid))
                value != null && value.isFinite() && value in 0.0..100.0
            }
            overallProgress.progress = filledAll
            overallLabel.text = "پیشرفت کل مأموریت · $filledAll از $count بند"
            sectionProgress.progress = filledSection
            sectionLabel.text = "این شاخص · $filledSection از $pageQuestionCount آیتم پاسخ داده شده"
            qualityLine.text = "بدون امتیاز: ${latest.unscored}  •  نیازمند اصلاح: ${latest.attentionCount}"
            qualityLine.setTextColor(if (latest.attentionCount > 0 || latest.unscored > 0) Color.rgb(157, 99, 29) else muted)
        }

        fun addInput(
            parent: LinearLayout,
            hint: String,
            initialValue: String,
            numeric: Boolean,
            multiline: Boolean,
            onChanged: (String) -> Unit
        ): EditText {
            val wrapper = TextInputLayout(this).apply {
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                boxBackgroundColor = Color.WHITE
                boxStrokeColor = line
                setBoxCornerRadii(dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat(), dp(12).toFloat())
                this.hint = hint
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val input = TextInputEditText(this).apply {
                setText(initialValue)
                textSize = 15f
                setTextColor(ink)
                setHintTextColor(muted)
                gravity = if (multiline) Gravity.TOP or Gravity.RIGHT else Gravity.RIGHT or Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    else -> InputType.TYPE_CLASS_TEXT
                }
                if (multiline) { minLines = 2; maxLines = 4 }
            }
            wrapper.addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            parent.addView(wrapper, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) })
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onChanged(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            return input
        }

        page.questionIndices.forEachIndexed { localIndex, globalIndex ->
            val q = questions.optJSONObject(globalIndex) ?: return@forEachIndexed
            val id = q.optString("id").trim()
            if (id.isBlank()) return@forEachIndexed
            val questionText = q.optString("text", q.optString("question", q.optString("title", q.optString("label", id))))
            val panel = MaterialCardView(this).apply {
                radius = dp(17).toFloat()
                strokeWidth = dp(1)
                strokeColor = line
                cardElevation = dp(1).toFloat()
                setCardBackgroundColor(Color.WHITE)
            }
            val inside = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(14), dp(13), dp(14), dp(14))
            }
            val questionHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            val number = TextView(this).apply {
                text = (localIndex + 1).toString()
                textSize = 14f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(navy)
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(248, 239, 215), 10)
                setPadding(dp(11), dp(7), dp(11), dp(7))
            }
            questionHeader.addView(number)
            questionHeader.addView(text("کد $id", 11, true, muted).apply {
                setPadding(dp(9), 0, dp(9), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val existingScore = draft.answers.optString(id)
            val state = TextView(this).apply {
                textSize = 10f
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }
            fun refreshQuestionState() {
                val raw = draft.answers.optString(id)
                val parsed = ScoreInput.parse(raw)
                val noteMissing = parsed != null && parsed < 60.0 && draft.notes.optString(id).isBlank()
                val evidenceMissing = parsed != null && (parsed < 30.0 || q.optBoolean("critical")) &&
                    EvidenceRefs.references(draft.attachments, id).isEmpty()
                val status = when {
                    raw.isBlank() -> Triple("بدون امتیاز", Color.rgb(150, 96, 31), Color.rgb(250, 243, 226))
                    parsed == null || parsed !in 0.0..100.0 -> Triple("امتیاز نامعتبر", Color.rgb(170, 52, 52), Color.rgb(252, 235, 235))
                    noteMissing && evidenceMissing -> Triple("نیازمند توضیح و مستند", Color.rgb(150, 96, 31), Color.rgb(250, 243, 226))
                    noteMissing -> Triple("نیازمند توضیح", Color.rgb(150, 96, 31), Color.rgb(250, 243, 226))
                    evidenceMissing -> Triple("نیازمند مستند", Color.rgb(150, 96, 31), Color.rgb(250, 243, 226))
                    else -> Triple("تکمیل‌شده", Color.rgb(25, 130, 82), Color.rgb(229, 246, 237))
                }
                state.text = status.first
                state.setTextColor(status.second)
                state.background = rounded(status.third, 30)
            }
            refreshQuestionState()
            questionHeader.addView(state)
            inside.addView(questionHeader)
            inside.addView(text(questionText, 15, true, navy).apply {
                setPadding(0, dp(11), 0, dp(12))
            })
            if (q.optBoolean("critical")) {
                inside.addView(chip("شاخص بحرانی · ثبت مستند الزامی است", Color.rgb(252, 235, 235), Color.rgb(155, 48, 48)).apply {
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                })
                inside.addView(View(this).apply { setBackgroundColor(Color.TRANSPARENT) },
                    LinearLayout.LayoutParams(1, dp(6)))
            }
            inside.addView(text("امتیاز ارزیابی · از ۰ تا ۱۰۰", 12, true, muted).apply {
                setPadding(0, 0, 0, dp(5))
            })
            val scoreInput = addInput(inside, "امتیاز این بند", existingScore, true, false) { value ->
                val normalizedScore = ScoreInput.normalize(value)
                draft.answers.put(id, normalizedScore)
                draft.clearAcknowledgement()
                saveDraftSafely(draft)
                refreshQuestionState()
                refreshProgress()
            }
            inside.addView(text("انتخاب سریع سطح ارزیابی", 11, true, muted).apply {
                setPadding(0, dp(1), 0, dp(5))
            })
            val quickScores = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER
            }
            AssessmentScale.quickLevels.forEach { option ->
                val quick = TextView(this).apply {
                    text = "${option.localizedValue}\n${option.label}"
                    textSize = 9f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    setTextColor(navy)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    maxLines = 2
                    background = rounded(Color.rgb(239, 243, 248), 9)
                    setPadding(dp(2), dp(5), dp(2), dp(5))
                    isClickable = true
                    isFocusable = true
                    contentDescription = "${option.label}، امتیاز ${option.localizedValue}"
                    setOnClickListener { scoreInput.setText(option.value) }
                }
                quickScores.addView(quick, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(2); marginEnd = dp(2)
                })
            }
            inside.addView(quickScores, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            ).apply { bottomMargin = dp(10) })
            addInput(inside, "توضیحات و دلایل ارزیابی (برای امتیاز زیر ۶۰ الزامی)", draft.notes.optString(id), false, true) { value ->
                draft.notes.put(id, value)
                saveDraftSafely(draft)
                refreshQuestionState()
                refreshProgress()
            }

            val attached = EvidenceRefs.references(draft.attachments, id)
            inside.addView(text("مستندات این بند · ${attached.size} از ${EvidenceRefs.MAX_PER_QUESTION}", 12, true, muted).apply {
                setPadding(0, dp(2), 0, dp(7))
            })
            if (attached.size < EvidenceRefs.MAX_PER_QUESTION) {
                val evidenceActions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                val cameraAction = makeButton("دوربین", false) { launchCamera(id) }.apply {
                    setIconResource(R.drawable.ic_camera)
                    iconTint = ColorStateList.valueOf(navy)
                }
                evidenceActions.addView(cameraAction, LinearLayout.LayoutParams(0, dp(47), 1f).apply {
                    marginEnd = dp(4)
                })
                val galleryAction = makeButton("گالری / PDF", false) { launchEvidencePicker(id) }
                evidenceActions.addView(galleryAction, LinearLayout.LayoutParams(0, dp(47), 1f).apply {
                    marginStart = dp(4)
                })
                inside.addView(evidenceActions, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(47)
                ).apply { bottomMargin = dp(8) })
            } else {
                inside.addView(text("سقف چهار مستند برای این سؤال تکمیل است.", 11, true, muted))
            }
            attached.forEachIndexed { attachmentIndex, reference ->
                val attachmentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    background = rounded(Color.rgb(246, 248, 251), 10)
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                }
                attachmentRow.addView(text("${attachmentIndex + 1}. ${evidenceVault.metadata(reference)}", 10, false, ink),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val viewButton = makeButton("مشاهده", false) { openAttachedEvidence(reference) }
                attachmentRow.addView(viewButton, LinearLayout.LayoutParams(dp(88), dp(40)).apply { marginStart = dp(4) })
                val deleteButton = makeButton("حذف", false) {
                    AlertDialog.Builder(this)
                        .setTitle("حذف مستند")
                        .setMessage("فقط نسخه رمزنگاری‌شده این مستند از پیش‌نویس حذف می‌شود؛ فایل اصلی گوشی دست‌نخورده می‌ماند.")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("حذف مستند") { _, _ ->
                            if (EvidenceRefs.remove(draft.attachments, id, reference)) {
                                if (saveDraftSafely(draft)) {
                                    if (reference !in EvidenceRefs.all(draft.attachments) && !runCatching { deficiencies.isEvidenceReferenced(reference) }.getOrDefault(true)) evidenceVault.delete(reference)
                                } else {
                                    EvidenceRefs.add(draft.attachments, id, reference)
                                    return@setPositiveButton
                                }
                            }
                            showMission(mission, globalIndex)
                        }.show()
                }
                attachmentRow.addView(deleteButton, LinearLayout.LayoutParams(dp(80), dp(40)))
                inside.addView(attachmentRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(5) })
            }
            val questionDeficiencies = runCatching { deficiencies.forQuestion(key, id) }.getOrDefault(emptyList())
            if (questionDeficiencies.isNotEmpty()) {
                inside.addView(text("${faDigits(questionDeficiencies.size.toString())} نقص ثبت‌شده برای این شاخص", 10, true, Color.rgb(150, 96, 31)).apply {
                    setPadding(0, dp(7), 0, dp(5))
                })
            }
            val deficiencyAction = makeButton(
                if (questionDeficiencies.isEmpty()) "ثبت نقص برای این شاخص" else "ثبت نقص جدید · ${faDigits(questionDeficiencies.size.toString())} سابقه",
                false
            ) { showDeficiencyEditor(mission, id, questionText, globalIndex) }.apply {
                textSize = 11f
                setIconResource(R.drawable.ic_notes)
                iconTint = ColorStateList.valueOf(navy)
            }
            inside.addView(deficiencyAction, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(45)
            ).apply { topMargin = dp(7) })
            panel.addView(inside)
            content.addView(panel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(11) })
        }

        label("پاسخ‌ها و مستندات هر آیتم به‌صورت خودکار و امن ذخیره می‌شوند. تکمیل همه بندها برای امضا الزامی نیست؛ بندهای خالی در مرحله امضا به شما اعلام می‌شوند.",
            11, false, muted)

        fun openReview() {
            if (!saveDraftSafely(draft)) return
            if (!enabled) {
                AlertDialog.Builder(this)
                    .setTitle("مأموریت هنوز برای ارسال فعال نیست")
                    .setMessage("تکمیل همه بندها الزامی نیست؛ اما تاریخ یا مجوز این مأموریت در آخرین پاسخ سرور فعال نیست. پیش‌نویس شما حفظ شده است.")
                    .setNegativeButton("بازگشت به ویرایش", null)
                    .setNeutralButton("مرور گزارش", { _, _ -> showReportReview(mission, draft) })
                    .setPositiveButton("به‌روزرسانی مأموریت", { _, _ ->
                        showHome("missions")
                        connectAndSync()
                    }).show()
            } else {
                showReportReview(mission, draft)
            }
        }

        // Fixed native navigation: previous section, sign/review from any section, next section.
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setBackgroundColor(Color.WHITE)
        }
        val previous = makeButton("شاخص قبلی", false) {
            if (currentSectionIndex > 0) showMission(mission, pages[currentSectionIndex - 1].questionIndices.first())
        }.apply { isEnabled = currentSectionIndex > 0; textSize = 12f }
        footer.addView(previous, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(3) })
        val sign = makeButton("امضا", true) { openReview() }.apply {
            setIconResource(R.drawable.ic_signature)
            iconTint = ColorStateList.valueOf(Color.WHITE)
            textSize = 12f
        }
        footer.addView(sign, LinearLayout.LayoutParams(0, dp(50), 0.85f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
        val nextCaption = if (currentSectionIndex < pages.lastIndex) "شاخص بعدی" else "مرور گزارش"
        val next = makeButton(nextCaption, true) {
            if (currentSectionIndex < pages.lastIndex) {
                if (!saveDraftSafely(draft)) return@makeButton
                showMission(mission, pages[currentSectionIndex + 1].questionIndices.first())
            } else openReview()
        }.apply { textSize = 12f }
        footer.addView(next, LinearLayout.LayoutParams(0, dp(50), 1.15f).apply { marginStart = dp(3) })
        shell.addView(View(this).apply { setBackgroundColor(line) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        shell.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(69)
        ))
    }

    private fun deficiencyActor(): String = serverSnapshot.optJSONObject("profile")
        ?.optString("name", "بازرس")?.takeIf { it.isNotBlank() } ?: "بازرس"

    private fun deficiencyDateLabel(value: String): String {
        val jalali = PersianCalendar.normalize(value) ?: runCatching { PersianCalendar.fromGregorian(LocalDate.parse(value)) }.getOrDefault(value)
        return faDigits(jalali)
    }

    private fun showDeficiencies() {
        activeTab = "deficiencies"
        selectedDeficiencyId = null
        selectedMission = null
        openDraft = null
        screen("مدیریت نواقص", "ثبت، پیگیری و بازدید مجدد", withNav = true)

        val all = runCatching { deficiencies.entries() }.getOrElse {
            AppDiagnostics.record(this, "deficiency_list", it as? Exception ?: Exception(it))
            emptyList()
        }
        val valid = all.filterNot { it.optBoolean("integrity_error") }
        val openCount = valid.count { it.optString("status") == DeficiencyWorkflowRules.OPEN }
        val progressCount = valid.count { it.optString("status") == DeficiencyWorkflowRules.IN_PROGRESS }
        val verifyCount = valid.count { it.optString("status") == DeficiencyWorkflowRules.AWAITING_VERIFICATION }
        val verifiedCount = valid.count { it.optString("status") == DeficiencyWorkflowRules.APPROVED }
        val rejectedCount = valid.count { it.optString("status") == DeficiencyWorkflowRules.REJECTED }
        val pendingSync = valid.count { it.optString("sync_state") != "synced" }
        val overdueCount = valid.count {
            DeficiencyWorkflowRules.isOverdue(it.optString("due_date_jalali", it.optString("due_date")), it.optString("status"))
        }

        val heroCard = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); strokeWidth = dp(1); strokeColor = gold
            cardElevation = dp(1).toFloat(); setCardBackgroundColor(navy)
        }
        val heroBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        heroBody.addView(text("چرخه پیگیری اصلاحات", 16, true, Color.WHITE))
        heroBody.addView(text("${faDigits(valid.size.toString())} نقص · ${faDigits(pendingSync.toString())} مورد ذخیره‌شده روی دستگاه و در انتظار اتصال سرور", 10, false, Color.rgb(220, 231, 242)).apply {
            setPadding(0, dp(5), 0, dp(10))
        })
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun metric(value: Int, caption: String, tone: Int) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = rounded(Color.argb(28, 255, 255, 255), 10); setPadding(dp(5), dp(8), dp(5), dp(8)) }
            box.addView(text(faDigits(value.toString()), 18, true, Color.WHITE).apply { gravity = Gravity.CENTER })
            box.addView(text(caption, 9, true, tone).apply { gravity = Gravity.CENTER; maxLines = 1 })
            metrics.addView(box, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        metric(openCount, "ثبت‌شده", Color.rgb(244, 214, 146))
        metric(progressCount, "در پیگیری", Color.rgb(244, 214, 146))
        metric(verifyCount, "بازدید مجدد", Color.rgb(244, 214, 146))
        metric(verifiedCount, "تأیید نهایی", Color.rgb(178, 231, 199))
        heroBody.addView(metrics)
        heroCard.addView(heroBody)
        content.addView(heroCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(11) })

        if (overdueCount > 0) {
            content.addView(text("${faDigits(overdueCount.toString())} نقص از مهلت اصلاح عبور کرده است.", 11, true, Color.rgb(154, 61, 44)).apply {
                background = rounded(Color.rgb(252, 237, 233), 11); setPadding(dp(11), dp(10), dp(11), dp(10))
            })
            gap(8)
        }
        goldButton("ثبت نقص جدید  +") { chooseDeficiencyMission() }
        secondaryButton("همگام‌سازی نواقص با سامانه") {
            if (deviceToken == null) notifyShort("برای همگام‌سازی ابتدا وارد حساب بازرس شوید") else connectAndSync()
        }
        val search = field("جست‌وجوی اداره، شاخص یا شرح نقص")
        search.setSingleLine(true)
        search.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0)
        search.compoundDrawablePadding = dp(8)
        val filters = listOf(
            "all" to "همه",
            DeficiencyWorkflowRules.OPEN to "ثبت‌شده",
            DeficiencyWorkflowRules.IN_PROGRESS to "در پیگیری",
            DeficiencyWorkflowRules.AWAITING_VERIFICATION to "تأیید سربازرس",
            DeficiencyWorkflowRules.REJECTED to "رد رفع",
            DeficiencyWorkflowRules.APPROVED to "تأیید نهایی"
        )
        var selectedFilter = "all"
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val filterViews = linkedMapOf<String, TextView>()
        // Deferred callback: filter chips are wired before the renderer body is assigned.
        var renderRows: () -> Unit = {}
        filters.forEach { (key, caption) ->
            val chipView = text(caption, 10, true, muted).apply {
                gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(Color.WHITE, 18)
                isClickable = true; isFocusable = true
                setOnClickListener { selectedFilter = key; renderRows() }
            }
            filterViews[key] = chipView
            filterRow.addView(chipView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(37)).apply { marginEnd = dp(5) })
        }
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filterRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(filterScroll)
        val resultCount = text("", 11, false, muted).apply { setPadding(0, dp(9), 0, dp(6)) }
        content.addView(resultCount)
        val resultHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(resultHost)

        renderRows = {
            filterViews.forEach { (key, view) ->
                val chosen = key == selectedFilter
                view.setTextColor(if (chosen) Color.WHITE else muted)
                view.background = rounded(if (chosen) navy else Color.WHITE, 18)
            }
            resultHost.removeAllViews()
            val query = search.text?.toString()?.trim().orEmpty()
            val rows = all.filter { record ->
                if (record.optBoolean("integrity_error")) return@filter true
                val statusOk = selectedFilter == "all" || record.optString("status") == selectedFilter
                val queryOk = query.isBlank() || listOf("organization", "question_title", "description", "mission_key")
                    .any { record.optString(it).contains(query, ignoreCase = true) }
                statusOk && queryOk
            }
            resultCount.text = "${faDigits(rows.size.toString())} مورد از ${faDigits(all.size.toString())} سابقه"
            if (rows.isEmpty()) {
                val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = rounded(Color.WHITE, 16); setPadding(dp(18), dp(22), dp(18), dp(22)) }
                empty.addView(icon(R.drawable.ic_notes, muted, 30))
                empty.addView(text("نقصی در این فهرست وجود ندارد", 14, true, navy).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
                empty.addView(text("از داخل فرم بازرسی یا دکمه ثبت نقص جدید، موردی را ثبت کنید.", 11, false, muted).apply { gravity = Gravity.CENTER })
                resultHost.addView(empty)
            } else rows.forEach { record ->
                val card = MaterialCardView(this).apply {
                    radius = dp(16).toFloat(); strokeWidth = dp(1); strokeColor = line
                    cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
                    isClickable = true; isFocusable = true
                    setOnClickListener { showDeficiencyDetail(record) }
                }
                val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)) }
                if (record.optBoolean("integrity_error")) {
                    body.addView(text("رکورد نقص قابل بازیابی نیست", 13, true, Color.rgb(160, 55, 45)))
                    body.addView(text("اطلاعات اصلی حذف نشده؛ برای جلوگیری از آسیب، این رکورد فقط‌خواندنی است.", 10, false, muted))
                } else {
                    val status = record.optString("status")
                    val severity = record.optString("severity")
                    val overdue = DeficiencyWorkflowRules.isOverdue(record.optString("due_date_jalali", record.optString("due_date")), status)
                    val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                    val severityColor = when (severity) {
                        DeficiencyWorkflowRules.CRITICAL, DeficiencyWorkflowRules.HIGH -> Color.rgb(164, 61, 49)
                        DeficiencyWorkflowRules.MEDIUM -> Color.rgb(153, 103, 35)
                        else -> Color.rgb(65, 105, 91)
                    }
                    header.addView(chip(DeficiencyWorkflowRules.severityLabel(severity), Color.rgb(250, 241, 226), severityColor).apply { textSize = 9f })
                    header.addView(text(DeficiencyWorkflowRules.statusLabel(status), 10, true, navy).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (overdue) header.addView(chip("مهلت گذشته", Color.rgb(252, 235, 232), Color.rgb(165, 55, 45)).apply { textSize = 8f })
                    body.addView(header)
                    body.addView(text(record.optString("organization", "دستگاه اجرایی"), 14, true, navy).apply { setPadding(0, dp(8), 0, dp(3)) })
                    body.addView(text("شاخص ${faDigits((record.optInt("question_index") + 1).toString())} · ${record.optString("question_title")}", 11, true, ink).apply { maxLines = 2 })
                    body.addView(text(record.optString("description"), 10, false, muted).apply { maxLines = 2; setPadding(0, dp(5), 0, dp(5)) })
                    body.addView(text("مهلت اصلاح: ${deficiencyDateLabel(record.optString("due_date_jalali", record.optString("due_date")))} · مستند مرتبط: ${faDigits(record.optInt("linked_evidence_count").toString())}", 9, false, muted))
                    val syncText = when (record.optString("sync_state")) {
                        "synced" -> "همگام‌شده با سامانه"
                        "failed" -> "خطا در همگام‌سازی"
                        "conflict" -> "تعارض نسخه؛ نیازمند بررسی بازرس"
                        else -> "ذخیره امن روی دستگاه · در انتظار اتصال سرور"
                    }
                    body.addView(text(syncText, 9, true, when (record.optString("sync_state")) {
                        "synced" -> Color.rgb(35, 125, 80)
                        "conflict", "failed" -> Color.rgb(165, 55, 45)
                        else -> Color.rgb(150, 96, 31)
                    }).apply { setPadding(0, dp(6), 0, 0) })
                }
                card.addView(body)
                resultHost.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderRows()
            override fun afterTextChanged(s: Editable?) {}
        })
        renderRows()
        gap(7)
        label("سوابق این نسخه به‌صورت رمزنگاری‌شده روی گوشی نگهداری می‌شوند. تا اتصال endpoint اختصاصی و تأیید رسید سرور، هیچ موردی همگام‌شده تلقی نمی‌شود.", 10, false, muted)
    }

    private fun chooseDeficiencyMission() {
        val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
        val candidates = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }
            .filter { missionKey(it).isNotBlank() && (it.optJSONObject("checklistSnapshot")?.optJSONArray("questions")?.length() ?: 0) > 0 }
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this).setTitle("مأموریتی برای ثبت نقص موجود نیست")
                .setMessage("ابتدا مأموریت‌های خود را از سرور همگام‌سازی کنید یا از داخل یک فرم بازرسی، نقص همان شاخص را ثبت کنید.")
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        val labels = candidates.map { "${it.optString("orgName", "دستگاه اجرایی")} · ${it.optString("date", "")}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب اداره و مأموریت")
            .setItems(labels) { _, index -> chooseDeficiencyQuestion(candidates[index]) }
            .setNegativeButton("انصراف", null).show()
    }

    private fun chooseDeficiencyQuestion(mission: JSONObject) {
        val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
        val valid = (0 until questions.length()).mapNotNull { index ->
            val question = questions.optJSONObject(index) ?: return@mapNotNull null
            val id = question.optString("id").trim()
            if (id.isBlank()) null else Triple(index, id, question.optString("text", question.optString("title", question.optString("question", id))))
        }
        if (valid.isEmpty()) { notifyShort("شاخص معتبری برای ثبت نقص پیدا نشد"); return }
        val labels = valid.map { "${faDigits((it.first + 1).toString())}. ${it.third}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب شاخص دارای نقص")
            .setItems(labels) { _, index ->
                val item = valid[index]
                showDeficiencyEditor(mission, item.second, item.third, item.first)
            }
            .setNegativeButton("انصراف", null).show()
    }

    private fun showDeficiencyEditor(mission: JSONObject, questionId: String, questionTitle: String, questionIndex: Int) {
        activeTab = "deficiency_editor"
        selectedMission = mission
        selectedMissionKey = missionKey(mission)
        screen("ثبت نقص جدید", mission.optString("orgName", "دستگاه اجرایی"))
        val info = MaterialCardView(this).apply { radius = dp(15).toFloat(); strokeWidth = dp(1); strokeColor = line; cardElevation = 0f; setCardBackgroundColor(Color.WHITE) }
        val infoBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)) }
        infoBody.addView(text(mission.optString("orgName", "دستگاه اجرایی"), 14, true, navy))
        infoBody.addView(text("شاخص ${faDigits((questionIndex + 1).toString())} · $questionTitle", 11, false, muted).apply { setPadding(0, dp(5), 0, 0) })
        info.addView(infoBody)
        content.addView(info)
        gap(12)
        val descriptionInput = field("توضیحات و شرح نقص (حداقل ۸ نویسه)")
        label("شدت نقص", 13, true, navy)
        var selectedSeverity = DeficiencyWorkflowRules.MEDIUM
        val severityOptions = listOf(
            DeficiencyWorkflowRules.CRITICAL to "بحرانی",
            DeficiencyWorkflowRules.HIGH to "زیاد",
            DeficiencyWorkflowRules.MEDIUM to "متوسط",
            DeficiencyWorkflowRules.LOW to "کم"
        )
        val severityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val severityButtons = linkedMapOf<String, TextView>()
        fun refreshSeverity() {
            severityButtons.forEach { (key, view) ->
                val chosen = key == selectedSeverity
                view.setTextColor(if (chosen) Color.WHITE else navy)
                view.background = rounded(if (chosen) navy else Color.WHITE, 11)
            }
        }
        severityOptions.forEach { (key, caption) ->
            val view = text(caption, 10, true, navy).apply {
                gravity = Gravity.CENTER; setPadding(dp(5), dp(10), dp(5), dp(10)); background = rounded(Color.WHITE, 11)
                isClickable = true; isFocusable = true
                setOnClickListener { selectedSeverity = key; refreshSeverity() }
            }
            severityButtons[key] = view
            severityRow.addView(view, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4); marginStart = dp(4) })
        }
        content.addView(severityRow)
        refreshSeverity()
        gap(10)
        var dueDate = LocalDate.now().plusDays(7)
        lateinit var dueButton: MaterialButton
        dueButton = secondaryButton("مهلت اصلاح: ${deficiencyDateLabel(dueDate.toString())}") {
            val picker = DatePickerDialog(this, { _, year, month, day ->
                dueDate = LocalDate.of(year, month + 1, day)
                dueButton.text = "مهلت اصلاح: ${deficiencyDateLabel(dueDate.toString())}"
            }, dueDate.year, dueDate.monthValue - 1, dueDate.dayOfMonth)
            picker.datePicker.minDate = System.currentTimeMillis() - 1000L
            picker.show()
        }
        label("مستندات موجود برای این شاخص به نقص مرتبط می‌شوند؛ برای افزودن عکس یا PDF از فرم بازرسی استفاده کنید.", 10, false, muted)
        button("ذخیره امن نقص") {
            val cleanDescription = descriptionInput.text?.toString().orEmpty().trim()
            val validation = DeficiencyWorkflowRules.validateDescription(cleanDescription)
            if (validation != null) { notifyShort(validation); return@button }
            if (dueDate.isBefore(LocalDate.now())) { notifyShort("مهلت اصلاح نمی‌تواند قبل از امروز باشد"); return@button }
            val draft = runCatching { InspectionDraft(missionKey(mission), store) }.getOrNull()
            val linkedEvidence = if (draft != null && !draft.integrityError) EvidenceRefs.references(draft.attachments, questionId) else emptyList()
            val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
            val question = questions.optJSONObject(questionIndex)
            val sectionId = question?.optString("sectionId", "").orEmpty()
            val organizationId = mission.optString("orgId", mission.optString("org_id", mission.optString("organizationId", "")))
            val dueJalali = PersianCalendar.fromGregorian(dueDate)
            try {
                val record = deficiencies.create(
                    missionKey(mission), organizationId, mission.optString("orgName", "دستگاه اجرایی"), questionId,
                    questionIndex, sectionId, questionTitle, cleanDescription, selectedSeverity, dueDate.toString(), dueJalali,
                    deficiencyActor(), serverSnapshot.optJSONObject("profile")?.optString("inspector_ref", "").orEmpty(), linkedEvidence
                )
                showDeficiencyDetail(record)
                sessionToken?.let { activeSession ->
                    val recordId = record.optString("id")
                    work({ syncOneDeficiency(record, activeSession); deficiencies.get(recordId) ?: record }) { synced ->
                        if (activeTab == "deficiency_detail" && selectedDeficiencyId == recordId) showDeficiencyDetail(synced)
                        notifyShort("نقص در سرور ثبت و رسید همگام‌سازی دریافت شد")
                    }
                }
            } catch (error: Exception) {
                AppDiagnostics.record(this, "deficiency_create", error)
                notifyShort(error.message?.take(160) ?: "ذخیره نقص انجام نشد")
            }
        }
        secondaryButton("انصراف و بازگشت") {
            showMission(mission, questionIndex)
        }
    }

    private fun openRemoteDeficiencyEvidence(uploadId: Int) {
        val bearer = sessionToken
        if (bearer.isNullOrBlank()) { notifyShort("برای دریافت مستند از سرور ابتدا همگام‌سازی کنید"); return }
        work({ api.downloadInspectorEvidence(uploadId, bearer) }) { (bytes, mime) ->
            try {
                val ext = when {
                    mime == "application/pdf" -> "pdf"
                    mime == "image/jpeg" -> "jpg"
                    mime == "image/png" -> "png"
                    mime == "image/webp" -> "webp"
                    else -> "bin"
                }
                val dir = File(cacheDir, "deficiency_evidence").apply { mkdirs() }
                val file = File(dir, "finding-$uploadId.$ext")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                else notifyShort("برای نمایش این نوع فایل، برنامه سازگار نصب نیست")
            } catch (error: Exception) {
                AppDiagnostics.record(this, "deficiency_evidence_open", error)
                notifyShort("بازکردن مستند انجام نشد")
            }
        }
    }

    /** The mobile finding API only permits the originating inspector to change a finding. */
    private fun canManageDeficiency(record: JSONObject): Boolean {
        if (record.optBoolean("integrity_error")) return false
        val currentRef = serverSnapshot.optJSONObject("profile")?.optString("inspector_ref", "").orEmpty()
        val recordRef = record.optString("inspector_ref").ifBlank { record.optString("inspectorRef") }
        return DeficiencyWorkflowRules.canInspectorManage(record.optString("source"), recordRef, currentRef)
    }

    private fun showDeficiencyDetail(record: JSONObject) {
        if (record.optBoolean("integrity_error")) {
            AlertDialog.Builder(this).setTitle("رکورد فقط‌خواندنی")
                .setMessage("این رکورد رمزنگاری‌شده قابل بازیابی نیست. اطلاعات اصلی حذف نشده و امکان ویرایش آن متوقف شده است.")
                .setPositiveButton("متوجه شدم", null).show()
            return
        }
        activeTab = "deficiency_detail"
        selectedDeficiencyId = record.optString("id")
        screen("جزئیات نقص", record.optString("organization", "دستگاه اجرایی"))
        val severity = record.optString("severity")
        val status = record.optString("status")
        val canManage = canManageDeficiency(record)
        val overdue = DeficiencyWorkflowRules.isOverdue(record.optString("due_date_jalali", record.optString("due_date")), status)
        val header = MaterialCardView(this).apply { radius = dp(17).toFloat(); strokeWidth = dp(1); strokeColor = gold; cardElevation = dp(1).toFloat(); setCardBackgroundColor(navy) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)) }
        body.addView(text(record.optString("organization", "دستگاه اجرایی"), 16, true, Color.WHITE))
        body.addView(text("شاخص ${faDigits((record.optInt("question_index") + 1).toString())} · ${record.optString("question_title")}", 11, false, Color.rgb(221, 231, 242)).apply { setPadding(0, dp(5), 0, dp(10)) })
        body.addView(chip("${DeficiencyWorkflowRules.severityLabel(severity)} · ${DeficiencyWorkflowRules.statusLabel(status)}", Color.argb(35, 255, 255, 255), Color.rgb(248, 215, 151)))
        header.addView(body)
        content.addView(header)
        gap(11)
        val description = MaterialCardView(this).apply { radius = dp(15).toFloat(); strokeWidth = dp(1); strokeColor = line; cardElevation = 0f; setCardBackgroundColor(Color.WHITE) }
        val descBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13)) }
        descBody.addView(text("شرح نقص", 12, true, muted))
        descBody.addView(text(record.optString("description"), 14, false, ink).apply { setPadding(0, dp(6), 0, dp(10)) })
        descBody.addView(text("مهلت اصلاح: ${deficiencyDateLabel(record.optString("due_date_jalali", record.optString("due_date")))}", 11, true, if (overdue) Color.rgb(165, 55, 45) else navy))
        descBody.addView(text("مستندات مرتبط در شاخص: ${faDigits(record.optInt("linked_evidence_count").toString())}", 10, false, muted).apply { setPadding(0, dp(4), 0, 0) })
        val linkedRefs = record.optJSONArray("linked_evidence_refs") ?: JSONArray()
        for (i in 0 until linkedRefs.length()) {
            val reference = linkedRefs.optString(i)
            if (reference.isBlank()) continue
            val evidenceRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, dp(5), 0, 0) }
            evidenceRow.addView(text("${faDigits((i + 1).toString())}. ${evidenceVault.metadata(reference)}", 10, false, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            evidenceRow.addView(makeButton("مشاهده", false) { openAttachedEvidence(reference) }, LinearLayout.LayoutParams(dp(86), dp(38)))
            descBody.addView(evidenceRow)
        }
        description.addView(descBody)
        content.addView(description)
        val serverEvidence = record.optJSONArray("server_evidence_files") ?: JSONArray()
        if (serverEvidence.length() > 0) {
            gap(9)
            sectionTitle("مستندات ارسال‌شده به سرور")
            for (i in 0 until serverEvidence.length()) {
                val file = serverEvidence.optJSONObject(i) ?: continue
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; background = rounded(Color.WHITE, 11); setPadding(dp(10), dp(7), dp(10), dp(7)) }
                row.addView(text("${faDigits((i + 1).toString())}. ${file.optString("name", "مستند")}", 10, true, ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(makeButton("دریافت", false) { openRemoteDeficiencyEvidence(file.optInt("id")) }, LinearLayout.LayoutParams(dp(84), dp(38)))
                content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
            }
        }
        gap(10)
        val syncState = record.optString("sync_state")
        val syncLabel = when (syncState) {
            "synced" -> "این نقص با شناسه ${record.optString("server_id")} در سرور ثبت و رسید آن دریافت شده است."
            "failed" -> "آخرین همگام‌سازی ناموفق بوده است: ${record.optString("sync_error")}" 
            "conflict" -> "نسخه این نقص در سرور و گوشی متفاوت است. برای جلوگیری از بازنویسی ناخواسته، ارسال متوقف شده است. یکی از دو نسخه را آگاهانه انتخاب کنید."
            else -> "این رکورد به‌صورت رمزنگاری‌شده روی گوشی ذخیره شده و هنوز به سرور ارسال نشده است."
        }
        content.addView(text(syncLabel, 10, true, if (syncState == "synced") Color.rgb(35, 125, 80) else Color.rgb(150, 96, 31)).apply {
            background = rounded(if (syncState == "synced") Color.rgb(231, 246, 237) else Color.rgb(250, 243, 226), 11)
            setPadding(dp(11), dp(10), dp(11), dp(10))
        })
        if (!canManage) {
            content.addView(text(
                "این نقص برای مشاهده در مأموریت‌های شما همگام‌سازی شده است؛ تغییر وضعیت فقط توسط بازرس ثبت‌کننده و از طریق حساب مجاز او انجام می‌شود.",
                10, true, Color.rgb(91, 104, 119)
            ).apply {
                background = rounded(Color.rgb(239, 243, 248), 11)
                setPadding(dp(11), dp(10), dp(11), dp(10))
            })
        }
        val correctiveActions = serverSnapshot.optJSONArray("corrective_actions") ?: JSONArray()
        val relatedActions = (0 until correctiveActions.length()).mapNotNull { correctiveActions.optJSONObject(it) }
            .filter { it.optString("findingId", it.optString("finding_id")) == record.optString("id") }
        if (relatedActions.isNotEmpty()) {
            gap(10)
            sectionTitle("اقدامات اصلاحی اداره")
            relatedActions.forEach { action ->
                val actionCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 12); setPadding(dp(11), dp(9), dp(11), dp(9)) }
                actionCard.addView(text(action.optString("action", action.optString("title", "اقدام اصلاحی")), 11, true, navy))
                actionCard.addView(text("مسئول: ${action.optString("responsible", "تعیین نشده")} · مهلت: ${action.optString("due", action.optString("dueDate", "—"))}", 10, false, muted).apply { setPadding(0, dp(4), 0, 0) })
                val portalResponses = action.optJSONArray("portal_responses") ?: JSONArray()
                if (portalResponses.length() > 0) {
                    val latest = portalResponses.optJSONObject(0)
                    if (latest != null) actionCard.addView(text("پاسخ اداره: ${latest.optString("response_text", "")}", 10, false, ink).apply { setPadding(0, dp(4), 0, 0) })
                    if (latest != null) actionCard.addView(text("وضعیت پاسخ: ${latest.optString("status", "در انتظار بررسی")}", 9, true, muted).apply { setPadding(0, dp(3), 0, 0) })
                }
                val extensionRequests = action.optJSONArray("extension_requests") ?: JSONArray()
                if (extensionRequests.length() > 0) {
                    val extension = extensionRequests.optJSONObject(0)
                    if (extension != null) actionCard.addView(text("درخواست تمدید تا ${extension.optString("requested_due", "—")} · ${extension.optString("status", "در انتظار بررسی")}", 9, true, Color.rgb(150, 96, 31)).apply { setPadding(0, dp(3), 0, 0) })
                }
                content.addView(actionCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
            }
        }
        gap(12)
        sectionTitle("تاریخچه تغییر وضعیت")
        val events = record.optJSONArray("events") ?: JSONArray()
        for (i in events.length() - 1 downTo 0) {
            val event = events.optJSONObject(i) ?: continue
            val eventCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 12); setPadding(dp(11), dp(9), dp(11), dp(9)) }
            val from = event.optString("from_status")
            val to = event.optString("to_status")
            val title = if (from.isBlank()) "ثبت اولیه نقص" else "${DeficiencyWorkflowRules.statusLabel(from)} ← ${DeficiencyWorkflowRules.statusLabel(to)}"
            eventCard.addView(text(title, 11, true, navy))
            eventCard.addView(text("${event.optString("actor", "بازرس")} · ${formatLocalTimestamp(event.optString("at"))}", 9, false, muted).apply { setPadding(0, dp(3), 0, dp(3)) })
            if (event.optString("note").isNotBlank()) eventCard.addView(text(event.optString("note"), 10, false, ink))
            content.addView(eventCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(7) })
        }
        if (syncState == "conflict" && canManage) {
            button("پذیرش نسخه آخر سرور") {
                try { showDeficiencyDetail(deficiencies.resolveConflictUseServer(record.optString("id"))) }
                catch (error: Exception) { notifyShort(error.message ?: "بازیابی نسخه سرور انجام نشد") }
            }
            secondaryButton("حفظ تغییرات گوشی و ارسال مجدد") {
                try {
                    val local = deficiencies.resolveConflictKeepLocal(record.optString("id"))
                    showDeficiencyDetail(local)
                    sessionToken?.let { activeSession ->
                        work({ syncOneDeficiency(local, activeSession); deficiencies.get(local.optString("id")) ?: local }) { synced ->
                            if (activeTab == "deficiency_detail" && selectedDeficiencyId == synced.optString("id")) showDeficiencyDetail(synced)
                            notifyShort("تغییرات دستگاه با نسخه جدید سرور ثبت شد")
                        }
                    } ?: notifyShort("تغییرات روی دستگاه نگهداری شد؛ پس از ورود همگام‌سازی کنید")
                } catch (error: Exception) { notifyShort(error.message ?: "حل تعارض انجام نشد") }
            }
        }
        val next = when (status) {
            DeficiencyWorkflowRules.OPEN -> listOf(DeficiencyWorkflowRules.IN_PROGRESS)
            DeficiencyWorkflowRules.IN_PROGRESS -> listOf(DeficiencyWorkflowRules.AWAITING_VERIFICATION)
            DeficiencyWorkflowRules.REJECTED -> listOf(DeficiencyWorkflowRules.IN_PROGRESS)
            else -> emptyList()
        }
        if (canManage && syncState in setOf("local_pending", "failed")) {
            secondaryButton(if (syncState == "failed") "تلاش مجدد برای همگام‌سازی نقص" else "همگام‌سازی نقص با سامانه") {
                val bearer = sessionToken
                if (bearer.isNullOrBlank()) notifyShort("ابتدا وارد حساب بازرس شوید")
                else if (!online()) notifyShort("اتصال اینترنت برقرار نیست؛ اطلاعات روی گوشی حفظ شده است")
                else if (busy) notifyShort("عملیات دیگری در حال انجام است؛ کمی صبر کنید")
                else work({ syncOneDeficiency(record, bearer); deficiencies.get(record.optString("id")) ?: record }) { synced ->
                    if (activeTab == "deficiency_detail" && selectedDeficiencyId == synced.optString("id")) showDeficiencyDetail(synced)
                    notifyShort("نقص با موفقیت همگام‌سازی شد")
                }
            }
        }
        if (canManage && syncState == "synced" && next.isNotEmpty()) {
            button("ثبت اقدام / تغییر وضعیت") { chooseDeficiencyStatus(record, next) }
        }
        secondaryButton("بازکردن شاخص مرتبط") {
            val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
            val mission = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }
                .firstOrNull { missionKey(it) == record.optString("mission_key") }
            if (mission != null) showMission(mission, record.optInt("question_index"))
            else notifyShort("مأموریت در حافظه فعلی نیست؛ ابتدا همگام‌سازی کنید")
        }
        secondaryButton("بازگشت به فهرست نواقص") { showHome("deficiencies") }
    }

    private fun chooseDeficiencyStatus(record: JSONObject, options: List<String>) {
        if (!canManageDeficiency(record)) { notifyShort("این نقص فقط‌خواندنی است"); return }
        if (record.optString("sync_state") != "synced") { notifyShort("ابتدا نقص را با سرور همگام‌سازی کنید"); return }
        if (sessionToken.isNullOrBlank()) { notifyShort("نشست امن بازرس منقضی شده است؛ دوباره همگام‌سازی کنید"); return }
        if (!online()) { notifyShort("برای ثبت تغییر وضعیت، اتصال اینترنت لازم است؛ وضعیت قبلی حفظ شد"); return }
        if (busy) { notifyShort("عملیات دیگری در حال انجام است؛ کمی صبر کنید"); return }
        val labels = options.map { DeficiencyWorkflowRules.statusLabel(it) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("انتخاب وضعیت بعدی")
            .setItems(labels) { _, index ->
                val nextStatus = options[index]
                val noteInput = EditText(this).apply {
                    hint = if (DeficiencyWorkflowRules.requiresActionNote(nextStatus)) "شرح اقدام یا نتیجه بازدید مجدد (حداقل ۸ نویسه)" else "توضیح اقدام (اختیاری)"
                    textSize = 14f; setTextColor(ink); setHintTextColor(muted)
                    gravity = Gravity.RIGHT or Gravity.TOP
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    minLines = 3; maxLines = 5
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                }
                val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(noteInput) }
                AlertDialog.Builder(this).setTitle("توضیح اقدام")
                    .setView(wrap)
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("ثبت تغییر") { _, _ ->
                        try {
                            val updated = deficiencies.changeStatus(record.optString("id"), nextStatus, noteInput.text?.toString().orEmpty(), deficiencyActor())
                            showDeficiencyDetail(updated)
                            sessionToken?.let { activeSession ->
                                work({ syncOneDeficiency(updated, activeSession); deficiencies.get(updated.optString("id")) ?: updated }) { synced ->
                                    if (activeTab == "deficiency_detail" && selectedDeficiencyId == synced.optString("id")) showDeficiencyDetail(synced)
                                    notifyShort("تغییر وضعیت نقص در سرور ثبت شد")
                                }
                            }
                        } catch (error: Exception) {
                            AppDiagnostics.record(this, "deficiency_status_change", error)
                            notifyShort(error.message?.take(180) ?: "تغییر وضعیت ثبت نشد")
                        }
                    }.show()
            }
            .setNegativeButton("انصراف", null).show()
    }

    /** Full checklist map: jump to any section or directly to its first incomplete item. */
    private fun showChecklistOverview(
        mission: JSONObject,
        draft: InspectionDraft,
        pages: List<ChecklistSectionPage>
    ) {
        val overview = draft.checklistOverview(mission)
        checklistOverviewVisible = true
        screen("نمای کلی شاخص‌ها", mission.optString("orgName", "دستگاه اجرایی"))

        val summaryCard = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            strokeWidth = dp(1)
            strokeColor = line
            cardElevation = 0f
            setCardBackgroundColor(Color.WHITE)
        }
        val summaryBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(14), dp(15), dp(14))
        }
        summaryBody.addView(text("پیشرفت کل بازرسی", 16, true, navy))
        summaryBody.addView(text("${overview.answered} از ${overview.total} بند دارای امتیاز معتبر", 12, false, muted).apply {
            setPadding(0, dp(5), 0, dp(8))
        })
        val totalBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = overview.coveragePercent
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(line)
        }
        summaryBody.addView(totalBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        summaryBody.addView(text(
            "بدون امتیاز: ${overview.unscored}  •  امتیاز نامعتبر: ${overview.invalidScores}  •  نیازمند توضیح: ${overview.missingNotes}  •  نیازمند مستند: ${overview.missingEvidence}",
            11, true, if (overview.attentionCount > 0 || overview.unscored > 0) Color.rgb(150, 96, 31) else Color.rgb(25, 130, 82)
        ).apply { setPadding(0, dp(10), 0, 0) })
        summaryCard.addView(summaryBody)
        content.addView(summaryCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(13)
        })

        pages.forEachIndexed { pageIndex, page ->
            val section = overview.section(page.id)
            val answered = section?.answered ?: 0
            val total = section?.total ?: page.questionIndices.size
            val unscored = section?.unscored ?: 0
            val blockers = section?.attentionCount ?: 0
            val firstIncomplete = section?.firstIncompleteIndex
            val complete = section?.complete == true
            val statusText = when {
                (section?.structuralIssues ?: 0) > 0 || (section?.invalidScores ?: 0) > 0 -> "نیازمند بررسی امتیاز یا ساختار"
                (section?.missingNotes ?: 0) > 0 || (section?.missingEvidence ?: 0) > 0 -> "نیازمند تکمیل توضیحات یا مستندات"
                unscored > 0 -> "دارای بندهای بدون امتیاز"
                complete -> "این شاخص تکمیل شده است"
                else -> "در انتظار تکمیل"
            }
            val statusColor = if (complete) Color.rgb(25, 130, 82) else Color.rgb(150, 96, 31)
            val panel = MaterialCardView(this).apply {
                radius = dp(16).toFloat()
                strokeWidth = dp(1)
                strokeColor = line
                cardElevation = 0f
                setCardBackgroundColor(Color.WHITE)
            }
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(14), dp(13), dp(14), dp(13))
            }
            body.addView(text("شاخص ${pageIndex + 1} · ${page.title}", 15, true, navy))
            body.addView(text("${page.category}  •  $answered از $total بند امتیاز معتبر", 11, false, muted).apply {
                setPadding(0, dp(4), 0, dp(7))
            })
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = total.coerceAtLeast(1)
                progress = answered.coerceAtMost(max)
                progressTintList = ColorStateList.valueOf(if (complete) Color.rgb(35, 153, 103) else gold)
                progressBackgroundTintList = ColorStateList.valueOf(line)
            }
            body.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
            body.addView(text(statusText + if (blockers > 0) " · $blockers مورد نیازمند اصلاح" else "", 11, true, statusColor).apply {
                setPadding(0, dp(7), 0, dp(8))
            })
            val targetIndex = firstIncomplete ?: page.questionIndices.firstOrNull() ?: 0
            val actionCaption = if (firstIncomplete != null) "رفتن به اولین مورد ناقص" else "مشاهده این شاخص"
            body.addView(makeButton(actionCaption, !complete) {
                showMission(mission, targetIndex)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)))
            panel.addView(body)
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }

        val firstIncomplete = overview.firstIncompleteIndex
        if (firstIncomplete != null) {
            button("رفتن مستقیم به اولین بند ناقص") { showMission(mission, firstIncomplete) }
        } else {
            label("تمام بندهای دارای امتیاز، توضیحات و مستندات الزامی تکمیل شده‌اند.", 12, true, Color.rgb(25, 130, 82))
        }
        secondaryButton("بازگشت به فرم فعلی") { showMission(mission, currentQuestionIndex) }
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
        if (!mission.optBoolean("can_execute", false)) {
            label("این پیش‌نمایش فقط برای بررسی است. زمان یا مجوز این مأموریت در سرور فعال نیست؛ بعد از همگام‌سازی و فعال‌شدن مأموریت می‌توانید امضا کنید.", 12, true, Color.rgb(157, 99, 29))
            secondaryButton("به‌روزرسانی مأموریت") {
                showHome("missions")
                connectAndSync()
            }
        } else {
            button("تأیید گزارش و امضای لمسی") { askBeforeSignature(mission, draft) }
        }
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
            draft.clearAcknowledgement()
            if (!saveDraftSafely(draft)) return
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
                if (saveDraftSafely(draft)) showSignatureScreen(mission, draft)
            }.show()
    }

    /** Native signature capture; content changes invalidate this signature. */
    private fun showSignatureScreen(mission: JSONObject, draft: InspectionDraft) {
        if (draft.validate(mission).isNotEmpty() || !draft.hasAcknowledgedUnscored(mission)) {
            askBeforeSignature(mission, draft); return
        }
        // A dedicated Native touch canvas, filling the available app height.
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
                if (!saveDraftSafely(draft)) return@makeButton
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
        applyShellInsets(shell)
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
        screen("ارسال گزارش‌ها", "پیگیری امن، ارسال گروهی و تلاش مجدد", withNav = true)
        val entries = outbox.entries()
        if (entries.isEmpty()) {
            emptyState("صف ارسال خالی است", "گزارش‌های امضاشده پس از تأیید بازرس در این قسمت قرار می‌گیرند.")
            secondaryButton("بازگشت به پیش‌نویس‌ها") { showHome("drafts") }
            return
        }
        val failed = entries.count { it.optString("state") == "failed" || it.optBoolean("integrity_error") }
        val uploading = entries.count { it.optString("state") == "uploading" }
        val waiting = entries.size - failed - uploading
        val summary = MaterialCardView(this).apply {
            radius = dp(18).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
        }
        val summaryBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)) }
        summaryBody.addView(text("وضعیت ارسال گزارش‌ها", 14, true, navy))
        val summaryRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, dp(9), 0, 0) }
        fun queueMetric(value: Int, caption: String, foreground: Int, background: Int) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(8), dp(4), dp(8)); setBackground(rounded(background, 11)) }
            cell.addView(text(faDigits(value.toString()), 19, true, foreground).apply { gravity = Gravity.CENTER })
            cell.addView(text(caption, 9, true, muted).apply { gravity = Gravity.CENTER })
            summaryRow.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        queueMetric(waiting, "در انتظار", navy, Color.rgb(239, 243, 248))
        queueMetric(uploading, "ارسال ناتمام", Color.rgb(165, 112, 32), Color.rgb(250, 244, 229))
        queueMetric(failed, "ناموفق / خطا", Color.rgb(165, 76, 48), Color.rgb(252, 239, 234))
        summaryBody.addView(summaryRow)
        summary.addView(summaryBody)
        content.addView(summary)
        gap(9)
        label(if (online()) "اتصال شبکه برقرار است؛ رسید نهایی فقط پس از تأیید سرور صادر می‌شود." else "حالت آفلاین؛ گزارش‌ها در حافظه امن باقی می‌مانند و برای ارسال به اینترنت نیاز دارید.",
            11, false, if (online()) Color.rgb(28, 128, 82) else Color.rgb(155, 105, 32))
        gap(9)
        val batch = makeButton("ارسال گروهی گزارش‌های سالم (${entries.count { !it.optBoolean("integrity_error") }})", true) { sendAllQueued() }
        batch.setIconResource(R.drawable.ic_send_all)
        batch.iconTint = ColorStateList.valueOf(Color.WHITE)
        batch.isEnabled = entries.any { !it.optBoolean("integrity_error") }
        content.addView(batch)
        gap(10)

        entries.forEach { item ->
            val key = item.optString("mission_key")
            val mission = item.optJSONObject("mission") ?: JSONObject()
            val state = item.optString("state")
            val integrityError = item.optBoolean("integrity_error")
            val status = when {
                integrityError -> "خطای بازیابی امن · ارسال متوقف است"
                state == "failed" -> "ارسال ناموفق · آماده تلاش مجدد"
                state == "uploading" -> "ارسال قبلی ناتمام · قابل ادامه با تلاش مجدد"
                else -> "در انتظار ارسال"
            }
            val tone = when {
                integrityError || state == "failed" -> Color.rgb(166, 76, 48)
                state == "uploading" -> Color.rgb(160, 112, 34)
                else -> Color.rgb(30, 128, 82)
            }
            val tint = when {
                integrityError || state == "failed" -> Color.rgb(252, 240, 234)
                state == "uploading" -> Color.rgb(250, 244, 229)
                else -> Color.rgb(231, 246, 238)
            }
            val panel = MaterialCardView(this).apply {
                radius = dp(17).toFloat(); strokeWidth = dp(1); strokeColor = line
                cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
            }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)) }
            val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            header.addView(iconBadge(R.drawable.ic_building, 21), LinearLayout.LayoutParams(dp(42), dp(42)))
            val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, dp(8), 0) }
            identity.addView(text(item.optString("organization", "دستگاه اجرایی"), 13, true, navy).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            identity.addView(text("کد مأموریت: ${faDigits(key.take(12))}", 9, false, muted))
            header.addView(identity, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            header.addView(chip(status, tint, tone).apply { textSize = 8f; maxLines = 2; gravity = Gravity.CENTER; setPadding(dp(7), dp(6), dp(7), dp(6)) })
            body.addView(header)

            val draft = if (key.isNotBlank()) InspectionDraft(key, store) else null
            if (draft != null && !draft.integrityError && mission.optJSONObject("checklistSnapshot") != null) {
                val total = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions")?.length() ?: 0
                val answered = runCatching { draft.answeredCount(mission) }.getOrDefault(0)
                val pct = if (total > 0) answered * 100 / total else 0
                body.addView(text("پیشرفت فرم · ${faDigits(answered.toString())} از ${faDigits(total.toString())} شاخص · ${faDigits(pct.toString())}٪", 10, true, muted).apply { setPadding(0, dp(10), 0, dp(5)) })
                val progress = LinearProgressIndicator(this).apply { isIndeterminate = false; max = 100; setProgressCompat(pct, false); trackColor = line; setIndicatorColor(gold) }
                body.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)))
                val unscored = runCatching { draft.unscoredIds(mission).size }.getOrDefault(0)
                if (unscored > 0) body.addView(text("${faDigits(unscored.toString())} بند بدون امتیاز · تأیید بازرس ثبت شده یا باید در مرور بررسی شود", 10, false, Color.rgb(150, 96, 31)).apply { setPadding(0, dp(7), 0, 0) })
            }
            body.addView(text("تعداد تلاش‌ها: ${faDigits(item.optInt("attempts").toString())} · آخرین وضعیت: ${formatLocalTimestamp(item.optString("updated_at", item.optString("queued_at")))}", 9, false, muted).apply { setPadding(0, dp(8), 0, dp(2)) })
            if (item.optString("error").isNotBlank()) {
                body.addView(text("آخرین خطا: ${item.optString("error").take(420)}", 10, false, Color.rgb(150, 76, 50)).apply {
                    setPadding(dp(9), dp(8), dp(9), dp(8)); background = rounded(Color.rgb(252, 243, 239), 10)
                })
            }
            if (integrityError) {
                body.addView(text("این رکورد فقط‌خواندنی است؛ داده‌های رمزنگاری‌شده دست‌نخورده باقی مانده‌اند. برای بازیابی، گزارش فنی را ارسال کنید.", 11, true, Color.rgb(150, 96, 31)).apply { setPadding(0, dp(8), 0, dp(7)) })
                body.addView(makeButton("ارسال گزارش فنی خطا", false) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "گزارش خطای صف ارسال سامانه بازرسی")
                        putExtra(Intent.EXTRA_TEXT, AppDiagnostics.read(this@MainActivity))
                    }
                    runCatching { startActivity(Intent.createChooser(send, "ارسال گزارش خطا")) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            } else {
                val retry = makeButton(if (state == "failed") "تلاش مجدد برای ارسال" else "ارسال اکنون", true) { sendQueued(key) }
                retry.setIconResource(R.drawable.ic_send); retry.iconTint = ColorStateList.valueOf(Color.WHITE)
                body.addView(retry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(45)).apply { topMargin = dp(10) })
                val edit = makeButton("بازگشت به ویرایش", false) {
                    AlertDialog.Builder(this).setTitle("بازگشت گزارش به پیش‌نویس")
                        .setMessage("گزارش از صف خارج می‌شود اما پاسخ‌ها و مستندات رمزنگاری‌شده در دستگاه باقی می‌مانند.")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("بازگشت به ویرایش") { _, _ ->
                            outbox.remove(key)
                            signatures.clear(key)
                            showMission(mission)
                        }.show()
                }
                body.addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(43)).apply { topMargin = dp(6) })
            }
            panel.addView(body)
            content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        gap(5)
        label("وضعیت ارسال تنها پس از دریافت رسید سرور قطعی است. در خطاهای شبکه، پیش‌نویس، امضا و مستندات در دستگاه حفظ می‌شوند.", 10, false, muted)
        secondaryButton("بازگشت به پیش‌نویس‌ها") { showHome("drafts") }
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
                        val prepared = PhotoUploadOptimizer.prepare(attachment)
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
                store.saveDurable("receipt:$key", response.toString())
                // A local history write failure must not turn a server-confirmed success into a retry.
                runCatching { receiptHistory.save(mission, draft, response) }
                EvidenceRefs.all(draft.attachments).forEach { reference ->
                    // Keep evidence that is still referenced by an independently tracked deficiency.
                    if (!runCatching { deficiencies.isEvidenceReferenced(reference) }.getOrDefault(true)) {
                        evidenceVault.delete(reference)
                    }
                }
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

    private fun localEvidenceArchiveEntries(): List<EvidenceArchiveEntry> =
        EvidenceArchive(store, evidenceVault, deficiencies).localEntries(serverSnapshot.optJSONArray("missions") ?: JSONArray())

    private fun refreshEvidenceLibrary() {
        val device = deviceToken ?: run { notifyShort("برای دریافت مستندات سرور ابتدا وارد حساب شوید"); return }
        work({
            val bearer = sessionFor(device)
            Pair(bearer, api.getEvidenceLibrary(bearer))
        }) { (bearer, response) ->
            sessionToken = bearer
            val items = response.optJSONArray("items") ?: JSONArray()
            serverSnapshot.put("evidence_library", items)
                .put("evidence_library_synced_at", response.optString("server_time"))
            store.save("mobile_sync", serverSnapshot.toString())
            showEvidenceLibrary()
            notifyShort("${faDigits(items.length().toString())} مستند ارسال‌شده از سرور دریافت شد")
        }
    }

    private fun evidenceDateLabel(epochMillis: Long, fallback: String = "") : String {
        if (epochMillis > 0) return formatLocalTimestamp(Instant.ofEpochMilli(epochMillis).toString())
        return fallback.ifBlank { "تاریخ نامشخص" }
    }

    private fun showEvidenceLibrary() {
        selectedMission = null
        openDraft = null
        activeTab = "evidence"
        screen("مرکز مستندات", "آرشیو امن تصاویر و مدارک بازرسی", withNav = true)

        val localEntries = try { localEvidenceArchiveEntries() } catch (error: Exception) {
            AppDiagnostics.record(this, "evidence_archive_local", error)
            emptyList()
        }
        val remote = serverSnapshot.optJSONArray("evidence_library") ?: JSONArray()
        val remoteEntries = (0 until remote.length()).mapNotNull { remote.optJSONObject(it) }
        val localImages = localEntries.count { it.mime.startsWith("image/") }
        val localDocuments = localEntries.size - localImages
        val remoteImages = remoteEntries.count { it.optString("mime_type").startsWith("image/") }
        val remoteDocuments = remoteEntries.size - remoteImages

        val summary = MaterialCardView(this).apply {
            radius = dp(17).toFloat(); strokeWidth = dp(1); strokeColor = gold
            cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
        }
        val summaryBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        summaryBody.addView(text("وضعیت آرشیو مستندات", 14, true, navy))
        val metricRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun metric(value: Int, title: String, color: Int) {
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(10), dp(4), dp(5)) }
            cell.addView(text(faDigits(value.toString()), 22, true, color).apply { gravity = Gravity.CENTER })
            cell.addView(text(title, 10, false, muted).apply { gravity = Gravity.CENTER })
            metricRow.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        metric(localEntries.size, "روی گوشی", navy)
        metric(remoteEntries.size, "ارسال‌شده", Color.rgb(35, 125, 80))
        metric(localImages + remoteImages, "تصویر", Color.rgb(48, 112, 166))
        metric(localDocuments + remoteDocuments, "PDF/سند", Color.rgb(156, 111, 39))
        summaryBody.addView(metricRow)
        summary.addView(summaryBody)
        content.addView(summary)
        gap(8)
        val privacy = chip("مستندات محلی در گاوصندوق رمزنگاری‌شده نگهداری می‌شوند", Color.rgb(232, 242, 250), navy).apply {
            gravity = Gravity.CENTER; textSize = 10f
        }
        content.addView(privacy)
        gap(9)

        val search = field("جست‌وجوی نام فایل، اداره یا شاخص")
        search.setSingleLine(true)
        search.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_search, 0)
        search.compoundDrawablePadding = dp(8)
        val filters = listOf(
            "all" to "همه",
            EvidenceArchiveRules.IMAGES to "تصاویر",
            EvidenceArchiveRules.DOCUMENTS to "PDF و اسناد",
            EvidenceArchiveRules.LOCAL to "روی گوشی",
            EvidenceArchiveRules.SERVER to "ارسال‌شده"
        )
        var selectedFilter = EvidenceArchiveRules.ALL
        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val filterButtons = linkedMapOf<String, MaterialButton>()
        // Initialize before wiring filter callbacks to avoid local-function forward references.
        var renderEvidenceRows: () -> Unit = {}
        filters.forEach { (id, title) ->
            val b = makeButton(title, false) { selectedFilter = id; renderEvidenceRows() }.apply {
                textSize = 10f; minHeight = dp(40); setPadding(dp(9), 0, dp(9), 0)
            }
            filterButtons[id] = b
            filterRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply {
                marginEnd = dp(5)
            })
        }
        filterScroll.addView(filterRow)
        content.addView(filterScroll)
        gap(6)
        val count = text("", 11, false, muted)
        content.addView(count)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(results)

        fun buildRemoteRows(): List<Triple<Boolean, EvidenceArchiveEntry?, JSONObject?>> = remoteEntries.map { raw ->
            Triple(false, null, raw)
        }
        renderEvidenceRows = {
            val query = search.text?.toString()?.trim().orEmpty()
            val rows = mutableListOf<Triple<Boolean, EvidenceArchiveEntry?, JSONObject?>>()
            localEntries.forEach { rows.add(Triple(true, it, null)) }
            rows.addAll(buildRemoteRows())
            val filtered = rows.filter { (isLocal, local, server) ->
                val mime = local?.mime ?: server?.optString("mime_type", "application/octet-stream").orEmpty()
                val name = local?.name ?: server?.optString("original_name", "مستند").orEmpty()
                val organization = local?.organization ?: server?.optString("organization", "").orEmpty()
                val question = local?.questionTitle ?: server?.optString("question_title").orEmpty().ifBlank { server?.optString("question_key").orEmpty() }
                val source = local?.sourceLabel ?: "ارسال‌شده به سرور"
                EvidenceArchiveRules.matches(selectedFilter, isLocal, mime, query, listOf(name, organization, question, source))
            }
            count.text = "${faDigits(filtered.size.toString())} مستند نمایش داده می‌شود · ${faDigits(localEntries.size.toString())} محلی · ${faDigits(remoteEntries.size.toString())} سروری"
            results.removeAllViews()
            if (filtered.isEmpty()) {
                val title = if (localEntries.isEmpty() && remoteEntries.isEmpty()) "هنوز مستندی در آرشیو نیست" else "مستندی با این فیلتر پیدا نشد"
                val detail = if (deviceToken == null) "پس از ثبت مستند در فرم بازرسی، فایل در این بخش نمایش داده می‌شود." else "جست‌وجو یا فیلتر را تغییر دهید؛ برای دریافت فایل‌های ارسال‌شده، همگام‌سازی سرور را اجرا کنید."
                val empty = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(16), dp(26), dp(16), dp(26)); background = rounded(Color.WHITE, 14)
                }
                empty.addView(icon(R.drawable.ic_official_report, muted, 34))
                empty.addView(text(title, 14, true, navy).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(4)) })
                empty.addView(text(detail, 11, false, muted).apply { gravity = Gravity.CENTER })
                results.addView(empty)
            } else filtered.forEach { (isLocal, local, server) ->
                val mime = local?.mime ?: server?.optString("mime_type", "application/octet-stream").orEmpty()
                val name = local?.name ?: server?.optString("original_name", "مستند").orEmpty()
                val organization = local?.organization ?: server?.optString("organization", "دستگاه اجرایی").orEmpty()
                val question = local?.questionTitle ?: server?.optString("question_title").orEmpty().ifBlank { server?.optString("question_key").orEmpty().ifBlank { "شاخص" } }
                val size = local?.sizeBytes ?: server?.optLong("file_size", 0L) ?: 0L
                val date = local?.let { evidenceDateLabel(it.createdAtMillis) }
                    ?: server?.optString("captured_at").orEmpty().ifBlank { "تاریخ ثبت‌شده در سرور" }
                val source = local?.sourceLabel ?: "ارسال‌شده به سرور"
                val panel = MaterialCardView(this).apply {
                    radius = dp(15).toFloat(); strokeWidth = dp(1); strokeColor = line
                    cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
                }
                val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(11)) }
                val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                header.addView(iconBadge(if (mime.startsWith("image/")) R.drawable.ic_camera else R.drawable.ic_official_report, 22), LinearLayout.LayoutParams(dp(42), dp(42)))
                val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), 0, 0, 0) }
                titleBox.addView(text(name, 12, true, navy).apply { maxLines = 2 })
                titleBox.addView(text(organization, 10, false, muted).apply { maxLines = 1 })
                header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val stateChip = chip(if (isLocal) "روی گوشی" else "ارسال‌شده", if (isLocal) Color.rgb(235, 240, 248) else Color.rgb(230, 246, 237), if (isLocal) navy else Color.rgb(35, 125, 80)).apply { textSize = 9f }
                header.addView(stateChip)
                body.addView(header)
                body.addView(text("شاخص: $question", 10, false, ink).apply { setPadding(0, dp(7), 0, dp(2)); maxLines = 2 })
                body.addView(text("$source · ${EvidenceArchiveRules.formatSize(size)} · $date", 9, false, muted).apply { setPadding(0, dp(2), 0, dp(7)); maxLines = 2 })
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
                val viewButton = makeButton("مشاهده مستند", true) {
                    if (isLocal && local != null) {
                        if (local.integrityError) notifyShort("اطلاعات رمزنگاری‌شده این مستند قابل بازیابی نیست؛ فایل حذف نشده است")
                        else openAttachedEvidence(local.reference)
                    } else {
                        val id = server?.optInt("id", 0) ?: 0
                        if (id > 0) openRemoteDeficiencyEvidence(id) else notifyShort("شناسه فایل سرور معتبر نیست")
                    }
                }.apply { textSize = 10f; minHeight = dp(42) }
                actions.addView(viewButton, LinearLayout.LayoutParams(0, dp(43), 1f))
                val entryMissionKey = local?.missionKey ?: server?.optString("mission_key").orEmpty()
                if (entryMissionKey.isNotBlank()) {
                    val mission = (0 until (serverSnapshot.optJSONArray("missions")?.length() ?: 0))
                        .mapNotNull { serverSnapshot.optJSONArray("missions")?.optJSONObject(it) }
                        .firstOrNull { missionKey(it) == entryMissionKey }
                    if (mission != null && local?.questionId?.isNotBlank() == true) {
                        val questionId = local.questionId
                        val jump = makeButton("بازکردن شاخص", false) {
                            val questions = mission.optJSONObject("checklistSnapshot")?.optJSONArray("questions") ?: JSONArray()
                            var index = 0
                            for (i in 0 until questions.length()) if (questions.optJSONObject(i)?.optString("id") == questionId) { index = i; break }
                            showMission(mission, index)
                        }.apply { textSize = 10f; minHeight = dp(42) }
                        actions.addView(jump, LinearLayout.LayoutParams(0, dp(43), 1f).apply { marginStart = dp(6) })
                    }
                }
                body.addView(actions)
                panel.addView(body)
                results.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
            filterButtons.forEach { (id, button) ->
                val selected = id == selectedFilter
                button.backgroundTintList = ColorStateList.valueOf(if (selected) navy else Color.WHITE)
                button.setTextColor(if (selected) Color.WHITE else muted)
                button.strokeColor = ColorStateList.valueOf(if (selected) gold else line)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderEvidenceRows() }
            override fun afterTextChanged(s: Editable?) {}
        })
        renderEvidenceRows()
        gap(8)
        secondaryButton("همگام‌سازی مستندات ارسال‌شده") { refreshEvidenceLibrary() }
        secondaryButton("بازگشت به فرم‌های بازرسی") { showHome("missions") }
        label("حذف مستند از این آرشیو انجام نمی‌شود تا از حذف ناخواسته شواهد جلوگیری شود. مدیریت حذف فقط از محل ثبت اصلی و با تأیید کاربر انجام می‌شود.", 10, false, muted)
    }

    private fun showOfficialReports() {
        val device = deviceToken ?: run { notifyShort("برای مشاهده گزارش رسمی ابتدا وارد حساب شوید"); return }
        activeTab = "official_reports"
        screen("گزارش رسمی", "فهرست ادارات و PDFهای تأییدشده از سرور", withNav = true)
        label("در حال دریافت فهرست رسمی از سرور…", 13, false, muted)
        work({ api.get("mobile_reports.php", sessionFor(device)) }) { result ->
            screen("گزارش رسمی", "گزارش‌های خودتان و وضعیت آماده‌سازی", withNav = true)
            val reports = result.optJSONArray("reports") ?: JSONArray()
            label("${reports.length()} گزارش ثبت‌شده", 16, true, navy)
            gap(12)
            if (reports.length() == 0) emptyState("گزارشی موجود نیست", "گزارش‌ها پس از ثبت و تأیید مدیر در این بخش قابل دریافت هستند.")
            for (i in 0 until reports.length()) {
                val report = reports.optJSONObject(i) ?: continue
                val id = report.optInt("id", 0)
                if (id <= 0) continue
                val panel = MaterialCardView(this).apply {
                    radius = dp(15).toFloat(); strokeWidth = dp(1); strokeColor = Color.rgb(211, 184, 124)
                    cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
                }
                val document = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(15), dp(15), dp(15))
                }
                val documentHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                }
                documentHeader.addView(icon(R.drawable.ic_shield, gold, 28), LinearLayout.LayoutParams(dp(36), dp(36)))
                val institution = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, 0, 0) }
                institution.addView(text("فرمانداری شهرستان جوانرود", 11, true, navy))
                institution.addView(text("سامانه بازرسی ادارات", 9, false, muted))
                documentHeader.addView(institution, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                document.addView(documentHeader)
                val goldRule = View(this).apply { setBackgroundColor(gold) }
                document.addView(goldRule, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(10) })
                document.addView(text("گزارش نتیجه بازرسی", 15, true, navy).apply { gravity = Gravity.CENTER })
                document.addView(text(report.optString("org_name", "دستگاه اجرایی"), 13, true, ink).apply {
                    gravity = Gravity.CENTER; setPadding(0, dp(9), 0, dp(8))
                })
                document.addView(text("شماره رسید: ${report.optString("receipt_code")}", 11, false, muted))
                document.addView(text("تاریخ ثبت: ${faDigits(report.optString("submitted_at"))}", 11, false, muted))
                panel.addView(document)
                val status = report.optString("evaluation_status", "legacy")
                val scored = report.optInt("answered_count", 0)
                val total = report.optInt("question_count", 0)
                if (total > 0) {
                    document.addView(text("پوشش ارزیابی: $scored از $total بند · " +
                        if (status == "partial") "دارای بندهای بدون امتیاز" else "کامل", 12, false,
                        if (status == "partial") Color.rgb(150, 96, 31) else muted))
                }
                val available = report.optBoolean("official_ready", false)
                document.addView(text(if (available) "گزارش رسمی قابل دریافت است" else "در انتظار تأیید مدیر یا آماده‌سازی PDF", 12, true, if (available) Color.rgb(25,124,79) else muted))
                if (available) {
                    document.addView(makeButton("دانلود PDF خلاصه مدیریتی", true) {
                        pendingOfficialPdf = id to "summary"
                        officialPdfPicker.launch("BAZR-$id-summary.pdf")
                    })
                    document.addView(makeButton("دانلود PDF تفصیلی همه بندها", false) {
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
        screen("سوابق بازرسی", "گزارش‌های ثبت‌شده و وضعیت بررسی مدیر", withNav = true)
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
            val integrityError = report.optBoolean("integrity_error")
            val status = when {
                integrityError -> "نسخه محلی رسید قابل بازیابی نیست؛ وضعیت را از سرور همگام کنید"
                report.optString("review_status") in setOf("approved", "accepted") -> "تأیید نهایی مدیریت ثبت شده"
                report.optString("review_status") == "supervisor_approved" -> "تأیید سربازرس؛ در انتظار مدیریت"
                report.optString("review_status") in setOf("admin_review", "pending_review") -> "در انتظار تأیید سربازرس"
                report.optString("review_status") in setOf("rejected", "returned") -> "برای اصلاح به بازرس برگشت داده شد"
                else -> if (fromServer) "وضعیت بررسی ثبت نشده" else "رسید ذخیره‌شده محلی"
            }
            inner.addView(text(status, 12, true, if (integrityError) Color.rgb(163, 112, 42) else teal))
            if (!integrityError) inner.addView(makeButton("مشاهده جزئیات", false) { showHistoryDetail(report, fromServer, organization) })
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
        val localCopy = receiptHistory.find(key)
        val detail = localCopy?.takeIf { copy ->
            val localCode = copy.optString("receipt_code")
            localCode.isNotBlank() && localCode == report.optString("receipt_code")
        }
        if (detail == null && localCopy?.optBoolean("integrity_error") == true) {
            gap(12)
            val integrityMessage = localCopy.optString("integrity_message", "نسخه محلی رسید قابل بازیابی نیست")
            label("$integrityMessage. وضعیت معتبر گزارش را از سرور همگام کنید.", 13, false, Color.rgb(163, 112, 42))
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

    private fun noticeUnreadCount(): Int {
        val notices = serverSnapshot.optJSONArray("notices") ?: JSONArray()
        return (0 until notices.length()).count { index ->
            val item = notices.optJSONObject(index) ?: return@count false
            !item.optBoolean("is_read", item.optBoolean("read", false))
        }
    }

    private fun noticeKey(notice: JSONObject): String =
        notice.optString("notice_key").ifBlank {
            notice.optString("_server_key").ifBlank { notice.optString("id") }
        }

    private fun setNoticeReadLocally(key: String) {
        if (key.isBlank()) return
        val notices = serverSnapshot.optJSONArray("notices") ?: JSONArray()
        for (i in 0 until notices.length()) {
            val item = notices.optJSONObject(i) ?: continue
            if (noticeKey(item) == key || item.optString("id") == key) {
                item.put("is_read", true)
                    .put("read_at", Instant.now().toString())
            }
        }
        serverSnapshot.put("notices", notices)
        serverSnapshot.put("notice_unread_count", noticeUnreadCount())
        runCatching { store.saveDurable("mobile_sync", serverSnapshot.toString()) }
    }

    private fun queueNoticeRead(key: String) {
        if (key.isBlank()) return
        val saved = runCatching { store.loadStrict("pending_notice_reads") }.getOrNull()
        val ids = linkedSetOf<String>()
        runCatching { JSONArray(saved ?: "[]") }.getOrNull()?.let { array ->
            for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(ids::add)
        }
        ids.add(key)
        runCatching { store.saveDurable("pending_notice_reads", JSONArray(ids.toList().takeLast(400)).toString()) }
    }

    private fun removeQueuedNoticeRead(key: String) {
        val raw = runCatching { store.loadStrict("pending_notice_reads") }.getOrNull() ?: return
        val pending = runCatching { JSONArray(raw) }.getOrNull() ?: return
        val remaining = (0 until pending.length()).map { pending.optString(it) }
            .filter { it.isNotBlank() && it != key }
        runCatching { store.saveDurable("pending_notice_reads", JSONArray(remaining).toString()) }
    }

    private fun syncPendingNoticeReads(session: String): Int {
        val raw = runCatching { store.loadStrict("pending_notice_reads") }.getOrNull() ?: return 0
        val pending = runCatching { JSONArray(raw) }.getOrElse { return 0 }
        val remaining = mutableListOf<String>()
        var synced = 0
        for (i in 0 until pending.length()) {
            val key = pending.optString(i).takeIf { it.isNotBlank() } ?: continue
            try {
                api.post("mobile_notice_read.php",
                    JSONObject().put("action", "mark_read").put("notice_key", key), session)
                synced++
            } catch (_: Exception) {
                remaining.add(key)
            }
        }
        runCatching { store.saveDurable("pending_notice_reads", JSONArray(remaining).toString()) }
        return synced
    }

    private fun markNoticeRead(key: String, after: () -> Unit) {
        if (key.isBlank()) { after(); return }
        val notices = serverSnapshot.optJSONArray("notices") ?: JSONArray()
        val alreadyRead = (0 until notices.length()).mapNotNull { notices.optJSONObject(it) }
            .firstOrNull { noticeKey(it) == key || it.optString("id") == key }
            ?.optBoolean("is_read", false) == true
        if (alreadyRead) { after(); return }

        // Optimistic local receipt is durable; server receipt is retried if offline or busy.
        queueNoticeRead(key)
        setNoticeReadLocally(key)
        after()

        val device = deviceToken
        if (device == null || !online() || busy) {
            if (!online()) notifyShort("ابلاغیه خوانده شد؛ رسید آن پس از اتصال همگام می‌شود")
            return
        }
        io.execute {
            if (deviceToken != device) return@execute
            try {
                val bearer = sessionToken ?: sessionFor(device)
                api.post("mobile_notice_read.php",
                    JSONObject().put("action", "mark_read").put("notice_key", key), bearer)
                removeQueuedNoticeRead(key)
            } catch (_: Exception) {
                // Keep the encrypted local queue entry; connectAndSync retries it later.
            }
        }
    }

    private fun markAllNoticesRead() {
        val notices = serverSnapshot.optJSONArray("notices") ?: JSONArray()
        val keys = (0 until notices.length()).mapNotNull { notices.optJSONObject(it) }
            .filter { !it.optBoolean("is_read", it.optBoolean("read", false)) }
            .map(::noticeKey).filter { it.isNotBlank() }
        if (keys.isEmpty()) { notifyShort("همه ابلاغیه‌ها قبلاً خوانده شده‌اند"); return }
        val device = deviceToken
        if (device == null || !online() || busy) {
            keys.forEach(::queueNoticeRead)
            keys.forEach(::setNoticeReadLocally)
            showHome("notices")
            notifyShort("وضعیت خواندن پس از اتصال به سرور همگام می‌شود")
            return
        }
        work({
            val bearer = sessionToken ?: sessionFor(device)
            api.post("mobile_notice_read.php", JSONObject().put("action", "read_all"), bearer)
        }) {
            keys.forEach(::setNoticeReadLocally)
            showHome("notices")
            notifyShort("همه ابلاغیه‌ها خوانده‌شده ثبت شدند")
        }
    }

    private fun openNoticeTarget(notice: JSONObject) {
        val refType = notice.optString("ref_type", notice.optString("refType"))
        val refId = notice.optString("ref_id", notice.optString("refId"))
        val targetMissionKey = notice.optString("mission_key", notice.optString("missionKey"))
        when (refType) {
            "mission" -> {
                val missions = serverSnapshot.optJSONArray("missions") ?: JSONArray()
                val mission = (0 until missions.length()).mapNotNull { missions.optJSONObject(it) }
                    .firstOrNull { missionKey(it) == targetMissionKey || missionKey(it) == refId || it.optString("id") == refId }
                if (mission != null) showMission(mission) else {
                    showHome("missions")
                    notifyShort("مأموریت مرتبط در آخرین همگام‌سازی موجود نیست")
                }
            }
            "deficiency", "corrective_action" -> {
                val record = runCatching { deficiencies.get(refId) }.getOrNull()
                    ?: deficiencies.entries().firstOrNull {
                        it.optString("server_id") == refId || it.optString("id") == refId
                    }
                if (record != null) showDeficiencyDetail(record) else {
                    showHome("deficiencies")
                    notifyShort("نقص مرتبط در حافظه فعلی پیدا نشد")
                }
            }
            "report" -> showHistory()
            else -> notifyShort("این ابلاغیه پیوند مستقیمی به یک صفحه عملیاتی ندارد")
        }
    }

    private fun showNoticeDetail(notice: JSONObject) {
        val title = notice.optString("title", "ابلاغیه سازمانی")
        val body = notice.optString("message", notice.optString("body", ""))
        val severity = when (notice.optString("severity")) {
            "critical" -> "فوری"
            "important" -> "مهم"
            else -> "عادی"
        }
        val sender = notice.optString("sender_name", "مدیریت سامانه")
        val created = notice.optString("created_at", notice.optString("date"))
        val refType = notice.optString("ref_type", notice.optString("refType"))
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("$body\n\nاولویت: $severity\nفرستنده: $sender" +
                if (created.isNotBlank()) "\nزمان: $created" else "")
            .setPositiveButton(if (refType.isNotBlank()) "بازکردن مورد مرتبط" else "بستن") { _, _ ->
                if (refType.isNotBlank()) openNoticeTarget(notice)
            }
        if (refType.isNotBlank()) builder.setNegativeButton("بستن", null)
        builder.show()
    }

    private fun showNotices() {
        selectedMission = null
        openDraft = null
        activeTab = "notices"
        screen("صندوق ابلاغیه‌ها", "پیام‌ها و ابلاغیه‌های اختصاصی سازمان", withNav = true)

        val notices = serverSnapshot.optJSONArray("notices") ?: JSONArray()
        val unread = noticeUnreadCount()
        val urgent = (0 until notices.length()).count {
            notices.optJSONObject(it)?.let { n ->
                !n.optBoolean("is_read") && n.optString("severity") == "critical"
            } == true
        }
        val summary = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); strokeWidth = dp(1); strokeColor = gold
            cardElevation = dp(1).toFloat(); setCardBackgroundColor(Color.WHITE)
        }
        val summaryBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        summaryBody.addView(text("وضعیت ابلاغیه‌های شما", 14, true, navy))
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        fun noticeMetric(value: Int, title: String, color: Int) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = rounded(Color.rgb(244, 247, 250), 11)
                setPadding(dp(5), dp(8), dp(5), dp(8))
            }
            cell.addView(text(faDigits(value.toString()), 20, true, color).apply { gravity = Gravity.CENTER })
            cell.addView(text(title, 9, true, muted).apply { gravity = Gravity.CENTER })
            metrics.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        }
        noticeMetric(notices.length(), "کل ابلاغیه‌ها", navy)
        noticeMetric(unread, "خوانده‌نشده", Color.rgb(163, 112, 42))
        noticeMetric(urgent, "فوری", Color.rgb(164, 61, 49))
        summaryBody.addView(metrics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10)
        })
        summary.addView(summaryBody)
        content.addView(summary)
        gap(10)

        goldButton("همگام‌سازی ابلاغیه‌ها") {
            if (deviceToken == null) notifyShort("برای دریافت ابلاغیه‌ها ابتدا وارد حساب بازرس شوید")
            else connectAndSync()
        }
        if (store.getString("notify_notices_enabled") != "yes") {
            secondaryButton("فعال‌سازی اعلان‌های پس‌زمینه") { enableMissionNotifications() }
        } else {
            secondaryButton("مدیریت مجوز اعلان‌ها") { enableMissionNotifications() }
            secondaryButton("غیرفعال‌سازی اعلان‌های پس‌زمینه") { disableNoticeNotifications() }
        }
        if (unread > 0) secondaryButton("علامت‌گذاری همه به‌عنوان خوانده‌شده") { markAllNoticesRead() }
        gap(8)

        if (notices.length() == 0) {
            emptyState("ابلاغیه‌ای موجود نیست", "با همگام‌سازی، پیام‌های اختصاصی بازرس و ابلاغیه‌های سازمانی در این بخش نمایش داده می‌شوند.")
        } else {
            for (i in 0 until notices.length()) {
                val notice = notices.optJSONObject(i) ?: continue
                val isRead = notice.optBoolean("is_read", notice.optBoolean("read", false))
                val severity = notice.optString("severity", "info")
                val severityLabel = when (severity) {
                    "critical" -> "فوری"
                    "important" -> "مهم"
                    else -> "عادی"
                }
                val severityColor = when (severity) {
                    "critical" -> Color.rgb(164, 61, 49)
                    "important" -> Color.rgb(153, 103, 35)
                    else -> Color.rgb(49, 103, 145)
                }
                val card = MaterialCardView(this).apply {
                    radius = dp(15).toFloat()
                    strokeWidth = dp(if (isRead) 1 else 2)
                    strokeColor = if (isRead) line else gold
                    cardElevation = if (isRead) 0f else dp(2).toFloat()
                    setCardBackgroundColor(Color.WHITE)
                    isClickable = true; isFocusable = true
                }
                val body = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                }
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                    gravity = Gravity.CENTER_VERTICAL
                }
                header.addView(chip(severityLabel, Color.rgb(250, 243, 230), severityColor))
                header.addView(text(if (isRead) "خوانده‌شده" else "جدید", 10, true,
                    if (isRead) muted else Color.rgb(155, 105, 32)).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(8), 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                body.addView(header)
                body.addView(text(notice.optString("title", "ابلاغیه سازمانی"), 14, true, navy).apply {
                    setPadding(0, dp(9), 0, dp(5))
                })
                val message = notice.optString("message", notice.optString("body", ""))
                if (message.isNotBlank()) body.addView(text(message.take(900), 11, false, ink).apply { maxLines = 4 })
                val created = notice.optString("created_at", notice.optString("date"))
                if (created.isNotBlank()) body.addView(text(created, 9, false, muted).apply { setPadding(0, dp(7), 0, 0) })
                card.addView(body)
                card.setOnClickListener {
                    val key = noticeKey(notice)
                    markNoticeRead(key) { showHome("notices"); showNoticeDetail(notice) }
                }
                content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(9) })
            }
        }
        val pendingId = pendingNoticeId
        if (!pendingId.isNullOrBlank()) {
            val target = (0 until notices.length()).mapNotNull { notices.optJSONObject(it) }
                .firstOrNull { noticeKey(it) == pendingId || it.optString("id") == pendingId }
            if (target != null) {
                pendingNoticeId = null
                markNoticeRead(noticeKey(target)) { showHome("notices"); showNoticeDetail(target) }
            }
        }
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
        screen("تأیید هویت", "برای ورود امن، هویت خود را تأیید کنید", withNav = false)
        val panel = MaterialCardView(this).apply {
            radius = dp(22).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = dp(2).toFloat(); setCardBackgroundColor(Color.WHITE)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(22), dp(25), dp(22), dp(24))
        }
        val fingerprint = MaterialCardView(this).apply {
            radius = dp(42).toFloat(); strokeWidth = dp(2); strokeColor = gold
            cardElevation = dp(3).toFloat(); setCardBackgroundColor(Color.rgb(247, 242, 230))
        }
        fingerprint.addView(icon(R.drawable.ic_fingerprint, navy, 54),
            FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))
        body.addView(fingerprint, LinearLayout.LayoutParams(dp(92), dp(92)).apply { bottomMargin = dp(17) })
        body.addView(text("تأیید هویت", 22, true, navy).apply { gravity = Gravity.CENTER })
        body.addView(text("برای ورود امن، اثر انگشت خود را تأیید کنید.", 13, false, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(18))
        })
        panel.addView(body)
        content.addView(panel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(18)
        button("تأیید و ورود") {
            if (!biometricsAvailable()) { notifyShort("اثر انگشت قوی در تنظیمات دستگاه فعال نیست"); return@button }
            try {
                val cipher = store.bioDecryptCipher() ?: error("token missing")
                biometricPrompt("ورود با اثر انگشت", cipher) { authorized ->
                    try {
                        deviceToken = store.decryptBio(authorized)
                        store.saveDurable("notification_device_token", deviceToken!!)
                        val requestedTab = if (intent?.getStringExtra("open_tab") == "notices") "notices" else "home"
                        showHome(requestedTab)
                        connectAndSync()
                    }
                    catch (_: Exception) { notifyShort("کلید دستگاه نامعتبر است؛ ورود مجدد لازم است"); showLogin() }
                }
            } catch (_: Exception) { notifyShort("اطلاعات بیومتریک نیازمند فعال‌سازی مجدد است"); showLogin() }
        }
        secondaryButton("بازگشت به ورود با رمز عبور") { showLogin() }
    }
    private fun confirmLogout() {
        AlertDialog.Builder(this).setTitle("خروج")
            .setMessage("از حساب خارج می‌شوید. پیش‌نویس‌های ذخیره‌شده روی دستگاه حذف نمی‌شوند. برای حذف کامل داده‌ها باید از تنظیمات اندروید اقدام کنید.")
            .setNegativeButton("انصراف",null)
            .setPositiveButton("خروج") { _,_ ->
                deviceToken=null;sessionToken=null
                store.clearDurable("device_token");store.clearDurable("notification_device_token");store.clearBio()
                store.clearDurable("pending_notice_reads");store.clearDurable("notice_notified_ids")
                NoticePollingScheduler.cancel(this)
                store.putString("notify_notices_enabled", "no")
                store.removeStringDurable("request_id")
                store.clearDurable("mobile_sync")
                store.removeStringDurable("last_sync")
                serverSnapshot = JSONObject()
                showLogin()
            }.show()
    }
}
