package ir.bazr.inspection

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Small, dependency-free Jalali/Gregorian bridge used for deadlines shown by the inspection workflow. */
object PersianCalendar {
    private val tehran = ZoneId.of("Asia/Tehran")

    fun today(): String = fromGregorian(LocalDate.now(tehran))

    fun fromGregorian(date: LocalDate): String {
        val gdm = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy = date.year
        val gm = date.monthValue
        val gd = date.dayOfMonth
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666L + 365L * gy + (gy2 + 3L) / 4L - (gy2 + 99L) / 100L +
            (gy2 + 399L) / 400L + gd + gdm[gm - 1]
        var jy = -1595L + 33L * (days / 12053L)
        days %= 12053L
        jy += 4L * (days / 1461L)
        days %= 1461L
        if (days > 365L) {
            jy += (days - 1L) / 365L
            days = (days - 1L) % 365L
        }
        val jm: Long
        val jd: Long
        if (days < 186L) {
            jm = 1L + days / 31L
            jd = 1L + days % 31L
        } else {
            jm = 7L + (days - 186L) / 30L
            jd = 1L + (days - 186L) % 30L
        }
        return "%04d/%02d/%02d".format(jy, jm, jd)
    }

    fun normalize(value: String): String? {
        val normalized = value.trim().replace('۰', '0').replace('۱', '1').replace('۲', '2')
            .replace('۳', '3').replace('۴', '4').replace('۵', '5').replace('۶', '6')
            .replace('۷', '7').replace('۸', '8').replace('۹', '9').replace('-', '/')
        val match = Regex("^(\\d{4})/(\\d{1,2})/(\\d{1,2})$").matchEntire(normalized) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        if (year !in 1300..1500 || month !in 1..12) return null
        val maxDay = when (month) {
            in 1..6 -> 31
            in 7..11 -> 30
            else -> 30 // Esfand 30 is checked against the Gregorian picker for newly created items.
        }
        if (day !in 1..maxDay) return null
        return "%04d/%02d/%02d".format(year, month, day)
    }

    /** Converts a valid Jalali date to Gregorian. Round-trip validation rejects Esfand 30 in non-leap years. */
    fun toGregorian(value: String): LocalDate? {
        val normalized = normalize(value) ?: return null
        val parts = normalized.split("/")
        val jy = parts[0].toIntOrNull() ?: return null
        val jm = parts[1].toIntOrNull() ?: return null
        val jd = parts[2].toIntOrNull() ?: return null

        val jYear = jy - 979
        var jDayNo = 365L * jYear + (jYear / 33) * 8L + ((jYear % 33 + 3) / 4)
        jDayNo += if (jm <= 6) (jm - 1) * 31L else 186L + (jm - 7) * 30L
        jDayNo += jd - 1L

        var gDayNo = jDayNo + 79L
        var gy = 1600 + 400 * (gDayNo / 146097L).toInt()
        gDayNo %= 146097L
        var leap = true
        if (gDayNo >= 36525L) {
            gDayNo -= 1L
            gy += 100 * (gDayNo / 36524L).toInt()
            gDayNo %= 36524L
            if (gDayNo >= 365L) gDayNo += 1L else leap = false
        }
        gy += 4 * (gDayNo / 1461L).toInt()
        gDayNo %= 1461L
        if (gDayNo >= 366L) {
            leap = false
            gDayNo -= 1L
            gy += (gDayNo / 365L).toInt()
            gDayNo %= 365L
        }
        val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        var gm = 1
        while (gm <= 12 && gDayNo >= monthDays[gm - 1]) {
            gDayNo -= monthDays[gm - 1]
            gm++
        }
        if (gm !in 1..12) return null
        val result = runCatching { LocalDate.of(gy, gm, gDayNo.toInt() + 1) }.getOrNull() ?: return null
        return result.takeIf { fromGregorian(it) == normalized }
    }

    /** Exact Jalali month length, including leap-year Esfand. */
    fun daysInMonth(year: Int, month: Int): Int {
        if (year !in 1300..1500 || month !in 1..12) return 30
        val first = toGregorian("%04d/%02d/01".format(year, month)) ?: return if (month == 12) 29 else if (month <= 6) 31 else 30
        val nextDate = if (month == 12) {
            if (year == 1500) return 29
            toGregorian("%04d/01/01".format(year + 1))
        } else toGregorian("%04d/%02d/01".format(year, month + 1))
        return nextDate?.let { ChronoUnit.DAYS.between(first, it).toInt() } ?: if (month == 12) 29 else if (month <= 6) 31 else 30
    }

    fun isValid(value: String): Boolean = normalize(value) != null
    fun isBefore(left: String, right: String = today()): Boolean {
        val a = normalize(left) ?: return false
        val b = normalize(right) ?: return false
        return a < b
    }
}
