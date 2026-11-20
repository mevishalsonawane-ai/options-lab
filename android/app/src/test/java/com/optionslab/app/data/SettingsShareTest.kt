package com.optionslab.app.data

import com.optionslab.app.security.MainWrites
import com.optionslab.app.security.SecurePrefs
import com.optionslab.app.testing.NetworkGuard
import com.optionslab.app.testing.RobolectricTest
import com.optionslab.engine.orb.DonchianArm
import com.optionslab.ira.ArmAutoStops
import com.optionslab.ira.StateShare
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * 20 Nov 2026 (Boss: "a button where i could share u all the setting details"): the "Share full settings and state" file
 * ([SettingsShare]) - every heading, R51's "not mirrored" reason and the automatic stops' switches in it, never cut short (more than the
 * 140 lines a pasted note kept), no planted key, id or address, and built off the screen's thread.
 */
class SettingsShareTest : RobolectricTest() {
    private val reason = "the account stop rule: drawdown -Rs 41,200 from its best (limit -Rs 40,000)"

    @Before fun up() {
        assumeFalse("IraGoldAlgo has no arms to share", com.optionslab.app.BuildConfig.GOLD)
        com.optionslab.app.testing.forgetFileProviderPaths()
        MainWrites.resetForTest()
        ArmAutoStopsHost.resetForTest(ArmAutoStops.State(on = mapOf(ArmAutoStops.Rule.FORWARD_BAR to false, ArmAutoStops.Rule.LIVE_STOP to false),
            migrated = true))
        val today = Market.today()
        val now = Market.now().toLocalDateTime().withNano(0)
        ArmLive.viewForTest(ArmLive.View(switches = mapOf(DonchianArm.SOURCE to true), shadow = mapOf(DonchianArm.SOURCE to false), loaded = true,
            notMirrored = (1..8).map { ArmLive.NotMirrored("passed:$it", DonchianArm.SOURCE, today.atTime(9, 20 + it), reason) }))
        check(now.toLocalDate() == today)
    }

    @After fun down() {
        ArmLive.wipe()
        ArmAutoStopsHost.resetForTest()
        MainWrites.resetForTest()
        assertEquals("no test may reach the internet", emptyList<String>(), NetworkGuard.blocked.toList())
    }

    private fun plantSecrets() {
        SecurePrefs.putAll(mapOf("kite.apiKey" to "plantedapikey1234", "kite.userId" to "QX4321", "relay.host" to "relay.example.net",
            "ntfy.topic" to "boss-secret-topic"))
        SecurePrefs.flush()
        Diag.record("relay", "connected to relay.example.net at 13.234.56.78:22 as QX4321 · mail boss@example.com · call 9876543210")
        repeat(200) { Diag.record("armlive", "R51 Donchian 20: break $it held back") }
        Diag.flush()
    }

    @Test fun theFileHasEveryHeadingTheReasonAndTheSwitchesAndIsNotCut() {
        plantSecrets()
        val text = runBlocking { SettingsShare.text() }
        val lines = text.lines()
        assertEquals(StateShare.TOP_LINE, lines.first())
        for (h in StateShare.HEADINGS) assertTrue("heading $h", lines.contains("== $h"))
        // R51's not-mirrored count and its last reason (Boss's 20 Nov round trips).
        assertTrue(text, text.contains("Not mirrored today: 8 (last: $reason)"))
        // The automatic stops: every one of the five switches with its state.
        assertTrue(text, text.contains("- ${ArmAutoStops.Rule.FORWARD_BAR.switch}: off"))
        assertTrue(text, text.contains("- ${ArmAutoStops.Rule.LIVE_STOP.switch}: off"))
        for (r in listOf(ArmAutoStops.Rule.SELF_REVIEW, ArmAutoStops.Rule.PAPER_ACCOUNT, ArmAutoStops.Rule.JARVIS_CUTOFF))
            assertTrue(r.name, text.contains("- ${r.switch}: on"))
        // Every arm the live path copies, the stops and the saved settings.
        for (w in listOf("R51 Donchian 20 [", "R72 Breadth Donchian", "Gates: ", "A live entry now: ", "Arms' live-copies account stop",
                "Daily loss limit:", "Day lock:", "Liquidity's live stop rule", "Weekly loss stop", "liveMarkets: ", "armLiveLots: ",
                "Exit coordinator", "R151 picker", "Paper refusals go live", "Funds check", "Trade manager modes"))
            assertTrue(w, text.contains(w))
        // Not cut: the whole report and today's 200 diary lines of the arms' live path.
        assertTrue("${lines.size} lines", lines.size > 140)
        assertTrue(text, text.contains("break 199 held back"))
        assertTrue(text.contains("-- Events (newest first) --"))
        // Nothing of the account's identity, keys or addresses.
        for (s in listOf("plantedapikey1234", "QX4321", "relay.example.net", "13.234.56.78", "boss@example.com", "9876543210", "boss-secret-topic"))
            assertFalse("$s leaked", text.contains(s))
    }

