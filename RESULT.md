# Enhancement round 5: RESULT (21 Oct 2026)

Branch `claude/enhance-r5` (based on integ-r4 `d6feb0e` = main + round 4 + research). Boss, 20 Oct: "run a loop for enhancement
and adding new features in the application." Round 4's ranked items 1-5. Encrypted diagnostics still waits on Boss. This is the
last round before the coordinator asks Boss whether to go on.

## What changed (plain words for Boss)

1. **"Stop and target as one GTT" is now counted (item 1).** Bot settings' card and the diagnostics show today's counts and the last
   seven days': how many protections were placed as one GTT, kept at the open, trailed, shrunk, fired at Zerodha, replaced by two
   orders, or found gone at Zerodha. After a week on, these numbers tell you whether to keep it. The counts are kept on the phone
   (written in the background) and survive a restart. The line lives in a diagnostics helper, not in the big report function (one
   more line there broke the APK build twice before).
2. **Paper needs no lag guard (item 2, measured, nothing built).** On paper, an exit's fill and the position change in one step,
   so a reader never sees a filled exit with the old position. A new engine test proves it for a market exit, a resting stop, and
   a busy day across NRML and MIS. Two paper-only gaps turned up while measuring (not lag): see round 6, ideas 1 and 2.
3. **The "never sell a filled exit twice" guard now handles a conversion (item 3).** If you convert a position between NRML and MIS
   on the day an exit fills, Kite shows that sell in the other product's row. The guard could read that as "positions still behind"
   and, when switched on, send less than you hold. It now compares the whole contract across both products too. When it suspects a
   conversion, it reads the orders once more (never a shared older read) before trusting it. So a real intraday sell that lands in
   between can never hide a real lag. **Same switch as before (Bot settings → One exit at a time → Never sell a filled exit twice),
   still in SHADOW by default.** When on, it only ever counts less as sold than round 4 did, never more. The diagnostics line counts
   the conversions it saw, and the diary records both products' rows each time, so a week of logs can check that Kite really
   behaves this way.
4. **No GTT for the arms' intraday stops (item 4, measured, nothing built).** No arm ever rests a target beside its stop. Every arm
   (ORB, the Liquidity books, Pine, the strategies, the mirrored arms) rests exactly one exit at Zerodha: an SL order for MIS, or a
   single-leg GTT for NRML (Night, MCX). Its target, time and trail exits are sent from the phone only after the stop is confirmed
   out. With the phone dead, only the stop rests, and Zerodha's MIS square-off closes what is left. Nothing can fill twice, so the
   GTT has nothing to fix. An SL order at the exchange is also better than a GTT, which can miss in a gap. The one dead-phone
   two-order case is outside the arms: an intraday (MIS) protection that you set with both a stop and a target. See round 6, idea 3.
5. **A payout counts only once the money has really left (item 5).** Every margins read now keeps the day's opening balance, and
   what the next opening should be if no money moved (opening + realised P&L − premium of options bought + money added). A payout
   is confirmed when the next trading day's opening balance is lower by at least the whole payout (charges only make the drop
   bigger). A payout cancelled after the day's last read never confirms. Unconfirmed payouts are said ("the next day's opening
   balance has not shown Rs N leaving") and are never offered. Reads before 08:30 IST or on a holiday are ignored (they can still
   show the old opening). **Same switch as round 4 (Bot settings → Payouts and the drawdown), still SHADOW by default.** This only
   makes the offer rarer. It never clears the kill switch, and lowering the peak still takes your PIN.
6. **Two test races fixed (CI).** The live-orders layout test tapped "Orders 1" before the chip had its count (the Compose idling
   failure in round 4's CI). It now waits for it, as its sibling test already did. OrderPathRound2Test built "yesterday" from the
   wall clock while the app reads the market's clock (pinned to a trading day's noon). On a Saturday, or the day after a holiday,
   both were the same day and the test failed. It now uses the market's day before. No test was skipped, disabled or loosened.
7. One What's New entry for round 5 (newest; the WhatsNewChangelogTest and BatteryRestTest positions moved by one).

**Switches and defaults:** nothing new acts on a live trade on its own. Stop and target as one GTT: off (PIN to switch on), now with
counts. Never sell a filled exit twice: shadow (as rounds 3-4), now conversion-aware. Payouts and the drawdown: shadow, now
confirmed by the next opening balance.

## Safety

- **Live changes only lower risk.**
  - The lag guard's conversion check can only lower what it counts as sold, and only when a fresh orders read confirms it. A
    failed read keeps round 4's behaviour.
  - The payout check can only withhold an offer, never add one.
  - Nothing new turns on by itself.
- **Order and tick path stay clean.** Nothing on it touches disk, the settings vault or the network beyond Zerodha's own reads:
  - The day-GTT counts are memory, written on a worker thread.
  - The guard's payout check reads memory only (tested: no vault reads, with balances too).
  - Balances are recorded and written in the background.
  - The confirming orders read is one more Zerodha read, made only when a conversion is suspected and the switch is on.
