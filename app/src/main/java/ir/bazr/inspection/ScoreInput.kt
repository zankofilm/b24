package ir.bazr.inspection

/** Locale-safe score parsing. Persian/Arabic keyboards may emit non-ASCII digits. */
object ScoreInput {
    /**
     * Normalize a score without silently accepting malformed repeated separators.
     * A single comma is treated as a decimal separator when no decimal point exists;
     * commas before a dot are accepted only as valid thousands groups.
     */
    fun normalize(raw: String): String {
        val mapped = buildString(raw.length) {
            for (char in raw.trim()) {
                append(when (char) {
                    in '\u06F0'..'\u06F9' -> ('0'.code + (char.code - '\u06F0'.code)).toChar()
                    in '\u0660'..'\u0669' -> ('0'.code + (char.code - '\u0660'.code)).toChar()
                    '\u066B' -> '.'
                    '\u060C' -> ','
                    '\u2212' -> '-'
                    '\u066C', '\u00A0', '\u202F', ' ' -> continue
                    else -> char
                })
            }
        }
        if (mapped.isBlank()) return ""

        val decimalCount = mapped.count { it == '.' }
        val commaCount = mapped.count { it == ',' }
        if (decimalCount > 1) return "#"

        if (decimalCount == 1) {
            val decimalAt = mapped.indexOf('.')
            val integerPart = mapped.substring(0, decimalAt)
            val fractionPart = mapped.substring(decimalAt + 1)
            if (fractionPart.contains(',')) return "#"
            if (integerPart.contains(',')) {
                val validGroupedInteger = integerPart.matches(Regex("-?\\d{1,3}(,\\d{3})+"))
                if (!validGroupedInteger || commaCount != integerPart.count { it == ',' }) return "#"
            }
            return integerPart.replace(",", "") + "." + fractionPart
        }

        if (commaCount > 1) return "#"
        if (commaCount == 1) return mapped.replace(',', '.')
        return mapped
    }

    fun parse(raw: String): Double? = normalize(raw).toDoubleOrNull()?.takeIf { it.isFinite() }
}
