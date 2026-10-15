# R47 pre-registration: relaxed index-option BUYING search

Written 15 Oct 2026, BEFORE any R47 trade or P&L was computed. Committed and pushed on its own, together with
`r47cfg.py`, which holds every number below.

## Why

Boss (15 Oct): *"for index option buying, you can relax some rules, then try, if we could get some results."* Every
study so far (R3, R21-R45, F1, R41, B2) found no edge in BUYING index options under our usual design constraints. R47
relaxes the **design** of the strategies. It does **not** relax the **measurement**: relaxing measurement only
manufactures false wins (R45's "+Rs 174 a trade" came from pricing a trade on the rolling ATM series).

## What is relaxed (each labelled in the write-up)

1. **Signal source.** Besides the index (the default, label **U**), signals from the option chain (label **C**: PCR,
   OI shift, ATM IV) and from an option's own price chart (label **O**, *non-default: option-chart signal*; only Boss
   could ever switch such a rule on, and only for paper). Event / calendar timing is label **E**; the tree model is
   label **M** (underlying + chain features).
2. **Holding period.** Intraday, overnight (exit next session 09:16) and 1, 3, 5-session holds (exit 15:15). Gap risk is
   priced on the real next-session rows of the held contract.
3. **Instruments (all BUYING).** Near (WEEK series) ITM3, ITM1, ATM, OTM2, OTM5; month (MONTH series, NIFTY and SENSEX)
   ATM and ITM2; debit spreads (buy ATM / sell OTM3; buy ITM2 / sell OTM2: a bought, limited-risk position); straddle
   (ATM) and strangle (OTM2) buying, near and month. Next-month expiry is not in the lake (only the nearest weekly and
   nearest monthly contracts are), so it cannot be tested.
4. **Trade count.** No "max 2 a day" cap. One position at a time per rule (a new signal while the rule's previous
   position is open is skipped). Sizing by a premium budget of Rs 20,000 a position (lots = floor(budget / cost of one
   lot), at least 1, at most 20, at most 10% of the lots the contract traded in the 5 minutes before entry).
5. **Search breadth.** Every combination of 19 signal families (their parameters), 4 underlyings, 12 instruments,
   20 intraday / 16 positional / 4-6 event exits and 14 regime filters, on DESIGN data only, with no BH during
   exploration. A gradient-boosted tree model (M1) is one of the families.
6. **Regime / calendar filters.** Previous VIX close (< 14, 14-20, > 20), weekday, sessions to the near expiry (0, 1,
   2+), the last 3 sessions of a month, event days +-1 (RBI policy, budget). FII flows and global cues are NOT in the
   lake (not used).

## The search space (r47cfg.FAMILIES; signal source in brackets)

| family | rule (direction +1 = buy calls / bull spread, -1 = puts / bear spread) | params |
|---|---|---|
| U1_orb [U] | first 5-minute close beyond the 09:15 opening range of 15/30/60 min, up to 13:30; each side once a day | OR length |
| U2_gap [U] | at the 09:20 close, gap open vs previous close > 0.3/0.6/1.0%: follow or fade | threshold, follow/fade |
| U3_lateday [U] | at the 15:20 close, day move from the open > 0.5/1.0%: follow or fade, held overnight / days | threshold, follow/fade |
| U4_donchian [U] | 15:20 close above the highest (below the lowest) of the previous 10/20/55 session closes, held | N |
| U5_daily_mr [U] | 15:20: RSI(2) of session closes < 10 -> calls, > 90 -> puts; or 3 down (up) sessions in a row -> calls (puts) | kind |
| U6_ema [U] | 5-minute EMA 9/21 or 5/20 cross, 09:45-14:30, optionally only on the VWAP's side | EMAs, VWAP filter |
| U7_bigbar [U] | 5-minute bar range > 2/3 x median of the last 20 bars, closing in its outer quarter: follow or fade | k, follow/fade |
| U8_pdhl [U] | first 5-minute close beyond the previous session's high/low (break), or a poke beyond and close back inside (fail -> fade) | break/fail |
| U9_vix [U] | VIX close up > 10/15% on the day -> buy calls at 15:20 (fear reversal), held | threshold |
| U10_trendday [U] | at 13:00/14:00 the index is > 0.4/0.8% from the open and on the VWAP's side -> follow to 15:15 | threshold, time |
| C1_pcr [C] | OI put/call ratio of the ATM+-10 chain at 10:15/11:30, in its top (bottom) fifth of the previous 60 sessions at that minute: follow (high PCR -> calls) or fade | time, follow/fade |
| C2_ivcheap [C] | ATM IV in the bottom 10% / 20% of the previous 60 sessions -> buy the straddle/strangle (at 15:20, held; or at 09:30, intraday) | percentile, timing |
| C3_oishift [C] | 09:20 -> 11:00 change of (put OI - call OI) / total OI in its top (bottom) fifth of the previous 60 sessions: follow or fade | follow/fade |
| O1_prem_orb [O] | the 09:15 ATM call (put) closes a 5-minute bar above its own 15/30-minute opening-range high -> buy that contract | OR length |
| O2_prem_mom [O] | the 09:15 ATM call (put) is up 20/40% from its 09:15 open at a 5-minute close (09:30-13:30) -> buy that contract | x |
| E1_event_vol [E] | straddle bought at 15:20 the session before an RBI / budget day, or at 09:20 on the day | timing |
| E2_expiry_vol [E] | straddle / strangle bought on an expiry day at 09:20 / 11:00 / 13:00, held to 15:05 | time |
| E3_expiry_hero [E] | expiry day 13:30/14:15: index > 0.3/0.6% from the open -> buy in that direction | time, threshold |
| M1_gbm [M] | trees on underlying + chain features at 10:15 / 11:45 / 13:15 predict the index's direction to 15:15; trade when P(up) > 0.55/0.60 (or < 1 - that); trained on earlier blocks only | threshold |

