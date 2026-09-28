package com.optionslab.engine.strategy

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every refusal the strategy validator makes, read as the user would see it. */
class ValidatorCoverageTest {
    private fun batchLeg(vararg kv: Pair<String, Any?>) = linkedMapOf<String, Any?>(
        "segment" to "options", "position" to "S", "lots" to 1L, "option_type" to "CE", "expiry" to "weekly",
    ).apply { kv.forEach { (k, v) -> if (v == REMOVE) remove(k) else put(k, v) } }

    private fun batch(vararg kv: Pair<String, Any?>, legs: List<Any?> = listOf(batchLeg())) = linkedMapOf<String, Any?>(
        "name" to "S", "underlying" to "nifty", "underlying_exchange" to "NSE_INDEX", "entry_time" to "09:20", "exit_time" to "15:15", "legs" to legs,
    ).apply { kv.forEach { (k, v) -> if (v == REMOVE) remove(k) else put(k, v) } }

    private fun sigLeg(vararg kv: Pair<String, Any?>) = linkedMapOf<String, Any?>("symbol" to "reliance", "exchange" to "NSE", "qty" to 10L)
        .apply { kv.forEach { (k, v) -> if (v == REMOVE) remove(k) else put(k, v) } }

    private fun signal(vararg kv: Pair<String, Any?>, legs: List<Any?> = listOf(sigLeg())) =
        batch("strategy_kind" to "signal", "underlying" to "RELIANCE", "underlying_exchange" to "NSE", "product" to "MIS", *kv, legs = legs)

    private fun err(payload: Any?, lot: (String, String) -> Int? = { _, _ -> null }): String =
        (StrategyValidator.validate(payload, lot) as? StrategyValidator.Result.Invalid)?.message ?: error("accepted: $payload")

    private fun ok(payload: Any?, lot: (String, String) -> Int? = { _, _ -> null }): StrategyDef {
        val r = StrategyValidator.validate(payload, lot)
        assertIs<StrategyValidator.Result.Ok>(r, r.toString())
        return r.def
    }

    @Test fun `the body, unknown fields and required fields`() {
        assertEquals("The request body must be a JSON object", err(listOf(1)))
        assertEquals("The request does not accept id, zzz. Accepted fields: " + listOf("name", "direction", "universe_tab", "underlying",
            "underlying_exchange", "strategy_type", "entry_time", "exit_time", "product", "pricetype", "legs", "overall_sl_mtm",
            "overall_target_mtm", "lock_profit", "trail_sl_to_entry", "scheduler", "daily_loss_limit_inr", "webhook_ip_allowlist",
            "strategy_kind").sorted().joinToString(", "), err(batch("zzz" to 1, "id" to 2)))
        assertEquals("legs is required", err(batch("legs" to REMOVE)))
        assertEquals("name is required", err(batch("name" to REMOVE)))
        assertEquals("name must be text", err(batch("name" to 5L)))
        assertEquals("name is required", err(batch("name" to "   ")))
        assertEquals("name must be at most 200 characters, got 201", err(batch("name" to "x".repeat(201))))
        assertEquals("underlying must be at most 50 characters, got 51", err(batch("underlying" to "x".repeat(51))))
        assertEquals("underlying_exchange must be one of: " + StrategyValidator.UNDERLYING_EXCHANGES.joinToString(", "), err(batch("underlying_exchange" to 3L)))
        assertEquals("strategy_kind must be one of: batch, signal. Got 'basket'", err(batch("strategy_kind" to "basket")))
        assertEquals("pricetype must be one of: MARKET. Got 'LIMIT'", err(batch("pricetype" to "LIMIT")))
    }

