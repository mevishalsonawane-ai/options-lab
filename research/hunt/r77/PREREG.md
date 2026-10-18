# R77 pre-registration: "absorption bubble" PROXY on 1-minute index bars

Written 18 Oct 2026, **before any R77 result was computed** (no event, P&L or move was looked at). Research only (no
`android/**`). Boss, 18 Oct: a quick test of an "absorption bubble" strategy.

## 0. What can and cannot be tested

True absorption (big passive size soaking up aggressive orders at one price) needs trade-by-trade prints with the
aggressor side and the order book. We have neither in history (R7, R8, R10: brokers keep 1-minute candles only). So
this is a **proxy**: a 1-minute bar with unusually high volume but an unusually small range, at a swing extreme,
followed by a reversal bar.

**Volume source.** The lake's index-futures minutes (`fut_1m_live_index`) start only on 29 Jul 2026, and expired
futures minutes cannot be fetched (h38). The index itself has no volume. So the volume of a minute is, as R10 did for
VWAP, the **nearest-expiry option volume**: the sum of CE + PE volume over every strike held in R51's store for that
minute (the rolling ATM +- offsets of the WEEK file, within 12 strikes of spot), in lots (divided by the lot in force
if the lake counts shares). A proxy check (section 5) compares it with the real futures volume on the days both exist,
with no prices or P&L read.

- **Signal source: the UNDERLYING** (the index's 1-minute bars for price, range and swing; the index's option-market
  volume for the volume condition, as context). The trade is the 1-ITM option chosen by the strike rule. No signal is
  read from the traded option's own chart.

## 1. The event (fixed; only the two thresholds vary)

On the 09:15-15:29 minute grid of one index and one session (bar i = minute row i):

- **Volume percentile** `vp(i)` = the share of the previous 20 sessions' volumes at the same minute row that are
  strictly below today's volume at row i (needs >= 15 of those 20 to have the row; else no event).
  High volume: `vp(i) >= V` (V = 0.95 or 0.90).
- **Range percentile** `rp(i)` = the same for the index bar's range H - L. Small range: `rp(i) <= R` (R = 0.40 or 0.25).
- **Swing extreme** (N = 20, within the day, so i >= 20): a downside event when `low(i) < min(low(i-20 .. i-1))`; an
  upside event when `high(i) > max(high(i-20 .. i-1))`. (If both, no event.)
- **Confirmation** on bar i + 1 only:
  - downside: `low(i+1) >= low(i)` AND (`close(i+1) > min(low(i-20 .. i-1))`, back inside the swept range, OR
    `close(i+1) > high(i)`, the absorption bar's opposite extreme broken);
  - upside: the mirror image.
- **Entry** at the open of minute i + 2 (the minute after the confirmation bar closes; R51 engine fills there or up to
  2 minutes later). So the event and its confirmation use only bars closed before the entry (the look-ahead guard,
  tested in `test_r77.py`). Entries 09:37 (row 22, the first possible) to 14:45. Long **CE** after a downside
  absorption, long **PE** after an upside one; 1-ITM strike at row i + 1 (R51 `strike_for`, money 1).
- One open position per side at a time, at most 2 fills a side a day (as R71). Expiry days skipped (R51 rule). Any
  missing value (NaN bar, missing volume) means no event.
- Warm-up: the store's option minutes start on 20 Nov 2024, so the first 20 sessions of each index have no volume
  baseline; events start about 19 Dec 2024. Index bars from 1 Sep 2024 are not needed.

## 2. The 8 variants (4 detectors x 2 exits), fixed now

| id | V (volume pct, at least) | R (range pct, at most) |
|---|---|---|
| A (Boss's example) | 0.95 | 0.40 |
| B | 0.90 | 0.40 |
| C | 0.95 | 0.25 |
| D | 0.90 | 0.25 |

Exits (both square off at **15:15**):
- **X1, structure (underlying)**: index stop = the absorption bar's extreme (CE: the first minute whose index low is
  below `low(i)`; PE: high above `high(i)`); index target = the next swing level, the opposite end of the swept range
  (CE: `max(high(i-20 .. i-1))`; PE: `min(low(i-20 .. i-1))`; if the entry minute's previous close is already at or
  past it, no index target). An index stop or target touched in a minute sells at the next minute's open (as
  `fam_liq.forced_row`). Also a -25% premium catastrophe stop and a 30-minute time stop.
