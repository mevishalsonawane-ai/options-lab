# Enhancement round 4: RESULT (21 Oct 2026)

Branch `claude/enhance-r4` (based on main `9c42044`, rounds 1-3). Boss, 20 Oct: "run a loop for enhancement and adding new
features in the application." Round 3's ranked items 1-5; encrypted diagnostics still waits on Boss. The parallel branch's
areas (DayLockGuard, McxPaperArms, the Candle agent's staleness check, the Jarvis words worker, the arms' status lines) were
not touched.

## What was added (plain words for Boss)

1. **Stop and target as one GTT, in the day too (item 1).** A carried (NRML) position's stop and target rest in the day as two
   orders, and the app cancels one when the other fills. With the phone dead, both stayed: if the stop filled, the target
   could fill later and open a short. Switched on, they rest all day as ONE two-leg GTT at Zerodha, which cancels the other leg
   itself. The overnight GTT is simply kept at the open; a trailing stop moves the GTT in place; a part sold by hand shrinks it
   (or, if Zerodha will not shrink it, it gives way to two orders for what is held); closing the position deletes it.
   **Switch: Bot settings → Stop and target as one GTT. OFF by default; switching it on takes your PIN.** The trade-off, said on
   the card: a GTT's stop is checked by Zerodha's server and then sends a limit order 5% past the trigger, which can miss in a
   sharp gap; an SL order rests at the exchange. Intraday (MIS), stop-only and target-only protections are unchanged.
   Found while building it and fixed: the 15:05 expiry square-off and MCX's expiry exit cancelled resting orders before selling
   but never GTTs; a protection's GTT is now deleted (confirmed) first, so it can never fire after the position is sold.
2. **A stop resting since the morning is never sold twice (item 2).** Round 3's guard against Zerodha's positions trailing a
   filled exit woke only for exits this phone sent in the last minute. It now also wakes when any exit order filled at the
   exchange a moment ago, by the order's exchange update time. **Same switch as round 3 (Bot settings → One exit at a time →
   Never sell a filled exit twice), still in SHADOW by default**: it only counts and logs until you switch it on, and on, it
   only ever sends less.
3. **Payouts counted in the drawdown (item 3).** Money taken out of Zerodha lowers the next day's opening balance, which
   reads as a drawdown from the peak and can turn the kill switch on. The app now keeps each day's payout (Kite's figure) with
   the peak that stood then. **Switch: Bot settings → Payouts and the drawdown. In SHADOW by default (logged only).** On, when
   a drawdown stops live entries and the payouts alone explain it, the refusal says so and the card offers "Lower the peak by
   Rs N (PIN)". It never clears the kill switch and never keeps it from going on: you clear it yourself if you agree.
4. **A real two-book twin test (item 4).** A test day where both Liquidity BANKNIFTY books (5 and 15-minute) read their own
   charts and break the same level on the same minute, so both want the same 54,000 call. With the no-twin rule on, one buys
   and the other is a would-be trade; off, both buy, each sells only its own lot, both stops filling together leave nothing
   short. **No bug found in the books.**
5. **App tests no longer depend on the hour (item 5).** Every app test now starts with the market's clock at noon IST on a
   trading day (still ticking); a test that needs another time sets it itself, and setting it back returns to noon, not the
   wall clock. Applied to every app test class (through the two shared bases and 94 classes directly). No test was skipped,
   disabled or loosened.
6. One What's New entry for round 4.

**Switches and defaults:** Stop and target as one GTT - off (PIN to switch on). Never sell a filled exit twice - shadow (as
round 3). Payouts and the drawdown - shadow. Nothing new acts on a live trade until you switch it on.

## Safety

- Live changes only lower risk or wait for you: the day GTT is off until you switch it on with the PIN; the lag guard and the
  payout drawdown stay logged only until you switch them on; the payout drawdown never clears or holds off the kill switch.
- Nothing on the order or tick path touches disk, the settings vault or the network beyond Zerodha's own reads: the guard's
  payout check is memory only (tested: no vault reads); payouts are recorded and written on a worker thread.
- Never two exits that could both fire: a day GTT's answer lost places nothing else beside it (it is found on the next look);
  your own exit from Trade counts the protection's GTT as an exit resting (as its two orders were); after a restart the switch
  is never read as "off" before the settings are read; expiry exits delete a protection's GTT (or cancel a just-fired one's
  sell) before they sell.
- Open positions first: protections are watched as before; a stream look trails a day GTT from the fresh tick.

## Tests

- **Checks run locally (CLAUDE.md):** `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification`
  green: engine 1,479 tests, ira-core 3,848, coverage gate passed.
- **The whole app suite, as CI runs it** (4 shards, `-PtestsWithoutJarvis=true`, an Android SDK set up here): 4,168 tests, 0
  failures (164 skipped by their own conditions, e.g. Jarvis-only or IraGoldAlgo-only). A first run without shards hit the
  task's 20-minute limit; the shards each finish in 8-20 minutes.
