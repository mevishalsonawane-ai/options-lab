# R58 pre-registration: buying STOCK options on the top 10 stocks (written 15 Oct 2026, before any P&L)

Boss, 15 Oct 2026: "If we shift from index to actual funds like top50 companies ... Start a deep study on the stock
options now, start with top 10". The index-option studies R47-R55 found almost nothing after costs on Nov 2024+ data
(research/FINDINGS.md). R58 asks whether single stocks do better.

Research only. Nothing in the app changes because of this file.

## 1. Data (Drive lake `gdrive:IraAlgo-data-lake`, fetched with rclone 15 Oct 2026; raw files never go to git)

| what | lake file | used for |
|---|---|---|
| stock 1-minute | `equity_1m/equity_1m_2024..2026.parquet` (to 12 Oct 2026; Aug-Oct 2026 sessions end 15:14) | every signal |
| stock daily | `equity_daily/equity_daily.parquet` (to 12 Oct 2026) | previous close, daily rules |
| stock options | `stock_options_rolling_1m/stkopt_<SYM>_MONTH.parquet` (rolling ATM-5..+5, nearest monthly, to 14 Oct) | the option traded |
| NIFTY 1-minute | `index_1m/index_1m_NIFTY_2024..2026.parquet` | relative strength only |
| NSE F&O bhavcopy | `nse_bhavcopy/*.csv.zip` (UDiFF, one day a week) | universe, lot sizes, expiry dates |

- Window: trades on sessions 20 Nov 2024 - 12 Oct 2026 (the last stock-minute session). Earlier data (from
  1 Sep 2024) only warms up indicators.
- **Design:** 20 Nov 2024 - 31 Mar 2026. Walk-forward by quarter.
- **Final test:** 1 Apr 2026 - 12 Oct 2026. Read ONCE by `r58study.py final`, which writes `results/FINAL_READ` first
  and refuses to run again. Earlier studies have seen parts of 2026 for the indices, but not these stock options.
- No book crosses the boundary. In design, a position still open is closed at 15:15 on 31 Mar 2026. The final book
  starts flat on 1 Apr 2026.
- Sessions: a stock session needs at least 300 one-minute bars of the stock. This drops the 21 Oct 2025 Muhurat hour.

## 2. Universe (pre-registered rule; numbers in `results/universe.json`)

1. Candidates: the 50 current NIFTY 50 names (niftyindices.com `ind_nifty50list.csv`, fetched 15 Oct 2026). This
   carries survivorship bias, which is stated in the write-up.
