# RESULT: Donchian paper trades vs the backtest (R105's app leads)

26 Oct 2026, branch `claude/donchian-app-bugs` (from `claude/android-options-lab-kotlin-xgt1ym`). Paper behaviour only. Live is
unchanged, except that stop checks now run on time, which only lowers risk.

Evidence used: the code, and the phone's redacted diagnostics `diag-2026-10-19` to `diag-2026-10-26` (private repo, read only, no
code run from them). Key facts from them:
- R51 was off from 19 Oct (evening) until 23 Oct 10:36 (change timeline). It made no decision on 20-22 Oct.
- 20 Oct was a holiday (Dussehra): no trading that day. On Mon 19 Oct the Hero ticket tried a NIFTY expiry put, so NIFTY expired 19 Oct.
- The per-index status lines first appear in the 22 Oct file (build d29a6b5, installed after Thursday's close). Lines written
  by an older build had no date, and the new build stamped them with the day the book was last saved (21 Oct).

| # | Lead | Verdict | Fix |
|---|---|---|---|
| 1 | 4 entries missed, "fill window passed" | Already fixed on 22 Oct, now hardened | Retry 10 s after the bar close |
| 2 | Stops looked at 30 s+ apart; lock sold Rs 216 below | **Bug** | Locks book at their level; every wait in a look is bounded |
| 3 | Late 12:11 entry | Not a bug (inside its window) | Covered by fix 1 |
| 4 | R51 "NIFTY's expiry day" on 21 Oct | Not an expiry bug (wrong date on an old line). One real holiday bug fixed. | Holiday-aware expiry rule |
| 5 | R51's Supertrend filter | Not a bug: matches research exactly | Parity test |
| - | Same-direction cap vs R72 | Unchanged (Boss's rule) | Would-be trades still counted apart (tested) |

## 1. "The fill window passed" (4 breaks)

- **R72 FINNIFTY 09:20 put, 22 Oct.** Ran on the pre-22-Oct build. The stream built FINNIFTY, MIDCPNIFTY and SENSEX minutes
  only after the first entry check, so breadth could not be judged until about 09:21:24. By then R52 had already bought the
  same FINNIFTY put (09:21:24), so R72 found it held and said "missed".
  - Fixed on 22 Oct (build d29a6b5): indices followed from 09:16, R72 decides first, and a held option becomes a labelled would-be.
- **R51 BANKNIFTY / FINNIFTY / SENSEX 13:45 put breaks, "21 Oct".** R51 was off on 21 Oct, so these lines were written on
  R51's last day on (most likely Mon 19 Oct: the day's daily loss limit matches the MIDCPNIFTY "bot is stopped" line next to
  them) and dated "21 Oct" by the new status dates.
  - The old words do not say why. Most likely the same late index minute: there was no index following before 22 Oct, and 19 Oct had 15 stream drops.
- Since the 22 Oct fixes: no "fill window passed" on 23 or 26 Oct. On 23 Oct the misses were the daily loss stop, a rule.
- **Not the order lane or the stop path.** The bar-close check ran once, 3 s after the boundary. If an index's last minute was
  late, the next try was the full pass, up to a minute later, and the window is only 3 minutes.
- **Fix (paper only):**
  - When a bar cannot be settled (a break due and not decided, or an index read missing the bar's last minute), the entry
    check runs again 10 s later, until the bar is settled or its window ends.
  - A cached index read that lacks that minute is read again.
  - It never runs while any of these arms could be mirrored live, so no live entry is added. This is checked when it is
    planned and again just before it fires.
  - At most 8 retries a bar, so an index whose feed is down all day costs only a few reads a bar.
  - The stop path is untouched.
  - Code: `PassedArmsHost.retryEnds/planRetry`, `DonchianArm.nextRetry/barWhole/windowEnd`.

## 2. Stop and lock checks, and the BANKNIFTY lock (bug)

**Why the sale was low.** The paper lock does not rest as an order. The app checks it on each look and sells at market. On
21 Oct, one look at 12:33:42 found both R72 puts under their +5% locks:
- NIFTY sold at 211.85 against a 212.10 lock;
- BANKNIFTY sold at 464.10 against 471.29, Rs 216 lower on 30 qty.

The research sells at the level (or at the minute's open on a gap). The code booked the lower fill ("a worse fill is booked as it came").

**Why looks were 30 s+ apart.**
- 21-22 Oct (before the stop lane): the check ran after the full pass.
- 23 and 26 Oct (with the stop lane), each look still waited on network reads:
  - the paper book's price read for each held option (two tries of 5 s connect plus 10 s read, so up to 30 s with the stream quiet);
  - then each arm's own minute read, one held option after another under the book's lock;
  - then the sweep and the Zerodha stops.
- The gaps match the "stream quiet / Zerodha failing" incidents on those days.

**Fixes:**
- **The record books a lock or stop at its research fill** (`PassedArms.booked`, `PassedArms.restingPx`): the level, or the
  minute's open on a gap through it. Between minutes it uses the level only when the minute just ended is known and closed
  above it. If any minute in between is unseen (it might have gapped through), it uses the price now, as before. It never
  books a price that cannot be known.
  - The paper account's own fill is kept beside it (`paperFill`), so the account stays as filled and the record mirrors the backtest.
  - On 21 Oct this would have booked BANKNIFTY at 471.29.
  - It is slightly kinder than the research, which also takes about 0.1% plus 2 ticks of slippage off a stop.
- **Exit looks are never held by a slow read.**
  - A held option's minutes are waited on for 2 s at most (`PassedArmsHost.exitMinutes`), and all held options are asked for
    side by side. The read finishes in the background and is kept for the next look.
  - Kept minutes are used for the walk only: an old close never stands in for the price now (a quote is read instead).
  - In the stop lane, the paper book's pass and the sweep/stops step are each waited on for 3 s at most. They finish by themselves.
  - So a look waits 14 s at most (`StopLane.LOOK_BOUND_MS`), inside its 15 s window. No step is skipped or slowed.

## 3. The late 12:11 entry (not a bug)

- R72's NIFTY 22850 PE and BANKNIFTY 55000 PE were bought at 12:11:17 on 21 Oct, from the 12:05 bar's breadth break.
- That is inside the research's own fill rule: the first open of the 3 minutes after the bar, 12:10-12:12. So the signal was
  not stale, and refusing it would break the rule.
- It was late because the bar-close check (12:10:03) could not settle the bar. The phone was hot and at 15% battery, with
  stream drops around then; the next try was the full pass.
- The 22 Oct index following and today's 10 s retry cover this.

## 4. "NIFTY: its expiry day" on Wed 21 Oct (not an expiry bug; one real holiday bug fixed)

- R51 and Solo 2.0 decide "expiry day" from the exchange's listed expiries (`HeroRules.isExpiryDay`), which carry the holiday
  shift. On 21 Oct the list had 27 Oct next: R72 bought NIFTY 27 Oct contracts, and the Hero ticket said "not its expiry day".
- R51 was off on 21 Oct. Its line was written on Mon 19 Oct, NIFTY's real expiry, moved back from the Dussehra holiday, and
  dated "21 Oct" by the new status dates. So it was right on the day it was written.
- **A real holiday bug was found:** the context-only past-expiry rule (`MarketContext.ruleExpiry`, used by "days like today")
  marked every Tuesday, so the 20 Oct holiday was flagged and Mon 19 Oct was not.
  - Fixed with `ExpiryRule`: the weekday rule, moved back to the previous trading day, never the next.
  - Tested with Dussehra, two holidays in a row, weekends, the monthly indices, SENSEX, and a broken calendar.
  - The app's own holiday table shows a case ahead: Tue 24 Nov 2026 (Guru Nanak Jayanti) is November's last Tuesday, so the
    monthly expiry falls on Mon 23 Nov.
- The new entry retry reads the live flags itself before checking "could this trade live". The same-direction cap's check
  uses only flags already in memory, and right after a start it would say "not live".

## 5. R51's Supertrend (10, 3) filter (not a bug)

- The phone matches R73 (`research/hunt/r73/r73rules.py`) on every point that was asked about:
  - the same Wilder ATR (an RMA seeded by the mean of the first 10 true ranges, not an SMA);
  - the same hl2 ± 3 ATR bands and flip;
  - the same index 5-minute bars (R51's bars5);
  - judged at the signal bar's close, with nothing later read.
- **Parity test** (`SupertrendParityTest`, fixture from `android/tools/supertrend_parity_fixture.py`, which runs the research's own functions):
  - 60 sessions with a flat stretch, a gap open and missing bars;
  - direction equal on all 4,485 bars; ATR and both bands within 1e-6;
  - all 675 Donchian breaks equal, and the T7 take/skip decision equal on every one (12 against);
  - the phone's ~11 earlier sessions give the same verdict as the full series on every break from session 12 on;
  - scrambling later bars changes nothing (no look-ahead).
- One known difference: with no earlier session in memory, the phone does not judge the break and does not take it. This is
  by design.
- So the filter's -Rs 16,188 on 14 paper trades is not a calculation error. It is either bad luck, or the exits it ran with
  (the 30-60-100 lock and L25 replace R73's +30% target). That is a research question for Boss, not an app bug.

## Same-direction cap (unchanged)

The cap still refuses most of R72's 4-5-index breaks. Each refusal stays a labelled would-be trade in R72's record, which is
"real + would-be", and the 40-trade forward bar counts real trades only. Covered by the existing cap test plus
`capRefusedBreaksStayInThePlainRulesRecordLabelledApart`.

## Review

A separate reviewer agent read the whole diff. Every confirmed finding is fixed:
- What's New test positions not all shifted (blocker): fixed.
- Kept minutes could stand in for the price now (major): fixed and tested (`aFailedReadNeverLetsAnOldCloseStandInForThePriceNow`).
- The resting fill accepted a minute of feed lag (major): it now needs the minute just ended.
- The What's New text said live was unchanged (minor): it now says the stop check's time limit covers live stops too.

Suspected points also handled:
- live re-checked before a retry fires;
- a cap on retries per bar;
- the look's own cancellation rethrown;
- the exit reads started side by side;
- the new memory cleared on wipe.

Left as is: when the paper pass runs past its 3 s, the orphan watch can run beside the arms' exits. Each sells only under its own
lock, and an exit only ever reduces what is held, as already holds for the stream lanes.

## Tests

- Engine:
  - `PassedArmsTest`: the 21 Oct BANKNIFTY lock case, resting-fill edge cases (stale, entry minute, a lock raised on a red
    minute, broken prices), and the updated between-minute expectations;
  - `SupertrendParityTest`, `ExpiryRuleTest`, `DonchianArmTest` (retry window, whole bar, market closed / before 09:20).
- App (Robolectric, CI):
  - `PassedArmsHostTest`: a stop between looks books at its level with the account fill kept; a gap books at the open; a slow
    read never holds the exit look; a bar not whole at 09:20:20 is retried at 09:20:30 and bought; no retry while an arm can
    trade live; the would-be record;
  - `StopLaneTest`: the waits add up to less than 15 s, and a held paper option with a hanging price read still gets an
    on-time look;
  - `MarketContextExpiryRuleTest`: the Dussehra shift.
- ira-core: What's New entry (newest first) with `WhatsNewChangelogTest` indices shifted.
- Local: `:engine:test :ira-core:test :engine:jacocoTestCoverageVerification` green. `OrderPathRound2Test` is untouched: the
  retry and the bounded reads run on the host's background scope, never on the order or tick path.

Also fixed: CI was already red on the base commit (878cb1f): "Engine parity with the PC" failed on `KnowledgeTest`
("what does wyckoff say about volume"), which skipped the app tests and the APK. R105D's volume entries pushed the Wyckoff book
card out of the top five, so R104's two tested Wyckoff volume rules now lead. The test now accepts them, as it already does
for the tested Aziz VWAP rules.

CI sha: (filled in after the push)
