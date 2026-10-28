package com.optionslab.app.data

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.optionslab.app.security.SessionLock
import com.optionslab.app.security.Vault
import com.optionslab.app.testing.AreaE
import com.optionslab.app.ui.eraseEverything
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * "Erase everything" erases every store (26 Oct, erase audit). Many stores were missing from the erase: they kept their books in
 * memory and saved them again after it, sealed with a new key. Now every store is in [PersonalStores]; these tests make sure a new
 * store cannot be forgotten:
 *  - every main source file that saves anything (or touches the vault or the settings) is in one of the registry's lists;
 *  - every store in the registry has a seed here (a new store fails until it gets one);
 *  - after seeding every store and erasing, no store holds data in memory and no file is left, except the public market files
 *    in [PersonalStores.KEPT_FILES].
 */
@RunWith(AndroidJUnit4::class)
class PersonalStoresTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun up() { AreaE.resetGlobals(); SessionLock.unlock() }

    @After fun down() { PersonalStores.testExtra = emptyList(); Vault.resetEraseForTest() }

    // ---- every store is listed --------------------------------------------------------------------------------------------

    /** What marks a source file as one that saves something, or holds the vault's or the settings' values. */
    private val SAVES = Regex("Vault\\.|writeFileWritten|LastWrite\\(|SecureBlobs|SecurePrefs|\\.writeText\\(|\\.writeBytes\\(|" +
        "FileOutputStream\\(|appendText\\(|filesDir|noBackupFilesDir|cacheDir|getDir\\(|getSharedPreferences|openFileOutput|" +
        "bufferedWriter|RandomAccessFile|prefs\\(\\)\\.put")

    /** The main source folders of the app and of the two libraries it saves through (the test runs in android/app or android). */
    private fun sourceRoots(): List<File> = listOf("src/main/java", "../ira-core/src/main/kotlin", "../engine/src/main/kotlin",
        "app/src/main/java", "ira-core/src/main/kotlin", "engine/src/main/kotlin").map(::File).filter { it.isDirectory }

    private fun sources(): List<File> = sourceRoots().flatMap { r -> r.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList() }
        .distinctBy { it.canonicalPath }

    private fun listed(): Set<String> = PersonalStores.ALL.map { it.source }.toSet() + PersonalStores.PREFS_ONLY.keys + PersonalStores.NOT_PERSONAL.keys

    @Test fun everySourceThatSavesIsListed() {
        val all = sources()
        assertTrue("the main sources were found (${File(".").absolutePath})", all.size > 200)
        val missing = all.filter { SAVES.containsMatchIn(it.readText()) }.map { it.name.removeSuffix(".kt") }.filter { it !in listed() }.sorted()
        assertTrue("source files that save something, missing from PersonalStores (add each to ALL with a wipe, or to PREFS_ONLY / " +
            "NOT_PERSONAL with the reason): $missing", missing.isEmpty())
    }

    @Test fun theListsNameRealFilesOnceEach() {
        val names = sources().map { it.name.removeSuffix(".kt") }.toSet()
        val unknown = listed().filter { it !in names }.sorted()
        assertTrue("listed, but no such source file: $unknown", unknown.isEmpty())
        val stores = PersonalStores.ALL.map { it.source }.toSet()
        val twice = (stores intersect PersonalStores.PREFS_ONLY.keys) + (stores intersect PersonalStores.NOT_PERSONAL.keys) +
            (PersonalStores.PREFS_ONLY.keys intersect PersonalStores.NOT_PERSONAL.keys)
        assertTrue("in two lists: $twice", twice.isEmpty())
        for ((k, why) in PersonalStores.PREFS_ONLY + PersonalStores.NOT_PERSONAL + PersonalStores.KEPT_FILES) assertTrue("$k: a reason", why.length >= 12)
        // A kept file is never a vault file, a set-aside copy or a settings file: those hold personal data.
        for (k in PersonalStores.KEPT_FILES.keys) assertFalse(k, k.endsWith(".vault") || "unreadable" in k || k.startsWith("prefs"))
    }

    @Test fun everyStoreHasASeed() {
        val seeded = seeds().map { it.first }.toSet()
        val none = PersonalStores.ALL.map { it.source }.toSet().filter { it !in seeded && it !in UNSEEDED }.sorted()
        assertTrue("stores with no seed in this test (add one): $none", none.isEmpty())
        val stray = seeded.filter { s -> PersonalStores.ALL.none { it.source == s } }
        assertTrue("seeds for no store: $stray", stray.isEmpty())
    }

    /** Stores that hold nothing a unit test can put in them, and why (each still wiped by the erase). */
    private val UNSEEDED = mapOf(
        "IraAlgoApp" to "the crash report is written only by the crash handler (not installed in tests); its file is seeded directly below",
        "ShortAnswer" to "Jarvis's short-answer memo fills only while he answers",
        "BiometricGate" to "a Keystore key, made only with a fingerprint enrolled",
    )

    // ---- seed, erase, nothing left ----------------------------------------------------------------------------------------

    /** The erase's slow file thread ("erase-files"), if one is running, waited for. */
    private fun joinEraseFiles() {
        Thread.getAllStackTraces().keys.filter { it.name == "erase-files" }.forEach { it.join(30_000) }
    }

    /** The app's folders, each with a label, and every file under them (by path inside its folder). */
    private fun leftFiles(): List<String> {
        val data = app.filesDir.parentFile!!
        val roots = listOf(app.filesDir, app.noBackupFilesDir, app.cacheDir, File(data, "shared_prefs"), File(data, "databases")).distinctBy { it.path }
        return roots.filter { it.isDirectory }.flatMap { r ->
            r.walkTopDown().filter { it.isFile }.map { it.relativeTo(r).invariantSeparatorsPath }.filterNot(::kept).map { "${r.name}/$it" }.toList()
        }
    }

    /** Whether [rel] (a path inside one of the app's folders) is a kept public file ([PersonalStores.KEPT_FILES]). */
    private fun kept(rel: String): Boolean = PersonalStores.KEPT_FILES.keys.any { glob(it).matches(rel) || glob(it).matches(rel.substringAfterLast('/')) }

    private fun glob(g: String): Regex = Regex(g.split("**").joinToString(".*") { part -> part.split("*").joinToString("[^/]*") { Regex.escape(it) } })

    @Test fun theGlobsMatchAsWritten() {
        assertTrue(kept("orderflow/2026-10-26.csv.gz"))
        assertTrue(kept("orderflow/events/abc.csv.gz"))
        assertTrue(kept("history/2026-10-23/opt-NIFTY.ihv"))
        assertTrue(kept("holidays.json"))
        assertFalse(kept("orb.vault"))
        assertFalse(kept("orb.vault.unreadable.123"))
        assertFalse(kept("self/boss.vault"))
        assertFalse(kept("orderflowx/a.csv"))
        // 26 Oct, review: FlowGate's shadow log (the arms' trades) shares the recorder's folder, and is personal.
        assertFalse(kept("orderflow/shadow.log"))
        assertFalse(kept("orderflow/shadow.old.log"))
    }

    @Test fun eraseLeavesNoStoreHoldingDataAndNoFile() {
        // The crash report, which only the crash handler writes.
        Vault.writeFile(File(app.filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE), "java.lang.IllegalStateException at OrbArms".toByteArray())
        val failedSeeds = ArrayList<String>()
        for ((name, seed) in seeds()) runCatching(seed).onFailure { failedSeeds += "$name: $it" }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("seeds that failed: $failedSeeds", failedSeeds.isEmpty())
        // Each store took its seed: in memory, or on disk.
        val empty = PersonalStores.ALL.groupBy { it.source }.filter { (src, es) -> src !in UNSEEDED &&
            es.none { e -> runCatching { e.holds() }.getOrDefault(false) || runCatching { e.files().any { it.exists() } }.getOrDefault(false) } }.keys.sorted()
        assertTrue("stores that hold nothing after their seed (the seed or the store's holds/files is wrong): $empty", empty.isEmpty())
        assertTrue("the seeds left files to erase", leftFiles().isNotEmpty())

        eraseEverything()
        joinEraseFiles()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("wipes that threw: ${com.optionslab.app.ui.eraseFailures.value}", com.optionslab.app.ui.eraseFailures.value.isEmpty())
        val holding = PersonalStores.ALL.filter { e -> runCatching { e.holds() }.getOrDefault(true) }.map { it.source }
        assertTrue("stores still holding data in memory after the erase: $holding", holding.isEmpty())
        val files = PersonalStores.ALL.flatMap { e -> runCatching { e.files() }.getOrDefault(emptyList()).filter { it.exists() && !(it.isDirectory && it.walkTopDown().none { f -> f.isFile }) }.map { "${e.source}: ${it.name}" } }
        assertTrue("store files left after the erase: $files", files.isEmpty())
        val left = leftFiles()
        assertTrue("files left after the erase (none may hold anything personal; a public one goes in KEPT_FILES with why): $left", left.isEmpty())
        assertFalse(Vault.isErasing)
    }

    /** A wipe that throws never stops the others: each is guarded, and the one that threw is named. */
    @Test fun aWipeThatThrowsNeverStopsTheRest() {
        var after = false
        val f = File(app.filesDir, "extra.vault").apply { writeText("x") }
        PersonalStores.testExtra = listOf(
            PersonalStores.Entry("Thrower", files = { listOf(f) }) { throw IllegalStateException("a store not set up") },
            PersonalStores.Entry("After") { after = true },
        )
        eraseEverything()
        joinEraseFiles()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the store after the one that threw was wiped", after)
        assertFalse("the thrower's file was still deleted", f.exists())
        assertTrue("the wipe that threw is named", "Thrower" in com.optionslab.app.ui.eraseFailures.value)
        assertFalse("After" in com.optionslab.app.ui.eraseFailures.value)
        assertFalse(Vault.isErasing)
    }

    /** During an erase a file set aside (its read refused) is deleted, never renamed to outlive the erase. */
    @Test fun aFileSetAsideDuringAnEraseIsDeleted() {
        val f = File(app.filesDir, "aside.vault")
        Vault.writeFile(f, "Boss's book".toByteArray())
        Vault.beginErase()
        try { Vault.setAside(f) } finally { Vault.endErase() }
        assertFalse(f.exists())
        assertTrue(app.filesDir.listFiles().orEmpty().none { "unreadable" in it.name })
        // Outside an erase it is still renamed (kept for a look), as before.
        Vault.writeFile(f, "Boss's book".toByteArray())
        val aside = Vault.setAside(f)
        assertTrue(aside.exists() && !f.exists())
    }

    /** One seed per store: puts personal data in its memory and on disk through its own API, as the app does. */
    private fun s(name: String, block: () -> Unit): Pair<String, () -> Unit> = name to block

    private fun seeds(): List<Pair<String, () -> Unit>> = listOf(
        s("Ledger") {
            com.optionslab.app.data.Alarms.upsert(com.optionslab.app.data.PriceAlarm(1L, "NIFTY", above = true, level = 25_000.0, note = "Boss's alarm"))
            // A ledger ticket needs a whole Live.Ticket; its file written directly through the vault (the ledger keeps nothing in memory).
            com.optionslab.app.security.Vault.writeFile(java.io.File(app.noBackupFilesDir, "ledger.vault"), "[]".toByteArray(Charsets.UTF_8))
        },
        s("Broker") {
            com.optionslab.app.security.SecurePrefs.putAll(mapOf("kite.apiKey" to "abcd1234", "kite.userName" to "Boss Test", "kite.userId" to "AB1234"))
            com.optionslab.app.data.Broker.userName          // the session read into memory (credsKept)
            java.io.File(app.filesDir, "kite_instruments.json").writeText("{}")
            java.io.File(app.filesDir, "kite_futures.json").writeText("{}")
        },
        s("GrammarArm") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.GrammarArm.recordForTest("idea-1", listOf(120.0, -40.0), com.optionslab.app.data.Market.today()) }
        },
        s("OiWallShadowHost") {
            com.optionslab.app.data.OiWallShadowHost.init(app)        // TestApp does not init it (no file otherwise)
            com.optionslab.app.data.OiWallShadowHost.putForTest("NIFTY", com.optionslab.app.data.Market.today(), "test day")
        },
        s("Diag") {
            com.optionslab.app.data.Diag.record("test", "Boss bought NIFTY 25000 CE")
            com.optionslab.app.data.Diag.flush()
        },
        s("ExitCoordinator") {
            com.optionslab.app.data.ExitCoordinator.resetForTest()
            com.optionslab.app.data.ExitCoordinator.setOn(true)
        },
        s("HiddenArms") {
            com.optionslab.app.data.HiddenArms.resetForTest(app)       // its file under this test's folder, the default arms, no worker
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.HiddenArms.hideAgain(com.optionslab.engine.orb.ArmsCleanup.ORB) }
        },
        s("OrderTiming") {
            com.optionslab.app.data.OrderTiming.marginCheck(150L)
        },
        s("GoldTasPaper") {
            com.optionslab.app.data.GoldTasPaper.init(app)
            val t = java.time.LocalDateTime.of(2026, 10, 20, 10, 0)
            kotlinx.coroutines.runBlocking {
                com.optionslab.app.data.GoldTasPaper.replaceForTest(com.optionslab.app.data.GoldTasPaper.Book(armed = true,
                    trades = listOf(com.optionslab.app.data.GoldPaper.Trade(2400.0, 2410.0, t, t.plusHours(1), 0.01, "tas_down", 10.0))))
            }
        },
        s("Sweeper") {
            com.optionslab.app.data.Sweeper.heldForTest(listOf(com.optionslab.engine.risk.PriceWatch.Held("NFO:NIFTY26OCT25000CE", "NFO")))
        },
        s("DayLockGuard") {
            // Over any lock amount: the lock day written to the settings when the lock is on; the kept copy warmed either way.
            com.optionslab.app.data.DayLockGuard.update(app, paper = 1_000_000.0, live = null)
        },
        s("PaperOnly") {
            com.optionslab.app.data.PaperOnly.set(com.optionslab.app.data.Market.today().plusDays(3), "test period", pinConfirmed = true)
        },
        s("BotReplayStore") {
            com.optionslab.app.ira.SelfJobs.init(app)                  // the replay folder lives under SelfJobs' folder
            val d = com.optionslab.app.ira.BotReplayStore.filesForErase().first()
            d.mkdirs(); java.io.File(d, "trade-1.txt").writeText("1760000000000,100.0,101.0,99.0,100.5")
            com.optionslab.app.ira.BotReplayStore.show("trade-1")
        },
        s("IraCoach") {
            com.optionslab.app.ira.IraCoach.failedTrailForTest(1L, 101.5)
        },
        s("IraSolo") {
            com.optionslab.app.ira.IraSolo.on = true                   // the settings key and the card's switch (onState)
        },
        s("History") {
            com.optionslab.app.security.Vault.writeFile(java.io.File(app.filesDir, "equity_keys.vault"),
                "{\"NSE:RELIANCE\":\"NSE_EQ|INE002A01018\"}".toByteArray(Charsets.UTF_8))
        },
        s("BossTradeLog") {
            com.optionslab.app.ira.BossTradeLog.init(app)
            com.optionslab.app.ira.BossTradeLog.setForTest(listOf(com.optionslab.ira.BossTrades.Trade("paper:1", "NIFTY26OCT25000CE", live = false, openedMs = System.currentTimeMillis(), note = "boss note")))
            com.optionslab.app.ira.BossTradeLog.drain()   // saves noBackupFilesDir/self/boss.vault on this thread
        },
        s("LiquidityNotices") {
            com.optionslab.app.work.LiquidityNotices.init(app)
            com.optionslab.app.work.LiquidityNotices.once(app, java.time.LocalDate.of(2026, 10, 26), "liquidity:BANKNIFTY|entry")
            com.optionslab.app.work.LiquidityNotices.entryId(app, java.time.LocalDate.of(2026, 10, 26), "liquidity:BANKNIFTY")
            // apply() writes in the background: a commit puts the whole file on disk now.
            app.getSharedPreferences("liquidity.notices", android.content.Context.MODE_PRIVATE).edit().putBoolean("seeded", true).commit()
        },
        s("MainActivity") {
            com.optionslab.app.MainActivity.nonce()   // the intent nonce: a SecurePrefs key
            com.optionslab.app.MainActivity.cardRequests.value = com.optionslab.app.work.NoticeCard(1, "buy", "BUY NIFTY 25000 CE", "Bought 75 at 120, P&L +900", System.currentTimeMillis())
            com.optionslab.app.MainActivity.closeRequests.value = "NIFTY26OCT25000CE"
        },
        // Two seeds share the source "TradeBook" (if the test keys seeds by source, run both in one snippet).
        s("TradeBook") {
            com.optionslab.app.data.TradeBook.recordLive(listOf(com.optionslab.app.data.Broker.Trade("T1", "O1", "NIFTY26OCT25000CE", "NFO", "BUY", 75, 120.5, "NRML", "2026-10-26 10:15:00")))
            com.optionslab.app.data.Journal.put("kite:T1", "boss's note", setOf("Breakout"))
        },
        s("Protections") {
            // A paper stop needs a known paper contract; the list is written as protectPaper writes it, then read into memory.
            com.optionslab.app.security.Vault.writeFile(java.io.File(app.noBackupFilesDir, "protections.vault"),
                """[{"id":1,"live":false,"symbol":"NIFTY26OCT25000CE","exchange":"NFO","product":"NRML","qty":75,"lot":75,"tick":0.05,"stop":90.0,"trail":null,"target":null,"best":100.0,"stopId":"P1","targetId":"","active":true,"note":"","gtt":0}]""".toByteArray(Charsets.UTF_8))
            check(kotlinx.coroutines.runBlocking { com.optionslab.app.data.Protections.active() }.size == 1)
        },
        s("ArmLive") {
            // Boss's "Trade live" off for the first arm: the book (switches + log) saved to arm_live.vault and the switches' second copy in SecurePrefs.
            val said = kotlinx.coroutines.runBlocking { com.optionslab.app.data.ArmLive.setLive(com.optionslab.engine.orb.LiveMirror.ARMS.first().key, on = false, pinConfirmed = false, log = false) }
            check(com.optionslab.app.data.ArmLive.done(said)) { said }
        },
        s("CandleAgentHost") {
            com.optionslab.app.data.CandleAgentHost.init(app)
            val a = com.optionslab.app.data.CandleAgentHost.agentOrLoad(app)   // in memory (the research seed)
            // Its counts file, as its batch writes it (the batch itself needs market hours and candles).
            java.io.File(app.noBackupFilesDir, "candles/stats.txt").apply { parentFile?.mkdirs(); writeText(a.encodeStats()) }
        },
        s("OrderReviewHost") {
            // The review's own file as its pass writes it (the pass needs a day's orders), then read into memory as at a start.
            java.io.File(app.noBackupFilesDir, "orderreview/review.txt").apply { parentFile?.mkdirs()
                writeText(com.optionslab.ira.OrderReview.encode(com.optionslab.ira.OrderReview.Book(proposals = listOf("Boss: stop moved too early on NIFTY"), lastRun = java.time.LocalDate.of(2026, 10, 23), cutoffMs = 1L))) }
            com.optionslab.app.data.OrderReviewHost.reloadForTest()
            com.optionslab.app.data.OrderReviewHost.ensureLoaded()
            check(com.optionslab.app.data.OrderReviewHost.holdsForErase())
        },
        s("LiveSelfTest") {
            com.optionslab.app.data.LiveSelfTest.setAuto(true)    // its switch: a SecurePrefs key
            // One run on TestApp's fake sources (a closed market): its steps (the account's reads, failing offline) kept in memory.
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.LiveSelfTest.run() }
            check(com.optionslab.app.data.LiveSelfTest.holdsForErase())
        },
        s("OpenWidget") {
            // Its figures need a widget placed on a home screen: held through its seam, and its vault copy written as its store does.
            com.optionslab.app.widget.OpenWidget.holdForTest("PAPER|1250.5|NIFTY26OCT25000CE 75")
            com.optionslab.app.security.SecurePrefs.put("ow.paper", "PAPER|1250.5|NIFTY26OCT25000CE 75")
        },
        s("JarvisLearning") {
            com.optionslab.app.ira.JarvisLearning.init(app)
            // His lessons' file as the nightly pass writes it (the pass needs a trading day's records), then read into memory.
            java.io.File(app.noBackupFilesDir, "jarvis/lessons.txt").apply { parentFile?.mkdirs()
                writeText(com.optionslab.ira.AgentLessons.encode(com.optionslab.ira.AgentLessons.Book(regimes = mapOf(java.time.LocalDate.of(2026, 10, 23) to "trend"),
                    lastPass = java.time.LocalDate.of(2026, 10, 23)))) }
            com.optionslab.app.ira.JarvisLearning.reloadForTest()
            com.optionslab.app.ira.JarvisLearning.ensureLoaded()
            check(com.optionslab.app.ira.JarvisLearning.holdsForErase())
        },
        s("IraHub") {
            // TestApp has inited it and read its (empty) memory. A note in the chat, saved as the keeper saves it 300 ms later.
            com.optionslab.app.ira.IraHub.note("Boss, your NIFTY 25000 CE paper trade closed at +900.")
            com.optionslab.app.ira.IraHub.saveStateForTest()
            check(java.io.File(app.noBackupFilesDir, "ira-state.vault").exists())
        },
        s("OrbArms") {
            // Paper (TestApp): Boss's own switch-on of Liquidity 15+5; the book is written on the caller's thread.
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.OrbArms.setArmed("liquidity", true, automatic = false) }
        },
        s("ShadowArms") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.ShadowArms.asked("V43") }
        },
        s("Strategies") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.Strategies.tagOwner("paper:seed-1", "Seed strategy · entry") }
            com.optionslab.app.data.Strategies.awaitWrites()
        },
        s("Autopilot") {
            com.optionslab.app.ira.Autopilot.init(app)
            com.optionslab.app.ira.Autopilot.setForTest(com.optionslab.ira.AutoChange.record(com.optionslab.ira.AutoChange.Book(),
                com.optionslab.ira.AutoChange.passedLock(true), com.optionslab.ira.AutoChange.Status.KEPT, System.currentTimeMillis(),
                java.time.LocalDate.of(2026, 10, 26)).first)
            com.optionslab.app.ira.Autopilot.saveNow()      // noBackupFilesDir/autopilot/log.txt and knobs.txt
        },
        s("IraBots") {
            com.optionslab.app.security.SecurePrefs.put("jarvis.bots.switches",
                """[{"d":"2026-10-23","k":["ORB"],"a":["ORB"],"l":["ORB"],"w":[]}]""")
            com.optionslab.app.ira.IraBots.armLog()           // the record read into memory
        },
        s("ManagerSpecialists") {
            com.optionslab.app.data.ManagerSpecialists.reloadForTest(app)     // context known, file read (none)
            com.optionslab.app.data.ManagerSpecialists.mute("orb", com.optionslab.ira.ManagerTeam.IDS.first())
            com.optionslab.app.data.ManagerSpecialists.save()                 // noBackupFilesDir/trademanager/specialists.json
        },
        s("McxNr7Record") {
            com.optionslab.app.data.McxNr7Record.init(app)
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.McxNr7Record.setOn(true) }   // noBackupFilesDir/mcx_nr7_record.vault
        },
        s("MarketContext") {
            com.optionslab.app.data.MarketContext.setShadowDaysForTest(setOf("2026-10-20"))
            com.optionslab.app.data.MarketContext.setMode(com.optionslab.ira.EventShield.Mode.SHADOW)
            com.optionslab.app.data.MarketContext.setTodayForTest(java.time.LocalDate.of(2026, 10, 26), listOf("Boss's own event"))
            // setMode's save is on a background coroutine: waited for here (26 Oct, review: a key written directly left that
            // coroutine free to run after the erase and write the key back - a settings file left), then put on disk.
            val end = System.currentTimeMillis() + 5_000
            while (runCatching { com.optionslab.app.security.SecurePrefs.getString("context.eventPause") }.getOrNull() != "SHADOW" &&
                System.currentTimeMillis() < end) Thread.sleep(20)
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("IraStudy") {
            com.optionslab.app.ira.IraStudy.overnight(java.time.Instant.now(), "RBI kept the repo rate")   // SecurePrefs jarvis.study
        },
        s("IraWidget") {
            com.optionslab.app.widget.IraWidget.publish(app, 25000.0 to 0.4, 52000.0 to -0.2, 1234.5)
            com.optionslab.app.widget.IraWidget.charges(app, 42.5, exact = true)
        },
        s("GoldTrendPaper") {
            com.optionslab.app.data.GoldTrendPaper.init(app)
            val t = com.optionslab.app.data.GoldPaper.Trade(2400.0, 2410.0, java.time.LocalDateTime.of(2026, 10, 23, 8, 0),
                java.time.LocalDateTime.of(2026, 10, 23, 12, 0), 0.01, "seed", 10.0)
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.GoldTrendPaper.replaceForTest(com.optionslab.app.data.GoldTrendPaper.Book(armed = true, trades = listOf(t))) }
        },
        s("GoldDipPaper") {
            com.optionslab.app.data.GoldDipPaper.init(app)
            val t = com.optionslab.app.data.GoldPaper.Trade(2400.0, 2410.0, java.time.LocalDateTime.of(2026, 10, 23, 8, 0),
                java.time.LocalDateTime.of(2026, 10, 23, 12, 0), 0.01, "seed", 10.0)
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.GoldDipPaper.replaceForTest(com.optionslab.app.data.GoldDipPaper.Book(armed = true, trades = listOf(t))) }
        },
        s("ProblemsHost") {
            com.optionslab.app.data.ProblemsHost.init(app)
            com.optionslab.app.data.ProblemsHost.ensureLoaded()
            com.optionslab.app.data.ProblemsHost.report("seed:problem", "Seed agent", com.optionslab.ira.OpenProblems.Severity.WARN, "A seeded problem")
            com.optionslab.app.data.ProblemsHost.saveNow()      // noBackupFilesDir/problems/board.txt
        },
        s("RegimeHost") {
            // No public API makes an override without 3 shadow days of the brain: the book set through ira-core's own [use], and its key.
            com.optionslab.ira.RegimeParams.use(com.optionslab.ira.RegimeParams.Book(day = com.optionslab.ira.RegimeParams.Day(
                java.time.LocalDate.of(2026, 10, 26), com.optionslab.ira.RegimeParams.Regime.BUSY, false, false, 585)))
            com.optionslab.app.security.SecurePrefs.putAll(mapOf("regime.book" to com.optionslab.ira.RegimeParams.encode(com.optionslab.ira.RegimeParams.book)))
        },
        s("GammaLive") {
            com.optionslab.app.data.GammaLive.setConvention(com.optionslab.ira.GammaRegime.Convention.INDIA_SELLERS)
        },
        s("GitHubSync") {
            // No upload can run offline: the folder a run leaves behind, written as the run does (sealed bytes; content irrelevant).
            com.optionslab.app.data.GitHubSync.init(app)
            java.io.File(app.noBackupFilesDir, "github-out").apply { mkdirs() }.resolve("live-2026-10-23.zip").writeBytes(byteArrayOf(1, 2, 3))
        },
        // GitHubSync has no memory: assert only its files are gone (holds is the default false).
        s("JarvisVoice") {
            com.optionslab.app.ira.JarvisVoice.paceGaps = listOf(420L, 510L, 380L)     // memory + settings key jarvis.voice.pausegaps
            com.optionslab.app.ira.JarvisVoice.note("seed turn")                        // the turns' trace (memory)
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("AgentLessons") {
            com.optionslab.ira.AgentLessons.asked("what have you learned today")      // memory only (no file)
        },
        s("McxPaperArms") {
            val arm = com.optionslab.app.data.McxPaperArms.ARMS.first { com.optionslab.app.data.HiddenArms.mayArm(it) }
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.McxPaperArms.setArmed(arm, true) }   // book in memory + mcx_arms.vault
        },
        s("Ft3Arm") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.Ft3Arm.setArmed(true, log = false) }   // book in memory + ft3.vault
        },
        s("VariantRunner") {
            com.optionslab.app.data.VariantRunner.init(app)   // its folder needs the context (TestApp does not init it)
            val v = com.optionslab.ira.VariantLab.Book(tried = 3)
            com.optionslab.app.data.VariantRunner.setForTest(v, com.optionslab.ira.StrategyForge.Book(tried = 2))
            java.io.File(app.noBackupFilesDir, "strategylab").apply { mkdirs() }.resolve("variants.txt").writeText(com.optionslab.ira.VariantLab.encode(v))
        },
        s("BossRiskHost") {
            com.optionslab.app.data.BossRiskHost.setPaperDaysForTest(listOf(com.optionslab.ira.AccountStop.Day(java.time.LocalDate.of(2026, 10, 20), -5000.0)))
            com.optionslab.app.data.BossRiskHost.setLive(com.optionslab.app.data.BossRiskHost.Rule.WEEKLY, true)   // a settings key
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("LiveRecorder") {
            com.optionslab.app.data.LiveRecorder.attachForTest(app)       // its folder, without the writer loops
            com.optionslab.app.data.LiveRecorder.fill("NIFTY26OCT25000CE", "BUY", false, 75, 120.5, 120.0, true, "seed")
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.LiveRecorder.drain() }   // recordings/<day>/trades file written
            com.optionslab.app.data.LiveRecorder.fill("NIFTY26OCT25000CE", "SELL", true, 75, 130.0, 130.0, true, "seed")   // one queued (memory)
        },
        s("Market") {
            java.io.File(app.filesDir, "contracts.json").writeText("{\"day\":\"2026-10-26\",\"c\":[]}")   // public data; file only, holds stays false
        },
        s("NtfyReader") {
            com.optionslab.app.data.NtfyReader.setTopic("seed-topic-q8w7e6r5t4y3u2i1")   // settings key ntfy.topic
            com.optionslab.app.security.SecurePrefs.flush()
            com.optionslab.app.data.NtfyReader.armedHint = true          // memory (setter made internal for this seed)
        },
        s("TimeMachineHost") {
            com.optionslab.app.data.TimeMachineHost.check(com.optionslab.app.data.TimeMachineHost.Entry(arm = "orb", label = "ORB", ref = "seed-1",
                underlying = "NIFTY", direction = 1, at = java.time.LocalDateTime.of(2026, 10, 23, 10, 0), stopPoints = 20.0, targetPoints = 40.0,
                exitAt = null))                                   // synchronous: memory + noBackupFilesDir/self/timemachine.vault
        },
        s("SelfGrowth") {
            val w = com.optionslab.ira.GrowthReport.Week(java.time.LocalDate.of(2026, 10, 19), learned = 3, effectRs = 1200.0, effectN = 4)
            com.optionslab.app.ira.SelfGrowth.setForTest(w = listOf(w))
            java.io.File(app.noBackupFilesDir, "growth").apply { mkdirs() }.resolve("weeks.txt").writeText("M||\n" + com.optionslab.ira.GrowthReport.encode(w) + "\n")
        },
        s("SecureBlobs") {
            com.optionslab.app.security.SecurePrefs.putAllSoon(mapOf("jarvis.notes" to "seed note: buy NIFTY on dips"))
            com.optionslab.app.security.SecurePrefs.flush()      // noBackupFilesDir/prefs.blobs/jarvis.notes.vault
        },
        s("PayoutWatch") {
            com.optionslab.app.data.PayoutWatch.resetForTest()   // TestApp does not reset it; loaded = true
            com.optionslab.app.data.PayoutWatch.keepForTest(java.time.LocalDate.of(2026, 10, 20), 5000.0, 100000.0)
            com.optionslab.app.data.PayoutWatch.keepBalanceForTest(java.time.LocalDate.of(2026, 10, 20), 100000.0, 95000.0)
            com.optionslab.app.data.PayoutWatch.setOn(true)      // settings key g.payoutDrawdown.on
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("LossBreaker") {
            com.optionslab.app.data.LossBreaker.noteBossStop(true)   // settings key breaker.bossstop + the kept copy warmed
        },
        s("IraNotes") {
            com.optionslab.app.ira.IraNotes.add("Liquidity 15+5 bought NIFTY 25000 CE on paper")
            com.optionslab.app.ira.IraNotes.seen()                 // settings key (CatchUp.KEY_SEEN) + memory
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("EventDays") {
            com.optionslab.app.data.EventDays.remove(java.time.LocalDate.of(2026, 11, 26))   // settings key events.removed + calendar built
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("FlowArm") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.FlowArm.setArmed(true, log = false) }   // book in memory + flow_arm.vault
        },
        s("NtfyArm") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.NtfyArm.setSettings(com.optionslab.engine.signals.NtfyRules.Settings(lots = 2)) }
        },
        s("TradeManagerHost") {
            val t0 = System.currentTimeMillis()
            val t = com.optionslab.ira.TradeManager.ManagedTrade("pine:1", "seed-1", "Pine · Level", "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
                com.optionslab.ira.TradeManager.Caps(t0 + 3 * 3_600_000L), com.optionslab.ira.TradeManager.Account.PAPER, t0, 1.0)
            com.optionslab.app.data.TradeManagerHost.recordsForTest(listOf(com.optionslab.ira.TradeManager.Record(t, com.optionslab.ira.TradeManager.Mode.ACT,
                actual = com.optionslab.ira.TradeManager.Exit(t0, 110.0, "x"))))
            com.optionslab.app.data.TradeManagerHost.save()      // loaded = true from TestApp's resetForTest; writes trademanager/records.json now
        },
        s("TrendWatchHost") {
            com.optionslab.app.data.TrendWatchHost.setOthers(true)   // reads the (absent) log, logs the Hero switch-on once, writes trend_watch.vault
        },
        s("NightArm") {
            com.optionslab.app.data.NightArm.init(app)            // not in TestApp
            kotlinx.coroutines.runBlocking {
                com.optionslab.app.data.NightArm.seedForTest(com.optionslab.app.data.NightArm.Pos("NIFTY", "NIFTY25OCT25000CE", "CE", 75, 100.0,
                    java.time.LocalDate.of(2026, 10, 23), java.time.LocalDateTime.of(2026, 10, 23, 15, 20), "seed-1",
                    exit = 110.0, exitTime = java.time.LocalDateTime.of(2026, 10, 26, 9, 16), why = "09:16"))
            }
        },
        s("GoldPaper") {
            com.optionslab.app.data.GoldPaper.init(app)           // not in TestApp (GoldBooks' gate is already open when it never ran)
            val at = java.time.LocalDateTime.of(2026, 10, 23, 10, 0)
            kotlinx.coroutines.runBlocking {
                com.optionslab.app.data.GoldPaper.replaceForTest(com.optionslab.app.data.GoldPaper.Book(armed = true,
                    trades = listOf(com.optionslab.app.data.GoldPaper.Trade(2400.0, 2410.0, at, at.plusHours(2), 0.01, "target", 10.0))))
            }
        },
        s("WatchlistHost") {
            com.optionslab.app.data.WatchlistHost.setOn(false)    // writes watchlist.vault; the switch is held in memory
        },
        s("AppHealth") {
            com.optionslab.app.data.AppHealth.overrun("seed-worker")
            com.optionslab.app.data.AppHealth.saveNow()           // the coalesced save waits 60 s: written now
        },
        s("ExposureCapHost") {
            com.optionslab.app.data.ExposureCapHost.setMax(3)
            com.optionslab.app.data.ExposureCapHost.refused("ORB", "NIFTY25OCT25000CE", 1, 100.0, live = false, applied = true, why = "seed")
            // disk: settings keys only (putAllSoon / putAllLazy), wiped with SecurePrefs; files() is empty
        },
        s("PortfolioHost") {
            com.optionslab.app.data.PortfolioHost.setCapPct(3.0)  // the guard's config in memory, the setting written soon
        },
        s("StaticIp") {
            com.optionslab.app.data.StaticIp.registered = "203.0.113.7"
            check(com.optionslab.app.data.StaticIp.registered == "203.0.113.7")   // the read keeps it in memory
        },
        s("IraDayJournal") {
            com.optionslab.app.ira.IraDayJournal.shown(listOf("Seed reminder"))      // a settings key
            com.optionslab.app.ira.IraDayJournal.startForTest(false, com.optionslab.ira.DayJournal.Draft(com.optionslab.app.data.Market.today(), 1,
                listOf("Bought NIFTY 25000 CE"), "One trade today.", listOf(com.optionslab.ira.DayJournal.Question("why", "Why did you buy it?"))))
        },
        s("VoiceGuard") {
            com.optionslab.app.security.SecurePrefs.put("jarvis.voiceprint",
                com.optionslab.ira.VoicePrint.Print(doubleArrayOf(1.0, 2.0), doubleArrayOf(0.5, 0.5), 3.0).save())
            check(com.optionslab.app.ira.VoiceGuard.enrolled)     // the read keeps the print in memory
        },
        s("SelfJobs") {
            com.optionslab.app.ira.Autopilot.init(app); com.optionslab.app.ira.SelfJobs.init(app)   // as SelfRoadmapTest; sets its folder
            com.optionslab.app.ira.SelfJobs.keepLessonForTest(com.optionslab.ira.DailyLesson.Lesson(com.optionslab.app.data.Market.today(),
                com.optionslab.ira.DailyLesson.Kind.BOT, "ORB gave back Rs 500", "Seed lesson."))   // memory + self/lesson.txt (sealed)
        },
        s("IraModel") {
            // A unit test has no model library: this pretends the model is loaded (handle != 0). The wipe's free() then throws
            // UnsatisfiedLinkError, which unloadLocked already catches, and the handle goes to 0. No file to seed (the model file stays).
            com.optionslab.app.ira.IraModel.pretendLoadedForTest()
        },
        // `SecurePrefs.flush()` waits for `putAllSoon` writes. A `put` on the main (test) thread may be deferred, which is why the
        // snippets flush after it.
        s("Jobs") {
            com.optionslab.app.work.Tasks.TicketNotices.add("2026-10-26|NIFTY|stop-near")
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("Paper") {
            com.optionslab.app.data.Paper.reset(java.math.BigDecimal("250000"))   // writeInlineForTest: on disk at once
            com.optionslab.app.data.Paper.awaitWrites()
        },
        s("HeroTicketArm") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.HeroTicketArm.setArmed(true) }   // writes hero_ticket.vault
        },
        s("IraNewsTrades") {
            com.optionslab.app.ira.IraNewsTrades.save(listOf(com.optionslab.app.ira.IraNewsTrades.Pos(
                "NIFTY26OCT25000CE", false, 100.0, 65, 110.0, 70.0, "news: seed", "2026-10-26")))
            com.optionslab.app.ira.IraNewsTrades.all()          // fills lastRead (memory)
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("McxMarket") {
            com.optionslab.app.data.McxMarket.notePaperExposure(true)   // mcx_watch.hint written at once
            com.optionslab.app.data.McxMarket.markLive()                // liveSeen + mcx.liveHeld key
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("FlowGate") {
            val t = com.optionslab.app.data.FlowGate.check("orb", "BANKNIFTY", 1, live = false, at = "2026-10-26T10:01", what = "CE")
            com.optionslab.app.data.FlowGate.taken(t, "P-1")
            com.optionslab.app.data.FlowGate.flush()                    // orderflow/shadow.log
            com.optionslab.app.data.FlowGate.set("orb", com.optionslab.ira.OrderFlow.Mode.OFF)
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("PineScripts") {
            com.optionslab.app.data.PineScripts.init(app)               // TestApp does not start it
            com.optionslab.app.data.PineScripts.wipe()                  // its loader thread then finds it loaded
            com.optionslab.app.data.PineScripts.put(com.optionslab.app.data.PineScripts.Item(0, "Boss's script",
                "//@version=5\nstrategy(\"Boss\", overlay = true)\n"))
        },
        s("LiquidityTest") {
            com.optionslab.app.data.LiquidityTest.setWatched(true)      // background write of liquidity_test.txt
            val end = System.currentTimeMillis() + 5_000
            while (com.optionslab.app.data.LiquidityTest.stateWrittenForTest() == null && System.currentTimeMillis() < end) Thread.sleep(20)
        },
        s("Relay") {
            com.optionslab.app.data.Relay.host = "203.0.113.5"
            com.optionslab.app.data.Relay.enabled = true
            check(com.optionslab.app.data.Relay.enabled)                // re-read: the kept switch (memory)
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("IraWeekly") {
            val f = com.optionslab.app.ira.IraWeekly.file()!!
            val r = com.optionslab.ira.WeeklyReview.Review(java.time.LocalDate.of(2026, 10, 19), java.time.LocalDate.of(2026, 10, 23),
                java.time.LocalDateTime.of(2026, 10, 23, 18, 0), "Net +Rs 1,200 on 4 trades", "plain", "watch", emptyList())
            com.optionslab.app.security.Vault.writeFile(f, com.optionslab.ira.WeeklyReview.encodeAll(listOf(r)).toByteArray(Charsets.UTF_8))
            kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraWeekly.refresh() }   // state (memory)
        },
        s("FillSlippage") {
            com.optionslab.app.data.FillSlippage.paper("NIFTY26OCT25000CE", "BUY", true, 65, 100.0, 100.5, limit = false)
            val end = System.currentTimeMillis() + 5_000
            while (com.optionslab.app.data.FillSlippage.fileTextForTest().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        },
        s("AccountStopHost") {
            com.optionslab.app.data.AccountStopHost.setOn(true)
            com.optionslab.app.data.AccountStopHost.dayRead(-5_000.0)   // the pinned clock is noon on a trading day
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("ExpirySquareOff") {
            com.optionslab.app.data.ExpirySquareOff.noteToldForTest("paper NIFTY26OCT25000CE")
            com.optionslab.app.security.SecurePrefs.put("sq.expiry.done", "2026-10-26")
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("DayOco") {
            com.optionslab.app.data.DayOco.resetForTest()
            com.optionslab.app.data.DayOco.setOn(true, pinConfirmed = true)
            com.optionslab.app.data.DayOco.note(com.optionslab.ira.DayGttTally.Event.PLACED)
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("HealthReport") {
            com.optionslab.app.data.HealthReport.write(app)             // cache/exports/health/*.zip (holds() stays false: files only)
        },
        s("GlanceSource") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.GlanceSource.read() }   // last card (memory)
            com.optionslab.app.data.GlanceSource.setExpanded(false)
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("PassedArmsHost") {
            // TestApp: init done, writeInlineForTest = true, testNoAutoOn = true. book() saves its migrations; the switch saves again.
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.PassedArmsHost.setArmed(com.optionslab.app.data.PassedArmsHost.SOURCES.first(), true, log = false) }
            com.optionslab.app.data.PassedArmsHost.awaitWrites()
        },
        s("PineAuto") {
            // No Pine script is set up in a unit test: resetPaper() reads the (empty) book and saves it, so its file exists (holds() may
            // stay false: an empty book holds nothing personal; the test's "or its files exist" applies).
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.PineAuto.resetPaper() }
        },
        s("SmartWorkers") {
            com.optionslab.app.data.SmartWorkers.found("self-review", com.optionslab.ira.Findings.Kind.DRIFT, com.optionslab.ira.Findings.ALL,
                words = "Liquidity (paper) drifting from its backtest")
            // The log is written by the brain's 30-second flush (private): written here as it would be.
            java.io.File(java.io.File(app.noBackupFilesDir, "findings"), "${java.time.LocalDate.now()}.log").apply { parentFile!!.mkdirs(); writeText("self-review|DRIFT|*|0\n") }
        },
        s("MarketRecorder") {
            com.optionslab.app.data.MarketRecorder.append(java.time.LocalDate.now(), listOf("10:00:00|test|a recorded line"))
            com.optionslab.app.data.MarketRecorder.status()     // the card's status, held in memory
        },
        s("SecurePrefs") {
            com.optionslab.app.security.SecurePrefs.put("jarvis.notes", "[{\"t\":\"2026-10-26T10:00\",\"n\":\"bought on the break\"}]")
            com.optionslab.app.security.SecurePrefs.flush()
        },
        s("VixDivArm") {
            // TestApp: init done. Writes vix_div.vault (armed, a log line) and holds the book.
            kotlinx.coroutines.runBlocking { com.optionslab.app.data.VixDivArm.setArmed(true) }
        },
        s("SharedKnowledgeHost") {
            com.optionslab.app.data.SharedKnowledgeHost.attachForTest(app)
            com.optionslab.app.data.SharedKnowledgeHost.read("Jarvis", "the lesson on BANKNIFTY expiry days")
            com.optionslab.app.data.SharedKnowledgeHost.shadowOn = true
            // Tonight's lessons bundle (recordings/<today>/lessons_bundle.json; money sealed with the vault).
            com.optionslab.app.data.SharedKnowledgeHost.writeBundle(java.time.LocalDate.now(), com.optionslab.ira.SharedKnowledge.EMPTY, emptyList())
        },
        s("MoveRecorder") {
            com.optionslab.app.data.MoveRecorder.on = false     // Boss's switch: memory + a settings key (no event file)
        },
        s("IraJournal") {
            com.optionslab.app.ira.IraJournal.seenForTest("P:NIFTY26OCT25000CE")
            com.optionslab.app.ira.IraJournal.note("bought on the breakout")    // jarvis.notes (settings) + an activity line
        },
        s("Store") {
            // Slow entry. Files only (the backtest cache needs the bundled sessions; holds() stays false).
            com.optionslab.app.data.Store.markHarvested("NIFTY", java.time.LocalDate.now(), listOf("NSE_FO|12345"))
        },
        s("LearningFreeze") {
            com.optionslab.app.ira.LearningFreeze.init(app)
            com.optionslab.app.ira.LearningFreeze.testLive = false
            com.optionslab.app.ira.LearningFreeze.setForTest(com.optionslab.ira.FreezeRule.State(frozen = true,
                accounts = setOf(com.optionslab.ira.FreezeRule.Account.PAPER), on = java.time.LocalDate.now(), why = "paper fell 9,000 since the agents' changes"))
            // Unfreezing saves the state (self/freeze.txt); the state after it (unfrozen today) is still held in memory.
            com.optionslab.app.ira.LearningFreeze.unfreeze(pinConfirmed = false)
        },
        s("JarvisSpeaker") {
            // Under Robolectric aloud() only keeps the words (no speech engine); set directly in case the build has no Jarvis.
            com.optionslab.app.ira.JarvisSpeaker.aloud(app, "Boss, your day is up 5,000 on paper", flush = true)
            com.optionslab.app.ira.JarvisSpeaker.lastAloud = "Boss, your day is up 5,000 on paper"
            com.optionslab.app.ira.JarvisSpeaker.speakTyped = false
        },
        s("IraExpert") {
            kotlinx.coroutines.runBlocking { com.optionslab.app.ira.IraExpert.verdicts() }   // judged from the paper trips, kept in memory
            com.optionslab.app.security.SecurePrefs.put("jarvis.expert.told", "Liquidity:PASSED")
        },
        s("KiteStream") {
            // An order update as Zerodha pushes it on the stream (kept by order id in memory).
            com.optionslab.app.data.KiteStream.textForTest("""{"type":"order","data":{"order_id":"O1","status":"COMPLETE","average_price":120.5,"filled_quantity":75,"tradingsymbol":"NIFTY26OCT25000CE"}}""")
            check(com.optionslab.app.data.KiteStream.holdsForErase())
        },
        s("IraAccount") {
            // The account's lines as a quick answer keeps them, and the paper round trips named (an empty list is still a kept result).
            com.optionslab.app.ira.IraAccount.keepForTest(com.optionslab.ira.AppView("PAPER", mapOf(com.optionslab.ira.Section.PNL to listOf("Today's P&L: +₹900 on NIFTY 25000 CE"))))
            com.optionslab.app.ira.IraAccount.trips(false, emptyMap())
            check(com.optionslab.app.ira.IraAccount.holdsForErase())
        },
        s("IraThinking") {
            // With Jarvis, add() keeps the step and writes its key behind (putAllSoon) to SecurePrefs. 26 Oct, review: CI runs the
            // tests with -PtestsWithoutJarvis=true, where add() keeps nothing: the step is then held through the test seam.
            val step = com.optionslab.ira.Thinking.Step(com.optionslab.app.ira.IraThinking.now(), com.optionslab.ira.Thinking.Kind.PAPER_TOOK,
                "my Nifty call idea", listOf(com.optionslab.ira.Thinking.Fact("confidence 72%")))
            com.optionslab.app.ira.IraThinking.add(step)
            if (!com.optionslab.app.ira.IraThinking.holdsForErase()) com.optionslab.app.ira.IraThinking.keepForTest(listOf(step))
            com.optionslab.app.security.SecurePrefs.flush()
            check(com.optionslab.app.ira.IraThinking.holdsForErase())
        },
        s("IraTools") {
            com.optionslab.app.ira.IraTools.remember("Boss keeps his NIFTY stop at 20 points")   // a SecurePrefs key
            com.optionslab.app.ira.IraTools.missed("show my banknifty hedge")                    // a SecurePrefs key (written behind)
            com.optionslab.app.ira.IraTools.mistakes()                                           // read once and kept in memory
            com.optionslab.app.ira.IraTools.alertLog()                                           // read once and kept in memory
            check(com.optionslab.app.ira.IraTools.holdsForErase())
        },
        s("IraTax") {
            // The tax CSV as export() writes it (export itself opens the share sheet); HealthReport's zip lives in the same folder.
            java.io.File(app.cacheDir, "exports/Trades_FY2026-27_Paper.csv").apply { parentFile?.mkdirs()
            writeText("date,symbol,qty,entry,exit,pnl,charges\n2026-10-23,NIFTY26OCT25000CE,75,120.5,132.5,900,58.2\n") }
            check(com.optionslab.app.ira.IraTax.filesForErase().any { it.exists() })
        },
        s("PineScreen") {
            com.optionslab.app.ui.screens.pineDraftForTest(com.optionslab.app.data.PineScripts.Item(0L, "Boss's draft", "//@version=5\nindicator(\"mine\")\nplot(close)"))
            check(com.optionslab.app.ui.screens.pineDraftHeld())
        },
        s("PreTradeShadow") {
            com.optionslab.app.data.DangerToday.halfSize = true   // the switch's memory copy, as a settings save sets it
            check(com.optionslab.app.data.DangerToday.holdsForErase())
        },
        s("OpenChange") {
            com.optionslab.ira.ArmExits.set("orb", listOf(com.optionslab.ira.ArmExits.Entry("orb", "ORB", live = false, symbol = "NIFTY26OCT25000CE",
            stop = 90.0, target = 150.0, at = null)))
            check(!com.optionslab.ira.ArmExits.isEmpty())
        },
        s("Rx") {
            com.optionslab.ira.Ask.parse("how is my nifty position doing today")   // Ask's question cache and the Kept readers it goes through
            com.optionslab.ira.Ask.reading("how is my nifty position doing today")
            check(com.optionslab.ira.WordCaches.holds())
        },
    )
}
