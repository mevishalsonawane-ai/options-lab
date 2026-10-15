# R62 pre-registration: anchored Volume Profile + Smart-Money-Concepts entries (written 15 Oct 2026, before any P&L)

Boss, 15 Oct 2026, shared a strategy from a video:
- a Volume Profile (row size 50, value area 70%, profile and POC shown), anchored from swing low to swing high in an
  uptrend and from swing high to swing low in a downtrend;
- the POC (the price with the most volume): when it lines up with an Order Block (OB), price tends to come back to it and
  reverse, so trade the reversal in the trend's direction;
- low-volume / zero-volume nodes inside the profile (treated like Fair Value Gaps) attract price; a pullback into them
  gives a reversal;
- stop just beyond the swing low (longs) / swing high (shorts) or the zone; target at least 1:2, or the structural
  high / low; risk 1-2% of capital a trade;
- confluence filters: POC on a clean OB; an FVG lined up with a volume vacuum; trade only with the higher-timeframe
  market structure (BOS / ChoCH).

R62 turns every subjective word into a fixed, mechanical rule (below), tests it honestly, and reports it. Research
only: nothing in the app changes because of this file.

## 1. Data (Drive lake `gdrive:IraAlgo-data-lake`, fetched with rclone 15 Oct 2026; raw files never go to git)

| what | lake file | used for |
|---|---|---|
| index 1-minute | `index_1m/index_1m_<U>_2024..2026.parquet` | every index signal |
| index options | `options_rolling_1m/opt_<U>_WEEK_2024..2026.parquet` (rolling ATM -10..+10, nearest expiry) | the held option traded; volume source (a) |
| stock 1-minute | `equity_1m/equity_1m_2024..2026.parquet` (59 symbols, to 12 Oct 2026) | stock signals and cash trades; index volume source (b) |
| stock options (context) | `stock_options_rolling_1m/stkopt_<SYM>_MONTH.parquet` + `nse_bhavcopy` | the stock-option version only |

- U = NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX.
- Window (Boss, 14-15 Oct): trades on sessions 20 Nov 2024 onwards only. Bars from 1 Sep 2024 are read only to warm up
  the swing / structure state; nothing before 20 Nov 2024 is traded or reported.
- **Design:** 20 Nov 2024 - 31 Mar 2026. **Final test:** 1 Apr 2026 - the lake's last session (13 Oct 2026 for the
  indices, 12 Oct 2026 for stocks). Read ONCE by `r62study.py final`, which writes `results/FINAL_READ` first and
  refuses to run again. Earlier studies have seen the indices' 2026 sessions, but never with these rules.
- No book crosses the boundary: a design position still open is closed at the session's square-off on 31 Mar 2026; the
  final book starts flat on 1 Apr 2026. Structure / legs are warmed up on all earlier bars (the signal state is
  causal, so this is not look-ahead), but no final trade comes from a leg confirmed before 1 Apr 2026.
- Sessions: an index or stock session needs >= 300 one-minute bars (drops the Muhurat hours).

## 2. The volume problem (stated before any P&L)

An index has no traded volume. The lake's `index_1m` volume column is 0 until Dec 2025 and filled only from Dec 2025
(source unknown); index futures minutes (`fut_1m_live_index`) start 29 Jul 2026; MCX futures minutes
(`fut_1m_live_mcx`) start 20 Oct 2025. So a true volume profile of the index exists for none of the design period.
ONE primary volume source is fixed per market; the others are reported as secondary, never gated:

| market | PRIMARY (gated) | secondary (reported, not gated) |
|---|---|---|
| 5 indices | **(a) option-chain volume**: each minute, the summed traded volume of every cleaned contract of the index's nearest-expiry rolling chain (CE + PE, ATM -10..+10), placed on that minute's index bar | (b) constituents: each minute, the summed traded value (close x volume) of the index's constituents in `equity_1m`, placed on the index bar. NIFTY = the 50 current NIFTY 50 names in the lake; BANKNIFTY = HDFCBANK, ICICIBANK, SBIN, KOTAKBANK, AXISBANK, INDUSINDBK, BANKBARODA, FEDERALBNK, IDFCFIRSTB, PNB, AUBANK, CANBK (current-list survivorship stated). Not built for FINNIFTY / MIDCPNIFTY / SENSEX (constituents missing from the lake). (c) TPO: every minute counts 1 (time at price) |
| 10 stocks | **real stock volume** (equity_1m) | (c) TPO |
| MCX | not tested | MCX futures minute volume covers 20 Oct 2025 onwards only: less than half the window (5 of 16 design months). Excluded, stated. |

