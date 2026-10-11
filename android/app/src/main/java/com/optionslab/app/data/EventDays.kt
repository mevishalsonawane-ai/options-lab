package com.optionslab.app.data

import com.optionslab.app.security.SecurePrefs
import com.optionslab.ira.EventCalendar
import com.optionslab.ira.LearningFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * The event-day calendar on the phone (TODO S11; ira-core's [EventCalendar] and [LearningFilter]): the bundled 2026-2027
 * dates and the rule-made ones (index rebalances, expiries moved by the app's holiday list), Boss's own events (the ones he
 * adds to Jarvis, "add event ...", [com.optionslab.app.ira.IraEvents], and in Bot settings) and the bundled days he took out.
 * [refresh] (a worker thread: it reads the settings once) builds the calendar and hands it to [LearningFilter], so the
 * lessons, Autopilot and the lab leave event days out of their evidence; [today] is what Home and Jarvis say.
 */
object EventDays {
    private const val K_REMOVED = "events.removed"

    private val _calendar = MutableStateFlow(LearningFilter.calendar)
    val calendar: StateFlow<EventCalendar.Calendar> = _calendar
    @Volatile private var builtOn: LocalDate? = null

    /** Rebuilt once a day (or on a change), off the main thread. */
    fun refreshIfStale() { if (builtOn != runCatching { Market.today() }.getOrNull()) refresh() }

    fun refresh() {
        val today = runCatching { Market.today() }.getOrDefault(LocalDate.now())
        val removed = runCatching { SecurePrefs.getString(K_REMOVED).orEmpty().split(',').mapNotNull { s -> runCatching { LocalDate.parse(s.trim()) }.getOrNull() }.toSet() }
            .getOrDefault(emptySet())
        val owner = runCatching { EventCalendar.fromEvents(com.optionslab.app.ira.IraEvents.ownerAll()) }.getOrDefault(emptyList())
        val c = EventCalendar.calendar(today.minusYears(1).withDayOfYear(1), maxOf(LocalDate.of(2027, 12, 31), today.plusYears(1)),
            { d -> runCatching { Holidays.isHoliday(d) }.getOrDefault(false) }, owner, removed)
        LearningFilter.use(c)
        _calendar.value = c
        builtOn = today
    }

    /** Today's note for Home and Jarvis, or null on an ordinary day. Memory only. */
    fun todayNote(): String? = runCatching { EventCalendar.note(_calendar.value, Market.today()) }.getOrNull()

    fun isEventDay(day: LocalDate): Boolean = _calendar.value.isEventDay(day)

    /** The next [days] days' entries, for Bot settings. */
    fun upcoming(days: Long = 120): List<EventCalendar.Entry> {
        val t = runCatching { Market.today() }.getOrDefault(LocalDate.now())
        return _calendar.value.between(t, t.plusDays(days))
    }

    /** Boss takes a bundled or rule-made day out (his own events are removed where he added them). */
    fun remove(day: LocalDate): String {
        val cur = runCatching { SecurePrefs.getString(K_REMOVED).orEmpty() }.getOrDefault("")
        val next = (cur.split(',').filter { it.isNotBlank() } + day.toString()).distinct().sorted().takeLast(200)
        runCatching { SecurePrefs.putAll(mapOf(K_REMOVED to next.joinToString(","))) }
        runCatching { com.optionslab.app.ira.IraEvents.ownerAll().filter { it.day == day }.forEach { com.optionslab.app.ira.IraEvents.remove(it) } }
        refresh()
        return "Taken out: learning counts ${day.dayOfMonth} ${day.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} as an ordinary day."
    }

    /** Boss adds an event day ([name]: "RBI policy", "State election results"). */
    fun add(day: LocalDate, name: String): String {
        runCatching { com.optionslab.app.ira.IraEvents.add(day, name.ifBlank { "Event" }) }
        val cur = runCatching { SecurePrefs.getString(K_REMOVED).orEmpty() }.getOrDefault("")
        if (day.toString() in cur) runCatching { SecurePrefs.putAll(mapOf(K_REMOVED to cur.split(',').filter { it.isNotBlank() && it != day.toString() }.joinToString(","))) }
        refresh()
        return "Added: learning leaves ${day.dayOfMonth} ${day.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} out of its evidence."
    }

    /** TEST ONLY. */
    internal fun resetForTest() { builtOn = null; LearningFilter.resetForTest(); _calendar.value = LearningFilter.calendar }
}
