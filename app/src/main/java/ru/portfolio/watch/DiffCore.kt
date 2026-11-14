package ru.portfolio.watch

/** Pure Kotlin comparison logic: testable without Android. Does not infer trades from price changes. */
data class TradeRow(val stableId: String?, val cells: List<String>) {
    fun fingerprint(): String = (if (cells.size >= 11) cells.filterIndexed { index, _ -> index != 3 } else cells)
        .joinToString(" ␟ ") { it.trim().replace(Regex("\\s+"), " ") }
    fun description(): String = cells.joinToString(" · ") { it.trim() }.take(260)
}

data class PortfolioChange(val type: String, val label: String, val description: String)

object DiffCore {
    fun compare(old: List<TradeRow>, fresh: List<TradeRow>, complete: Boolean = true): List<PortfolioChange> {
        require(complete) { "Неполный список операций нельзя сравнивать как полный." }
        val changes = mutableListOf<PortfolioChange>()
        // ID is trusted only if unique in BOTH sets; otherwise fall back to multiset comparison.
        val oldCounts = old.mapNotNull { it.stableId?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
        val newCounts = fresh.mapNotNull { it.stableId?.takeIf(String::isNotBlank) }.groupingBy { it }.eachCount()
        val goodIds = (oldCounts.keys + newCounts.keys).filter {
            (oldCounts[it] ?: 0) <= 1 && (newCounts[it] ?: 0) <= 1
        }.toSet()
        val oldId = old.filter { it.stableId in goodIds && !it.stableId.isNullOrBlank() }.associateBy { it.stableId!! }
        val newId = fresh.filter { it.stableId in goodIds && !it.stableId.isNullOrBlank() }.associateBy { it.stableId!! }
        val oldOnly = old.filter { it.stableId !in oldId.keys }
        val freshOnly = fresh.filter { it.stableId !in newId.keys }
        for ((id, row) in newId) {
            val earlier = oldId[id]
            if (earlier == null) changes += added(row)
            else if (earlier.fingerprint() != row.fingerprint()) {
                changes += PortfolioChange("MODIFIED", "Изменена операция", "Было: ${earlier.description()}\nСтало: ${row.description()}")
            }
        }
        for ((id, row) in oldId) if (id !in newId) changes += removed(row)
        val oldBuckets = oldOnly.groupBy { it.fingerprint() }.mapValues { it.value.toMutableList() }
        val newBuckets = freshOnly.groupBy { it.fingerprint() }.mapValues { it.value.toMutableList() }
        for ((fingerprint, rows) in newBuckets) {
            val delta = rows.size - (oldBuckets[fingerprint]?.size ?: 0)
            repeat(delta.coerceAtLeast(0)) { changes += added(rows[it]) }
        }
        for ((fingerprint, rows) in oldBuckets) {
            val delta = rows.size - (newBuckets[fingerprint]?.size ?: 0)
            repeat(delta.coerceAtLeast(0)) { changes += removed(rows[it]) }
        }
        return changes
    }
    private fun added(row: TradeRow): PortfolioChange {
        val txt = row.description()
        val type = when {
            Regex("покупк|\\bbuy\\b", RegexOption.IGNORE_CASE).containsMatchIn(txt) -> "BUY"
            Regex("продаж|\\bsell\\b", RegexOption.IGNORE_CASE).containsMatchIn(txt) -> "SELL"
            else -> "ADDED"
        }
        val label = when (type) {
            "BUY" -> "Новая покупка"
            "SELL" -> "Новая продажа"
            else -> "Новая операция"
        }
        return PortfolioChange(type, label, txt)
    }
    private fun removed(row: TradeRow) = PortfolioChange("REMOVED", "Запись исчезла из истории", row.description())
}
