# تغییرات نسخه 4.13.2 — اصلاح خطای ساخت Android

## رفع خطای کامپایل
- جایگزینی دسترسی Kotlin property به `statusBarContrastEnforced` با فراخوانی صریح `Window.setStatusBarContrastEnforced(false)`.
- جایگزینی دسترسی Kotlin property به `navigationBarContrastEnforced` با فراخوانی صریح `Window.setNavigationBarContrastEnforced(false)`.
- اعمال اصلاح در هر دو مسیر `applyShellInsets` و `applySplashInsets`.
- محدود کردن فراخوانی‌ها به API 29 به بالا؛ `minSdk` پروژه API 30 است.
- حفظ طراحی edge-to-edge و scrimهای بالای/پایین صفحه؛ رفتار ظاهری نوارها عمداً تغییر داده نشده است.

## نسخه و CI
- `versionCode`: 23 → 24
- `versionName`: 4.13.1 → 4.13.2
- نام Artifact در GitHub Actions به `bazr-inspector-4.13.2-apk` تغییر کرد.

## مبنای فنی
Android SDK مستند می‌کند که هر دو setter در `android.view.Window` از API 29 در دسترس هستند. خطای قبلی از نحوه resolve شدن propertyهای Java boolean در Kotlin بود، نه از نبودن API در Android 35.
