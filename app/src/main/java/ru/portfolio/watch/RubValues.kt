package ru.portfolio.watch

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Preserve monetary precision and avoid mislabeling non-RUB trade amounts. */
object RubValues {
    private val apiAmount = Regex("""^RUB\s+([+-]?[0-9]+(?:\.[0-9]+)?)$""")

    fun fromApi(text: String?): BigDecimal? {
        val match = apiAmount.matchEntire(text?.trim().orEmpty()) ?: return null
        return match.groupValues[1].toBigDecimalOrNull()
    }

    fun display(rawRub: String?): String {
        val value = rawRub?.toBigDecimalOrNull() ?: return "—"
        val formatted = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }
        return "${formatted.format(value)} ₽"
    }
}
