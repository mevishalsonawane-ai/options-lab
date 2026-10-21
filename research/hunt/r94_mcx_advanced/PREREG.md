# R94 pre-registration: Boss's five "advanced commodity" ideas on MCX (written 21 Oct 2026)

Written and committed BEFORE any statistic of this study was computed. Up to this commit only data COVERAGE was looked
at: which lake files exist, which futures contracts and option offsets exist, how many minutes both legs of a calendar
pair printed, option strike steps and zero-volume shares, which dates exist in the daily file and where its open
interest jumps (roll days). No price change, spread, return, P&L or correlation was computed. Any later change is an
amendment in `NOTES.md`, dated, with its reason, written before the numbers it affects.

Research only. Nothing here touches the app, places an order or sells an option live.

## 0. The ideas (Boss, 21 Oct) and his risk rules

1. Calendar spreads: buy near / sell far month (or the reverse) on contango / backwardation shifts, no outright direction.
2. Sector index pairing: MCX BULLDEX / METLDEX / ENRGDEX against their constituent futures.
3. Option selling for theta: covered calls, short straddles / strangles on GOLD, SILVER, CRUDEOIL in range-bound or
   high-IV periods.
4. Seasonal: bullion demand around Diwali and the wedding months (NCDEX agri: no data, not tested).
5. Inter-market: COMEX gold / NYMEX crude overnight, before the MCX 09:00 open, to catch the opening gap.

Risk rules applied in every test: risk at most 1-2% of capital per position; a hard stop on every trade; liquid
contracts only (GOLD, SILVER, CRUDEOIL, NATURALGAS and their minis). Capital = Rs 1,00,000 (the MCX_GUIDE frame).
Every rule is measured per 1 lot (the smallest tradable size, the edge test) and reported with Boss's sizing check:
the share of trades whose stop risk (costs included) fits 2% of Rs 1 lakh (Rs 2,000), and the capital the median and the
largest trade would need to fit 2%. A rule whose trades cannot fit 2% at Rs 1 lakh cannot be traded under his rules at
that capital, whatever its P&L; the verdict says so.

Option selling (idea 3) follows CLAUDE.md's crypto-style rule here too: every short-option trade has a hard stop AND
a profit lock; defined-risk forms (iron condor, iron fly) are primary; a naked short strangle is tested only as a
labelled comparison (research only; nothing sells options live).

**Signal source.** Idea 1: the two futures legs themselves (the spread). Idea 2: the futures themselves (the ratio of
two roll-adjusted futures). Idea 3: no direction signal; the option chain's own ATM IV is a regime filter only
(context, allowed by CLAUDE.md); stops / locks on the premium. Idea 4: the calendar; trade = the future. Idea 5: the US
benchmark (Dukascopy CFD minutes, a labelled PROXY for COMEX / NYMEX) and the MCX future itself; trade = the MCX mini
future. No rule reads an option's own price chart for a direction.

## 1. Prior work (read first, not repeated)

- FINDINGS / BRAIN_MCX (MB1): on MCX the size of the next hour is predictable, the direction is not (AUC 0.48-0.54).
- R2 (HUNT_R2_US_MCX): the US overnight move is mostly priced into the MCX 09:00 gap (corr 0.67-0.82); the residual
  predicts the rest of the day from the daily OPEN print, but from a tradable 09:05 entry it survived only for natural
  gas (09:05-14:00) and silver (to the close), futures. R2's locked holdout was 1 Oct 2025 - 6 Oct 2026, i.e. it
  overlaps all of R94's idea-5 window. Idea 5 here is therefore a re-test, not independent evidence; it is labelled so.
- R82 (precious, proxies), R83 (energy: EIA / storage / OPEC / NG seasonality / roll week, 0 of 33), R85 (MCX candles,
  0 of 4), R42 (SILVERM spikes), MB1 (0 of 16 L4 rules): nothing directional pays on MCX after costs.
- R89 / STRAD_CRUDE / LONG_VOL: buying straddles loses; options price the move. The mirror question (does SELLING
  collect a premium after costs and tails?) has not been asked on MCX. NONDIRECTIONAL (NSE indices): the naked ATM
  short straddle with a 30-50% stop was positive in both years; hedged forms looked bad but its fill model was too
  pessimistic. R94 uses close +/- a half-spread per leg, not every leg at its worst price.
