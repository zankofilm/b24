# گزارش کنترل نسخه 4.14.2

## اصلاحات و کنترل‌های اجراشده

- خطای Kotlin گزارش‌شده در `MainActivity.kt:1965` اصلاح شد.
- کنترل رگرسیون به `tools/verify_android_source.py` افزوده شد تا استفاده مستقیم از `entries.takeLast()` را رد کند.
- `versionName` روی `4.14.2` و `versionCode` روی `27` تنظیم شد.
- نام Artifact در GitHub Actions به `bazr-inspector-4.14.2-apk` تغییر کرد.
- 26 فایل XML با XML parser بررسی شدند؛ خطای ساختاری یافت نشد.
- 21 ارجاع Drawable با منابع پروژه تطبیق داده شدند؛ منبع مفقود مشاهده نشد.
- منابع تصویری ضروری طراحی ورود موجود هستند.
- اسکریپت `tools/verify_android_source.py` اجرا شد و موفق بود.
- فایل Workflow با YAML parser بررسی شد و قابل Parse بود.
- نمونه مستقل Kotlin برای تبدیل `entries` به List و انتخاب `takeLast` کامپایل و اجرا شد.

## محدودیت مهم

در محیط آماده‌سازی، Android SDK و Gradle اجرایی در دسترس نبودند و پروژه فاقد Gradle Wrapper است؛ بنابراین کامپایل کامل Android، اجرای JUnit از طریق Gradle، ساخت APK و تست روی گوشی در این محیط انجام نشده است. تنها GitHub Actions می‌تواند تأیید نهایی کامپایل و تولید APK را برای این مخزن انجام دهد.

## مراحل تأیید نهایی

1. ZIP کامل نسخه 4.14.2 را در ریشه مخزن GitHub جایگزین کنید.
2. Repository Variable با نام `BAZR_API_BASE_URL` را با URL واقعی HTTPS و اسلش انتهایی نگه دارید.
3. Workflow را از مسیر Actions اجرا کنید.
4. ابتدا موفقیت `:app:testDebugUnitTest` و سپس `:app:assembleDebug` یا `:app:assembleRelease` را کنترل کنید.
5. APK را روی دستگاه آزمایشی نصب و ورود، اثر انگشت، مأموریت، فرم، ذخیره پیش‌نویس، امضا، ارسال، گزارش و PDF را بررسی کنید.

## وضعیت

**اصلاح خطای مشخص‌شده و کنترل‌های ایستا: موفق**  
**Build کامل APK و تست دستگاه: در انتظار اجرای مجدد GitHub Actions**
