# R60 pre-registration: does Solo work on every index and on MCX commodities?

Written 15 Oct 2026, **before any R60 profit, loss or model score was computed**. Commit this file first; results come
after. Research only: no `android/**`, no `.github/**`, no knowledge-pack rebuild.

Boss, 15 Oct: "Solo should work for all index and commodities." Solo is Jarvis's own trader and stays **paper only,
forever**. Today it is SoloMidday (`android/engine/.../orb/SoloMidday.kt`: NIFTY, BANKNIFTY, FINNIFTY, 4-ITM) and, being
built, Solo 2.0 = R57's A2 meta-labeller (setups S1-S4 on NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY). Under CLAUDE.md's
PAPER rule Solo may only act on a new market once its rules have PASSED the research gates there. R60 runs that test.

Lessons carried in: every label and P&L is on the HELD contract (R45, R46, R51); foreign option rows are dropped (R47);
one-minute bad prints are removed (lake `spikes.py`; R42's F1 filter for MCX); random twins draw the side by coin flip
(R52); MCX evening prints are the dirtiest window (R42); MCX metal options are thin, and GOLDM prints rose 3-5x in 2026
(R53), so design and final are not like for like on the metals.

## 1. Data, window and split

- Drive lake `gdrive:IraAlgo-data-lake`, copied with rclone to `~/.cache/iraalgo` (never committed, deleted at the end).
- **Indices:** `index_1m` (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, INDIAVIX) and `options_rolling_1m` WEEK files
  (nearest expiry; ATM-10..+10 with OI, IV, volume, spot). R57's day stores, rebuilt with SENSEX added (R57/R51 code,
  unchanged). Lots in force on each date (`r51cfg.LOTS`; SENSEX 20 from 28 Nov 2024, confirmed by the app's own Oct 2026
  SENSEX tickets). BSE charges for SENSEX (`obuy.costs.Costs('dated')`, `bse=True`).
