package com.optionslab.ira

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app's side of the review fixes G7 / G9 where it is wiring (the app's own Robolectric tests run them on CI; this module has
 * no Android): each check failed before its fix.
 */
class LabRiskWiringTest {
    private fun app(path: String): String =
        listOf("../app/src/main/java/com/optionslab/app/$path", "android/app/src/main/java/com/optionslab/app/$path").map(::File).first { it.isFile }.readText()

    /** [src]'s text from [from] up to the next [to] (a branch of a long function). */
    private fun between(src: String, from: String, to: String): String {
        val i = src.indexOf(from); assertTrue(i >= 0, from)
        val j = src.indexOf(to, i + from.length); assertTrue(j > i, to)
        return src.substring(i, j)
    }

    @Test fun g9_2_aRegimeSettingIsApprovedForLiveWithThePin() {
        val ui = app("ui/screens/PortfolioSection.kt")
        assertTrue("RegimeHost.approveLive(key, pinConfirmed = true)" in ui)
        val reauth = ui.indexOf("Reauth(model"); val approve = ui.indexOf("RegimeHost.approveLive(")
        assertTrue(reauth in 0 until approve, "approved only inside the PIN's onOk")
        assertTrue("BrainSection(divider = false, model = model)" in app("ui/screens/AutomationCard.kt"))
        assertTrue("BrainExtras(model)" in app("ui/screens/BrainSection.kt"))
        val host = app("data/RegimeHost.kt")
        assertFalse("Autopilot asks" in host, "the posted words promised an ask that never came")
        assertTrue("IraHub.offer(\"regime setting" in host)
    }

    @Test fun g9_4_theLabAndAutopilotAreNotAnsweredOnALockedPhone() {
        val hub = app("ira/IraHub.kt")
        val lab = between(hub, "val labAsk = ", "val autoAsk = ")
        assertTrue("VariantRunner.answer(labAsk, labLocked)" in lab && "val labLocked = phoneLocked()" in lab)
        val auto = between(hub, "val autoAsk = ", "AgentLessons.whyAsked(q) && JarvisLearning.whyFresh")
        val gate = auto.indexOf("if (phoneLocked()) { reply(com.optionslab.ira.SelfTalk.LOCKED_BOTS); return true }")
        assertTrue(gate >= 0 && gate < auto.indexOf("Autopilot.answer(autoAsk)"))
        assertTrue("SelfTalk.lockedRefusal(a.kind, locked)" in app("ira/SelfAnswers.kt"))
    }

    @Test fun g9_6_autopilotAndTheLabLeaveEventDaysOutAndAVoiceEventIsSeenAtOnce() {
        val ap = between(app("ira/Autopilot.kt"), "private suspend fun gather(", "// ---- applying one change")
        assertTrue("LearningFilter.includeMs(it.trade.entryMs)" in ap)
        assertFalse("TradeManagerHost.records.value, policy" in ap)
        val vr = app("data/VariantRunner.kt")
        assertTrue("LearningFilter.include(d)" in vr && "from, actual, evidence)" in vr)
        val acts = app("ira/IraActions.kt")
        assertEquals(2, Regex("IraEvents\\.(add\\(d, n\\)|remove\\(e\\[i]\\)); eventDaysChanged\\(\\)").findAll(acts).count())
        assertTrue("EventDays.refresh()" in acts)
    }

    @Test fun g7_14_theLabsWorkerNeverSwallowsItsCancellation() {
        val w = app("work/DiscoveryWorker.kt")
        assertFalse("runCatching { VariantRunner." in w)
        assertTrue("guarded { VariantRunner.settleIfDue() }" in w && "catch (e: kotlinx.coroutines.CancellationException)" in w)
        // The settle's books and their save in one critical section.
        val settle = between(app("data/VariantRunner.kt"), "suspend fun settleIfDue(", "private fun tell(")
        val sync = settle.substring(settle.indexOf("synchronized(lock) {"))
        assertTrue(sync.indexOf("save()") in 0 until sync.indexOf("v to f"))
    }

    @Test fun g9_3_thePortfolioGuardKeepsItsLastMatrixWhenAReadFails() {
        val ph = between(app("data/PortfolioHost.kt"), "suspend fun refresh()", "private fun publish(")
        assertFalse("getOrDefault(emptyMap())" in ph || "getOrDefault(emptyList())" in ph)
        assertTrue(ph.indexOf("refreshedAt = System.currentTimeMillis()") > ph.indexOf("guard.update("))
    }

    @Test fun g7_5_theCardOffersUndoOnlyForTheChangeInForce() {
        val card = app("ui/screens/StrategyLabCard.kt")
        assertEquals(2, Regex("VariantLab\\.undoable\\(variants, ch\\.id\\)").findAll(card).count())
    }
}