Directional families use the 9 directional instruments; C2, E1 and E2 use the 3 straddle/strangle instruments; O1 and
O2 buy the very contract whose chart signalled (1 instrument). Exits: `r47cfg.XI` (intraday: stop 25/40/none x target
50/100/none x 60 minutes / 15:15, plus 2 profit-lock versions), `XP` (positional: stop none/50% x target none/100% x
next 09:16 / 1 / 3 / 5 sessions), `XE1`, `XE2` (events). All on the position's value.

Events: `research/brain/calendar_data/events.csv` (2024+) plus the 2021-2023 RBI policy and budget dates, kept in
`r47sig.EXTRA_EVENTS` (rbi.org.in / indiabudget.gov.in dates). E1 cannot reach 40 holdout trades, so it is
**exploration only** (it can never be sent to the holdout); this is decided now.

## Measurement (KEPT, non-negotiable)

- **One contract per leg, from entry to exit.** The leg's strike is read at the decision minute from the lake's
  rolling rows (`strike` of offset k, that side), and the contract (that strike, that side, that series' current
  expiry) is priced from every lake row that carried it, whatever its offset. Never the rolling series.
- **Fills:** decision on a minute's (or 5-minute bar's) close -> fill at the open of the contract's first row in the
  next 3 minutes (no row: no trade). R17 'liq' fills (`research/obuy/costs.Fills("liq")`: 5 bps plus 1/2/4 ticks by the
  5-minute traded lots; stops 10 bps and twice the ticks; short legs of spreads pay the same on the other side).
- **Stops** on single legs fill at the worse of the level and the row's open (gaps, including the next session's
  open); stop before target within a minute. Spreads / straddles are checked on minute closes of their net value and
  exit at the next minute's opens. Time exits at the open of the first row at/after the exit minute.
- **Coverage:** a held contract that reaches ATM+-9 is sold at the next minute (a real, causal rule; the lake keeps
  only ATM+-10). A position whose contract is out of the lake's rows at an exit (an overnight gap past +-10) is valued
  conservatively: a bought leg at its intrinsic value (0 if out of the money), a sold leg at the nearest covered strike's
  price plus the strike difference. These cases are counted and reported.
- **Expiry:** positions on the near (month) series are sold by 15:05 on its expiry day; no positional entry on the
  contract's own expiry day. Expiry days are recovered from the lake's rows (R25 time-value method, gaps filled by the
  same test, `r47data.expiries`).
- **Charges:** `Costs("dated")` (the rates in force on the day, BSE for SENSEX), every leg, both sides.
- **No look-ahead:** every signal at minute m reads index / chain / option rows up to m only (prefix tests in
  `test_r47.py`); filters use the previous session's values; percentiles use the previous 60 sessions only; the tree
  model is trained on earlier blocks only.

## Data split

- Data: Drive lake `gdrive:IraAlgo-data-lake`, `options_rolling_1m` (NIFTY, BANKNIFTY, FINNIFTY, SENSEX), `index_1m`,
  `index_daily`. MIDCPNIFTY is left out (its expiry dates cannot be recovered: `research/brain/expiry_infer.py`).
- **Design:** 1 Aug 2021 - 31 Oct 2025. Walk-forward blocks B1 Aug 2021 - Dec 2022, B2 2023, B3 2024, B4 Jan - Oct 2025.
- **Embargo:** 1 - 9 Nov 2025 (used by neither).
- **LOCKED holdout:** 10 Nov 2025 - the lake's last session (13 Oct 2026), about 11 months. This study has never read
  it. Design runs load no row after 31 Oct 2025 (`r47data` refuses); a design trade that would still be open after
  31 Oct 2025 is dropped. `r47holdout.py` reads the holdout once, only for the frozen candidates, and refuses if
  `results/holdout.json` exists or `results/candidates.json` is not committed unchanged.

## Selection rule (fixed now)

- **Family** = (signal family, underlying, instrument kind: directional single leg / debit spread / straddle).
  **Variant** = (signal params, instrument, exit, filter).
- **Anchored walk-forward on design:** for each test block B2, B3, B4, pick the family's variant with the highest
  training t-statistic of net per trade (mean / (sd / sqrt n)) over all earlier blocks, among variants with 40+
  training trades and a positive training net (none -> no trades that block). Its trades in the test block are the
  family's out-of-sample (OOS) trades.
