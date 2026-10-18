package com.optionslab.ira

import com.optionslab.engine.strategy.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The day's diagnostics file (TODO G3, 13 Oct): its reasons, counts, caps and, above all, its privacy. */
@Suppress("UNCHECKED_CAST")
class DiagDayTest {
    private val day = LocalDate.of(2026, 10, 13)
    private fun t(h: Int, m: Int, s: Int = 0, d: LocalDate = day) = d.atTime(h, m, s)
    private fun ms(at: LocalDateTime) = at.atZone(com.optionslab.engine.IST).toInstant().toEpochMilli()

    private fun json(i: DiagDay.Input): Map<String, Any?> {
        val raw = GZIPInputStream(DiagDay.encode(i).inputStream()).readBytes().toString(Charsets.UTF_8)
        return Json.parse(raw) as Map<String, Any?>
    }

    private fun text(i: DiagDay.Input): String = GZIPInputStream(DiagDay.encode(i).inputStream()).readBytes().toString(Charsets.UTF_8)

    private fun input(arms: List<DiagDay.Arm> = emptyList(), decisions: List<DiagDay.Decision> = emptyList(), diary: List<String> = emptyList(),
                      orders: List<DiagDay.Order> = emptyList(), changes: List<ChangeTimeline.Item> = emptyList(),
                      health: Map<String, String> = emptyMap(), workers: List<String> = emptyList(), others: List<String> = emptyList(),
                      personal: List<String?> = emptyList(), tally: DiagErrors.Day? = null, app: String = "1.0 (build abc1234)") =
        DiagDay.Input(day, t(23, 41, 5), app, mapOf("live" to false, "kill_switch" to false), arms, others, decisions, t(9, 0), orders, changes,
            diary, tally, health, workers, personal)

    // ---- names --------------------------------------------------------------------------------------------------------

    @Test fun namesAreStableOnePerDay() {
        assertEquals("diag-2026-10-13.json.gz", DiagDay.assetName(day))
        assertEquals("diag-2026-10", DiagDay.tag(day))
        assertEquals(day, DiagDay.dayOf("diag-2026-10-13.json.gz"))
        assertNull(DiagDay.dayOf("diag-2026-10-13.json.gz.new"))
        assertNull(DiagDay.dayOf("live-2026-10-13.irlive"))
        assertNull(DiagDay.dayOf("diag-2026-13-40.json.gz"))
    }

    // ---- the reasons --------------------------------------------------------------------------------------------------

    @Test fun eachVerdictHasItsReasonKind() {
        val cases = mapOf(
            "entered" to "taken", "entered_live" to "taken", "entered_unconfirmed" to "taken", "holding" to "holding",
            "guard_refused: kill switch is on" to "kill_switch", "guard_refused: day lock: 2 losses" to "day_lock",
            "guard_refused: the app is locked" to "lock", "guard_refused: pre-trade checks failed" to "pretrade",
            "guard_refused: event pause (RBI policy)" to "event", "guard_refused: max 2 trades a day reached" to "cap",
            "guard_refused: something new" to "guard", "refused: no quote" to "no_quote", "refused: spread too wide" to "liquidity",
            "stopped_for_today" to "day_lock", "skipped_by_flow" to "flow", "liquidity_no_room" to "liquidity",
            "side_cap_reached" to "cap", "side_cap_reached: CE" to "cap", "day_limit_reached" to "cap",
            "opposite_position_open: ORB Sweep holds PE" to "exposure", "approval_lapsed" to "lapsed", "skipped_by_you" to "skipped_by_boss",
            "order_refused: margin" to "order_refused", "account_changed: Live was switched off" to "refused",
            "done_after_trade_manager_exit" to "manager_done", "no_contract" to "no_contract", "error" to "error", "error: timeout" to "error",
            "awaiting_approval" to "awaiting_approval", "flat_after_square_off" to "outside_hours",
            "liquidity_outside_entry_hours" to "outside_hours", "no_decision_bar" to "outside_hours", "liquidity_history_loading" to "loading",
            "no_BANKNIFTY_bars" to "no_data", "inside_range" to "no_signal", "no_liquidity_break" to "no_signal",
            "waiting_for_opening_range" to "no_signal", "waiting_for_fresh_break_after_pause" to "no_signal", "hero_no_signal" to "no_signal",
            "something else" to "other", "" to "other",
        )
        for ((v, k) in cases) assertEquals(k, DiagDay.reasonKind(v), v)
        assertEquals("taken", DiagDay.outcome("taken"))
        assertEquals("skipped", DiagDay.outcome("guard"))
        assertEquals("skipped", DiagDay.outcome("lapsed"))
        assertEquals("waiting", DiagDay.outcome("no_signal"))
        assertEquals("waiting", DiagDay.outcome("awaiting_approval"))
        assertEquals("error", DiagDay.outcome("error"))
    }

    // ---- an arm's day -------------------------------------------------------------------------------------------------

