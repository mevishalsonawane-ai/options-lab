package com.optionslab.ira.dhan

import java.io.BufferedReader
import java.time.LocalDate

/**
 * What the Dhan store downloads: the indices (and their options and futures) and the companies and banks in them.
 *
 * Security IDs are read from Dhan's scrip master ([parseMaster]) - an index's ID from its own INDEX row (segment IDX_I:
 * NIFTY 13, BANKNIFTY 25, ...), a company's from its NSE equity row, the near future's from the futures rows. An index
 * is NEVER asked by its options' UNDERLYING_SECURITY_ID (NIFTY 26000, BANKNIFTY 26009, SENSEX 1, ...): tested live,
 * charts/rollingoption returns no data for those, it needs the IDX_I ID. The [Index.fallbackId]s (the IDX_I IDs) are
 * used when the master has no INDEX row for it or could not be read. Index MEMBERSHIP is not in the scrip master, so [CONSTITUENTS] is a static list -
 * see its date; a symbol the master does not know (a renamed or demerged company) is skipped and named on the screen.
 */
object DhanUniverse {
    /**
     * An index: its [name] (as the app names it), [masterNames] it may carry in the master, its options' [optSegment]
     * (NSE_FNO or BSE_FNO) and rolling [flag] (WEEK where weekly contracts trade, MONTH otherwise), and whether it has
     * options at all.
     */
    data class Index(val name: String, val masterNames: List<String>, val fallbackId: String?, val optSegment: String,
                     val flag: String, val options: Boolean = true)

    val INDICES: List<Index> = listOf(
        Index("NIFTY", listOf("NIFTY", "NIFTY 50", "NIFTY50"), "13", "NSE_FNO", "WEEK"),
        Index("BANKNIFTY", listOf("BANKNIFTY", "NIFTY BANK", "BANK NIFTY"), "25", "NSE_FNO", "MONTH"),
        Index("FINNIFTY", listOf("FINNIFTY", "NIFTY FIN SERVICE", "NIFTY FINANCIAL SERVICES"), "27", "NSE_FNO", "MONTH"),
        Index("MIDCPNIFTY", listOf("MIDCPNIFTY", "NIFTY MID SELECT", "NIFTY MIDCAP SELECT"), "442", "NSE_FNO", "MONTH"),
        Index("NIFTYNXT50", listOf("NIFTYNXT50", "NIFTY NEXT 50", "NIFTY NXT 50", "NIFTYNEXT50"), "38", "NSE_FNO", "MONTH"),
        Index("SENSEX", listOf("SENSEX", "BSE SENSEX", "S&P BSE SENSEX"), "51", "BSE_FNO", "WEEK"),
        Index("BANKEX", listOf("BANKEX", "BSE BANKEX", "S&P BSE BANKEX"), "69", "BSE_FNO", "MONTH"),
        Index("INDIAVIX", listOf("INDIA VIX", "INDIAVIX"), "21", "NSE_FNO", "MONTH", options = false),
    )

    fun index(name: String): Index? = INDICES.firstOrNull { it.name == name }

