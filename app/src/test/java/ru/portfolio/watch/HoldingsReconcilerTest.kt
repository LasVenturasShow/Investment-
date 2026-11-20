package ru.portfolio.watch

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class HoldingsReconcilerTest {
    private fun d(s: String) = BigDecimal(s)
    private val cutoff = "2026-11-13 18:27:00"
    private fun op(id: String, ticker: String, kind: String, qty: String, date: String = cutoff, cash: String? = null) =
        LedgerOperation("$id-$kind-$date", id, ticker, ticker, "STOCK", kind, date, d(qty), cash?.let(::d))
    private fun pos(id: String, ticker: String, qty: String, value: String) =
        OverviewPosition(id, ticker, ticker, "STOCK", d(qty), d(value), null, d(value))
    private fun money(value: String) =
        OverviewPosition("101236","RUB","Денежные средства","MONEY",d(value),d(value),d("1"),null)
    @Test fun fullySoldRealTickersDisappearBeforeOverviewRefresh() {
        val source = listOf(pos("1318","NLMK","3900","273000"),pos("1334","CHMF","400","253920"),
            pos("244447","RAGR","3000","200820"),pos("1168","MTSS","1600","282080"),money("62.51"))
        val history = listOf(op("1318","NLMK","BUY","3900"),op("1334","CHMF","BUY","400"),
            op("244447","RAGR","BUY","3000"),op("1168","MTSS","BUY","1600"),
            op("1318","NLMK","SELL","3900","2026-11-20 16:30:57","287820"),
            op("1334","CHMF","SELL","400","2026-11-20 16:31:43","270000"),
            op("244447","RAGR","SELL","3000","2026-11-20 16:33:14","207000"))
        val r = HoldingsReconciler.reconcile(history,source,cutoff,null)
        assertTrue(r.corrected)
        assertEquals(setOf("MTSS","RUB"),r.positions.map { it.ticker }.toSet())
        assertEquals(0,d("764882.51").compareTo(r.positions.single { it.ticker=="RUB" }.value))
        assertEquals(0,d("1046962.51").compareTo(r.total))
    }

    @Test fun partialSaleAndRebuyDoNotReuseOldCostBasis() {
        val source = listOf(pos("1","AAA","100","1000"),money("1000"))
        val history = listOf(op("1","AAA","BUY","100"),
            op("1","AAA","SELL","40","2026-11-20 10:00:00","400"),
            op("1","AAA","BUY","20","2026-11-20 11:00:00","-200"))
        val r = HoldingsReconciler.reconcile(history,source,cutoff,null)
        val p=r.positions.single { it.ticker=="AAA" }
        assertEquals(0,d("80").compareTo(p.quantity))
        assertEquals(0,d("800").compareTo(p.value))
        assertNull(p.invested)
        assertEquals(0,d("1200").compareTo(r.positions.single { it.ticker=="RUB" }.value))
    }

    @Test fun missingQuoteAndMissingSettlementDoNotFabricateValuation() {
        val source = listOf(pos("1","AAA","100","1000"),money("1000"))
        val history = listOf(op("1","AAA","BUY","100"),
            op("1","AAA","SELL","100","2026-11-20 10:00:00"),
            op("2","BBB","BUY","5","2026-11-20 11:00:00"))
        val r=HoldingsReconciler.reconcile(history,source,cutoff,null)
        assertEquals(listOf("BBB"),r.positions.map { it.ticker })
        assertEquals(0,d("5").compareTo(r.positions[0].quantity))
        assertNull(r.positions[0].value)
        assertNull(r.total)
    }

    @Test fun freshOverviewIncludesCashAndNoClosedSecurity() {
        val history=listOf(op("1","AAA","BUY","100"),
            op("1","AAA","SELL","100","2026-11-20 10:00:00","1000"))
        val source=listOf(pos("1","AAA","0","0"),money("1500"))
        val r=HoldingsReconciler.reconcile(history,source,"2026-11-20 10:00:00",d("1500"))
        assertFalse(r.corrected)
        assertEquals(listOf("RUB"),r.positions.map{it.ticker})
        assertEquals(0,d("1500").compareTo(r.total))
    }

    @Test(expected = IllegalArgumentException::class)
    fun inconsistentHistoricalAnchorIsRejected() {
        HoldingsReconciler.reconcile(listOf(op("1","AAA","BUY","100")),
            listOf(pos("1","AAA","90","900"),money("100")),cutoff,null)
    }

    @Test fun livePublicFixture() {
        val path=System.getenv("PORTFOLIO_FIXTURE") ?: return
        val lines=java.io.File(path).readLines()
        val meta=lines.first().split('\t')
        val source=lines.filter{it.startsWith("P\t")}.map{
            val f=it.split('\t')
            OverviewPosition(f[1],f[2],f[2],f[3],d(f[4]),f[5].toBigDecimalOrNull(),
                f[6].toBigDecimalOrNull(),f[7].toBigDecimalOrNull())
        }
        val trades=lines.filter{it.startsWith("T\t")}.map{
            val f=it.split('\t')
            LedgerOperation(f[1],f[2],f[3],f[3],f[4],f[5],f[6],f[7].toBigDecimalOrNull(),f[8].toBigDecimalOrNull())
        }
        val r=HoldingsReconciler.reconcile(trades,source,meta[1],meta[2].toBigDecimalOrNull())
        assertTrue(trades.size > 700)
        assertTrue(r.positions.isNotEmpty())
        assertTrue(r.positions.all { it.quantity.signum() > 0 })
        // Independent ledger check across the complete history, keyed by the actual share ID.
        val net=trades.filter{it.asset!="MONEY" && it.operation in setOf("BUY","SELL")}
            .groupBy{it.shareId}.mapValues{(_,ts)->ts.fold(BigDecimal.ZERO){a,t->
                if(t.operation=="BUY")a.add(t.quantity!!) else a.subtract(t.quantity!!)
            }}
        assertEquals(net.filterValues{it.signum()>0}.keys,r.positions.filter{it.ticker!="RUB"}.map{it.shareId}.toSet())
        println("LIVE: "+trades.size+" trades; "+r.positions.map{it.ticker+"="+it.quantity})
    }
}
