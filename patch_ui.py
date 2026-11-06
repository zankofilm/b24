from pathlib import Path
import re
p=Path('/mnt/data/bazr_work/app/src/main/java/ir/bazr/inspection/MainActivity.kt')
s=p.read_text()

# Palette tuning for the approved light dashboard.
s=s.replace('private val pageBg = Color.rgb(245, 247, 250)', 'private val pageBg = Color.rgb(243, 246, 250)')
s=s.replace('private val line = Color.rgb(220, 227, 234)', 'private val line = Color.rgb(216, 225, 235)')

# Replace screen shell.
start=s.index('    /** Native app shell: fixed toolbar, scrollable content and native bottom navigation. */')
end=s.index('    private fun showAppMenu() {', start)
new_screen='''    /** Native app shell: approved white institutional header, scrollable content and light bottom navigation. */
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
        shell.addView(appTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(74)))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(17), dp(18), dp(26))
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

    /** Approved 4.2 toolbar: white surface, gold shield and a compact role chip. */
    private fun appTopBar(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(8), dp(16), dp(8))
        }
        val mark = MaterialCardView(this).apply {
            radius = dp(13).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = 0f
            setCardBackgroundColor(navy)
        }
        mark.addView(icon(R.drawable.ic_shield, gold, 25),
            FrameLayout.LayoutParams(dp(29), dp(29), Gravity.CENTER))
        row.addView(mark, LinearLayout.LayoutParams(dp(44), dp(44)))

        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
        }
        names.addView(text("سامانه بازرسی ادارات", 16, true, navy))
        names.addView(text("فرمانداری شهرستان جوانرود", 10, false, muted))
        row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val role = text("بازرس", 12, true, navy).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(7), dp(13), dp(7))
            background = rounded(Color.rgb(249, 244, 232), 11)
            isClickable = true
            isFocusable = true
            contentDescription = "باز کردن منوی برنامه"
            setOnClickListener { showAppMenu() }
        }
        row.addView(role, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        wrapper.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(73)))
        wrapper.addView(View(this).apply { setBackgroundColor(Color.rgb(226, 205, 160)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        return wrapper
    }

'''
s=s[:start]+new_screen+s[end:]

# Replace bottom navigation.
start=s.index('    private fun bottomNavigation(): View {')
end=s.index('    private fun statCard(', start)
new_bottom='''    /** Four primary destinations from the approved mobile reference; extra roles stay in the role menu. */
    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(5), dp(8), dp(6))
            elevation = dp(7).toFloat()
        }
        val items = listOf(
            Triple("home", "خانه", R.drawable.ic_home),
            Triple("missions", "مأموریت‌ها", R.drawable.ic_clipboard),
            Triple("drafts", "پیش‌نویس‌ها", R.drawable.ic_notes),
            Triple("account", "حساب", R.drawable.ic_person)
        )
        for ((id, caption, drawable) in items) {
            val chosen = activeTab == id
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(2), dp(2), dp(1))
                isClickable = true
                isFocusable = true
                contentDescription = caption
                setOnClickListener { if (!busy) showHome(id) }
            }
            val iconHolder = FrameLayout(this).apply {
                background = if (chosen) rounded(Color.rgb(249, 244, 232), 11) else rounded(Color.TRANSPARENT, 11)
                addView(icon(drawable, if (chosen) navy else muted, 22),
                    FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
            }
            item.addView(iconHolder, LinearLayout.LayoutParams(dp(42), dp(34)))
            val captionView = text(caption, 10, chosen, if (chosen) navy else muted)
            captionView.gravity = Gravity.CENTER
            item.addView(captionView)
            bar.addView(item, LinearLayout.LayoutParams(0, dp(59), 1f))
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

'''
s=s[:start]+new_bottom+s[end:]

# Replace stat card.
start=s.index('    private fun statCard(')
end=s.index('    private fun statsRow(', start)
new_stat='''    private fun statCard(number: String, caption: String, drawable: Int, background: Int, foreground: Int): View {
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
            background = rounded(background, 12)
            addView(icon(drawable, foreground, 22),
                FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
        }
        inner.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)))
        inner.addView(text(number, 25, true, navy).apply {
            setPadding(0, dp(5), 0, 0)
        })
        inner.addView(text(caption, 12, false, muted))
        outer.addView(inner, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return outer
    }
'''
s=s[:start]+new_stat+s[end:]

# Replace hero.
start=s.index('    private fun hero(profileName: String) {')
end=s.index('    private fun quickActionCard(', start)
new_hero='''    /** Plain navy welcome card from the approved 4.2 reference; no photographic banner. */
    private fun hero(profileName: String) {
        val card = MaterialCardView(this).apply {
            radius = dp(19).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = 0f
            setCardBackgroundColor(navy)
        }
        val frame = FrameLayout(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        copy.addView(text("خوش آمدید", 13, true, Color.rgb(235, 190, 91)))
        copy.addView(text("بازرس گرامی", 21, true, Color.WHITE).apply {
            setPadding(0, dp(5), 0, dp(3))
        })
        copy.addView(text("مأموریت‌های محوله را از این بخش مدیریت کنید", 11, false, Color.rgb(220, 230, 240)))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val emblem = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            strokeWidth = dp(1)
            strokeColor = gold
            cardElevation = 0f
            setCardBackgroundColor(Color.rgb(10, 39, 67))
        }
        emblem.addView(icon(R.drawable.ic_shield, gold, 30),
            FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        row.addView(emblem, LinearLayout.LayoutParams(dp(58), dp(58)).apply { marginStart = dp(12) })
        frame.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))
        frame.addView(View(this).apply {
            background = rounded(gold, 6)
        }, FrameLayout.LayoutParams(dp(6), dp(76), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply {
            rightMargin = dp(10)
        })
        card.addView(frame)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))
        gap(12)
    }

'''
s=s[:start]+new_hero+s[end:]

