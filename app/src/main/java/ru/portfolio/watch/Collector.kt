package ru.portfolio.watch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

const val TRADES_URL = "https://intelinvest.ru/public/portfolio/781662/trades"
private const val POSITIONS_URL = "https://intelinvest.ru/public/portfolio/781662"

data class Holding(val ticker: String, val name: String, val percent: Double)
data class Capture(val trades: List<TradeRow>, val holdings: List<Holding>, val diagnostics: String)

/** Reads the actual public SPA as rendered in Android WebView. No private cookies, credentials or fake fixtures. */
class Collector(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var browser: WebView? = null
    private var deadlineStage = 0
    private var finished = false
    private var retries = 0
    private var trades: List<TradeRow> = emptyList()
    private var diagnostics = ""
    private lateinit var onDone: (Result<Capture>) -> Unit
    private val script: String by lazy { context.assets.open("collector.js").bufferedReader().use { it.readText() } }

    @SuppressLint("SetJavaScriptEnabled")
    fun collect(onDone: (Result<Capture>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "WebView must be created on the main thread" }
        this.onDone = onDone
        try {
            val web = WebView(context)
            browser = web
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.allowFileAccess = false
            web.settings.allowContentAccess = false
            web.settings.javaScriptCanOpenWindowsAutomatically = false
            web.settings.setSupportMultipleWindows(false)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!finished) handler.postDelayed({ evaluate(deadlineStage) }, 1700)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true && !finished) {
                        finish(Result.failure(IllegalStateException("Сайт недоступен: ${error?.description ?: "ошибка сети"}")))
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return url != null && !isAllowed(url)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return request != null && !isAllowed(request.url.toString())
                }
            }
            startStage(0)
            handler.postDelayed({
                if (!finished) finish(Result.failure(IllegalStateException("Истекло время загрузки публичного портфеля")))
            }, 95000)
        } catch (e: Exception) { finish(Result.failure(e)) }
    }

    private fun isAllowed(url: String): Boolean = try {
        val u = android.net.Uri.parse(url)
        u.scheme == "https" && (u.host == "intelinvest.ru" || u.host == "www.intelinvest.ru")
    } catch (_: Exception) { false }

    private fun startStage(stage: Int) {
        deadlineStage = stage
        retries = 0
        browser?.loadUrl(if (stage == 0) TRADES_URL else POSITIONS_URL)
        handler.postDelayed({ evaluate(stage) }, 4000)
    }

    private fun evaluate(stage: Int) {
        if (finished || stage != deadlineStage) return
        val web = browser ?: return
        try {
            web.evaluateJavascript(script) { encoded ->
                if (finished || stage != deadlineStage) return@evaluateJavascript
                try {
                    val value = JSONTokener(encoded).nextValue() as? String ?: ""
                    val page = JSONObject(value)
                    if (stage == 0) {
                        val valid = extractTrades(page)
                        if (valid != null && !page.optBoolean("loading", false)) {
                            trades = valid
                            diagnostics = "Сделок прочитано: ${valid.size}. Адрес: ${page.optString("url")}"
                            startStage(1)
                        } else retry(stage)
                    } else {
                        val holdings = extractHoldings(page)
                        if (holdings.isNotEmpty() && !page.optBoolean("loading", false)) {
                            diagnostics += ". Позиций с долями: ${holdings.size}"
                            finish(Result.success(Capture(trades, holdings, diagnostics)))
                        } else if (retries < 5) retry(stage)
                        else {
                            diagnostics += ". Состав активов не подтверждён (диаграмма скрыта)"
                            finish(Result.success(Capture(trades, emptyList(), diagnostics)))
                        }
                    }
                } catch (e: Exception) { retry(stage) }
            }
        } catch (_: Exception) { retry(stage) }
    }

    private fun retry(stage: Int) {
        if (finished || stage != deadlineStage) return
        retries++
        if (retries >= (if (stage == 0) 13 else 6)) {
            if (stage == 0) finish(Result.failure(IllegalStateException(
                "Не удалось надёжно прочитать полную таблицу сделок. Снимок НЕ сохранён. " +
                    "Возможны изменение верстки IntelInvest, пагинация или ограничение доступа."
            ))) else finish(Result.success(Capture(trades, emptyList(), "$diagnostics. Нет подтверждённых долей активов")))
        } else handler.postDelayed({ evaluate(stage) }, 2200)
    }

    private fun extractTrades(page: JSONObject): List<TradeRow>? {
        if (page.optBoolean("hasNext") || page.optString("url").contains("login", true)) return null
        val tables = page.optJSONArray("tables") ?: return null
        for (i in 0 until tables.length()) {
            val t = tables.optJSONObject(i) ?: continue
            if (t.optBoolean("truncated", false)) continue
            val headers = stringList(t.optJSONArray("headers"))
            val rows = t.optJSONArray("rows") ?: continue
            // Validate table identity; never treat an arbitrary site table as a trade history.
            val h = headers.joinToString(" ").lowercase(Locale.ROOT)
            val looksLikeTradeHeaders = ("дата" in h || "date" in h) &&
                    (listOf("операц", "сделк", "тип", "колич", "цена", "transaction", "trade").any { it in h })
            if (!looksLikeTradeHeaders || rows.length() == 0) continue
            val parsed = (0 until rows.length()).mapNotNull { j ->
                val obj = rows.optJSONObject(j) ?: return@mapNotNull null
                val values = stringList(obj.optJSONArray("cells"))
                if (values.size < 3) null else TradeRow(obj.optString("id").ifBlank { null }, values)
            }
            if (parsed.isNotEmpty() && parsed.size == rows.length()) return parsed
        }
        return null
    }

    private fun extractHoldings(page: JSONObject): List<Holding> {
        if (page.optBoolean("hasNext")) return emptyList()
        val tables = page.optJSONArray("tables") ?: return emptyList()
        for (i in 0 until tables.length()) {
            val t = tables.optJSONObject(i) ?: continue
            val headers = stringList(t.optJSONArray("headers"))
            val shareIndex = headers.indexOfFirst { Regex("дол[яие]|share|weight|%", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            if (shareIndex < 0) continue
            val tickerIndex = headers.indexOfFirst { Regex("тикер|ticker|symbol", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            val nameIndex = headers.indexOfFirst { Regex("актив|назван|инструмент|бумаг|security|asset|name", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            if (tickerIndex < 0 && nameIndex < 0) continue
            val rows = t.optJSONArray("rows") ?: continue
            val out = mutableListOf<Holding>()
            for (j in 0 until rows.length()) {
                val values = stringList(rows.optJSONObject(j)?.optJSONArray("cells"))
                if (shareIndex !in values.indices) continue
                val pct = Regex("-?\\d+(?:[.,]\\d+)?").find(values[shareIndex])?.value?.replace(',', '.')?.toDoubleOrNull()
                    ?: continue
                if (pct <= 0 || pct > 100) continue
                val tickerRaw = values.getOrNull(tickerIndex)?.trim().orEmpty()
                val nameRaw = values.getOrNull(nameIndex)?.trim().orEmpty()
                val ticker = tickerRaw.ifEmpty { nameRaw.take(24) }
                val name = nameRaw.ifEmpty { ticker }
                if (ticker.isNotBlank()) out += Holding(ticker.take(30), name.take(100), pct)
            }
            val sum = out.sumOf { it.percent }
            if (out.isNotEmpty() && sum in 90.0..110.0) return out
        }
        return emptyList()
    }

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it).trim() }
    }

    private fun finish(result: Result<Capture>) {
        if (finished) return
        finished = true
        val web = browser
        browser = null
        handler.post { try { web?.stopLoading(); web?.destroy() } catch (_: Exception) { } }
        onDone(result)
    }
}
