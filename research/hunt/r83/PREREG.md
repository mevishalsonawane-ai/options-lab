# R83 pre-registration: MCX energy and base-metal strategies (written 18 Oct 2026, before any test)

Boss, 18 Oct: "use multiple agents/sessions to enhance the liquidity strategies and commodities strategies ... use all
the existing knowledge and, if you need, the internet." R83 = energy and base metals only (crude, natural gas, copper,
zinc, aluminium). Gold and silver are R82's. Research only: nothing in `android/**` or `.github/**` changes.

Committed BEFORE any rule below was run. Data downloads and loader checks (row counts, date ranges) were done first;
no P&L, hit rate or return statistic of any rule was computed before this commit.

## 0. What the app runs today and what already failed (read first, not re-tested unless changed)

- App MCX arms, all "not proven - paper only": NG evening breakout (M3 EVE, `McxEveRules`), SILVERM morning call (R82's
  area), US-night silver (R82's area), MCX 12-month trend (M2 TSM252 on CRUDEOILM, NATGASMINI, SILVERMIC, ZINCMINI,
  LEADMINI, ALUMINI, GOLDPETAL, `McxTrendRules`). R53 re-test: EVE -Rs 3,508 on 115 final trades; trend -Rs 66,099 on 28
  leg-months.
- Logged failures on crude/gas (experiment log): M3 USORB (NYMEX-open 15-min range break), M3 EVT / R53 MA9 (15-min
  pre-release EIA/storage range, break within 30 min), M3 LIQ15/LIQ5 (app Liquidity on 15m/5m), M3 INORB, H60, MOMA/MOMB,
  STRAD_CRUDE (1,496 straddle variants incl. EIA), NN_CRUDE (direction AUC 0.48-0.51), R2 (EIA surprise direction 40-54%),
  M4 storage-Thursday break, R6 Gann/Fib, R44 Donchian-20 5m on crude options, R60 SoloMidday on crude/NG, M2 trend/carry
  (198 futures variants, SPA p 0.79).
- So every R83 idea below is either new for MCX energy or a MATERIAL change of a logged failure, stated per idea.

## 1. Data (nothing committed; all in /dev/shm)

| source | what | span |
|---|---|---|
| Drive lake `options_rolling_1m` CRUDEOIL / NATURALGAS MONTH | MCX near-month option minutes ATM±3 with `spot` = near future | 5 Aug 2025 - 13 Oct 2026 |
| Drive lake `fut_daily_continuous` | MCX CRUDEOIL, NATURALGAS continuous near-month daily OHLC + OI (not back-adjusted) | 2012 - 13 Oct 2026 |
| Dukascopy 1-minute BID candles (`fetch_proxy.py`) | **US benchmark PROXY**: WTI (LIGHTCMDUSD), Henry Hub (GASCMDUSD), COMEX copper (COPPERCMDUSD), Brent | 2016 - 16 Oct 2026 |
| Yahoo daily / 1h | CL=F, NG=F, HG=F, BZ=F, INR=X (USD/INR) | 2000 - Oct 2026 / last 730 days |

- **MCX base metals are missing.** The lake has no COPPER/ZINC/ALUMINIUM series and the Dhan token is expired (DH-901,
  18 Oct). Copper is tested only through COMEX copper (proxy, labelled). Zinc and aluminium are **not tested** (no data).
- MCX crude = WTI x USD/INR (NN_CRUDE: price ratio 1.001, 5-min return corr 0.94); MCX natural gas = Henry Hub x USD/INR.
  So a proxy % move is used as the MCX % move. Every proxy result is labelled "signal and fills on the US benchmark
  proxy, MCX costs". Rupee P&L = side x (exit / entry - 1) x notional, notional = proxy price x previous USD/INR close x
  lot units (CRUDEOILM 10 bbl, NATGASMINI 250 mmBtu, COPPER 2,500 kg at $/lb x 2.20462).
- Proxy trades exist only inside MCX hours: 09:00 to 23:30 IST (US summer time) / 23:55 (US winter time), as
  `McxSession.kt`; intraday rules are flat 15 minutes before the close.

## 2. Costs (strict)

- Charges: the app's `McxCosts.kt` (Zerodha, ported 1:1 to Python with a parity test): futures brokerage min(Rs 20, 0.03%),
  CTT 0.01% sell side, MCX 0.0021%, SEBI, stamp 0.002% buy, GST 18%; options Rs 20, CTT 0.05%, MCX 0.0418%, stamp 0.003%.
