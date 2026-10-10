package com.optionslab.ira

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings search ([SettingsIndex]) and "where is the X setting" ([SettingWhere]): every entry points at a page the app's
 * Settings has (CabinetScreen's drawers, or IraGoldAlgo's own Settings) and at a row key the app's source really carries
 * (read from the app's source beside this module, as CollisionTest reads IraHub); the search finds by title, description
 * and synonyms; GOLD lists only its own; Jarvis's answer names the path and never claims to switch anything.
 */
class SettingsIndexTest {
    private val app = File("../app/src/main/java/com/optionslab/app")
    private fun src(rel: String) = File(app, rel).readText()
    private val allSource: String by lazy { app.walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() } }

    /** CabinetScreen's drawers: key -> title (a constant title read from its declaration). */
    private fun drawers(): Map<String, String> {
        val cab = src("ui/screens/CabinetScreen.kt")
        return Regex("Drawer\\(\"([a-z-]+)\", (\"[^\"]+\"|[A-Z_]+),").findAll(cab).associate { m ->
            val t = m.groupValues[2]
            m.groupValues[1] to if (t.startsWith("\"")) t.trim('"') else
                Regex("const val $t = \"([^\"]+)\"").find(allSource)!!.groupValues[1]
        }
    }

    @Test fun everyPageIsADrawerAndEveryKeyIsInTheSource() {
        assertTrue(app.isDirectory, "the app's source beside this module")
        val cab = src("ui/screens/CabinetScreen.kt")
        val drawers = drawers()
        assertTrue(drawers.size >= 15, "$drawers")
        val automations = src("ira/Automations.kt")
        val notes = src("ira/IraNotes.kt")
        val gold = src("ui/screens/GoldScreens.kt")
        for (e in SettingsIndex.ENTRIES) {
            val what = "${e.title} (${e.page}/${e.key})"
            if (e.page == SettingsIndex.GOLD_PAGE) {
                // IraGoldAlgo's own Settings: never listed in IraAlgo, its row in GoldScreens.
                assertTrue(e.gold && !e.ira, what)
                assertTrue("SettingSpot(\"${e.key}\")" in gold, what)
                continue
            }
            // A drawer of CabinetScreen's, opened by its DrawerPage.
            assertTrue(e.page in drawers, what)
            assertTrue("\"${e.page}\" -> " in cab, what)
            assertEquals(drawers[e.page], e.path.first(), what)
            when {
                e.isPage -> assertEquals(listOf(e.title), e.path, what)
                e.key.startsWith("jarvis.switch.group.") -> {
                    val g = e.key.removePrefix("jarvis.switch.group.")
                    assertTrue(Regex("\\n        $g\\(\"").containsMatchIn(automations.substringAfter("enum class Group")), what)
                    assertTrue("\"jarvis.switch.group.\${g.name}\"" in notes, what)
                }
                e.key.startsWith("jarvis.switch.sub.") -> {
                    val a = e.key.removePrefix("jarvis.switch.sub.")
                    assertTrue(Regex("subs = listOf\\([^)]*Auto\\.$a\\b").containsMatchIn(automations), what)
                    assertTrue("\"jarvis.switch.sub.\${a.name}\"" in notes, what)
                }
                e.key == "jarvis.solo" -> {
                    assertTrue("SOLO_KEY = \"jarvis.solo\"" in notes, what)
                    assertTrue("SettingSpot(com.optionslab.app.ira.IraNotes.SOLO_KEY)" in allSource, what)
                }
                else -> assertTrue("SettingSpot(\"${e.key}\")" in allSource, what)
            }
        }
        // Jarvis's rows are on his page; the group and sub rows are drawn by AutomationsCard with those keys.
        assertTrue("SettingSpot(com.optionslab.app.ira.IraNotes.groupKey(g))" in allSource)
        assertTrue("SettingSpot(com.optionslab.app.ira.IraNotes.subKey(a))" in allSource)
    }

    /** Titles as the app shows them (a few are said in words where the app builds them from parts). */
    @Test fun everyTitleIsTheAppsOwn() {
        val shown = allSource.replace("\\\"", "\"")
        val composed = setOf("Answers: short or detailed", "Trading mode: Live or Paper", "Product: NRML or MIS", "Set a price alarm",
            "Battery: Unrestricted", "Notifications and precise alarms", "Erase after wrong PINs", "Record market data")
        for (e in SettingsIndex.ENTRIES) if (e.title !in composed) assertTrue(e.title in shown, e.title)
    }

    @Test fun theCatalogueIsWhole() {
        val all = SettingsIndex.ENTRIES
        assertTrue(all.size >= 100, "${all.size}")
        assertEquals(all.size, all.map { it.page to it.title }.distinct().size, "each setting once on its page")
        for (e in all) {
            assertTrue(e.title.isNotBlank() && e.description.isNotBlank() && e.path.isNotEmpty(), e.title)
            assertTrue(e.pathText.startsWith("Settings → "), e.pathText)
            assertTrue(e.gold || e.ira, e.title)
        }
        // The spec's own example.
        assertEquals("Settings → Voice and AI model → What Jarvis does by itself → Market alerts",
            all.first { it.key == "jarvis.switch.group.MARKET" && it.title == "Market alerts" }.pathText)
        assertEquals("Settings → Voice and AI model → What Jarvis does by itself → Market alerts → Opening read",
            all.first { it.key == "jarvis.switch.sub.OPENING" }.pathText)
        // The security rows are there to be found, each marked as keeping its own check.
        for (t in listOf("Fingerprint", "Change PIN", "Kill switch", "AI trades go live", "Guard my positions", "Live orders without PIN",
            "Allow screenshots and screen recording", "Erase everything personal", "Backup and restore"))
            assertTrue(all.first { it.title == t }.guarded, t)
        assertTrue(all.first { it.title == "Trading mode: Live or Paper" }.title.isNotEmpty())
    }

    @Test fun theSearchFindsByTitleDescriptionAndSynonym() {
        fun titles(q: String, gold: Boolean = false) = SettingsIndex.search(q, gold).map { it.title }
        // Every word Boss is likely to try finds something.
        for (w in listOf("notification", "sound", "voice", "speak", "lots", "liquidity", "solo", "hero", "backup", "pin", "fingerprint", "quiet",
            "battery", "news", "alerts", "gift", "recorder", "theme", "kill", "mute"))
            assertTrue(titles(w).isNotEmpty(), w)
        // The title first.
        assertEquals("Backup and restore", titles("backup").first())
        assertEquals("Kill switch", titles("kill switch").first())
        assertEquals("Fingerprint", titles("fingerprint").first())
        assertTrue(titles("quiet").containsAll(listOf("Quiet hours 22:00 to 07:00", "Quiet hours")))
        assertTrue("Opening read" in titles("gift"))
        assertTrue("Record market data" in titles("recorder"))
        assertTrue("Mute Jarvis (replies on screen only)" in titles("sound"))
        assertTrue(titles("lots").containsAll(listOf("Max lots per instrument", "Lot sizes")))
        assertTrue("Solo: Jarvis trades by himself (paper)" in titles("solo"))
        assertTrue("Forward-test watch" in titles("hero"))
        // A word's start, any case, plural or not; words in any order.
        assertTrue("Other notifications" in titles("NOTIF"))
        assertEquals(titles("alert"), titles("alerts"))
        assertEquals(titles("market alerts").toSet(), titles("alerts market").toSet())
        // The description too.
        assertTrue("Kill switch" in titles("square-offs"))
        // "news" is news, never "new".
        assertFalse("No new entries after" in titles("news"))
        // Nothing, or only filler: nothing.
        assertTrue(titles("").isEmpty()); assertTrue(titles("   ").isEmpty()); assertTrue(titles("the setting").isEmpty())
        assertTrue(titles("cricket score").isEmpty())
        // Pages a build does not show are left out.
        assertTrue(SettingsIndex.search("quiet", false, pages = setOf("security", "risk")).isEmpty())
    }

    @Test fun goldListsOnlyItsOwn() {
        val gold = SettingsIndex.forBuild(gold = true)
        assertTrue(gold.isNotEmpty() && gold.all { it.gold })
        assertTrue(gold.size < SettingsIndex.forBuild(gold = false).size)
        // IraGoldAlgo's Settings: its paper, background, look and help, and Security (no Zerodha, no Jarvis page, no bot).
        assertTrue(gold.all { it.page == SettingsIndex.GOLD_PAGE || it.page == "security" }, gold.map { it.pathText }.toString())
        for (q in listOf("kill", "backup", "quiet", "zerodha", "solo", "recorder", "certificate"))
            assertTrue(SettingsIndex.search(q, gold = true).isEmpty(), q)
        assertEquals(listOf("Fingerprint"), SettingsIndex.search("fingerprint", gold = true).map { it.title })
        assertTrue(SettingsIndex.search("lots", gold = true).map { it.title }.contains("Lot size"))
        assertTrue(SettingsIndex.search("theme", gold = true).all { it.page == SettingsIndex.GOLD_PAGE })
        // IraAlgo never lists IraGoldAlgo's own.
        assertTrue(SettingsIndex.forBuild(gold = false).none { it.page == SettingsIndex.GOLD_PAGE })
    }

    // ---- SettingWhere ----

    @Test fun whereAndHowToQuestionsAreRead() {
        val asked = mapOf(
            "where is the quiet hours setting" to "quiet hours", "where's the mute switch" to "mute", "where do i find the backup option" to "backup",
            "where is the setting for quiet hours" to "quiet hours", "where is quiet hours in settings" to "quiet hours",
            "how do i turn off market alerts" to "market alerts", "how can i switch on the fingerprint" to "fingerprint",
            "how to turn off notifications" to "notifications", "how do i turn quiet hours off" to "quiet hours", "how do i change the theme" to "theme",
            "how do i mute jarvis" to "mute", "how do i stop jarvis talking" to "jarvis talking",
            "backup ki setting kahan hai" to "backup", "liquidity ka switch kidhar hai" to "liquidity", "quiet hours setting kaha hai" to "quiet hours",
            "solo kaise band karu" to "solo", "market alerts kaise off karte hai" to "market alerts", "jarvis where is the backup setting" to "backup")
        for ((s, topic) in asked) assertEquals(SettingWhere.Q(topic), SettingWhere.asked(s), s)
        // Not a where / how-to of a setting: a command, the market, an arm, a trade, a position, nothing nameable.
        for (s in listOf("turn off liquidity", "switch off solo", "stop jarvis talking", "mute", "where is nifty", "how do i turn off orb",
            "how do i stop orb", "how do i close my position", "how do i turn off my position", "where is the settings", "where is the strategies switch",
            "how do i turn off cricket", "what is quiet hours", "is the kill switch on", "where is the kill switch", "how do i turn off the kill switch", "how do i buy a call", "where is the gift nifty setting"))
            assertNull(SettingWhere.asked(s), s)
    }

    @Test fun theAnswerNamesThePathAndSwitchesNothing() {
        val one = SettingWhere.answer(SettingWhere.Q("backup"), gold = false)
        assertTrue(one.startsWith("Boss, it's under Settings → Security → Backup and restore."), one)
        assertTrue(one.contains("Type \"backup\" in the search at the top of Settings"), one)
        assertTrue(one.contains(SettingWhere.NOTHING_SWITCHED) && one.contains(SettingWhere.GUARDED), one)
        val market = SettingWhere.answer(SettingWhere.Q("market alerts"), gold = false)
        assertTrue(market.contains("Settings → Voice and AI model → What Jarvis does by itself → Market alerts"), market)
        // Several fit: up to three named, and how many.
        val voice = SettingWhere.answer(SettingWhere.Q("voice"), gold = false)
        assertEquals(SettingWhere.NAMED_MAX, Regex("Settings → ").findAll(voice).count(), voice)
        // An arm named: where its own switch is too.
        assertTrue(SettingWhere.answer(SettingWhere.Q("liquidity"), gold = false).contains(SettingWhere.ARMS))
        // Nothing by that name: said so, and the search named.
        val none = SettingWhere.answer(SettingWhere.Q("cricket"), gold = false)
        assertTrue(none.contains("can't find a setting called \"cricket\""), none)
        // Only the pages Settings shows (the set its search is given): a page it would not list is never named.
        val notes = SettingWhere.answer(SettingWhere.Q("today's notes"), gold = false)
        assertTrue(notes.contains("Today's notes"), notes)
        val shown = SettingWhere.answer(SettingWhere.Q("today's notes"), gold = false, pages = setOf("security", "jarvis"))
        assertFalse(shown.contains("Today's notes"), shown)
        assertEquals(SettingsIndex.search("backup", gold = false, pages = setOf("security")).first().pathText,
            SettingWhere.answer(SettingWhere.Q("backup"), gold = false, pages = setOf("security")).removePrefix("Boss, it's under ").substringBefore(". Type"))
        // IraGoldAlgo: its own Settings only.
        assertTrue(SettingWhere.answer(SettingWhere.Q("kill"), gold = true).contains("can't find"))
        assertTrue(SettingWhere.answer(SettingWhere.Q("fingerprint"), gold = true).contains("Settings → Security → Unlocking → Fingerprint"))
        // Never says anything was switched, turned or done.
        for (a in listOf(one, market, voice, none)) assertFalse(Regex("(?i)\\b(i have|i've|done|switched (it )?(off|on)|turned (it )?(off|on))\\b").containsMatchIn(a), a)
    }
}
