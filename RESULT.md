# Enhancement round 1: RESULT (20 Oct 2026)

Branch `claude/enhance-r1` (based on main `f724ec5`, the deep-review fixes). Boss, 20 Oct: "...after that run a loop for
enhancement and adding new features in the application." This round took six of the deep review's ranked ideas
(2, 3, 4, 6, 7 and 9). Idea 1 (one exit coordinator) is left for a later round, as asked. Idea 5 (encrypted diagnostics)
was not done: see "Next" below.

## What was added (plain words)

1. **Overnight stops at Zerodha (idea 2).** When you protect a position you carry overnight (NRML, not intraday), its stop
   and target used to be day orders that Zerodha cancels at the close. Now, once they end, the app rests the same stop and
   target at Zerodha as one GTT, so they hold overnight and at the open even if the phone is off. At the next open the
   GTT is deleted first (and checked gone), then the day's orders go back as before. Two exits never rest together, and
   the GTT is deleted whenever the protection ends or the position is closed. The watch now stays for up to half an hour
   after the 15:40 close, looking only at stops and targets, until the GTT is placed. It only ever adds a stop.
   Jarvis's live trades use these protections, so they get it too. The Strategy Module's carried legs and the arms' live
   trades already had their own GTTs.
2. **Liquidity no-twin rule (idea 3, paper).** On 19 Oct both Liquidity BANKNIFTY books held the same put and were
   stopped together. Now, on paper, if one Liquidity book already holds an option, the other book's entry on that same
   option is not bought: it is kept as a would-be trade, counted apart, so the plain-rules record for the 26 Oct decision
   stays separable (the evening replay still replays every signal). The two books still trade side by side on different
   options. Live is never changed: a live twin is only logged. It is on for paper from this update, logged in the
   Autopilot change timeline, and one tap undoes it (or the switch on the Strategy lab card).
3. **Account stop rule over the whole account (idea 4).** Your rule (-₹40,000 from the best, or -₹13,000 in a month) now
   watches the whole Zerodha account's daily P&L (Zerodha's own day figure, before charges), not just the arms' trades.
   Once reached, it can refuse the bots' new live entries and their automatic live strategy starts. It never touches your
   own orders or any exit. It starts in **shadow**: it only logs what it would refuse until you switch it on in
   Bot settings → Account stop rule. Restarting its count after it is reached takes your PIN.
4. **Stop checks timed honestly (idea 6).** While anything is held, the 15-second stop check now runs every 15 s from
   start to start (it used to wait 15 s after each check finished, so a 3 s check made 18 s). Every gap over 16 s is
   counted and logged; one of 30 s or more is also shown in the app. The daily diagnostics say how many gaps went over.
5. **One "is anything held?" check (idea 7).** Four slightly different lists decided whether something was held (that
   is how the T3 gap happened). Now one shared check decides for the watch's pulse, the 15-second stop check, the arms'
   waits and the smart scheduler. It keeps every source each list had, so nothing is checked less often; a book not read
   yet still counts as held. The diagnostics show what counts as held.
6. **Cap "only logged" count (idea 9).** The daily diagnostics now count the entries the same-direction cap only logged
   and let through (paper entries that could go live, live entries in shadow), apart from the ones it refused, so a
   silent bypass like 20 Oct's shows at once.

One What's New entry (6 sentences) says these in plain words; the indices in WhatsNewChangelogTest and BatteryRestTest
moved by +1.

## Safety

- Nothing new skips or resizes a live trade by itself: the account stop rule and the live no-twin check are shadow only
  until Boss switches them on; the overnight GTT only adds a stop.
- The entry door reads only memory for the account stop rule (no disk, settings or network on the order path).
- Paper changes (the no-twin rule) are logged in the Autopilot timeline and undoable in one tap; paper changes never
  carry over to live.
- Open positions first: the shared held check only adds sources; the stop-check window only brings checks sooner; the
  evening look only adds a stops-and-targets look after the close.

## Tests

- **New ira-core tests:** HeldTest, StopCadenceTest (window, drift, evening look), AccountStopTest (month, drawdown,
  restart, latch, junk input), LiquidityTwinTest (twins, records, fidelity excuse, saved switch), and SameDirectionCapTest
  (the "only logged" split).
