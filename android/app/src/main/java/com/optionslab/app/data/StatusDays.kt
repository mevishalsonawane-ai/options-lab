package com.optionslab.app.data

import com.optionslab.ira.ArmStatus
import org.json.JSONObject
import java.time.LocalDate

/**
 * 21 Oct: an arm's status lines with the day each was last WRITTEN ([ArmStatus]): every write ([put], `status[k] = x`) stamps
 * today, so a line from an earlier session shows as "from 20 Oct: ..." while one written again today, even with the same
 * words, is today's. The days are kept with the arm's book ("statusDays"). Memory and the arm's own save only; words only.
 */
internal class StatusMap : java.util.HashMap<String, String>() {
    /** The day each line was last written. */
    val days = java.util.HashMap<String, LocalDate>()

    private fun today(): LocalDate = runCatching { Market.today() }.getOrElse { LocalDate.now() }

    override fun put(key: String, value: String): String? { days[key] = today(); return super.put(key, value) }
    override fun putAll(from: Map<out String, String>) { for ((k, v) in from) put(k, v) }
    override fun remove(key: String): String? { days.remove(key); return super.remove(key) }
    override fun clear() { days.clear(); super.clear() }

    /**
     * True when [key] already holds [value] AND it was written today: the arms' "write only on a change" guard, so a line
     * yesterday's session left is written (and stamped) again on its first use today.
     */
    fun sameToday(key: String, value: String): Boolean = this[key] == value && days[key] == today()

    /** A copy with its days (an arm's working copy). */
    fun copyMap(): StatusMap = StatusMap().also { c -> for ((k, v) in entries) c.loaded(k, v, days[k]) }

    /** A line read back from the book, with its kept day (null: none). */
    fun loaded(key: String, value: String, day: LocalDate?) { super.put(key, value); if (day != null) days[key] = day else days.remove(key) }

    /**
     * The lines read back from the book's JSON [o] ("status" and "statusDays"); a line kept without a day by an older build is
     * given the day its book was last saved ([savedAtMs], 0: not known).
     */
    fun readFrom(o: JSONObject?, savedAtMs: Long) {
        val saved = savedAtMs.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).atZone(com.optionslab.engine.IST).toLocalDate() }
        val unknown = ArmStatus.unknownDay(saved, today())
        val kept = o?.optJSONObject(KEY)
        o?.optJSONObject("status")?.let { m -> m.keys().forEach { k -> loaded(k, m.getString(k), ArmStatus.decodeDay(kept?.optString(k)) ?: unknown) } }
    }

    /** The days written into the book's JSON [o]. */
    fun writeTo(o: JSONObject) { o.put(KEY, JSONObject().apply { for ((k, d) in days) if (k in this@StatusMap) put(k, d.toString()) }) }

    /** The lines as the rows, the diagnostics and Jarvis show them today ([sticky]: shown as they are). */
    fun shown(sticky: (String, String) -> Boolean = { _, _ -> false }): Map<String, String> = ArmStatus.shown(toMap(), HashMap(days), today(), sticky)

    private companion object { const val KEY = "statusDays" }
}