- **MCX:** `options_rolling_1m` `opt_<SYM>_MONTH_*` for GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, NATURALGAS (near month,
  ATM-3..+3 used, 5 Aug 2025 - 13 Oct 2026; the lake has no MCX option minutes before 5 Aug 2025 and no 14 Oct). The
  near future's minute price is the per-minute median of the option rows' `spot` (R53's proxy; close-only). The lake has
  **no option files for CRUDEOILM or NATGASMINI** (and none for GOLDPETAL / SILVERMIC): they cannot be tested as option
  buyers and are reported as "no data". `fut_1m_live_mcx` (true OHLC, from 20 Oct 2025 only) is used only as a data
  check of the proxy, never for a signal (it does not cover the design start). USD/INR is not in the lake: no USD/INR
  feature.
- Trades only from **20 Nov 2024** (index; first session 21 Nov). Index minutes from 1 Sep 2024 only warm up indicators.
- **Split.** Index DESIGN 21 Nov 2024 - 31 Mar 2026; MCX DESIGN 5 Aug 2025 - 31 Mar 2026 (all the MCX minutes that
  exist before the final). **FINAL TEST 1 Apr 2026 - the lake's last session (13 Oct 2026), read ONCE** by
  `r60study.py final` (guard file `results/FINAL_READ`; the harness refuses to price a final-test session before it).
- Not pristine: R47-R59 already read parts of Apr-Oct 2026 (R53 the MCX book, R57 the four NSE indices). Only live paper
  from 15 Oct 2026 is truly unseen.

## 2. Measurement (never relaxed)

### Indices (R57 exactly)
- R51's engine, unchanged: next-minute fills (first traded minute up to 2 minutes later, else no trade), R17 "liq"
  fills, resting stops on the minute low (gap fills at the open), dated charges (STT 0.15% from 1 Apr 2026; BSE rates for
  SENSEX), 1 lot in force. Cleaning before any contract: |strike - spot| > 12 steps; foreign rows (spot > 3% off the
  index, R47); one-minute bad ticks (spikes.py rule). **No trade on an index's own expiry day.**

### MCX (R53's harness, `hunt/r53/mcx`, plus the rules below)
- **Held contract:** the strike is read at the signal minute from the rolling offsets, then that one (day, side,
  strike) contract is priced to its exit, on printed minutes only (volume > 0).
- **Cleaning before any contract:** R47 foreign / stray rows (an option row whose `spot` is more than 3% off that
  minute's median spot is dropped; the median is then recomputed); R42's F1 bad-print filter (k 5, 15 min, ratio 1.5) on
  printed rows; the lake `spikes.py` flag (a printed close > 50% away from both neighbours that revert) is applied too.
  Counts are reported per commodity.
- **Strike rule "ITM where liquid, else ATM":** at the fill window [e, e+3] (e = the minute after the signal minute),
  the 1-ITM contract (call: offset -1; put: +1) is bought at the open of its first printed minute; if it has no print in
  the window, the ATM contract is tried in the same window; neither: no trade.
- **Exits.** A future-level exit (stop / lock / target) is decided on a 1-minute close of the near-future proxy and the
  option is sold at the NEXT minute (its open if printed, else the next print's open within 10 minutes, else the last
  printed close, marked stale). An option-premium stop rests on the printed minute's low (gap through: the open). A time
  exit sells at the close of the exit minute (same fallback). Ties in one minute: option stop, future stop, lock,
  target, time.
- **Expiry and settlement:** MCX options devolve into futures (physical delivery chain) at expiry, so **no trade on an
  option expiry day** (R53's robust expiry detection), and on the day before expiry every position is out by
  min(rule exit, 23:00, session end - 15 min) (app `McxExpiry.optionExitAt`). Every R60 MCX trade is intraday.
- **Sessions:** 09:00 - 23:30 (US summer time) / 23:55 (US winter time), or the day's last bar + 1 minute if earlier
  (`McxSession.kt`). Holidays with only an evening session have no morning data, so the noon rule cannot fire there.
- **Costs:** Zerodha MCX option charges (`McxCosts.kt` = `r53/common.mcx_opt_charges`: Rs 20 a leg brokerage, CTT 0.05%
  of the sell premium, 0.0418% exchange, SEBI, 0.003% stamp, 18% GST; rates unchanged over the window). Spread, per
  side, as a share of premium: half of the commodity's full spread (crude 0.30%, natural gas 0.43%, gold / GOLDM 0.40%,
  silver / SILVERM 0.40%), doubled before 17:00 (R53), and **doubled again for a side whose contract printed nothing in
  the 5 minutes before** (thin big contracts GOLD / SILVER pay this most). Lots: CRUDEOIL 100, NATURALGAS 1,250, GOLD 100
  (1 kg, quoted per 10 g), GOLDM 10, SILVER 30, SILVERM 5 (`r53/mcx/data.MULT`). 1 lot.
- **2x costs** (reported, not gated): charges and spread both doubled.
- **Capital per lot** = premium x lot at entry (median and max reported); **trades per day** reported.

## 3. The variants (21, one Benjamini-Hochberg family)

Signal source of every variant: the UNDERLYING (index spot, or the commodity's near future). Option-chain data is
context only (model features). No rule reads an option's own price chart.

### Indices (5)
| id | what | taking |
|---|---|---|
| I1 | **SoloMidday on SENSEX** (S1 = SoloMidday.kt exactly: 12:00, `abs(c - o) >= 0.5 ATR14`, close in the outer 25% of the 09:15-11:59 range, 4-ITM (then one step either way, the less ITM first), index stop 0.3 ATR14 on a 1-min close, breakeven once 0.45 ATR its way, 14:30 out; SENSEX step 100) | 1 a day |
| I2 | **SoloMidday on MIDCPNIFTY** (same rule, step 25) | 1 a day |
| I3 | **SoloMidday on all five indices** (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), strongest `abs(c - o) / ATR14` first, ties A-Z, an index on its expiry day passes the turn | 1 a day |
| I4 | **A2 extended**: R57's A2 method exactly (logistic C 0.1, median impute, standardise, held-contract win/lose labels, purged walk-forward with monthly refits and a 1-session embargo, take = first candidate of the day in time order whose score >= the 70th percentile of that fold's training scores), retrained with SENSEX added (candidates S1-S4 on all five indices; index one-hots include SENSEX) | 1 a day, any index |
| I5 | **A2 on SENSEX only**: I4's monthly models, but only SENSEX candidates may be taken | 1 a day, SENSEX |

Design walk-forward test months for I4/I5: Jul 2025 - Mar 2026 (as R57); final: monthly refits Apr - Oct 2026.

### MCX: SoloMidday translated (14)
The rule, on each commodity's near future (the proxy), faithful to SoloMidday:
- `o` = the session's first proxy price (first minute at or after 09:00, which must be by 09:05);
- at the decision minute D: `c` = the proxy close of minute D-1 (must exist), `H` / `L` = the max / min proxy close from
  09:00 to D-1 (close-only data), at least 100 minutes with a price before D (noon) / 300 (evening);
- signal when `abs(c - o) >= 0.5 x ATR14`; up when `c > o`; the close in the outer 25% of the range; strength
  `abs(c - o) / ATR14`;
- ATR14 = mean true range of the 14 completed sessions before the day, from daily bars built from proxy closes (on the
  first session of a new contract the true range is that day's high - low, so the roll gap is never counted);
- strike: 1-ITM, else ATM (section 2); fill window D .. D+3;
- exits: future stop `entry future - side x 0.3 ATR14` on a 1-min close; breakeven: once the future has gone 0.45 ATR14
  its way (on closes, earned by the minutes before the one checked) the stop moves to the entry level; time exit;
- 1 lot; no expiry day; one trade a day per variant.

| id | commodity | decision D | time exit |
|---|---|---|---|
| X1 / X2 | GOLD | 12:00 / 18:00 | 14:30 / 20:30 |
| X3 / X4 | GOLDM | 12:00 / 18:00 | 14:30 / 20:30 |
| X5 / X6 | SILVER | 12:00 / 18:00 | 14:30 / 20:30 |
| X7 / X8 | SILVERM | 12:00 / 18:00 | 14:30 / 20:30 |
| X9 / X10 | CRUDEOIL | 12:00 / 18:00 | 14:30 / 20:30 |
| X11 / X12 | NATURALGAS | 12:00 / 18:00 | 14:30 / 20:30 |
| X13 | all six, strongest first (ties A-Z), noon | 12:00 | 14:30 |
| X14 | all six, strongest first (ties A-Z), evening | 18:00 | 20:30 |

12:00 is the session's 3-hour mark (SoloMidday decides 2 h 45 min after the NSE open); 18:00 is the evening variant
(US-session hours, where MCX moves most). Both hold 2 h 30 min at most, as SoloMidday.

### MCX: A2-style meta-labeller retrained for MCX (2)
Candidates (signal on the commodity's near future; every candidate has a stop):
| setup | rule | markets | strike | exits |
|---|---|---|---|---|
| SN | SoloMidday noon (X rules above) | all six | 1-ITM else ATM | 0.3 ATR stop, breakeven at 0.45 ATR, 14:30 |
| SE | SoloMidday evening | all six | 1-ITM else ATM | same, 20:30 |
| EV | the app's NG evening breakout (McxEveRules / R53 MA1): 17:00-19:00 range of proxy closes (>= 60 minutes), first close beyond it 19:00-22:00 | all six (the app runs NATURALGAS; R60 applies the same rule to each commodity) | 1-ITM else ATM | option -15% stop, future stop at the range middle, future target edge + one width, 120 minutes, flat by 23:15 |
| MO | the app's SILVERM morning call (McxMorningRules / M4): 09:15 grid slot, ATM+2 call, 11-20 sessions to expiry | SILVERM | ATM+2 call | Solo layer (-30% premium stop; +10% locked once +40%), 240 minutes |

Left out, with reasons: US-night silver (MA2: a futures rule that needs US silver and USD/INR hourly prices, which are
not in the lake); the 12-month trend (MA10: monthly, not a day candidate).

Features (read at the signal minute's close; a look-ahead test covers each): from the proxy - return over 15 and 60
minutes and since the session open (signed by side), the day's range so far / ATR14, distance from its own side's edge
of the day's range, 30-minute realised volatility, ATR14 as % of price, minute of day, US-session flag (minute >= 19:00
IST in US summer time, 20:00 in winter, i.e. after the US cash open), sessions to expiry; option context only - ATM IV
(call/put mean at offset 0) and put/call OI ratio (ATM-3..+3), both the latest value at or before the minute;
commodity one-hots (6), setup one-hots (4), side.

| id | candidates | model |
|---|---|---|
| M1 | SN, SE, EV, MO | A2 method: logistic C 0.1 (median impute, standardise), labels = held-contract net > 0, purged walk-forward with monthly refits and a 1-session embargo, at least 50 training rows; take = the day's candidates in time order (same minute: highest score first), the first with score >= the 70th percentile of that fold's training scores; then nothing more that day (1 MCX trade a day) |
| M2 | SN, SE only (meta-labelled Solo) | same |

MCX design walk-forward test months: Nov 2025 - Mar 2026 (training from Aug 2025); final: monthly refits Apr - Oct 2026.

## 4. Statistics and gates (as R47-R59, never relaxed)

- **Random-entry twins, 500 per variant (seed 60).** Each trade is replaced by a twin on the same day and market, entry
  minute uniform in the rule's window, side by coin flip, the same strike rule and the same exits (future / index stops
  recomputed from the twin's own entry). Windows: index S1-type 09:20-14:00 (R57); MCX noon 09:15-14:00, evening
  15:00-20:00; EV 19:00-22:00; MO 09:15-10:00; index meta (I4/I5) as R57 (09:20-14:00, the picked setup's exits). A twin
  with no fill is redrawn (up to 30 times, then 0) so thin markets are not flattered.
- p = (1 + #twins with total net >= the rule's) / 501. **One BH step over all 21 final p-values.**
- **Gates (all needed):** final net > 0 after costs; twin p < 0.05; BH q <= 0.10; 40+ final trades; top-5 trades
  < 100% of the final net; design positive (rules: design net > 0; models: design walk-forward net > 0).
- Reported per market: 1x and 2x costs, trades, win %, net, net per trade, max drawdown, worst day, months green,
  capital per lot, trades per day. Plain-rules records are kept separate from anything changed later.

## 5. Order of work

1. Commit this file. 2. Build stores (no P&L). 3. Design run (design P&L, walk-forward). 4. A dry run of the final
pipeline on design months only (crash check). 5. The ONE final read. 6. Write-up, experiment entries, tests.
Any deviation found later is written as a dated amendment below, never silently.

## Amendments
(none yet)
