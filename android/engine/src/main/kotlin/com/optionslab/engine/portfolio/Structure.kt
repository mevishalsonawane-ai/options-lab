package com.optionslab.engine.portfolio

data class Cluster(val id: Int, val members: List<String>, val weight: Double, val independent: Boolean)

/** What the portfolio is made of: fund vs stock by name, and co-movement clusters. */
data class Structure(
    val instrumentClasses: Map<String, Double>,
    val instrumentClassBasis: String,
    val clusters: List<Cluster>,
    val threshold: Double,
    val effectiveBets: Int,
    val largestClusterWeight: Double,
    val sectorNote: String,
)

/**
 * Port of `portfolio/grouping.py`. There is no sector field to read, so
 * holdings are grouped by single-linkage correlation clustering (>= 0.6) and
 * classed fund/stock by listing-name markers, labelled as the heuristic it is.
 */
object Grouping {
    private val FUND_MARKERS = listOf("ETF", "BEES", "FUND", "INDEX", "NIFTY", "SENSEX", "GOLD", "SILVER")
    const val SECTOR_NOTE =
        "Sector and market-cap breakdowns are not shown: IraAlgo's symbol master carries no sector or market-cap " +
            "field, and inventing one would be worse than omitting it. Co-movement clustering answers the same " +
            "question from data that actually exists."

    fun classifyInstrument(symbol: String, name: String?): String {
        val haystack = "$symbol ${name ?: ""}".uppercase()
        return if (FUND_MARKERS.any { it in haystack }) "fund" else "stock"
    }

    fun correlationClusters(symbols: List<String>, returns: Array<DoubleArray>, threshold: Double = 0.6): List<List<String>> {
        if (symbols.size < 2) return symbols.map { listOf(it) }
        val parent = IntArray(symbols.size) { it }
        fun find(x: Int): Int {
            var i = x
            while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i] }
            return i
        }
        for (i in symbols.indices) for (j in i + 1 until symbols.size) {
            val v = Np.corr(returns[i], returns[j])
            if (v.isFinite() && v >= threshold) {
                val a = find(i); val b = find(j)
                if (a != b) parent[b] = a
            }
        }
        val groups = LinkedHashMap<Int, MutableList<String>>()
        symbols.forEachIndexed { i, s -> groups.getOrPut(find(i)) { ArrayList() }.add(s) }
        return groups.values.sortedByDescending { it.size }
    }

    internal fun structure(
        symbols: List<String>, weights: DoubleArray, returns: Array<DoubleArray>, names: Map<String, String>, d: Disp,
        threshold: Double = 0.6,
    ): Structure {
        val total = Np.sum(weights).let { if (it == 0.0) 1.0 else it }
        val w = DoubleArray(weights.size) { weights[it] / total }
        val classes = LinkedHashMap<String, Double>()
        symbols.forEachIndexed { i, s ->
            val kind = classifyInstrument(s, names[s])
            classes[kind] = (classes[kind] ?: 0.0) + w[i]
        }
        val rows = correlationClusters(symbols, returns, threshold).mapIndexed { i, members ->
            var weight = 0.0
            for (m in members) weight += w[symbols.indexOf(m)]
            Cluster(i + 1, members, d.r(weight, 5), members.size == 1)
        }.sortedByDescending { it.weight }
        return Structure(
            classes.mapValues { d.r(it.value, 5) },
            "inferred from the listing name",
            rows,
            threshold,
            rows.size,
            d.r(rows.maxOfOrNull { it.weight } ?: 0.0, 5),
            SECTOR_NOTE,
        )
    }
}