- MCX_GUIDE: next-month OPTIONS are dead (spreads 17-97%), so calendar spreads are built from FUTURES only; GOLD / SILVER
  big-lot options are out of reach at Rs 1 lakh; MCXBULLDEX / MCXMETLDEX futures are listed but trade ~0 lots a day.

## 2. Data (copied from Drive `gdrive:IraAlgo-data-lake` on 21 Oct 2026 to ~/lake; nothing raw is committed)

| idea | series | window available |
|---|---|---|
| 1 | `fut_1m_live_mcx`: 1-minute OHLCV per contract, every contract live on 19 Oct 2026, from its listing | per pair below; contracts that expired before 14 Oct are not in the lake (Dhan serves live contracts only) |
| 2 | `fut_daily_continuous` near month (expiry_code 0): GOLD, SILVER, COPPER, ZINC, ALUMINIUM, CRUDEOIL, NATURALGAS | 2 Jan 2012 - 16 Oct 2026 |
| 3 | `options_rolling_1m` MONTH books (nearest monthly expiry, ATM-3..ATM+3, 1-minute, `iv`, `spot` = underlying future): CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, GOLDM, SILVERM | 5 Aug 2025 - 19 Oct 2026 |
| 4 | `fut_daily_continuous` GOLD, SILVER | 2 Jan 2012 - 16 Oct 2026 |
| 5 | MCX near future per minute = median option-row `spot` (R53 Market) for GOLDM, SILVERM, CRUDEOIL, NATURALGAS books; US proxy = Dukascopy 1-minute BID candles XAUUSD, XAGUSD, LIGHTCMDUSD (WTI), GASCMDUSD (Henry Hub), fetched with timestamps (R83 `fetch_proxy.py` decoder) to a cache outside git | 5 Aug 2025 - 19 Oct 2026 |

Not available, said plainly:
- **MCX sector indices (idea 2).** MCXBULLDEX and MCXMETLDEX futures are in Dhan's scrip master (Oct-Jan expiries;
  no ENRGDEX future is listed), but the lake holds no history of them, the Dhan token expired (HTTP 401 on 21 Oct, it
  needs Boss's login to renew), and mcxindia.com refuses scripted requests (HTTP 403). MCX_GUIDE (8 Oct) found ~0 lots
  a day in both index futures, which already fails Boss's liquidity rule. Idea 2 is tested only through labelled
  PROXIES (section 3.2).
- **Long calendar-spread history (idea 1).** Only contracts live on 19 Oct exist per minute, so each commodity has one
  to three consecutive-month pairs with 1-5 months of overlap, all in May - Oct 2026. Dhan's next-month continuous
  daily (expiryCode 1) would give 2012+ history but needs a live token.
- **NCDEX (idea 4) and COMEX / NYMEX exchange prints (idea 5).** Not in the lake. Dukascopy CFDs are the timestamped
  free proxy (spot gold / silver, WTI and Henry Hub CFDs), labelled as such.
- USD/INR overnight is not modelled (the rupee does not trade onshore overnight; MCX = US x INR).

## 3. The rules (every choice fixed here)

### 3.1 Idea 1: calendar spreads (futures, both legs)

**Pairs** (fixed from the coverage count: consecutive listed months, Boss's four liquid families, >= 4,000 minutes in
which both legs printed): GOLDM Nov/Dec, Dec/Jan, Jan/Feb; SILVERM Nov/Feb, Feb/Apr; SILVER Dec/Mar; CRUDEOILM Oct/Nov,
Nov/Dec, Dec/Jan; CRUDEOIL Oct/Nov, Nov/Dec; NATGASMINI Oct/Nov, Nov/Dec, Dec/Jan; NATURALGAS Oct/Nov, Nov/Dec (16
pairs). "Near" = the earlier expiry of the pair, "far" = the later one, whatever their rank on the day.

**Spread series.** A 15-minute grid in MCX hours (09:00 to the session end, 23:30 US summer / 23:55 US winter). In each
15-minute bar, the observation is the LAST minute in which BOTH legs printed (volume > 0); s = ln(far close / near close)
at that minute. A bar with no common printed minute has no observation. Signals use observations up to and including
the current one; every fill is at the NEXT observation's prices (both legs' closes of that minute) plus half-spreads.