- **X2, the app's premium exits (fixed R)**: -15% premium stop, +30% premium target (2R), 30-minute time stop
  (R71's XB, the arms' standard).

The 8 tests: A-X1, A-X2, B-X1, B-X2, C-X1, C-X2, D-X1, D-X2. No other combination will be run or reported as a test.

## 3. Measurement

- Prices: R64's engine (R51's held-contract simulator plus breakeven steps; none used here) on R51's Amendment A clean
  stores (`R51_CLEAN=1`), exactly as R71: next-minute fills, R17 liq fills, stops on the minute low with gap fills at
  the worse open, limit targets at their price, `Costs('dated')` (STT 0.15% from 1 Apr 2026), lots in force, 1 lot.
- Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (every index R51's stores cover), pooled; per-index rows
  reported. BANKNIFTY and NIFTY are also shown alone.
- Periods (as R70 / R76): **design** 20 Nov 2024 - 31 Mar 2026; **walk-forward** = the design rule's out-of-sample
  quarters Apr 2025 - Mar 2026 (nothing is tuned: the 8 rules are fixed, so the walk-forward net is each rule's net on
  those four quarters, each quarter also shown); **final** 1 Apr - 13 Oct 2026, read once (`results/FINAL_READ`). The
  final period was already read by R51, R63, R70, R71, R76 and others for other rules, so it is **weak evidence**.
- Reported per variant and period: trades, win rate, net Rs, per trade, worst day, max drawdown (daily), exits; plus
  three example trades.

## 4. Gates (never relaxed)

A variant PASSES only if all hold:
1. net > 0 after costs in design, in the walk-forward quarters, AND in the final period;
2. at least 100 design trades;
3. it beats **random-entry twins at the same times of day**, final period: 500 twins (seed 77); each trade is replaced
   by a trade on the same index, same side, same entry minute row, on a random other non-expiry session of the same
   period, with the same strike rule and the same exits (X1: index stop and target the same number of points from the
   twin's reference close as the real trade's); p = (1 + #twins with total >= real) / 501; **BH across the 8 tests,
   q <= 0.10**. The same twin test on design is reported (information).
A pass would be paper-only, logged and labelled (CLAUDE.md); a fail stays a note.

## 5. Event study (description, no P&L) and proxy check

- For every confirmed event of detector A-D (design and final, separately): the index's signed move from the
  confirmation bar's close (row i + 1) to the close of row i + 1 + k, k = 5, 15, 30 minutes (signed + for the expected
  reversal: up after a downside absorption), in basis points; mean, median, share > 0, n.
- Placebos, both matched on index, side and minute row:
  - **P-time**: the same row on 200 random other non-expiry sessions of the same period (time-of-day drift);
  - **P-swing**: the same swing extreme + the same confirmation but WITHOUT the volume / range condition (every such
    event in the period at rows within +-15 minutes; tells whether "absorption" adds anything to a plain swing
    reversal).
  Difference (event - placebo mean) with a day-block bootstrap 95% interval (2,000, seed 77). Information only.
- **Proxy check** (no prices or P&L): on the sessions where `fut_1m_live_index` has the near-month future (29 Jul -
  13 Oct 2026), per index: Spearman correlation of the per-minute futures volume and the option-volume proxy, and the
  share of proxy high-volume minutes (vp >= 0.95 against the proxy's own 20 sessions) that are in the top 10% of that
  day's futures minutes.

## 6. Limits stated in advance

It is a proxy: the volume is option volume, not futures order flow, and a bar's range says nothing about where in the
bar the volume traded. True absorption needs the phone's tick recordings (R10 section 4.2, test 4). The final period
has been read before. 8 tests are fewer than the 4 x 2 x (other N, other confirmations) one could try; no others will
be run.