    private val liq = DiagDay.Arm(
        key = "liquidity", label = "Liquidity 15+5", armed = true, liveCleared = false, parkedIndices = listOf("FINNIFTY"),
        status = "holding",
        today = listOf(
            DiagDay.Trade("liquidity15", false, "CE", t(10, 5, 12), 200.0, t(10, 40), 230.0, "target", source = "Strategy: Liquidity 15+5"),
            DiagDay.Trade("liquidity5", true, "PE", t(11, 0), 100.0, t(11, 20), 90.0, "stop", lots = 2.0, source = "Strategy: Liquidity 15+5"),
            DiagDay.Trade("liquidity5", false, "CE", t(14, 0), 150.0, source = "Strategy: Liquidity 15+5", trail = true),
        ),
        closed = listOf(
            DiagDay.Trade("liquidity15", false, "CE", t(10, 0, d = day.minusDays(3)), 100.0, t(10, 30, d = day.minusDays(3)), 110.0),
            DiagDay.Trade("liquidity15", false, "CE", t(10, 5, 12), 200.0, t(10, 40), 230.0),
            DiagDay.Trade("liquidity5", true, "PE", t(11, 0), 100.0, t(11, 20), 90.0),
        ),
        aliases = listOf("Liquidity 15m", "liquidity15", "Liquidity 5m", "liquidity5"),
    )

    @Test fun anArmsDayIsCountedTodayAndAllDaysInPointsAndPercent() {
        val decisions = listOf(
            DiagDay.Decision(t(10, 5), "Liquidity 15m", "entered", LocalTime.of(10, 0)),
            DiagDay.Decision(t(10, 50), "Liquidity 15m", "liquidity_no_room"),
            DiagDay.Decision(t(10, 55), "Liquidity 5m", "guard_refused: day lock: 2 losses"),
            DiagDay.Decision(t(10, 57), "liquidity5", "no_liquidity_break"),
            DiagDay.Decision(t(9, 30, d = day.minusDays(1)), "Liquidity 15m", "entered"),     // another day: not counted
        )
        val r = json(input(arms = listOf(liq), decisions = decisions))
        val a = (r["arms"] as List<Map<String, Any?>>).single()
        assertEquals("armed", a["state"])
        assertEquals(listOf("FINNIFTY"), a["parked_indices"])
        assertEquals("holding", a["status_kind"])
        val today = a["today"] as Map<String, Any?>
        assertEquals(4L, today["decisions"].num())
        assertEquals(3L, today["signals"].num())
        assertEquals(1L, today["taken"].num())
        assertEquals(2L, today["skipped"].num())
        assertEquals(mapOf("day_lock" to 1L, "liquidity" to 1L), (today["skips"] as Map<String, Any?>).mapValues { it.value.num() })
        assertEquals(3L, today["entries"].num())
        assertEquals(2L, today["exits"].num())
        assertEquals(1L, today["live"].num()); assertEquals(2L, today["paper"].num())
        assertEquals(1L, today["wins"].num()); assertEquals(1L, today["losses"].num())
        assertEquals(20.0, today["points"])           // +30 and -10 points of premium
        assertEquals(2.5, today["avg_pct"])           // (+15% - 10%) / 2
        val all = a["all_days"] as Map<String, Any?>
        assertEquals(3L, all["trades"].num())
        assertEquals("2026-10-10", all["since"])
        assertEquals(30.0, all["points"])
        val trades = a["trades"] as List<Map<String, Any?>>
        assertEquals(listOf("paper", "live", "paper"), trades.map { it["account"] })
        assertEquals("10:05:12", trades[0]["entry_at"]); assertEquals("10:40:00", trades[0]["exit_at"])
        assertEquals(15.0, trades[0]["pct"]); assertEquals(30.0, trades[0]["points"])
        assertEquals("Strategy: Liquidity 15+5", trades[0]["source"])
        assertEquals(true, trades[2]["open"]); assertNull(trades[2]["exit"]); assertNull(trades[2]["pct"])
        assertEquals(true, trades[2]["trail"]); assertEquals(false, trades[0]["trail"])
        // 18 Oct review: a live trade has no lots (points x lots x lot size was its rupees); a paper one keeps them.
        assertTrue(trades[1].containsKey("lots")); assertNull(trades[1]["lots"])
        assertEquals(1.0, trades[0]["lots"])
        // The decisions list: today's only, oldest first, with each reason's kind and outcome.
        val ds = r["decisions"] as List<Map<String, Any?>>
        assertEquals(4, ds.size)
        assertEquals("10:05:00", ds[0]["at"]); assertEquals("10:00", ds[0]["bar"]); assertEquals("taken", ds[0]["outcome"])
        assertEquals("day_lock", ds[2]["kind"]); assertEquals("skipped", ds[2]["outcome"])
        // No rupee field anywhere in an arm: points and percent only.
        val keys = HashSet<String>()
        fun walk(x: Any?) { when (x) { is Map<*, *> -> x.forEach { (k, v) -> keys += k.toString(); walk(v) }; is List<*> -> x.forEach(::walk) } }
        walk(a)
        assertTrue(keys.none { it.contains("rupee") || it.contains("pnl") || it.contains("charges") || it == "qty" }, keys.toString())
    }

    private fun Any?.num(): Long = (this as Number).toLong()

