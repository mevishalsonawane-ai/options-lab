# R42 pre-registration: do SILVERM M4's profits survive once bad option prices are removed?

Written 14 Oct 2026, about 16:25 UTC, BEFORE any P&L was computed by R42. Seen so far: file formats, row counts,
sha256 of the lake files, and one row check (the lake's SILVERM OTM2 call at 8 Jul 2026 09:30 is strike 234000, close
6949.5, the same as M4's first holdout trade). Nothing else.

## Why

An outside sweep found that 4-7% of minutes in the lake's GOLD/GOLDM/SILVER/SILVERM option files show a >50%
one-minute premium jump (SILVER +427,200% in 5 minutes). The SILVERM morning-call PAPER arm (M4) rests on a holdout of
51 trades, +Rs 454/trade (+Rs 23.2k), random p 0.050, walk-forward -Rs 3.5k, top 3 trades = 145% of net. If a few
bad prints made those top trades, the arm has no edge.

## Signal source

M4 enters on the clock (a fixed 15-minute time grid, 09:15-13:45 IST) with no indicator at all; the instrument is the
SILVERM option itself (OTM2-3 call). Option premium is used only for the trade's entry/exit price and P&L. No signal is
read from the option's chart. (Strike choice uses the SILVERM futures price, `spot`.)

## Data

- Google Drive `gdrive:IraAlgo-data-lake/options_rolling_1m/` (Dhan v2 `rollingoption`, near monthly expiry, 1-min):
  `opt_<SYM>_MONTH_<2025|2026>.parquet` for SILVERM, SILVER, GOLDM, GOLD, CRUDEOIL, NATURALGAS; `opt_NIFTY_WEEK_*`,
  `opt_BANKNIFTY_MONTH_*` for comparison. SILVERM sha256: 2025 `a165a135…`, 2026 `4abfc5f3…` (full list in results).
  Lake data ends 12 Oct 2026 (13 Oct is not in the lake).
- M4's original inputs (M3's `opt_SYM.parquet`/`spot_SYM.parquet`, crude from the STRAD-CRUDE cache) are not on this
  machine. They are rebuilt from the lake in the same format: offsets ATM-3..+3, call/put, `close, iv, oi, volume,
  strike`; `spot` = the per-minute median of the option rows' `spot` (M4 used M3's separate futures file). Crude's
  next-month file (used only by M4's Q3 signals) does not exist in the lake and is not needed here.
- **Reproduction check first:** M4's own frozen code (build.py, analyze.py's Q4 functions; only file paths changed)
  is run on the rebuilt raw inputs with NO new cleaning. R42 reports how close it gets to M4's numbers (holdout 51
  trades, +Rs 454/trade, p 0.050; walk-forward -Rs 3.5k). That rebuilt run is the "raw" baseline every cleaned
  number is compared with. Any difference from M4 is reported, not tuned away.

## Bad-tick definitions (fixed now)

A **contract** is (day, side, strike) inside a rolling series: the same strike under different ATM offsets during
the day is the same contract. A **priced minute** is a row of that contract.

- **S0, naive sweep** (for comparison only, roughly what the outside sweep did): per rolling series (book, offset,
  side, day), consecutive rows; flag if |close / previous close - 1| > 50%. This also fires when the series rolls to
  a new strike, which is not a bad price.
- **F1, close spike (main filter):** per contract, neighbours = the up-to-5 nearest priced minutes before and the
  up-to-5 nearest after, within 15 clock minutes, same day. At least 1 neighbour on each side is needed (otherwise
  not judged, kept). ref = median of the neighbours' closes. Flag if close > 1.5 x ref or close < ref / 1.5.
  So a single-minute (or up to about 5-minute) spike that reverts is flagged, and a genuine jump that stays is not
  (half its neighbours are at the new level).
- **F2, band (strict):** F1, or the minute's high > 1.5 x ref or low < ref / 1.5 (same ref).
- Each filter is computed on two populations: **all rows** and **traded rows** (volume > 0). M4 already treats a
  volume-0 minute as "no price", so for M4 the relevant cleaning is F1 on traded rows (main) and F2 on traded rows
  (strict variant).
