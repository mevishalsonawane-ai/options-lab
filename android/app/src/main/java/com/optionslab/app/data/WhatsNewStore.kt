package com.optionslab.app.data

import com.optionslab.ira.WhatsNew

/**
 * Which "What's new" entries Boss has seen ([WhatsNew]): their ids, one string in the app's settings ([KEY]; nothing
 * secret, carried in a backup like any setting). Home's card lists the unseen ones until "Got it" ([markAllSeen]).
 * IraGoldAlgo shows only the entries marked for it ([entries]). A settings file that cannot be read shows nothing new
 * rather than failing.
 */
internal object WhatsNewStore {
    const val KEY = "home.whatsnew.seen"

    /** The entries this build shows, newest first. */
    fun entries(): List<WhatsNew.Entry> =
        WhatsNew.forBuild(gold = com.optionslab.app.BuildConfig.GOLD, jarvis = com.optionslab.app.BuildConfig.JARVIS)

    /** The ids seen; null when the settings cannot be read (then nothing is shown as new). */
    fun seen(): Set<String>? = runCatching { WhatsNew.decode(com.optionslab.app.security.SecurePrefs.getString(KEY)) }.getOrNull()

    /** This build's entries not seen yet, newest first (empty when the settings cannot be read). */
    fun unseen(): List<WhatsNew.Entry> = seen()?.let { WhatsNew.unseen(entries(), it) } ?: emptyList()

    /** "Got it": every entry this build shows is kept as seen (the write itself runs on the settings' own writer). */
    fun markAllSeen() {
        runCatching {
            val now = WhatsNew.markSeen(seen().orEmpty(), entries())
            com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(KEY to WhatsNew.encode(now)))
        }
    }
}
