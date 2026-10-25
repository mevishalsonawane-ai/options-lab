# R104 pre-registration: every testable rule from the 50 books, then new strategies the books suggest

Written 25 Oct 2026, **before any R104 number was computed**, and committed and pushed on `claude/r104-books` before the
first engine run. Boss, 25 Oct: *"Now find the way to implement all the book studies ... and also find out any new
strategies could be found from the findings of the book."*

Research only. Nothing in `android/**`, no arm or paper setting is touched. A rule that passes every gate below is
reported as **ready for paper** under the PAPER rule (CLAUDE.md, 13 Oct); nothing goes live without Boss's yes, and any
live change that could skip or resize a trade starts in shadow.

## 1. Inputs and what is already settled

- Rules: `research/books/TESTABLE_RULES.md` (211 rules, one `proposed` entry each in `research/experiments/entries/book-*.yaml`).
  **The mechanics of every rule are exactly the ones written there** (entry, stop, exit, filters, timeframe, instruments
  and the "Assumptions" readings). This file adds only the conventions in section 5 that the rules leave open, and the
  per-rule deviations listed in `PREREG_RULES.md` (data proxies, dropped clauses). Nothing else is changed.
- Read first (so nothing tested is re-run as new): `FINDINGS.md`, `research/books/README.md` and the HUNT files the rules
  cite (R3 / R23 / R49 / R92 ORB, R71 chart patterns, R72 / R51 Donchian, R81 / R100 exits, R86 / R91 fades, R87 candles,
  R89 PDHL, R90 BOS, R94 / R96 MCX, R95 CCI, R101 arm filters, R102 index facts, R103 selling, H14 / H15 / H25 / OBUY).

## 2. The register (`r104rules.py`, table in `PREREG_RULES.md`)

| status | rules | what R104 does |
|---|---|---|
| test | 166 | run as below; one gated test per (rule, market): **221 tests** |
| covered | 18 | near-duplicate of a tested rule; the earlier result is recorded beside the rule, no new test, no BH budget |
| lack | 26 | needs data we do not have (22 flagged in TESTABLE_RULES.md plus 4 found here); the reason is stated |
| desc | 1 | Bennett's skew-vs-range question: a regression, reported, not gated |

"Covered" is used only where the book's version is the same mechanism with the same kind of stop and exit (for example
the scale-out rules against R81 X5, the indicator crosses against H25 / OBUY, ORB on VWAP's side against R10 / R92).
Where the book's version materially differs (the trend side only, the book's own wider stop, a daily instead of an
intraday timeframe, a cointegration gate), it is tested and the difference is written in its row.

Tests per market: IDX 78, MCXI 40, DLY 27, ARM 20, VOL 17, MCXD 16, MM 12, CRY 6, IDXF 4, STK 1.

## 3. Families (Boss's list, plus three that collect the rest)

