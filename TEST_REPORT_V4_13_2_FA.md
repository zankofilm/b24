# گزارش کنترل نسخه 4.13.2

## کنترل‌های انجام‌شده روی بسته
- هر چهار دسترسی property گزارش‌شده در لاگ از `MainActivity.kt` حذف شدند.
- هر دو setter صریح در هر دو تابع `applyShellInsets` و `applySplashInsets` وجود دارند.
- شرط حداقل API 29 برای setterها اضافه شد؛ `minSdk` فعلی 30 است.
- `versionCode=24` و `versionName=4.13.2` کنترل شد.
- نام Artifact در Workflow به `bazr-inspector-4.13.2-apk` تغییر کرد.
- منابع XML و Manifest از بسته مبنا بدون تغییر باقی ماندند.
- سلامت ZIP و CRC بسته‌های خروجی کنترل شد.

## مواردی که هنوز باید در CI تأیید شوند
- `:app:testDebugUnitTest`
- `:app:assembleDebug` یا `:app:assembleRelease`
- دریافت APK از Artifact و نصب روی دستگاه واقعی
- تست نمایش نوار وضعیت/ناوبری در Android 11 و Android 15

## وضعیت
این نسخه اصلاح مشخص و محدود به خطای کامپایل گزارش‌شده را اعمال می‌کند. تا زمان اجرای موفق GitHub Actions، ساخت APK تأییدشده محسوب نمی‌شود.