    @Test fun parkedPausedAndAwaitingStatesAreSaid() {
        val parked = DiagDay.Arm("orb", "ORB", armed = false, parked = true)
        val off = DiagDay.Arm("orb_fresh", "ORB Fresh", armed = false)
        val waiting = DiagDay.Arm("range_fade", "Range Fade", armed = true, automatic = false, pending = true, status = "awaiting_approval")
        val states = (json(input(arms = listOf(parked, off, waiting)))["arms"] as List<Map<String, Any?>>).map { it["state"] }
        assertEquals(listOf("parked", "off", "awaiting_approval"), states)
    }

    @Test fun aTradeHeldOvernightCarriesItsFullTimeAndAZeroPremiumHasNoPercent() {
        val a = DiagDay.Arm("night", "Night", armed = true, today = listOf(
            DiagDay.Trade("night", false, "CE", t(15, 20, d = day.minusDays(1)), 50.0, t(9, 20), 60.0),
            DiagDay.Trade("night", false, "PE", t(10, 0), 0.0, t(10, 5), 1.0),
        ))
        val trades = ((json(input(arms = listOf(a)))["arms"] as List<Map<String, Any?>>).single()["trades"] as List<Map<String, Any?>>)
        assertEquals("2026-10-12T15:20:00", trades[0]["entry_at"])
        assertEquals("09:20:00", trades[0]["exit_at"])
        assertNull(trades[1]["pct"])
        assertEquals(1.0, trades[1]["points"])
    }

    // ---- the diary ----------------------------------------------------------------------------------------------------

    private val diary = listOf(
        "10-12 10:05:00 [orb] ORB: entered · range 51200.0-51000.0 · bar 10:00 close 51250.5",       // yesterday: not read
        "10-13 10:05:01 [orb] ORB: guard_refused: kill switch is on · range 51200.0-51000.0 · bar 10:00 close 51250.5",
        "10-13 10:10:01 [orb] ORB Sweep: no_sweep",
        "10-13 10:11:00 [orb] ORB Fresh: the trade manager's lock raised the paper stop to 210.50",   // a notice, not a decision
        "10-13 10:11:30 [orb] Liquidity 15+5: size 1 lot -> 2 lots (you)",                          // a notice too
        "10-13 10:12:00 [orb] Liquidity 15m: entered",                       // kept in memory too: not counted twice
        "10-13 10:20:00 [error] Missing required field: quantity",
        "10-13 10:21:00 [error] Missing required field: quantity",
        "10-13 10:22:00 [error] Order 77 failed",
        "10-13 11:00:00 [stream] dropped: no data from Zerodha for 12 s · up 3 min",
        "10-13 11:30:00 [stream] dropped: network lost · up 1 min",
        "10-13 23:40:00 [github] upload skipped this time: the options-lab check failed (rate limit)",
        "10-13 23:41:00 [github] backup: failed: GitHub is having trouble",
        "10-13 23:42:00 [github] live 2026-10-13: 2.1 MB uploaded",
        "not a diary line",
    )

    @Test fun theDiaryGivesDecisionsErrorsDropsAndSyncs() {
        val r = json(input(decisions = listOf(DiagDay.Decision(t(10, 12), "Liquidity 15m", "entered")), diary = diary))
        val ds = r["decisions"] as List<Map<String, Any?>>
        assertEquals(listOf("ORB", "ORB Sweep", "Liquidity 15m"), ds.map { it["arm"] })
        assertEquals("guard_refused: kill switch is on", ds[0]["verdict"])
        assertEquals("kill_switch", ds[0]["kind"])
        assertEquals("10:00", ds[0]["bar"])
        val h = r["health"] as Map<String, Any?>
        val errors = (h["errors"] as Map<String, Any?>).mapValues { it.value.num() }
        assertEquals(2L, errors["missing required field: quantity"])
        assertEquals(1L, errors["order # failed"])
        val stream = h["stream"] as Map<String, Any?>
        assertEquals(2L, stream["drops"].num())
        assertEquals("11:30:00", stream["last_at"])
        val sync = h["sync"] as Map<String, Any?>
        assertEquals(1L, sync["failed"].num()); assertEquals(1L, sync["skipped"].num())
        assertEquals(3, (sync["lines"] as List<*>).size)
        assertEquals(13L, h["diary_lines"].num())
    }

    @Test fun afterARestartTheDiaryKeepsTheDecisionsMemoryLost() {
        // The app restarted at 11:00: Liquidity's memory holds only what came after; its morning is in the diary.
        val lines = listOf("10-13 10:12:00 [orb] Liquidity 15m: entered", "10-13 11:30:00 [orb] Liquidity 15m: liquidity_no_room")
        val i = input(decisions = listOf(DiagDay.Decision(t(11, 30), "Liquidity 15m", "liquidity_no_room")), diary = lines)
            .copy(decisionsSince = t(11, 0))
        val ds = json(i)["decisions"] as List<Map<String, Any?>>
        assertEquals(listOf("10:12:00" to "entered", "11:30:00" to "liquidity_no_room"), ds.map { it["at"] to it["verdict"] })
    }

