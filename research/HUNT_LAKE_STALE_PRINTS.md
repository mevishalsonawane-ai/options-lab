# Stale repeated prints in the lake: how many, and do they change any study?

18 Oct 2026. Research only (no `android/**`, nothing changes paper or live). Pre-registered in
`research/hunt/lakestale/PREREG.md` (commit 5c6168a7, pushed before any measurement). The rule was not changed after the
measurement, and no amendment was needed. Code: `research/hunt/lakeclean.py` (step 3, opt-in `stale=True`),
`research/hunt/lakestale/` (`measure.py`, `rerun.py`, `compare.py`, tests). Small results:
`research/hunt/lakestale/results/{measure,compare}.json`.

## Answer

**No study's verdict changes, and no passed rule loses a rupee.** The three rules that passed their gates give exactly
the same trades and numbers on the repaired data:

- R51's Donchian 20 re-tune: 1,787 final trades, +Rs 55,439, p 0.002, q 0.025.
- R72's C1 Breadth Donchian: 1,202 final trades, +Rs 1,71,366, p 0.008, q 0.032.
- R73's recommended T7 Supertrend trend filter: T7-P with 1,670 final trades, +Rs 1,34,743, q 0.013; T7-Q +Rs 1,34,308.

R73's recommendation (T7) and its list of trade-offs are unchanged. The repair matters in two places:

- **R77's headline loss was mostly one fake price.** Each of R77's four A and B tests loses about Rs 1 lakh less in the
  final period, and their twin p falls from 0.996-0.998 to 0.20-0.70. All 8 R77 tests still fail (design and
  walk-forward stay negative), so R77 is still rejected. It now loses modestly instead of badly.
- **R76:** today's Liquidity exits (B0) make Rs 9,394 more in design (+Rs 2,66,643 instead of +Rs 2,57,249), from one
  Budget-day trade. That widens B0's lead over Boss's 25-35% lock, so R76's "no" stands more firmly.

Everything else moves by a few rupees, or only in its p-value, which comes from random comparison trades: 19 other
rows change p by 0.002-0.062, and no gate flips.

**How much the rule flags:** 9,646 one-minute option prices (9,666 counting the lake's duplicate rows), out of 35.4
million. That is 0.03%, in 544 runs on 271 contracts over 159 index-days, almost all FINNIFTY and MIDCPNIFTY in 2026.
NIFTY has none.

**Caveat:** the rule catches gross errors only (more than 50% outside what the neighbouring strikes allow), so smaller
stale errors stay in the data. Read "unchanged" as "unchanged by the gross errors". See "What the rule misses" below.

## The rule (as pre-registered, unchanged)

The problem: when an option does not trade, the lake carries its last price forward, minute after minute, with zero
volume. That is usually fine. But on 28 Sep 2026 the FINNIFTY 24650 CE showed Rs 1,855.85 from 09:40 to 09:52, flat,
with zero volume. Its real price was about Rs 192 (it first traded at 09:53 at Rs 192.45). An R77 trade bought it at
Rs 1,855.85 and lost Rs 1,00,027. R51's stray-print cleaning cannot see this, because it compares a price with the
minutes on either side, and they repeat the same wrong price.

The new step (lakeclean step 3) drops an option minute only when both are true:

1. It is part of a **stale run**: 5 or more minutes in a row, flat (open = high = low = close), zero volume, the same
   price.
2. It is **impossible next to its neighbouring strikes**. A call can never be worth more than the call one strike lower,
   or less than the call one strike higher; puts mirror this. The neighbours' prices come from their latest real trade
   (volume > 0) in the last 15 minutes, at or before that minute. The price must be more than 50% (and 2 ticks) outside
   that band.

It uses no model and no volatility. It changes option prices only. Every study's signal is on the index (signal on the
underlying, trade the option), and the ATM label and the expiry-day test still read the rows before step 3. A trade
whose entry minute was dropped has no price there, so it enters at the next priced minute or is not taken; this happened
twice (R73 and R77 below).

## What it flagged (20 Nov 2024 - 13 Oct 2026, WEEK option files, after R51's cleaning)

