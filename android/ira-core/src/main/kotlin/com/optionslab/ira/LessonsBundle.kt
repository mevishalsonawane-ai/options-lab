package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.time.LocalDate
import java.util.Locale

/**
 * The phone's nightly "lessons bundle" (Boss, 13 Oct: the findings from the app go back to the research): one JSON file a day
 * under the recordings folder (recordings/YYYY-MM-DD/[FILE]) with what the agents learned - Jarvis's lessons with their
 * evidence counts, a summary of the findings log, the strategy lab's ideas (tried and skipped) and variants, the order review's
 * recurring flaws, the trade manager's vote outcomes, the shared knowledge's shadow skips and what each agent read and added.
 *
 * Boss's money figures (each lesson's rupees, an idea's net, a flaw's rupees, a shadow skip's rupees) are put in a SEPARATE map
 * ([Built.money]): the app encrypts it with its Vault and stores it as "money_enc" ([text]), as the live recorder keeps his
 * trades; only the PIN-confirmed "Export recordings" decrypts it into "money" ([decrypted]). The research side's importer
 * (research/knowledge/import_app_lessons.py) keeps rates, counts and effects only - never that money.
 *
 * A hook for the future G1 uploader: [FILE] in each day's folder is the whole bundle; an uploader sends the file as stored
 * (still encrypted), never this object's plain money. Pure: no clock, no Android, no disk, no key.
 */
object LessonsBundle {
    const val SCHEMA = 1
    const val KIND = "iraalgo-lessons-bundle"
    const val FILE = "lessons_bundle.json"

    /** The findings log, counted: [count] posts of [kind] by [who] on [days] days. */
    data class FindingCount(val kind: String, val who: String, val count: Int, val days: Int)

    data class Inputs(
        val day: LocalDate,
        val app: String = "",
        val packVersion: String = "",
        val book: AgentLessons.Book = AgentLessons.Book(),
        val findings: List<FindingCount> = emptyList(),
        val forge: StrategyForge.Book? = null,
        /** Tonight's ideas the shared knowledge kept the forge from testing again (name, why). */
        val skipped: List<Pair<String, String>> = emptyList(),
        val variants: VariantLab.Book? = null,
        val orders: OrderReview.Book? = null,
        val shadow: List<SharedKnowledge.ShadowSkip> = emptyList(),
        /** What each agent read from the shared knowledge and added today (the "What I learned" sheet's lines). */
        val agents: List<String> = emptyList(),
    )

    /** The bundle's plain part (no money) and its money part (encrypted by the app before it is written). */
    data class Built(val plain: Map<String, Any?>, val money: Map<String, Any?>)

    private fun r4(x: Double): Double = Math.round(x * 10_000) / 10_000.0
    private fun r2(x: Double): Double = Math.round(x * 100) / 100.0

