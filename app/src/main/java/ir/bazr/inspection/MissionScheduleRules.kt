package ir.bazr.inspection

import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Parsing and calendar rules for server-assigned missions. No schedule is inferred when data is absent. */
object MissionScheduleRules {
    private val jalaliDatePattern = Regex("(?<!\\d)((?:13|14)\\d{2})[/-](\\d{1,2})[/-](\\d{1,2})(?!\\d)")
    private val gregorianDatePattern = Regex("(?<!\\d)((?:19|20)\\d{2})[/-](\\d{1,2})[/-](\\d{1,2})(?!\\d)")
    private val timePattern = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)")
    private val monthNames = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )
    private val tehran = ZoneId.of("Asia/Tehran")

    fun normalizeDigits(value: String): String = value.trim()
        .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3')
        .replace('۴', '4').replace('۵', '5').replace('۶', '6').replace('۷', '7')
        .replace('۸', '8').replace('۹', '9').replace('٫', '.')

    /** Accepts Jalali dates and ISO/Gregorian dates, including dates embedded in schedule labels. */
    fun parseDate(value: String): String? {
        val normalized = normalizeDigits(value).replace('-', '/')
        jalaliDatePattern.find(normalized)?.let { match ->
            val candidate = "%04d/%02d/%02d".format(
                match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()
            )
            val valid = PersianCalendar.normalize(candidate) ?: return@let
            if (PersianCalendar.toGregorian(valid) != null) return valid
        }
        gregorianDatePattern.find(normalized)?.let { match ->
            val date = runCatching {
                LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }.getOrNull() ?: return@let
            return PersianCalendar.fromGregorian(date)
        }
        return null
    }

    fun parseTime(value: String): String? {
        val normalized = normalizeDigits(value)
        val match = timePattern.find(normalized) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    fun missionDate(mission: JSONObject): String? {
        val fields = listOf("date", "visit_date", "scheduled_date", "mission_date", "schedule_label")
        for (field in fields) {
            val value = mission.optString(field, "")
            if (value.isNotBlank()) parseDate(value)?.let { return it }
        }
        return null
    }

    fun missionTime(mission: JSONObject): String? {
        val fields = listOf("time", "start_time", "scheduled_time", "visit_time", "schedule_time", "schedule_label")
        for (field in fields) {
            val value = mission.optString(field, "")
            if (value.isNotBlank()) parseTime(value)?.let { return it }
        }
        return null
    }

    fun scheduledDateTime(mission: JSONObject): LocalDateTime? {
        val date = missionDate(mission) ?: return null
        val time = missionTime(mission) ?: return null
        val gregorian = PersianCalendar.toGregorian(date) ?: return null
        val parts = time.split(":")
        return runCatching { gregorian.atTime(parts[0].toInt(), parts[1].toInt()) }.getOrNull()
    }

    fun scheduledInstant(mission: JSONObject): Instant? =
        scheduledDateTime(mission)?.atZone(tehran)?.toInstant()

    fun monthTitle(year: Int, month: Int): String = monthNames.getOrNull(month - 1) ?: "ماه نامشخص"

    fun shiftDays(jalaliDate: String, days: Long): String {
        val gregorian = PersianCalendar.toGregorian(jalaliDate) ?: return PersianCalendar.today()
        return PersianCalendar.fromGregorian(gregorian.plusDays(days))
    }

    fun shiftPeriod(jalaliDate: String, mode: String, amount: Int): String {
        return when (mode) {
            "day" -> shiftDays(jalaliDate, amount.toLong())
            "week" -> shiftDays(jalaliDate, amount.toLong() * 7L)
            else -> {
                val parts = jalaliDate.split("/")
                var year = parts.getOrNull(0)?.toIntOrNull() ?: 1405
                var month = parts.getOrNull(1)?.toIntOrNull() ?: 1
                var index = year * 12 + (month - 1) + amount
                year = Math.floorDiv(index, 12)
                month = Math.floorMod(index, 12) + 1
                "%04d/%02d/01".format(year, month)
            }
        }
    }

    /** Week begins on Saturday, matching the Iranian work week. */
    fun weekDates(jalaliDate: String): List<String> {
        val date = PersianCalendar.toGregorian(jalaliDate) ?: return emptyList()
        val offset = (date.dayOfWeek.value + 1) % 7
        val saturday = date.minusDays(offset.toLong())
        return (0L..6L).map { PersianCalendar.fromGregorian(saturday.plusDays(it)) }
    }

    fun address(mission: JSONObject): String = listOf(
        "address", "location_address", "org_address", "organization_address", "location", "address_text"
    ).firstNotNullOfOrNull { key -> mission.optString(key, "").trim().takeIf { it.isNotBlank() } }.orEmpty()

    fun coordinates(mission: JSONObject): Pair<Double, Double>? {
        fun number(vararg keys: String): Double? = keys.firstNotNullOfOrNull { key ->
            val raw = normalizeDigits(mission.optString(key, ""))
            raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        val latitude = number("latitude", "lat") ?: return null
        val longitude = number("longitude", "lng", "lon") ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return latitude to longitude
    }

    fun hasMapLocation(mission: JSONObject): Boolean = coordinates(mission) != null || address(mission).isNotBlank()
}
