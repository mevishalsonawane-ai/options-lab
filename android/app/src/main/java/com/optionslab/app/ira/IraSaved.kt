package com.optionslab.app.ira

import com.optionslab.ira.Market
import com.optionslab.ira.PatternKind
import com.optionslab.ira.StrategyLab
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * What Ira keeps between runs besides the pattern book, as JSON (encrypted by the caller): the strategy proposals with
 * their backtests, the session review journal, and the patterns already tried (the last week only). Never the
 * conversation. A part that does not read back is dropped, never guessed.
 */
internal object IraSaved {
    data class Read(val proposals: List<IraHub.Proposal>, val journal: List<IraHub.DayScore>, val nightlyAt: Instant?, val tested: List<String>,
                    val messages: List<IraHub.Msg> = emptyList())

    /** Tried-pattern keys end with the pattern's day; older than this many days they are not kept. */
    private const val TESTED_DAYS = 7L

    fun write(proposals: List<IraHub.Proposal>, journal: List<IraHub.DayScore>, nightlyAt: Instant?, tested: List<String>, today: LocalDate,
              messages: List<IraHub.Msg> = emptyList()): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put("proposals", JSONArray().apply { proposals.forEach { p -> runCatching { put(proposal(p)) } } })
        o.put("journal", JSONArray().apply { journal.forEach { put(JSONArray().put(it.day.toString()).put(it.seen).put(it.worked)) } })
        nightlyAt?.let { o.put("nightlyAt", it.toString()) }
        o.put("tested", JSONArray().apply { tested.filter { fresh(it, today) }.forEach { put(it) } })
        // The conversation: what was said and the facts shown (an order card or a pending action is not brought back).
        o.put("messages", JSONArray().apply { messages.filter { !it.writing }.forEach { m ->
            put(JSONObject().put("ira", m.fromIra).put("text", m.text).put("facts", JSONArray(m.facts))
                .apply { m.draft?.let { put("draft", it) }; m.proposal?.let { put("proposal", it) } }) } })
        return o.toString()
    }

    fun read(text: String, today: LocalDate = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))): Read {
        val o = JSONObject(text)
        val ps = o.optJSONArray("proposals") ?: JSONArray()
        val js = o.optJSONArray("journal") ?: JSONArray()
        val ts = o.optJSONArray("tested") ?: JSONArray()
        return Read(
            (0 until ps.length()).mapNotNull { i -> runCatching { proposal(ps.getJSONObject(i)) }.getOrNull() },
            (0 until js.length()).mapNotNull { i -> runCatching { js.getJSONArray(i).let { a -> IraHub.DayScore(LocalDate.parse(a.getString(0)), a.getInt(1), a.getInt(2)) } }.getOrNull() },
            o.optString("nightlyAt").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            (0 until ts.length()).map { ts.getString(it) }.filter { fresh(it, today) },
            (o.optJSONArray("messages") ?: JSONArray()).let { a -> (0 until a.length()).mapNotNull { i -> runCatching {
                val m = a.getJSONObject(i)
                val f = m.optJSONArray("facts") ?: JSONArray()
                IraHub.Msg(m.getBoolean("ira"), m.getString("text"), (0 until f.length()).map { f.getString(it) },
                    draft = m.optString("draft").takeIf { it.isNotEmpty() }, proposal = if (m.has("proposal")) m.getLong("proposal") else null)
            }.getOrNull() } },
        )
    }

    private fun fresh(key: String, today: LocalDate) =
        runCatching { !LocalDate.parse(key.substringAfterLast('|')).isBefore(today.minusDays(TESTED_DAYS)) }.getOrDefault(false)

    private fun proposal(p: IraHub.Proposal): JSONObject {
        val r = p.result
        return JSONObject().put("id", p.id).put("status", p.status).apply { p.pineId?.let { put("pineId", it) } }
            .put("name", r.name).put("kind", r.kind.name).put("market", r.market.name).put("minutes", r.minutes).put("script", r.script)
            .apply { r.from?.let { put("from", it.toString()) }; r.to?.let { put("to", it.toString()) } }
            .put("days", r.days).put("trades", r.trades).put("winRate", r.winRate).put("netPoints", r.netPoints).put("avgPoints", r.avgPoints)
            .put("maxDrawdownPoints", r.maxDrawdownPoints).put("firstHalfPoints", r.firstHalfPoints).put("secondHalfPoints", r.secondHalfPoints)
            .apply { r.rupees?.let { put("rupees", it) }; r.error?.let { put("error", it) } }
            .put("rupeeTrades", r.rupeeTrades).put("recommended", r.recommended).put("verdict", r.verdict)
    }

    private fun proposal(o: JSONObject): IraHub.Proposal {
        val r = StrategyLab.Result(
            o.getString("name"), PatternKind.valueOf(o.getString("kind")), Market.valueOf(o.getString("market")), o.getInt("minutes"), o.getString("script"),
            o.optString("from").takeIf { it.isNotEmpty() }?.let(LocalDate::parse), o.optString("to").takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
            o.getInt("days"), o.getInt("trades"), o.getDouble("winRate"), o.getDouble("netPoints"), o.getDouble("avgPoints"),
            o.getDouble("maxDrawdownPoints"), o.getDouble("firstHalfPoints"), o.getDouble("secondHalfPoints"),
            if (o.has("rupees")) o.getDouble("rupees") else null, o.optInt("rupeeTrades"),
            o.getBoolean("recommended"), o.getString("verdict"), o.optString("error").takeIf { it.isNotEmpty() },
        )
        val status = o.getString("status").takeIf { it in listOf(IraHub.Proposal.NEW, IraHub.Proposal.APPROVED, IraHub.Proposal.DISMISSED) }
            ?: error("unknown status")
        return IraHub.Proposal(o.getLong("id"), r, status, if (o.has("pineId")) o.getLong("pineId") else null)
    }
}
