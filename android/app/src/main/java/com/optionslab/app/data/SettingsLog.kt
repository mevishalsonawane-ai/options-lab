package com.optionslab.app.data

import com.optionslab.ira.SettingsHistory
import com.optionslab.ira.SettingsTalk
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Every change to the app's limits (the owner's wish, 2026-10-02: "what did I change this week"), whoever made it -
 * the Settings screen or Jarvis - kept on the phone (encrypted, the last [KEEP]). Also what "Jarvis, undo" puts back.
 */
object SettingsLog {
    private const val KEY = "settings.history"
    private const val KEEP = 200
    private val IST = ZoneId.of("Asia/Kolkata")

    /** Who makes the next save (Jarvis sets it just before its own change); the Settings screen otherwise. */
    @Volatile var nextBy: String? = null

    fun all(): List<SettingsHistory.Change> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { o ->
            SettingsHistory.Change(LocalDateTime.parse(o.getString("t")), SettingsTalk.Key.valueOf(o.getString("k")), o.getDouble("o"), o.getDouble("n"), o.getString("b"))
        } }.getOrNull() }
    }.getOrDefault(emptyList())

    /**
     * 22 Nov 2026 (B6 review): every setting's name the log holds - those of settings gone since too ("LOTS_PER_ORDER", "ORDERS_PER_DAY"),
     * which [all] leaves out. Read raw; empty when the log cannot be read. A worker thread.
     */
    fun keysLogged(): Set<String> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).getString("k") }.getOrNull() }.toSet()
    }.getOrDefault(emptySet())

    /** The changes between [old] and [new] (only the limits Jarvis knows), recorded. */
    @Synchronized fun diff(old: AppSettings, new: AppSettings) {
        // Each save keeps its own exact time (two saves in the same second are still two saves, undone one at a time).
        val prev = all().lastOrNull()?.at
        val now = LocalDateTime.now(IST).let { if (prev != null && !it.isAfter(prev)) prev.plusNanos(1_000) else it }
        val changed = SettingsTalk.Key.entries.mapNotNull { k ->
            val a = com.optionslab.app.ira.IraActions.setting(k, old); val b = com.optionslab.app.ira.IraActions.setting(k, new)
            if (a == b) null else Triple(k, a, b)
        }
        // A save that changes no limit (the kill switch, the theme) leaves Jarvis's mark for the save it was meant for.
        if (changed.isEmpty()) return
        val by = nextBy ?: "Settings screen"
        nextBy = null
        val changes = changed.map { (k, a, b) -> SettingsHistory.Change(now, k, a, b, by) }
        // 22 Nov 2026 (B6 review): the rows already kept are written back as they are - a row of a setting gone since (one [all] cannot
        // read) included, so what it says is never lost on the next save ([keysLogged]).
        val kept = runCatching {
            val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }.getOrDefault(emptyList())
        val rows = (kept + changes.map { c -> JSONObject().put("t", c.at.toString()).put("k", c.key.name).put("o", c.old).put("n", c.new).put("b", c.by) })
            .takeLast(KEEP)
        runCatching { com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { rows.forEach { put(it) } }.toString()) }
    }

    fun lines(): List<String> = SettingsHistory.lines(all(), Market.today())

    private const val UNDONE = "settings.undone"
    const val UNDO_BY = "Jarvis (undo)"

    private fun undone(): Set<String> = com.optionslab.app.security.SecurePrefs.getString(UNDONE).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
    private fun id(c: SettingsHistory.Change) = "${c.at}|${c.key.name}"

    /**
     * What "undo" puts back: the latest save's changes (all limits one save changed, together) that are not an undo
     * themselves and not undone already - so a second "undo" goes one step further back, never redoes.
     */
    fun lastUndoable(): List<SettingsHistory.Change> {
        val done = undone()
        val open = all().filter { it.by != UNDO_BY && id(it) !in done }
        val at = open.lastOrNull()?.at ?: return emptyList()
        return open.filter { it.at == at }
    }

    @Synchronized fun markUndone(list: List<SettingsHistory.Change>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(UNDONE, (undone() + list.map { id(it) }).toList().takeLast(400).joinToString("\n"))
    }
}
