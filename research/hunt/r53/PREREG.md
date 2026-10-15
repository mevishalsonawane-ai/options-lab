# R53 pre-registration: rejected MCX, gold and crypto rules on the recent market only

Written 15 Oct 2026, **before any R53 profit-and-loss number was computed**. Boss, 15 Oct: "any more strategies we rejected,
try that too" and "add the Nov 2024 onwards view. Don't test any data before that". The rule list and its reasons are
in `CATALOGUE.md`. Nothing in R53 changes the app. Nothing in the app trades crypto.

## 1. Data window and split (fixed now)

- **Only 20 Nov 2024 to the lake's end (12 Oct 2026).** Bars before 20 Nov 2024 may warm up indicators. No trade is entered
  before 20 Nov 2024.
- **DESIGN: 20 Nov 2024 - 31 Mar 2026.** For every MCX rule that needs MCX minutes, the minutes start on 5 Aug 2025
  (the lake's MCX option files; their `spot` column is the only MCX near-future minute series before May 2026), so the
  MCX design is **5 Aug 2025 - 31 Mar 2026**. The MCX daily trend rule (MA10) uses the Nov 2024 design.
- **FINAL TEST: 1 Apr - 13 Oct 2026** (the data ends 12 Oct). It is read **once** per rule, by a runner that refuses a
  second read (`results/final_read_<book>.json`).
- **Honesty note:** earlier studies saw parts of this window. M3/M4 used Aug 2025 - Oct 2026; R2/R6 held out
  Oct 2025 - Oct 2026; RC1 held out Oct 2025 - Oct 2026; RC2, NN and STRAD held out Apr - Oct 2026; the gold studies held
  out Oct 2025 - Sep 2026; F1C used Nov 2023 - Mar 2026. So the "final test" is not unseen data for any rule: the rules
  were chosen by people who had looked at it. It is a fresh, single, pre-registered measurement, nothing more.

### Walk-forward inside DESIGN

- Folds, Nov 2024 design: train 20 Nov 2024 - 31 Mar 2025; out-of-sample quarters Apr-Jun 2025, Jul-Sep 2025,
  Oct-Dec 2025, Jan-Mar 2026.
- Folds, MCX minute design: train 5 Aug - 31 Oct 2025; out-of-sample Nov-Dec 2025, Jan-Feb 2026, Mar 2026.
- **Original settings:** walk-forward net = the sum of the out-of-sample folds (nothing is fitted).
- **Re-tune:** before each out-of-sample fold, the variant with the highest net on all earlier design data is picked
  (ties: the original), and the pick is traded in that fold. Walk-forward net = the sum of those folds.
- The **design pick** for the final test = the variant with the highest net over the whole design, if that is not the
  original and its design net is > 0. The final test then reads **the original and the design pick, nothing else**.

## 2. Measurement (never relaxed)

- **One held contract per trade.** MCX options: the strike is chosen at the signal from the near-future price (the
  rolling series' `spot`), then that one contract (day, side, strike) is priced to the exit, whatever ATM offset it sits
  at later. A trade whose contract has no print in the 3 minutes after the signal is not taken. Prints flagged by R42's
  F1 filter (`hunt/r42/spikes.py flag_spikes`, k 5, 15 min, ratio 1.5) are removed before pricing. Futures: the near
  future; days when the near contract changed overnight are skipped by rules that read an overnight gap.
- **Next-bar fills.** Signal on a bar's close, entry at the next bar's open (MCX options: next minute's open print;
  crypto options on Delta: **one minute later than the signal minute's next bar**, because the book is thin). Stops and
  targets fill at their level, or at the bar's open when the bar gaps through (the worse price). No look-ahead: every
  input is known at the signal bar's close (tested).
- **Costs.**
  - MCX: Zerodha MCX charges as in the app's `McxCosts.kt` (futures brokerage min(0.03%, Rs 20), CTT 0.01% sell,
    exchange 0.0021%, stamp 0.002% buy; options Rs 20, CTT 0.05% sell premium, exchange 0.0418%, stamp 0.003% buy; SEBI
    Rs 10/crore; GST 18% on brokerage + exchange + SEBI). Spread paid half per side: options 0.30% (crude) / 0.43% (NG) /
    0.40% (GOLDM, SILVERM) of premium after 17:00 IST and twice that before 17:00; futures max(1 tick, 0.01%) with
    crude Rs 2/bbl. Lot multipliers as `m3lib.MULT`.
  - Gold (XAUUSD): the studies' own costs: ask = bid + 0.30, commission $7 a lot round trip, $40 a lot a night
    (x3 Wednesday) for positions held over the 21:00 UTC roll. Money in USD per standard lot.
  - Crypto, two cost books, both reported:
    - **Delta India**: perps taker 0.05% + 18% GST, slippage 0.02% a side; options fee min(0.01% of notional,
      3.5% of premium) + 18% GST, spread model of RC2 (`rc2/costs.py`), buy at ask, sell at bid less one tick and 1%.
    - **CoinSwitch PRO**: options 0.015% of notional, futures 0.02% maker / 0.05% taker (R53 assumes taker on market
      orders), each **with and without** 18% GST on the fee; same slippage/spread as Delta.
    - **Tax**: 30% + 4% cess (31.2%) on each winning trade, no loss set-off; 1% TDS on each sale's value shown as a cash
      flow locked for a year (not a cost). **The crypto gates read the after-tax Delta book.**
    - Sizing as the original studies: options Rs 1 lakh account, premium <= 2% of it, whole Delta lots (RC2); perps
      Rs 1 lakh notional at 1x (Delta lots: BTC 0.001, ETH 0.01). USD/INR: ECB daily (frankfurter).
- **Crypto rule (CLAUDE.md, never broken):** every crypto trade has a stop-loss AND a profit lock (trailing or step).
  No "no lock" version is run. Buy-and-hold and DCA appear only as baselines.

## 3. Statistics and gates

- **Random-entry twins, 500 per tested version.**
  - Where the study's simulator takes arbitrary entries (MCX `m3lib.sim_option/sim_fut`, the crypto exits of
    `rc2/exits.py` and `f1c/perp.py`, the R53 generic engine), each twin draws the same number of entries at random
    minutes inside the rule's own entry window, on random days of the same period, with a random side for two-way rules
    and the same side for one-way rules, and exits by **the rule's own exits**.
  - Where the study's code cannot take arbitrary entries (some gold scripts), each twin trade is a random entry inside
    the same window with the same side and **the same holding time** as the matched real trade, same costs.
  - p = (1 + number of twins with net >= the rule's net) / 501.
- **Benjamini-Hochberg** over every final-test p-value of R53 (all books together), and separately over every design
  variant (reported).
- **Gates (all needed to pass):** final-test net > 0 after costs (crypto: after tax); p < 0.05; q <= 0.10; 40 or more
  final-test trades (fewer: "too few trades to judge"); the top 5 trades < 100% of the final-test net; design
  walk-forward net > 0.
- Reported per rule: OLD verdict vs NOW (design) vs FINAL TEST: trades, win rate, net (crypto before and after tax, both
  cost books), net per day, average win and loss, worst day, max drawdown, p, q, pass/fail.

## 4. Rules and their pre-registered variants

Original settings first (as the study or the app had them). "RT" = the small re-tune set, design only.

### MCX (signal: near future = per-minute median `spot` of the option rows; trade the option by the strike rule or the future)

| id | original | RT variants |
|---|---|---|
| MA1 app-ng-eve | NATURALGAS, app `McxEveRules`: range 17:00-<19:00 of 1m closes (>= 60 minutes), first close beyond it 19:00-22:00, entry next minute, 1-ITM, option stop -15% on the minute low, stop = future back at the range middle, target = edge + 1 width (future close), 120 min, flat 23:15, 1 a day | option stop -25%; max hold 60 min |
| MA1b eve-goldm-fut | same EVE on the GOLDM future, 1 lot | max hold 60 min |
| MA1c eve-crude-opt | same EVE, CRUDEOIL 1-ITM option | max hold 60 min |
| MA2 app-us-silver | app `McxUsSilverRules`: s = ln(SI=F 08:30 / SI=F at the previous MCX close) + ln(INR=X same), Yahoo 1h (open of the bar at that time, else the last close <= 2 h old); at 09:05 IST buy (s > 0) or sell (s < 0) 1 SILVERMIC (proxy price: the SILVERM near future, per kg); 3% disaster stop; exit 10 minutes before the MCX close; skip roll days | exit 14:00 (R2's research exit) |
| MA3 r2-c1-ng-gap | side = sign(ln(future at 09:05 / last price of the previous session)), same contract; NATGASMINI future and NG 1-ITM option (first print within 30 min); exit 14:00 | exit 10 min before the close; option -30% stop |
| MA4 r2-c3-fade | res = gap(09:05) - (us_on + inr_on); side = -sign(res) if \|res\| > 12 / 22 / 11 bp (crude / NG / gold); CRUDEOILM future, NG 1-ITM option, GOLDTEN future (proxy price GOLDM near future per 10 g); exit 14:00 | thresholds x 1.5 |
| MA5 r2-c4-xau-lead | catch-up = 5-min ln XAUUSD - 5-min ln GOLDM future; \|catch-up\| > 23.8 bp → buy GOLDM 1-ITM that way within 2 min if it prints; hold 15 min; one at a time | hold 30 min |
| MA6 r3-goldm-am | GOLDM 5m bars from 09:00, EMA8/EMA21 cross (ewm, adjust=False), ADX14 (Wilder) > 15 at the bar close, entries 09:00-14:55, ATM call/put next minute (no print in 3 min: skip), -25% / +50% on the option, 60 min, 23:15, max 2 a day | 1-ITM strike; entries all day |
| MA7 r6-gann-swing | R6 `gann_swing` 15m, SILVERMIC future (proxy price: SILVERM near future per kg), longs only, R6's NAT exit, flat 23:15 | both sides |
| MA8 m3-inorb-goldm | GOLDM, range 09:00-09:30, first 1m close beyond it 09:30-12:00, 1-ITM option, mid stop, edge + 1 width, 60 min, option -15% | 120 min |
| MA9 m3-evt-eia | CRUDEOIL, EIA weekly release (Wed 10:30 ET, DST-exact; holiday shifts from the EIA calendar where known), range = 15 min before, first close beyond it within 30 min, 1-ITM option, mid stop, edge + width, 30 min, -15% | none |
| MA10 app-trend12 | app `McxTrendRules`: on the last MCX trading day of each month, sign(close - close 252 trading days earlier) on the continuous daily series; hold that side from the next session's open, 1 lot (minis: CRUDEOILM = CRUDEOIL/10, NATGASMINI = NATURALGAS/5, SILVERMIC = SILVER/30 per kg, GOLDPETAL = GOLD per gram x 1, i.e. the full series scaled), roll cost charged on each monthly contract roll | none |

### Gold (XAUUSD, Dukascopy 1-minute bid + the release file's tail to 12 Oct 2026; buys only, as the studies)

Each rule is run with **its own study script's signal and exit code**, called on the full series with entries kept only
from 20 Nov 2024. The year split of the old scripts is replaced by R53's split.

| id | original | RT variants |
|---|---|---|
| GB1 boss-opt1 | `gold_session_strats.py` Option 1: M15, H1 bias, target 2R | target 1R; exit on the opposite cross |
| GB2 boss-opt2 | Option 2: Asian sweep, target range +100% | target +200% |
| GB3 tas-15m | `gold_tas.py` 15m, ATR 19, targets 1.5/2.5/3.5 R with breakeven | no targets |
| GB4 tas-4h / tas-30m | `gold_tas.py` 4h with targets; 30m no targets | (two originals, no RT) |
| GB5 ai-opinion | `gold_ai_opinion.py` Robust, 1h and 4h, stop 1 ATR, target 2 ATR | (two originals, no RT) |
| GB6 reopen-drift | `gold_reopen.py`: buy reopen + 5 min, sell reopen + 120 min | fixed 23:00-23:55 UTC |
| GB7 liq-india-1h15 | `liquidity_gold_1h.py` India 1h+15m, buys | none |
| GB8 liq-2h | `gold_timeframes.py` 2h, new settings | none |
| GB9 liq-1h-conf20 | `gold_1h_plus.py` 24x5 arm with pool confirmation 20 | none |
| GB10 liq-india-15+5 | `liquidity_gold.py` India 15+5, buys | none |
| GB11 hammer-1h | `gold_candles.py` 1h hammer, buys, target 2R | exit after 4 candles |
| GB12 dip-keephalf | `gold_mtf_lock.py` Dip entry, keep half at 2 ATR, 8 h | giveback 1.5 ATR, 8 h |
| GB13 trend-gb3 | `gold_trend_lock.py` Supertrend(10,3) 4h + 3-ATR giveback, wait after a lock exit | 5-ATR giveback |

### Crypto (signal on the perp / Delta index; Binance USD-M 1m perps as the price source for perps and signals; Delta
India 1m MARK candles for options; every version has a stop and a profit lock)

| id | original | RT variants |
|---|---|---|
| CC1 rc2-r8-pdl | RC2 `signals.pdl_break`, ATM daily option (`pick_expiry` min_left 3 h, max hold 3 h), exit `s40_step` | `s40_trail`; `s25_step` |
| CC2 rc2-r4-burst | RC2 `vol_burst` (DVOL from Deribit), exit `s40_trail`, 2 h | `s40_step` |
| CC3 rc2-r6-hero | RC2 `hero`, exit `s70_step` | `s50_step` |
| CC4 f1c-p07 | F1C P07 on BTC and ETH perps, 00:00-09:00 IST, `s15_trail` (stop 1.5 x atr14_1h_pct, trail from +1R keeping 50%), 180 min, 1 a day | `s10_trail`; `s15_step` |
| CC5 f1c-p13 | F1C P13 (Binance 5m open interest), fade, 120 min, 2 a day, F1C's pick exit | the other lock (step/trail) |
| CC6 f1c-o19 | F1C O19: R4 trigger inside 00:00-09:00 IST, ATM Delta option of the 17:30 IST expiry, `s25_step`, 120 min | `s40_trail` |
| CC7 f1c-o18 | F1C O18: P07 trigger, ATM option, `s40_trail`, 180 min | `s25_step` |
| CC8 rc1-g-breakout | RC1 g on 4h perps 1x: close > prior 20-bar high, stop entry - 2 x ATR(14), trailing exit below the prior 10-bar low | + F1C step lock (+1R locks +0.25R, +nR locks +(n-1)R) |

Baselines reported next to the crypto rules (not tested, not gated): buy-and-hold BTC/ETH and a monthly DCA over the
same windows, after tax.

## 5. What would change the app

Nothing directly. The write-up ends with which rules (exact settings) could come back on PAPER and which stay off.
A rule can be proposed for paper only if it passes every gate in section 3. Crypto stays research-only.
