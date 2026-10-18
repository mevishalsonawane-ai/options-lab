# R78 pre-registration: "Gautam Jha's Daily High/Low Reversal" on XAUUSD gold

Written 18 Oct 2026, **before any R78 result was computed** (no trade list, no net, no count of setups has been looked at).
Research only (no `android/**`); nothing changes paper or live. Any app change is Boss's decision, made in the parent
session.

Boss, 18 Oct: test "Gautam Jha's Daily High/Low Reversal" on XAUUSD:
1. 1-day chart: mark the previous day's High and Low.
2. 1-minute chart: wait for price to break completely below the daily low.
3. Confirmation: a green candle right after the break, then the next candle breaks and closes above that green candle's
   high.
4. Enter long on that break; stop just below the green candle's swing low; target near where the down move started (the
   swing high). Mirror for shorts (break above the daily high, a red candle, then a close below its low).
Longs and shorts are reported separately and combined.

**Signal source: XAUUSD itself** (the 1-minute and daily bars of spot gold). No option price is involved; a trade is
the metal (a CFD-style long or short of 1 standard lot = 100 oz).

## 1. Data (fixed now)

- **Source:** the `research-data` release asset `xauusd_m1_bid.csv.gz` (Dukascopy XAUUSD 1-minute BID, 2 Oct 2023 -
  30 Sep 2026; made by `.github/workflows/gold.yml`), the same file the GOLD_* studies and R53 used, plus real Dukascopy
  BID day files for every weekday the release lacks and for 1 - 16 Oct 2026, decoded by R53's own decoder
  (`research/hunt/r78/fetch_fill.py`). The release's rows win on duplicates. Nothing is forward-filled or invented.
  The Drive lake's `xauusd_1m/` folder holds only its README and the event calendar (its 1-year parquet pack is not on
  Drive), so it is not used.
- Weekday bars only (Mon-Fri UTC), as `gold_scalp.py` / `gold_session_strats.py`.
- **Daily candle: the UTC calendar day (00:00 - 24:00 UTC), Monday to Friday.** This is the definition of the earlier gold
  studies (`liquidity_gold.sessions` groups minutes by UTC date; `gold_24x5.py` charts 00:00 - 21:00 UTC) and of the app's
  Gold paper arms (`GoldLiquidity.kt`, "all times UTC", Monday to Friday). It is **not** the broker / New York 17:00 day.
  "Previous day" = the previous weekday; Monday's is Friday. A day is used as "previous" only if it has >= 600 minutes in
  the data and is the calendar weekday right before (a missing weekday means no trade the next day).
- The previous day's High / Low are the max high / min low of its BID minutes; they are known only after that day
  closes (tested).

## 2. Split (fixed now)

- **DESIGN: 2 Oct 2023 - 31 Mar 2026.**
  - Walk-forward inside design: warm-up / "train" 2 Oct 2023 - 31 Mar 2024, then eight out-of-sample quarters
    Apr 2024 - Mar 2026.
  - The variants have no fitted parameter, so a variant's **walk-forward net = the sum of its eight out-of-sample quarters**.
  - A **re-tune line** is also reported (before each quarter, the variant with the best net on all earlier design data,
    ties to V1); it is information, not a gate.
- **FINAL: 1 Apr - 16 Oct 2026**, read **once**, all variants in one run, by a runner that refuses a second read
  (`results/FINAL_READ`).
- **Honesty note:** earlier gold studies held out Oct 2025 - Sep 2026 and R53 read Apr - Oct 2026, so people have looked at
  gold in the final window. This rule has never been run on it. The final read is a fresh, single, pre-registered
  measurement, nothing more.

## 3. The rule, exactly (long; the short is the mirror)

Prices are BID bars (the chart Dukascopy prints). `PDL` / `PDH` = previous UTC day's low / high.

- **Break:** a closed bar `i` with `close_i < PDL - X` and `close_{i-1} >= PDL - X` (a fresh cross; bar `i-1` may be
  before the window). `i` must start in **07:00 - 18:59 UTC** (London + New York, the window of `liquidity_gold.py` and
  `gold_scalp.py`). X = 0 (V1) or X = 0.03% of PDL (V4).
- **Green candle:** the first bar `g` in `i+1 .. i+K` with `close > open` (strictly).
- **Confirmation:** bar `g+1` closes above `high_g`. If it does not, the next green bar inside `i+1 .. i+K` becomes the
  candidate (its confirmation bar may fall at `i+K+1`). If none confirms, the setup is dead; a later fresh break the same
  day may start a new setup.
