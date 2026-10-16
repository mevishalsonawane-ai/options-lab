# R74 Part B pre-registration: the five best untried families, index signal, buy the option

Written 16 Oct 2026, **before any R74 profit or loss was computed**, and committed first. Research only (no `android/**`).
The candidates are ranks 1-5 of `research/HUNT_R74_STRATEGY_MAP.md` section 3, pushed as Part A (8325427) before
this file.

## 1. Signal source

**Every rule's signal is read from the index** (the underlying). India VIX and the lake's 59 large-cap stocks (price and
volume) are used as context. No rule reads an option's own chart.
- India VIX is computed from option prices, so V1 and V2 use option-market information as a filter around an index
  condition. This is allowed by CLAUDE.md and labelled here.
- Each trade buys the **1-ITM option of the index named by the rule**, and trades are taken exactly as Donchian takes
  them:
  - entries 09:20-15:10;
  - one open position a side per index, and at most 2 a side a day;
  - never on that index's own expiry day;
  - a signal on a 5-minute bar's close fills at the next 5-minute row.

## 2. Data (Drive lake `gdrive:IraAlgo-data-lake`, rclone, 16 Oct 2026; nothing raw in git)

- **Index and options:** `index_1m` and `options_rolling_1m` (WEEK) for the five indices. Priced on R51's Amendment A
  clean stores (`R51_CLEAN=1`, rebuilt today). Today's run reproduces R72 exactly: Donchian XB design Rs 8,281 on 4,518
  trades; C1 design Rs 2,16,929 on 3,071 trades.
- **India VIX:** `index_1m_INDIAVIX_<year>`. The 5-minute close is the last print at or before the bar's end; the day's
  first print is VIX's open.