- Spread, paid half on entry and half on exit (fraction of price):
  - futures: CRUDEOILM 0.03% half-spread (app `PaperSpread.MCX_FUTURE`); **NATGASMINI 0.06%** (2x the app default, for
    the wide natgas book); COPPER 0.05%.
  - options (MCX-real replay only): CRUDEOIL 0.30% / 0.60% half-spread (from / before 17:00, app default);
    **NATURALGAS 0.45% / 0.90%** (1.5x the app default).
- Stop exits pay an extra 0.02% slippage (futures). Daily rules pay a full round trip at every MCX roll.

## 3. Split, walk-forward, final (one read)

- **Intraday proxy rules:** design 1 Jan 2016 - 31 Dec 2022. Walk-forward folds: 2019, 2020, 2021, 2022 (each fold's
  variant is picked on design data before the fold, from 2016). **Final: 1 Jan 2023 - 13 Oct 2026, read once.** The same
  single final read also replays the frozen rule on real MCX minutes (5 Aug 2025 - 13 Oct 2026) as futures AND as the
  1-ITM near-month option (the "options versus futures" comparison).
- **Daily rules:** design 2012 - 2022 on MCX daily (Yahoo x USD/INR for copper, and for Henry Hub seasonality estimates
  from 2000); folds 2019-2022; final 1 Jan 2023 - 13 Oct 2026, read once.
- Disclosure: the final window overlaps windows other studies read (M2 holdout 2025-26, M3/M4 holdouts Jul-Oct 2026,
  R53 final Apr-Oct 2026). None of them ran these rules, but the market period is not "unseen".
- Final lock: `research/hunt/r83/results/final_read_<idea>.json` is written at the read; a second read raises.
- Pick rule (frozen before the final): within each idea, the variant with the best design net (2016/2012 - 2022); ties ->
  the primary (listed first).

## 4. Ideas and variants (every rule has a stop-loss)

Signal source is stated per idea. "Proxy" = the US benchmark future (WTI / Henry Hub / COMEX copper) as a stand-in for
the MCX future it drives.

