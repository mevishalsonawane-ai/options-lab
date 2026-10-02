package com.optionslab.app.ira

import com.optionslab.ira.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What Jarvis did (JarvisAlgo): each command run, trade suggested, answered, placed or closed, kept on the phone
 * (encrypted, the last [Activity.KEEP]) for "what did you do today". Words only: never a secret (they are hidden first).
 */
internal object IraActivity {
    private const val KEY = "jarvis.activity"
    private val IST = ZoneId.of("Asia/Kolkata")

    private fun load(): List<Activity.Entry> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { Activity.Entry(LocalDateTime.parse(it.getString("t")), it.getString("w")) } }.getOrNull() }
    }.getOrDefault(emptyList())

    @Synchronized fun add(what: String) {
        if (!com.optionslab.app.BuildConfig.JARVIS) return
        val w = com.optionslab.ira.Secrets.redact(what).replace(Regex("\\s+"), " ").trim().take(240)
        if (w.isEmpty()) return
        val all = (load() + Activity.Entry(LocalDateTime.now(IST).withNano(0), w)).takeLast(Activity.KEEP)
        runCatching { com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { all.forEach { put(JSONObject().put("t", it.at.toString()).put("w", it.what)) } }.toString()) }
    }

    fun lines(): List<String> = Activity.lines(com.optionslab.app.data.Market.today(), load())

    /** The first sentence of a result, for the log. */
    fun short(text: String): String = text.trim().split(Regex("(?<=[.!?])\\s+")).firstOrNull().orEmpty().take(160)
}
