package com.optionslab.app.data

import com.optionslab.ira.WhatsNew
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Which "What's new" entries Boss has seen ([WhatsNew]): their ids, one string in the app's settings ([KEY]; nothing
 * secret, carried in a backup like any setting). Home's Dashboard and the Ira page show the unseen ones ([shown], one list
 * for both) until "Got it" ([markAllSeen]), which hides the card in both places at once. IraGoldAlgo shows only the entries
 * marked for it ([entries]). A settings file that cannot be read shows nothing new rather than failing.
 */
internal object WhatsNewStore {
    const val KEY = "home.whatsnew.seen"

    private val unseenNow = MutableStateFlow<List<WhatsNew.Entry>>(emptyList())
    @Volatile private var loaded = false

    /** The entries this build shows, newest first. */
    fun entries(): List<WhatsNew.Entry> =
        WhatsNew.forBuild(gold = com.optionslab.app.BuildConfig.GOLD, jarvis = com.optionslab.app.BuildConfig.JARVIS)

    /** The ids seen; null when the settings cannot be read (then nothing is shown as new). */
    fun seen(): Set<String>? = runCatching { WhatsNew.decode(com.optionslab.app.security.SecurePrefs.getString(KEY)) }.getOrNull()

    /** This build's entries not seen yet, newest first (empty when the settings cannot be read). */
    fun unseen(): List<WhatsNew.Entry> = seen()?.let { WhatsNew.unseen(entries(), it) } ?: emptyList()

    /**
     * The unseen entries every card shows, read from the settings the first time it is asked for (a settings file that could
     * not be read then is read again on the next call: the cards call it again each time the app comes back to the front).
     * A flow, not Compose state: asking for it never writes a screen's state.
     */
    fun shown(): StateFlow<List<WhatsNew.Entry>> {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val seen = seen()
                if (seen != null) { unseenNow.value = WhatsNew.unseen(entries(), seen); loaded = true }
            }
        }
        return unseenNow
    }

    /** "Got it": every entry this build shows is kept as seen (the write itself runs on the settings' own writer); every card hides. */
    fun markAllSeen() {
        runCatching {
            val now = WhatsNew.markSeen(seen().orEmpty(), entries())
            com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf(KEY to WhatsNew.encode(now)))
        }
        synchronized(this) { unseenNow.value = emptyList(); loaded = true }
    }

    /**
     * Read the seen ids again now (after a backup is restored: the cached list is the old phone's). A settings file that
     * cannot be read leaves the list as it was and is read again on the next [shown].
     */
    fun reload() {
        synchronized(this) {
            val seen = seen()
            if (seen != null) { unseenNow.value = WhatsNew.unseen(entries(), seen); loaded = true } else loaded = false
        }
    }

    /** Read the settings again on the next [shown] (tests, after they set the seen ids). */
    fun forget() { synchronized(this) { loaded = false; unseenNow.value = emptyList() } }
}