- **Open positions still come first.** No watch, stop, target, trail or guard check was slowed or reordered.

## Tests

- **Core checks (CLAUDE.md):** `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification` all green.
  - engine: 1,497 tests;
  - ira-core: 3,891 tests;
  - coverage gate passed.
- **New tests:**
  - ira-core: DayGttTallyTest (6), PositionsLagConversionTest (8), PayoutLeftTest (9), WhatsNewChangelogTest (+1).
  - engine: PaperNoPositionsLagTest (3).
  - app: EnhanceRound5CardsTest (Compose, 3); ExitCoordinatorTest (+5: a conversion on and in shadow, a real lag beside another
    product's row, another product's later fill never hiding a lag, the fresh confirming read).
  - app: PayoutWatchTest (+8: not seen leaving, not read yet, shadow, confirmed, balances kept, the expected opening, no vault
    reads, a read before the morning run).
  - app: ProtectionsDayOcoTest (+2, and the count checked in 9 existing cases: placed, replaced, fired, kept, trailed, shrunk,
    replaced on switch-off, gone, placed at the open).
- **Targeted app run** (the changed classes plus the guard's, OrderFlowLive2Test and the trade layouts): 225 tests, 0 failures
  (12 skipped by their own conditions).
- **Full app suite:** CI ran all four shards on the pushed code, all green (below). Locally, shard 0/4 also ran in full: 940 tests,
  0 failures (51 skipped by their own conditions).
- **Local set-up note:** Maven Central refused this machine's downloads (HTTP 429), so a local Gradle init script (not committed)
  pointed Gradle and Robolectric at Google's mirror of Maven Central.

## Review

- **Self-review:** the full diff, line by line.
- **Separate reviewer agent:** read the whole diff. It confirmed the two safety directions: the conversion check never counts more
  as sold, and the payout check never offers more. Findings, all fixed with tests:
  1. Another product's sell filling after the orders read could hide a real lag. A suspected conversion is now confirmed on a fresh
     orders read (which never joins an older shared read).
  2. To check the conversion model on live logs, each case now records both products' rows in the diary.
  3. Balances were kept 10 days and payouts 60, so a confirmed payout could fall back to "not read yet". Balances are now kept as
     long as payouts.
  4. The 5% tolerance made a wrong "confirmed" easier. It is now one-sided: the whole payout must show, less rupee rounding.
  5. A read past midnight or on a holiday could fix a stale opening balance. Such reads are now ignored.
  6. Stale "waiting" words could linger; they are now cleared.
  7. Two saves close together could be written in reverse order. They are now saved under the lock.
  8. Counts seen before the start-up read were not written. They are now written once it lands.
  9. Plainer What's New wording.
- **Noted, not changed:**
  - A fired GTT that has already dropped off Kite's list by the next look is counted as neither fired nor gone (an undercount, in
    the diagnostics only).
  - A loss after the day's last read can still confirm a payout that never left. The offer stays Boss's call, with the PIN.

## CI

**CI on `e313949` (the code of this round): all green.**
- Passed: app tests shards 0-3, Build APK, engine parity with the PC, PC harness tests, TDLib.
- Screenshots publish: skipped by its own rule.
- First try, no re-runs.

## Next: ranked ideas for a possible round 6

1. **Paper protection's two orders can both fill (paper only).** A paper protection rests a stop and a target together. The other
   one is cancelled only at the protection's next look, so two quick price passes can fill both and leave a paper short. Fix: an
   OCO group inside the paper engine (cancel the sibling in the same step as the fill).
2. **Paper exits check, then sell, in two steps.** Two paper senders on one contract can both pass the held check and both sell
   (only `Paper.close` is atomic). Fix: an "exit no more than held" check inside the paper book's own lock.
3. **An intraday protection with a stop and a target on a dead phone.** Boss's MIS brackets and Jarvis's paper-to-live news trades
   rest an SL and a LIMIT as two DAY orders, and with the phone dead both can fill. Whether Zerodha takes MIS in a GTT must be
   checked first; otherwise the options are: no target order for MIS (target watched by the app), or a warning on the bracket ticket. Shadow first.
4. **One clock for app tests.** FakeKite stamps orders with the wall clock while the app reads the pinned market clock.
   AutomationSupport's arm clocks and several tests also build "today" from the wall clock. Unify on the market clock (it removes
   the remaining weekend/holiday/hour-dependent failures). Also make FakeKite's `close()` point at a dead local endpoint, and drain
   the background jobs before closing.
5. **Latency budgets as properties, not milliseconds.** OrderPathRound2Test, PositionsFirstTest, OrderLatencyTest and
   PassedArmsHostTest assert wall-clock budgets (300 ms and so on). Prove "no network read between the tick and the sell" instead,
   and keep the timings as logged trends.
6. **Encrypted diagnostics** (waits for your choice).