| Index | option minutes | in a stale run (5+ min) | flagged and dropped | runs | contracts | index-days | flagged, causal version |
|---|---:|---:|---:|---:|---:|---:|---:|
| NIFTY | 7,356,775 | 27,099 | 0 | 0 | 0 | 0 | 0 |
| BANKNIFTY | 7,372,369 | 266,640 | 92 | 15 | 7 | 7 | 64 |
| FINNIFTY | 6,131,865 | 2,409,155 | 5,099 | 275 | 120 | 76 | 4,870 |
| MIDCPNIFTY | 7,227,910 | 1,983,987 | 4,437 | 251 | 141 | 74 | 4,135 |
| SENSEX | 7,313,552 | 95,042 | 18 | 3 | 3 | 2 | 9 |
| **Total** | **35,402,471** | **4,781,923** | **9,646** | **544** | **271** | **159** | **9,078** |

Notes, with sample sizes:

- **Stale runs are common, but stale errors are rare.** 13.5% of all option minutes (4.78 million) sit in a stale run.
  99.8% of those are kept: 3.78 million are consistent with their neighbours, and 0.99 million could not be judged
  because no neighbour traded in the last 15 minutes. Flatness and zero volume alone are not a reason to drop a price.
- 6,821 flagged minutes were too HIGH (as in R77's case) and 2,825 too LOW. Some runs are very long: the longest was
  FINNIFTY 25400 CE on 9 Sep 2026 at Rs 2,046.65 from 10:20 to the close (310 minutes), when its neighbours allowed
  Rs 385-485.
- 481 of the 544 runs are in 2026 and 63 in 2025 (FINNIFTY and MIDCPNIFTY mostly; their far strikes trade thinly).
- The **causal** version (a minute counts only from the 5th minute of its run, using no later minute) flags 9,078 of the
  9,646 (94%). The difference is the first 4 minutes of each run. The two-sided rule is the one applied, as an offline
  repair of the data (PREREG, Look-ahead).
- The store builds reproduced Amendment A's committed drop counts exactly for all 5 indices (foreign rows, stray opens,
  capped highs) before step 3 was added.