- Why (a) is primary for indices: it is the only real traded volume available for all five indices over the whole
  window. Its weakness, stated now: option volume measures option traders' activity (it piles up at the open, on fast
  moves and on expiry days), not the index's own trading.
- Negative or missing volumes (the lake has a few int32 overflows) count as 0.
- The comparison point: R52's H37-10 "open outside value" (a TPO value-area rule) is the only index rule that has
  passed on Nov 2024+ data. The write-up quotes its numbers next to R62's TPO results.

## 3. Bars and timeframes (pre-listed)

- One-minute grid: row r = 09:15 + r minutes, rows 0..374. Missing minutes stay missing (nothing filled).
- **Execution:** 5-minute bars, aligned to 09:15 (bar j = rows 5j..5j+4). **Structure:** 15-minute bars (rows
  15j..15j+14). **Higher timeframe (HTF):** 1-hour bars aligned to 09:15 (rows 60j..60j+59; the 15:15 bar has 15 minutes).
- A bar is built from the minutes present (O = first open, H = max, L = min, C = last close, V = sum). A bar with no
  minute does not exist. Bars of all sessions are joined in time order (structure carries across sessions).
- A bar's values are known only at its last row; anything that uses it acts from the next minute.

## 4. Mechanical definitions (unit-tested in `test_r62.py` on hand-made bars)

**Swing high / low (fractal, N = 2 on 15-min and 1-hour; N = 2 on 5-min for internal structure).** Bar i is a swing
high when H[i] > H[i-1], H[i] > H[i-2] and H[i] >= H[i+1], H[i] >= H[i+2]; a swing low is the mirror. It is CONFIRMED
only at the close of bar i+2: no rule may see it earlier (look-ahead guard).

**Structure, BOS and ChoCH.** Walk the bars in order. Keep the latest confirmed swing high and swing low that has not
been broken yet. At a bar's close, after adding swings confirmed at that close:
- close > the unbroken swing high -> a bullish break; it is a **ChoCH** when the state was bearish, else a **BOS**;
  the state becomes bullish and that swing high is used up;
- close < the unbroken swing low -> the bearish mirror.
- The **trend** is the state: bullish after a bullish break (the swings then print HH / HL), bearish after a bearish
  break (LH / LL), none before the first break.

