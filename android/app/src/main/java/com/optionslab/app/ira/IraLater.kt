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
    private const val RKEY = "jarvis.reminders"
    private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    /** A command is still run if its alarm came this late (the phone was off); later than that it is dropped and told. */
    private const val GRACE_MS = 30 * 60_000L

    /** [live]: the app's mode when it was set (a start is not run in another mode than the one Boss confirmed it in). */
    /** [remind]: Boss's own reminder - only said at its time, never run. */
    data class Item(val id: Long, val text: String, val at: Long, val live: Boolean = false, val remind: Boolean = false)

    @Synchronized fun all(): List<Item> = runCatching {
        val a = org.json.JSONArray(SecurePrefs.getString(KEY) ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Item(it.getLong("id"), it.getString("text"), it.getLong("at"), it.optBoolean("live", false)) } }
    }.getOrDefault(emptyList())

    /** Commands and reminders, each in its own store (a reminder can never be read as a command, even by an older build). */
    @Synchronized fun everything(): List<Item> = all() + reminders()

    @Synchronized fun reminders(): List<Item> = runCatching {
        val a = org.json.JSONArray(SecurePrefs.getString(RKEY) ?: "[]")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Item(it.getLong("id"), it.getString("text"), it.getLong("at"), remind = true) } }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(items: List<Item>) {
        val cmds = items.filter { !it.remind }; val rem = items.filter { it.remind }
        val a = org.json.JSONArray()
        cmds.forEach { a.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("live", it.live)) }
        SecurePrefs.put(KEY, if (cmds.isEmpty()) null else a.toString())
        val r = org.json.JSONArray()
        rem.forEach { r.put(org.json.JSONObject().put("id", it.id).put("text", it.text).put("at", it.at)) }
        SecurePrefs.put(RKEY, if (rem.isEmpty()) null else r.toString())
    }

    /** Kept and its alarm set ([text] is the command without its time). */
    fun add(context: Context, text: String, at: LocalDateTime) {
        val c = Ask.parse(text).command
        require(c != null && c.kind in Later.ALLOWED) { "only starting or stopping can wait for a time" }
        val live = runCatching { com.optionslab.app.data.AppSettings.load().live }.getOrDefault(true)
        save(everything() + Item(System.nanoTime(), text, at.atZone(IST).toInstant().toEpochMilli(), live))
        schedule(context)
    }

    /** Boss's reminder: said at [at], nothing done. */
    fun remind(context: Context, what: String, at: LocalDateTime) {
        save(everything() + Item(System.nanoTime(), com.optionslab.ira.Secrets.redact(what), at.atZone(IST).toInstant().toEpochMilli(), remind = true))
        schedule(context)
    }

    /** "Boss, what have you set for later?" */
    fun say(now: LocalDateTime = LocalDateTime.now(IST)): String {
        val items = everything().sortedBy { it.at }
        if (items.isEmpty()) return "Nothing is set for later, Boss."
        return "Set for later: " + items.joinToString("; ") { (if (it.remind) "a reminder to " else "") + "\"${it.text}\" " + Later.say(LocalDateTime.ofInstant(Instant.ofEpochMilli(it.at), IST), now) } + "."
    }

    /** Boss's reminders only are dropped; timed commands stay. */
    fun clearReminders(context: Context): Int { val n = reminders().size; save(all()); schedule(context); return n }

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
        val next = everything().minOfOrNull { it.at } ?: return
        try {
            if (com.optionslab.app.work.Jobs.canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    /** The alarm fired: each command now due is run (or dropped when far too late), and Boss is told. */
    suspend fun fire(context: Context) {
        // IraGoldAlgo never runs commands (Jarvis only talks there): anything found is dropped.
        // (Reminders only speak, so they stay.)
        if (com.optionslab.app.BuildConfig.GOLD) save(reminders())
        val now = System.currentTimeMillis()
        val due = everything().filter { it.at <= now + 30_000 }
        if (due.isNotEmpty()) save(everything().filter { it.at > now + 30_000 })
        for (item in due) {
            // A reminder: said (late ones say so), never run - whatever its words.
            if (item.remind) {
                val r = com.optionslab.ira.Reminder.said(item.text) + if (now - item.at > GRACE_MS) " (late: the phone was off at its time)" else ""
                IraActivity.add("Reminder said: \"${item.text}\".")
                runCatching { JarvisPopup.show(context, "Jarvis", r) }
                runCatching { JarvisVoice.announce(r) }
                runCatching { IraHub.note(r) }
                continue
            }
            val said = if (now - item.at > GRACE_MS) "I did not \"${item.text}\": its time passed while the phone was off. Ask me again if you still want it."
            else {
                val c = Ask.parse(item.text).command
                val starts = c?.kind == com.optionslab.ira.Command.Kind.START_ALL || c?.kind == com.optionslab.ira.Command.Kind.START_ONE
                val s = runCatching { com.optionslab.app.data.AppSettings.load() }.getOrNull()
                if (c == null || c.kind !in Later.ALLOWED) "I did not \"${item.text}\": it is not something I may do on a timer."
                // A start runs only as confirmed: the same mode, the kill switch off, the day's loss breaker not tripped.
                else if (starts && (s == null || s.live != item.live)) "I did not \"${item.text}\": the app is in ${if (s?.live == true) "Live" else "Paper"} mode now, not the mode you set it in. Ask me again if you still want it."
                else if (starts && (s!!.guardKill || com.optionslab.app.data.LossBreaker.trippedToday())) "I did not \"${item.text}\": the kill switch is on or the day's loss limit was hit."
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