    @Test fun theDiarysOwnTallyIsPreferredWhenItHoldsTheDay() {
        val tally = DiagErrors.Day(day, mapOf("socket closed" to 7), mapOf("Jarvis words: ij1" to 2))
        val h = json(input(diary = diary, tally = tally))["health"] as Map<String, Any?>
        assertEquals(mapOf("socket closed" to 7L), (h["errors"] as Map<String, Any?>).mapValues { it.value.num() })
        assertEquals(mapOf("Jarvis words: ij1" to 2L), (h["unreadable"] as Map<String, Any?>).mapValues { it.value.num() })
        // Another day's tally (the morning catch-up): counted from the diary instead.
        val h2 = json(input(diary = diary, tally = DiagErrors.Day(day.plusDays(1), mapOf("x" to 1))))["health"] as Map<String, Any?>
        assertEquals(2L, (h2["errors"] as Map<String, Any?>)["missing required field: quantity"].num())
    }

    @Test fun anEmptyDayIsAWholeReport() {
        val r = json(input())
        assertEquals(DiagDay.SCHEMA.toLong(), r["schema"].num())
        assertEquals(DiagDay.KIND, r["kind"])
        assertEquals("2026-10-13", r["day"])
        assertEquals("2026-10-13T23:41:05", r["generated_at"])
        assertEquals("09:00:00", r["decisions_since"])
        for (k in listOf("arms", "decisions", "orders", "timeline", "other_arms")) assertEquals(emptyList<Any?>(), r[k], k)
        val h = r["health"] as Map<String, Any?>
        assertEquals(0L, (h["stream"] as Map<String, Any?>)["drops"].num())
        assertEquals(emptyMap<String, Any?>(), h["errors"])
        assertEquals(emptyMap<String, Any?>(), r["truncated"])
    }

    // ---- orders and the timeline ---------------------------------------------------------------------------------------

    @Test fun ordersCarryTheirSourceAndAccountNeverAnId() {
        val orders = listOf(
            DiagDay.Order(t(10, 5, 12), false, "ORB", "BANKNIFTY25OCT51200CE", "BUY", 30, 30, "COMPLETE", "MARKET", 201.35),
            DiagDay.Order(t(10, 6), true, "Liquidity 15+5", "BANKNIFTY25OCT51200PE", "BUY", 30, 0, "REJECTED", "LIMIT", 0.0,
                "Insufficient funds. Required margin is 12,345.50 but available margin is 1,002.25"),
            DiagDay.Order(t(11, 0), false, "ORB", "BANKNIFTY25OCT51200CE", "SELL", 30, 30, "COMPLETE"),
        )
        val r = json(input(orders = orders))
        val os = r["orders"] as List<Map<String, Any?>>
        assertEquals(listOf("paper", "live", "paper"), os.map { it["account"] })
        assertEquals("BANKNIFTY25OCT51200CE", os[0]["symbol"])
        assertEquals(201.35, os[0]["avg"])
        assertFalse(os[1]["message"].toString().contains("12,345"), os[1]["message"].toString())
        assertFalse(os[1]["message"].toString().contains("1,002"), os[1]["message"].toString())
        assertTrue(os.none { it.containsKey("id") || it.containsKey("order_id") })
        assertEquals(mapOf("live · Liquidity 15+5" to 1L, "paper · ORB" to 2L), (r["orders_by_source"] as Map<String, Any?>).mapValues { it.value.num() })
    }

    @Test fun theTimelineHasTheDaysChangesBossesOwnIncluded() {
        val items = listOf(
            ChangeTimeline.Item("ap:2026-10-13#2", ChangeTimeline.Source.AUTOPILOT, ms(t(12, 0)), "You (logged by Autopilot)",
                "Liquidity paper trail: off → on", "your choice", ChangeTimeline.UndoState.UNDOABLE, "paper", "2026-10-13#2", own = true),
            ChangeTimeline.Item("ap:2026-10-13#1", ChangeTimeline.Source.AUTOPILOT, ms(t(9, 30)), "Autopilot · Flow",
                "Order flow: CONFIRM → SHADOW", "3 losing skips", ChangeTimeline.UndoState.NEEDS_PIN, "live"),
        )
        val tl = json(input(changes = items))["timeline"] as List<Map<String, Any?>>
        assertEquals(listOf("09:30:00", "12:00:00"), tl.map { it["at"] })
        assertEquals(true, tl[1]["own"])
        assertEquals("needs_pin", tl[0]["undo"])
        assertEquals("autopilot", tl[0]["source"])
        assertEquals("Liquidity paper trail: off → on", tl[1]["what"])
    }

    // ---- the size cap -------------------------------------------------------------------------------------------------

