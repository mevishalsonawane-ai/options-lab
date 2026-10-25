package com.optionslab.ira

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Home's Automation card's lines ([AutomationSummary]): one short line per section, from the books in memory. */
class AutomationSummaryTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val day: LocalDate = LocalDate.of(2026, 10, 12)
    private val t0: Long = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun trade(id: String, family: String, atMs: Long) =
        TradeManager.ManagedTrade("$family:x", id, family, "NIFTY", 1, "NIFTY25OCT25000CE", 100.0, 75, 70.0, 160.0,
            TradeManager.Caps(atMs + 3_600_000L), TradeManager.Account.PAPER, atMs, 1.0)

    @Test fun theTradeManagerSaysTodaysTradesAndWhereItActs() {
        val yesterday = t0 - 86_400_000L
        val records = listOf(
            TradeManager.Record(trade("a", "orb_fresh", t0), TradeManager.Mode.ACT),
            TradeManager.Record(trade("b", "liquidity", t0 + 60_000), TradeManager.Mode.SHADOW,
                actual = TradeManager.Exit(t0 + 120_000, 110.0, "target")),
            TradeManager.Record(trade("c", "orb", yesterday), TradeManager.Mode.SHADOW, actual = TradeManager.Exit(yesterday + 1, 90.0, "stop")),
        )
        val onlyFresh = TradeManager.Policy(TradeManager.FAMILIES.associate { TradeManager.key(it.key, TradeManager.Account.PAPER) to
            (if (it.key == "orb_fresh") TradeManager.Mode.ACT else TradeManager.Mode.SHADOW) })
        assertEquals("Trade manager: 2 trades managed today (1 open) · ORB Fresh acting on paper",
            AutomationSummary.managerLine(records, onlyFresh, day, zone))
        val none = TradeManager.Policy(TradeManager.FAMILIES.associate { TradeManager.key(it.key, TradeManager.Account.PAPER) to TradeManager.Mode.SHADOW })
        assertEquals("Trade manager: 0 trades managed today · records only", AutomationSummary.managerLine(emptyList(), none, day, zone))
    }

    @Test fun namesStayShort() {
        assertEquals("", AutomationSummary.names(emptyList()))
        assertEquals("Solo", AutomationSummary.names(listOf("Solo")))
        assertEquals("Solo and Pine", AutomationSummary.names(listOf("Solo", "Pine")))
        assertEquals("Solo, Pine +2", AutomationSummary.names(listOf("Solo", "Pine", "ORB Fresh", "Night")))
    }

    private fun change(target: String) = AutoChange.Change(AutoChange.Kind.SPECIALIST_MUTE, target, AutoChange.COUNTS, AutoChange.MUTED,
        "it hurt", AutoChange.Evidence(40, -4_000.0, AgentLessons.Confidence.STRONG, -1), AutoChange.Risk.LOWER, AutoChange.Scope.PAPER,
        expect = -1, effect = -1, what = target)

    @Test fun autopilotSaysTodaysChangesAndReverts() {
        assertEquals("Autopilot: 0 changes today, 0 reverted", AutomationSummary.autopilotLine(AutoChange.Book(), day))
        var b = AutoChange.record(AutoChange.Book(), change("spec:pine:vwap"), AutoChange.Status.PROBATION, 1, day).first
        assertEquals("Autopilot: 1 change today, 0 reverted", AutomationSummary.autopilotLine(b, day))
        b = AutoChange.record(b, change("spec:solo:oi"), AutoChange.Status.PROBATION, 2, day.minusDays(6)).first
        b = b.copy(entries = b.entries.map { if (it.change.target == "spec:solo:oi") it.copy(status = AutoChange.Status.REVERTED, closedOn = day) else it })
        b = AutoChange.record(b, change("spec:night:oi"), AutoChange.Status.OFFERED, 3, day).first
        assertEquals("Autopilot: 1 change today, 1 reverted · 1 asked", AutomationSummary.autopilotLine(b, day))
    }

    @Test fun aChangeMadeAndRevertedOrUndoneTodayStillCountsAsMade() {
        var b = AutoChange.record(AutoChange.Book(), change("spec:pine:vwap"), AutoChange.Status.PROBATION, 1, day).first
        b = b.copy(entries = b.entries.map { it.copy(status = AutoChange.Status.REVERTED, closedOn = day) })
        assertEquals("Autopilot: 1 change today, 1 reverted", AutomationSummary.autopilotLine(b, day))
        b = b.copy(entries = b.entries.map { it.copy(status = AutoChange.Status.UNDONE) })
        assertEquals("Autopilot: 1 change today, 0 reverted", AutomationSummary.autopilotLine(b, day))
        // Never applied: not made.
        b = b.copy(entries = b.entries.map { it.copy(status = AutoChange.Status.DECLINED) })
        assertEquals("Autopilot: 0 changes today, 0 reverted", AutomationSummary.autopilotLine(b, day))
    }

    @Test fun theLabCountsVariantsAndIdeasOnPaper() {
        val v = VariantLab.Book(variants = listOf(
            VariantLab.Variant("V1", "liquidity", emptyMap(), emptyMap(), day, 1.0, VariantLab.Status.ALIVE),
            VariantLab.Variant("V2", "liquidity", emptyMap(), emptyMap(), day, 1.0, VariantLab.Status.ALIVE),
            VariantLab.Variant("V3", "liquidity", emptyMap(), emptyMap(), day, 1.0, VariantLab.Status.PROPOSED),
            VariantLab.Variant("V4", "liquidity", emptyMap(), emptyMap(), day, 1.0, VariantLab.Status.RETIRED)))
        val idea = StrategyForge.Record(StrategyForge.Candidate(12, day, "BANKNIFTY",
            ShadowSim.Rules(ShadowSim.Entry.ORB_BREAK, stopPct = 0.2, targetPct = 0.5), StrategyForge.ExitBlock.FIXED), StrategyForge.Stage.PAPER, null)
        assertEquals("Lab: 3 variants · 1 idea on paper", AutomationSummary.labLine(v, StrategyForge.Book(records = listOf(idea))))
        assertEquals("Lab: 0 variants · 0 ideas on paper", AutomationSummary.labLine(VariantLab.Book(), StrategyForge.Book()))
    }

    @Test fun orderFlowSaysItsModesOnly() {
        assertEquals("Order flow: shadow only, no entry skipped", AutomationSummary.orderFlowLine(emptyMap()))
        assertEquals("Order flow: 1 strategy confirms entries",
            AutomationSummary.orderFlowLine(mapOf("orb" to FlowShadow.Setting(OrderFlow.Mode.CONFIRM), "solo" to FlowShadow.Setting(OrderFlow.Mode.SHADOW))))
    }

    @Test fun theBrainSaysTheDayAndThePauses() {
        assertEquals("Brain: regime not known yet", AutomationSummary.brainLine(MarketBrain.Context(), emptyList(), emptySet(), emptySet()))
        val busy = MarketBrain.Context(regimes = mapOf("BANKNIFTY" to MarketBrain.Regime("BANKNIFTY", MarketBrain.State.BUSY),
            "NIFTY" to MarketBrain.Regime("NIFTY", MarketBrain.State.NORMAL)))
        assertEquals("Brain: busy day · 1 strategy paused on paper · 1 parked to paper",
            AutomationSummary.brainLine(busy, emptyList(), setOf("night"), setOf("liquidity")))
        val quiet = MarketBrain.Context(regimes = mapOf("NIFTY" to MarketBrain.Regime("NIFTY", MarketBrain.State.QUIET)))
        assertEquals("Brain: quiet day", AutomationSummary.brainLine(quiet, emptyList(), emptySet(), emptySet()))
    }

    @Test fun theFreezeWarnsOnlyWhileFrozen() {
        assertNull(AutomationSummary.frozenLine(FreezeRule.State()))
        val line = AutomationSummary.frozenLine(FreezeRule.State(frozen = true, accounts = setOf(FreezeRule.Account.LIVE, FreezeRule.Account.PAPER)))
        assertEquals("Learning frozen (paper, live): nothing is applied until you unfreeze it.", line)
    }

    @Test fun theRowsKeepTheCardsOrderAndLeaveOutWhatIsNotBuilt() {
        val l = AutomationSummary.Lines(manager = "m", lab = "l", brain = "b", orderFlow = "o")
        assertEquals(listOf(AutomationSummary.Section.MANAGER, AutomationSummary.Section.LAB, AutomationSummary.Section.ORDER_FLOW,
            AutomationSummary.Section.BRAIN), l.rows().map { it.first })
        assertTrue(AutomationSummary.Lines().rows().isEmpty())
        // 22 Oct: the flat-by line is the last row when the card has it.
        assertEquals(AutomationSummary.Section.FLAT_BY, l.copy(flatBy = "Flat-by: index options 15:25").rows().last().first)
        assertEquals(AutomationSummary.Section.values().size, AutomationSummary.Section.values().map { it.key }.distinct().size)
    }
}
