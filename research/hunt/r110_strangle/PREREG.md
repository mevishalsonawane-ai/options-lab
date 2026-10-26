# R110 pre-registration: can the BANKNIFTY Monday strangle be made to fit Boss's account rule?

26 Oct 2026. Written and pushed BEFORE any R110 number is computed. Branch `claude/r109-irathinker-rules`. Research
only: nothing in `android/**` or `irathinker-app/**` changes; nothing goes live. Boss, 26 Oct, after R109: "yes, run
R110 on the strangle".

## 0. Why

R109 (`research/HUNT_R109_IRATHINKER_RULES.md`) found IraThinker's Monday short strangle profitable on real prints but
outside Boss's account rule at 1 lot. Design: +Rs 1,67,686 over 69 weeks, worst month -Rs 24,307. Final: +Rs 61,920 over
26 weeks, worst month -Rs 32,436, max drawdown Rs 37,926. After R109, a look at the R109 trade table (history and design
only) showed every loss came from weeks when BANKNIFTY moved more than 2%. R110 tests 8 pre-set changes aimed at that
tail.

**Signal source:** none directional. Strikes are set off the index spot at 09:30. S2's stop reads the index (the
underlying); S1's stop reads the position's premium (allowed). F1 and F2 read the index's realised vol and the ATM
option's implied vol as context (allowed: option-chain data as a filter).

## 1. Base (B0) and data

B0 is R109's `strangle-bn`, unchanged: every Monday, spot = the 09:30 bar's close, ATM = spot rounded to 100. Sell the
nearest monthly CE at ATM + 200 and PE at ATM - 200 at the 09:31 open. Buy back at the exit day's 15:29 open (Thursday,
or the last session before it). If the monthly expiry falls inside Monday-Thursday, settle at intrinsic. R109's engine,
stores, fills, charges, stale-leg repricing and lake copy are reused (`research/hunt/r109_irathinker/`). **Parity
check:** R110's B0 must reproduce R109's strangle weeks and nets in every period before any variant runs (asserted).

**Lake limit:** the lake keeps strikes within 10 steps of the ATM at each minute (1,000 points on BANKNIFTY), so no
leg is placed more than 1,000 points from the entry ATM.

## 2. The 8 tests (each one change to B0)