    @Test fun `numbers, whole numbers, booleans and times`() {
        assertEquals("overall_sl_mtm must be a number", err(batch("overall_sl_mtm" to true)))
        assertEquals("overall_sl_mtm must be a number, got 'abc'", err(batch("overall_sl_mtm" to "abc")))
        assertEquals("overall_sl_mtm must be a number", err(batch("overall_sl_mtm" to listOf(1))))
        assertEquals("overall_sl_mtm must be a number", err(batch("overall_sl_mtm" to Double.NaN)))
        assertEquals("overall_sl_mtm must be a number", err(batch("overall_sl_mtm" to "inf")))
        assertTrue(err(batch("overall_sl_mtm" to -5000L)).startsWith("overall_sl_mtm is entered as a positive amount"))
        assertEquals(5000.0, ok(batch("overall_sl_mtm" to "5000")).overallSlMtm)
        assertEquals("overall_target_mtm must be 0 or more, got -1", err(batch("overall_target_mtm" to -1L)))
        assertEquals(12.0, ok(batch("overall_target_mtm" to 12)).overallTargetMtm, "an Int is a number")
        assertEquals("legs[0].lots must be a whole number", err(batch(legs = listOf(batchLeg("lots" to true)))))
        assertEquals("legs[0].lots must be a whole number, got 'two'", err(batch(legs = listOf(batchLeg("lots" to "two")))))
        assertEquals("legs[0].lots must be a whole number, got 1.5", err(batch(legs = listOf(batchLeg("lots" to 1.5)))))
        assertEquals("legs[0].lots must be a whole number, got nan", err(batch(legs = listOf(batchLeg("lots" to Double.NaN)))))
        assertEquals("legs[0].lots must be between 1 and 50, got 100000000000000000000", err(batch(legs = listOf(batchLeg("lots" to 1e20)))))
        assertEquals(2, ok(batch(legs = listOf(batchLeg("lots" to 2.0)))).legs[0].lots)
        assertEquals(3, ok(batch(legs = listOf(batchLeg("lots" to 3)))).legs[0].lots)
        assertEquals(4, ok(batch(legs = listOf(batchLeg("lots" to "4")))).legs[0].lots)
        assertEquals("legs[0].lots must be a whole number", err(batch(legs = listOf(batchLeg("lots" to listOf(1))))))
        assertEquals("legs[0].lots must be between 1 and 50, got 51", err(batch(legs = listOf(batchLeg("lots" to 51L)))))
        assertEquals("trail_sl_to_entry must be true or false", err(batch("trail_sl_to_entry" to "yes")))
        assertEquals("trail_sl_to_entry must be true or false", err(batch("trail_sl_to_entry" to null)), "a present null is not a boolean")
        assertEquals("entry_time must be a HH:MM 24-hour time, for example 09:20", err(batch("entry_time" to 920L)))
        assertEquals("entry_time must be a HH:MM 24-hour time, for example 09:20. Got '9.20'", err(batch("entry_time" to "9.20")))
        assertEquals("exit_time is not a valid time of day: '24:00'", err(batch("exit_time" to "24:00")))
        assertEquals("exit_time is not a valid time of day: '15:60'", err(batch("exit_time" to "15:60")))
        assertEquals("entry_time is required for an intraday strategy", err(batch("entry_time" to REMOVE)))
        assertEquals("exit_time is required for an intraday strategy", err(batch("exit_time" to REMOVE)))
        assertEquals("entry_time must be earlier than exit_time", err(batch("entry_time" to "15:15")))
        val pos = ok(batch("strategy_type" to "positional", "entry_time" to REMOVE, "exit_time" to REMOVE))
        assertEquals(null, pos.entryTime); assertEquals(StrategyType.POSITIONAL, pos.strategyType)
        assertEquals(LocalTime.of(9, 20), ok(batch("strategy_type" to "positional", "exit_time" to REMOVE)).entryTime)
    }