**Anchored leg (the profile's anchor).** On the 15-minute structure, when a swing high at bar h is confirmed while
the state is bullish, the leg is [l, h], l = the latest swing low bar before h, provided a bullish break (BOS or
ChoCH) happened on a bar in (l, h]. Mirror for bearish legs (swing high to swing low). The leg covers the one-minute
rows from the first minute of bar l to the last minute of bar h. The leg becomes active at the close of bar h+2.

**Volume profile over the leg.** 50 rows of equal height between the leg's low and high (row size = range / 50).
Each minute's volume is spread evenly over the rows its high-low range touches (a minute with H = L puts all of it in
its row). **POC** = the row with the most volume (ties: the row nearest the leg's middle, then the lower row).
**Value area 70%**: start at the POC; add whichever neighbour row has more volume (ties: the upper one) until >= 70%
of the volume is inside; VAL / VAH are its edges (reported, not traded). A profile needs volume on >= 80% of the leg's
minutes (else no profile: no setup from that leg).

**Low-volume node (LVN).** A row with volume < 20% of the POC row's volume (zero-volume rows included), among the
inner 40 rows only (rows 5..44; the first and last 5 rows are always thin because price only passed through them).
The **LVN zone** of a bullish leg is the highest contiguous run of inner LVN rows (the first one a pullback from the
high meets); of a bearish leg, the lowest run.

**Fair Value Gap (FVG), 5-minute.** Bullish FVG at bar i: L[i] > H[i-2]; the gap is [H[i-2], L[i]]. Bearish: H[i] <
L[i-2]; gap [H[i], L[i-2]]. Only FVGs whose three bars lie inside the leg count.

**Order Block (OB), 5-minute, full range.** A bullish internal break: a 5-minute close above the latest unbroken
confirmed 5-minute swing high (N = 2). Its bullish OB is the last bearish 5-minute candle (C < O) before that break
bar (searched back at most 10 bars). Zone = the candle's FULL range [L, H] (chosen over the body). It is "clean" when
no 5-minute low traded back into it (L <= its H) between the break bar and the end of the leg. Only clean OBs whose
candle and break bar lie inside the leg count. Bearish OBs are the mirror.

## 5. The setup, entry and exits

**Zones.** POC zone = the POC row [bottom, top]. LVN zone = section 4.
- POC confluence (OB): the POC row overlaps a clean OB of the leg's direction.
- LVN confluence (FVG): the LVN zone overlaps an FVG of the leg's direction.

**Active leg.** One leg at a time per instrument (a newly confirmed leg replaces the old). A leg is cancelled by: a
5-minute close beyond its anchor (below the swing low for bullish legs), a 15-minute structure flip against it, a newer
leg, the end of the session AFTER the one it became active in, or its first trade (one trade per leg per variant).
A zone is only tradeable if the 5-minute close at activation was on the far side of it (above a bullish zone).

**Entry (long; short is the mirror).** At the close of 5-minute bar j, when bar j-1 touched the zone (low <= zone top)
and bar j is green (C > O) and closes above the zone top: signal. Signal bars closing 09:24 - 14:29; fill at the next
minute's open (index option: the first print of the held contract in that minute and the next 2, R51 engine; stock:
the stock's open). At most one open position per instrument per variant.

**Stop (the video's "just beyond the swing low").** Long: the leg's swing low minus 0.02% of price; short: swing high
plus 0.02%. On the underlying: the first minute whose low <= stop (long) triggers it.

**Target.** `2r`: entry price on the underlying (the fill minute's open) + 2 x (entry - stop). `st` (structural): the
leg's swing high (long) / swing low (short). On the underlying: the first minute whose high >= target.
A minute that reaches both counts as the stop.

**Exits on the index option** (signal on the index, trade the option): stop / target are index levels; the option is
sold at the next minute's open after the index minute that hit them (R51 engine forced exit). Square-off 15:15, 15:05
on expiry days. Nothing carried overnight.
**Exits on the stock (cash, MIS):** stop is a resting SL-M (fills at min(stop, open) for longs, stop slippage x2);
target a limit (fills at max(target, open)); square-off 15:10 (the lake's Aug-Oct 2026 stock sessions end 15:14).

## 6. Variants (16 rules, applied to each primary book)

zone {`poc`, `lvn`} x confluence {`x` off, `c` on (OB for poc, FVG for lvn)} x HTF filter {`x` off, `h` on: the
1-hour structure state at the signal is the trade's direction} x target {`2r`, `st`}. Names: e.g. `poc-c-h-2r`.

**Primary books (gated, one BH family of 16 x 2 = 32 tests):**
- `IDX`: the 5 indices pooled, volume source (a); buy the **1-ITM** CE (long) / PE (short) of the nearest expiry
  (CLAUDE.md default strike rule), 1 lot in force on the date (R51 lot table), held contract.
- `STK`: the 10 stocks pooled (R58's universe: RELIANCE, SBIN, MARUTI, ICICIBANK, INFY, LT, KOTAKBANK, M&M, TRENT,
  TATASTEEL - its pre-registered liquidity rule), real volume, the STOCK in cash intraday (MIS), long and short,
  quantity = 1 F&O lot of the stock on the date (R58 `results/lots.json`).

**Secondary / context (reported with the same statistics, never gated):**
- `IDX-b` (NIFTY, BANKNIFTY), `IDX-c` (5 indices): the same rules with volume sources (b) / (c).
- `STK-c`: stocks with TPO.
- `STK-sw`: stocks, 1-5 day swing in delivery, LONG only (a cash short cannot be held overnight), real volume; the same
  entries, stop and target, held up to 5 sessions (15:10 of the 5th), never across R58's ex-dates (KOTAKBANK 14 Jan
  2026, TRENT 4 Jun 2026).
- `STK-opt`: stocks, the ATM nearest-monthly STOCK OPTION instead, on R58's engine unchanged (held contract, per-stock
  fitted spread, band exit, expiry cut-off, lots).

## 7. Measurement and costs

- **Index option:** R51's engine (`research/hunt/r51/engine.py`) with the R51 Amendment A cleaned store (foreign rows
  and stray prints removed, `research/hunt/lakeclean.py`) plus `research/data/spikes.py`'s spike rule applied to
  contract-minutes (R57's `spike_mask`). One HELD contract per trade (never the rolling ATM series). R17 'liq' fills
  (buy +5 bps + 1/2/4 ticks by the contract's last-5-minute volume; sells -5 bps; stop-type sells use the market sell
  since the exit is a next-minute market order on an index trigger). Charges `obuy.costs.Costs('dated')` (STT
  0.1% / 0.15% from 1 Apr 2026, BSE for SENSEX). **2x:** R51's SLIP2 (double bps and ticks) and twice the charges.
- **Stock cash:** R58's `cash_charge` (MIS / delivery) and slippage max(Rs 0.05, 1 bp) a side, stop fills x2. 2x =
  double both.
- **Sizing:** gates use 1 lot. As information only, a 1%-of-Rs-1-lakh risk size (Rs 1,000 at risk to the stop) is
  reported: stocks: floor(1,000 / (entry - stop)) shares, capped at 5x leverage; index options: the number of lots
  whose index-point risk x lot x 0.6 (an assumed 1-ITM delta) fits Rs 1,000 (often 0, stated).

## 8. Statistics and gates (the same as R47-R61)

- Random-entry twins (500 per test, seed 62): each real trade is re-drawn on the same instrument, session and side, at
  a random fill minute in 09:25-14:30, with the same stop and target distances in % of the underlying and the same
  strike rule / square-off. p = (1 + #twins with net >= real net) / 501.
- Benjamini-Hochberg across all 32 primary final p-values.
- **PASS** needs all of: final net > 0 at 1x; p < 0.05; q <= 0.10; 40+ final trades; top 5 trades < 100% of final
  net; positive in design (design net > 0 AND net > 0 in >= 3 of the 5 design quarters 2025Q1 (with 20 Nov - 31 Dec
  2024), 2025Q2, Q3, Q4, 2026Q1).
- Reported per book and variant, design and final, 1x and 2x: trades, win rate, avg win / loss, realised R multiple
  (underlying move to exit / entry-to-stop distance), net, worst day, max drawdown, months green; per instrument
  trades and net.

## 9. What would change the app

Nothing directly. A PASS is at most a paper candidate for Boss, labelled "signal on the underlying", never live without
unseen paper first (CLAUDE.md safety rules).

## Amendment 0 (15 Oct 2026, after the data build, BEFORE any P&L was computed)

Decided while building the data and the engine; no result of any rule had been seen.
1. **Cash quantity.** `equity_1m` is split-adjusted (KOTAKBANK reads ~Rs 425 in early Jan 2026, before its 1:5
   split). A dated lot on adjusted prices would mis-size trades before a split, so a cash trade's quantity is the
   stock's lot in the LATEST bhavcopy R58 read (`r58/results/lots.json`), on today's share scale, for every date.
2. **Volume source (b).** A minute's constituent value counts only when >= 80% of the listed names printed that minute
   (else it is unknown, NaN); the profile's 80%-coverage rule then applies as written.
3. **Twins.** A random draw that cannot fill (no contract / no print / no stock open) is redrawn up to 5 times (as in
   R58); a twin that never fills adds 0.
4. **Last session.** The index lake runs to 14 Oct 2026 (stocks to 12 Oct 2026); the final test runs to those days.
5. **STK-opt** is reported only if R58's books can be rebuilt in this session (they need the stock-option files and the
   bhavcopies); it uses R58's engine unchanged, so its stock stop / target fire on a minute CLOSE beyond the level
   (R58's rule), not on the low / high as in the cash book. Stated, context only.
6. Index options use R51's market-sell fill for every exit (the exit is a next-minute market order after an index
   trigger, not a resting option stop).
