package ru.portfolio.watch

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Preserve monetary precision and avoid mislabeling non-RUB trade amounts. */
data class OpenPositionResult(val profitRub: BigDecimal, val percent: BigDecimal)

object RubValues {
    /**
     * Unrealized gain/loss of the *currently held* position, not all-time realized P&L.
     * Returns null if the publicly supplied invested amount is missing/invalid.
     */
    fun openPositionResult(currentRub: BigDecimal?, investedRub: BigDecimal?): OpenPositionResult? {
        if (currentRub == null || investedRub == null ||
            currentRub.signum() < 0 || investedRub.signum() <= 0) return null
        val delta = currentRub.subtract(investedRub)
        val percent = delta.multiply(BigDecimal("100"))
            .divide(investedRub, 6, RoundingMode.HALF_UP)
        return OpenPositionResult(delta, percent)
    }

    fun displaySignedRub(raw: String?): String {
        val value = raw?.toBigDecimalOrNull() ?: return "—"
        val sign = if (value.signum() > 0) "+" else if (value.signum() < 0) "−" else ""
        return sign + display(value.abs().toPlainString())
    }

    fun displaySignedPercent(raw: String?): String {
        val value = raw?.toBigDecimalOrNull() ?: return "—"
        val formatter = NumberFormat.getNumberInstance(Locale("ru", "RU")).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            roundingMode = RoundingMode.HALF_UP
        }
        val sign = if (value.signum() > 0) "+" else if (value.signum() < 0) "−" else ""
        return sign + formatter.format(value.abs()) + "%"
    }

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