    @Test fun longListsAreCutWithACountAndTheFileStaysSmall() {
        val many = (0 until 3000).map { DiagDay.Decision(t(9, 15).plusSeconds(it * 7L), "ORB", "inside_range number $it " + "x".repeat(200)) }
        val orders = (0 until 1000).map { DiagDay.Order(t(9, 15).plusSeconds(it.toLong()), false, "ORB", "BANKNIFTY25OCT51200CE", "BUY", 30, 30, "COMPLETE", message = "y".repeat(250)) }
        val workers = (0 until 500).map { "worker $it " + "z".repeat(250) }
        val i = input(decisions = many, orders = orders, workers = workers)
        val raw = text(i)
        assertTrue(raw.toByteArray().size <= DiagDay.MAX_BYTES, "${raw.length}")
        assertTrue(DiagDay.encode(i).size < 64 * 1024, "${DiagDay.encode(i).size} gz bytes")
        val r = Json.parse(raw) as Map<String, Any?>
        val cut = (r["truncated"] as Map<String, Any?>).mapValues { it.value.num() }
        val kept = (r["decisions"] as List<Map<String, Any?>>)
        assertEquals(3000L, kept.size + cut.getValue("decisions"))
        // The newest are kept.
        assertTrue(kept.last()["verdict"].toString().startsWith("inside_range number 2999"))
        assertEquals(1000L, (r["orders"] as List<*>).size + cut.getValue("orders"))
        assertTrue(cut.getValue("workers") > 0)
        // Each text is cut to its own cap.
        assertTrue(kept.all { it["verdict"].toString().length <= DiagDay.MAX_TEXT })
    }

    // ---- privacy (the test the task asks for) -------------------------------------------------------------------------

    /** A fake set of every secret and money pattern. None may survive into the file. */
    private val pat = "github_pat_" + "11ABCDEFG0" + "a1b2c3d4e5f6g7h8i9j0".repeat(3)
    private val classic = "ghp_" + "Z9y8X7w6V5u4T3s2R1q0P9o8"
    private val jwt = "eyJhbGciOiJIUzI1NiJ9" + ".eyJzdWIiOiJyYXZpIn0" + ".c2lnbmF0dXJlMTIz"
    private val kiteToken = "aB3dE5fG7hJ9kL1mN3pQ5rS7tU9vW1xY"
    private val apiKey = "k9q2w8e7r6t5y4u3"
    private val clientId = "ZQ4821"
    private val name = "Ravikiran Deshmukh"
    private val email = "ravi.d@example.com"
    private val phone = "+91 98765 43210"
    private val orderId = "251013000123456"
    private val account = "9988776655443"
    private val secrets = listOf(pat, classic, jwt, kiteToken, apiKey, clientId, name, email, phone, orderId, account)
    private val sentence = "user $name ($clientId, $email, $phone) token $pat / $classic jwt $jwt access $kiteToken key $apiKey order $orderId " +
        "account $account PIN 4321 balance ₹1,23,456.50 margin available 52,310.25 P&L -13,250 day loss Rs 40,000 and 2,500 rupees, Rs. 999"

    @Test fun nothingPersonalOrSecretSurvivesAnywhereInTheFile() {
        val arm = DiagDay.Arm("liquidity", "Liquidity 15+5 $name", armed = true, status = sentence, parkedIndices = listOf(sentence),
            today = listOf(DiagDay.Trade("liquidity15", true, "CE", t(10, 5), 200.0, t(10, 40), 230.0, sentence, source = sentence)),
            closed = listOf(DiagDay.Trade("liquidity15", true, "CE", t(10, 5), 200.0, t(10, 40), 230.0, sentence)))
        val i = input(
            arms = listOf(arm),
            decisions = listOf(DiagDay.Decision(t(10, 6), sentence, sentence)),
            diary = listOf("10-13 10:07:00 [orb] ORB: $sentence", "10-13 10:08:00 [error] $sentence", "10-13 23:40:00 [github] $sentence"),
            orders = listOf(DiagDay.Order(t(10, 5), true, sentence, "BANKNIFTY25OCT51200CE", "BUY", 30, 30, "COMPLETE", "LIMIT", 201.0, sentence)),
            changes = listOf(ChangeTimeline.Item("ap:1", ChangeTimeline.Source.AUTOPILOT, ms(t(12, 0)), sentence, sentence, sentence,
                ChangeTimeline.UndoState.UNDOABLE, sentence)),
            health = mapOf("order_speed" to sentence, "slippage" to "Slippage: live today 0.45/1.20 pts (0.9/2.4 ticks) n12 · $sentence"),
            workers = listOf(sentence), others = listOf(sentence), personal = listOf(name, clientId, "boss-login", null, ""),
            tally = DiagErrors.Day(day, mapOf(sentence to 1)), app = "1.0 $sentence",
        )
        val raw = text(i)
        for (s in secrets) assertFalse(raw.contains(s, ignoreCase = true), "leaked: $s")
        // Parts of them too: the name's words, the token's tail, the account's digits.
        for (part in listOf("Ravikiran", "Deshmukh", "a1b2c3d4e5f6g7h8i9j0", "98765", "43210", "123456", "4821", "776655", "example.com"))
            assertFalse(raw.contains(part, ignoreCase = true), "leaked part: $part")
        // The final scrub's patterns: no GitHub token, no JWT, no run of 9+ digits, no rupee amount, no figure after a money word.
        assertFalse(Regex("github_pat_|gh[pousr]_[A-Za-z0-9]").containsMatchIn(raw))
        assertFalse(raw.contains("eyJ"))
        assertFalse(Regex("\\d{9,}").containsMatchIn(raw))
        assertFalse(Regex("\\d[\\d ,]{7,}\\d").findAll(raw).any { m -> m.value.count { it.isDigit() } >= 9 }, "a long digit group")
        assertFalse(Regex("(₹|\\bRs\\.?)\\s*[-+]?\\d").containsMatchIn(raw), "a rupee amount")
        assertFalse(Regex("(?i)\\d[\\d,.]*\\s*rupees").containsMatchIn(raw), "an amount in rupees")
        assertFalse(Regex("(?i)\\b(margin|balance|p&l|available)\\b[^0-9\"]{0,24}\\d").containsMatchIn(raw), "a figure after a money word")
        assertFalse(Regex("(?i)\\bpin\\s+\\d").containsMatchIn(raw), "a PIN")
        assertFalse(raw.contains("boss-login"))
        // What research needs is still there: the symbol, the premiums, the slippage in points.
        assertTrue(raw.contains("BANKNIFTY25OCT51200CE"))
        assertTrue(raw.contains("\"entry\":200.0"))
        assertTrue(raw.contains("0.45/1.20 pts"))
        // And the file still parses.
        assertNotNull(Json.parse(raw))
    }