**Sanity read (the contract's next real trade that day; information only, the rule never reads it).** 351 of the 544
runs had a later real trade on the same contract the same day. In 321 of them (91%) that trade landed inside the band
the neighbours set. In 28 (8%; 201 minutes, 2.1% of flagged minutes) it came within 20% of the flagged price, so those
flags are **possible false positives**. Looking at them, most are days when two neighbouring strikes' REAL, traded prices
contradict each other. Examples: the FINNIFTY 27900 CE and 27950 CE on 2 Mar 2026; the BANKNIFTY 52800 PE on 13 Mar 2026
against the 52700 PE. On such days the lake's strike labels look muddled, and the rule cannot tell which side is wrong.
As pre-registered, these are reported, not fixed with a new rule. None of them touched any study trade (below).

## Effect on each study

Each study's own code was re-run twice: on the Amendment A stores (to prove it reproduces the committed results here),
and on the stores with step 3. Same rules, grids, seeds, twins, walk-forward picks and gates. The final periods were
already read; this re-prices them, it does not re-select. **All 74 headline rows reproduced the committed results
exactly** (trades, nets, p, q, verdict) before step 3, so every difference below comes from step 3 alone. 138 trade
lists were compared trade by trade.

| Study (tests) | trades touched by step 3 | verdicts | passed rule(s) |
|---|---|---|---|
| R51 Amendment A (passing rule + 6 near-misses) | none | unchanged | Donchian 20 RT: identical (1,787 final trades, +Rs 55,439, q 0.025) |
| R72 cross-index (12) | none | unchanged | C1 Breadth Donchian: identical (1,202 final, +Rs 1,71,366, q 0.032) |
| R73 trend / wait (36) | 1 design trade in the 1-minute-wait variants (Rs 4-5) | unchanged; T7 still recommended | T7-P / T7-Q, T1, T2: identical |
| R76 Liquidity lock (B0 + 5) | 1 design trade (B0 and L35: +Rs 9,394) | unchanged: nothing recommended | (none passed) |
| R77 absorption (8) | 1 final trade, in A-X1, A-X2, B-X1, B-X2 (about +Rs 99,700 each) | unchanged: all 8 fail | (none passed) |

Only two days' prices changed any real trade: FINNIFTY on Budget day (Sun 1 Feb 2026) and FINNIFTY on 28 Sep 2026.
Both flags are correct (below). Twin p-values move a little in R51, R72, R73 and R77, because some random twin entries
land on a repaired minute.

### R77 (the study that found the problem)

| test | design trades / net | walk-forward net | final trades | final net before -> after | twin p before -> after | BH q before -> after | verdict |
|---|---|---|---:|---|---|---|---|
| A-X1 | 328 / -Rs 45,965 | -Rs 26,059 | 153 | -Rs 1,13,096 -> **-Rs 13,383** | 0.996 -> 0.198 | 0.998 -> 0.591 | FAIL |
| A-X2 | 315 / -Rs 67,577 | -Rs 40,914 | 152 | -Rs 1,25,017 -> **-Rs 25,184** | 0.998 -> 0.403 | 0.998 -> 0.591 | FAIL |
| B-X1 | 520 / -Rs 58,188 | -Rs 33,159 | 273 | -Rs 1,38,732 -> **-Rs 39,018** | 0.998 -> 0.517 | 0.998 -> 0.591 | FAIL |
| B-X2 | 502 / -Rs 1,00,386 | -Rs 65,978 | 265 | -Rs 1,61,911 -> **-Rs 62,078** | 0.998 -> 0.699 | 0.998 -> 0.699 | FAIL |
| C-X1 | 208 / -Rs 9,722 | +Rs 4,926 | 102 | -Rs 10,869 (same) | 0.341 (same) | 0.998 -> 0.591 | FAIL |
| C-X2 | 200 / -Rs 9,054 | +Rs 5,483 | 100 | -Rs 8,942 (same) | 0.240 (same) | 0.998 -> 0.591 | FAIL |
| D-X1 | 333 / -Rs 11,675 | +Rs 11,090 | 190 | -Rs 23,808 (same) | 0.431 -> 0.437 | 0.998 -> 0.591 | FAIL |
| D-X2 | 324 / -Rs 9,020 | +Rs 7,565 | 186 | -Rs 37,053 (same) | 0.503 -> 0.507 | 0.998 -> 0.591 | FAIL |

The fake Rs 1,855.85 entry is gone. With no price at 09:40 that trade is not taken, so a later signal the same day
(13:07, same contract, Rs 162.70 -> Rs 159.00, -Rs 314) is taken instead. Design and walk-forward did not change, so
every A and B test still fails on "design net <= 0" and "walk-forward net <= 0", and all eight fail the twin gate.
These numbers match R77's own after-the-fact "without the stale print" column to within that one replacement trade.

### R76 (Liquidity 15+5 lock)

| rule | design (881 trades) before -> after | walk-forward (657) before -> after | final (357) | decision |
|---|---|---|---|---|
| B0 own exits | Rs 2,57,249 -> **Rs 2,66,643** | Rs 2,38,064 -> Rs 2,47,458 | Rs 11,155 (same) | (baseline) |
| T25 / T30 / T35 / T50 | unchanged (Rs 2,08,114 / 2,27,558 / 2,28,775 / 2,20,719) | unchanged | unchanged | not recommended (q 0.94) |
| L35 | Rs 2,64,578 -> Rs 2,73,972 | Rs 2,38,533 -> Rs 2,47,927 | Rs 8,832 (same) | not recommended (q 0.94) |

The one trade is the FINNIFTY 27150 PE on Budget day, bought at 12:15 at Rs 400. The market was falling hard. Its
rule exit at 12:30 read Rs 392.00, a zero-volume price repeated since 12:18. At that minute the 27100 PE traded at
Rs 605, and a 27150 put cannot be cheaper than a 27100 put, so step 3 dropped Rs 392.00. The exit moved to the next
real trade, Rs 548.85 at 12:32: -Rs 613 became +Rs 8,780. That is still conservative, since the true 12:30 price was
at least Rs 605. The four T variants had already sold this trade at 12:19-12:22 at the same Rs 392.00. Those minutes
are wrong too, but by less than the 50% margin, so the rule kept them, and the T variants' loss on this trade is
overstated by about Rs 700-2,500. After the repair B0 leads them in design by Rs 38k-59k, so this does not change R76's "no".

### R73, R72, R51

- **R73:** the 36 tests' trades are identical except one design trade in the 1-minute-wait variants (W3H, W3D, W3HD,
  W5D, W5HD). On Budget day a FINNIFTY put entry fell on a dropped minute, so the trade entered a few minutes later at
  12:35, the 26750 PE at the same repeated Rs 240.00. In W5D the dropped entry had been the 26850 PE, so the trade moved
  to the neighbouring strike, and W5D's design net changes by -Rs 4 (P and Q). Twin p moves by 0.002-0.004 in 9 tests, and q alone in 2 more.
  The closest call is W2D-P, whose q goes from 0.022 to 0.025; it still fails, as before, on its design and
  walk-forward nets. The 6 passes and the decision (T7 recommended; T8, W5D, W5HD as trade-offs) are unchanged.