    private fun closedLive(net: Double): ArmLive.Pos {
        val t = Market.now().toLocalDateTime().withNano(0).minusHours(1)
        // Bought 100 at 500, sold at 500 + net / 100: the trade's net (no charges).
        return ArmLive.Pos(DonchianArm.SOURCE, "passed:x", "NIFTY26NOV24500CE", "NFO", "MIS", 1, 100, 100, 75, 0.05, 1, 500.0, t,
            500.0, 100, null, "1", exit = 500.0 + net / 100, exitTime = t.plusMinutes(10))
    }

    @Test fun theArmsAccountStopIsReadFromMemoryAndAnUnreadRecordRefuses() {
        // The ORB family's record not in memory yet: said, and counted as refusing (as the entry's own gate does).
        OrbArms.wipe()
        val (unread, unreadRefusal) = ArmLive.accountStopNow()
        assertTrue(unread, unread.contains("not in memory yet"))
        assertEquals(ArmLive.ACCOUNT_UNCHECKED, unreadRefusal)
        runBlocking { OrbArms.view() }
        assertTrue(OrbArms.closedLiveKept() != null)
        // Read and not reached: no refusal.
        ArmLive.viewForTest(ArmLive.View(loaded = true, closed = listOf(closedLive(-5_000.0))))
        val (fine, none) = ArmLive.accountStopNow()
        assertTrue(fine, fine.contains("not reached") && fine.contains("1 live trade(s)"))
        assertEquals(null, none)
        // Reached: its reason, and the file's R51 row says the live entry is refused for it.
        ArmLive.viewForTest(ArmLive.View(loaded = true, closed = listOf(closedLive(-41_000.0))))
        val (reached, why) = ArmLive.accountStopNow()
        assertTrue(reached, reached.contains("REACHED"))
        assertTrue(why, why!!.contains("drawdown"))
    }

    @Test fun eachLiquidityBookHasItsOwnLineAndThePaperLegsAreGroupedByArm() {
        runBlocking { OrbArms.view() }
        val books = OrbArms.liquidityBookLines()
        assertEquals(com.optionslab.engine.orb.LiquidityRules.BOOKS.size, books!!.size)
        assertTrue(books.joinToString("\n"), books.all { it.contains("cleared for Zerodha by your switch: ") && it.contains("status: ") })
        ArmLive.register("share-test") { listOf(ArmLive.PaperLeg("share-test:1", DonchianArm.SOURCE, 75, symbol = "NIFTY26NOV24500CE"),
            ArmLive.PaperLeg("share-test:2", DonchianArm.SOURCE, 0)) }
        val legs = ArmLive.paperLegsNow()[DonchianArm.SOURCE].orEmpty().filter { it.ref.startsWith("share-test") }
        assertEquals(2, legs.size)
        val text = runBlocking { SettingsShare.text() }
        assertTrue(text, text.contains("Book: Liquidity"))
        assertTrue(text, text.contains("held (NIFTY26NOV24500CE)"))
    }

    /** The "A live entry now" line under [label]'s row of the file. */
    private fun liveEntryOf(text: String, label: String): String {
        val lines = text.lines()
        val at = lines.indexOfFirst { it.startsWith("- $label [") }
        check(at >= 0) { "no row for $label" }
        return lines.drop(at + 1).first { it.trimStart().startsWith("A live entry now:") }
    }

    @Test fun theArmsAccountStopFollowsTheSwitchForR51AndR72Only() {
        val oov = com.optionslab.engine.orb.OovArm.SOURCE
        try {
            com.optionslab.app.testing.AutomationSupport.liveSettings()
            PaperOnly.resetForTest(null)
            runBlocking { OrbArms.view() }
            ArmLive.viewForTest(ArmLive.View(switches = mapOf(DonchianArm.SOURCE to true, oov to true),
                shadow = mapOf(DonchianArm.SOURCE to false, oov to false), loaded = true, closed = listOf(closedLive(-41_000.0))))
            // Boss's "Liquidity live stop rule" switch is off (the @Before): the reached stop does not act on R51; R52 keeps it.
            val off = runBlocking { SettingsShare.text() }
            assertTrue(off, off.contains("not acting on R51 and R72 while your \"Liquidity live stop rule\" switch is off"))
            assertFalse(liveEntryOf(off, "R51 Donchian 20"), liveEntryOf(off, "R51 Donchian 20").contains("account stop rule"))
            assertTrue(liveEntryOf(off, "R52 Open outside value"), liveEntryOf(off, "R52 Open outside value").contains("refused: the account stop rule"))
            // On again: R51 is refused by it as before.
            ArmAutoStopsHost.resetForTest()
            val on = runBlocking { SettingsShare.text() }
            assertFalse(on, on.contains("not acting on R51 and R72"))
            assertTrue(liveEntryOf(on, "R51 Donchian 20"), liveEntryOf(on, "R51 Donchian 20").contains("refused: the account stop rule"))
        } finally { com.optionslab.app.testing.AutomationSupport.liveSettings(false) }
    }

