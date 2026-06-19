package com.invoicedetector.sdk.extract

/**
 * Parses monetary amounts out of messy OCR text, coping with both
 * "1,234.56" (US/UK) and "1.234,56" (EU) groupings as well as currency symbols.
 */
internal object AmountParser {

    /** Matches a run that looks like a money amount, optionally prefixed by a symbol. */
    private val AMOUNT_REGEX =
        Regex("""[\$€£₹]?\s*\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?""")

    private val CURRENCY_SYMBOL = Regex("""[\$€£₹]|\b(USD|EUR|GBP|INR|AUD|CAD|JPY)\b""")

    /** Returns every amount found in [text], left-to-right. */
    fun findAmounts(text: String): List<Double> =
        AMOUNT_REGEX.findAll(text)
            .mapNotNull { parse(it.value) }
            .toList()

    /** Returns the largest amount on a line - usually the relevant figure for totals. */
    fun lastAmount(text: String): Double? = findAmounts(text).lastOrNull()

    fun detectCurrency(text: String): String? =
        CURRENCY_SYMBOL.find(text)?.value?.trim()

    /** Parses a single token like "1,234.56" / "1.234,56" / "$99.00" into a Double. */
    fun parse(raw: String): Double? {
        var s = raw.trim().replace(Regex("""[\$€£₹\s]"""), "")
        if (s.isEmpty()) return null

        val hasComma = s.contains(',')
        val hasDot = s.contains('.')

        s = when {
            hasComma && hasDot -> {
                // The right-most separator is the decimal point.
                if (s.lastIndexOf(',') > s.lastIndexOf('.')) {
                    s.replace(".", "").replace(',', '.')   // EU: 1.234,56
                } else {
                    s.replace(",", "")                      // US: 1,234.56
                }
            }
            hasComma -> {
                val decimals = s.length - s.lastIndexOf(',') - 1
                if (decimals == 2) s.replace(',', '.') else s.replace(",", "")
            }
            else -> s
        }
        return s.toDoubleOrNull()
    }
}
