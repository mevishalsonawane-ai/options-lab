# R53 catalogue: every rejected MCX, gold and crypto rule, and which ones R53 re-tests

Written 15 Oct 2026 for Boss ("any more strategies we rejected, try that too"), before any R53 P&L. Built from the write-ups and
their code, de-duplicated. NSE/BSE index-option rules are R49-R52's. M4's SILVERM morning call is R51's (skipped here).

Columns: **id** (R53 name), **source**, **rule** (short; exact settings in `PREREG.md`), **old verdict and sample**,
**signal source** (U = the underlying: the MCX near future, XAUUSD, or the BTC/ETH perp/index; OC = the option chain used
as context; none of the rules reads an option's own price chart), **why it is (not) re-tested**.

Priority order for re-testing (from the brief): rules in the app, rules Boss proposed (or an outside report Boss sent),
rules the old write-up called "close", "watch" or "candidate", then the rest.

## A. MCX (signal on the near future; trade the option chosen by the strike rule, or the future)

| id | source | rule | old verdict, sample | signal | R53 |
|---|---|---|---|---|---|
| **MA1 app-ng-eve** | app arm `McxEveRules` (M3 EVE, MCX_INTRADAY amendment 1) | NATURALGAS 17:00-19:00 close range, first 1m close beyond it 19:00-22:00, 1-ITM option, -15% option stop, range-mid stop, target edge + 1 width, 120 min, flat 23:15 | design 197 tr +Rs 47/tr (p 0.09, q 0.63); holdout 55 tr +Rs 93 (p 0.19). Paper arm, "not proven" | U | **tested** (app rule as it is) |
| MA1b eve-goldm-fut | MCX_INTRADAY M3 EVE | same EVE rule on the GOLDM future | design 192 tr +Rs 313 (p 0.01, q 0.45); holdout 53 tr +Rs 143 | U | **tested** (close) |
| MA1c eve-crude-opt | MCX_INTRADAY M3 EVE | same EVE rule, CRUDEOIL 1-ITM option | design 203 tr -Rs 633; holdout 60 tr +Rs 303 | U | **tested** (one more book of MA1, cheap) |
| **MA2 app-us-silver** | app arm `McxUsSilverRules` (HUNT_R2 C2) | sign(ln SI=F 08:30/prev MCX close + ln INR=X same) at 09:05: buy/sell the SILVERMIC future, 3% disaster stop, out 10 min before the close | holdout 166 tr +Rs 1,189 (q 0.019) in silver's tripling year; design minute check -Rs 246; paper arm | U (US + FX) | **tested** (app rule as it is) |
| **MA3 r2-c1-ng-gap** | HUNT_R2 C1 | sign of the 09:05 gap vs the last price of the previous session; NATGASMINI future and NG 1-ITM option; out 14:00 | fut holdout 233 tr +Rs 346 (q 0.019, Jan 2026 = 80% of it); option 208 tr +Rs 181 (q 0.13, "watch-list") | U | **tested** (watch-list) |
| MA4 r2-c3-fade | HUNT_R2 C3 | fade the gap residual (gap - US/INR overnight move) beyond 12/22/11 bp (crude/NG/gold); out 14:00 | GOLDTEN fut 123 tr +Rs 126 (q 0.049, longs only); NG option 116 tr +Rs 262 (q 0.13, "watch"); CRUDEOILM fut 141 tr +Rs 274 (q 0.17) | U (US + FX) | **tested** (watch) |
| MA5 r2-c4-xau-lead | HUNT_R2 C4 | 5-min XAUUSD move minus 5-min GOLDM move beyond 23.8 bp: buy the GOLDM 1-ITM option that way, 15 min | holdout 128 tr +Rs 228 (q 0.095; longs +719, shorts -263); never built (needs a live XAU feed) | U (XAUUSD + GOLDM future) | **tested** (paper candidate) |
| **MA6 r3-goldm-am** | HUNT_R3_GOLDM_MORNING (outside report Boss sent) | GOLDM 5m EMA8/21 cross with ADX14 > 15, entries 09:00-14:55, ATM option, -25% / +50%, 60 min, max 2 a day | 222 tr -Rs 274/tr (-Rs 60.8k a year); rejected | U | **tested** (Boss / outside report) |
| MA7 r6-gann-swing | HUNT_R6 | Gann swing chart on 15m SILVERMIC future, longs (the write-up's "closest") | holdout +Rs 439/day (189 tr) but design -Rs 94/day; rejected | U | **tested** (closest of 2,034) |
| MA8 m3-inorb-goldm | MCX_INTRADAY M3 | 09:00-09:30 range, break 09:30-12:00, mid stop, edge + 1 width, 60 min, option -15% | GOLDM option design 68 tr +Rs 110 (p 0.04, q 0.56); holdout 58 tr -Rs 67 | U | **tested** (close in design) |
| MA9 m3-evt-eia | MCX_INTRADAY M3 rule 4 | crude EIA: range = 15 min before the release, break within 30 min, mid stop, edge + width, 30 min | design 41 tr -Rs 361; holdout 14 tr +Rs 645 (p 0.03) | U | **tested** (sample is about 20 a half-year: expected "too few trades") |
| **MA10 app-trend12** | app arm `McxTrendRules` (MCX_TREND M2 TSM252) | monthly sign of the 252-day return, long/short 1 lot per leg, roll 5 trading days before expiry | development Sharpe 0.54, Reality Check p 0.13, SPA p 0.79; paper arm | U (daily) | **tested** on the four legs the lake has (CRUDEOIL, NATURALGAS, SILVER, GOLD continuous daily, scaled to the minis); a monthly rule gives far fewer than 40 trades (stated in advance) |
| m3-liq15/5 | MCX_INTRADAY rule 1 | app Liquidity port on MCX | every option cell lost (design -Rs 119 to -649) | U | left: not close |
| m3-usorb, m3-h60, m3-moma, m3-momb | MCX_INTRADAY | US-open range break; 60-min high break; evening momentum; EIA-day momentum | all lost in design (-Rs 126 to -839 a trade) | U | left: not close |
| m4-q3 chain signals | MCX_OPTIONS Q3 | put-call OI change, risk reversal, basis, OI build-up extremes, ATM, 60 min | every trade cell lost (holdout -Rs 66 to -476) | OC | left: not close |
| m4-ev6/ev7/ev9 | MCX_OPTIONS | gas-storage Thursday follow; CPI bullion; gated straddle | all lost or 2-9 trades | U / size | left: not close or untestable |
| m4-q4 other cells | MCX_OPTIONS Q4 | clock-entry OTM calls (GOLDM, CRUDEOIL) | GOLDM design 112 tr +Rs 464 (p 0.04); walk-forward -Rs 3.5k | clock | left: part of M4's family (R51) |
| m2 ensemble / singles / carry | MCX_TREND | ENS, 11 single signals x 7 commodities, carry | 0 of 308 pass BH, ruin at Rs 1 lakh | U (daily) | left: not close; the app's TSM252 leg (MA10) is the one kept |
| r2-c5 US lead (silver, crude, NG) | HUNT_R2 amendment 2 | C4 rule on XAGUSD / crude / gas | design 25 tr, holdout never run | U | left: no XAG/crude/gas minute source reachable this session beyond XAUUSD (Dukascopy XAU only) |
| r2 event / macro rules | HUNT_R2 | EIA surprise, CPI/NFP hour, DXY/real-yield trend, COT, ES risk-off, Asian-hours gold, FOMC straddle | all failed (hit rates 32-54%) | U | left: not close |
| r6 other ~2,030 versions | HUNT_R6 | Square of 9, Fib, Gann time, harmonics ... | 0 pass of 2,034; median -Rs 146/day | U | left: only the closest (MA7) re-tested |
| strad-crude | STRAD_CRUDE | HAR/MLP size-forecast straddle, frozen q0.9, 30 min, strangle, SL25 | 0 of 1,496 pay; holdout 52 tr net -Rs 348 | size forecast | left: model rule (needs the HAR/MLP refit), net loss after costs even gross-positive |
| nn-crude | NN_CRUDE | MLP/logit/GBM direction score, top/bottom 5% | holdout AUC 0.48-0.51; -Rs 45.9k | model | left: model, no signal |
| m1 #4, #8, #10 | MCX_GUIDE | NG contango carry, cross-sectional carry, seasonals | never tested (open ideas, not rejections) | U | left: not rejections |

## B. Gold (XAUUSD, Dukascopy 1-minute bid; the studies' own instrument and costs)

All gold studies trade XAUUSD (1 standard lot = 100 oz), buys only, signal on XAUUSD itself (U). The four arms that passed
(Liquidity 1h 24x5, Trend 4h Supertrend + 4-ATR lock, Dip 1h+30m, TAS 1h) are not rejected rules and are not re-tested.

| id | source | rule | old verdict, sample (Oct 2023 - Sep 2026) | R53 |
|---|---|---|---|---|
| **GB1 boss-opt1** | GOLD_SESSION_STRATS (Boss) | EMA9/21 cross with RSI14 > 50 on M15, H1 bias (1h close > EMA50), stop 10-bar swing low, target 2R, 07:00-16:00 UTC, out 20:40 | best buys-only +$15.9k, 265 tr, t 0.52; 26 of 32 lost | **tested** (Boss) |
| **GB2 boss-opt2** | GOLD_SESSION_STRATS (Boss) | Asian range 00-07 UTC; sweep below the low that closes back inside 07-16 → buy; stop past the sweep; target range +100% | lost; win rate 9-17% | **tested** (Boss) |
| **GB3 tas-15m** | GOLD_TAS (Boss's own TAS setting) | TAS tracker ATR 19 on 15m, score >= 50%, targets 1.5/2.5/3.5 R | +$72.6k, 947 tr, t 0.92 | **tested** (Boss) |
| GB4 tas-4h / tas-30m | GOLD_TAS(_LOCK) | TAS on 4h (with targets) and on 30m (no targets) | 4h +$154.8k, 59 tr, t 2.22; 30m +$175.5k, 470 tr, t 2.03 | **tested** (close) |
| **GB5 ai-opinion** | GOLD_AI_OPINION (Boss's TradingView script) | V5 five-vote consensus, Robust filter, stop 1 ATR, target 2 ATR, on 1h and 4h, buys | 1h +$87.6k, 445 tr, t 1.55; 4h +$131.9k, 165 tr, t 2.25 | **tested** (Boss) |
| GB6 reopen-drift | GOLD_REOPEN | buy at reopen + 5 min, sell at reopen + 120 min, every weekday | +$62.7k, 716 tr, t 1.83 ("candidate to watch on paper") | **tested** (close) |
| GB7 liq-india-1h15 | LIQUIDITY_GOLD_1H | liquidity rule, 1h + 15m books, India hours 03:45-10:00 UTC, buys | +$40.7k, 133 tr, t 2.83, positive every year; not adopted | **tested** (close) |
| GB8 liq-2h | GOLD_TIMEFRAMES | 24x5 liquidity rule on 2h candles (new settings) | +$101.2k, 81 tr, t 2.11 ("runner-up") | **tested** (close) |
| GB9 liq-1h-conf20 | GOLD_1H_PLUS | 24x5 1h arm with pool confirmation 20 (second-level target) | +$121.6k, t 3.21, "better in both", not adopted | **tested** (close) |
| GB10 liq-india-15+5 | LIQUIDITY_GOLD | 15+5 book, India hours, buys | +$33.3k, 488 tr, t 1.71 ("not a proven edge") | **tested** (close-ish) |
| GB11 hammer-1h | GOLD_CANDLES | 1h hammer/pin bar, buys, stop under the low, target 2R | +$37.7k, 424 tr, t 0.84, "worth a paper trial" | **tested** (close) |
| GB12 dip-keephalf | GOLD_MTF_LOCK | Dip entry, keep half at 2 ATR, 8 h | +$154k, t 2.40, did not beat the arm in the held-out year | **tested** (close) |
| GB13 trend-gb3 | GOLD_TREND_TUNE | Supertrend 4h with a 3-ATR giveback lock (the arm uses 4 ATR) | +$207.6k, t 3.52 (fitting-years pick), not adopted | **tested** (close) |
| gold-arms ORB family | GOLD_ARMS | ORB / fresh / sweep / range fade, 3 sessions | all 24 buys-only versions lost | left: not close |
| gold-scalp | GOLD_SCALP | 108 M1/M5 scalps | 2 of 108 positive | left: not close |
| colour runs / 1h+30m combos | GOLD_5M/15M/30M_1H patterns | ~8,000 colour sequences | at or below chance | left: grid search, chance level |
| trend alternatives / retune / trend locks other than 3 ATR | GOLD_TREND(_TUNE) | Donchian, EMA, chandelier, 288 retunes | 0 of 38 beat the baseline out of sample | left: not close |
| liquidity locks, 15m/30m books, 1h tweaks other than conf 20 | GOLD_LOCK, GOLD_TIMEFRAMES, GOLD_1H_PLUS, GOLD_30_1H | | all "no" | left: not close |
| TAS 5m, AI opinion 5m/15m/30m | GOLD_TAS, GOLD_AI_OPINION | | lost | left: not close |

## C. Crypto (signal on the BTC/ETH perp or index; every tested version has a stop AND a profit lock)

| id | source | rule | old verdict, sample | R53 |
|---|---|---|---|---|
| **CC1 rc2-r8-pdl** | HUNT_RC2 R8 | first 5m close above the previous UTC day's high (call) / below its low (put), 01:00-22:00 UTC, Delta daily ATM option, -40% stop + step lock | design 1,184 tr +0.4%; holdout 290 tr +Rs 8,910 pre-tax, -Rs 34,173 after tax; "the honest next step: watch-only paper log" | **tested** (closest) |
| CC2 rc2-r4-burst | HUNT_RC2 R4 | 15-min move >= 2.5 x DVOL-implied 15-min move, with it, ATM option, -40% stop + trail | design 2,218 tr +0.9% (direction p 0.004); holdout 512 tr -Rs 54,659 | **tested** (close) |
| CC3 rc2-r6-hero | HUNT_RC2 R6 | 09:00-11:00 UTC, 15-min move >= 2x implied, cheap OTM option of today's expiry, -70% stop + step lock | design 211 tr +7.9%; holdout 56 tr -Rs 20,916 | **tested** (close) |
| CC4 f1c-p07 | HUNT_F1 crypto P07 | RC2 R8's trigger on the perp inside 00:00-09:00 IST, ATR stop + trail lock, 180 min | design 1,595 tr; p 0.058, q 0.252; holdout -Rs 1.50L | **tested** (close by p) |
| CC5 f1c-p13 | HUNT_F1 crypto P13 | a > 1 ATR hour while OI falls > 0.5%, fade, 120 min | p 0.007, q 0.053; design -Rs 4.26L after tax | **tested** (close by p) |
| CC6 f1c-o19 | HUNT_F1 crypto O19 | R4's trigger inside the window, ATM option, s25 step, 120 min | design pre-tax +Rs 1.42L, after tax -Rs 16,848; q 0.002; "the closest" | **tested** (closest) |
| CC7 f1c-o18 | HUNT_F1 crypto O18 | P07's trigger, ATM option, s40 trail, 180 min | design pre-tax +Rs 42,478, after tax -Rs 40,915; q 0.002 | **tested** (close) |
| CC8 rc1-g-breakout | HUNT_RC1 g | 4h close above the prior 20-bar high, stop entry - 2 ATR(14), exit below the prior 10-bar low (a trailing lock), perp | design after tax -12.2%/yr, p 0.089; holdout -45.7% | **tested** (has stop + trailing lock) |
| rc2 R1/R2/R3/R5/R7 | HUNT_RC2 | Asia/EU/US ORB, DVOL jump, funding reversal | all lost in design | left: not close |
| f1c P01-P06, P08, P09, P11, P12, P14, P15; O16, O17, O20 | HUNT_F1 crypto | VWAP z, range position, ribbons, taker imbalance, liquidation flush, ... | all lost (p 0.08-1.0) | left: not close |
| f1c P10 | HUNT_F1 crypto | 1h ribbon ordered + 15m ribbon turning | p 0.047, q 0.223, design -Rs 3.21L | left: needs the crypto brain's ribbon features (cube v3 is design-only to Mar 2026; rebuilding it to Oct 2026 is out of scope); see CC4/CC5 for the close perps |
| rc1 b/c/d/e/f | HUNT_RC1 | rebalance, SMA/Donchian trend, vol target, weekday, funding carry | all rejected; their exits are slow regime exits, not Boss's "enter, take profit or stop, exit" | left: not Boss's style; buy-and-hold / DCA stay baselines only |
| C1/C2 model rules, C2 W4-ETH | CRYPTO_C1/C2 | logistic direction models | rejected; W4-ETH "inconclusive", unread holdout | left: model rules with no stop/lock; the crypto brain's own locked holdout is not R53's to spend |
| NN_CRYPTO | NN_CRYPTO | CNN/LR/HGB scores | 0 of 64 positive after tax | left: model |
| STRAD_CRYPTO | STRAD_CRYPTO | event / cheap straddles | best 22-69 trades, after tax negative | left: event sample too small (fewer than 40 in the final test by construction) |
| R41 R14-R16 | HUNT_R41 | NN confidence perps | rejected | left: model |

## Count

- Re-tested: 10 MCX + 13 gold + 8 crypto = **31 rules** (variants per rule in `PREREG.md`).
- Left: listed above with the reason. Nothing is dropped silently.