    @Test fun anUnreadableBookAndAFailingReadStillGiveTheWholeFile() {
        ArmLive.viewForTest(ArmLive.View(loaded = true, unreadable = true))
        try {
            SettingsShare.testFailPart = "Passed-research arms"
            val text = runBlocking { SettingsShare.text() }
            assertTrue(text, text.contains("Arms' live book: UNREADABLE (no live entries)"))
            assertTrue(text, text.contains("Mirrored arms (each follows its own paper trade at Zerodha): book UNREADABLE."))
            assertTrue(text, text.contains("Passed-research arms: could not be read (IllegalStateException)"))
            // Every other part still built.
            for (h in StateShare.HEADINGS) assertTrue(h, text.contains("== $h"))
            assertTrue(text.contains("ORB family and Liquidity 15+5") && text.contains("Daily loss limit:") && text.contains("-- Events (newest first) --"))
        } finally { SettingsShare.testFailPart = null }
    }

    @Test fun theFileIsDeletedThirtyMinutesLaterUnlessWrittenAgain() {
        assertEquals(30 * 60_000L, SettingsShare.KEEP_MS)
        val f = runBlocking { SettingsShare.write(context) }
        assertTrue("its delete is waiting", SettingsShare.sweepsWaiting() >= 1)
        val written = f.lastModified()
        // Written again since (a later share): this sweep leaves the new file to its own.
        f.setLastModified(written + 5_000)
        SettingsShare.sweep(f, written)
        assertTrue(f.exists())
        SettingsShare.sweep(f, f.lastModified())
        assertFalse(f.exists())
        // The erase deletes the folder.
        runBlocking { SettingsShare.write(context) }
        assertTrue(SettingsShare.filesForErase().single().exists())
        SettingsShare.wipe()
        assertFalse(SettingsShare.filesForErase().single().exists())
    }

    @Test fun theNameScrubCoversTheHolderGitHubAndTheOwner() {
        SecurePrefs.putAll(mapOf("github.token.login" to "boss-handle"))
        val known = SettingsShare.known()
        assertTrue(known.toString(), known.contains(com.optionslab.ira.CloudSync.OWNER) && known.contains("boss-handle"))
        assertTrue(StateShare.redact("synced to ${com.optionslab.ira.CloudSync.OWNER}/IraAlgo", known).contains(StateShare.MASK))
    }

    @Test fun theFileIsWrittenAsOneTextFileInTheSharedFolder() {
        val f = runBlocking { SettingsShare.write(context) }
        assertTrue(f.isFile && f.name.endsWith(".txt") && f.parentFile!!.path.contains("exports"))
        assertTrue(f.readText().startsWith(StateShare.TOP_LINE))
        // The exports FileProvider shares it.
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".exports", f)
        assertTrue(uri.toString(), uri.toString().contains(".exports"))
        // A second file replaces the first; the erase deletes it.
        runBlocking { SettingsShare.write(context) }
        assertEquals(1, f.parentFile!!.listFiles()!!.size)
        SettingsShare.wipe()
        assertFalse(f.exists())
    }

    @Test fun shareBuildsTheFileOffTheScreensThreadAndOpensTheShareSheetOnIt() {
        // The test thread is the screen's (Robolectric's main looper): [share] is started there, as the button does.
        MainWrites.testMainThread = Thread.currentThread()
        SettingsShare.builtOn = null
        var said: String? = null
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch {
            said = runCatching { SettingsShare.share(context) }.getOrElse { "failed: " + it.stackTraceToString().take(3_000) }
        }
        val until = System.currentTimeMillis() + 60_000
        while (!job.isCompleted && System.currentTimeMillis() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("the share finished", job.isCompleted)
        assertTrue(said, said!!.startsWith("Settings and state ready to share"))
        val built = SettingsShare.builtOn
        assertTrue("built", built != null)
        assertNotSame("built off the screen's thread", Thread.currentThread(), built)
        assertEquals("vault writes on the screen's thread", 0L, MainWrites.count())
        val chooser = org.robolectric.Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertEquals("text/plain", send.type)
        @Suppress("DEPRECATION") val uri = send.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)!!
        assertTrue(uri.toString(), uri.toString().endsWith(".txt"))
    }
}