2. Rank by stock-option traded value in the design period: the sum of `TtlTrfVal` (NSE's traded value, notional) over
   the STO rows of the 70 weekly bhavcopies dated 25 Nov 2024 - 25 Mar 2026.
3. Keep names with **ATM coverage >= 95%**. Coverage = the share of design sessions on which the lake holds BOTH the
   ATM CE and the ATM PE (offset 0) for at least 300 of the 375 minutes. This is measured on the minute rows, not taken
   from `STOCK_OPTIONS_COVERAGE.json`. That file shows >= 99% chunk coverage for BSE and BAJFINANCE, but their option
   rows only start in mid-2025, around their corporate actions.
4. Take the first 10 that pass.

| rank | stock | design option value (Rs crore, notional) | ATM coverage | ±1 coverage | in |
|---|---|---|---|---|---|
| 1 | RELIANCE | 13,94,474 | 97.6% | 97.6% | yes |
| 2 | HDFCBANK | 10,02,021 | 91.4% (no ATM PE Dec 2025 - Jan 2026) | 97.0% | no |
| 3 | SBIN | 8,71,243 | 97.6% | 97.3% | yes |
| 4 | MARUTI | 8,16,828 | 97.6% | 97.6% | yes |
| 5 | BSE | 7,73,224 | 62.9% (no rows before Jun 2025) | 61.4% | no |
| 6 | ICICIBANK | 6,52,592 | 97.6% | 97.3% | yes |
| 7 | BAJFINANCE | 6,42,086 | 57.6% (no rows before Jun 2025) | 56.7% | no |
| 8 | INFY | 6,26,601 | 97.6% | 97.3% | yes |
| 9 | TCS | 6,18,751 | 91.7% (no ATM CE Feb - Mar 2026) | 95.8% | no |
| 10 | BHARTIARTL | 5,67,689 | 92.0% | 97.3% | no |
| 11 | AXISBANK | 4,80,674 | 91.4% | 97.3% | no |
| 12 | BEL | 4,56,179 | 92.0% | 97.3% | no |
| 13 | LT | 4,11,412 | 96.7% | 90.2% | yes |
| 14 | KOTAKBANK | 4,06,543 | 97.6% | 97.0% | yes |
| 15 | M&M | 4,05,866 | 97.6% | 97.3% | yes |
| 16 | TRENT | 3,91,181 | 97.6% | 97.0% | yes |
| 17 | TATASTEEL | 3,88,138 | 95.5% | 88.7% | yes |

**R58 universe:** RELIANCE, SBIN, MARUTI, ICICIBANK, INFY, LT, KOTAKBANK, M&M, TRENT, TATASTEEL.

- 97.6% is the most any stock can reach: 8 design sessions are missing for every stock (e.g. 3-4 Dec 2024,
  1-5 Aug 2025).
- **±1 strikes** are used only by the two "1-ITM" variants (section 5, F6). They trade only the stocks with ±1
  coverage >= 95%, which excludes LT and TATASTEEL.
- No stock has ±2 rows in this window, which is why the band exit below exists.

## 3. Contracts, lots, expiry, physical settlement

- **Option:** the nearest monthly expiry (the lake's MONTH series). The expiry dates come from the bhavcopy STO
  `XpryDt` of each stock. A lake row dated d belongs to the first expiry on or after d.
- **Strike:** ATM = the strike nearest the option rows' `spot` at the signal minute, among the strikes present in that
  minute's rows. 1-ITM = one strike step in the money (step = the gap between that minute's ATM and ±1 strikes).
- **HELD contract:** a trade is ONE contract (expiry, strike, side) from entry to exit. Its prices are every lake row
  that carried that contract, at any offset. The rolling ATM series is never used for P&L (lesson of R45 / R46).
- **Lot:**
  - For the contract on the trade date, the lot is the `NewBrdLotQty` of that contract in the latest bhavcopy on or
    before the date (if none, the earliest after).
  - Cross-check: the largest of that stock's bhav lots that divides >= 90% of the contract's traded volumes that day
    (R49's `lot_from_volume`). If that check finds a bhav lot of the contract, it wins; the counts are reported.
  - Lots change inside a contract's life (KOTAKBANK 400 -> 2000, TATASTEEL 5500 -> 2750, TRENT 100 -> 150 -> 225),
    so the lot is always read per contract and date. Source table: `results/lots.json`.
- **Physical settlement cut-off (pre-registered):**
  - Let T be the expiry session and T-1, T-2, T-3 the stock sessions before it. **No entries on T-2, T-1 or T.** Every
    position is closed by **15:15 on T-3**. So no trade ever touches the last 2 trading days before expiry (or expiry
    day).
  - CONTEXT ONLY (never gated): the same rules re-run with no cut-off. Entries are allowed on T-2..T, intraday exits at
    15:15 (15:05 on T), and swing positions run to their own exit or 15:05 on T.
- **Corporate actions:**
  - A session is an ex-date when the day's median ratio (option `spot` / adjusted stock close) moves by > 3% from the
    previous session and stays there (in this universe: KOTAKBANK 14 Jan 2026, TRENT 4 Jun 2026).
  - No position is held across an ex-date: positions close by 15:15 on the session before it.
  - A single session whose ratio is > 3% away from both neighbours is a bad data day for that stock (LT 9 Jul 2025):
    no entries and no holding through it.

## 4. Pricing, fills, costs

- **Signal:** on the STOCK (adjusted 1-minute bars; daily bars from `equity_daily`) and, for relative strength,
  NIFTY. Option data is never a signal input (CLAUDE.md "signal on the underlying"). Stops and targets may be on the
  option premium.
- **Data hygiene (before anything else):**
  - **Stray rows:** a row is dropped when `spot` is more than 3% off the stock's unadjusted price that minute
    (adjusted close x the day's ratio). This is the r47data pattern; prices are adjusted for splits and bonuses.
  - **Spikes:** `research/data/spikes.py spike_flags` minutes are dropped.
  - Nothing is filled in.
- **Entry:** the first print of the held contract in the 3 minutes after the signal minute, at that minute's open.
  With no print, there is no trade (counted).
- **Exits**, checked each minute in this order:
  1. premium stop (resting; filled at min(level, open));
  2. underlying stop;
  3. underlying target;
  4. premium target;
  5. rule-specific exit (VWAP cross);
  6. time exit;
  7. band exit;
  8. square-off at 15:15 of the last holding session (expiry cut-off, ex-date).
  Underlying and premium signal exits sell at the next minute's open. A premium stop can only fire on a print.
- **Band exit (every rule):**
  - The lake has no ±2 strikes for these stocks, so a held contract has prices only while it is within one strike of
    ATM.
  - Every rule therefore sells at the next minute's open once a stock minute closes >= 1.25 strike steps away from the
    held strike, or at the open after an overnight gap that does so.
  - This is a real, tradeable underlying exit. It caps the upside of a held ATM option at roughly a 1.25-step move.
  - CONTEXT ONLY: the swing rules again with no band exit, priced beyond the band by Black-Scholes with the contract's
    last observed IV (labelled "modelled").
- **Missing exit price:**
  - Use the first print in the next 3 minutes.
  - If there is none, use the contract's last print before the exit minute on the same session ("stale").
  - If the contract has no print on that session at all, use Black-Scholes with its last IV ("model"; r = 6.5%,
    T = calendar days to 15:30 on expiry / 365).
  - Stale and model exits are counted. Every passing rule is also shown with those exits at intrinsic value.
- **Spread and slippage, fitted per stock:**
  - The fit uses the R17 'liq' template on DESIGN rows only, at build time, before any P&L.
  - Each order pays the R17 bps (5 market, 10 stop) plus a half-spread in rupees. The half-spread is the larger of:
    - R17's ticks (>= 20 lots in the contract's 5 minutes before the order: 1 tick; 1-20 lots: 2; none: 4; stops x2;
      tick Rs 0.05);
    - the stock's fitted Roll half-spread for that volume bucket (as a fraction of price), from 1-minute close changes
      of the stock's ATM contracts on design sessions (Roll = 2 sqrt(-cov(dp_t, dp_t-1)); undefined when the
      covariance is >= 0, then R17 ticks alone).
  - The table goes to `results/spread_fit.json`.
- **Charges, option:** `obuy.costs.Costs("dated")`. Brokerage Rs 20 an order; STT 0.1% of sell premium to 31 Mar
  2026, 0.15% from 1 Apr 2026; NSE 0.03503% + IPFT 0.0005%; SEBI Rs 10/crore; stamp 0.003% of buys; GST 18%.
- **2x costs:** twice the spread + bps and twice the charges. Gates are judged at 1x; 2x is reported next to them.

## 5. Rules (38 variants; every rule: 1 lot, ATM CE for long / ATM PE for short, a stop, a time exit, band exit, cut-off)

Grid: row m = the 1-minute bar starting 09:15 + m. A signal on the close of row s fills from row s+1. Intraday rules:

- entries from 09:20 to 14:30 unless stated;
- one trade per stock per rule per day unless stated;
- square-off 15:15 (row 360).

Swing rules hold up to H sessions (the entry session counts as 1) and exit at 15:15 of session H, or earlier by
stop, band exit or cut-off. A rule never holds two positions in the same stock (except F6 Donchian: one a side).

**F1 Opening-range breakout on the stock** (range = rows 0..M-1; the first 5-minute bar closing above the range high
-> CE, below the low -> PE; only the day's first breakout; 5-minute bars closing by 13:00)
- `orb15-rs`, `orb30-rs`: M = 15 / 30; stop = the range's other side (stock minute close beyond it); 15:15.
- `orb15-ps`, `orb30-ps`: premium stop -30%; 15:15.
- `orb15-rs-t`, `orb30-rs-t`: range-side stop + premium target +50%.

**F2 Relative strength vs NIFTY**
- Intraday `rs-i-<time>-<thr>`:
  - At decision minute 10:15 or 11:30, r_s = stock close / today's open - 1 and r_n = the same for NIFTY.
  - Long when r_s >= +thr and r_n <= +0.10%. Short when r_s <= -thr and r_n >= -0.10%.
  - thr 0.5% / 1.0%.
  - Stop: the stock back through today's open, or premium -30%. Exit 15:15.
  - Variants: `rs-i-1015-050`, `rs-i-1015-100`, `rs-i-1130-050`, `rs-i-1130-100`.
- Daily `rs-d-l<L>-h<H>`:
  - At day t's close, excess = stock L-session return - NIFTY L-session return. Long when excess >= X and NIFTY's
    L-session return <= 0; short is the mirror.
  - X = 2% for L = 1, 3% for L = 3.
  - Buy at 09:20 on t+1 (signal row 4).
  - Stop: a stock minute close beyond day t's low (long) / high (short), or premium -40%. Hold H = 1 or 3.
  - Variants: `rs-d-l1-h1`, `rs-d-l1-h3`, `rs-d-l3-h1`, `rs-d-l3-h3`.

**F3 Gap-and-go / gap-fade** (gap = row 0 open / previous daily close - 1; first 15-minute bar = rows 0-14,
decided at row 14, fill from row 15)
- `gap-go-1`, `gap-go-2`:
  - gap >= +1% / +2% and a green first bar -> CE; gap <= -1% / -2% and a red bar -> PE.
  - Stop: beyond the first bar's other extreme. 15:15.
- `gap-fade-1`, `gap-fade-2`:
  - gap up and a red first bar -> PE; gap down and a green bar -> CE.
  - Stop: beyond the first bar's extreme.
  - Target: the previous close touched (the gap filled). 15:15.
- `gap-go-1-ps`, `gap-fade-1-ps`: as the 1% versions, with a premium stop of -25% instead of the stock stop.

**F4 Volume surge and VWAP trend**
- Volume surge `vol-k<k>-<exit>`:
  - Slot average = the mean volume of the same 5-minute slot over the previous 20 sessions (>= 15 present).
  - A 5-minute bar closing 09:45-14:30 with volume >= k x its slot average and a close above every earlier high of the
    day -> CE; below every earlier low -> PE. The day's first surge signal only.
  - Stop: beyond the surge bar's other extreme. Exit 60 minutes after entry or 15:15.
  - k = 2.5 / 4. Variants: `vol-k25-60`, `vol-k25-eod`, `vol-k4-60`, `vol-k4-eod`.
- VWAP trend `vwap-<time>`:
  - At 10:00 / 10:30 / 11:30: close > VWAP x 1.002 and VWAP rising (above its value 30 minutes earlier) -> CE; the
    mirror -> PE.
  - VWAP is from 09:15 on the typical price and the stock's volume.
  - Exit: the first 5-minute close back across VWAP (next minute), premium stop -30%, or 15:15.
  - Variants: `vwap-1000`, `vwap-1030`, `vwap-1130`.

**F5 Multi-day momentum / Donchian 20 (daily bars, swing)**
- `dc20-h1`, `dc20-h3`, `dc20-h5`:
  - Day t's close > the highest high of the 20 sessions before t -> CE at 09:20 on t+1; close < lowest low -> PE.
  - Stop: a stock minute close beyond the 10-session low (long) / high (short) known at t, or premium -40%.
  - Hold H = 1 / 3 / 5.
- `mom10-h3`, `mom10-h5`:
  - 10-session return >= +5% and close > 20-session SMA -> CE; <= -5% and < SMA -> PE. Entry at 09:20 on t+1.
  - Stop: beyond day t's low / high, or premium -40%. Hold H = 3 / 5.

**F6 The index rules that came closest, applied to stocks**
- R51 "Donchian-20-RT" (research/hunt/r51/fam_r44.py; R44 ind.donchian):
  - 5-minute stock bars, continuous across sessions, 100 warm-up bars.
  - Bar close > max of the 20 earlier bars' highs -> CE; < min of their lows -> PE. Fill at the next bar's open.
  - Entries 09:20-15:10. One open position a side; at most 2 fills a day a side.
  - `dc5-rt-atm`: R51's re-tuned exits (stop -15%, target +30%, 30 minutes), ATM.
  - `dc5-rt-itm1`: the same at 1-ITM, the 8 stocks with ±1 coverage >= 95%.
  - `dc5-a-atm`: R44's original exits (-25% / +50% / 60 minutes), ATM.
- R52 "H37-10 open outside value and go" (research/hunt/h37/sigs.py profile_levels + mp_signals MP_OOV, unchanged):
  - The previous session's 30-minute-TPO 70% value area.
  - Open > VAH and the first 5-minute bar's close > VAH -> CE; the mirror below VAL -> PE. Decided at row 4, fill from
    row 5.
  - `h37-60-atm`: out 60 minutes after entry, ATM.
  - `h37-60-itm1`: the same at 1-ITM (8 stocks).
  - `h37-30-atm`: out after 30 minutes.
  - R52's passing setting had no stop. This brief requires one, so all three carry a premium stop of -30%. That is a
    stated deviation.

**F7 Post-results drift: NOT tested.** There is no results calendar in the lake or the repo. Inferring results days
from the same day's move would be look-ahead.

## 6. Cash baselines (reported, not gated)

- The same trade in the stock: the same entry and exit minutes, the same number of shares as the option's lot.
- Intraday rules (F1-F4, F6) trade as MIS, both sides. Charges:
  - brokerage min(Rs 20, 0.03%) an order;
  - STT 0.025% of sells;
  - NSE 0.00297%;
  - SEBI Rs 10/crore;
  - stamp 0.003% of buys;
  - GST 18% on brokerage + exchange + SEBI.
- Swing rules (F2 daily, F5) trade as delivery, long only (a cash short cannot be held overnight; those rows show
  "n/a"). Charges:
  - brokerage Rs 0;
  - STT 0.1% each side;
  - stamp 0.015% of buys;
  - NSE 0.00297%;
  - SEBI;
  - GST;
  - DP Rs 15.34 a sell.
- Slippage: max(Rs 0.05, 1 bp) a side; stops x2. 2x = double both.
- Capital per trade: option = premium x lot. MIS = 20% of the shares' value. Delivery = 100%.

## 7. Statistics and gates (the same as R47-R55)

- **Random-entry twins (final test, 500 per variant, seed 58):**
  - Each real trade is re-drawn with the same stock, side and period.
  - The session is random among that stock's eligible sessions (not blocked by the cut-off, an ex-date or a bad day).
  - The decision minute is random within the rule's own decision window (fixed-time rules keep their time).
  - The exits are the same rule exits. Stock-level stops and targets keep the real trade's distance in % of the stock
    price; VWAP, time, band and cut-off are computed for the twin.
  - p = (1 + #twins with net >= the real net) / 501.
- **Benjamini-Hochberg** across ALL 38 final p-values (one family).
- **PASS** needs all of:
  1. final net > 0 at 1x costs;
  2. p < 0.05;
  3. q <= 0.10;
  4. 40+ final trades;
  5. the top 5 trades < 100% of the final net;
  6. positive in the design walk-forward.
- **Design walk-forward:**
  - The rules are fixed in advance, so every design quarter is out of sample for them.
  - The quarters are 20 Nov 2024 - 31 Mar 2025, 2025Q2, 2025Q3, 2025Q4 and 2026Q1.
  - Gate: design net > 0 AND net > 0 in >= 3 of the 5 quarters.
  - As context only, a family-level walk-forward is also reported: each quarter, pick the family's best variant on the
    quarters before it.
- **Reported for every variant, design and final, at 1x and 2x:**
  - trades, win rate, net, net/trade;
  - average win / loss, worst day, max drawdown;
  - months green, capital per trade (median premium x lot);
  - stale / model exit counts.
  - Per-stock trades and net. The net without the best stock: a PASS whose net is <= 0 without its best stock is
    labelled "carried by one stock".
  - Per-stock coverage (ATM, ±1) for design and final.
- **Tests (pytest, `research/hunt/r58/test_r58.py`):**
  - a look-ahead guard per signal (changing any bar after the decision minute changes nothing);
  - held-contract pricing (an ATM switch never changes the price series);
  - the expiry cut-off;
  - lot by date;
  - holidays;
  - missing minutes;
  - no data;
  - stray rows.

## 8. What would change the app

Nothing directly. A PASS is at most a paper candidate for Boss, labelled with its signal source, never live without
unseen paper first.
