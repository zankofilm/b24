# اصلاحیه ساخت نسخه ۴.۱۲.۱ — Build Fix 1

## علت شکست قبلی
در لاگ GitHub Actions، مرحله `:app:compileDebugKotlin` در فایل زیر شکست خورده بود:

`app/src/main/java/ir/bazr/inspection/MainActivity.kt:629`

پارامتر تابع `statCard` با نام `background` از نوع `Int` تعریف شده بود و داخل `FrameLayout.apply` عبارت `background = rounded(background, 12)` به‌علت تداخل نام، به پارامتر اشاره می‌کرد؛ در نتیجه کامپایلر انتساب `GradientDrawable` به `Int` را گزارش می‌کرد.

## اصلاح اعمال‌شده
عبارت قبلی:

```kotlin
background = rounded(background, 12)
```

به عبارت صریح زیر تغییر کرد:

```kotlin
setBackground(rounded(background, 12))
```

همچنین `versionCode` به ۲۱ و `versionName` به `4.12.1.1-native-ui-reference-fix1` افزایش یافت.

## وضعیت اعتبارسنجی
- ZIP و CRC فایل‌ها: بررسی‌شده
- XML منابع: ۲۴ فایل، بدون خطای parse
- ارجاع‌های drawable: ۱۹ مورد، بدون مرجع مفقود
- خطای Kotlin ثبت‌شده در لاگ: اصلاح‌شده در سورس
- اجرای مجدد Gradle/ساخت APK: باید در GitHub Actions انجام شود؛ در محیط تهیه این اصلاحیه Android SDK و Gradle نصب نیست.

## اقدام در GitHub
محتویات ZIP کامل را در ریشه مخزن جایگزین کنید، سپس از مسیر Actions، workflow با نام `Build BAZR Inspector Android (debug)` را اجرا کنید. پس از موفقیت، Artifact با نام `bazr-inspector-debug` قابل دریافت است.