- **Stocks:** `equity_1m_<year>`, 59 large caps, 2 Sep 2024 onward. For each 5-minute bar:
  - **share above VWAP**: the share of stocks whose last close is above their own session VWAP (typical price x volume,
    from 09:15 to the bar's end);
  - **stock volume**: the stocks' total volume in the bar.
  - **Survivorship:** this is today's list of 59 stocks.
- Every value at bar b uses minutes up to the end of bar b only. `test_r74.py`'s look-ahead guard proves it for every
  rule and setting.

## 3. The five rules (call side; the put side is the mirror unless stated)

Notation:
- **bar b** is the 5-minute bar of the day: b = 0 is 09:15-09:19, and bar b closes at 09:15 + 5(b+1) min.
- **channel** is Donchian's: the high and low of the previous 20 five-minute bars of that index (R72 `Panel`).
- **day open** is the first bar's open.

| id | rule | traded indices | index stop (exit set XI) | original | grid |
|---|---|---|---|---|---|
| **M1 Late-day momentum on high-volatility days** | At the close of bar 65 (14:45), let r = close / previous session's last close - 1, and sigma = the mean absolute close-to-close daily return over the previous 20 sessions. Signal when **|r| >= k x sigma** AND today's high-low range through bar 65 is **>= the median of the previous 20 sessions' high-low range through bar 65** (a high-volatility day). Buy the call if r > 0, the put if r < 0. | all 5 (each on its own series) | the previous session's last close | k = 0.5 | k {0.5, 1.0} |
| **V1 VIX / index divergence, faded** | Checks only at the closes of bars 14, 26, 38 and 50 (10:30, 11:30, 12:30, 13:30). Let ri = close / day open - 1 and rv = VIX 5-min close / VIX first print - 1. If **ri >= +k and rv >= +2%**, buy the **put** (a rally on rising fear is fragile). If **ri <= -k and rv <= -2%**, buy the **call**. First signal of the day per index only. | all 5 | put: the day's high so far; call: the day's low so far | k = 0.2% | k {0.2%, 0.4%} |
| **V2 VIX-confirmed Donchian break** | A Donchian-20 break up (close above the channel high), taken only when VIX's change over the last 6 bars of the same day (VIX close at bar b / at bar b-6 - 1, so from bar 6 on) is **< -x** for calls; puts need a break down and **> +x**. | all 5 | the channel middle | x = 0 (VIX strictly falling / rising) | x {0, 1%} |
| **B1 Stock-breadth thrust** | Let s(b) = the share of stocks above their VWAP. From bar 6 on, a call signal when **s(b) >= T and the lowest s over bars b-6 .. b-1 of the same day is <= 1 - T** (most of the large caps flip above their VWAP within 30 minutes). A put when s(b) <= 1 - T and the highest of the previous 6 is >= T. First such bar per side per day. | NIFTY, BANKNIFTY, FINNIFTY, SENSEX (large-cap indices; not MIDCPNIFTY) | call: the day's low so far; put: the day's high so far | T = 0.7 | T {0.7, 0.8} |
| **B2 Volume-confirmed Donchian break** | A Donchian-20 break on bar b, taken only when the stocks' total volume in that bar is **>= m x its mean at the same bar of the day over the previous 20 sessions** that have stock data (participation confirms the break). | NIFTY, BANKNIFTY, FINNIFTY, SENSEX | the channel middle | m = 1.5 | m {1.5, 2.0} |

Rules V2 and B2 are **filters on Donchian 20**, so a high overlap with Donchian is expected and will be reported. M1,
V1 and B1 are new entry signals.

## 4. Exits, measurement, periods and gates (as R71 / R72)

- **Exits:**
  - XB: -15% / +30% / 30 min.
  - XL: -15%, the 30/60/100 ladder + L25, 30 min.
  - XI: the index stop above + a -25% safety stop + ladder + L25, 60 min.
  - Square-off 15:15 for all three.
- **Original vs re-tune:**
  - Original = the original parameter with XL.
  - Re-tune = 2 parameters x 3 exits, picked walk-forward (R51 section 4). A fold's pick needs >= 20 trades before the
    fold. The final pick is the best design net with >= 30 trades.
- **Pricing:** R64's engine on R51's Amendment A clean stores, dated charges, R17 fills, 1 lot. Code: R71's study code
  unchanged, with R74's rules plugged in (as R72 did).
- **Periods:** design 20 Nov 2024 - 31 Mar 2026; walk-forward quarters Apr 2025 - Mar 2026; final 1 Apr - 13 Oct 2026,
  read **once** (`results/FINAL_READ`).

**Gates per test** (5 originals + 5 re-tunes = **10 tests**; never relaxed):
1. final net > 0;
2. random-entry twin p < 0.05 (500 twins, seed 74; same day, index and side; entry minute uniform in 09:20-15:10; the
   same exits; for XI, the twin's index stop sits the same number of points from its entry);
3. BH q <= 0.10 across the 10;
4. >= 40 final trades;
5. net without the top 5 trades > 0;
6. design and walk-forward nets > 0.

A test that passes every gate is offered for **paper only**, and only if its final net stays > 0 at twice the slippage.
BH across all 38 tests run on 16 Oct (R71 16 + R72 12 + R74 10) is reported beside the R74 q.

**Comparison** with **Donchian XB** and **Breadth Donchian (R72 C1, M = 4, XL)** on the same days:
- net, win, worst day, max drawdown, green days, trades a month;
- daily correlation and same-trade overlap with each;
- the combined book.

A correlation >= 0.6 with either is labelled a duplicate: a new arm should add, not copy.

## 5. Honest notes, said before the result

- The final window (1 Apr - 13 Oct 2026) has been read by about 25 studies (R49-R72), and Donchian and Breadth Donchian
  were both chosen on it. A pass there is weak evidence. Design and walk-forward carry most of the weight, and only
  paper trading from 14 Oct is truly unseen.
- The stock data starts 2 Sep 2024 with today's 59 stocks (survivorship). B1 and B2 have no warm-up problem by
  20 Nov 2024 (more than 20 sessions of history).
- Every test gets an entry in `research/experiments/entries/` (failures included):
  - family `patterns-indicators` for M1 and B1;
  - family `daily-filters` for V2 and B2 (filters);
  - family `flow-oi` for V1 (it reads VIX as a signal partner).
