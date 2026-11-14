import ru.portfolio.watch.*

fun main() {
    val a = TradeRow("id-a", listOf("10.11.2026", "SBER", "Покупка", "1"))
    val aEdited = TradeRow("id-a", listOf("10.11.2026", "SBER", "Покупка", "2"))
    val b = TradeRow(null, listOf("11.11.2026", "GAZP", "Продажа", "1"))
    check(DiffCore.compare(emptyList(), listOf(a)).single().type == "BUY")
    check(DiffCore.compare(listOf(a), listOf(a)).isEmpty())
    check(DiffCore.compare(listOf(a), listOf(aEdited)).single().type == "MODIFIED")
    check(DiffCore.compare(listOf(a), emptyList()).single().type == "REMOVED")
    check(DiffCore.compare(listOf(b, b), listOf(b)).single().type == "REMOVED")
    check(DiffCore.compare(listOf(b), listOf(b, b)).single().type == "SELL")
    check(DiffCore.compare(listOf(a, b), listOf(b, a)).isEmpty())
    try { DiffCore.compare(listOf(a), emptyList(), complete = false); error("must reject incomplete") }
    catch (_: IllegalArgumentException) { }
    println("PASS: baseline / unchanged / modification / deletion / duplicates / order / incomplete")
}
