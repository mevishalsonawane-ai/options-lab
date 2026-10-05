package com.optionslab.app.ira

import com.optionslab.ira.Thinking
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Jarvis's reason trail ([Thinking]; reasoning, round 5): each decision of his own - a paper idea taken or sat out, a
 * Solo trade taken or shadowed, a market alert said or kept to the chat, an item of his plan worked or skipped, a "check
 * me on this" - written at the moment he decides, as facts from his records and the rule he applied (never the model's
 * words), so "why didn't you take that trade?" walks what he had then. Kept on the phone (encrypted), today and yesterday
 * only, [Thinking.MAX_A_DAY] a day; no rupee amount and no secret is ever kept ([Thinking.clean]). Words only: nothing
 * here decides or does anything, and a failure to write is never in the way of the decision itself.
 */
internal object IraThinking {
    private const val KEY = "jarvis.thinking"
    private val IST = ZoneId.of("Asia/Kolkata")
    @Volatile private var cache: List<Thinking.Step>? = null

    private fun prefs() = com.optionslab.app.security.SecurePrefs

    private fun load(): List<Thinking.Step> = cache ?: runCatching {
        val a = JSONArray(prefs().getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            val f = o.optJSONArray("f") ?: JSONArray()
            Thinking.Step(LocalDateTime.parse(o.getString("t")), Thinking.Kind.valueOf(o.getString("k")), o.getString("s"),
                (0 until f.length()).map { n -> f.getJSONObject(n).let { x -> Thinking.Fact(x.getString("x"), x.optBoolean("p", false)) } },
                o.optString("m").takeIf { it.isNotEmpty() }?.let { m -> runCatching { com.optionslab.ira.Market.valueOf(m) }.getOrNull() },
                o.optBoolean("p", false))
        }.getOrNull() }
    }.getOrDefault(emptyList()).also { cache = it }

    private fun save(all: List<Thinking.Step>) {
        cache = all
        val a = JSONArray()
        all.forEach { s ->
            val o = JSONObject().put("t", s.at.toString()).put("k", s.kind.name).put("s", s.subject).put("p", s.private)
                .put("f", JSONArray().apply { s.facts.forEach { put(JSONObject().put("x", it.text).put("p", it.private)) } })
            s.market?.let { o.put("m", it.name) }
            a.put(o)
        }
        prefs().putAllSoon(mapOf(KEY to a.toString()))
    }

    /** [steps] written now (each made by [Thinking]'s builders at the decision point). Never throws. */
    fun add(step: Thinking.Step?) {
        if (step != null) add(listOf(step))
    }

    @Synchronized fun add(steps: List<Thinking.Step>) {
        if (!com.optionslab.app.BuildConfig.JARVIS || steps.isEmpty()) return
        runCatching {
            val was = load()
            val all = steps.fold(was) { t, s -> Thinking.add(t, s) }
            if (all != was) save(all)
        }
    }

    fun now(): LocalDateTime = LocalDateTime.now(IST).withSecond(0).withNano(0)

    /**
     * The answer to [q] from what was written ([locked]: Boss's account and words are left out), or null when nothing
     * fits. For "why did you do that", something in the activity log after his latest step is what "that" means.
     */
    fun answer(q: Thinking.Query, locked: Boolean): String? = runCatching {
        val newer = if (q.latest) runCatching { IraActivity.entries().maxOfOrNull { it.at } }.getOrNull() else null
        Thinking.answer(q, load(), LocalDateTime.now(IST), locked, newer)
    }.getOrNull()
}
