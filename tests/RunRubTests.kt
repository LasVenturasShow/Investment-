import ru.portfolio.watch.*
import java.math.BigDecimal

fun main() {
    check(RubValues.fromApi("RUB 3974053.35") == BigDecimal("3974053.35"))
    check(RubValues.fromApi("RUB -294464.40") == BigDecimal("-294464.40"))
    check(RubValues.fromApi("USD 50.00") == null)
    check(RubValues.fromApi("") == null)
    check(RubValues.display("294880.00").contains("294"))
    check(RubValues.display("294880.00").endsWith("₽"))
    val row = TradeRow("123", listOf("2026-11-13", "Покупка", "MTSS", "МТС", "1600",
        "RUB 184.3", "RUB 0", "RUB 294880.00", "STOCK", "RUB", ""))
    val change = DiffCore.compare(emptyList(), listOf(row)).single()
    check(change.type == "BUY")
    check(change.description.contains("Сумма сделки:"))
    check(change.description.contains("294"))
    check(DiffCore.compare(listOf(row),listOf(row)).isEmpty())
    println("PASS: RUB parsing / formatting / trade notification")
}