- **A family qualifies** if its OOS trades number 100+, OOS net > 0, and 2 of the 3 test blocks are positive.
- **Its candidate** is the variant with the highest t-statistic on the whole design, among variants with 100+ design
  trades, design net > 0, net still > 0 without its 5 best trades, and positive in all but at most one of its design
  blocks (blocks with trades). If none, the family sends nothing.
- **Sent to the holdout:** at most 10 candidates, the qualifying families ranked by OOS t-statistic, at most one per
  (signal family, underlying). E1 never (too few events). Written to `results/candidates.json` with every detail, and
  committed before the holdout read.

## Holdout gates (all must pass)

1. holdout net after charges > 0;
2. 40+ holdout trades;
3. random-entry twins: 500 twins per candidate, each trade re-entered at a random holdout session (same kind of day:
   expiry days for E2/E3, any session otherwise) and a random decision minute in the family's entry window, with the same
   direction, instrument, exits and sizing; one-sided p = (1 + twins with net >= the candidate's) / 501 < 0.05;
4. Benjamini-Hochberg across the candidates sent: q <= 0.10;
5. the 5 best holdout trades are < 100% of the holdout net (net without them > 0);
6. not dependent on one period: holdout net without its best calendar month > 0 (and on design, the candidate rule
   above already needs all but one block positive).

Anything that passes every gate would be described exactly (instrument, entry, exit, size, signal source) as a PAPER
proposal for Boss; an option-chart (O) rule only with Boss's own yes. Nothing in R47 changes the app.

## Amendment 1 (15 Oct 2026, after the first design run, BEFORE any candidate was frozen or the holdout touched)

Auditing the first selection's top design trades showed stray prints in thin contracts: e.g. FINNIFTY 18200 CE on
5 Apr 2022 printed a 09:15 open of 500 and a 09:16 open of 151 while trading near 60, and a sell at that 09:16 open made
a +107% "win" while the index fell. This is a MEASUREMENT error, so it is fixed (never relaxed): a fill can no longer
profit from a stray print. When a row's open is more than 20% (and 2 ticks) away from the median of the contract's
closes on the rows before, at and after it, the fill takes the WORSE of the open and that median; a high more than 20%
above that median cannot hit a target or arm a lock. Stops still trigger on any low. (`r47price.open_for_sell /
open_for_buy / confirmed_high`, `r47cfg.BAD_PRINT`, tests in `test_r47.py`.) The fix can only lower results. The
whole design search and the unchanged selection rule were run again; the first run's output is kept as
`results/design_v0_unamended.json` and `results/candidates_v0_unamended.json` (never sent to the holdout).

## Amendment 2 (15 Oct 2026, written AFTER the one holdout read; it changes no candidate and reads nothing twice)

While checking an impossible-looking design number for the write-up (FINNIFTY trees +Rs 4,807 a trade), we found that the
lake's rolling option files contain rows of ANOTHER underlying. One example is FINNIFTY 2025 rows with spot and strike
near 12,500 (MIDCPNIFTY's level) while FINNIFTY traded near 25,700. Other files are affected too (see the write-up's
table). Fix (measurement): an option row whose own `spot` is more than 3% off the underlying's index close at that
minute is dropped (`r47data.foreign_rows`, `r47cfg.FOREIGN_TOL`).

To find out whether this touched the result, without a second look at the holdout for selection:

1. The design stores were rebuilt with the filter. The full search and the UNCHANGED selection rule were rerun on design
   data only (`r47select.py --tag v2` -> `results/design_v2.json`, `results/candidates_v2.json`).
2. Only if that rerun froze exactly the same candidates, the same frozen candidates are re-priced on the cleaned holdout
   rows (`r47holdout.py --recheck`, refused otherwise by `r47data.recheck_key`) -> `results/holdout_amended.json`.
   `results/holdout.json` (the one read) is kept unchanged.

Outcome: the rerun chose the identical 10 candidates with identical design numbers. The re-pricing gave identical
holdout trades for all ten; only C3's random twins moved (p 0.40 -> 0.48). No verdict changed.

## Amendment 3 (15 Oct 2026, self-review after the holdout read; no candidate changed, nothing read twice)

A look-ahead bug: for underlyings whose index minutes start on 4 Aug 2021 (BANKNIFTY, FINNIFTY, SENSEX), the first
session had index 0, so "the previous session" wrapped round to the last design session. Signals now start at session 3
at the earliest (`r47sig.sessions_from`), with a test. The design search and the unchanged selection rule were rerun
(`results/design_v3.json`, `results/candidates_v3.json`). Nine candidates are identical. In slot 10, BANKNIFTY big-bar
(C9, which never read the wrapped value; its walk-forward pick moved with a 3-session later warm-up) is replaced by the
NIFTY O1 premium ORB (option-chart, non-default). That rule is NOT sent to the holdout, which has already been read once.
The official holdout result stays that of the 10 frozen candidates.
