# اصلاح ساخت نسخه 4.13.2

## علت شکست Workflow نسخه 4.13.1
در لاگ `logs_114603763772.zip` چهار خطای Kotlin در `MainActivity.kt` دیده شد:
- خط 354: `statusBarContrastEnforced`
- خط 355: `navigationBarContrastEnforced`
- خط 382: `statusBarContrastEnforced`
- خط 383: `navigationBarContrastEnforced`

Task شکست‌خورده: `:app:compileDebugKotlin`.
به علت شکست کامپایل، APK تولید نشد و مرحله upload نیز فایل خروجی پیدا نکرد.

## اصلاح انجام‌شده
در هر دو تابع `applyShellInsets` و `applySplashInsets` دسترسی property با فراخوانی مستقیم setterهای Java جایگزین شد:
- `window.setStatusBarContrastEnforced(false)`
- `window.setNavigationBarContrastEnforced(false)`

فراخوانی‌ها در شرط `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q` قرار دارند. مستندات رسمی Android هر دو setter را از API 29 معرفی می‌کند و پروژه حداقل API 30 دارد.

## تغییر نسخه
- versionCode: 24
- versionName: 4.13.2
- Artifact: `bazr-inspector-4.13.2-apk`

## اقدام کاربر
1. محتوای ZIP کامل را در ریشه مخزن GitHub جایگزین کنید.
2. مطمئن شوید `BAZR_API_BASE_URL` در Repository Variables تنظیم است.
3. از Actions، Workflow ساخت Android را اجرا کنید.
4. در صورت شکست، لاگ جدید را ارسال کنید؛ در صورت موفقیت، APK در بخش Artifacts همان اجرای Workflow قرار می‌گیرد.

## محدودیت اعتبارسنجی این بسته
در محیط تهیه این بسته Android SDK و Gradle نصب نیست؛ بنابراین این اصلاح از نظر کد و ارجاع API کنترل شده، اما ادعای کامپایل موفق یا APK ساخته‌شده نمی‌شود. نتیجه نهایی کامپایل باید توسط GitHub Actions تأیید شود.