    @Test fun `batch legs`() {
        assertEquals("legs must be a list", err(batch("legs" to "x")))
        assertEquals("A strategy needs at least 1 leg", err(batch(legs = emptyList())))
        assertEquals("A strategy takes at most 10 legs, got 11", err(batch(legs = List(11) { batchLeg() })))
        assertEquals("legs[0] must be a JSON object", err(batch(legs = listOf("x"))))
        assertEquals("Every leg needs its own id", err(batch(legs = listOf(batchLeg("id" to 2L), batchLeg()))), "the second leg defaults to its position, 2")
        assertEquals("legs[0].id must be between 1 and 10, got 11", err(batch(legs = listOf(batchLeg("id" to 11L)))))
        assertEquals("legs[0].position is required", err(batch(legs = listOf(batchLeg("position" to REMOVE)))))
        assertEquals("legs[0].strike is only used when strike_mode is 'strike'. Set strike_mode to 'strike', or remove the strike.",
            err(batch(legs = listOf(batchLeg("strike" to 100.0)))))
        assertEquals("legs[0].atm_offset is only used when strike_mode is 'atm'. Set strike_mode to 'atm', or remove the offset.",
            err(batch(legs = listOf(batchLeg("strike_mode" to "strike", "atm_offset" to "ATM", "strike" to 1.0)))))
        assertEquals("legs[0].strike must be greater than 0, got 0", err(batch(legs = listOf(batchLeg("strike_mode" to "strike", "strike" to 0L)))))
        assertEquals(292.5, ok(batch(legs = listOf(batchLeg("strike_mode" to "strike", "strike" to 292.5)))).legs[0].strike)
        assertEquals("legs[0].option_type is only valid on an options leg", err(batch(legs = listOf(batchLeg("segment" to "futures")))))
        val fut = ok(batch(legs = listOf(batchLeg("segment" to "futures", "option_type" to REMOVE, "expiry" to "monthly"))))
        assertEquals(UniverseTab.MONTHLY_ONLY, fut.universeTab, "no weekly rank: the monthly tab")
        assertEquals("legs[0].expiry is not valid on a cash leg",
            err(batch("universe_tab" to "stocks_fno", "product" to "MIS", legs = listOf(batchLeg("segment" to "cash", "option_type" to REMOVE)))))
        val cash = ok(batch("product" to "MIS", legs = listOf(batchLeg("segment" to "cash", "option_type" to REMOVE, "expiry" to REMOVE, "lots" to 500L))))
        assertEquals(UniverseTab.STOCKS_FNO, cash.universeTab); assertEquals(500, cash.legs[0].lots)
        assertTrue(err(batch("product" to "CNC", legs = listOf(batchLeg("segment" to "cash", "option_type" to REMOVE, "expiry" to REMOVE))))
            .startsWith("legs[0] sells cash short, but product 'CNC' carries the position."))
        assertEquals(1, ok(batch("product" to "CNC", legs = listOf(batchLeg("segment" to "cash", "position" to "B", "option_type" to REMOVE, "expiry" to REMOVE)))).legs.size)
        assertEquals("legs[0].segment is 'cash', which the 'weekly_monthly' universe does not offer. That tab trades futures and options.",
            err(batch("universe_tab" to "weekly_monthly", "product" to "MIS", legs = listOf(batchLeg("segment" to "cash", "option_type" to REMOVE, "expiry" to REMOVE)))))
        assertEquals(UniverseTab.MCX, ok(batch("underlying_exchange" to "MCX", legs = listOf(batchLeg("expiry" to "current")))).universeTab)
        assertEquals(UniverseTab.MCX, ok(batch("underlying_exchange" to "mcx", legs = listOf(batchLeg("expiry" to "current")))).universeTab, "the tab is read case-insensitively")
    }

    @Test fun `leg risk in points and percent`() {
        assertEquals("legs[0].risk_unit must be one of: points, percent. Got 'pips'", err(batch(legs = listOf(batchLeg("risk_unit" to "pips")))))
        assertEquals("legs[0].sl_pts must be 100.0 or less, got 150", err(batch(legs = listOf(batchLeg("risk_unit" to "percent", "sl_pts" to 150L)))))
        assertEquals(150.0, ok(batch(legs = listOf(batchLeg("sl_pts" to 150L, "target_pts" to 5.5)))).legs[0].slPts)
        assertEquals("legs[0].trail must be a JSON object", err(batch(legs = listOf(batchLeg("trail" to 5L)))))
        assertEquals("legs[0].trail does not accept z. Accepted fields: x, y", err(batch(legs = listOf(batchLeg("trail" to mapOf("x" to 1L, "y" to 1L, "z" to 1L))))))
        assertEquals("legs[0].trail.y is required", err(batch(legs = listOf(batchLeg("trail" to mapOf("x" to 1L))))))
        assertEquals("legs[0].trail.x must be 0 or more, got -1", err(batch(legs = listOf(batchLeg("trail" to mapOf("x" to -1L, "y" to 1L))))))
        assertEquals(Trail(2.0, 0.5), ok(batch(legs = listOf(batchLeg("risk_unit" to "PERCENT", "trail" to mapOf("x" to 2L, "y" to 0.5))))).legs[0].trail)
    }

