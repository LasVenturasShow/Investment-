package ru.portfolio.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

const val TRADES_URL = "https://intelinvest.ru/public/portfolio/781662/trades"
private const val API_ROOT = "https://intelinvest.ru/api/public"
private const val PORTFOLIO_ID = 781662

data class Holding(val ticker: String, val name: String, val percent: Double, val quantity: String = "")
data class Capture(val trades: List<TradeRow>, val holdings: List<Holding>, val diagnostics: String)

/**
 * Public, read-only IntelInvest JSON API. No account access, cookies or embedded browser.
 * Never returns a partial trade-history snapshot as successful.
 */
class Collector(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())

    fun collect(onDone: (Result<Capture>) -> Unit) {
        thread(name = "intelinvest-public-api", isDaemon = true) {
            val outcome = runCatching { load() }
            main.post { onDone(outcome) }
        }
    }

    private fun load(): Capture {
        val started = System.currentTimeMillis()
        val trades = readAllTrades(started)
        var holdings: List<Holding> = emptyList()
        var allocationMessage = "Данные о долях временно недоступны"
        try {
            holdings = readHoldings()
            allocationMessage = "Активных позиций: ${holdings.size}"
        } catch (e: Exception) {
            allocationMessage += ": " + (e.message ?: "нет ответа").take(130)
        }
        return Capture(trades, holdings, "Сделок подтверждено: ${trades.size}. $allocationMessage")
    }

    private fun tradesQuery(offset: Int, limit: Int = 25): String =
        "$API_ROOT/trades/$PORTFOLIO_ID?offset=$offset&limit=$limit&&&" +
            "showLinkedMoneyTrades=false&showMoneyTrades=true&search=&&start=&end=&&&&" +
            "calculateSubTotal=true&sortColumn=DATE&descending=true"

    private fun readAllTrades(started: Long): List<TradeRow> {
        val records = ArrayList<TradeRow>()
        val ids = HashSet<String>()
        var expected = -1
        var offset = 0
        val pageSize = 25
        var firstPage: List<TradeRow> = emptyList()
        var requests = 0
        while (true) {
            if (++requests > 150 || System.currentTimeMillis() - started > 85000L) {
                error("Превышен лимит загрузки истории: ${records.size} операций из $expected")
            }
            val payload = request(tradesQuery(offset, pageSize))
            val total = payload.optInt("totalItems", -1)
            if (total <= 0 || total > 20000) error("Некорректное общее количество сделок: $total")
            if (expected < 0) expected = total
            if (expected != total) error("История изменилась при загрузке ($expected → $total). Повторите проверку")
            // IntelInvest incorrectly returns offset=0 and pageNumber=0 even for later pages.
            // Verify actual uniqueness and completeness instead of trusting those metadata fields.
            if (payload.optInt("pageSize", -1) != pageSize)
                error("Сервер сменил размер страницы при загрузке")
            val content = payload.optJSONArray("content") ?: error("В ответе отсутствует список сделок")
            if (content.length() == 0 || content.length() > pageSize) error("Неверное число записей на странице: ${content.length()}")
            val page = ArrayList<TradeRow>(content.length())
            for (i in 0 until content.length()) {
                val o = content.optJSONObject(i) ?: error("Некорректная сделка на странице ${offset / pageSize}")
                val id = o.optLong("id", 0L)
                if (id <= 0L || o.optLong("portfolioId", 0L) != PORTFOLIO_ID.toLong()) {
                    error("Неверный ID сделки или портфеля на странице ${offset / pageSize}")
                }
                if (!ids.add(id.toString())) error("Дублирующаяся сделка #$id: возможен сдвиг пагинации")
                val date = o.optString("date").trim()
                val operation = o.optString("operationLabel").trim()
                if (date.isEmpty() || operation.isEmpty()) error("Сделка #$id без обязательных полей")
                val row = TradeRow(id.toString(), listOf(
                    date,
                    operation,
                    o.optString("ticker").trim(),
                    o.optString("companyName").trim(),
                    o.optString("quantity").trim(),
                    o.optString("moneyPrice").trim(),
                    o.optString("fee").trim(),
                    o.optString("signedTotal").trim(),
                    o.optString("asset").trim(),
                    o.optString("currency").trim(),
                    if (o.isNull("note")) "" else o.optString("note").trim()
                ))
                page += row
            }
            if (offset == 0) firstPage = page
            records.addAll(page)
            if (records.size >= expected) break
            if (content.length() != pageSize) error("Неполная промежуточная страница на смещении $offset")
            offset += pageSize
        }
        if (records.size != expected) error("Получено ${records.size} из $expected сделок — снимок не сохранён")
        // Guard against newly inserted/backdated trades during multi-page reads.
        val repeatPayload = request(tradesQuery(0, pageSize))
        if (repeatPayload.optInt("totalItems", -1) != expected) error("Число сделок изменилось во время загрузки")
        val repeat = repeatPayload.optJSONArray("content") ?: error("Не удалось перепроверить первую страницу")
        if (repeat.length() != firstPage.size) error("Первая страница изменилась во время загрузки")
        for (i in firstPage.indices) {
            val current = repeat.optJSONObject(i) ?: error("Ошибочная перепроверка страницы")
            if (current.optLong("id").toString() != firstPage[i].stableId ||
                current.optString("date") != firstPage[i].cells[0] ||
                current.optString("operationLabel") != firstPage[i].cells[1] ||
                current.optString("quantity") != firstPage[i].cells[4] ||
                current.optString("moneyPrice") != firstPage[i].cells[5] ||
                current.optString("signedTotal") != firstPage[i].cells[7] ||
                (if (current.isNull("note")) "" else current.optString("note").trim()) != firstPage[i].cells[10]) {
                error("Сделки изменились во время загрузки — повторите проверку")
            }
        }
        return records
    }

    private fun readHoldings(): List<Holding> {
        val overview = request("$API_ROOT/portfolios/$PORTFOLIO_ID/overview")
        val rows = overview.optJSONArray("rows") ?: error("В обзоре нет позиций")
        if (rows.length() == 0) error("Пустой список позиций")
        val out = mutableListOf<Holding>()
        val keys = HashSet<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val share = row.optJSONObject("share") ?: continue
            val ticker = share.optString("ticker").trim()
            val name = share.optString("name").trim().ifBlank { ticker }
            val weight = row.optString("percCurrShareInWholePortfolio")
                .replace(',', '.').toDoubleOrNull() ?: continue
            if (weight <= 0.0) continue
            if (weight > 100 || ticker.isBlank() || !keys.add(ticker)) error("Некорректные позиции: $ticker")
            val qty = row.optString("quantity").takeUnless { it == "null" }.orEmpty()
            out += Holding(ticker, name.take(100), weight, qty)
        }
        val sum = out.sumOf { it.percent }
        if (out.isEmpty() || sum !in 98.0..102.0) {
            error("Неполная структура портфеля: сумма долей $sum%")
        }
        return out.sortedByDescending { it.percent }
    }

    private fun request(address: String): JSONObject {
        require(address.startsWith("$API_ROOT/"))
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Referer", TRADES_URL)
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36")
        return try {
            val status = connection.responseCode
            if (status != 200) error("IntelInvest API вернул HTTP $status")
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.contains("json")) error("Сервер вернул не JSON: $contentType")
            val length = connection.contentLength
            if (length > 8_000_000) error("Слишком большой ответ API")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.length > 8_000_000) error("Размер ответа превышает допустимый")
            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }
}
