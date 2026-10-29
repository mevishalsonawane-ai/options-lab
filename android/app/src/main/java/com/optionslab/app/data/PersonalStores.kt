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
    /** The app's folders, for the files no store owns (the crash report). Null before [init]. */
    @Volatile private var app: android.content.Context? = null

    fun init(context: android.content.Context) { app = context.applicationContext }

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
        // Ledger.kt holds two stores (the paper ledger and the price alarms): one entry, each wipe guarded so one failing never skips the other.
        Entry("Ledger", files = { com.optionslab.app.data.Ledger.filesForErase() + com.optionslab.app.data.Alarms.filesForErase() }) {
        runCatching { com.optionslab.app.data.Ledger.wipe() }; runCatching { com.optionslab.app.data.Alarms.wipe() } },
        Entry("Paper", files = { com.optionslab.app.data.Paper.filesForErase() }, holds = { com.optionslab.app.data.Paper.holdsForErase() }) { com.optionslab.app.data.Paper.wipe() },
        Entry("OrbArms", files = { com.optionslab.app.data.OrbArms.filesForErase() }, holds = { com.optionslab.app.data.OrbArms.holdsForErase() }) { com.optionslab.app.data.OrbArms.wipe() },
        Entry("ShadowArms", files = { com.optionslab.app.data.ShadowArms.filesForErase() }, holds = { com.optionslab.app.data.ShadowArms.holdsForErase() }) { com.optionslab.app.data.ShadowArms.wipe() },
        Entry("PineScripts", files = { com.optionslab.app.data.PineScripts.filesForErase() }, holds = { com.optionslab.app.data.PineScripts.holdsForErase() }) { com.optionslab.app.data.PineScripts.wipe() },
        // Already wiped today (Root.kt: PineAuto.wipe()): same call; wipe() now also drops a pass's late save/publish.
        Entry("PineAuto", files = { com.optionslab.app.data.PineAuto.filesForErase() }, holds = { com.optionslab.app.data.PineAuto.holdsForErase() }) { com.optionslab.app.data.PineAuto.wipe() },
        Entry("Protections", files = { com.optionslab.app.data.Protections.filesForErase() }, holds = { com.optionslab.app.data.Protections.holdsForErase() }) { com.optionslab.app.data.Protections.wipe() },
        // TradeBook.kt holds two stores: the Zerodha trade book and the trade journal (both wiped today; same calls).
        Entry("TradeBook", files = { com.optionslab.app.data.TradeBook.filesForErase() }, holds = { com.optionslab.app.data.TradeBook.holdsForErase() }) { com.optionslab.app.data.TradeBook.wipe() },
        Entry("TradeBook", files = { com.optionslab.app.data.Journal.filesForErase() }, holds = { com.optionslab.app.data.Journal.holdsForErase() }) { com.optionslab.app.data.Journal.wipe() },
        Entry("Strategies", files = { com.optionslab.app.data.Strategies.filesForErase() }, holds = { com.optionslab.app.data.Strategies.holdsForErase() }) { com.optionslab.app.data.Strategies.wipe() },
        // Already in eraseEverything: exact current call (the NSE contract list; public, but erased as before).
        Entry("Market", files = { com.optionslab.app.data.Market.filesForErase() }) { com.optionslab.app.data.Market.wipe() },
        Entry("Broker", files = { com.optionslab.app.data.Broker.filesForErase() }, holds = { com.optionslab.app.data.Broker.holdsForErase() }) { com.optionslab.app.data.Broker.forget() },
        // Already wiped today: MarketRecorder.forget() while the vault is held (settings keys + memory), its folder on the slow thread.
        Entry("MarketRecorder") { com.optionslab.app.data.MarketRecorder.forget() },
        Entry("MarketRecorder", slow = true, files = { com.optionslab.app.data.MarketRecorder.filesForErase() }, holds = { com.optionslab.app.data.MarketRecorder.holdsForErase() }) { com.optionslab.app.data.MarketRecorder.wipeFiles(); com.optionslab.app.data.MarketRecorder.forgetMemory() },
        Entry("GrammarArm", files = { com.optionslab.app.data.GrammarArm.filesForErase() }, holds = { com.optionslab.app.data.GrammarArm.holdsForErase() }) { com.optionslab.app.data.GrammarArm.wipe() },
        // slow: wipeNow takes the host's monitor, which a round holds (Root.kt called wipe(), which starts a thread and returns at once:
        // its memory was not yet cleared when the erase returned). wipeNow writes nothing (no vault, no settings), so it is safe after the
        // vault is released; a round that saved in between is deleted by it (it waits for the round).
        Entry("OiWallShadowHost", slow = true, files = { com.optionslab.app.data.OiWallShadowHost.filesForErase() }, holds = { com.optionslab.app.data.OiWallShadowHost.holdsForErase() }) { com.optionslab.app.data.OiWallShadowHost.wipeNow() },
        Entry("ExitCoordinator", holds = { com.optionslab.app.data.ExitCoordinator.holdsForErase() }) { com.optionslab.app.data.ExitCoordinator.wipe() },
        Entry("HiddenArms", files = { com.optionslab.app.data.HiddenArms.filesForErase() }, holds = { com.optionslab.app.data.HiddenArms.holdsForErase() }) { com.optionslab.app.data.HiddenArms.wipe() },
        Entry("OrderTiming", holds = { com.optionslab.app.data.OrderTiming.holdsForErase() }) { com.optionslab.app.data.OrderTiming.forget() },
        Entry("GoldTasPaper", files = { com.optionslab.app.data.GoldTasPaper.filesForErase() }, holds = { com.optionslab.app.data.GoldTasPaper.holdsForErase() }) { com.optionslab.app.data.GoldTasPaper.wipe() },
        Entry("Sweeper", holds = { com.optionslab.app.data.Sweeper.holdsForErase() }) { com.optionslab.app.data.Sweeper.wipe() },
        Entry("DayLockGuard", holds = { com.optionslab.app.data.DayLockGuard.holdsForErase() }) { com.optionslab.app.data.DayLockGuard.wipe() },
        Entry("PaperOnly", holds = { com.optionslab.app.data.PaperOnly.holdsForErase() }) { com.optionslab.app.data.PaperOnly.wipe() },
        Entry("BotReplayStore", files = { com.optionslab.app.ira.BotReplayStore.filesForErase() }, holds = { com.optionslab.app.ira.BotReplayStore.holdsForErase() }) { com.optionslab.app.ira.BotReplayStore.wipe() },
        Entry("IraCoach", holds = { com.optionslab.app.ira.IraCoach.holdsForErase() }) { com.optionslab.app.ira.IraCoach.wipe() },
        Entry("IraSolo", holds = { com.optionslab.app.ira.IraSolo.holdsForErase() }) { com.optionslab.app.ira.IraSolo.wipe() },
        // slow, as today (Root.kt's slow runnable). It is one small vault file; it could just as well move to the vault-held phase.
        Entry("History", slow = true, files = { com.optionslab.app.data.History.filesForErase() }) { com.optionslab.app.data.History.wipe() },
        Entry("BossTradeLog", files = { com.optionslab.app.ira.BossTradeLog.filesForErase() }, holds = { com.optionslab.app.ira.BossTradeLog.holdsForErase() }) { com.optionslab.app.ira.BossTradeLog.wipe() },
        Entry("LiquidityNotices", files = { com.optionslab.app.work.LiquidityNotices.filesForErase() }, holds = { com.optionslab.app.work.LiquidityNotices.holdsForErase() }) { com.optionslab.app.work.LiquidityNotices.wipe() },
        Entry("MainActivity", holds = { com.optionslab.app.MainActivity.holdsForErase() }) { com.optionslab.app.MainActivity.wipe() },
        Entry("ArmLive", files = { com.optionslab.app.data.ArmLive.filesForErase() }, holds = { com.optionslab.app.data.ArmLive.holdsForErase() }) { com.optionslab.app.data.ArmLive.wipeForErase() },
        Entry("CandleAgentHost", files = { com.optionslab.app.data.CandleAgentHost.filesForErase() }, holds = { com.optionslab.app.data.CandleAgentHost.holdsForErase() }) { com.optionslab.app.data.CandleAgentHost.wipe() },
        Entry("OrderReviewHost", files = { com.optionslab.app.data.OrderReviewHost.filesForErase() }, holds = { com.optionslab.app.data.OrderReviewHost.holdsForErase() }) { com.optionslab.app.data.OrderReviewHost.wipe() },
        Entry("LiveSelfTest", holds = { com.optionslab.app.data.LiveSelfTest.holdsForErase() }) { com.optionslab.app.data.LiveSelfTest.wipe() },
        Entry("OpenWidget", holds = { com.optionslab.app.widget.OpenWidget.holdsForErase() }) { com.optionslab.app.widget.OpenWidget.wipe() },
        Entry("JarvisLearning", files = { com.optionslab.app.ira.JarvisLearning.filesForErase() }, holds = { com.optionslab.app.ira.JarvisLearning.holdsForErase() }) { com.optionslab.app.ira.JarvisLearning.wipe() },
        Entry("IraHub", files = { com.optionslab.app.ira.IraHub.filesForErase() }, holds = { com.optionslab.app.ira.IraHub.holdsForErase() }) { com.optionslab.app.ira.IraHub.wipe() },
        Entry("Autopilot", files = { com.optionslab.app.ira.Autopilot.filesForErase() }, holds = { com.optionslab.app.ira.Autopilot.holdsForErase() }) { com.optionslab.app.ira.Autopilot.wipe() },
        Entry("IraBots", holds = { com.optionslab.app.ira.IraBots.holdsForErase() }) { com.optionslab.app.ira.IraBots.wipe() },
        Entry("ManagerSpecialists", files = { com.optionslab.app.data.ManagerSpecialists.filesForErase() }, holds = { com.optionslab.app.data.ManagerSpecialists.holdsForErase() }) { com.optionslab.app.data.ManagerSpecialists.wipe() },
        Entry("McxNr7Record", files = { com.optionslab.app.data.McxNr7Record.filesForErase() }, holds = { com.optionslab.app.data.McxNr7Record.holdsForErase() }) { com.optionslab.app.data.McxNr7Record.wipe() },
        Entry("MarketContext", holds = { com.optionslab.app.data.MarketContext.holdsForErase() }) { com.optionslab.app.data.MarketContext.wipe() },
        Entry("IraStudy", holds = { com.optionslab.app.ira.IraStudy.holdsForErase() }) { com.optionslab.app.ira.IraStudy.wipe() },
        Entry("IraWidget", holds = { com.optionslab.app.widget.IraWidget.holdsForErase() }) { com.optionslab.app.widget.IraWidget.wipe() },
        Entry("GoldTrendPaper", files = { com.optionslab.app.data.GoldTrendPaper.filesForErase() }, holds = { com.optionslab.app.data.GoldTrendPaper.holdsForErase() }) { com.optionslab.app.data.GoldTrendPaper.wipe() },
        Entry("GoldDipPaper", files = { com.optionslab.app.data.GoldDipPaper.filesForErase() }, holds = { com.optionslab.app.data.GoldDipPaper.holdsForErase() }) { com.optionslab.app.data.GoldDipPaper.wipe() },
        Entry("ProblemsHost", files = { com.optionslab.app.data.ProblemsHost.filesForErase() }, holds = { com.optionslab.app.data.ProblemsHost.holdsForErase() }) { com.optionslab.app.data.ProblemsHost.wipe() },
        Entry("RegimeHost", holds = { com.optionslab.app.data.RegimeHost.holdsForErase() }) { com.optionslab.app.data.RegimeHost.wipe() },
        Entry("GammaLive", holds = { com.optionslab.app.data.GammaLive.holdsForErase() }) { com.optionslab.app.data.GammaLive.wipe() },
        // The upload folder can be large (sealed day files): on the slow file thread. Its wipe writes no setting or vault file.
        Entry("GitHubSync", slow = true, files = { com.optionslab.app.data.GitHubSync.filesForErase() }) { com.optionslab.app.data.GitHubSync.wipe() },
        // Jarvis's ears: the words heard last, the turns' trace, Boss's pauses, hearing/language counts (companion of the service).
        Entry("JarvisVoice", holds = { com.optionslab.app.ira.JarvisVoice.holdsForErase() }) { com.optionslab.app.ira.JarvisVoice.wipe() },
        // ira-core: the readings of Boss's last questions, keyed by his words (64 kept).
        Entry("AgentLessons", holds = { com.optionslab.ira.AgentLessons.cachedForErase() }) { com.optionslab.ira.AgentLessons.clearCache() },
        Entry("McxPaperArms", files = { com.optionslab.app.data.McxPaperArms.filesForErase() }, holds = { com.optionslab.app.data.McxPaperArms.holdsForErase() }) { com.optionslab.app.data.McxPaperArms.wipe() },
        Entry("Ft3Arm", files = { com.optionslab.app.data.Ft3Arm.filesForErase() }, holds = { com.optionslab.app.data.Ft3Arm.holdsForErase() }) { com.optionslab.app.data.Ft3Arm.erase() },
        Entry("VariantRunner", files = { com.optionslab.app.data.VariantRunner.filesForErase() }, holds = { com.optionslab.app.data.VariantRunner.holdsForErase() }) { com.optionslab.app.data.VariantRunner.wipe() },
        Entry("BossRiskHost", holds = { com.optionslab.app.data.BossRiskHost.holdsForErase() }) { com.optionslab.app.data.BossRiskHost.wipe() },
        // Already in eraseEverything (the slow thread): exact current call.
        Entry("LiveRecorder", slow = true, files = { com.optionslab.app.data.LiveRecorder.filesForErase() }, holds = { com.optionslab.app.data.LiveRecorder.holdsForErase() }) { com.optionslab.app.data.LiveRecorder.wipe() },
        // Its running connection holds the topic and token; stops it, keeps the supervisor (NOT the test-only NtfyReader.wipe()).
        Entry("NtfyReader", holds = { com.optionslab.app.data.NtfyReader.holdsForErase() }) { com.optionslab.app.data.NtfyReader.forgetForErase() },
        Entry("TimeMachineHost", files = { com.optionslab.app.data.TimeMachineHost.filesForErase() }, holds = { com.optionslab.app.data.TimeMachineHost.holdsForErase() }) { com.optionslab.app.data.TimeMachineHost.wipe() },
        Entry("SelfGrowth", files = { com.optionslab.app.ira.SelfGrowth.filesForErase() }, holds = { com.optionslab.app.ira.SelfGrowth.holdsForErase() }) { com.optionslab.app.ira.SelfGrowth.wipe() },
        Entry("PayoutWatch", holds = { com.optionslab.app.data.PayoutWatch.holdsForErase() }) { com.optionslab.app.data.PayoutWatch.wipe() },
        Entry("LossBreaker", holds = { com.optionslab.app.data.LossBreaker.holdsForErase() }) { com.optionslab.app.data.LossBreaker.wipe() },
        Entry("IraNotes", holds = { com.optionslab.app.ira.IraNotes.holdsForErase() }) { com.optionslab.app.ira.IraNotes.wipe() },
        Entry("EventDays", holds = { com.optionslab.app.data.EventDays.holdsForErase() }) { com.optionslab.app.data.EventDays.wipe() },
        Entry("NtfyArm", files = { com.optionslab.app.data.NtfyArm.filesForErase() }, holds = { com.optionslab.app.data.NtfyArm.holdsForErase() }) { com.optionslab.app.data.NtfyArm.wipeForErase() },
        // 28 Oct: IraThinker's flow's paper book and its session record.
        Entry("FlowArm", files = { com.optionslab.app.data.FlowArm.filesForErase() }, holds = { com.optionslab.app.data.FlowArm.holdsForErase() }) { com.optionslab.app.data.FlowArm.wipeForErase() },
        // 28 Oct: S5 Trail's paper book.
        Entry("S5Arm", files = { com.optionslab.app.data.S5Arm.filesForErase() }, holds = { com.optionslab.app.data.S5Arm.holdsForErase() }) { com.optionslab.app.data.S5Arm.wipeForErase() },
        Entry("CisdArm", files = { com.optionslab.app.data.CisdArm.filesForErase() }, holds = { com.optionslab.app.data.CisdArm.holdsForErase() }) { com.optionslab.app.data.CisdArm.wipeForErase() },
        Entry("TradeManagerHost", files = { com.optionslab.app.data.TradeManagerHost.filesForErase() }, holds = { com.optionslab.app.data.TradeManagerHost.holdsForErase() }) { com.optionslab.app.data.TradeManagerHost.wipe() },
        Entry("TrendWatchHost", files = { com.optionslab.app.data.TrendWatchHost.filesForErase() }, holds = { com.optionslab.app.data.TrendWatchHost.holdsForErase() }) { com.optionslab.app.data.TrendWatchHost.wipe() },
        Entry("NightArm", files = { com.optionslab.app.data.NightArm.filesForErase() }, holds = { com.optionslab.app.data.NightArm.holdsForErase() }) { com.optionslab.app.data.NightArm.wipe() },
        Entry("GoldPaper", files = { com.optionslab.app.data.GoldPaper.filesForErase() }, holds = { com.optionslab.app.data.GoldPaper.holdsForErase() }) { com.optionslab.app.data.GoldPaper.wipe() },
        Entry("WatchlistHost", files = { com.optionslab.app.data.WatchlistHost.filesForErase() }, holds = { com.optionslab.app.data.WatchlistHost.holdsForErase() }) { com.optionslab.app.data.WatchlistHost.wipe() },
        Entry("AppHealth", files = { com.optionslab.app.data.AppHealth.filesForErase() }, holds = { com.optionslab.app.data.AppHealth.holdsForErase() }) { com.optionslab.app.data.AppHealth.wipe() },
        Entry("ExposureCapHost", holds = { com.optionslab.app.data.ExposureCapHost.holdsForErase() }) { com.optionslab.app.data.ExposureCapHost.wipe() },
        Entry("PortfolioHost", holds = { com.optionslab.app.data.PortfolioHost.holdsForErase() }) { com.optionslab.app.data.PortfolioHost.wipe() },
        Entry("StaticIp", holds = { com.optionslab.app.data.StaticIp.holdsForErase() }) { com.optionslab.app.data.StaticIp.wipe() },
        Entry("IraDayJournal", holds = { com.optionslab.app.ira.IraDayJournal.holdsForErase() }) { com.optionslab.app.ira.IraDayJournal.wipe() },
        Entry("VoiceGuard", holds = { com.optionslab.app.ira.VoiceGuard.holdsForErase() }) { com.optionslab.app.ira.VoiceGuard.wipe() },
        Entry("SelfJobs", files = { com.optionslab.app.ira.SelfJobs.filesForErase() }, holds = { com.optionslab.app.ira.SelfJobs.holdsForErase() }) { com.optionslab.app.ira.SelfJobs.wipe() },
        Entry("IraModel", holds = { com.optionslab.app.ira.IraModel.holdsForErase() }) { com.optionslab.app.ira.IraModel.wipe() },
        Entry("Jobs", holds = { com.optionslab.app.work.Tasks.TicketNotices.holdsForErase() }) { com.optionslab.app.work.Tasks.TicketNotices.wipe() },
        Entry("HeroTicketArm", files = { com.optionslab.app.data.HeroTicketArm.filesForErase() }, holds = { com.optionslab.app.data.HeroTicketArm.holdsForErase() }) { com.optionslab.app.data.HeroTicketArm.erase() },
        Entry("IraNewsTrades", holds = { com.optionslab.app.ira.IraNewsTrades.holdsForErase() }) { com.optionslab.app.ira.IraNewsTrades.wipe() },
        Entry("McxMarket", files = { com.optionslab.app.data.McxMarket.filesForErase() }, holds = { com.optionslab.app.data.McxMarket.holdsForErase() }) { com.optionslab.app.data.McxMarket.erase() },
        Entry("FlowGate", files = { com.optionslab.app.data.FlowGate.filesForErase() }, holds = { com.optionslab.app.data.FlowGate.holdsForErase() }) { com.optionslab.app.data.FlowGate.wipe() },
        Entry("LiquidityTest", files = { com.optionslab.app.data.LiquidityTest.filesForErase() }, holds = { com.optionslab.app.data.LiquidityTest.holdsForErase() }) { com.optionslab.app.data.LiquidityTest.wipe() },
        Entry("Relay", holds = { com.optionslab.app.data.Relay.holdsForErase() }) { com.optionslab.app.data.Relay.wipe() },
        Entry("IraWeekly", files = { com.optionslab.app.ira.IraWeekly.filesForErase() }, holds = { com.optionslab.app.ira.IraWeekly.holdsForErase() }) { com.optionslab.app.ira.IraWeekly.wipe() },
        Entry("FillSlippage", slow = true, files = { com.optionslab.app.data.FillSlippage.filesForErase() }, holds = { com.optionslab.app.data.FillSlippage.holdsForErase() }) { com.optionslab.app.data.FillSlippage.wipe() },
        Entry("AccountStopHost", holds = { com.optionslab.app.data.AccountStopHost.holdsForErase() }) { com.optionslab.app.data.AccountStopHost.wipe() },
        Entry("ExpirySquareOff", holds = { com.optionslab.app.data.ExpirySquareOff.holdsForErase() }) { com.optionslab.app.data.ExpirySquareOff.wipe() },
        Entry("DayOco", holds = { com.optionslab.app.data.DayOco.holdsForErase() }) { com.optionslab.app.data.DayOco.wipe() },
        Entry("HealthReport", files = { com.optionslab.app.data.HealthReport.filesForErase() }) { com.optionslab.app.data.HealthReport.wipe() },
        Entry("GlanceSource", holds = { com.optionslab.app.data.GlanceSource.holdsForErase() }) { com.optionslab.app.data.GlanceSource.wipe() },
        Entry("PassedArmsHost", files = { com.optionslab.app.data.PassedArmsHost.filesForErase() }, holds = { com.optionslab.app.data.PassedArmsHost.holdsForErase() }) { com.optionslab.app.data.PassedArmsHost.wipeForErase() },
        Entry("SmartWorkers", files = { com.optionslab.app.data.SmartWorkers.filesForErase() }, holds = { com.optionslab.app.data.SmartWorkers.holdsForErase() }) { com.optionslab.app.data.SmartWorkers.wipe() },
        Entry("VixDivArm", files = { com.optionslab.app.data.VixDivArm.filesForErase() }, holds = { com.optionslab.app.data.VixDivArm.holdsForErase() }) { com.optionslab.app.data.VixDivArm.wipeForErase() },
        Entry("SharedKnowledgeHost", files = { com.optionslab.app.data.SharedKnowledgeHost.filesForErase() }, holds = { com.optionslab.app.data.SharedKnowledgeHost.holdsForErase() }) { com.optionslab.app.data.SharedKnowledgeHost.wipe() },
        Entry("MoveRecorder", holds = { com.optionslab.app.data.MoveRecorder.holdsForErase() }) { com.optionslab.app.data.MoveRecorder.wipe() },
        Entry("IraJournal", holds = { com.optionslab.app.ira.IraJournal.holdsForErase() }) { com.optionslab.app.ira.IraJournal.wipe() },
        // Already wiped today on the slow thread (Root.kt: Store.wipeDeviceData()).
        Entry("Store", slow = true, files = { com.optionslab.app.data.Store.filesForErase() }, holds = { com.optionslab.app.data.Store.holdsForErase() }) { com.optionslab.app.data.Store.wipeDeviceData() },
        Entry("LearningFreeze", files = { com.optionslab.app.ira.LearningFreeze.filesForErase() }, holds = { com.optionslab.app.ira.LearningFreeze.holdsForErase() }) { com.optionslab.app.ira.LearningFreeze.wipe() },
        Entry("JarvisSpeaker", holds = { com.optionslab.app.ira.JarvisSpeaker.holdsForErase() }) { com.optionslab.app.ira.JarvisSpeaker.wipe() },
        Entry("IraExpert", holds = { com.optionslab.app.ira.IraExpert.holdsForErase() }) { com.optionslab.app.ira.IraExpert.wipe() },
        Entry("Diag", files = { com.optionslab.app.data.Diag.filesForErase() }, holds = { com.optionslab.app.data.Diag.holdsForErase() }) { com.optionslab.app.data.Diag.wipe() },
        // The settings vault's large values (Jarvis's notes, memory, study, trades...); SecurePrefs.wipe() also calls this - harmless twice.
        Entry("SecureBlobs", files = { com.optionslab.app.security.SecureBlobs.filesForErase() }, holds = { com.optionslab.app.security.SecureBlobs.holdsForErase() }) { com.optionslab.app.security.SecureBlobs.wipe { } },
        // Already wiped today (Root.kt: SecurePrefs.wipe()).
        Entry("SecurePrefs", files = { com.optionslab.app.security.SecurePrefs.filesForErase() }, holds = { com.optionslab.app.security.SecurePrefs.holdsForErase() }) { com.optionslab.app.security.SecurePrefs.wipe() },
        // The last crash report (class names and stack frames, sealed with the key the erase destroys): deleted too, as an
        // exception's frames can name a symbol or an order.
        Entry("IraAlgoApp", files = { listOfNotNull(app?.let { File(it.filesDir, com.optionslab.app.IraAlgoApp.CRASH_FILE) }) }) { },
        // Jarvis's short answers to Boss's last questions (memory only).
        Entry("ShortAnswer") { com.optionslab.ira.ShortAnswer.clearCache() },
        // The fingerprint unlock's key (in the Keystore, not a file).
        Entry("BiometricGate") { com.optionslab.app.security.BiometricGate.forget() },
        Entry("KiteStream", holds = { com.optionslab.app.data.KiteStream.holdsForErase() }) { com.optionslab.app.data.KiteStream.wipe() },
        Entry("IraAccount", holds = { com.optionslab.app.ira.IraAccount.holdsForErase() }) { com.optionslab.app.ira.IraAccount.wipe() },
        Entry("IraThinking", holds = { com.optionslab.app.ira.IraThinking.holdsForErase() }) { com.optionslab.app.ira.IraThinking.wipe() },
        Entry("IraTools", holds = { com.optionslab.app.ira.IraTools.holdsForErase() }) { com.optionslab.app.ira.IraTools.wipe() },
        Entry("IraTax", files = { com.optionslab.app.ira.IraTax.filesForErase() }) { com.optionslab.app.ira.IraTax.wipe() },
        Entry("PineScreen", holds = { com.optionslab.app.ui.screens.pineDraftHeld() }) { com.optionslab.app.ui.screens.wipePineDraft() },
        Entry("PreTradeShadow", holds = { com.optionslab.app.data.DangerToday.holdsForErase() }) { com.optionslab.app.data.DangerToday.wipe() },
        Entry("OpenChange", holds = { !com.optionslab.ira.ArmExits.isEmpty() }) { com.optionslab.ira.ArmExits.clear() },
        // ira-core's word caches: every Kept reading (keyed by Boss's questions and his ask log's lines), Ask's parsed questions and
        // ShortAnswer's memo (the last is also cleared by the existing ShortAnswer.clearCache() call; doing it twice is harmless).
        Entry("Rx", holds = { com.optionslab.ira.WordCaches.holds() }) { com.optionslab.ira.WordCaches.clear() },
    ) }

    /** Source files that save only settings keys and keep no copy in memory: [com.optionslab.app.security.SecurePrefs.wipe] clears them. */
    val PREFS_ONLY: Map<String, String> = mapOf(
        "Backup" to "keeps nothing in memory; writes only the backup reminder's day (backup.last) and the restore's disarm flag to the settings (the files it restores belong to their own stores)",
        "IdeaRuleHost" to "writes only the win-rate rule for automatic ideas (g.ideaRule.on, g.ideaRule.minPct, 28 Oct); memory holds that switch and one number, nothing about the account",
        "BookPriceHost" to "writes only the paper book-price switch (g.paperBookPrice.on, 28 Oct); memory holds that one on/off flag, nothing about the account",
        "WhatsNewStore" to "writes only which What's new entries were seen (home.whatsnew.seen); memory holds this build's own entries, nothing about the account",
        "RemovedFeatureCleanup" to "deletes the removed Telegram files once and keeps only its done flag (cleanup.telegram.v1) in SecurePrefs; no memory copy",
        "IraLiquidityInsight" to "keeps only its weekly look's day and mark in SecurePrefs; reads the arms' book through OrbArms, holds no copy",
        "Automations" to "Jarvis's switches and last-acted lines are SecurePrefs keys only; no memory copy",
        "DailyPnl" to "the P&L calendar is the pnl.days.* SecurePrefs keys; the minute-lazy figure is held by SecurePrefs itself, whose wipe drops it; only a change counter in memory",
        "Settings" to "AppSettings are SecurePrefs keys; only a save counter in memory (the danger switch's memory copy is DangerToday's, in PreTradeShadow)",
        "ChartScreen" to "the chart's recent symbols and switches are SecurePrefs keys; its in-memory copies are composition state, dropped when the erase locks the session",
        "PnlTracker" to "the day's P&L curve lives only in SecurePrefs keys (pnl.day, pnl.series), read from there each time; no copy in memory",
        "IraTomorrow" to "writes only jarvis.tomorrow.done; its only memory is the public FII/DII figures read from the market recorder",
        "IraSundayReview" to "writes only the jarvis.sundayReview.* days; the review is composed from other stores each time, nothing kept",
        "Heartbeat" to "writes only its heartbeat / stalled / alerted keys; memory holds only timestamps and the busy worker's name",
        "IraActivity" to "Jarvis's activity log: one settings key (jarvis.activity, a SecureBlobs key), read from the settings each time; no copy in memory",
        "IraOpening" to "the day the opening note was put (jarvis.opening.done); no copy in memory",
        "PinLock" to "the PIN's salted verifier and attempt counters (pin.*), read from the settings each time; no copy in memory",
        "IraForwardWatch" to "what was last told for each arm's forward test (ForwardWatch keys); reads only otherwise, no copy in memory",
        "IraEvents" to "Boss's own events: one settings key (ira.events), read from the settings each time; nothing in memory",
        "SettingsLog" to "the limits' change history and undo marks: settings keys only; in memory only who makes the next save (a label)",
        "BotTile" to "the gold tile's armed arms: one settings key (gold.tile.armed); the tile reads the books, keeps nothing",
        "KitePin" to "the Kite CA pins: settings keys; the memory copy is public CA key hashes keyed on SecurePrefs.generationHint, which SecurePrefs.wipe bumps",
        "BrokerScreens" to "the Kite key form's draft: settings keys (draft.kite.*); the typed text is screen state, and (26 Oct) never written back after an erase",
        "GoldScreens" to "one settings flag (asked.notify); the rest is screen state",
        "AppModel" to "the screen ViewModel (not a singleton): it saves only the health.verdict setting; its screen copies are cleared by resetAfterWipe when Root's wipes bumps (26 Oct: now every account copy, the login secret too)",
        "Capture" to "only the screenshot switch (sec.capture.live), read from SecurePrefs on every use; nothing kept in memory",
        "IraGoals" to "Boss's goals and the told-today marks are SecurePrefs keys, read afresh on every use; nothing kept in memory",
        "Root" to "only the 'asked once' flags (asked.notify, getting started); the erase itself lives here; screen state is dropped via wipes",
        "DailyReports" to "the reports' once-a-day marks (jarvis.closedMorning.*, the evening's) are settings keys; it keeps nothing in memory",
        "IraPreMarket" to "the armed-bots record (jarvis.premarket.armed, names only) is a settings key; it keeps nothing in memory",
        "IraLater" to "Boss's timed commands and reminders (jarvis.later, jarvis.reminders) are settings keys, read each time; it keeps nothing in memory",
        "Guard" to "the drawdown peaks (per account) are settings keys; its only memory is the public instrument list",
        "OiBaseline" to "the day's first OI per contract (oi.baseline, public market data) is a settings key, read each time; no memory",
        "ProcessExits" to "the last process end seen (exit.seen) is a settings key; its memory is only Android's own process-exit records (no account data)",
        "IraAgenda" to "Jarvis's plan for the day is one SecurePrefs key (jarvis.agenda), read afresh each time; no copy in memory",
        "IraImprove" to "Jarvis's weekly goals are one SecurePrefs key (jarvis.improve), read afresh each time; no copy in memory",
    )

    /** Source files that save something, but nothing personal (public market data, the app's own build facts): why each. */
    val NOT_PERSONAL: Map<String, String> = mapOf(
        "BatteryMeter" to "noBackupFilesDir/battery_today.txt: the last three days' background-loop minutes and counts (loop names and numbers only); nothing about the account",
        "GammaMorningHost" to "the morning gamma line: market GEX readings (index, signs, strikes) in memory and its day/done keys in the settings; nothing about the account",
        "CloudSync" to "ira-core's pure rules for the GitHub sync: no state, writes nothing (the app's GitHubSync host saves)",
        "OrderFlowLive" to "records public order flow (1-second bars of index futures, ATM options and MCX futures) to noBackupFilesDir/orderflow; nothing about the account",
        "MainWrites" to "saves nothing: keeps in memory only setting key / file names and class.method names of screen-thread writes (never a value); its diary line goes through Diag",
        "Speed" to "saves nothing: the screen's stall tally in memory (counts, times, class.method names); diary lines go through Diag, health samples through AppHealth",
        "LiveRecord" to "ira-core record shapes and pure logic only: writes nothing (LiveRecorder writes the recordings)",
        "HistoryKeeper" to "public market history (indices, futures, watched ATM options, end-of-day chains, MCX) as plain gzip under noBackupFilesDir/history; its cap and tried pieces are SecurePrefs keys",
        "Consolidation" to "pure ira-core rules: it reads and writes nothing itself (SelfGrowth reads the files, passes them in and saves the result)",
        "TickZone" to "ira-core rules and row shapes for the per-second market recording; writes nothing itself (TickZoneRecorder writes, under LiveRecorder's folder)",
        "NightMarks" to "shared_prefs/night_marks: alarm times, the night's CPU budget, the install stamp and ntfy's armed flag only - times and flags, nothing about the account",
        "Holidays" to "NSE's public trading-holiday calendar (fetched from Upstox/NSE, plus any date corrected by hand): market data, kept like the rest of it",
        "Vault" to "the encryption itself: writes only other stores' files; its key is destroyed by the erase; memory holds file paths and write counts only",
        "ModelDownload" to "downloads the public on-phone language model file (Hugging Face, checked by SHA-256); nothing personal",
        "HealthBook" to "pure functions (no I/O) in ira-core; the health book is kept and saved by AppHealth",
        "StockOptions" to "today's NSE F&O stock-option contract list (public, contracts_stocks.txt) and its in-memory copy",
        "TickZoneRecorder" to "public market ticks and order-flow minutes under recordings/ (deleted with the recordings, and its memory reset, by LiveRecorder.wipe)",
        "HistoryVault" to "ira-core class: public 1-minute bars and end-of-day chains (HistoryKeeper's folder), market data like the recorder's",
        "PersonalStores" to "this list itself: it saves nothing (it names the stores and calls their wipes)",
        "PineChart" to "saves nothing: runs Boss's Pine scripts on the chart, read from PineScripts each call (no copy kept)",
        "StatusDays" to "saves nothing itself: the arms' status-line map class, saved inside each arm's own book (each arm's wipe drops it)",
        "IraSaved" to "saves nothing itself: the JSON codec for IraHub's state, which IraHub writes and wipes",
        "SecretBox" to "pure crypto (PIN sealing, the Keystore pepper key): keeps nothing; the pepper key is destroyed with the vault's keys",
        "SessionLock" to "reads only the idle-lock setting; holds only the locked flag and the last touch time",
        "ChartSymbolPicker" to "reads only the chart's last symbol (a SecurePrefs key written by ChartScreen); keeps no copy",
        "ControlPages" to "screen only: reads the harvest's last line from SecurePrefs and shows other stores' data; saves through them",
        "IraPage" to "screen only: the AI-live switch saves through IraNewsTrades; everything else shown is other stores' state",
        "PortfolioSection" to "screen only: the history cap saves through HistoryKeeper (public market history)",
        "RowActions" to "screen only: a trade's note saves through Journal (TradeBook.kt)",
        "BackgroundBudget" to "CPU-time budget counts only (night marks via NightMarks); nothing about the account",
        "BudgetRule" to "ira-core pure CPU-budget logic: writes nothing",
        "HistoryKeep" to "ira-core pure plan of what public market history to fetch: writes nothing",
        "LiveSeal" to "ira-core pure file sealing format (IRLIVE01): writes nothing, keeps nothing",
        "ManagerTeam" to "ira-core pure specialists' vote logic: writes nothing, no object-level state",
        "MarketRecord" to "ira-core pure recording format of public market data: writes nothing (MarketRecorder writes the files)",
    )

    /**
     * Files (by name, under the app's own folders) that may remain after an erase, and why each holds nothing personal.
     * Market data is public and stays (the erase has always kept it).
     */
    val KEPT_FILES: Map<String, String> = mapOf(
        // Paths are relative to one of the app's folders (files, no_backup, cache, shared_prefs); "*" is any name, "**" any depth.
        // 26 Oct, review: only the recorders' own files, not the whole folder - FlowGate's shadow log (the arms' trades and order
        // ids, personal) lives in orderflow/ too, and "orderflow/**" hid it from the test's check for files left.
        "orderflow/*.csv.gz" to "the order-flow recorder's day and footprint files: public 1-second bars and footprints of indices, options and MCX futures",
        "orderflow/events/**" to "the move recorder's event files: public market moves (prices and flow around a big move)",
        "history/**" to "the history keeper's folder: public 1-minute bars of indices, futures, watched options and end-of-day chains",
        "holidays.json" to "NSE's public holiday calendar",
        "mcx_holidays.json" to "MCX's public trading calendar",
        "mcx_contracts.json" to "the day's public MCX contract list",
        "mcx_contracts.json.tmp" to "the public MCX contract list being written",
        "contracts_bfo.json" to "the public BSE (SENSEX) option contract list",
        "contracts_stocks.txt" to "today's public NSE F&O stock-option contract list",
        "contracts_stocks.txt.tmp" to "the same public list being written",
        "*.gguf" to "Jarvis's on-phone language model: public weights, checked by SHA-256 (up to 2 GB to download again)",
        "*.gguf.part" to "a public language model download in progress",
        "night_marks.xml" to "alarm times, the night's CPU budget, the install stamp and ntfy's armed flag: times and flags only",
    )

    /**
     * The home-screen widgets redrawn after an erase (26 Oct): each showed the last figures until its next update. An update
     * broadcast to each of the app's widget kinds; each draws from its (now empty) store.
     */
    fun redrawWidgets() {
        val c = app ?: return
        val mgr = android.appwidget.AppWidgetManager.getInstance(c) ?: return
        for (k in listOf(com.optionslab.app.widget.IraWidget::class.java, com.optionslab.app.widget.OpenWidget::class.java,
            com.optionslab.app.widget.JarvisWidget::class.java)) runCatching {
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(c, k))
            if (ids.isNotEmpty()) c.sendBroadcast(android.content.Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .setClass(c, k).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
        }
    }

    /** TEST ONLY: one more store, wiped with the rest (a test shows one that throws never stops the others). */
    @Volatile internal var testExtra: List<Entry> = emptyList()
        set(v) { check(com.optionslab.app.BuildConfig.DEBUG) { "the test seam exists only in debug builds" }; field = v }

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
