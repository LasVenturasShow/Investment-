package ru.portfolio.watch

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class OpenPositionResultTest {
    @Test fun positiveMts() {
        val result = RubValues.openPositionResult(BigDecimal("296560"), BigDecimal("294880"))
        assertNotNull(result)
        assertEquals(0, result!!.profitRub.compareTo(BigDecimal("1680")))
        assertEquals("0,57%", RubValues.displaySignedPercent(result.percent.toPlainString()).removePrefix("+"))
        assertEquals("+1\u00a0680,00 ₽", RubValues.displaySignedRub(result.profitRub.toPlainString()).replace("\u202f", "\u00a0"))
    }

    @Test fun profitIsNotAllTimeProfit() {
        // CHMF published overall history profit is negative, open position profit positive.
        val result = RubValues.openPositionResult(BigDecimal("283600"), BigDecimal("280080"))!!
        assertEquals(0, result.profitRub.compareTo(BigDecimal("3520")))
        assertEquals("+1,26%", RubValues.displaySignedPercent(result.percent.toPlainString()))
    }

    @Test fun lossAndZero() {
        val result = RubValues.openPositionResult(BigDecimal("90"), BigDecimal("100"))!!
        assertEquals(0, result.profitRub.compareTo(BigDecimal("-10")))
        assertEquals("−10,00 ₽", RubValues.displaySignedRub(result.profitRub.toPlainString()))
        assertEquals("−10,00%", RubValues.displaySignedPercent(result.percent.toPlainString()))
        val even = RubValues.openPositionResult(BigDecimal("100"), BigDecimal("100"))!!
        assertEquals("0,00%", RubValues.displaySignedPercent(even.percent.toPlainString()))
    }

    @Test fun incompleteSourceIsNotInvented() {
        assertNull(RubValues.openPositionResult(BigDecimal("10"), null))
        assertNull(RubValues.openPositionResult(BigDecimal("10"), BigDecimal.ZERO))
        assertNull(RubValues.openPositionResult(BigDecimal("-10"), BigDecimal("10")))
        assertEquals("—", RubValues.displaySignedRub(null))
        assertEquals("—", RubValues.displaySignedPercent(null))
    }
}