    /**
     * The members of each index, by NSE symbol. AS OF 2025-09 (the last list known when this was written, 2026-10-06):
     * index committees change members twice a year, so this is a starting list for Boss to edit, not a record. A company
     * no longer in an index only adds data; one missing is simply not downloaded.
     */
    const val CONSTITUENTS_AS_OF = "2025-09"
    val CONSTITUENTS: Map<String, List<String>> = mapOf(
        "NIFTY" to listOf("ADANIENT", "ADANIPORTS", "APOLLOHOSP", "ASIANPAINT", "AXISBANK", "BAJAJ-AUTO", "BAJFINANCE", "BAJAJFINSV",
            "BEL", "BHARTIARTL", "CIPLA", "COALINDIA", "DRREDDY", "EICHERMOT", "ETERNAL", "GRASIM", "HCLTECH", "HDFCBANK", "HDFCLIFE",
            "HINDALCO", "HINDUNILVR", "ICICIBANK", "INDIGO", "INFY", "ITC", "JIOFIN", "JSWSTEEL", "KOTAKBANK", "LT", "M&M", "MARUTI",
            "MAXHEALTH", "NESTLEIND", "NTPC", "ONGC", "POWERGRID", "RELIANCE", "SBILIFE", "SBIN", "SHRIRAMFIN", "SUNPHARMA", "TATACONSUM",
            "TATAMOTORS", "TATASTEEL", "TCS", "TECHM", "TITAN", "TRENT", "ULTRACEMCO", "WIPRO"),
        "BANKNIFTY" to listOf("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "INDUSINDBK", "BANKBARODA", "AUBANK",
            "FEDERALBNK", "IDFCFIRSTB", "PNB", "CANBK", "UNIONBANK", "YESBANK"),
        "FINNIFTY" to listOf("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "BAJFINANCE", "BAJAJFINSV", "SHRIRAMFIN",
            "HDFCLIFE", "SBILIFE", "CHOLAFIN", "PFC", "RECLTD", "ICICIGI", "ICICIPRULI", "MUTHOOTFIN", "SBICARD", "JIOFIN", "HDFCAMC",
            "BSE", "LICHSGFIN"),
        "MIDCPNIFTY" to listOf("PERSISTENT", "COFORGE", "CUMMINSIND", "HDFCAMC", "INDHOTEL", "LUPIN", "AUROPHARMA", "FEDERALBNK",
            "IDFCFIRSTB", "VOLTAS", "POLYCAB", "BHARATFORG", "MPHASIS", "GODREJPROP", "ASHOKLEY", "HINDPETRO", "AUBANK", "DIXON",
            "SUZLON", "PBFINTECH", "CONCOR", "IDEA", "YESBANK", "BSE", "PAYTM"),
        "NIFTYNXT50" to listOf("ABB", "ADANIENSOL", "ADANIGREEN", "ADANIPOWER", "AMBUJACEM", "BAJAJHLDNG", "BAJAJHFL", "BANKBARODA",
            "BOSCHLTD", "BPCL", "BRITANNIA", "CANBK", "CGPOWER", "CHOLAFIN", "DABUR", "DIVISLAB", "DLF", "DMART", "GAIL", "GODREJCP",
            "HAL", "HAVELLS", "HINDZINC", "HYUNDAI", "ICICIGI", "ICICIPRULI", "INDHOTEL", "IOC", "IRFC", "JINDALSTEL", "JSWENERGY",
            "LICI", "LODHA", "LTIM", "MOTHERSON", "NAUKRI", "PFC", "PIDILITIND", "PNB", "RECLTD", "SHREECEM", "SIEMENS", "SOLARINDS",
            "SWIGGY", "TATAPOWER", "TORNTPHARM", "TVSMOTOR", "UNITDSPR", "VBL", "VEDL", "ZYDUSLIFE"),
        "SENSEX" to listOf("ADANIPORTS", "ASIANPAINT", "AXISBANK", "BAJAJFINSV", "BAJFINANCE", "BEL", "BHARTIARTL", "ETERNAL",
            "HCLTECH", "HDFCBANK", "HINDUNILVR", "ICICIBANK", "INFY", "ITC", "KOTAKBANK", "LT", "M&M", "MARUTI", "NTPC", "POWERGRID",
            "RELIANCE", "SBIN", "SUNPHARMA", "TATAMOTORS", "TATASTEEL", "TCS", "TECHM", "TITAN", "TRENT", "ULTRACEMCO"),
        "BANKEX" to listOf("HDFCBANK", "ICICIBANK", "SBIN", "KOTAKBANK", "AXISBANK", "INDUSINDBK", "BANKBARODA", "AUBANK",
            "FEDERALBNK", "IDFCFIRSTB"),
    )

    /** Every company in any index, once, in the order first named. */
    fun allConstituents(): List<String> = CONSTITUENTS.values.flatten().distinct()

    // ---- the scrip master -------------------------------------------------------------------------------------------

    /** What the master gave: index IDs by app name, equity IDs by NSE symbol, the near future's ID by underlying. */
    data class Master(val indexIds: Map<String, String>, val equityIds: Map<String, String>, val nearFutures: Map<String, Future>)

    /** A futures contract: its [id], [segment] ("NSE_FNO"/"BSE_FNO"), [instrument] (FUTIDX/FUTSTK) and [expiry]. */
    data class Future(val id: String, val segment: String, val instrument: String, val expiry: LocalDate)

    // Column names in the detailed master, with the compact master's names as a second choice.
    private val C_EXCH = listOf("EXCH_ID", "SEM_EXM_EXCH_ID")
    private val C_SEG = listOf("SEGMENT", "SEM_SEGMENT")
    private val C_ID = listOf("SECURITY_ID", "SEM_SMST_SECURITY_ID")
    private val C_INSTR = listOf("INSTRUMENT", "SEM_INSTRUMENT_NAME")
    private val C_UNDER = listOf("UNDERLYING_SYMBOL")
    private val C_SYMBOL = listOf("SYMBOL_NAME", "SM_SYMBOL_NAME")
    private val C_TRADING = listOf("SEM_TRADING_SYMBOL", "TRADING_SYMBOL")
    private val C_SERIES = listOf("SERIES", "SEM_SERIES")
    private val C_EXPIRY = listOf("SM_EXPIRY_DATE", "SEM_EXPIRY_DATE")

    /** One CSV line split on commas, honouring double quotes. */
    fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString().trim(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString().trim()
        return out
    }

    private fun upper(s: String?) = s?.trim()?.uppercase().orEmpty()

