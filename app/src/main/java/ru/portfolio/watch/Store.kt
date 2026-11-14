package ru.portfolio.watch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class PortfolioDb(context: Context) : SQLiteOpenHelper(context, "portfolio_watch.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshots(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL, trades TEXT NOT NULL)")
        db.execSQL("CREATE TABLE positions(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL, holdings TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp_ms INTEGER NOT NULL, type TEXT NOT NULL, label TEXT NOT NULL, description TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}

data class HistoryEntry(val timestamp: Long, val change: PortfolioChange)

data class MonitorState(
    val lastChecked: Long = 0,
    val tradesCount: Int = 0,
    val holdings: List<Holding> = emptyList(),
    val holdingsCheckedAt: Long = 0,
    val events: List<HistoryEntry> = emptyList(),
    val status: String = "Ещё не проверялось",
    val enabled: Boolean = false,
    val intervalMinutes: Long = 30
)

data class CheckOutcome(val initial: Boolean, val changes: List<PortfolioChange>, val checkedAt: Long, val diagnostic: String)

/** Only persists complete, validated trades snapshots. Failed collection MUST NOT replace the baseline. */
class PortfolioStore(context: Context) {
    private val db = PortfolioDb(context.applicationContext)
    private val prefs = context.applicationContext.getSharedPreferences("monitor_settings", Context.MODE_PRIVATE)

    @Synchronized
    fun record(capture: Capture): CheckOutcome {
        require(capture.trades.isNotEmpty()) { "Пустая таблица не является корректным снимком." }
        val timestamp = System.currentTimeMillis()
        val database = db.writableDatabase
        database.beginTransaction()
        return try {
            val prior = readPrior(database)
            val changes = (if (prior == null) emptyList() else DiffCore.compare(prior, capture.trades)).toMutableList()
            val previousHoldings = readPriorHoldings(database)
            if (previousHoldings != null && capture.holdings.isNotEmpty()) {
                // Market price/percentage changes are NOT trades; only appearance/disappearance of an asset is reported.
                val previous = previousHoldings.associateBy { it.ticker.trim().uppercase() }
                val current = capture.holdings.associateBy { it.ticker.trim().uppercase() }
                for ((key, asset) in current) if (key !in previous) changes +=
                    PortfolioChange("POSITION_ADDED", "Новый актив в составе", "${asset.ticker} · ${asset.name}")
                for ((key, asset) in previous) if (key !in current) changes +=
                    PortfolioChange("POSITION_REMOVED", "Актив исчез из состава", "${asset.ticker} · ${asset.name}")
            }
            database.insertOrThrow("snapshots", null, ContentValues().apply {
                put("timestamp_ms", timestamp)
                put("trades", tradesToJson(capture.trades).toString())
            })
            if (capture.holdings.isNotEmpty()) database.insertOrThrow("positions", null, ContentValues().apply {
                put("timestamp_ms", timestamp)
                put("holdings", holdingsToJson(capture.holdings).toString())
            })
            for (change in changes) database.insertOrThrow("events", null, ContentValues().apply {
                put("timestamp_ms", timestamp)
                put("type", change.type)
                put("label", change.label)
                put("description", change.description)
            })
            database.execSQL("DELETE FROM snapshots WHERE id NOT IN (SELECT id FROM snapshots ORDER BY id DESC LIMIT 200)")
            database.execSQL("DELETE FROM positions WHERE id NOT IN (SELECT id FROM positions ORDER BY id DESC LIMIT 200)")
            database.setTransactionSuccessful()
            CheckOutcome(prior == null, changes, timestamp, capture.diagnostics)
        } finally { database.endTransaction() }
    }

    @Synchronized
    fun readState(): MonitorState {
        val database = db.readableDatabase
        var checked = 0L
        var count = 0
        database.rawQuery("SELECT timestamp_ms,trades FROM snapshots ORDER BY id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) {
                checked = c.getLong(0)
                count = jsonToTrades(JSONArray(c.getString(1))).size
            }
        }
        var holdingsChecked = 0L
        var holdings: List<Holding> = emptyList()
        database.rawQuery("SELECT timestamp_ms,holdings FROM positions ORDER BY id DESC LIMIT 1", null).use { c ->
            if (c.moveToFirst()) {
                holdingsChecked = c.getLong(0)
                holdings = jsonToHoldings(JSONArray(c.getString(1)))
            }
        }
        val events = mutableListOf<HistoryEntry>()
        database.rawQuery("SELECT timestamp_ms,type,label,description FROM events ORDER BY id DESC LIMIT 40", null).use { c ->
            while (c.moveToNext()) events.add(HistoryEntry(c.getLong(0), PortfolioChange(c.getString(1), c.getString(2), c.getString(3))))
        }
        return MonitorState(checked, count, holdings, holdingsChecked, events,
            if (checked == 0L) "Снимка ещё нет" else "Последняя проверка: ${dateText(checked)}",
            prefs.getBoolean("enabled", false), prefs.getLong("intervalMinutes", 30))
    }

    fun setSchedule(enabled: Boolean, minutes: Long) {
        prefs.edit().putBoolean("enabled", enabled).putLong("intervalMinutes", minutes).apply()
    }

    private fun readPriorHoldings(database: SQLiteDatabase): List<Holding>? {
        database.rawQuery("SELECT holdings FROM positions ORDER BY id DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return null
            return jsonToHoldings(JSONArray(c.getString(0)))
        }
    }

    private fun readPrior(database: SQLiteDatabase): List<TradeRow>? {
        database.rawQuery("SELECT trades FROM snapshots ORDER BY id DESC LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) return null
            return jsonToTrades(JSONArray(c.getString(0)))
        }
    }
}

fun dateText(millis: Long): String = if (millis == 0L) "—" else
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru", "RU")).format(Date(millis))

fun tradesToJson(rows: List<TradeRow>): JSONArray = JSONArray().apply {
    for (row in rows) put(JSONObject().apply {
        put("id", row.stableId ?: JSONObject.NULL)
        put("cells", JSONArray(row.cells))
    })
}
fun jsonToTrades(arr: JSONArray): List<TradeRow> = (0 until arr.length()).map { i ->
    val o = arr.getJSONObject(i)
    val cells = o.getJSONArray("cells")
    TradeRow(if (o.isNull("id")) null else o.getString("id"), (0 until cells.length()).map { cells.getString(it) })
}
fun holdingsToJson(rows: List<Holding>): JSONArray = JSONArray().apply {
    rows.forEach { put(JSONObject().put("ticker", it.ticker).put("name", it.name).put("percent", it.percent)) }
}
fun jsonToHoldings(arr: JSONArray): List<Holding> = (0 until arr.length()).map { i ->
    val o = arr.getJSONObject(i)
    Holding(o.getString("ticker"), o.getString("name"), o.getDouble("percent"))
}