| id | idea | signal source | variants (primary first) | baseline | material change vs logged failure |
|---|---|---|---|---|---|
| E1 | **EIA crude FADE.** Release T (Wed 10:30 ET; official 2025-26 exceptions; pre-2025 holiday weeks Mon-Wed skipped). m = close(T+15) - close(T-1). If \|m\| >= k x ATR14d (previous day's daily ATR), enter AGAINST m at the T+16 close. Stop: the T..T+15 extreme -/+ 0.1 ATR14d; target: close(T-1); time exit T+120. | WTI proxy (CRUDEOILM future) | k = 0.20, 0.35 | flat (0) | new: fade, not break |
| E2 | **EIA crude FOLLOW the post-release range.** Range = high/low of closes T..T+R. First close beyond it in (T+R, T+120] -> follow at the next close. Stop: range middle; target: edge + 1 width; time exit 90 min after entry. | WTI proxy | R = 30, 15 | flat | M3 EVT used the 15-min PRE-release range |
| E3 | **NG storage FADE.** As E1 on Thursday 10:30 ET (pre-2025 weeks with a US federal holiday Mon-Thu skipped). | Henry Hub proxy (NATGASMINI) | k = 0.20, 0.35 | flat | new: fade |
| E4 | **NG storage FOLLOW the post-release range.** As E2. | Henry Hub proxy | R = 30, 15 | flat | M4/M3 used pre-release ranges |
| E5 | **OPEC+ meeting follow.** Meeting dates from `hunt/nn_crude/calendar_events.py` (approximate, 2014-2025). At the first MCX daily close after the meeting date, follow the sign of that day's close-to-close move; hold 3 trading days; stop 2 x ATR14d on the close. | MCX CRUDEOIL daily (CRUDEOILM) | hold 3, 5 days | flat | new |
| E6 | **US-open ORB with agreement.** 09:00-09:15 ET range of the proxy; first 1-min close beyond it by 11:00 ET -> follow; stop range middle; target edge + 1 width; time exit 60 min (M3 USORB exits). Filters: (a) daily trend agrees (previous close above / below its 20-day SMA); (b) cross-market breadth agrees (crude ORB taken only if Henry Hub's 09:00 ET -> signal-minute move has the same sign; NG ORB only if WTI's does). | WTI / HH proxy | per market: SMA20 filter, breadth filter | **M3 USORB unfiltered (logged failure)** | agreement filters (R72/R73 style) |
| E7 | **Liquidity sweep on crude 15m / 1h.** The app's Liquidity book (`indicator/liquidity.py` swing_zones(20) + pool_zones(2, 5, conf 10); `research/liquidity_gold.py` simulate exits: failed break, next liquidity, new liquidity, session cut) on proxy bars inside MCX hours, both sides, plus a hard stop at 1 x ATR(14) of the book's bars. | WTI proxy | 1h, 15m | flat | M3 tested 15m/5m on MCX options only (14 months); 1h is new, the proxy gives 7 years |
| E8 | **Daily Donchian with agreement and breadth.** Donchian 20-day close breakout on CRUDEOILM / NATGASMINI / COPPER(proxy); initial stop 2 x ATR14, then a 3 x ATR chandelier trail; one position per market. Filters: (a) Supertrend(10, 3) daily agrees; (b) breadth: crude only if copper's 50-day trend has the same sign, NG only if crude's, copper only if crude's. | MCX daily (copper: COMEX x INR) | per market: ST filter, breadth filter | Donchian-20 unfiltered with the same stops | daily (not 5m), agreement filters on commodities |
| E9 | **Natural-gas seasonality.** Month-of-year mean of Henry Hub monthly returns, estimated only from years before the trade (from 2000). Trade the MCX NG future for the month in the sign of that mean when \|t\| >= 1.5 (primary) / always (alt); stop 2.5 x ATR14d on the close. | Henry Hub history (signal), MCX NATGASMINI (trade) | \|t\|>=1.5, all months | always-short NG (carry) and flat | new |
| E10 | **ATR-percentile regime on the app's NG evening breakout (EVE).** EVE exactly as `McxEveRules` (17:00-19:00 IST range, break 19:00-22:00, mid stop, edge + width target, 120 min, flat 23:15), taken only when the previous day's ATR14d percentile over the past 250 days is < 50 (calm, primary) / >= 50 (busy). | Henry Hub proxy (futures) + MCX real replay | calm, busy | **EVE unfiltered (the app arm)** | regime filter on the app arm |
| E11 | **12-month trend arm on energy: exits.** TSM252 monthly sign on CRUDEOILM and NATGASMINI (app rule), plus (a) a 3 x ATR14d chandelier stop: stopped legs stay flat until the next month-end signal; (b) weekly re-check of the sign instead of monthly. | MCX daily | +chandelier, weekly | **the app's TSM252 on the same legs** | new exits |
| E12 | **Rollover week.** Hold the near future over the last 5 MCX trading days before its expiry (expiry = the day before the OI-jump roll in the continuous series), direction = long / short, stop 2 x ATR14d. | MCX daily | crude long, crude short, NG long, NG short | flat | new |

- Variants in total: E1 2, E2 2, E3 2, E4 2, E5 2, E6 4 (2 markets x 2 filters), E7 2, E8 6 (3 markets x 2 filters),
  E9 2, E10 2, E11 2, E12 4 = **32 variants** (baselines are reported, not counted as candidates). Any variant added
  later is logged as an amendment and counted.
- Ideas considered and **not tested**, with reasons: API Tuesday (released 16:30 ET = 02:00-03:00 IST, after MCX closes;
  untradable, M3); zinc and aluminium (no data); roll TIMING of the trend arm (no next-month price history: the continuous
  series has the near contract only).

## 5. Statistics and gates

- Random twins (200 sets per rule): every trade is re-run with the same side and exits from the same clock time
  (intraday) or with the same holding rule (daily) on a random other trading day of the same calendar year (for event
  rules: a non-event day of the same weekday). Twin p = (1 + #twin totals >= actual) / 201.
- Benjamini-Hochberg across ALL 32 variants (unread variants count with p = 1).
- Top-5 check: final net minus its 5 best trades must stay > 0.
- **Pass (all needed):** final net > 0 and > the baseline's final net; walk-forward net > 0 and > the baseline's
  walk-forward net; final twin p < 0.10; BH q < 0.10; not carried by its top 5 trades; at least 30 final trades.
  For the MCX-real replay the sign must agree (reported; with ~14 months it cannot pass alone).
- Look-ahead guard: every rule reads only bars closed at or before its decision minute; pytest tests in
  `research/hunt/r83/test_r83.py` shift the future and check that no past decision changes.
- Sample size is given with every number.

## 6. Deliverables

`research/HUNT_R83_MCX_ENERGY.md`, one experiment entry per idea, a FINDINGS line, `APP_SPEC.md` for any pass, RESULT.md.