- A flagged minute becomes "no price". After that M4's own machinery is unchanged: forward-fill up to 5 minutes,
  else Black-76 at the last good implied vol; an entry needs a clean traded price at the entry minute with IV within
  0.5x-2x of the 14 options' median IV and a lot premium >= Rs 500.

## Census (descriptive, reported in full)

Per book (GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, NATURALGAS, NIFTY weekly, BANKNIFTY monthly), offsets ATM-3..+3,
window 5 Aug 2025 - 12 Oct 2026: rows, traded share, share flagged by S0, F1 (all rows), F1 (traded), F2 (traded),
by time of day: MCX 09:00-10:44, 10:45-13:59, 14:00-16:59, 17:00-23:30; NSE 09:15-10:44, 10:45-15:30.
For M4: how many of the 51 original holdout trades (and of the frozen cell's design trades) had a flagged entry or
exit price under F1/F2; the top-3 holdout trades with raw vs cleaned entry and exit prices.

## M4 re-run (the frozen rule, unchanged)

Buy 1 lot (5) SILVERM call at ATM+2 or ATM+3 (OTM2-3), 11-20 business days to expiry, morning grid (09:15-13:45 every
15 min), exit after 240 minutes (E1), one position at a time; M4's expiry inference, entry filters and exit pricing.
Windows: design 5 Aug 2025 - 7 Jul 2026, holdout 8 Jul - 8 Oct 2026, **extra** 9-12 Oct 2026 (labelled extra, not
part of the decision). Data variants: raw (rebuilt), F1-clean (main), F2-clean (strict).
Costs: M4's (Zerodha MCX fees + half M4's estimated spread on each side, x2 before 17:00) and the same with 2x spread.
Reported: trades, hit, gross/trade, net/trade, total, max drawdown, by month, top-3 share.

**Random p:**
- **Twins (decision p):** for each rule trade, a twin on the same day at a random minute 09:15-13:59 at which the same
  cell (OTM2-3 call passing the same entry filters, either strike) has a valid entry, same 240-minute exit, same costs.
  2,000 draws; p = share of draws whose total net >= the rule's total net. (The rule's own minute is in the pool, so
  every day has at least one twin.)
- M4's original baseline (same strike offset and side, any time of day and DTE > 0, same month counts) is also
  reported, for comparison with M4's 0.050.

**Walk-forward:** M4's procedure re-run unchanged on F1-cleaned data (all 4 M4 books CRUDEOIL, NATURALGAS, GOLDM,
SILVERM; every design cell x {E1, E2}; each month pick the best net per trade on earlier months with >= 100 trades and
>= 20 days; trade that month). Compared with M4's -Rs 3.5k. Also the frozen cell's own design net by month.

## Decision rule (fixed now)

On F1-cleaned data with M4's costs: if the holdout net per trade <= 0, OR the twin random p >= 0.05, the verdict is
**"switch the M4 paper arm off"** (a risk-lowering paper change; Boss is told first; R42 does not change the app).
Otherwise the verdict is "keep it on paper, unchanged" (it still failed M4's own walk-forward gate, so it never goes
live from this). F2, 2x spread, M4-style p and the extra days are reported but do not change the verdict.

## Count

One rule is tested (the frozen M4 rule), nothing is selected, so there is no multiple-testing search here (m = 1 for
the decision; the variants are robustness checks). The filter thresholds above are not tuned after seeing results.

## Amendment 1 (14 Oct 2026, ~16:40 UTC; after the synthetic unit tests, before any real data was filtered)

- F1/F2 use two one-sided medians instead of one pooled median: ref_prev = median close of the up-to-5 priced
  neighbours before, ref_next = median of the up-to-5 after (same 15-minute window, same contract and day). Flag if
  close > 1.5 x max(ref_prev, ref_next) or close < min(ref_prev, ref_next) / 1.5; F2 adds high > 1.5 x max(...) or
  low < min(...) / 1.5. Reason: with the pooled median of an even count, the last minute before a genuine >= 2.25x
  re-pricing (100 -> 300 that stays) was flagged, and a minute next to one far-away spike in sparse trading was
  flagged too. This matches the brief's wording ("jumps from both its neighbours"). Thresholds unchanged.