# Replace authScreen with the same white institutional shell.
start=s.index('    /** Branded authentication shell; native controls remain independent of the artwork. */')
end=s.index('    private fun showSplash(', start)
new_auth='''    /** Login and biometric screens use the same approved light native shell as the dashboard. */
    private fun authScreen(): LinearLayout = screen("", "", withNav = false)

'''
s=s[:start]+new_auth+s[end:]

# Change password field icon and neutral outline.
s=s.replace('boxStrokeColor = gold', 'boxStrokeColor = line')
s=s.replace('startIconDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_shield)', 'startIconDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_lock)')

# Replace showLogin UI prefix and remove old chevron decoration.
s=s.replace('''        authScreen()
        label("ورود به سامانه", 23, true, navy)
        label("لطفاً اطلاعات کاربری خود را وارد کنید", 13, false, muted)
        gap(8)
''','''        screen("ورود بازرس", "دسترسی امن به مأموریت‌های سازمانی", withNav = false)
        val intro = MaterialCardView(this).apply {
            radius = dp(17).toFloat(); strokeWidth = dp(1); strokeColor = line
            cardElevation = 0f; setCardBackgroundColor(Color.WHITE)
        }
        val introRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        val introCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        introCopy.addView(text("ورود به میز کار بازرس", 16, true, navy))
        introCopy.addView(text("ثبت و پیگیری مأموریت‌های محوله", 11, false, muted).apply { setPadding(0, dp(4), 0, 0) })
        introRow.addView(introCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val introMark = MaterialCardView(this).apply {
            radius = dp(13).toFloat(); strokeWidth = dp(1); strokeColor = gold
            cardElevation = 0f; setCardBackgroundColor(navy)
        }
        introMark.addView(icon(R.drawable.ic_shield, gold, 25), FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
        introRow.addView(introMark, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginStart = dp(10) })
        intro.addView(introRow)
        content.addView(intro, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gap(16)
        sectionTitle("اطلاعات ورود")
''')
s=s.replace('''        login.setIconResource(R.drawable.ic_chevron)
        login.iconTint = ColorStateList.valueOf(Color.rgb(246, 196, 76))
        login.iconPadding = dp(12)
        login.iconGravity = MaterialButton.ICON_GRAVITY_END
        gap(5)
''','''        gap(5)
''')

# Replace security note section to match the reference card, keeping username remembrance available.
start_note = s.index('        val securityNote = LinearLayout(this).apply {', s.index('    private fun showLogin()'))
end_note = s.index('        if (store.getString("request_id") != null)', start_note)
new_note = '''        val securityNote = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(Color.rgb(235, 241, 247), 13)
        }
        val securityCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        securityCopy.addView(text("امنیت حساب", 13, true, navy))
        securityCopy.addView(text("رمز عبور روی دستگاه ذخیره نمی‌شود.", 10, false, muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        securityNote.addView(securityCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        securityNote.addView(icon(R.drawable.ic_shield, navy, 22))
        content.addView(securityNote)
'''
s = s[:start_note] + new_note + s[end_note:]

# Make biometric lock page use the approved heading and a clean centered panel.
s=s.replace('''    private fun showBiometricLock() {
        authScreen()
        val panel = MaterialCardView(this).apply {
''','''    private fun showBiometricLock() {
        screen("تأیید هویت", "برای ورود امن، هویت خود را تأیید کنید", withNav = false)
        val panel = MaterialCardView(this).apply {
''')

# Replace the home dashboard branch only.
start=s.index('            "home" -> {', s.index('    private fun showHome('))
end=s.index('            "supervisor" -> showSupervisorInbox()', start)
new_home='''            "home" -> {
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
                statsRow(
                    statCard(available.size.toString(), "قابل انجام", R.drawable.ic_check, Color.rgb(232, 245, 238), Color.rgb(38, 126, 86)),
                    statCard(all.size.toString(), "کل مأموریت‌ها", R.drawable.ic_clipboard, Color.rgb(234, 241, 249), navy)
                )
                statsRow(
                    statCard(drafted.size.toString(), "پیش‌نویس‌ها", R.drawable.ic_notes, Color.rgb(242, 237, 249), Color.rgb(112, 83, 154)),
                    statCard(reportCount().toString(), "گزارش ارسال‌شده", R.drawable.ic_chart, Color.rgb(250, 243, 229), Color.rgb(163, 112, 42))
                )
                gap(5)
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
'''
s=s[:start]+new_home+s[end:]

# Add short sync label and dashboard mission card before lastSyncDescription.
marker='''    private fun lastSyncDescription(): String {
'''
insert='''    private fun lastSyncShort(): String {
        val raw = store.getString("last_sync") ?: return "هنوز همگام‌سازی نشده"
        return try {
            val time = Instant.parse(raw).atZone(ZoneId.systemDefault())
            "آخرین همگام‌سازی: " + DateTimeFormatter.ofPattern("HH:mm", Locale("fa")).format(time)
        } catch (_: Exception) { "سوابق همگام‌سازی موجود" }
    }

    private fun dashboardMissionItem(mission: JSONObject) {
        val key = missionKey(mission)
        val name = mission.optString("orgName", "دستگاه اجرایی").ifBlank { "دستگاه اجرایی" }
        val date = mission.optString("date", "").trim()
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

'''
if marker not in s:
    raise SystemExit('lastSyncDescription marker not found')
s=s.replace(marker,insert+marker,1)

p.write_text(s)
print('patched',p,'bytes',p.stat().st_size)
