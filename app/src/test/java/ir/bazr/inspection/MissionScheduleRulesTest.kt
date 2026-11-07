package ir.bazr.inspection

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MissionScheduleRulesTest {
    @Test fun jalaliAndGregorianDatesNormalizeToTheSameIranianDay() {
        assertEquals("1405/08/16", MissionScheduleRules.parseDate("شنبه ۱۴۰۵/۰۸/۱۶"))
        assertEquals("1405/08/16", MissionScheduleRules.parseDate("2026-11-07T09:30:00+03:30"))
        assertEquals(LocalDate.of(2026, 11, 7), PersianCalendar.toGregorian("1405/08/16"))
        assertEquals("1405/08/16", PersianCalendar.fromGregorian(LocalDate.of(2026, 11, 7)))
    }

    @Test fun invalidEsfandThirtyIsRejectedForNonLeapYear() {
        assertNull(PersianCalendar.toGregorian("1404/12/30"))
        assertEquals(29, PersianCalendar.daysInMonth(1404, 12))
        assertEquals(29, PersianCalendar.daysInMonth(1405, 12))
        assertEquals(30, PersianCalendar.daysInMonth(1403, 12))
    }

    @Test fun monthLengthAndSaturdayFirstWeekAreCorrect() {
        assertEquals(31, PersianCalendar.daysInMonth(1405, 1))
        assertEquals(30, PersianCalendar.daysInMonth(1405, 8))
        assertEquals("1405/08/16", MissionScheduleRules.weekDates("1405/08/16").first())
        assertEquals(7, MissionScheduleRules.weekDates("1405/08/16").size)
    }

    @Test fun missionTimeIsExtractedWithoutGuessing() {
        assertEquals("09:30", MissionScheduleRules.parseTime("ساعت ۰۹:۳۰"))
        assertEquals("14:05", MissionScheduleRules.parseTime("۱۴:۰۵"))
        assertNull(MissionScheduleRules.parseTime("ساعت اعلام نشده"))
    }

    @Test fun periodNavigationUsesJalaliCalendar() {
        assertEquals("1406/01/01", MissionScheduleRules.shiftPeriod("1405/12/15", "month", 1))
        assertEquals("1405/08/17", MissionScheduleRules.shiftDays("1405/08/16", 1))
    }
}