    @Test fun theScrubLeavesMarketWordsAlone() {
        val kept = "ORB: guard_refused: day lock · range 51200.0-51000.0 · bar 10:00 close 51250.5 · BANKNIFTY25OCT51200CE 2 lots, " +
            "NIFTY2510725000PE, CRUDEOIL25NOVFUT, 2026-10-13 23:30, waiting_for_fresh_break_after_pause, R14, G3"
        assertEquals(kept, DiagDay.scrub(kept))
        val codes = "refused_premium_at_or_under_40, park_orb_family_2026_10_13, cash 15:30, 3 required, 2 available"
        assertEquals(codes, DiagDay.scrub(codes))
        assertEquals(Secrets.HIDDEN, DiagDay.scrub(kiteToken))
        assertEquals("key ${Secrets.HIDDEN}", DiagDay.scrub("key $apiKey"))
        assertTrue(DiagDay.scrub("x".repeat(1000)).length <= DiagDay.MAX_TEXT)
        assertEquals("", DiagDay.scrub(""))
    }

    // ---- 14 Oct review: rupees in the other arms' lines, Kite's RMS text, live fills, lower-case ids, short name words -----

    /** The other arms' diagLine() as they are written on the phone (NightArm, VixDivArm, McxPaperArms, GrammarArm). */
    private val night = "Night (R3): armed (paper only) · open 1 · last 7 closed -2,340 after charges · NIFTY entered 25100 CE · BANKNIFTY no_signal"
    private val vix = "VIX divergence: on (paper only) · open 0 · 4 closed 1,180 after charges · NIFTY waiting"
    private val mcx = "MCX paper arms: SILVERM morning on (paper only), open 1, last 3 closed -540 after charges (holding) · " +
        "CRUDEOIL evening off, open 0, last 2 closed 75 after charges"
    private val grammar = "Grammar ideas: 2 of 3 on paper · g12 4 trades -1,250 (holding) · g7 2 trades 380 · 1 stopped"
    private val rms = "RMS:Margin Exceeds,Required:95536.25, Available:32112.60 for entity account-AB1234 across exchange across segment across product"

    @Test fun theOtherArmsLinesLoseTheirRupeeNetAndKeepTheirCounts() {
        assertEquals("Night (R3): armed (paper only) · open 1 · last 7 closed (net hidden) · NIFTY entered 25100 CE · BANKNIFTY no_signal",
            DiagDay.otherArmLine(night))
        assertEquals("Grammar ideas: 2 of 3 on paper · g12 4 trades (holding) · g7 2 trades · 1 stopped", DiagDay.otherArmLine(grammar))
        assertEquals("no figures here", DiagDay.otherArmLine("no figures here"))
        assertEquals("", DiagDay.otherArmLine(""))
        val r = json(input(others = listOf(night, vix, mcx, grammar)))
        val lines = (r["other_arms"] as List<*>).map { it.toString() }
        val all = lines.joinToString("\n")
        for (amount in listOf("2,340", "2340", "1,180", "540", "1,250", "380", "75 after")) assertFalse(all.contains(amount), "leaked $amount: $all")
        assertFalse(Regex("\\d\\s*after charges").containsMatchIn(all), all)
        // The counts and the index level research needs are still there.
        assertTrue(lines[0].contains("last 7 closed") && lines[0].contains("NIFTY entered 25100 CE"), lines[0])
        assertTrue(lines[1].contains("4 closed"), lines[1])
        assertTrue(lines[2].contains("last 3 closed") && lines[2].contains("last 2 closed"), lines[2])
        assertTrue(lines[3].contains("g12 4 trades") && lines[3].contains("1 stopped"), lines[3])
        // Even unstripped (a new arm's line), the scrub itself hides a figure before "after charges".
        val raw = DiagDay.scrub(night)
        assertFalse(raw.contains("2,340"), raw)
        assertTrue(raw.contains("25100"), raw)
    }