- **R72:** all 24 trade lists identical; 6 of 12 twin p-values move (by 0.002-0.062; C5-RT 0.854 -> 0.916); no q
  crosses 0.10. C1 passes as before; C6 still fails on its design and walk-forward nets.
- **R51 Amendment A:** all 14 trade lists identical. The two R44-ema821_adx15 near-misses' twin p rises 0.016 -> 0.022
  and 0.018 -> 0.024 (q 0.102 -> 0.120 and 0.125), slightly further from passing; they also fail on design
  walk-forward <= 0 and top-5 trades.

## What the rule misses (stated plainly)

- **Errors under 50%.** On Budget day the FINNIFTY 27150 PE sat at Rs 392.00 from 12:18 to 12:31. The rule dropped
  only 12:30, the one minute where its neighbour had moved more than 50% away. The 27050 PE sat at Rs 276.90 with zero
  volume for 39 minutes (12:08-12:46) while the 27100 PE traded at Rs 360-716. A lower-strike put can be cheaper, but by at most the 50
  points between the strikes, so Rs 276.90 was wrong. The frozen band does not use that bound on that side
  (P(K) >= P(K_hi) - (K_hi - K) for puts, mirrored for calls), so most of that run was kept. These misses only touched
  a few trades in the studies above (R73's waits and R76's T variants on Budget day, a few thousand rupees in all).
- **Runs no neighbour can judge.** 0.99 million stale-run minutes had no neighbour traded in the last 15 minutes and
  were kept unjudged.
- A tighter rule (a smaller margin, the missing bound, or a stricter neighbour) would be a NEW pre-registered idea, not
  a fix to this one. It would have to show it does not drop real quiet prices: today's 50% margin was chosen so it
  could not.

## What this means for Boss

- Nothing changes on paper or live. The passing rules (R51 Donchian 20 RT, R72 C1, R73 T7) are untouched by this
  defect.
- R77's "loses about Rs 1.1-1.6 lakh in the final period" was mostly one fake price. Its fair reading is "loses
  Rs 9k-62k, no better than random entries". Still rejected.
- R76's answer ("keep Liquidity's own exits") is a little stronger.
- Step 3 stays opt-in (`R51_STALE=1`). Committed results keep their own stores, so they stay reproducible. A future
  study can switch it on, and should say so.

## How to reproduce

```
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m $IRAALGO_LAKE --include "opt_*_WEEK_202[456].parquet"
rclone copy gdrive:IraAlgo-data-lake/index_1m $IRAALGO_LAKE --include "index_1m_*_202[456].parquet"
R51_CLEAN=1 python research/hunt/r51/r51data.py build                  # Amendment A stores
R51_CLEAN=1 R51_STALE=1 python research/hunt/r51/r51data.py build      # with step 3
python research/hunt/lakestale/measure.py                              # results/measure.json
python research/hunt/lakestale/rerun.py clean|stale STUDY design|final # r51 (final only), r72, r73, r76, r77
python research/hunt/lakestale/compare.py                              # results/compare.json
```

R77's code is on branch `claude/r77-absorption` (`R77_DIR` points at its `research/hunt/r77`). Its shared modules are
taken from this branch, which differs from that one only by the opt-in step 3. Lake files as uploaded 14-15 Oct 2026.
Raw data was kept in memory (/dev/shm) and deleted after use.