- New: ProtectionsDayOcoTest (34: switch and PIN, placing, the stop firing with the phone off, fired but unfilled, kept at the
  open, a lost answer, trail, shrink and its refusal, switch off in and out of hours, the unread switch after a restart,
  deleted on Kite's site, the stop move, the expiry square-off, a just-fired GTT at expiry), PayoutWatchTest (13),
  LiquidityTwinDayTest (4), EnhanceRound4CardsTest (Compose, 4), MarketClockRuleTest (4), OrderFlowLive2Test (+1: your exit
  counts the protection's GTT), ExitCoordinatorTest (+7: a resting stop's fresh fill, on and in shadow, and at expiry);
  ira-core PositionsLagRestingTest (11), PayoutDrawdownTest (14), WhatsNewChangelogTest (+1; positions +1), BatteryRestTest.
- Found by the new tests and fixed: Zerodha answers an earlier session's order id with an error, not an empty history, so a
  GTT that fired yesterday could leave its protection stuck "being removed" (the overnight GTT's code had the same gap).
- Flaky once, not mine: PassedArmsHostTest reached Zerodha's real address after its fake closed (a background read finishing
  late); it passed alone (57 tests) and in the shard's re-run.
- **CI on `81b38fc`:** everything green (engine, PC harness, Build APK, app shards 0, 2, 3) except app shard 1: BrokerScreensTest's
  live check dated its fake candle by the wall clock while the check judges its age by the market clock, which the new rule pins
  to noon; CI ran at 00:23 IST, so the candle looked 11 hours old. The test now dates it by the market clock. The whole app
  suite was then run again locally past midnight IST (the same condition): 4,168 tests, 0 failures.
- **CI on `c152d0b`: all green** (engine, PC harness, Build APK, all four app shards). App shard 2 failed once on
  TradeDialogsLayoutTest's live-orders screen (a tap before the "Orders 1" tab had merged: Trade screen code this round did not
  touch; the same shard passed on the run before and twice locally) and passed on its one re-run.
- No test was skipped, disabled or loosened. One test's meaning was kept with one line: MarketTest's "no test clock reads the
  system clock" now clears the rule's fallback first (as in the app, where both are always empty).

## Review

A separate reviewer agent read the whole diff (code only). It confirmed eight findings; all are fixed, with tests:
1. A lost GTT answer fell back to two orders while the GTT might exist (three exits resting): now nothing else is placed; the
   next look adopts the GTT (or places it) - same for the open and the watch.
2. Your manual exit no longer saw the protection's GTT as an exit resting: it now counts, as the two orders did.
3. After a restart the switch read "off" until the settings landed, and the first look turned every day GTT back into two
   orders: now only a switch read as off does that.
4. A GTT that could not be shrunk (the price at a leg) stayed larger than held: now it gives way to two orders for what is held.
5. A GTT that fired but whose order ended with the session was "resting unfilled" forever: now said plainly and finished.
6. The expiry exits could miss a GTT placed a moment earlier, one being removed, or one that had just fired: all covered now.
7. A fire that left a short was said as "closed": now said as a position the other way round.
8. Wording for a GTT that sold only part.
Also taken from its notes: a payout requested and cancelled the same day is no longer counted (the latest reading is kept,
a zero removes the day); the drawdown's figures are kept as one snapshot. Noted, not changed: a same-day NRML/MIS conversion
can make the lag guard (when on) count a fill as sold that the positions moved to the other product (pre-existing for the
phone's own exits; the expiry square-off would then send less, never more); a stream look's trail modifies the GTT over the
network, as round 2's stream trail already modifies the SL order.

## Boss: switches to consider

- **Stop and target as one GTT:** worth it if the phone may be off while a carried position has both a stop and a target; the
  cost is the GTT stop's limit order in a gap.
- **Payouts and the drawdown:** switch on before your next withdrawal; it only adds words and the PIN offer.
- Still in shadow from earlier rounds: One exit at a time; Never sell a filled exit twice; the account stop rule.

## Next: ranked ideas for round 5

1. **Day GTT diagnostics line with counts** (placed, kept at the open, trailed, fired, replaced by orders) so Boss can judge it
   after a week on.
2. **The same lag guard for paper** (the paper engine's books never trail, so measure first; likely nothing to do).
3. **Lag guard and product conversions:** match a fill to the positions row across NRML/MIS on a day with a conversion.
4. **A GTT for the arms' intraday stops on a dead phone** (they rest as SL orders now; an OCO is moot without a target, so
   measure first how often an arm's own exit and stop both rest).
5. **Payout check against the next day's opening-balance drop** (confirm a payout really left before offering the peak).
6. **Encrypted diagnostics** (waits for your choice).
