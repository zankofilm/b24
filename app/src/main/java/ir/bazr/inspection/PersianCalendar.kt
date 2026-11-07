package ir.bazr.inspection

import java.time.LocalDate
import java.time.ZoneId

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

    fun isValid(value: String): Boolean = normalize(value) != null
    fun isBefore(left: String, right: String = today()): Boolean {
        val a = normalize(left) ?: return false
        val b = normalize(right) ?: return false
        return a < b
    }
}