| id | change | idea |
|---|---|---|
| `W1` | also BUY the CE at ATM + 700 and the PE at ATM - 700 (wings 500 beyond the shorts) | cap the tail |
| `W2` | wings at ATM + 1000 / ATM - 1000 | a cheaper, wider cap |
| `X1` | shorts at ATM + 500 / ATM - 500 instead of +-200 (no wings) | sell further out |
| `X1W` | shorts at +-500 and wings at +-1000 | further out and capped |
| `S1` | exit everything when the position's loss, read at a minute's close, reaches 1.0 x the credit received | premium stop |
| `S2` | exit everything when the index's 1-minute close goes beyond a breakeven (CE strike + credit, or PE strike - credit, in points) | index stop |
| `F1` | trade only weeks where entry implied vol / realised vol >= its median over the previous 52 Mondays (needs 26; fewer = skip) | sell when options are dear |
| `F2` | skip the week when either of the 2 sessions before Monday moved >= 2 sigma (IraThinker's aftershock rule, `Strangle.aftershockFlag`) | avoid fresh turbulence |

**Details fixed now:**
- **Legs:**
  - Wings fill like the shorts: the first open within 3 minutes of 09:31, as buys. A week whose wing (or wider short)
    has no print is skipped and counted.
  - At exit the wings sell at the same time as the shorts buy back. Every leg uses R109's fill rules and stale-leg
    repricing.
  - In expiry weeks every leg settles at intrinsic. A long leg settling in the money pays STT on exercise (0.125% of
    the settlement value).
- **S1 / S2 path:**
  - Checked at each minute's close from 09:31 Monday to the exit day's 15:28.
  - A leg is marked at its last close within the session. With no print in the last 5 minutes (or none yet that day),
    it is marked by Black-Scholes at the implied vol of its last print, with the index at that minute (R103 Amendment 2
    rule).
  - The stop fills every leg at the next minute's open with stop slippage (10 bps and twice the ticks), stale legs
    repriced.
  - S1's loss = (marks now - credit) in points. Charges are not counted in the trigger.
- **F1:**
  - Entry IV = the implied vol of the 09:30 close of the ATM CE (offset 0, the monthly chain), our Black-Scholes, time
    to 15:30 of expiry.
  - RV = IraThinker's `realizedVol` on the 20 daily closes before Monday (R109 `rv20_before`).
  - The median uses only earlier Mondays, history included.
- **F2:** sigma = the population stdev of the last 20 daily log returns before Monday; the 2 sessions are the last two
  before Monday.
- **Event filter dropped.** It was considered, but `research/context/events.csv` ends in Mar 2026, so it could not be
  judged in the final window. Its RBI 2026 dates are not verified.

## 3. Periods (as R109)

- **History** (Aug 2021 - 19 Nov 2024): descriptive, and the F1 median's warm-up.
- **Design:** 20 Nov 2024 - 31 Mar 2026.
- **Walk-forward window:** Apr 2025 - Mar 2026.
- **Final:** 1 Apr - 13 Oct 2026, read once by R110 (`results/FINAL_READ`). This is weak evidence: R109 read B0 on the
  same final window, so a variant's final rests on weeks whose base result is known.
- **Post-13 Oct:** 14 - 19 Oct (2 weeks), descriptive.
- Weeks belong to the period of their exit day (R109's rule).

## 4. Gates (all must hold; never relaxed)

1. Net > 0 in design, the walk-forward window and the final.
2. Boss's account rule at 1 lot in design, walk-forward and final: worst calendar month >= -Rs 13,000 and maximum
   drawdown < Rs 40,000.
3. Significance in design:
   - the week bootstrap p (mean week net <= 0) < 0.05, with 1,000,000 resamples, seed 110, p = (1 + count) /
     (resamples + 1);
   - F1 and F2 also need a random-skip twin p < 0.05: skip as many design weeks at random, 200,000 draws, p = share of
     draws whose net >= the filter's.
4. BH q <= 0.10, with p = the largest of the test's gate-3 p-values, over m = 8 R110 tests + 14,253 earlier tests
   (R109's 14,245 + R109's 8), so m = 14,261. Also reported: q over R110 alone.
5. At least 40 design weeks and 15 final weeks traded.

**Reported for every test** (n with every number): weeks, win rate, net, net per week, worst week, the 5 worst weeks,
worst month, max drawdown, months <= -Rs 13,000, weeks <= -Rs 13,000, mean credit, margin estimate, net / margin, stale
legs, skipped weeks, and the difference to B0 on the same weeks. Margin estimate:
- unhedged (B0, X1, S1, S2, F1, F2): 13% of spot x lot (R109);
- hedged (W1, W2, X1W): the defined maximum loss, (wing - short distance) x lot.

These are planning numbers; the broker decides.

## 5. What a pass means

A pass would be a paper candidate only. The app does not sell options today, so it would need building, Boss's sign-off,
and a labelled paper arm (CLAUDE.md: logged in the Autopilot change timeline, one-tap undo, never live). Failures stay
notes in `research/experiments/entries/r110-*.yaml`.

## 6. Checks

`research/hunt/r110_strangle/tests/test_r110.py` will cover, on hand-made bars:
- the multi-leg P&L with wings, and settlement with exercise STT;
- the S1 / S2 trigger and next-minute fills;
- stale marks;
- the F1 point-in-time median, and the F2 flag against IraThinker's definition;
- the parity with R109's B0;
- the look-ahead guard (nothing after a decision minute changes it);
- the bootstrap and random-skip p, and the final lock.

Plus the repo's research checks.
