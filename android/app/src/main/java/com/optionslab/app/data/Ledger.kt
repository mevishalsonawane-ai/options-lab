package com.optionslab.app.data

import android.content.Context
import com.optionslab.app.security.Vault
import com.optionslab.engine.ExpiryPut
import com.optionslab.engine.Live
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * The paper ledger (port of `live.record_ticket` / `settle_ticket` /
 * `read_ledger`), held encrypted in the vault.
 *
 * A TICKET IS NOT A TRADE. Every row is stamped paper=true. Recording a
 * session twice is refused - it would invent a second trade never placed.
 */
object Ledger {
    private lateinit var file: File

    /** [orders] holds Zerodha order ids once the ticket was actually sent; empty means paper. */
    data class Entry(val row: Live.LedgerRow, val shortKey: String?, val wingKey: String?, val orders: List<String> = emptyList()) {
        val live: Boolean get() = orders.isNotEmpty()
    }

    fun init(context: Context) { file = File(context.noBackupFilesDir, "ledger.vault") }

    @Synchronized
    fun all(): List<Entry> {
        // An unreadable ledger is moved aside, never overwritten by the next record().
        return try {
            val bytes = Vault.readFileSteady(file) ?: return emptyList()
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.row.ticket.session }
        } catch (_: Exception) {
            Vault.setAside(file); emptyList()
        }
    }

    @Synchronized
    private fun saveAll(rows: List<Entry>) {
        val arr = JSONArray()
        rows.sortedBy { it.row.ticket.session }.forEach { arr.put(toJson(it)) }
        Vault.writeFile(file, arr.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun record(ticket: Live.Ticket, shortKey: String?, wingKey: String?): Entry {
        val rows = all()
        if (rows.any { it.row.ticket.session == ticket.session }) throw Live.AlreadyRecorded(
            "a ticket for ${ticket.session} already exists; recording it twice would invent a second trade that was never placed")
        val e = Entry(Live.LedgerRow(ticket, "open", recordedAtMillis = System.currentTimeMillis()), shortKey, wingKey)
        saveAll(rows + e)
        return e
    }

    @Synchronized
    fun settle(session: LocalDate, settlement: Double, regime: String): Entry {
        val rows = all()
        val open = rows.firstOrNull { it.row.ticket.session == session }
            ?: throw Live.NotRecorded("no open ticket for $session; there is nothing to settle")
        val settled = open.copy(row = Live.settle(open.row, settlement, regime))
        saveAll(rows.map { if (it.row.ticket.session == session) settled else it })
        return settled
    }

    /**
     * The ticket was sent to Zerodha. Its credit becomes the ACTUAL net fill,
     * so settlement - and every health check after it - measures the trade
     * that happened, not the one that was priced.
     */
    @Synchronized
    fun attachOrders(session: LocalDate, orderIds: List<String>, shortFill: Double?, wingFill: Double?) {
        saveAll(all().map { e ->
            if (e.row.ticket.session != session) e else {
                val t = e.row.ticket
                // Both legs take their ACTUAL fills: Live.settle rebuilds the
                // short leg's credit as net credit + wing debit, so replacing
                // only the net figure would misstate the hedged P&L.
                val wing = if (t.wingStrike != null) (wingFill ?: t.wingDebit) else null
                val tk = if (shortFill != null && shortFill > 0) {
                    val net = shortFill - (wing ?: 0.0)
                    t.copy(credit = net, breakeven = t.strike - net, wingDebit = wing)
                } else t
                e.copy(row = e.row.copy(ticket = tk), orders = e.orders + orderIds)
            }
        })
    }

    @Synchronized
    fun delete(session: LocalDate) = saveAll(all().filter { it.row.ticket.session != session })

    fun openTicket(): Entry? = all().firstOrNull { it.row.status == "open" }

    /** Settled rows in the schema the health checks read. Open intentions are excluded. */
    fun settledRows() = all().mapNotNull { it.row.toMonitorRow() }

    fun wipe() { file.delete() }

    fun csv(): String = buildString {
        append("session,underlying,expiry,side,right,strike,lot_size,lots,qty,credit,forward,breakeven,margin,max_loss,wing_strike,wing_debit,status,settlement,net_pnl,won,paper,orders\n")
        for (e in all().sortedBy { it.row.ticket.session }) {
            val t = e.row.ticket
            val tr = e.row.trade
            append(listOf(t.session, t.underlying, t.expiry, t.side, t.right, t.strike, t.lotSize, t.lots, t.qty,
                t.credit, t.forward, t.breakeven, t.margin, t.maxLoss ?: "", t.wingStrike ?: "", t.wingDebit ?: "",
                e.row.status, e.row.settlement ?: "", tr?.netPnl ?: "", tr?.won ?: "", (!e.live).toString(), e.orders.joinToString(" ")).joinToString(","))
            append('\n')
        }
    }

    private fun toJson(e: Entry): JSONObject {
        val t = e.row.ticket
        val o = JSONObject()
            .put("session", t.session.toString()).put("underlying", t.underlying).put("expiry", t.expiry.toString())
            .put("side", t.side).put("right", t.right).put("strike", t.strike).put("lot_size", t.lotSize)
            .put("lots", t.lots).put("qty", t.qty).put("credit", t.credit).put("forward", t.forward)
            .put("breakeven", t.breakeven).put("margin", t.margin)
            .put("max_loss", t.maxLoss ?: JSONObject.NULL).put("wing_strike", t.wingStrike ?: JSONObject.NULL)
            .put("wing_debit", t.wingDebit ?: JSONObject.NULL)
            .put("status", e.row.status).put("paper", !e.live).put("recorded_at", e.row.recordedAtMillis)
            .put("orders", JSONArray(e.orders))
            .put("short_key", e.shortKey ?: JSONObject.NULL).put("wing_key", e.wingKey ?: JSONObject.NULL)
        e.row.settlement?.let { o.put("settlement", it) }
        e.row.trade?.let { tr ->
            o.put("net_credit", tr.credit).put("intrinsic", tr.intrinsic).put("gross_pnl", tr.grossPnl)
                .put("cost", tr.cost).put("net_pnl", tr.netPnl).put("won", tr.won)
                .put("wing_intrinsic", tr.wingIntrinsic)
        }
        return o
    }

    private fun JSONObject.d(k: String): Double? = if (isNull(k) || !has(k)) null else getDouble(k)
    private fun JSONObject.s(k: String): String? = if (isNull(k) || !has(k)) null else getString(k)

    private fun fromJson(o: JSONObject): Entry {
        val t = Live.Ticket(
            session = LocalDate.parse(o.getString("session")), underlying = o.getString("underlying"),
            expiry = LocalDate.parse(o.getString("expiry")), side = o.getString("side"), right = o.getString("right"),
            strike = o.getDouble("strike"), lotSize = o.getInt("lot_size"), lots = o.getInt("lots"), qty = o.getInt("qty"),
            credit = o.getDouble("credit"), forward = o.getDouble("forward"), breakeven = o.getDouble("breakeven"),
            margin = o.getDouble("margin"), maxLoss = o.d("max_loss"), wingStrike = o.d("wing_strike"), wingDebit = o.d("wing_debit"),
        )
        val settlement = o.d("settlement")
        val trade = if (o.has("net_pnl")) ExpiryPut.Trade(
            session = t.session, strike = t.strike, credit = o.getDouble("net_credit"), settlement = settlement ?: 0.0,
            intrinsic = o.getDouble("intrinsic"), lotSize = t.lotSize, lots = t.lots, qty = t.qty,
            wingStrike = t.wingStrike, wingDebit = t.wingDebit ?: 0.0, wingIntrinsic = o.optDouble("wing_intrinsic", 0.0),
            maxLoss = t.maxLoss, grossPnl = o.getDouble("gross_pnl"), cost = o.getDouble("cost"), netPnl = o.getDouble("net_pnl"),
            forward = t.forward, otmRealised = (t.forward - t.strike) / t.forward,
        ) else null
        val orders = o.optJSONArray("orders")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return Entry(Live.LedgerRow(t, o.getString("status"), settlement, trade, o.optLong("recorded_at")), o.s("short_key"), o.s("wing_key"), orders)
    }
}

/** Price alarms: "tell me when NIFTY closes a minute below 24,000". */
data class PriceAlarm(
    val id: Long,
    val symbol: String,            // NIFTY | BANKNIFTY | INDIAVIX, or any instrument as EXCHANGE:SYMBOL (e.g. NSE:INFY)
    val above: Boolean,
    val level: Double,
    val enabled: Boolean = true,
    val firedAtMillis: Long = 0L,
    val note: String = "",
) {
    fun describe() = "${symbol.removePrefix(CHART)} ${if (above) "rises above" else "falls below"} ${"%,.2f".format(level)}"

    companion object {
        /** An alarm set from the chart: priced from the chart's own feed, in Paper and Live alike. */
        const val CHART = "CHART:"
    }
    fun hit(price: Double) = if (above) price >= level else price <= level
}

object Alarms {
    private lateinit var file: File

    fun init(context: Context) { file = File(context.noBackupFilesDir, "alarms.vault") }

    @Synchronized
    fun all(): List<PriceAlarm> {
        return try {
            val bytes = Vault.readFileSteady(file) ?: return emptyList()
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                PriceAlarm(o.getLong("id"), o.getString("symbol"), o.getBoolean("above"), o.getDouble("level"),
                    o.optBoolean("enabled", true), o.optLong("fired", 0L), o.optString("note", ""))
            }
        } catch (_: Exception) {
            Vault.setAside(file); emptyList()
        }
    }

    @Synchronized
    fun save(list: List<PriceAlarm>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("symbol", it.symbol).put("above", it.above).put("level", it.level)
                .put("enabled", it.enabled).put("fired", it.firedAtMillis).put("note", it.note))
        }
        Vault.writeFile(file, arr.toString().toByteArray(Charsets.UTF_8))
    }

    fun upsert(a: PriceAlarm) = save(all().filter { it.id != a.id } + a)
    fun remove(id: Long) = save(all().filter { it.id != id })
    fun wipe() { file.delete() }
}
