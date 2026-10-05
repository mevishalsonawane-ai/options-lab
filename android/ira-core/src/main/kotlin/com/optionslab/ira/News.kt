package com.optionslab.ira

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One headline: its words, where it came from, when, its tone (-1 bad .. +1 good) and the markets it concerns. */
data class Headline(val title: String, val link: String, val source: String, val at: Instant?, val tone: Double, val markets: List<Market>) {
    val toneWord: String get() = when { tone >= 0.25 -> "positive"; tone <= -0.25 -> "negative"; else -> "neutral" }
}

/**
 * Reads RSS and Atom feeds into headlines, scores their tone with a trading word list, and tags the markets they
 * concern. Headline text is data only: nothing here acts on it. Pure; no network.
 */
object News {
    /** Words and phrases with their weight; phrases are matched before single words. */
    private val GOOD = mapOf(
        "record high" to 1.0, "all-time high" to 1.0, "rally" to 1.0, "rallies" to 1.0, "surge" to 1.0, "surges" to 1.0, "soar" to 1.0, "soars" to 1.0,
        "jump" to 0.8, "jumps" to 0.8, "gain" to 0.6, "gains" to 0.6, "rise" to 0.6, "rises" to 0.6, "climbs" to 0.6, "up" to 0.3, "higher" to 0.5,
        "recover" to 0.6, "recovers" to 0.6, "rebound" to 0.7, "rebounds" to 0.7, "beats" to 0.7, "upgrade" to 0.7, "upgrades" to 0.7,
        "inflows" to 0.6, "buying" to 0.4, "bullish" to 0.8, "optimism" to 0.6, "strong" to 0.4, "boost" to 0.6, "boosts" to 0.6,
        "rate cut" to 0.7, "eases" to 0.4, "easing" to 0.4, "outperform" to 0.6, "green" to 0.3,
    )
    private val BAD = mapOf(
        "crash" to 1.0, "crashes" to 1.0, "plunge" to 1.0, "plunges" to 1.0, "slump" to 0.9, "slumps" to 0.9, "tumble" to 0.9, "tumbles" to 0.9,
        "sell-off" to 0.9, "selloff" to 0.9, "fall" to 0.6, "falls" to 0.6, "drop" to 0.6, "drops" to 0.6, "decline" to 0.6, "declines" to 0.6,
        "down" to 0.3, "lower" to 0.5, "slips" to 0.5, "slide" to 0.6, "slides" to 0.6, "loss" to 0.5, "losses" to 0.6, "misses" to 0.7,
        "downgrade" to 0.7, "downgrades" to 0.7, "outflows" to 0.6, "selling" to 0.4, "bearish" to 0.8, "fears" to 0.6, "fear" to 0.5,
        "weak" to 0.5, "war" to 0.7, "tension" to 0.5, "tensions" to 0.5, "tariff" to 0.5, "tariffs" to 0.5, "rate hike" to 0.7, "red" to 0.3,
        "volatile" to 0.3, "crisis" to 0.8, "default" to 0.8,
    )
    /** For gold some news reads the other way: fear, war and rate cuts lift it, a strong dollar weighs on it. */
    private val GOLD_GOOD = mapOf("safe haven" to 1.0, "safe-haven" to 1.0, "war" to 0.6, "tension" to 0.5, "tensions" to 0.5, "fears" to 0.4,
        "rate cut" to 0.8, "weak dollar" to 0.8, "dollar falls" to 0.8, "dovish" to 0.7)
    private val GOLD_BAD = mapOf("strong dollar" to 0.8, "dollar rises" to 0.8, "dollar gains" to 0.8, "rate hike" to 0.8, "hawkish" to 0.7, "yields rise" to 0.6)
    private val NEGATORS = setOf("not", "no", "never", "fails", "without")

