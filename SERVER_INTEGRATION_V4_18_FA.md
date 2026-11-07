# اتصال سرور و پنل وب — نسخه ۴.۱۸

## مدل داده
ابلاغیه‌ها در جدول موجود `entity_records` با `entity_type='notice'` ذخیره می‌شوند. هیچ جدول یا ستون جدیدی ایجاد نمی‌شود.

فیلدهای مهم payload:
- `id`, `title`, `message`, `severity`, `category`
- `broadcast` یا `recipient_mobile_user_id` و `recipient_inspector_ref`
- `readByMobileUserIds`, `readAtByMobileUserId`
- `refType`, `refId`, `missionKey`
- `senderName`, `createdAt`

## APIهای جدید
- `GET api/mobile_notice_admin.php`: فهرست بازرسان فعال برای فرم پنل؛ نیازمند نشست ادمین و مجوز `reports-write`.
- `POST api/mobile_notice_admin.php`: ایجاد ابلاغیه اختصاصی یا همگانی؛ ارسال همگانی با تأیید پنل.
- `api/mobile_notice_rules.php` و `api/mobile_notice_common.php`: قواعد گیرنده، محدوده اداره و قالب‌بندی ابلاغیه.
- `GET api/mobile_notices.php`: دریافت ابلاغیه‌های مجاز بازرس با نشست موبایل.
- `POST api/mobile_notice_read.php`: ثبت رسید خواندن یک ابلاغیه یا همه ابلاغیه‌ها.
- `POST api/mobile_notice_poll.php`: polling پس‌زمینه فقط با `device_token` و امضای کلید خصوصی دستگاه؛ این endpoint نشست عمومی صادر نمی‌کند و فقط ابلاغیه‌های همان دستگاه را برمی‌گرداند.
- `api/mobile_sync.php`: دریافت ابلاغیه را به گیرنده صریح یا broadcast محدود می‌کند؛ رکوردهای بدون گیرنده دیگر به همه بازرسان نشت نمی‌کنند.

## امنیت
- API ادمین فقط برای نقش‌های دارای `reports-write` باز است.
- ابلاغیه فقط به `mobile_user_id`, `inspector_ref` یا broadcast صریح تحویل می‌شود.
- رسید خواندن به‌ازای هر `mobile_user_id` ثبت می‌شود و وضعیت مشاهده یک بازرس به سایر گیرندگان تعمیم داده نمی‌شود.
- polling پس‌زمینه با `device_id`, `device_token`, `timestamp`, `nonce` و امضای P-256 کلید Android Keystore احراز می‌شود.
- nonce تکراری یا timestamp قدیمی توسط `mobile_check_nonce` رد می‌شود.
- ابلاغیه‌های بدون گیرنده و بدون broadcast به‌صورت خصوصی/غیرقابل‌تحویل باقی می‌مانند.

## پنل وب
`pro.js` در بخش «ابلاغ و مکاتبات» فرم صدور ابلاغیه را نمایش می‌دهد. فهرست بازرسان از endpoint مدیریتی گرفته می‌شود و رسید مشاهده از payload همان رکورد در `state_pull.php` خوانده می‌شود.

## زمان‌بندی
Android WorkManager هر ۱۵ دقیقه یا دیرتر، با شرط شبکه، endpoint محدود `mobile_notice_poll.php` را فراخوانی می‌کند. زمان اجرای واقعی تابع سیاست باتری Android است. برای Push فوری باید FCM جداگانه پیکربندی شود.

ابلاغیه‌های قدیمی که فقط `orgId` دارند نیز فقط به بازرس‌هایی تحویل می‌شوند که حداقل یک مأموریت تخصیص‌یافته در همان اداره دارند؛ رکوردهای فاقد گیرنده و فاقد `orgId` همچنان خصوصی می‌مانند.
