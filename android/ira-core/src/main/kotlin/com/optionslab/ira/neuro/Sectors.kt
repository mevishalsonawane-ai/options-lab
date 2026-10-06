package com.optionslab.ira.neuro

/**
 * The sector of each company in [com.optionslab.ira.dhan.DhanUniverse.CONSTITUENTS], for the graph's IN_SECTOR edges.
 * A static starting list AS OF 2025-09 (written 2026-10-06), broad NSE-style groups: a company's business can change and
 * a symbol not listed here simply has no sector edge. Banks are their own sector, and their nodes are BANK nodes.
 */
object Sectors {
    const val AS_OF = "2025-09"

    const val BANKS = "Banks"

    private val BY_SECTOR: Map<String, List<String>> = mapOf(
        BANKS to listOf("AUBANK", "AXISBANK", "BANKBARODA", "CANBK", "FEDERALBNK", "HDFCBANK", "ICICIBANK", "IDFCFIRSTB", "INDUSINDBK",
            "KOTAKBANK", "PNB", "SBIN", "UNIONBANK", "YESBANK"),
        "Financial services" to listOf("BAJAJFINSV", "BAJAJHFL", "BAJAJHLDNG", "BAJFINANCE", "BSE", "CHOLAFIN", "HDFCAMC", "IRFC", "JIOFIN",
            "LICHSGFIN", "MUTHOOTFIN", "PAYTM", "PBFINTECH", "PFC", "RECLTD", "SBICARD", "SHRIRAMFIN"),
        "Insurance" to listOf("HDFCLIFE", "ICICIGI", "ICICIPRULI", "LICI", "SBILIFE"),
        "IT" to listOf("COFORGE", "HCLTECH", "INFY", "LTIM", "MPHASIS", "PERSISTENT", "TCS", "TECHM", "WIPRO", "NAUKRI"),
        "Oil and gas" to listOf("BPCL", "GAIL", "HINDPETRO", "IOC", "ONGC", "RELIANCE"),
        "Power" to listOf("ADANIENSOL", "ADANIGREEN", "ADANIPOWER", "JSWENERGY", "NTPC", "POWERGRID", "TATAPOWER", "SUZLON"),
        "Metals and mining" to listOf("COALINDIA", "HINDALCO", "HINDZINC", "JINDALSTEL", "JSWSTEEL", "TATASTEEL", "VEDL", "ADANIENT"),
        "Auto" to listOf("ASHOKLEY", "BAJAJ-AUTO", "BHARATFORG", "BOSCHLTD", "EICHERMOT", "HYUNDAI", "M&M", "MARUTI", "MOTHERSON",
            "TATAMOTORS", "TVSMOTOR"),
        "FMCG" to listOf("BRITANNIA", "DABUR", "GODREJCP", "HINDUNILVR", "ITC", "NESTLEIND", "TATACONSUM", "UNITDSPR", "VBL"),
        "Pharma and health" to listOf("APOLLOHOSP", "AUROPHARMA", "CIPLA", "DIVISLAB", "DRREDDY", "LUPIN", "MAXHEALTH", "SUNPHARMA",
            "TORNTPHARM", "ZYDUSLIFE"),
        "Cement and materials" to listOf("AMBUJACEM", "GRASIM", "SHREECEM", "ULTRACEMCO", "ASIANPAINT", "PIDILITIND", "SOLARINDS"),
        "Capital goods" to listOf("ABB", "BEL", "CGPOWER", "CUMMINSIND", "HAL", "LT", "POLYCAB", "SIEMENS", "HAVELLS"),
        "Consumer" to listOf("DIXON", "DMART", "ETERNAL", "INDHOTEL", "SWIGGY", "TITAN", "TRENT", "VOLTAS"),
        "Telecom" to listOf("BHARTIARTL", "IDEA"),
        "Realty" to listOf("DLF", "GODREJPROP", "LODHA"),
        "Transport" to listOf("ADANIPORTS", "CONCOR", "INDIGO"),
    )

    val SECTOR_OF: Map<String, String> = BY_SECTOR.flatMap { (s, l) -> l.map { it to s } }.toMap()

    fun of(symbol: String): String? = SECTOR_OF[symbol]

    fun isBank(symbol: String): Boolean = SECTOR_OF[symbol] == BANKS
}
