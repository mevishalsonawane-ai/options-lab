package com.optionslab.ira

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Who muted Jarvis, and whether a heard mute was clear (Boss, 5 Oct: "why is it sending most things in chat instead of
 * speaking?" - a single mis-heard "mute" after "Yes, Boss?" muted him for the rest of the day; his log showed three).
 *
 * A HEARD mute must be clear: the name in the same words ("Jarvis, mute"), or a phrase that cannot be a mis-heard word
 * ("mute yourself", "mute your voice", "voice off", "stay silent", "don't speak", "chup raho"...). A bare "mute",
 * "muted" or "mute it" heard after the wake prompt is not taken. A typed mute, or the Settings switch, is as Boss set it.
 *
 * A VOICE mute lasts the day at most: from the next day it no longer holds ([holds]), and the morning check says so
 * ([morningLine]). Typed and Settings mutes stay until Boss unmutes. What is kept: the source, when, and the mute's own
 * command words ([heardAs]: a few words that matched a mute phrase - never anything else heard). Pure.
 */
object VoiceMute {
    enum class By(val key: String, val say: String) { VOICE("voice", "voice"), TYPED("typed", "typing"), SETTINGS("settings", "Settings") }

    /** One mute: its source, when (epoch ms), and the command words heard ([By.VOICE] only). */
    data class Mark(val by: By, val at: Long, val heardAs: String? = null)

    private val CLEAR = Regex("^(mute (yourself|your voice|the voice|jarvis)|(be|go|stay|keep) (on )?silent|(stay|keep|be) muted|" +
        "(don t|do not|dont) (speak|talk)( any more| anymore)?|voice off|turn (off )?(your )?voice( off)?|switch (off )?(your )?voice( off)?|" +
        "be quiet|stay quiet|keep quiet|chup (ho jao|raho|karo|ho ja)|awaaz band( karo)?|mute (for|till|until) .+|" +
        // Understanding round 24: the voice named in Hinglish as the recognizer spells it, and its split "your self".
        "(apni |apna |tumhari |aapki )?(awaz|aawaz|awaaz|aavaz|avaaz|voice) band (karo|kar do|kardo|kar dijiye|kijiye)|mute (your self|you self|urself|ur self))$")

    private fun norm(s: String) = spacedWords(s.lowercase().replace("'", " "))

    /**
     * Is a mute HEARD as [question] (the words after the name) clear enough to mute? [named]: the name itself was in the
     * same words (the recognizer's best reading) - then "Jarvis, mute" is enough. A bare "mute" heard without the name in
     * those words (after "Yes, Boss?", the mic button or a follow-up) is never enough.
     */
    fun heardClear(question: String, named: Boolean): Boolean {
        val q = norm(question).replace(Regex("^(please |ok |okay |now )+"), "").replace(Regex("( please| now| boss)+$"), "").trim()
        if (q.isEmpty()) return false
        if (CLEAR.matches(q)) return true
        // The name in the same words: a short, plain mute is clear ("Jarvis, mute", "Jarvis mute please").
        return named && Regex("^(be |go |stay |keep )?(mute|muted)( karo| kar do| ho jao)?$").matches(q)
    }

    /** The mute's own words kept for the diagnostics: a short mute phrase only (else "a mute phrase"). */
    fun heardAs(question: String): String {
        val q = norm(question)
        val w = q.split(' ').filter { it.isNotEmpty() }
        return if (w.isNotEmpty() && w.size <= 4 && Regex("\\b(mute|muted|silent|quiet|voice|speak|talk|chup|awaaz)\\b").containsMatchIn(q)) q else "a mute phrase"
    }

    /** Kept as "voice|1696500000000|mute yourself". */
    fun encode(m: Mark): String = "${m.by.key}|${m.at}|${m.heardAs ?: ""}"

    fun decode(s: String?): Mark? {
        if (s.isNullOrBlank()) return null
        val p = s.split('|', limit = 3)
        val by = By.entries.firstOrNull { it.key == p.getOrNull(0) } ?: return null
        val at = p.getOrNull(1)?.toLongOrNull() ?: return null
        return Mark(by, at, p.getOrNull(2)?.takeIf { it.isNotBlank() })
    }

    private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /** Does the mute [m] still hold on [today]? A voice mute holds only on its own day; typed or Settings until undone. */
    fun holds(m: Mark?, today: LocalDate, zone: ZoneId): Boolean {
        if (m == null) return true                      // a mute from before the source was kept: as before
        return m.by != By.VOICE || !day(m.at, zone).isBefore(today)
    }

    /** "Muted by: voice at 15:01 (heard as 'mute')" - for the diagnostics (and the voice check). */
    fun diagLine(muted: Boolean, m: Mark?, zone: ZoneId): String = when {
        !muted -> "Muted by: not muted"
        m == null -> "Muted by: not recorded (muted before this was kept)"
        else -> "Muted by: ${m.by.say} at ${hm(m.at, zone)}" + (if (m.by == By.VOICE) " (heard as '${m.heardAs ?: "a mute phrase"}')" else "")
    }

    /** Why Jarvis is silent, for "why aren't you speaking?". */
    fun why(m: Mark?, zone: ZoneId): String = when {
        m == null -> "I'm muted"
        m.by == By.VOICE -> "I'm muted: I heard \"${m.heardAs ?: "mute"}\" at ${hm(m.at, zone)} and took it as mute (it lasts today only)"
        m.by == By.TYPED -> "I'm muted: you typed mute at ${hm(m.at, zone)}"
        else -> "I'm muted: Mute is on in Settings since ${hm(m.at, zone)}"
    }

    /** The morning check's line when yesterday's voice mute [m] has ended (null: nothing to say). */
    fun morningLine(m: Mark?, today: LocalDate, zone: ZoneId): String? {
        if (m == null || m.by != By.VOICE || !day(m.at, zone).isBefore(today)) return null
        return "I was muted by voice yesterday at ${hm(m.at, zone)}; I'm speaking again today. Say \"Jarvis, mute yourself\" to mute me."
    }

    /** What Jarvis says aloud before a voice mute. */
    const val MUTING = "Muting, Boss. Say \"Jarvis, unmute\" to hear me."

    /** A bare heard "mute" that was not taken. */
    const val UNCLEAR = "Boss, I only heard \"mute\", so I'm still speaking. To mute me, say \"Jarvis, mute yourself\"."

    private fun hm(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
}
