# Enhancement round 3: RESULT (21 Oct 2026)

Branch `claude/enhance-r3` (based on `113c7a5`: the deep-review fixes, round 1 and round 2). Boss, 20 Oct: "run a loop for
enhancement and adding new features in the application." Item 0 first (the flaky clock-dependent app tests), then round 2's
ideas 1, 3, 7 and 6.

## Item 0: flaky clock-dependent tests

**How they were found.** Not by reading alone: every test JVM was run with its wall clock forced (libfaketime under the
test JVMs only, through a local Gradle init script; nothing committed) at these IST times, on a whole suite each:

| Clock (IST) | engine + ira-core | app |
|---|---|---|
| Sat 24 Oct 12:00 (weekend) | 0 failures | _see below_ |
| Sun 25 Oct 18:00 (Sunday review hour) | 1 (a 2 s timeout under load; passes alone at the same time) | _see below_ |
| Wed 21 Oct 00:05 (just after midnight) | 0 | _see below_ |
| Wed 21 Oct 05:59 (Zerodha's token rolls over at 06:00) | 0 | _see below_ |
| Wed 21 Oct 09:20 (just after the open) | 0 | _see below_ |
| Wed 21 Oct 15:20 (after the 15:15 MIS cut-off) | 0 | _see below_ |
| Wed 21 Oct 15:45 (after F&O's 15:40 close) | 2 (micro-benchmarks under load; pass alone at the same time) | _see below_ |
| Wed 21 Oct 23:40 (after MCX's close) | 0 | _see below_ |
| Tue 27 Oct 15:07 (NIFTY expiry, after the 15:05 square-off) | 0 | _see below_ |
| Tue 10 Nov 11:00 (an NSE holiday) | 0 | _see below_ |

The CI failures themselves (the test reports of the three failed runs) were read too.

**Found and fixed.**
1. **Zerodha's 06:00 token roll-over (the 20 Oct ~23:00Z / 21 Oct 00:30Z failures).** The fake Zerodha's made-up login was
   dated "now"; Kite's token dies at the next 06:00 IST, so a test run started at 05:59 IST was logged out a moment later:
   OrderLatencyTest ("Log in to Zerodha for today first"), BrokerScreensTest (timed out waiting for the contracts) and
   TodayGlanceTest failed in the same shard at 06:00 IST. Fix: `FakeKite.loginAt` dates the made-up login just after 06:00
   when it is within two hours of it, so its token lasts to the next day (every test that logs in through FakeKite).
2. **ControlScreensLayoutTest.crashReport** (21 Oct 00:05Z, "Unable to find the image of the target root component"): not
   the clock but a race of the same kind - the crash dialog reads its file in the background and could open between the
   screenshot's look at the windows and the picture. It now waits for the dialog, then takes the screenshot.
3. Already fixed before this round (20 Oct): ExposureSpeedTest and LiveSelfTestTest (pinned to noon IST).

**Checked and not clock-dependent:** SupervisorTest, AutoChangeTest and SmartWorkersSpeedTest failed once each in the sweep,
only while two builds ran at once (and the faked clock adds its own cost to every clock read); each passed alone at the
same forced time. No test was skipped, disabled or quarantined.

## What was added (plain words for Boss)

1. **A filled exit is never sold twice (idea 1).** Zerodha's order book can show an exit COMPLETE a moment before its
   positions move. In that moment a second exit (an arm's, a stop, the expiry square-off) saw the old position and nothing
   working, and sold it again - a short. Now every exit sender compares the two books: when the positions trail a fill of
   an exit this phone just sent, they are read again (about a second at most), and if they still trail, that fill is counted
   as sold. It only ever sends less, never more, and never refuses an exit of what is still held.
   **It starts in shadow** (it only counts and logs) until you switch it on: Bot settings → One exit at a time → "Never sell a
   filled exit twice". The daily diagnostics have one line for it. Covered: the arms' live exits and their stops, ORB and
   Liquidity, Pine, the strategies, your protections, the 15:05 expiry square-off and MCX's expiry exit.
2. **Paper positions stay on the live stream (idea 3).** Your open paper positions and working paper orders were kept on
   Zerodha's price stream only by being priced (each price read kept them for three minutes). Now they are followed for as
   long as they are open, first in line with your live positions, and let go when they close.
3. **Sunday review catch-up (idea 7).** If the phone was off at 18:00 on Sunday, or locked (you got only the "ready" notice),
   Jarvis says the review the next time you unlock the phone that evening - once, never on a locked phone, never before
   18:00 or after midnight, and not again if you already asked for it.
4. **Copied diagnostics marked private (idea 6).** The diagnostics and the crash report go to the clipboard marked
   sensitive, so Android 13+ hides their preview.
5. **Liquidity BANKNIFTY's live stop rule keeps its whole record (idea 6, round 1's idea 14).** The arms' book keeps its last
   2,000 rows; a busy paper stretch could push an old live trade out at the next restart, and the rule (-Rs 40,000 from its
   best, -Rs 13,000 a month) could forget a loss it had counted. Every live row is kept now.

Not done this round: **idea 4 (stop and target as one OCO at Zerodha).** It means replacing the exchange-resting SL order with
a GTT trigger (checked by Zerodha's server, then a limit order that can miss in a gap), leftover GTTs after an MIS square-off,
and GTT moves for trailing stops. That changes how every live stop rests, so it needs your choice first (see round 4).

## Safety

- Nothing new skips or resizes a live trade by itself: the lag guard is off (shadow) by default and, on, only lowers what is
  sent; its reads again stop at 1.2 s so a turn never nears the coordinator's 4 s wait.
- The order path reads memory only: no vault reads in the guard (tested), the paper follow is memory only.
- Open positions first: paper positions now have their own place at the front of the stream; nothing slows the watch.
- Nothing about the account is spoken on a locked phone (the catch-up reads the lock before and as it speaks; tested).

## Tests

- **ira-core:** PositionsLagTest (14: longs and shorts, part fills, two sells, only this phone's recent exits, unknown day
  quantities, shadow, read again, counted as sold, the time budget on a fake clock), SundayReviewTest (the catch-up's hours),
  WhatsNewChangelogTest (the new entry; indices +1), BatteryRestTest (+1).
- **app:** ExitCoordinatorTest (+11: shadow, read again, counted as sold, no recent exit, the expiry pass on and in shadow, a
  pass with nothing of ours reads once, a null day quantity, no vault reads, the switch), ArmLiveTest (+2: a real arm exit with
  the positions trailing, on and in shadow), PaperStreamOwnerTest (4), IraSundayReviewTest (+5, run in the Jarvis build too:
  12 of 12), SensitiveClipTest (2), GoldScreensTest (the copy marked), EnhanceRound3CardsTest (Compose: the switch),
  LiquidityPaperTestAppTest (+2: the trim keeps live rows), ControlScreensLayoutTest (the crash dialog waited for).
- **Checks run locally:** `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification` green.
  App tests for everything touched green with CI's flags (about 400 in the targeted runs).
- **CI:** _see below_.

## Review

A separate reviewer agent read the whole diff. No high-severity finding; the confirmed ones were fixed, with tests:
- The expiry square-off and MCX's expiry exit were not guarded (medium): both now are, from their own pass's read.
- With the guard on, a turn could last past the coordinator's 4 s wait (medium): the reads again now stop at 1.2 s.
- A JSON null day quantity read as 0 (could have under-sold): now "not known".
- The Sunday catch-up could race the 18:00 run and say it twice: one lock for both; muted or quiet hours skip its reads.
- The fast path's paper look could wait on a paper save: it now follows only when the lock is free.
- A part sale's stop grown back could cover more than held: capped at what Zerodha holds.
- A test-only reset lacked its debug check; a timing assertion depended on the wall clock: both fixed.
- Noted, not changed: a re-read may join a positions read another worker began up to 2 s earlier (the guard then counts the
  fill as sold, the safe side); same-day MIS↔NRML conversions are not modelled (the guard would send less, not more).

## Boss: switches to consider

- **Bot settings → One exit at a time → Never sell a filled exit twice.** Watch its diagnostics line in shadow for a few days
  ("a positions read trailing an exit's fill N"); if N is not zero, switch it on.
- Still in shadow from earlier rounds: One exit at a time; the account stop rule.

## Next: ranked ideas for round 4

1. **OCO at Zerodha (idea 4), with your choice:** a two-leg GTT for carried (NRML) protections in the day too, off by default,
   so a dead phone never leaves both the stop and the target resting after one fills.
2. **Lag guard for a resting stop's fill** (not only exits sent in the last minute): use the order's exchange update time.
3. **Payout-aware drawdown** for the account guard's kill switch (Kite's utilised payout), with your confirmation.
4. **A real two-book twin test:** both Liquidity books signal the same option on one test day.
5. **A clock rule for the app tests:** a JUnit rule that pins Market.testClock by default, so a new test cannot depend on the
   hour by accident.
6. **Encrypted diagnostics** (waits for your choice).
