# R132 pre-registration: the liquidity playbook from Boss's two PDFs, done the way they describe

Written 29 Oct 2026, before any R132 number was computed. Research only: nothing in the app changes, nothing trades.

Boss sent two PDFs (*"Liquidity: The Fuel of Market"*, 13 pages; *"Market Liquidity"*, 12 pages):
*"Understand and deep study the files, and find out what are we missing, or what are we doing wrong, and resolve it."*

## 0. What the PDFs teach, against what R130 / R131 and earlier studies did

| the PDFs say | what we did | gap |
|---|---|---|
| Liquidity rests at **external** levels (beyond the range: previous highs / lows), at **equal highs / lows**, at **round numbers**, along trendlines, at range edges | R130 swept the latest **3-bar swing** (minor, internal). Amendment 2 added yesterday's low, the opening-range low and equal lows. H35 tested round numbers, yesterday's high / low and the weekly range as bounce / break levels (0 of 408) | **round numbers, the previous week's high / low and external-only sweeps were never used as SWEEP levels** |
| A sweep is a **sudden wick** beyond a key level and a **fast reversal back**. A level that is broken and held is a **run** (continuation), not a sweep. "Not every sweep is a reversal" | R130 counted any wick 1 tick beyond (V6 asked for a close back) | **no "sudden" test and no wick reclaim: runs and sweeps were mixed** |
| Wait for the sweep to finish, then a **BOS / CHoCH** ("entering too early" is mistake #1) | R130: MSS within 12 bars | tested; **"enter on the reclaim without the BOS"** (the too-early case) never compared |
| Stops **beyond the liquidity pool**, not at obvious levels (mistake #2) | R130 put the stop at the FVG's edge; Amendment 3 tried beyond the sweep (59% wins, little profit) | partly tested |
| Target the **next liquidity on the other side** (the PDF's example: entry after the grab, target at the opposite external low) | R130 targeted the nearest equal highs / swing: a median **0.44 of the stop distance**, too small to pay an option's costs (Amendment 3) | **opposite EXTERNAL liquidity as the target, at least 1R away, never tested** |
| Confirm with **volume** ("a noticeable increase in trading volume") | never (the index has no volume) | **untested; the ATM options' volume can stand in** (option data as a filter around an index signal, which CLAUDE.md allows) |
| **Session opens**; **HTF** liquidity / structure | R130 Amendment 2: morning only, 15 / 60-min trend: no help | tested |
| Order blocks, FVGs as confluence | R62 / R62b (OB 0 of 16), R130 (FVG) | tested |
| Order book, order flow | no depth data in the lake | **cannot be tested** (stated) |
| Trendline liquidity | not tested | **not tested here:** a trendline needs a subjective choice of touch points. With no objective rule in the PDFs, it is left out (stated) |

R132 builds the PDFs' playbook with every gap above that can be tested.

## 1. The rule (signal on the index, wicks only; buy the option)

**Liquidity levels** (known at the bar):
- yesterday's high / low (PDH / PDL);
- the opening range's high / low (09:15-09:29, usable from 09:30);
- **the previous week's high / low** (the last completed Monday-Friday week);
- **equal highs / lows:** two 3-bar 5-minute swing points of the last 5 sessions within 0.03% (the outer of the pair);
- **round numbers:** multiples of 100 (NIFTY, FINNIFTY), 500 (BANKNIFTY, SENSEX), 50 (MIDCPNIFTY), within 1% of price.

A level is "fresh" only if price has not traded through it:
- PDH / PDL: earlier today;
- opening range: since 09:30;
- previous week: earlier this week;
- equal pair: since it formed;
- round number: in the last 12 bars.

**A sweep (sell-side, for a buy; wicks only), on 5-minute bars:**
- **Beyond:** the bar's low goes below a fresh level by at least max(1 tick, 0.02% of the level).
- **Sudden:** the bar's high-low range is at least 1.2 × the median range of the previous 20 bars.
- **Reclaim (a sweep, not a run):** within this bar and the next 2, a **high** goes back above the level before any
  lower low than the sweep's lowest.

The sweep extreme is the lowest low from the sweep bar to the reclaim.

**Confirmation (BOS):** within 12 bars after the sweep bar, a minute's **high** goes at least 1 tick above the latest
3-bar 5-minute swing high known at the sweep.

**Entry:** buy the ATM call at the open of the minute after the breaking minute. Entries 09:30-14:30.

**Stop:** 0.02% beyond the sweep extreme ("beyond the pool").

**Target:** the nearest fresh opposite liquidity level above the entry at least 1R away (R = entry - stop): PDH, the
opening-range high, the previous week's high, the outer of an equal-highs pair, or a round number. If there is none, 2R.

**Exits:**
- stop or target on minute wicks;
- both in the same minute: the stop;
- otherwise out at 15:20.

One position per index. Sells mirror everything (a buy-side sweep above a level, the ATM put).

**The option:** R130 / R131's rule. The nearest expiry that does not expire that day, dated charges, liquidity
slippage (also 2x), 1 lot at today's lots, lake cleaning steps 1-3.

## 2. Versions (8, one BH family)

| version | what changes from P1 |
|---|---|
| **P1, the playbook** | as above |
| P2 | enter on the first 1-minute touch of the largest FVG in the sweep → BOS leg (within 12 bars), not at the BOS |
| P3 | **+ volume**: the ATM call + put volume in the sweep bar ≥ 1.5 × its average over the previous 12 bars (same session, at least 3) |
| P4 | **+ session open**: only sweeps whose bar starts 09:30-10:30 |
| P5 | **+ HTF**: the 60-minute structure (R131's rule) points the trade's way at entry |
| P6 | **external only**: sweeps of PDH / PDL and the previous week's high / low only |
| P7 | **too early (the PDFs' mistake #1)**: enter at the minute after the reclaim, with no BOS |
| P8 | **all the confluence**: P3 + P4 + P5 |

## 3. Periods, checks and gates

- **Design 2021-08-01 .. 2026-03-15:** no fitting, so each year is out of sample (folds 2023, 2024, 2025, 2026 Q1).
- **NIFTY index 2017-2020, index points:** read before only by R131 (a trend rule), never for liquidity rules. This is
  the cleanest test available.
  - **Versions:** P1, P2, P4, P5, P6 and P7 (P3 and P8 need option volume, which starts in 2021).
  - **Break-even:** as R131, from the design NIFTY P1 trades.
- **Apr - Oct 2026, read once for R132:** labelled "used before by R123 / R130 / R131".
- **Twins:** 200 per trade. The same index, day and side; a random entry minute 09:30-14:30; the same stop and target
  distances; the same option rule. p by the empirical rank; BH over the 8.
- **The PDFs' claims, checked (index wicks, design, with n):**
  - **K1, "sweeps are followed by structure breaks":** the share of sweeps with a BOS within 12 bars, against the same
    BOS test from random bars of the same sessions.
  - **K2, "a sweep reverses, a run continues":** after a reclaimed sweep, a 1:1 race from the reclaim (d = the reclaim
    level minus the sweep extreme) back toward the sweep or away from it. Compared with the same race after a
    **run** (a break of a fresh level with no reclaim within 3 bars, raced from the third bar).
  - **K3, "round numbers are magnets":** the K2 race for sweeps of round numbers, against **placebo levels** at the
    half-way points (50, 250, 25).
- **Gates (all; Boss's 65% rule included):**
  - design: ≥ 300 trades; average net > 0 at 1x and 2x; ≥ 3 of 4 folds positive; BH q ≤ 0.10;
  - NIFTY 2017-2020 (where run): above break-even;
  - Apr - Oct 2026: ≥ 50 trades; average net > 0; **win rate ≥ 65%**;
  - account (design plus Apr - Oct, 1 lot): drawdown above -Rs 40,000 and no month below -Rs 13,000.
- **If one passes,** a paper arm is proposed (not built). Otherwise the write-up says plainly it fails, and what the
  PDFs got right or wrong on the data.

## 4. Outputs

- Code and tests: `research/hunt/r132_liquidity_playbook/` (`r132.py`, `test_r132.py`, with a look-ahead guard).
- Results: `results/design.json`, `results/nifty_2017_2020.json`, `results/holdout.json`.
- Write-up: `research/HUNT_R132_LIQUIDITY_PLAYBOOK.md`, including the PDF gap table.
- Entries: `research/experiments/entries/r132-*.yaml`.
- RESULT.md.
