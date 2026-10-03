package com.optionslab.app.ira

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.work.AlarmReceiver
import com.optionslab.ira.Ask
import com.optionslab.ira.Later
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Commands Boss set for a time ("start all the arms tomorrow at 9am"), each confirmed when it was set: kept on the
 * phone, run by an exact alarm at their time, and told to Boss (a pop-up and the activity log). Only [Later.ALLOWED]
 * kinds are kept or run - starting and stopping strategies and arms, the kill switch on, paper mode - never an order.
 */
object IraLater {
    const val ACTION = "ol.jarvis.later"
    private const val KEY = "jarvis.later"
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    /** A command is still run if its alarm came this late (the phone was off); later than that it is dropped and told. */
    private const val GRACE_MS = 30 * 60_000L

    data class Item(val id: Long, val text: String, val at: Long)

    @Synchronized fun all(): List<Item> = runCatching {
        val a = org.json.JSONArray(SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Item(it.getLong("id"), it.getString("text"), it.getLong("at")) } }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(items: List<Item>) {
        val a = org.json.JSONArray()
        items.forEach { a.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
        SecurePrefs.put(KEY, if (items.isEmpty()) null else a.toString())
    }

    /** Kept and its alarm set ([text] is the command without its time). */
    fun add(context: Context, text: String, at: LocalDateTime) {
        val c = Ask.parse(text).command
        require(c != null && c.kind in Later.ALLOWED) { "only starting or stopping can wait for a time" }
        save(all() + Item(System.nanoTime(), text, at.atZone(IST).toInstant().toEpochMilli()))
        schedule(context)
    }

    /** "Boss, what have you set for later?" */
    fun say(now: LocalDateTime = LocalDateTime.now(IST)): String {
        val items = all().sortedBy { it.at }
        if (items.isEmpty()) return "Nothing is set for later, Boss."
        return "Set for later: " + items.joinToString("; ") { "\"${it.text}\" " + Later.say(LocalDateTime.ofInstant(Instant.ofEpochMilli(it.at), IST), now) } + "."
    }

    /** Everything set for later is dropped (nothing runs). */
    fun clear(context: Context) { save(emptyList()); schedule(context) }

    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 191, Intent(context, AlarmReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** The alarm for the earliest command (none when nothing waits). */
    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = intent(context)
        am.cancel(pi)
        val next = all().minOfOrNull { it.at } ?: return
        try {
            if (com.optionslab.app.work.Jobs.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    /** The alarm fired: each command now due is run (or dropped when far too late), and Boss is told. */
    suspend fun fire(context: Context) {
        val now = System.currentTimeMillis()
        val due = all().filter { it.at <= now + 30_000 }
        if (due.isNotEmpty()) save(all().filter { it.at > now + 30_000 })
        for (item in due) {
            val said = if (now - item.at > GRACE_MS) "I did not \"${item.text}\": its time passed while the phone was off. Ask me again if you still want it."
            else {
                val c = Ask.parse(item.text).command
                if (c == null || c.kind !in Later.ALLOWED) "I did not \"${item.text}\": it is not something I may do on a timer."
                else {
                    val (what, act) = runCatching { IraActions.prepare(c) }.getOrElse { ("I could not do that: ${it.message}") to null }
                    if (act == null) what else "As you asked: " + IraActions.run(what, act)
                }
            }
            IraActivity.add("Set for later, \"${item.text}\": ${IraActivity.short(said)}")
            runCatching { JarvisPopup.show(context, "Jarvis", "Boss, $said") }
        }
        schedule(context)
    }
}
