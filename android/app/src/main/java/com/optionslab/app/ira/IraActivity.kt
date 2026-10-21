package com.optionslab.app.ira

import com.optionslab.ira.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What Jarvis did (Jarvis): each command run, trade suggested, answered, placed or closed, kept on the phone
 * (encrypted, the last [Activity.KEEP]) for "what did you do today". Words only: never a secret (they are hidden first).
 */
internal object IraActivity {
    private const val KEY = "jarvis.activity"
    private val IST = ZoneId.of("Asia/Kolkata")

    private fun load(): List<Activity.Entry> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { Activity.Entry(LocalDateTime.parse(it.getString("t")), it.getString("w")) } }.getOrNull() }
    }.getOrDefault(emptyList())

    /**
     * 20 Oct (the 17 Oct 01:09 ANR): the log was written with the Keystore while this lock was held, and Jarvis's speech
     * callbacks on the screen's thread add to it - they waited for the encryption. The new list is made under the lock and
     * handed to the settings' background writer ([com.optionslab.app.security.SecurePrefs.putAllSoon]): readable at once,
     * written to disk on its own thread, so no caller ever waits on the disk or the Keystore here.
     */
    @Synchronized fun add(what: String) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val w = com.optionslab.ira.Secrets.redact(what).replace(Regex("\\s+"), " ").trim().take(240)
        if (w.isEmpty()) return
        val all = (load() + Activity.Entry(LocalDateTime.now(IST).withNano(0), w)).takeLast(Activity.KEEP)
        runCatching { com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(KEY to
            JSONArray().apply { all.forEach { put(JSONObject().put("t", it.at.toString()).put("w", it.what)) } }.toString())) }
    }

    /** Every kept entry (for "why did you ...?"). */
    fun entries(): List<Activity.Entry> = load()

    fun lines(): List<String> = Activity.lines(com.optionslab.app.data.Market.today(), load())

    /** The first sentence of a result, for the log. */
    fun short(text: String): String = text.trim().split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty().take(160)
}
