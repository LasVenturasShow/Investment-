package ru.portfolio.watch

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Reconcile quantities against complete history, never treat a stale overview as current. */
data class LedgerOperation(
    val tradeId: String, val shareId: String, val ticker: String, val name: String,
    val asset: String, val operation: String, val date: String,
    val quantity: BigDecimal?, val linkedCashRub: BigDecimal?
)
data class OverviewPosition(
    val shareId: String, val ticker: String, val name: String, val asset: String,
    val quantity: BigDecimal, val value: BigDecimal?, val price: BigDecimal?,
    val invested: BigDecimal?
)
data class ReconciledPosition(
    val shareId: String, val ticker: String, val name: String, val quantity: BigDecimal,
    val value: BigDecimal?, val invested: BigDecimal?
)
data class Reconciliation(
    val positions: List<ReconciledPosition>, val total: BigDecimal?, val corrected: Boolean,
    val note: String
)
object HoldingsReconciler {
    private val zero = BigDecimal.ZERO
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val supported = setOf("STOCK", "ASSET")
    private val neutral = setOf("DIVIDEND", "VARIATION_MARGIN_INCOME", "VARIATION_MARGIN_LOSS")
    private fun time(value: String) = LocalDateTime.parse(value, format)
    private fun qty(t: LedgerOperation): BigDecimal = when (t.operation) {
        "BUY", "SELL" -> {
            val n = t.quantity ?: error("Нет количества: ${t.ticker}")
            require(n.signum() > 0) { "Неверное количество: ${t.ticker}" }
            if (t.operation == "BUY") n else n.negate()
        }
        in neutral -> zero
        else -> error("Неподдерживаемая операция ${t.operation} у ${t.ticker}")
    }

    fun reconcile(trades: List<LedgerOperation>, source: List<OverviewPosition>,
                  cutoffText: String, sourceTotal: BigDecimal?): Reconciliation {
        val cutoff = time(cutoffText)
        require(trades.isNotEmpty() && source.isNotEmpty()) { "Нет полной истории или обзора" }
        require(source.map { it.shareId }.distinct().size == source.size) { "Повтор ID в сводке" }
        val sourceById = source.associateBy { it.shareId }
        val positions = mutableListOf<ReconciledPosition>()
        val securityTrades = trades.filter { it.asset != "MONEY" }
        val byId = securityTrades.groupBy { it.shareId }
        val newer = trades.filter { time(it.date).isAfter(cutoff) }
        // The overview cutoff must actually reconcile with the historical ledger.
        // Only supported instrument/operation types are reconstructed.
        for (id in (byId.keys + source.filter { it.asset != "MONEY" }.map { it.shareId }).sorted()) {
            val history = byId[id].orEmpty()
            val row = sourceById[id]
            val label = row?.ticker ?: history.firstOrNull()?.ticker ?: id
            val asset = row?.asset ?: history.firstOrNull()?.asset.orEmpty()
            require(asset in supported && history.all { it.asset == asset }) {
                "Неподтверждённый тип инструмента: $label"
            }
            val before = history.filter { !time(it.date).isAfter(cutoff) }.fold(zero) { a, t -> a.add(qty(t)) }
            val sourceQty = row?.quantity ?: zero
            require(before.compareTo(sourceQty) == 0) {
                "Сводка и история не совпадают на дату сводки: $label ($before / $sourceQty)"
            }
            val after = history.fold(zero) { a, t -> a.add(qty(t)) }
            require(after.signum() >= 0) { "Короткая позиция требует отдельной оценки: $label" }
            if (after.signum() == 0) continue
            val changed = after.compareTo(sourceQty) != 0 ||
                history.any { time(it.date).isAfter(cutoff) && it.operation in setOf("BUY","SELL") }
            val value = when {
                !changed -> row?.value
                asset != "STOCK" -> null
                row != null && row.quantity.signum() > 0 && row.value != null ->
                    row.value.multiply(after).divide(row.quantity, 8, RoundingMode.HALF_UP)
                else -> row?.price?.multiply(after)
            }
            // Changed basis cannot safely be inferred from rounded overview data (FIFO/fees).
            val invested = if (changed) null else row?.invested
            positions += ReconciledPosition(id, label, row?.name ?: history.first().name,
                after, value?.takeIf { it.signum() >= 0 }, invested)
        }

        // Cash balance: use the confirmed overview cash plus actual linked RUB settlements
        // after its last included trade. Never infer settlement from a security price or sign.
        val cash = source.filter { it.asset == "MONEY" }
        require(cash.size <= 1) { "Несколько валют: требуется отдельная сверка денежных остатков" }
        val cashBase = cash.singleOrNull()
        if (cashBase != null) require(cashBase.ticker == "RUB") { "Денежный остаток не в рублях" }
        val cashDelta = if (newer.all { it.asset == "STOCK" && it.operation in setOf("BUY","SELL") && it.linkedCashRub != null })
            newer.fold(zero) { a, t -> a.add(t.linkedCashRub!!) } else null
        val cashValue = if (cashDelta != null && cashBase?.value != null)
            cashBase.value.add(cashDelta) else null
        if (cashBase != null && cashValue != null && cashValue.signum() != 0) {
            positions += ReconciledPosition(cashBase.shareId, "RUB", "Денежные средства",
                cashValue, cashValue, null)
        }
        val complete = positions.all { it.value != null } &&
            (if (cashBase != null) cashValue != null && cashValue.signum() >= 0 else newer.isEmpty())
        var total = if (complete) positions.fold(zero) { a, p -> a.add(p.value!!) } else null
        if (total != null && total.signum() < 0) total = null
        // Fresh source total includes cash and is authoritative after reconciliation.
        if (total != null && newer.isEmpty() && sourceTotal != null) {
            require(sourceTotal.subtract(total).abs() <= BigDecimal("2.00")) {
                "Общая стоимость сводки не совпадает с позициями"
            }
            total = sourceTotal
        }
        val note = when {
            newer.isEmpty() && total != null -> "Состав и стоимость сверены с полной историей сделок."
            newer.isEmpty() -> "Состав сверён; часть оценок недоступна."
            total != null -> "Сводка IntelInvest запаздывает: остатки и деньги скорректированы по сделкам; цены из последней сводки."
            else -> "Сводка IntelInvest запаздывает: остатки сверены по сделкам; непроверенные оценки скрыты."
        }
        return Reconciliation(positions, total, newer.isNotEmpty(), note)
    }
}