**Rules** (one position per pair at a time; side +1 = buy far / sell near, -1 = sell far / buy near):
- **Z1 (fast mean reversion).** z = (s - mean) / sd over the previous 100 observations (excluding the current). Enter
  at z >= +2 (sell far / buy near) or z <= -2 (buy far / sell near). Exit when z crosses 0, after 2 sessions, or at the
  hard stop.
- **Z2 (slow mean reversion).** Same with 400 observations; exit when z crosses 0, after 5 sessions, or the hard stop.
- **C1 (carry, bullion pairs only: the 3 GOLDM, 2 SILVERM and the SILVER pair).** Implied annual carry
  c = s / ((far expiry - near expiry) / 365). Fair carry 6.5% a year. Enter when c - 6.5% >= +3 points (sell far /
  buy near) or <= -3 points (buy far / sell near); exit when |c - 6.5%| <= 1 point, after 10 sessions, or at the stop.
- **Hard stop.** Z1 / Z2: the spread moves against the position by 2 x the entry sd (in s units) from the entry
  observation's s. C1: the carry gap widens by a further 3 points from entry. Checked on every observation; the exit
  fills at that observation's prices (gaps fill where they print).
- **Expiry.** No entry within 7 sessions before the near leg's expiry; any open position closes on the 5th session
  before it (the bullion tender / delivery period and the expiry convergence are not traded).
- **Size.** 1 lot each leg (same lot sizes both legs). Risk per lot = the stop distance in rupees on the far leg's price
  (2 sd x far price x units for Z; 3 points x (expiry gap / 365) x far price x units for C1) + round-trip costs.

### 3.2 Idea 2: sector-index pairing (labelled proxies)