    @Test fun `lock profit, scheduler and the IP allowlist`() {
        val lp = { m: Map<String, Any?> -> batch("lock_profit" to m) }
        assertEquals("lock_profit must be a JSON object", err(batch("lock_profit" to "x")))
        assertEquals("lock_profit.lock_profit cannot be more than lock_profit.if_profit_reaches: the floor would be above the profit that arms it",
            err(lp(mapOf("mode" to "lock", "if_profit_reaches" to 100L, "lock_profit" to 200L))))
        assertEquals("lock_profit.trail_step is required when mode is 'lock_and_trail'", err(lp(mapOf("mode" to "lock_and_trail", "if_profit_reaches" to 100L, "lock_profit" to 50L))))
        assertEquals(LockProfit(LockProfitMode.LOCK, 100.0, 0.0, 5.0, true), ok(lp(mapOf("mode" to "lock", "if_profit_reaches" to 100L, "lock_profit" to 0L, "trail_step" to 5L))).lockProfit)
        assertEquals("lock_profit.if_profit_reaches must be greater than 0, got 0", err(lp(mapOf("mode" to "lock", "if_profit_reaches" to 0L, "lock_profit" to 0L))))
        val sc = { m: Map<String, Any?> -> batch("scheduler" to m) }
        assertEquals("scheduler.enabled must be true or false", err(sc(mapOf("enabled" to "on"))))
        assertEquals("scheduler.days must be a list of days, MON to SUN", err(sc(mapOf("days" to "MON"))))
        assertEquals("scheduler.days[1] must be one of: MON, TUE, WED, THU, FRI, SAT, SUN. Got 'FUN'", err(sc(mapOf("days" to listOf("MON", "FUN")))))
        assertEquals("scheduler.days lists MON more than once", err(sc(mapOf("days" to listOf("mon", "MON")))))
        assertEquals("scheduler.days needs at least one day when the scheduler is enabled", err(sc(mapOf("enabled" to true))))
        assertEquals("scheduler.start_time is required", err(sc(mapOf("enabled" to true, "days" to listOf("MON")))))
        assertEquals("scheduler.start_time must be earlier than scheduler.auto_stop_time",
            err(sc(mapOf("enabled" to true, "days" to listOf("MON"), "start_time" to "15:00", "auto_stop_time" to "09:00"))))
        val s = ok(sc(mapOf("enabled" to true, "days" to listOf("fri", "MON"), "start_time" to "9:20", "auto_stop_time" to "15:00", "default_mode" to "LIVE"))).scheduler!!
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), s.days); assertEquals(RunMode.LIVE, s.defaultMode)
        assertEquals(SchedulerConfig(false, emptyList(), null, LocalTime.of(10, 0)), ok(sc(mapOf("auto_stop_time" to "10:00"))).scheduler)
        val ip = { v: Any? -> batch("webhook_ip_allowlist" to v) }
        assertEquals("webhook_ip_allowlist must be a list of IP addresses or CIDR ranges", err(ip("10.0.0.1")))
        assertEquals("webhook_ip_allowlist takes at most 20 entries, got 21", err(ip(List(21) { "10.0.0.1" })))
        assertEquals("webhook_ip_allowlist[0] must be an IP address or CIDR range", err(ip(listOf(" "))))
        assertEquals("webhook_ip_allowlist[1] must be an IP address or CIDR range", err(ip(listOf("1.1.1.1", 5L))))
        assertEquals("webhook_ip_allowlist[0] is not a valid IP address or CIDR range: 'example.com'", err(ip(listOf("example.com"))))
        assertEquals(listOf("10.0.0.0/8", "::1"), ok(ip(listOf(" 10.0.0.0/8 ", "::1"))).webhookIpAllowlist)
    }

    @Test fun `signal legs - venues, quantities, sides and directions`() {
        val d = ok(signal())
        assertEquals(StrategyKind.SIGNAL, d.kind); assertEquals("RELIANCE", d.legs[0].symbol); assertEquals(QtyMode.UNITS, d.legs[0].qtyMode)
        assertEquals(LegSide.BOTH, d.legs[0].side); assertEquals(UniverseTab.STOCKS_FNO, d.universeTab)
        assertEquals("legs[0] must be a JSON object", err(signal(legs = listOf(1L))))
        assertEquals("legs[0].symbol is required", err(signal(legs = listOf(sigLeg("symbol" to REMOVE)))))
        assertEquals("legs[0].symbol must be at most 100 characters, got 101", err(signal(legs = listOf(sigLeg("symbol" to "X".repeat(101))))))
        assertEquals("legs[0].exchange must be one of: " + StrategyValidator.SIGNAL_LEG_EXCHANGES.joinToString(", ") + ". Got 'NSE_INDEX'",
            err(signal(legs = listOf(sigLeg("exchange" to "nse_index")))))
        assertEquals("legs[0].qty_mode is 'lots', but NSE has no lot size. Cash instruments are counted in units.", err(signal(legs = listOf(sigLeg("qty_mode" to "lots")))))
        assertEquals("legs[0].qty is required", err(signal(legs = listOf(sigLeg("qty" to REMOVE)))))
        assertEquals("legs[0].qty must be between 1 and 1000000, got 1000001", err(signal(legs = listOf(sigLeg("qty" to 1_000_001L)))))
        assertEquals("legs[0].expiry does not apply to a cash leg", err(signal(legs = listOf(sigLeg("expiry" to "current")))))
        assertEquals("legs[0].id must be between 1 and 10, got 0", err(signal(legs = listOf(sigLeg("id" to 0L)))))
        // Futures on a derivative venue count in lots with a descriptive expiry.
        val fut = ok(signal(legs = listOf(sigLeg("segment" to "futures", "symbol" to "NIFTY26MAYFUT", "exchange" to "NFO", "qty" to 2L))))
        assertEquals(QtyMode.LOTS, fut.legs[0].qtyMode); assertEquals("current", fut.legs[0].expiry)
        assertEquals("legs[0].qty must be between 1 and 10000, got 10001",
            err(signal(legs = listOf(sigLeg("segment" to "futures", "exchange" to "NFO", "qty" to 10001L)))))
        assertEquals("legs[0] is a cash leg on NFO, which lists derivatives. Use NSE or BSE for cash, or set the segment to 'futures'.",
            err(signal(legs = listOf(sigLeg("exchange" to "NFO", "qty_mode" to "units")))))
        assertEquals("legs[0] is a futures leg on NSE, which lists cash. Use a derivative exchange, or set the segment to 'cash'.",
            err(signal(legs = listOf(sigLeg("segment" to "futures")))))
        // A units quantity on a lot-traded contract must be whole lots when the lot is known.
        val units = sigLeg("segment" to "futures", "exchange" to "NFO", "qty_mode" to "units", "qty" to 100L)
        assertEquals("legs[0].qty is 100, which is not a whole number of lots. RELIANCE on NFO trades in lots of 65.", err(signal(legs = listOf(units))) { _, _ -> 65 })
        assertEquals(100, ok(signal(legs = listOf(units))) { _, _ -> 50 }.legs[0].qty)
        assertEquals(100, ok(signal(legs = listOf(units))) { _, _ -> 0 }.legs[0].qty, "lot 0 cannot say")
        // Sides against the direction.
        assertEquals("legs[0].side is 'short', which a 'long_only' strategy never acts on. Use both or long, or change the strategy direction.",
            err(signal("direction" to "long_only", legs = listOf(sigLeg("side" to "short")))))
        assertEquals("legs[0].side is 'long', which a 'short_only' strategy never acts on. Use both or short, or change the strategy direction.",
            err(signal("direction" to "short_only", legs = listOf(sigLeg("side" to "long")))))
        assertEquals(LegSide.SHORT, ok(signal("direction" to "short_only", legs = listOf(sigLeg("side" to "short")))).legs[0].side)
        assertEquals("legs[0] does not accept position. Accepted fields: " + listOf("id", "symbol", "exchange", "side", "qty", "qty_mode", "segment", "expiry", "sl_pts", "target_pts", "trail", "risk_unit").sorted().joinToString(", "),
            err(signal(legs = listOf(sigLeg("position" to "B")))))
    }

    @Test fun `IP networks are judged as Python's ipaddress judges them`() {
        val good = listOf("10.0.0.1", "10.0.0.0/8", "10.0.0.0/008", "10.0.0.0/255.255.255.0", "10.0.0.0/0.0.0.255", "0.0.0.0/0",
            "::", "::1", "fe80::1%eth0", "2001:db8::/32", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7::", "::ffff:1.2.3.4", "2001:DB8:0:0:0:0:0:1/128")
        for (g in good) assertTrue(IpNetwork.isValid(g), g)
        val bad = listOf("", "1.2.3", "1.2.3.4.5", "256.1.1.1", "01.1.1.1", "1.2.3.a", "1.2.3.4/33", "1.2.3.4/1234", "1.2.3.4/255.0.255.0",
            "1.2.3.4/x.y", "1.2.3.4/8/8", ":", "1:2", "1::2::3", "1:2:3:4:5:6:7:8:9", "1::2:3:4:5:6:7:8", "12345::", "g::1", "::1/129",
            "::1/abc", "fe80::1%", "::ffff:1.2.3.256", "1:2:3:4:5:6:7", ":1:2:3:4:5:6:7", "1:::2")
        for (b in bad) assertFalse(IpNetwork.isValid(b), b)
    }

    private companion object { val REMOVE = Any() }
}
