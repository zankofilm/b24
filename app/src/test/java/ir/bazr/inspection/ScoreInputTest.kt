package ir.bazr.inspection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreInputTest {
    @Test fun parsesPersianDigits() {
        assertEquals(98.0, ScoreInput.parse("۹۸")!!, 0.0)
    }

    @Test fun parsesArabicIndicDigits() {
        assertEquals(76.0, ScoreInput.parse("٧٦")!!, 0.0)
    }

    @Test fun parsesPersianDecimalSeparator() {
        assertEquals(72.5, ScoreInput.parse("۷۲٫۵")!!, 0.0001)
    }

    @Test fun parsesCommaDecimalAndThousandsSeparator() {
        assertEquals(12.5, ScoreInput.parse("۱۲,۵")!!, 0.0001)
        assertEquals(125000.0, ScoreInput.parse("۱۲۵٬۰۰۰")!!, 0.0001)
    }

    @Test fun acceptsThousandsGroupsOnlyBeforeDecimalPoint() {
        assertEquals(1234.5, ScoreInput.parse("1,234.5")!!, 0.0001)
        assertEquals(1234.5, ScoreInput.parse("۱٬۲۳۴٫۵")!!, 0.0001)
    }

    @Test fun rejectsMalformedOrRepeatedSeparators() {
        assertNull(ScoreInput.parse("۱۲٫۵٫۷"))
        assertNull(ScoreInput.parse("۱۲,۵,۶"))
        assertNull(ScoreInput.parse("۱۲,۵.۷"))
        assertNull(ScoreInput.parse("متن"))
    }
}