The index itself cannot be tested (section 2). Proxies, on the roll-adjusted daily continuous futures, 2012 - 2026:
- **P-GS: GOLD vs SILVER** (the BULLDEX's two constituents; liquid; the "gold/silver ratio").
- **P-CZ: COPPER vs ZINC, P-CA: COPPER vs ALUMINIUM, P-ZA: ZINC vs ALUMINIUM** (METLDEX constituents; FAIL Boss's
  liquidity rule: research only, labelled).
- **P-CN: CRUDEOIL vs NATURALGAS** (ENRGDEX constituents; not an economic pair; labelled).

Roll adjustment: a roll row is a row whose open interest is > 3x the previous row's (the near contract has expired and
the series has switched; checked on coverage only). The adjusted daily return is close / previous close - 1 on normal
rows and close / open - 1 on roll rows (the switch gap is not earned; R83's rule). Each leg's adjusted price index =
the cumulative product of (1 + return). Ratio r = ln(index A / index B).

Rule (two windows, W = 60 and W = 120 sessions): z = (r - mean) / sd over the previous W rows (excluding today), at
today's close. Enter at the NEXT open: z >= +2 -> short A / long B; z <= -2 -> long A / short B. Exit at the next
open after z crosses 0, after 20 sessions, or after the hard stop: |z| >= 3.5 at a close. Equal rupee notional:
Rs 10 lakh a leg (about one GOLDM and one SILVERM lot). Costs: futures charges and two half-spreads per leg per round
trip; each roll inside a hold pays one more round trip on that leg. Risk per position = the loss when z moves from 2 to
3.5 (1.5 x the entry sd of r x Rs 10 lakh) + costs. 5 pairs x 2 windows = 10 cells.

### 3.3 Idea 3: option selling (paper research only; defined risk primary)

Books: CRUDEOIL, NATURALGAS, GOLDM, SILVERM (liquid books) and CRUDEOILM, NATGASMINI (small lots that can fit 2% at
Rs 1 lakh). GOLD and SILVER big-lot options are out of reach (MCX_GUIDE) and thin (77-79% zero-volume ATM minutes).

**Contracts.** Legs are chosen by offset at the entry minute and then held as fixed (strike, side) contracts of the
same monthly expiry (R53 expiry detection; no hold crosses an expiry). Offsets for puts are negative below spot.
- **IC (iron condor):** short C+1 and P-1, long C+3 and P-3.
- **IF (iron fly):** short C0 and P0, long C+2 and P-2.
- **NS (naked short strangle, labelled comparison, research only):** short C+1 and P-1.
- **CC (covered call):** long 1 lot of the underlying future (the book's `spot`, same units) + short C+1.

**Horizons.**
- **D1 (overnight):** enter at 17:15 IST; exit at 17:15 on the next session (time exit), or earlier at the stop / lock.
  No entry on an expiry day or on the session before it.
- **CY (cycle):** enter at 17:15 on the 10th session before the option expiry day; exit at 17:15 on the session
  before expiry, or earlier at the stop / lock. One trade per book per monthly cycle.

**IV regime filter (context only).** ATM IV at the entry minute = mean of the C0 and P0 `iv`. Its percentile among the
same book's entry-minute ATM IVs of the previous 40 sessions. Variants: ALL (no filter) and HIGH (percentile >= 60).
A HIGH trade needs 40 sessions of history (so HIGH starts about Oct 2025). LOW (< 60) is reported descriptively.

**Entry check.** Every leg must have printed (volume > 0, not R42-flagged) within the 5 minutes up to the entry minute;
else the entry waits up to 30 minutes, then the day / cycle is skipped (counted).

**Prices and fills.** A leg's price at a minute = its last printed close (forward-filled; a leg with no print for
60+ minutes is marked "stale" and counted). Sell fills at price - half-spread, buy fills at price + half-spread
(`common.opt_half_spread`: R85's ATM rate x (1 + 0.5 |offset at entry|), never below 1 tick). If a held strike leaves
the ATM-3..+3 window (no row for it that minute), it is valued conservatively: a short leg at intrinsic + the time value
of the nearest in-window same-side strike (an upper bound), a long leg at intrinsic only (a lower bound); such minutes
are counted. CC's future: the `spot` close +/- the R53 futures half-spread.

**Hard stop and profit lock (every structure, checked on each minute close after entry).** Credit C = sum of short
fills - sum of long fills (rupees, 1 lot). Open P&L = C - cost to close at that minute (fills as above).
- Hard stop: open P&L <= -1.0 x C (IC, IF, NS); CC: open P&L <= -1% of the future's entry notional.
- Profit lock: once open P&L >= 50% of C (CC: 0.5% of notional), a floor is set at 25% of C (CC: 0.25%) and trails at
  peak open P&L - 25% of C (CC: - 0.25%); exit when open P&L <= floor.
- Take profit: open P&L >= 80% of C (IC, IF, NS; buy back cheap); CC has none.
- The exit fills at that minute's prices (an overnight gap fills at the first printed minute after it).

**Margin and risk.** IC / IF margin = max loss = (wing width x units) - C (exchange SPAN may ask more; a lower bound).
NS margin = the book's futures margin (MCX_GUIDE 8 Oct table); CC margin = the futures margin. Risk per lot = the
stop amount (C for IC / IF / NS, capped at the IC / IF max loss; 1% of notional for CC) + costs. NS's true risk is
unbounded through a gap; its worst trade is reported next to its stop.

Cells: 3 structures (IC, IF, NS) x 6 books x 2 horizons x 2 IV variants = 72, plus CC x 4 liquid books (CRUDEOIL,
NATURALGAS, GOLDM, SILVERM) x 2 horizons x 2 IV variants = 16; 88 cells.

### 3.4 Idea 4: seasonal bullion (daily continuous GOLD and SILVER, 2012 - 2026, futures, long or short)

Dates are calendar facts (hard-coded in `seasonal.py`): Diwali (Lakshmi Puja) 2012-2025 and Akshaya Tritiya
2012-2026. Windows count TRADING ROWS of the daily file strictly before / after the date (the Muhurat session row, when
present, is never used).
- **DW-PRE (GOLD, SILVER):** long from the open 10 rows before Diwali to the close of the last row before Diwali.
- **DW-POST (GOLD, SILVER):** short from the open of the first row after Diwali to the close of the 10th row after.
- **AT-PRE (GOLD, SILVER):** long from the open 10 rows before Akshaya Tritiya to the close of the last row before it.
- **WED (GOLD, SILVER):** long in the wedding-season months (Nov, Dec, Jan, Feb, Apr, May): from each such month's
  first open to its last close, one trade per month.
- Hard stop on every trade: 2 x ATR14 (daily, to the previous row) from the entry price, resting: exit at the stop
  price, or at the open if the open gaps through it. Roll rows (3.2) inside a hold pay one more round trip; the switch
  gap is not earned.
- P&L per 1 lot of the MINI (GOLDM units 10 on the GOLD price, SILVERM units 5 on the SILVER price), costs and
  half-spreads on each fill.
8 cells.

**Twins:** for each real window, 1,000 random windows of the same length, same side and same stop rule, with start rows
drawn uniformly from the same calendar year's rows (the WED twin: 6 random calendar months of the same year). p = the
share of twin totals >= the rule's total. This removes each year's drift.

### 3.5 Idea 5: US overnight move -> MCX open (labelled proxy; re-test of R2)

Pairs: GOLDM <- XAUUSD, SILVERM <- XAGUSD, CRUDEOIL (traded as CRUDEOILM) <- LIGHTCMDUSD, NATURALGAS (traded as
NATGASMINI) <- GASCMDUSD. MCX price = the book's near-future minute close. Previous MCX close = the last minute of the
previous session. US overnight move u = ln(US BID close at 09:04 IST / US BID close at the previous MCX session's last
minute). MCX gap-to-entry g = ln(MCX close at 09:05 / previous MCX close). Residual e = u - g (what MCX has not yet
priced at 09:05; MCX = US x INR, so 1:1).
- **G1 catch-up:** if |e| >= 1 x sd(e) of the previous 40 sessions, trade sign(e).
- **G2 follow-US:** if |u| >= 0.5 x sd(u) of the previous 40 sessions, trade sign(u).
- Entry: the 09:06 minute close + half-spread; time exit at the 14:00 minute close; hard stop: 0.5 x ATR14 (daily
  continuous, to the previous day; CRUDEOIL / NATURALGAS dailies for the minis) from the entry, checked on minute
  closes, filled at that close (close-only series).
- 1 lot of the mini: GOLDM 10, SILVERM 5, CRUDEOILM 10, NATGASMINI 250 units.
- Descriptive (not gated): corr(u, overnight MCX gap 09:00) and corr(e, 09:05 -> 14:00) in design.
- Twins: the same days and times with a random side (1,000 draws): does the sign carry information?
2 rules x 4 pairs = 8 cells.

## 4. Execution and costs

### 4.1 Futures (ideas 1, 2, 4, 5 and CC's future)
Zerodha MCX futures charges on every leg's round trip (`common.fut_leg_charges`; McxCosts.kt). Half-spread per fill:
near month / continuous series: R53's rule (crude Rs 1; other commodities max(1 tick, 1 bp)/2; base metals 3 bp/2);
**far month leg (idea 1): 3x the near leg's half-spread** (MCX far months are thinner; sensitivity 1x and 5x reported,
not gated). A calendar spread pays 4 orders and 4 half-spreads a round trip. **Margin: both legs at full single-leg
margin; MCX's calendar-spread benefit is noted, not assumed.**

### 4.2 Options (idea 3)
Zerodha MCX option charges per leg round trip (`common.opt_leg_charges`: Rs 20 an order, CTT 0.05% sell side,
exchange 0.0418%, SEBI, stamp, GST). An iron condor pays 8 orders a round trip. Half-spreads as in 3.3.

## 5. Windows, walk-forward, the holdout

| idea | design | walk-forward folds (fixed rules, no fitting; net per fold) | holdout (locked, read ONCE) |
|---|---|---|---|
| 1 | first data - 31 Aug 2026 | calendar months with trades | 1 Sep - 19 Oct 2026 |
| 2 | 2012 - 2021 | each year 2013-2021 | 1 Jan 2022 - 16 Oct 2026 |
| 3 | 5 Aug 2025 - 31 Mar 2026 | Aug-Oct 2025, Nov-Dec 2025, Jan-Feb 2026, Mar 2026 | 1 Apr - 19 Oct 2026 |
| 4 | 2012 - 2021 | 2012-14, 2015-17, 2018-21 | 2022 - 16 Oct 2026 (Diwali 2022-25, AT 2022-26, WED months to Sep 2026) |
| 5 | 5 Aug 2025 - 31 Mar 2026 | as idea 3 | 1 Apr - 19 Oct 2026 |

Indicators warm up on data before a window; a trade belongs to the window of its entry. The holdout of every cell is
read ONCE (`common.open_holdout` writes `results/HOLDOUT_READ.json` and refuses a second read), after the design
results of all five ideas and the BH step are saved; it is reported for every cell, descriptive for cells that failed
design.

## 6. Measures (every number with its n)

Per cell, design and holdout: trades, win rate, net after costs (gross too), average, worst trade, worst day, worst
month (against -Rs 13k), months below -Rs 13k, max drawdown, net without the best 5 trades, net per walk-forward fold,
Boss's 2% check (share fitting Rs 2,000 at Rs 1 lakh; capital needed for the median / largest trade), median margin.
Idea 1 adds: observations, the far-leg spread sensitivity (1x / 5x). Idea 3 adds: credit, the stop / lock / take-profit /
time exit shares, stale and out-of-window leg minutes, return on margin, the tail (worst 5% of trades' mean). Idea 5
adds: the descriptive correlations with n days.

**p-values (one-sided, "better than chance").**
- Ideas 1 and 2: random-entry twins: the same number of entries at uniformly random valid observations / rows of the
  same pair and window, random side, the same exits and stop (1,000 sets); p = share of twin totals >= the rule's.
- Idea 3: ALL variants trade every eligible day / cycle, so random entry is the rule itself; p = moving-block
  bootstrap (blocks of 5 trades, 5,000 draws) that the mean net per trade is <= 0. HIGH variants: the larger of that
  p and a random-subset twin p (the same number of trades drawn from the ALL variant's trades, 1,000 sets): the
  filter must beat both zero and chance.
- Idea 4: random windows of the same year (3.4). Idea 5: random side on the same days (3.5).

**Benjamini-Hochberg: ONE family across every R94 design cell** (16 x 3 - 10 = 38 idea-1 cells (C1 on the 6 bullion
pairs only), 10 idea-2, 88 idea-3, 8 idea-4, 8 idea-5: 152 cells; a cell with no trade has p = 1).

## 7. Gates (a cell passes only if all hold; never relaxed)

Design: >= 40 trades (idea 3 CY and idea 4: >= 10, they cannot reach 40 by construction; flagged); net > 0; p <= 0.05;
BH q <= 0.10; net > 0 in at least 2/3 of the walk-forward folds that have trades; net without the best 5 trades > 0
(cells of >= 40 trades); worst month >= -Rs 13k (1 lot).
Holdout (read once): net > 0 and its own p <= 0.10, worst month >= -Rs 13k.
Then Boss's risk rule: the cell is TRADABLE at Rs 1 lakh only if >= 80% of its trades fit 2% risk. A cell that passes
both windows but not this check is reported as "works only with more capital (Rs X)".

Anything that passes design + holdout gets an APP_SPEC for a PAPER-only arm (logged, undoable, behind a switch that is
off by default, hard stop, profit lock); nothing goes live and nothing sells options live.

## 8. Tests (pytest, before any result)

- costs: a hand-checked futures and option round trip against McxCosts.kt's examples; half-spread floors.
- idea 1: spread observations only on common printed minutes; z excludes the current observation; fills at the next
  observation; stop / time / expiry exits on hand-made series.
- idea 2: roll detection and roll-adjusted index; next-open fills; |z| >= 3.5 stop.
- idea 3: leg tracking across offsets; out-of-window valuation bounds; stop / lock / take-profit on hand-made premiums;
  no hold across an expiry; credit and margin of an IC.
- idea 4: windows strictly before / after the date skip the Muhurat row; resting stop at the stop price or the gapped
  open; twins stay inside the year.
- idea 5: u, g and e on a hand-made pair; 09:06 entry, 14:00 exit, stop.
- **Look-ahead guard** (every idea): signals and entries computed on data truncated at random cuts, and on data whose
  rows after the cut are perturbed, equal the full-data ones at every time before the cut.
- metrics, BH, block bootstrap and the holdout lock (a second read raises).