- **New app tests:**
  - ProtectionsGttTest (15): overnight GTT, the open's delete first, MIS never, fired and filled, fired not filled, closed
    overnight, remove, an unconfirmed delete, other exits, refusals, restart, the evening look hint, and each review case.
  - EnhanceRound1Test: the door in shadow and on, Boss's own orders and basket legs never refused, the restart PIN, the
    latch, a read before load, the drift guard, the held check, the cap count.
  - LiquidityArmTest and LiquidityArmLiveTest: a paper twin is a would-be trade, off it buys, another option is no twin,
    a live twin is only logged.
  - StrategiesLiveTest: an automatic live start is refused only with the switch on.
  - LiquidityNoTwinSwitchTest; EnhanceRound1CardsTest (Compose: the no-twin row and the account stop card).
- **Checks run locally:** `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification` green. The
  touched and related app classes (20 classes, 321 tests, 0 failed, 7 skipped by design, including ProtectionsLiveTest,
  OrderPathRound2Test, ExposureSpeedTest, TickPathReviewTest, JobsTest, ArmsWaitTest, BatteryRestTest) green with CI's
  flags. The full app suite (4 shards), Build APK and the rest run in CI.

## Review

Two separate reviewer agents read the full diff (the order and exit path; everything else). Every finding was checked
against the code; the confirmed ones were fixed, with tests:

- At the open, a GTT that fired during a pass could be followed by a full-size stop and target on a position already
  flat: now nothing is placed that pass and the next pass reads the position again.
- A GTT of the protection's own that reached Zerodha but was never recorded (a lost reply) is adopted, never counted as
  "another exit" (which would have put the day's orders beside it). At the open, active GTTs count as exits too.
- The overnight GTT was sized from the protection's own lots less every other exit, so an arm's GTT on its own lots
  took the protection's place: now held less other exits, at most what the protection covers.
- One failed positions read at the close ended a carried protection: now kept and asked again.
- Boss is told when the GTT could not rest after 3 tries.
- The account stop rule "un-reached" itself when the day's P&L recovered: now it stays reached until Boss restarts it with
  the PIN. A failed read of its saved days can no longer write today's figure over the history.
- No-twin: its would-be trades were counted as "missed signals" by the 26 Oct go-live fidelity check: now excused. The
  check runs after the same-direction cap and the order flow (their records stay whole) and never while the paper test is
  frozen. A file-write race and a stale card count were fixed.
- Stop-check window: after a slow check the next waits at least 5 s (never back to back on a slow broker); a late-check
  banner uses one fixed text so it does not repeat.
- The shared held check keeps Zerodha's MCX book only in the watch's pulse, as before (elsewhere its "not read yet today"
  kept the NSE loop on the 15 s path all day).

The no-twin rule has no research behind it yet: it is logged as `research/experiments/entries/e1-liq-no-twin.yaml`
(verdict paper-only, a planned replay test). It is on for paper as the round's brief asked (it only skips entries;
Boss's 13 Oct unfreeze allows risk-lowering paper changes on BANKNIFTY). **Boss: one tap on the Strategy lab card turns
it off if you want it logged only until it is tested.**

## Next: ranked ideas for round 2

1. **One exit coordinator per contract** (deep review idea 1): one registry of exits in flight for every exit sender.
   High value; medium risk.
2. **The no-twin rule's replay test** (e1): both books 2020-2026, with and without twins, real costs, walk-forward.
3. **MCX carried protections overnight:** the evening look covers NSE only; MCX's DAY exits end at 23:30 with no look
   after. Same GTT path, a look after MCX's close. High; low.
4. **Encrypted diagnostics** (idea 5): needs Boss's choice first - encrypted, a future session cannot read the file
   without the passphrase.
5. **Payout-aware drawdown** (idea 12) for the account guard's kill switch (the new account stop rule reads trading P&L
   only, so a withdrawal never trips it).
6. **Account stop rule net of charges:** Zerodha's day figure is before charges; subtract the day's charges from the
   contract notes so the rule reads the same net as the arms'.
7. **Paper positions as an explicit stream owner** (idea 8).
8. **Candle page "as of", filter chips, What's New tap** (idea 10); **count only 5m/15m candle reads into lessons**
   (idea 11).
9. **Clipboard diagnostics marked sensitive** (idea 13); **Liquidity live stop rule state kept** (idea 14).
10. **Weekly Jarvis review** (idea 15, TODO I15).
11. **A real two-book twin test:** today's twin tests inject the other book's holding; a test day where both books
    signal the same option would cover the real path.
12. **Protections' restAgain and arm GTTs before this round:** the open now counts active GTTs; a test that an arm's
    GTT and a protection on one contract never sell more than held across a whole day (fills, partials).
