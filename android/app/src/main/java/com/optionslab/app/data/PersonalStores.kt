package com.optionslab.app.data

import java.io.File

/**
 * Every store that keeps personal or trading data, in one list, so "Erase everything" wipes each one (26 Oct).
 *
 * Before this, [com.optionslab.app.ui.eraseEverything] named about 20 stores by hand and missed many (the grammar, night, Hero,
 * MCX and gold arms, the risk hosts, Jarvis's books...). Those kept their books in memory and saved them again, sealed with a
 * new key, after the erase: personal trading data survived it. Now each store joins [ALL] with
 *  - [Entry.wipe]: drops its memory and deletes its files (never reads the vault: the key is already gone);
 *  - [Entry.files]: the files it writes (the erase deletes them again after the wipe, in case the wipe missed one);
 *  - [Entry.holds]: whether it still holds data in memory (memory only, never the disk), for the tests.
 *
 * A store that keeps nothing in memory and writes only settings keys ([com.optionslab.app.security.SecurePrefs], wiped as a
 * whole) is listed in [PREFS_ONLY]; a source file that writes only public market data, or nothing personal, in [NOT_PERSONAL].
 * PersonalStoresTest reads every source file that saves anything and fails if it is in none of the three lists, so a new
 * store cannot be forgotten; it then seeds every store, erases, and checks that nothing is left in memory or on disk.
 */
object PersonalStores {
    class Entry(
        /** The store's source file (without ".kt"): the test matches it to the files that save something. */
        val source: String,
        /** The large folders: wiped on the erase's slow file thread, after the vault is released. */
        val slow: Boolean = false,
        val files: () -> List<File> = { emptyList() },
        val holds: () -> Boolean = { false },
        val wipe: () -> Unit,
    )

    /** The vault-held stores, wiped while the vault is held ([com.optionslab.app.security.Vault.beginErase]). */
    val ALL: List<Entry> by lazy { listOf(
        // ENTRIES
    ) }

    /** Source files that save only settings keys and keep no copy in memory: [com.optionslab.app.security.SecurePrefs.wipe] clears them. */
    val PREFS_ONLY: Map<String, String> = mapOf(
        // PREFS_ONLY
    )

    /** Source files that save something, but nothing personal (public market data, the app's own build facts): why each. */
    val NOT_PERSONAL: Map<String, String> = mapOf(
        // NOT_PERSONAL
    )

    /**
     * Files (by name, under the app's own folders) that may remain after an erase, and why each holds nothing personal.
     * Market data is public and stays (the erase has always kept it).
     */
    val KEPT_FILES: Map<String, String> = mapOf(
        // KEPT_FILES
    )

    /** TEST ONLY: one more store, wiped with the rest (a test shows one that throws never stops the others). */
    @Volatile internal var testExtra: List<Entry> = emptyList()

    /**
     * Wipes every store of one kind ([slow] or not), each guarded: one that throws never stops the rest. Its files are deleted
     * again afterwards. Returns the stores whose wipe threw (by source name), for the diagnostics.
     */
    fun wipeAll(slow: Boolean): List<String> {
        val failed = ArrayList<String>()
        for (e in ALL + testExtra) {
            if (e.slow != slow) continue
            runCatching { e.wipe() }.onFailure { failed += e.source }
            runCatching { e.files().forEach { f -> if (f.isDirectory) f.deleteRecursively() else f.delete() } }
        }
        return failed
    }
}