    @Test fun anOrdersMessageKeepsOnlyItsReason() {
        assertEquals("RMS:Margin Exceeds,Required", DiagDay.orderReason(rms))
        assertEquals("Insufficient funds. Required margin is", DiagDay.orderReason("Insufficient funds. Required margin is 12,345.50 but available 1,002.25"))
        assertEquals("Order price is out of the circuit range", DiagDay.orderReason("Order price is out of the circuit range"))
        assertEquals("refused_premium_at_or_under_40", DiagDay.orderReason("refused_premium_at_or_under_40"))
        assertEquals("Paper fill at", DiagDay.orderReason("Paper fill at ₹201.35"))
        assertEquals("", DiagDay.orderReason("95536.25 needed"))
        assertEquals("", DiagDay.orderReason(""))
        assertTrue(DiagDay.orderReason("x".repeat(500)).length <= DiagDay.ORDER_REASON)
        // A digit inside a word does not end it; the scrub then takes the id out.
        assertEquals("entity ${Secrets.HIDDEN} blocked", DiagDay.scrub(DiagDay.orderReason("entity ab1234 blocked")))
        // The scrub alone, on the whole RMS text, hides every figure and the id too.
        val s = DiagDay.scrub(rms)
        for (x in listOf("95536", "32112", "AB1234", ".25", ".60")) assertFalse(s.contains(x), "leaked $x: $s")
    }

    @Test fun everyFigureOfAMoneyClauseIsHidden() {
        val cases = listOf(
            "P&L 1,200 (2,300 realised)", "MTM -12,400", "Available: 32112.60", "available margin 52,310.25 and 1,002.25 used",
            "Margin 1,200, 2,300, 3,400", "day loss of 540 today", "net 90", "Brokerage 20 + charges 15.75", "premium paid 12,000",
            "premium received 4,500.50", "cash 32,112.60 and collateral 1,00,000", "unrealised -3,250 · realized 1,100",
            "drawdown 38,500 so far", "profit 2,750", "funds 5k left", "pnl -1.2 lakh", "balance: 1,23,456.50", "INR 4,000",
            "Rs 40,000", "₹ 2,500", "2,500 rupees", "-2,340 after charges", "1,250 P&L today", "Exceeds,Available,32112.60",
            "required=95536 now", "MTM:-12,400;", "loss 1,200 (2,300)",
        )
        for (c in cases) {
            val s = DiagDay.scrub(c)
            assertFalse(Regex("\\d{2,}|\\d,\\d|\\d\\.\\d").containsMatchIn(s), "a figure survived in \"$c\": $s")
        }
        // A lakh-grouped figure is rupees wherever it is.
        assertEquals("moved ${Secrets.HIDDEN} today", DiagDay.scrub("moved 1,23,456 today"))
        // Not money: index levels, ranges, bars, times, counts before a money word, percent and points in a money clause,
        // and words that only contain a money word.
        val kept = listOf(
            "NIFTY 25,100 · range 51200.0-51000.0 · bar 10:00 close 51250.5", "cash 15:30, 3 required, 2 available",
            "drawdown 12% of the peak", "network lost for 12 s", "guard_refused: day lock: 2 losses", "last 7 closed",
            "refused_premium_at_or_under_40", "MTM at 15:20:05", "Safety net with nothing held: every 30 s (every 5 s while held)",
            "memory available 312 MB", "cash for 2 lots", "net of 4 trades",
        )
        for (k in kept) assertEquals(k, DiagDay.scrub(k))
        assertEquals("", DiagDay.hideMoney(""))
    }

    @Test fun clientIdsInAnyCaseAndTheHoldersShortNameWordsAreTakenOut() {
        for (id in listOf("ZQ4821", "zq4821", "Zq4821", "abc123456")) assertEquals("client ${Secrets.HIDDEN}", DiagDay.scrub("client $id"), id)
        // Symbols and short codes are not ids.
        assertEquals("BANKNIFTY25OCT51200CE R14 G3 n12", DiagDay.scrub("BANKNIFTY25OCT51200CE R14 G3 n12"))
        val personal = listOf("Om Prakash Rao", "Om", "Prakash", "Rao", "S")
        val s = DiagDay.scrub("Order by OM for rao's account, Prakash approved · Om Prakash Rao · Omkar Bloom zoom S", personal)
        assertFalse(Regex("(?i)(?<![a-z])(om|rao|prakash)(?![a-z])").containsMatchIn(s), s)
        // A short word goes only as a whole word: other words that hold it stay.
        assertTrue(s.contains("Omkar") && s.contains("Bloom") && s.contains("zoom"), s)
        // A one-letter word is never taken out (it would cut every text apart).
        assertTrue(s.endsWith(" S"), s)
        // A 3-letter name word inside a money word ("Ali" in "realised") neither cuts it nor hides it from the money rules.
        val ali = DiagDay.scrub("realised 2,300 by Ali · valid device", listOf("Ali Dev Sharma", "Ali", "Dev", "Sharma"))
        assertEquals("realised ${Secrets.HIDDEN} by ${Secrets.HIDDEN} · valid device", ali)
    }

