# اتصال سرور نسخه ۴.۱۹ – مرکز مستندات

## Endpoint جدید
`GET /api/mobile_evidence_library.php`

- احراز هویت با `current_mobile_session()` و فقط نقش `inspector`.
- فایل‌ها فقط از `mobile_submission_files` متعلق به همان `mobile_user_id` خوانده می‌شوند.
- هر فایل فقط در صورتی برگردانده می‌شود که مأموریت آن با `mobile_mission_is_assigned()` برای همین بازرس مجاز باشد.
- پاسخ شامل شناسه فایل، مأموریت، اداره، شاخص، نام، MIME، حجم و زمان ثبت است.
- فایل باینری از endpoint موجود `mobile_file.php?upload_id=...` دریافت می‌شود؛ هیچ URL دلخواهی از پاسخ سرور باز نمی‌شود.

## وابستگی‌های موجود
`mobile_common.php`, `mobile_submission_files`, `mobile_uploads`, `entity_records`, `mobile_file.php`.

## تغییر دیتابیس
SQL یا جدول جدید لازم نیست. اگر جدول‌های فایل موجود نباشند، endpoint آرایه خالی با هشدار کنترل‌شده برمی‌گرداند.

## کنترل سرور
`php -l api/mobile_evidence_library.php` و سپس `python3 tools/verify_evidence_library_patch.py`.