- **Entry (V1-V7):** at the **open of bar g+2** (the bar after the confirming close; everything used is known at bar g+1's
  close). The entry bar must start by 19:00 UTC.
  **Entry (V8, stop order):** a buy stop at `high_g` during bar `g+1`: filled when bar `g+1`'s high exceeds `high_g`, at
  `max(open_{g+1}, high_g)`; no close needed. Not filled -> the next green bar inside the window becomes the candidate.
- **Stop:** `SL = min(low over bars i .. g) - $0.10` (the swing low of the reversal, just below it). Exits when a bar's
  low <= SL, filled at `min(SL, that bar's open)`.
- **Risk:** `R = entry fill - SL`. A setup with R < $0.50 an ounce is not taken (spread-sized stop).
- **Swing target (Boss):** `S = max high over the 60 bars ending at the break bar i` (where the down move started).
  Exits when a bar's high >= S, filled at S. If `S <= entry fill`, there is no room: not taken.
- **R targets:** S replaced by `entry fill + 1.5 R` (V5) or `+ 2 R` (V6).
- **Profit lock (V7, house rule):** swing target, plus a step lock: once a closed bar's high reaches entry + 1 R, the stop
  rises to entry + $0.10; then for every further 0.5 R reached, the stop rises by 0.5 R (1.5 R -> +0.5 R, 2 R -> +1 R,
  ...). A lock move applies from the next bar.
- **Same bar touches stop and target:** the stop (the worse case).
- **Time exit:** at the open of the 20:40 UTC bar (`liquidity_gold.py`'s cut), or at the last bar's close if the data
  ends that day. No position is held overnight, so no swap is charged (asserted in code).
- **One trade per side per day.** Long and short books are independent (both may be open at once); combined = sum.

## 4. Costs (the gold studies' costs, plus slippage)

- Spread: ask = bid + $0.30. A long buys at the ask and sells at the bid; a short sells at the bid and buys back at the ask
  (the rule's levels are read on the BID chart; a short's stop / target trigger when the BID touches them and fill at the
  ask, i.e. level + $0.30).
- Commission: $7 a lot (100 oz) round trip = $0.07 an ounce.
- **Slippage: $0.05 an ounce** on every stop-triggered fill (stop exits and V8's stop-order entry). Targets and the
  open-price fills get none.
- Swap $40 a lot a night (the studies' figure) would apply to a held night; none occurs.
- Money is **USD per 1 standard lot (100 oz)**.

## 5. Variants (8; each reported Long, Short and Combined = 24 lines)

| id | break | K (green within) | entry | exit |
|---|---|---|---|---|
| **V1** | close < PDL | 3 | next open after confirmation | swing target (Boss as written) |
| V2 | close < PDL | 1 (literally "right after") | next open | swing target |
| V3 | close < PDL | 10 | next open | swing target |
| V4 | close < PDL - 0.03% | 3 | next open | swing target |
| V5 | close < PDL | 3 | next open | 1.5 R |
| V6 | close < PDL | 3 | next open | 2 R |
| V7 | close < PDL | 3 | next open | swing target + step profit lock |
| V8 | close < PDL | 3 | buy-stop at the green high | swing target |

## 6. Random-entry twins and multiple testing

- For each real trade, a twin trade: **same side, same UTC minute of day for the entry, same R in dollars, same target
  distance in dollars (swing: the real trade's own distance; R exits: k x R), the same lock and cut-off**, on a random
  weekday of the same period that has a bar at that minute. Entry at that bar's open, same costs.
- 1,000 twin books per line (fixed seeds). p = (1 + #twin books with net >= the real net) / 1,001, one-sided.
- **Benjamini-Hochberg across all 24 lines** (8 variants x Long / Short / Combined) on the DESIGN p-values. Final-period
  twin p-values are reported too.

## 7. Pass rule (per line; never relaxed)

A line passes only if ALL hold:
1. net > 0 after costs in DESIGN, in WALK-FORWARD (sum of the 8 out-of-sample quarters) AND in FINAL;
2. beats its design twins with BH q <= 0.10;
3. at least 100 DESIGN trades.

A passing line is a paper candidate only (CLAUDE.md: a passed idea may act on paper arms; live needs shadow first and
Boss). A line that fails is a note only.

## 8. Report

Per line and period: trades, trades per month, win rate, average win, average loss, profit factor, net, worst day, max
drawdown (on the daily-closed equity), and 2-3 example trades with times. Exit reasons (stop / target / lock / time).
Every number with its trade count.