    private val TAGS: List<Pair<List<String>, List<Market>>> = listOf(
        listOf("bank nifty", "banknifty", "bank stocks", "banks", "bank", "psu bank", "private bank", "rbi", "hdfc bank", "icici", "sbi", "kotak", "axis bank") to listOf(Market.BANKNIFTY),
        listOf("finnifty", "fin nifty", "nbfc", "nbfcs", "insurance", "insurers", "financial services", "bajaj finance") to listOf(Market.FINNIFTY),
        listOf("nifty", "sensex", "stocks", "equities", "equity", "dalal street", "benchmark", "markets", "market", "fii", "fiis", "dii", "diis", "midcap", "smallcap") to listOf(Market.NIFTY, Market.SENSEX),
        listOf("sensex", "bse") to listOf(Market.SENSEX),
        listOf("vix", "volatility") to listOf(Market.VIX),
        listOf("gold", "bullion", "precious metal", "precious metals", "xau", "comex") to listOf(Market.GOLD),
    )

    private fun words(s: String) = s.lowercase().replace(rx("[^a-z0-9\\- ]"), " ").split(rx("\\s+")).filter { it.isNotBlank() }

    /** Tone of [title], -1..+1; for [market] GOLD the gold-specific readings apply. */
    fun tone(title: String, market: Market? = null): Double {
        val w = words(title)
        val text = " " + w.joinToString(" ") + " "
        var score = 0.0
        var used = text
        val good = if (market == Market.GOLD) GOOD + GOLD_GOOD else GOOD
        val bad = if (market == Market.GOLD) BAD.filterKeys { it !in GOLD_GOOD } + GOLD_BAD else BAD
        // phrases first (two words or more), then single words, each with negation looked up one or two words back
        val all = (good.map { Triple(it.key, it.value, 1) } + bad.map { Triple(it.key, it.value, -1) }).sortedByDescending { it.first.length }
        for ((phrase, weight, sign) in all) {
            var idx = used.indexOf(" $phrase ")
            while (idx >= 0) {
                val before = used.substring(0, idx).trim().split(" ").takeLast(2)
                val neg = before.any { it in NEGATORS }
                score += weight * sign * if (neg) -1 else 1
                used = used.substring(0, idx) + " " + "#".repeat(phrase.length) + " " + used.substring(idx + phrase.length + 2)
                idx = used.indexOf(" $phrase ")
            }
        }
        return (score / 2.0).coerceIn(-1.0, 1.0)
    }

    /** The markets [title] concerns. */
    fun tag(title: String): List<Market> {
        val text = " " + words(title).joinToString(" ") + " "
        val out = LinkedHashSet<Market>()
        for ((keys, ms) in TAGS) if (keys.any { text.contains(" $it ") }) out += ms
        return out.toList()
    }

    /** Headlines from an RSS 2.0 or Atom document. Tolerant: bad items are skipped, never thrown. */
    fun parse(xml: String, source: String): List<Headline> {
        val items = rx("<(item|entry)[\\s>][\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE).findAll(xml).map { it.value }.toList()
        return items.mapNotNull { it ->
            val title = field(it, "title")?.let(::clean)?.takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null
            val link = field(it, "link")?.let(::clean)?.takeIf { l -> l.startsWith("https://") || l.startsWith("http://") }
                ?: rx("<link[^>]*href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) ?: ""
            val at = (field(it, "pubDate") ?: field(it, "published") ?: field(it, "updated") ?: field(it, "dc:date"))?.let { d -> date(clean(d)) }
            Headline(title, link, source, at, tone(title), tag(title))
        }
    }

    private fun field(item: String, name: String): String? =
        rx("<$name(?:\\s[^>]*)?>([\\s\\S]*?)</$name>", RegexOption.IGNORE_CASE).find(item)?.groupValues?.get(1)

    private fun clean(s: String): String {
        var t = s.trim()
        rx("^<!\\[CDATA\\[([\\s\\S]*)]]>$").find(t)?.let { t = it.groupValues[1] }
        t = t.replace(rx("<[^>]+>"), " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace(rx("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { c -> String(Character.toChars(c)) } ?: "" }
        return t.replace(rx("\\s+"), " ").trim()
    }

    private fun date(s: String): Instant? =
        runCatching { ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(s) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()

    /** Headlines from several feeds, the same story once (by its words), newest first. */
    fun merge(lists: List<List<Headline>>): List<Headline> =
        lists.flatten().sortedByDescending { it.at ?: Instant.EPOCH }.distinctBy { words(it.title).joinToString(" ") }
}
