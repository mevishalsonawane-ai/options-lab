package com.optionslab.app.ira

import com.optionslab.ira.Events
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** The events the owner added ("add event RBI policy on 5 Dec"), kept encrypted in the app's settings; past ones drop off. */
internal object IraEvents {
    private const val KEY = "ira.events"

    fun owner(): List<Events.Event> = runCatching {
        val a = JSONArray(com.optionslab.app.security.SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Events.Event(LocalDate.parse(it.getString("d")), it.getString("n"), owner = true) }
    }.getOrDefault(emptyList()).filter { !it.day.isBefore(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(1)) }

    private fun save(list: List<Events.Event>) = runCatching {
        com.optionslab.app.security.SecurePrefs.put(KEY, JSONArray().apply { list.forEach { put(JSONObject().put("d", it.day.toString()).put("n", it.name)) } }.toString())
    }

    fun add(day: LocalDate, name: String) = save(owner() + Events.Event(day, name.take(60).replaceFirstChar { it.uppercase() }, owner = true))
    fun remove(e: Events.Event) = save(owner().filter { it != e })

    /** The next [days] days: built-in events, the index expiries the app knows, and the owner's. */
    fun upcoming(days: Long = 14): List<Events.Event> {
        val today = com.optionslab.app.data.Market.today()
        val exp = listOf("NIFTY", "BANKNIFTY", "FINNIFTY").associateWith { runCatching { com.optionslab.app.data.Market.upcomingExpiries(it).take(2) }.getOrDefault(emptyList()) }
        return Events.between(today, today.plusDays(days), exp, owner())
    }
}