F1 volume-confirmed breakouts (Wyckoff burst, Darvas with volume, exhaustion bar on volume); F2 Wyckoff tape / volume and
his trade management; F3 price-action bar and chart patterns with the books' own stops (Woods, Hougaard, the Advanced
Trading Book, Sadowski's candles); F4 Carli divergence; F5 swing rules on daily bars (Larry Swing, Graeme-Smith,
Ozenbas's 65-day MA, Sadowski daily, Fabozzi, Chan's turning point); F6 Porwal's Indian setups; F7 Aziz VWAP / ABCD /
bull flag; F8 volatility and premium structures (Bennett, Wilmott, Javaheri, Bjork, Musiela, Ross, Wilmott-Howison-
Dewynne); F9 money management (Heitkoetter's weekly goal, Aziz 1% / 6%, Douglas 20-trade blocks and fixed risk, CUSUM,
Kelly / Merton / min-variance / cushion sizing, Chan's throttle and worst-day cap); F10 indicator and intraday trend
rules; F11 exit overlays and entry filters on the arms; F12 pairs, mean reversion, seasonals and crypto.

## 4. Data (the Drive lake, `gdrive:IraAlgo-data-lake` and `gdrive:IraAlgo-data-crypto`, copied 25 Oct 2026 with rclone; never in git)

| market | files | window |
|---|---|---|
| index spot 1-minute (no volume) | `index_1m_<IDX>_<YEAR>` NIFTY 2017+, BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX 2021+, INDIAVIX | to 19 Oct 2026 |
| index options (held contracts, ATM +-10, nearest expiry) | `opt_NIFTY_WEEK_*`, `opt_BANKNIFTY_WEEK_*` via R103's builder (R51 Amendment A cleaning) | 2 Aug 2021 - 19 Oct 2026 |
| arms' stores (5 indices) | R51's default build (R101) | 20 Nov 2024 - 13 Oct 2026 |
| index daily | `index_daily` (NIFTY, BANKNIFTY, FINNIFTY, SENSEX, BANKEX, INDIAVIX) | 2006 - 19 Oct 2026 |
| futures daily with volume and OI (near month) | `fut_daily_continuous`: NIFTY / BANKNIFTY / SENSEX from Oct 2011; MCX GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, NATURALGAS 2012+, CRUDEOILM 2015+, NATGASMINI 2023+ | to 16-19 Oct 2026 |
| MCX futures 1-minute with volume | `fut_1m_mcx_continuous` (roll flagged) | 20 Oct 2025 - 19 Oct 2026 |
| index futures 1-minute with volume | `fut_1m_live_index` | 29 Jul - 19 Oct 2026 only (57 sessions) |
| F&O stocks daily | `equity_daily` (59 names) | 2002 - 12 Oct 2026 |
| crypto | Binance USD-M perps 1-minute, BTCUSDT and ETHUSDT | Oct 2023 - Oct 2026 |
| events | `research/context/events.csv` (Budget, RBI, US CPI, FOMC, results) | 2024 - 2026 |

**What cannot be reached here**, so those rules are `lack`: index-futures ticks and 1-minute volume before 29 Jul 2026
(every index volume rule: Wyckoff 01 / 03, Porwal 10 / 11, and the volume parts of the index versions of Wyckoff 02 / 04
and Porwal 05, which are tested on MCX instead); bid/ask, depth and queue history (Ozenbas 03 / 06 / 07, the exit-slicing
rule, every parity / butterfly / no-arbitrage trade); a second expiry's chain (Bennett 06 / 07 / 08, Javaheri 02); strikes
beyond ATM +-10 with daily history (Javaheri 01, the two density trades of Applied Quantitative Finance); intraday USDINR
and import duty (Musiela 04); ETF / basket minute prices and dividends (Lyuu); results / AGM dates for stocks (Smith 05);
index-futures minutes for the basis trade (only 57 sessions).

## 5. Engines and the conventions the rules leave open

**Signal source (CLAUDE.md, 14 Oct).** Every entry and exit signal is computed on the underlying: the index spot (index
rules), the MCX future (MCX rules), the perp (crypto), the stock (stocks). Option-chain data appears only as context in
the volatility rules (IV, skew) and as the thing bought or sold. Stops and targets are on the underlying unless the rule
puts them on the premium. **No rule in R104 reads an option's own price chart for its signal.**

| market | what is traded | fills and costs |
|---|---|---|
| IDX | the option the rule names (1-ITM where the rule says so, else ATM), nearest expiry, 1 lot, dated lots (R51 table) | R51 / R64 held-contract engine: bought at the open of the minute after the trigger, sold at the open of the minute after an exit trigger; R17 `liq` slippage (+-5 bps plus 1 / 2 / 4 ticks by the contract's last-5-minute volume; stop exits 2x) and `dated` charges (STT, exchange, SEBI, stamp, GST, Rs 20 an order, the rates in force on the date) |
| IDXF, DLY | the near-month index future, proxied by spot points x the dated lot | carry: a long pays (and a short earns) 5% a year of notional for the days held (r - q, about 6.5% - 1.5%); futures charges dated (Rs 20 an order, STT on the sell side 0.01% to Mar 2023, 0.0125% to Sep 2024, 0.02% to Mar 2026, 0.05% from Apr 2026; exchange 0.0019%, 0.00173% from Oct 2024; SEBI Rs 10/crore; stamp 0.002% on buys; GST 18%); half-spread max(1 tick, 0.5 bp) a fill, stops 2x |
| MCXI, MCXD | the mini future (GOLDM, SILVERM, CRUDEOILM, NATGASMINI; the big contract's series where the mini has no history: CRUDEOIL before 2015, NATURALGAS before Mar 2023, then valued per mini lot), 1 lot | R94's Zerodha MCX futures charges and half-spreads (`r94_mcx_advanced/common.py`), stops 2x the half-spread; MCXD back-adjusted at each roll (roll days found where the near contract changes: OI jump with the old contract's expiry passed; the roll-day open gap is removed, labelled) |
| CRY | the BTCUSDT / ETHUSDT perp, notional Rs 1,00,000 a trade | 0.05% taker fee + 18% GST each side (Delta Exchange India), half-spread 1 bp, stops 2 bp; funding ignored for intraday, charged from the funding file for multi-day holds; pre-tax (India's 30% crypto tax is named, not applied). **Every crypto trade has a stop and a profit lock** (CLAUDE.md): rules without a lock get the standard lock: at +1R the stop moves to entry plus costs, then trails at 50% of the open profit |
| STK | a stock-future proxy, notional Rs 5,00,000 a trade | futures charges as IDXF, half-spread 2 bp |
| ARM | the arms' own options (R101's B0 candidates: Liquidity 15+5, R72 Breadth Donchian, R51 Donchian, R52 OOV) | R101's engine exactly; overlays re-price each candidate with the changed exit, filters drop candidates before the one-position walk (R80 / R101 method) |
| MM | the B0 trade lists of the four arms (closed trades, net per lot, entry / exit times) | sizing in whole lots on the same trades, Rs 5,00,000 starting capital where a rule needs equity |
| VOL | option legs from the held-contract store (NIFTY weekly / BANKNIFTY nearest; NIFTY monthly series for the monthly rules) | R103's engine conventions: fills at the open of the minute after the decision, `liq` slippage per leg, dated charges per order, stale legs (no print in the 5 minutes before a fill) repriced by Black-Scholes at the leg's last implied vol (R103 Amendment 2); hedges on the spot as a futures proxy with futures charges and carry; cash-settled expiry at the intrinsic value with STT on exercise |

Conventions (only where a rule is silent; a rule's own words win):
- Bars are built from 1-minute bars: NSE 5 / 10 / 15 / 30 / 60-minute bars from 09:15; half-session bars 09:15-12:15 and
  12:15-15:30; MCX bars from 09:00. Indicators run continuously across sessions (as a chart does). ATR = Wilder ATR(14) of
  the bars the rule trades. Swings = fractals with the bars-each-side the rule names (2 if silent), confirmed only after
  the right-hand bars close.
- Trend "EMA 20 slope" = close above (below) EMA 20 and EMA 20 higher (lower) than 5 bars before.
- "Nearest opposing swing" target = the most recent confirmed swing high above the entry (long; low below for shorts);
  none = no target.
- Entry window for intraday NSE rules that do not state one: 09:30-14:30. One open position per rule per instrument;
  at most 2 entries a side a day. Exits: the rule's own, the stop first when stop and target fall in the same minute,
  square-off 15:10 (15:05 on expiry days) for NSE; MCX 10 minutes before the session's last bar.
- "Buy stop above the bar, valid N bars": triggers when a later 1-minute high trades through the level inside the
  window; the option is bought at the next minute's open; the future fills at max(open, level).
- Partial exits (thirds, halves) are modelled as separate equal sub-lots, each with its own exit; results are per lot.
- Rules with a book grid or listed variants (timeframes, stop readings, x% grids) are ONE test: the variant is chosen
  walk-forward (anchored: for each fold, the variant with the best net over the earlier design folds; fold 1 trains only),
  and the holdout trades the variant with the best full-design net. Every variant's numbers are reported.
- Event clauses use `events.csv`; before its first date every day counts as a no-event day (labelled).

## 6. Periods (fixed now; the holdout of each market is read once, for every test of that market at the same time)

| market | design (walk-forward folds) | locked holdout |
|---|---|---|
| IDX, VOL | 2 Aug 2021 - 31 Mar 2026; folds Aug 2021-Dec 2022, 2023, 2024, Jan 2025-Mar 2026 | 1 Apr - 19 Oct 2026 |
| IDXF | as IDX (NIFTY rules that can use 2017-2021 add a fold 2017-Jul 2021) | 1 Apr - 19 Oct 2026 |
| DLY, STK | 2006 (2012 for rules on futures volume) - 31 Dec 2021; folds 2006-09, 2010-13, 2014-17, 2018-21 | 1 Jan 2022 - 19 Oct 2026 |
| MCXD | 2012 - 2021; folds 2012-14, 2015-17, 2018-21 | 1 Jan 2022 - 16 Oct 2026 |
| MCXI | 20 Oct 2025 - 31 Mar 2026; folds 20 Oct-31 Dec 2025, Jan-Feb 2026, Mar 2026 | 1 Apr - 19 Oct 2026 |
| CRY | 1 Oct 2023 - 31 Mar 2026; folds Oct 2023-Jun 2024, Jul 2024-Mar 2025, Apr 2025-Mar 2026 | 1 Apr 2026 - end of data |
| ARM, MM | R101's: 20 Nov 2024 - 31 Mar 2026; walk-forward quarters Apr 2025 ... Mar 2026 | R101's final: 1 Apr - 13 Oct 2026 |

The holdout is served only after `results/HOLDOUT_READ` is written by `r104study.py holdout`, which refuses a second
read. Indicator warm-up may read bars before a design start; no trade is taken or reported outside its period.

## 7. Gates (never relaxed)

An entry rule (IDX, IDXF, DLY, MCXI, MCXD, CRY, STK, VOL) PASSES only if all hold:
1. **Design net after costs > 0** (the walk-forward-chosen series for multi-variant rules).
2. **Net > 0 in every walk-forward fold** (for multi-variant rules every fold after the first, which only trains).
3. **BH q < 0.10 across ALL R104 tests** (the 221 here plus every Amendment test, one family at the end). p = one-sided
   day-block bootstrap (10,000 resamples, seed 104) that the design mean net per trade is <= 0.
4. **Minimum trades**: design / holdout >= 100 / 20 (IDX, IDXF), 40 / 20 (MCXI), 60 / 20 (CRY, VOL), 30 / 10 (DLY,
   MCXD, STK).
5. **Not carried by its 5 best trades**: design net minus the 5 best trades > 0.
6. **Random-entry twin** (passers of 1-5 only, before the holdout): 500 twins with the same exits, the same days, a
   uniform random minute in the rule's entry window and a coin-flip side; p < 0.05. VOL filters instead beat 500 random
   day subsets of the same size of the unfiltered structure.
7. **Holdout read once: net > 0** (same sign as design).
Win rate is reported beside net everywhere; a higher win rate never passes a rule on its own. Twice-slippage results are
reported for every passer (not gated).

An ARM overlay or filter PASSES only if, against B0 on the same candidates: design Δnet > 0; Δnet > 0 in every
walk-forward quarter; BH q < 0.10 (p = paired day-block bootstrap of the daily Δ); final Δnet > 0; it changes >= 40
final trades; and for filters, the kept trades beat 500 random removals of the same count (p < 0.05).

A money-management rule **helps** (risk-lowering bar) only if, on the arms' B0 trades, in design AND in the final period:
the maximum drawdown of the daily equity falls by >= 20%, net falls by <= 10% (or rises), the worst calendar month is
not worse; it beats 500 random exposure cuts of the same size on the drawdown cut (p < 0.10, in BH with everything else);
and it never adds lots beyond the arm's base size unless the rule's own book formula does and that is reported
separately as "adds risk". A sizing rule that adds risk can only be "interesting", never "helps".

## 8. Number of tests and what follows

221 gated tests now. Every family's code is committed and pushed before that family runs; results are committed after
each family. After the design results of all families, **Amendment 1** (new strategies the books suggest that our research
has not tried, each with its hypothesis written first) is committed before the holdout is read; its tests join the BH
family. Then the holdout is read once for every test, and `HUNT_R104_BOOKS.md`, `FINDINGS.md`, the experiment entries,
the book notes, the knowledge pack and `RESULT.md` are written.

## Amendment 0 (25 Oct 2026, about 11:40 IST; a data fact found while loading, BEFORE any R104 result)

The MCX 1-minute futures file (`fut_1m_mcx_continuous`) is shorter than section 4 says: SILVERM runs 20 Oct 2025 -
19 Oct 2026, but GOLDM starts 6 May 2026, NATGASMINI 29 Apr 2026, CRUDEOILM 21 May 2026 (GOLD 14 May, SILVER 6 May,
CRUDEOIL 19 Jun, NATURALGAS 27 May). The section 6 MCXI split (design to 31 Mar 2026) would leave only SILVERM in design.
MCXI periods are therefore changed to: **design = each instrument's first session - 31 Jul 2026; walk-forward folds
first session - 31 Mar 2026 (SILVERM only), Apr-May 2026, Jun-Jul 2026; locked holdout 1 Aug - 19 Oct 2026.**
Minimum trades stay 40 / 20. Earlier MCX intraday studies (R53 / R96) used a close-only futures proxy from the option
rows' `spot`, which has no volume and no true high / low; R104 keeps the real futures bars because most MCXI rules need
volume, highs and lows. Everything else is unchanged.