    /**
     * Read the master as a stream (tens of MB: never held whole), keeping only what the store needs for [symbols] (NSE
     * equity symbols) and [INDICES]. [today]: futures expiring before it are ignored.
     */
    fun parseMaster(reader: BufferedReader, symbols: Set<String>, today: LocalDate): Master {
        val header = reader.readLine()?.let { splitCsv(it.removePrefix("﻿")).map(::upper) } ?: return Master(emptyMap(), emptyMap(), emptyMap())
        fun col(names: List<String>) = names.firstNotNullOfOrNull { n -> header.indexOf(n).takeIf { it >= 0 } } ?: -1
        val cExch = col(C_EXCH); val cSeg = col(C_SEG); val cId = col(C_ID); val cInstr = col(C_INSTR)
        val cUnder = col(C_UNDER); val cSym = col(C_SYMBOL); val cTrading = col(C_TRADING)
        val cSeries = col(C_SERIES); val cExpiry = col(C_EXPIRY)
        val wantedIndex = INDICES.flatMap { ix -> ix.masterNames.map { upper(it) to ix.name } }.toMap()
        val indexIds = HashMap<String, String>()
        val equities = HashMap<String, String>()
        val futures = HashMap<String, Future>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val f = splitCsv(line)
            fun at(c: Int) = if (c in f.indices) f[c] else ""
            val instr = upper(at(cInstr))
            val exch = upper(at(cExch))
            val id = at(cId).removeSuffix(".0")
            if (id.isEmpty()) continue
            val names = listOf(upper(at(cUnder)), upper(at(cSym)), upper(at(cTrading))).filter { it.isNotEmpty() }
            when (instr) {
                // The index's own row (segment I, Dhan's IDX_I): the ID every chart and rolling-option request needs.
                "INDEX" -> if (cSeg < 0 || upper(at(cSeg)) in setOf("I", "IDX_I")) {
                    names.firstNotNullOfOrNull { wantedIndex[it] }?.let { indexIds.putIfAbsent(it, id) }
                }
                "FUTIDX" -> {
                    val app = wantedIndex[upper(at(cUnder))] ?: wantedIndex[upper(at(cSym)).substringBefore('-')]
                    if (app != null) future(app, id, exch, instr, at(cExpiry), today, futures)
                }
                "EQUITY" -> if (exch == "NSE" && (cSeries < 0 || upper(at(cSeries)) in setOf("EQ", "BE", ""))) {
                    names.firstOrNull { it in symbols }?.let { equities.putIfAbsent(it, id) }
                }
                "FUTSTK" -> {
                    val under = upper(at(cUnder)).ifEmpty { upper(at(cSym)).substringBefore('-') }
                    if (under in symbols) future(under, id, exch, instr, at(cExpiry), today, futures)
                }
            }
        }
        // Only the IDX_I rows' IDs: the options' UNDERLYING_SECURITY_ID (26000, 26009, ...) gets no rolling-option data.
        return Master(indexIds, equities, futures)
    }

    private fun future(key: String, id: String, exch: String, instr: String, expiryText: String, today: LocalDate, into: HashMap<String, Future>) {
        val expiry = runCatching { LocalDate.parse(expiryText.trim().take(10)) }.getOrNull() ?: return
        if (expiry.isBefore(today)) return
        val seg = if (exch == "BSE") "BSE_FNO" else "NSE_FNO"
        val cur = into[key]
        if (cur == null || expiry.isBefore(cur.expiry)) into[key] = Future(id, seg, instr, expiry)
    }

    /** The ID [ix] is asked by: the master's, else its fallback. */
    fun indexId(ix: Index, master: Master?): String? = master?.indexIds?.get(ix.name) ?: ix.fallbackId

    // ---- the saved subset of the master ------------------------------------------------------------------------------

    /** The master's subset, as the few lines the store keeps (so a resumed run does not download the master again). */
    fun writeMaster(m: Master): String = buildString {
        append("$MASTER_HEADER\n")
        for ((k, v) in m.indexIds.toSortedMap()) append("I,$k,$v,,,\n")
        for ((k, v) in m.equityIds.toSortedMap()) append("E,$k,$v,,,\n")
        for ((k, v) in m.nearFutures.toSortedMap()) append("F,$k,${v.id},${v.segment},${v.instrument},${v.expiry}\n")
    }

    /** The kept subset's header; "v2": its index IDs are the IDX_I ones (an older file's are options' underlying IDs). */
    const val MASTER_HEADER = "kind,key,id,segment,instrument,expiry,v2"

    /** The kept subset. An older file (no "v2") may hold the options' underlying IDs: its index IDs are dropped (fallbacks used). */
    fun readMaster(text: String): Master {
        val ix = HashMap<String, String>(); val eq = HashMap<String, String>(); val fut = HashMap<String, Future>()
        val current = text.lineSequence().firstOrNull()?.trim() == MASTER_HEADER
        for (line in text.lineSequence().drop(1)) {
            val f = line.split(',')
            if (f.size < 6) continue
            when (f[0]) {
                "I" -> if (current) ix[f[1]] = f[2]
                "E" -> eq[f[1]] = f[2]
                "F" -> runCatching { LocalDate.parse(f[5]) }.getOrNull()?.let { fut[f[1]] = Future(f[2], f[3], f[4], it) }
            }
        }
        return Master(ix, eq, fut)
    }
}