    /** The bundle for [i]. Lessons: every active one with its counts, sign, confidence and q; rupees only in the money part. */
    fun build(i: Inputs): Built {
        val money = LinkedHashMap<String, Any?>()
        val lessonMoney = LinkedHashMap<String, Any?>()
        val lessons = AgentLessons.active(i.book).map { l ->
            val e = AgentLessons.evidence(l)
            if (l.kind == AgentLessons.Kind.RUPEES || e.rs != 0.0) lessonMoney[l.id] = r2(e.rs)
            linkedMapOf<String, Any?>("id" to l.id, "topic" to l.topic.name, "kind" to l.kind.name, "name" to l.name, "tags" to l.tags,
                "agents" to l.agents.sorted(), "n" to e.n, "hits" to e.hits.coerceAtMost(maxOf(e.n, e.of)), "sign" to l.sign,
                "confidence" to l.confidence.word, "status" to l.status.word, "p" to l.p?.let(::r4), "q" to l.q?.let(::r4),
                "cond" to l.cond, "first" to l.firstSeen.toString(), "last" to l.lastSeen.toString())
        }
        money["lessons"] = lessonMoney
        // The trade manager's vote outcomes: each specialist's settled votes on a strategy and how often its part helped.
        val votes = AgentLessons.active(i.book).filter { it.id.startsWith("spec:") }.map { l ->
            val e = AgentLessons.evidence(l); val parts = l.id.split(':')
            linkedMapOf<String, Any?>("family" to parts.getOrNull(1), "specialist" to parts.getOrNull(2), "settled" to e.n,
                "helped" to e.hits.coerceAtMost(e.n), "confidence" to l.confidence.word)
        }
        val forge = i.forge?.let { b ->
            val c = StrategyForge.counts(b)
            val made = b.records.filter { it.since == i.day || it.c.born == i.day }
            money["ideas"] = made.associate { r -> r.c.id to r.bt?.net?.let(::r2) }
            linkedMapOf<String, Any?>(
                "forge" to linkedMapOf("tried" to c.tried, "passed" to c.passed, "forward" to c.forward, "proposed" to c.proposed,
                    "retired" to c.retired, "rejected" to c.rejected, "paper" to c.paper, "skipped" to i.skipped.size),
                "ideas" to made.map { r ->
                    linkedMapOf<String, Any?>("id" to r.c.id, "entry" to r.c.rules.entry.name, "filters" to r.c.rules.filters.map { it.name }.sorted(),
                        "exit" to r.c.exit.name, "underlying" to r.c.underlying, "stage" to r.stage.name, "gate_failed" to r.bt?.firstFailed(),
                        "trades" to (r.bt?.trades ?: 0), "win_rate" to r.bt?.winRate?.let(::r4), "twin_p" to r.bt?.twinP?.let(::r4))
                },
                "skipped" to i.skipped.map { (idea, why) -> linkedMapOf("idea" to idea, "why" to why) },
                "variants" to (i.variants?.variants.orEmpty().filter { it.alive || it.lastDay == i.day }.map { v ->
                    linkedMapOf<String, Any?>("id" to v.id, "strategy" to v.strategy, "status" to v.status.name, "n" to v.n,
                        "better" to (v.n > 0 && v.diffs.average() > 0), "p_better" to r4(v.pBetter))
                }))
        } ?: linkedMapOf<String, Any?>("skipped" to i.skipped.map { (idea, why) -> linkedMapOf("idea" to idea, "why" to why) })
        val orders = i.orders?.let { b ->
            val pats = OrderReview.patterns(b.logs)
            money["orders"] = pats.associate { it.key to r2(it.rupees) }
            linkedMapOf<String, Any?>(
                "patterns" to pats.map { p -> linkedMapOf<String, Any?>("strategy" to p.strategy, "venue" to p.venue.word, "category" to p.category.words,
                    "count" to p.count, "days" to p.days, "first" to p.first.toString(), "last" to p.last.toString()) },
                "flaws_today" to (b.last?.takeIf { it.day == i.day }?.flaws.orEmpty().groupBy { Triple(it.strategy, it.venue.word, it.category.words) }
                    .map { (k, fs) -> linkedMapOf<String, Any?>("strategy" to k.first, "venue" to k.second, "category" to k.third, "count" to fs.size) }))
        } ?: emptyMap<String, Any?>()
        money["shadow"] = i.shadow.associate { "${it.rule}|${it.arm}" to r2(it.rs) }
        val plain = linkedMapOf<String, Any?>(
            "schema" to SCHEMA, "kind" to KIND, "day" to i.day.toString(), "app" to i.app, "pack_version" to i.packVersion,
            "note" to "Rates, counts and effects in the clear; Boss's money only in money_enc (the app's Vault).",
            "lessons" to lessons,
            "findings" to i.findings.map { linkedMapOf<String, Any?>("kind" to it.kind, "who" to it.who, "count" to it.count, "days" to it.days) },
            "lab" to forge, "orders" to orders, "votes" to votes,
            "shadow" to i.shadow.map { linkedMapOf<String, Any?>("rule" to it.rule, "arm" to it.arm, "trades" to it.trades, "would_skip" to it.wouldSkip, "why" to it.why) },
            "agents" to i.agents,
        )
        return Built(plain, money)
    }

    /** The money part as JSON (what the app encrypts). */
    fun moneyJson(b: Built): String = Json.write(b.money)

    /** The bundle file's text: the plain part with [moneyEnc] (the encrypted money part, base64; null: none) as "money_enc". */
    fun text(b: Built, moneyEnc: String?): String = Json.write(LinkedHashMap(b.plain).apply { if (moneyEnc != null) put("money_enc", moneyEnc) })

    /**
     * The bundle as the PIN-confirmed export writes it: "money_enc" replaced by "money" ([money]: the decrypted JSON, or null when
     * it could not be opened - then a gap note, never an invented figure).
     */
    fun decrypted(text: String, money: String?): String {
        val root = (Json.parse(text) as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v } ?: return text
        val out = LinkedHashMap<String, Any?>(root)
        out.remove("money_enc")
        if (money != null) out["money"] = runCatching { Json.parse(money) }.getOrNull() ?: mapOf("gap" to "could not be read")
        else if (root.containsKey("money_enc")) out["money"] = mapOf("gap" to "could not be decrypted")
        return Json.write(out)
    }

    /** The findings log counted ([FindingCount]s) from parsed findings. */
    fun counts(findings: List<Findings.Finding>): List<FindingCount> = counts(FindingsTally.of(findings))

    /** [counts] from the findings' counts alone (14 Oct: nothing kept as objects). */
    fun counts(tally: FindingsTally): List<FindingCount> = tally.counts().sortedWith(compareByDescending<FindingCount> { it.count }.thenBy { it.kind }.thenBy { it.who.lowercase(Locale.ENGLISH) })
}