    /** 18 Oct review: a part-filled order: paper keeps its quantities, live has none to multiply (its status stays). */
    @Test fun aPartFilledLiveOrderHasNoQuantities() {
        val orders = listOf(
            DiagDay.Order(t(10, 5), true, "ORB", "NIFTY25OCT24500CE", "BUY", 150, 75, "COMPLETE", "LIMIT", 100.0),
            DiagDay.Order(t(10, 6), false, "ORB", "NIFTY25OCT24500CE", "BUY", 150, 75, "OPEN", "LIMIT", 100.0),
        )
        val os = json(input(orders = orders))["orders"] as List<Map<String, Any?>>
        assertEquals(listOf("COMPLETE", "OPEN"), os.map { it["status"] })
        assertNull(os[0]["qty"]); assertNull(os[0]["filled"]); assertNull(os[0]["avg"])
        assertEquals(150L, os[1]["qty"].num()); assertEquals(75L, os[1]["filled"].num())
    }

    @Test fun aLiveOrderHasNoFillPriceAndNoRupeeFigureReachesTheFile() {
        val orders = listOf(
            DiagDay.Order(t(10, 5), false, "ORB", "BANKNIFTY25OCT51200CE", "BUY", 30, 30, "COMPLETE", "MARKET", 201.35),
            DiagDay.Order(t(10, 6), true, "Liquidity 15+5", "BANKNIFTY25OCT51200PE", "BUY", 60, 60, "COMPLETE", "LIMIT", 187.4),
            DiagDay.Order(t(10, 7), true, "Liquidity 15+5", "BANKNIFTY25OCT51200PE", "BUY", 60, 0, "REJECTED", "LIMIT", 0.0, rms),
        )
        val i = input(orders = orders, others = listOf(night, vix, mcx, grammar),
            health = mapOf("zerodha_sync" to "Zerodha: funds 32,112.60 · margin used 95,536.25 · P&L 1,200 (2,300 realised) · MTM -12,400"),
            workers = listOf("Available: 32112.60"), personal = listOf("Om Prakash Rao", "Om", "Prakash", "Rao"))
        val r = json(i)
        val os = r["orders"] as List<Map<String, Any?>>
        assertEquals(201.35, os[0]["avg"])
        assertTrue(os[1].containsKey("avg"))
        assertNull(os[1]["avg"]); assertNull(os[2]["avg"])
        // 18 Oct review: nor a live order's quantities (with a live trade's points they gave its rupees); a paper one keeps them.
        assertTrue(os[1].containsKey("qty")); assertNull(os[1]["qty"]); assertNull(os[1]["filled"]); assertNull(os[2]["qty"])
        assertEquals(30L, os[0]["qty"].num()); assertEquals(30L, os[0]["filled"].num())
        assertEquals("RMS:Margin Exceeds,Required", os[2]["message"])
        val raw = text(i)
        for (x in listOf("187.4", "95536", "95,536", "32112", "32,112", "AB1234", "1,200", "2,300", "12,400", "2,340", "1,180", "1,250"))
            assertFalse(raw.contains(x), "leaked $x")
        assertTrue(raw.contains("BANKNIFTY25OCT51200PE"))
    }

    // ---- the research sample ------------------------------------------------------------------------------------------

    /**
     * research/data/tests/diag_sample.json is the file research's reader is tested on: it must have the keys this report
     * writes, so the reader and the phone never drift apart.
     */
    @Test fun theResearchSampleHasThisSchemasKeys() {
        val f = listOf(File("../../research/data/tests/diag_sample.json"), File("../research/data/tests/diag_sample.json"),
            File("research/data/tests/diag_sample.json")).firstOrNull { it.isFile }
        assertNotNull(f, "research/data/tests/diag_sample.json is missing")
        val sample = Json.parse(f.readText()) as Map<String, Any?>
        val made = json(input(arms = listOf(liq), decisions = listOf(DiagDay.Decision(t(10, 5), "Liquidity 15m", "entered")), diary = diary,
            orders = listOf(DiagDay.Order(t(10, 5), false, "ORB", "BANKNIFTY25OCT51200CE", "BUY", 30, 30, "COMPLETE")),
            changes = listOf(ChangeTimeline.Item("ap:1", ChangeTimeline.Source.AUTOPILOT, ms(t(12, 0)), "Autopilot", "a → b", "why",
                ChangeTimeline.UndoState.UNDOABLE))))
        assertEquals(made.keys, sample.keys)
        assertEquals(DiagDay.SCHEMA.toLong(), sample["schema"].num())
        fun first(m: Map<String, Any?>, k: String) = (m[k] as List<Map<String, Any?>>).first()
        assertEquals(first(made, "arms").keys, first(sample, "arms").keys)
        assertEquals((first(made, "arms")["today"] as Map<*, *>).keys, (first(sample, "arms")["today"] as Map<*, *>).keys)
        assertEquals((first(made, "arms")["all_days"] as Map<*, *>).keys, (first(sample, "arms")["all_days"] as Map<*, *>).keys)
        assertEquals(first(first(made, "arms"), "trades").keys, first(first(sample, "arms"), "trades").keys)
        for (k in listOf("decisions", "orders", "timeline")) assertEquals(first(made, k).keys, first(sample, k).keys, k)
        assertEquals((made["health"] as Map<*, *>).keys, (sample["health"] as Map<*, *>).keys)
    }
}
