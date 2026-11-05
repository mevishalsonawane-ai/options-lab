# R148: enhancing Night: big-gap nights, a risk cap across indices, resting exits

Written 5 Nov 2026 for Boss's "Can we enhace it".

**Files:**
- Pre-registration: `research/hunt/r148_night_enhance/PREREG.md`, commit `e9b63b0`, before any number.
- Code: `research/hunt/r148_night_enhance/`, with 6 tests.
- Results: `results/results.json`.
- Experiment entries: `r148-*.yaml`, 9 of them, all failed.

**Research only.**

**The book tested:** Night on NIFTY, BANKNIFTY and FINNIFTY (exact rule) plus SENSEX (no-OI rule), as R147 proposes.
- **Periods:** design 2022-24; check Jan 2025 to Oct 2026. The check is a second look at R147's holdout.
- **Costs:** real option prices, app fills and charges, real half-spreads.

## Short answer for Boss

**None of the 9 enhancements helps. Keep Night simple: buy at 15:20, sell at 09:16, 1-ITM, 1 lot.**

| idea | design 2022-24 | 2025-26 | verdict |
|---|---|---|---|
| A1: 2nd lot on likely-big nights (weekend / holiday or VIX >= 16) | +Rs 66,914 extra (381 trades), but no better than a 2nd lot on random trades (p 0.76); 2024 negative | +Rs 127,268 (252), p 0.26 | fail: the extra comes from the extra size, not from picking the right nights |
| A2: ATM straddle (call + put) on likely-big nights | -Rs 340,247 (725 straddles, -Rs 469 each) | -Rs 556,059 (523, -Rs 1,063 each) | fail: big-gap nights are already priced in. The options cost more on exactly those nights |
| A3: the same, only where Night has no trade | -Rs 171,722 (344) | -Rs 304,677 (272) | fail |
| C1: resting stop -20% | -Rs 34,809 (771 trades; 125 gap-through fills) | -Rs 20,169 | fail: it sells at the lows of morning dips that recover by 09:16 |
| C2: resting stop -40% | -Rs 13,788 | -Rs 5,080 | fail (near neutral) |
| C3: resting target +40% | -Rs 19,813 (82 targets hit) | -Rs 39,555 | fail: it caps the big gap-up winners that pay for Night |
| C4: stop -20% + target +40% | -Rs 54,622 | -Rs 48,371 | fail |
| B1: at most 2 indices a night (strongest close) | drawdown unchanged (-Rs 35,137); gives up Rs 14,546 | drawdown -Rs 122,964 -> -Rs 113,190 (only 8%); gives up Rs 71,288 (33%) | fail |
| B2: at most 1 index a night | drawdown unchanged; gives up Rs 32,193 | -Rs 102,876; gives up Rs 112,156 (52%) | fail |

## The risk Boss must know before adding indices

These are Night's drawdowns on 1 lot of each index, on real prices. The account stop rule is -Rs 40k drawdown or
-Rs 13k a month.

| book | 2022-24: total / max drawdown / worst month | Jan 2025 - Oct 2026: total / max drawdown / worst month | months worse than -Rs 13k (2025-26) |
|---|---|---|---|
| NIFTY + BANKNIFTY (as now) | +Rs 125,140 / -Rs 35,137 / -Rs 31,685 | +Rs 124,062 / **-Rs 80,885** / -Rs 37,286 (Apr 2026) | 3 of 22 |
| + FINNIFTY | +Rs 167,367 / -Rs 35,137 / -Rs 31,685 | +Rs 162,623 / -Rs 82,433 / -Rs 37,286 | 4 of 22 |
| + SENSEX | +Rs 141,681 / -Rs 35,137 / -Rs 31,685 | +Rs 175,187 / **-Rs 121,417** / **-Rs 81,660** | 4 of 22 |
| + FINNIFTY + SENSEX | +Rs 183,908 / -Rs 35,137 / -Rs 31,685 | +Rs 213,747 / **-Rs 122,964** / **-Rs 81,660** | 4 of 22 |

- **Night as it is today already had a drawdown about twice the account stop in 2025-26** (on 1 lot each).
- **FINNIFTY adds Rs 38,561 of profit for only Rs 1,548 more drawdown.** It is a good addition.
- **SENSEX adds Rs 51,125 of profit but about Rs 40,500 more drawdown.** It doubles the worst month: April 2026 was
  -Rs 81,660 with SENSEX, -Rs 37,286 without. SENSEX's rupee swings are large for its lot.

**What I would change in the app (NOTES; Boss decides):**
1. Add FINNIFTY to Night on paper; live in shadow first.
2. SENSEX: paper only. Keep it out of live until a forward record shows the April-2026 kind of month is rare.
3. No stops, targets, caps, straddles or extra lots for Night.

## Honesty notes

- **The check period was R147's holdout,** already read for Night itself. R148's variants were never computed there
  before. Nothing passed design, so the check is information only.
- **The resting stop and target** are judged on the 09:15 bar (stale opens replaced by the bar's close, R146 A3).
  Within-minute order is unknown, so a stop is assumed to fill before a target in the same bar.
- **One cost was not modelled.** The app's live path already rests a -30% SAFETY GTT for Night. R146 found -30%
  changes little, and C1 / C2 bracket it (-20% hurts, -40% is near neutral), so the safety stop costs little.
