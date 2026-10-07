# obuy: an option-buying backtest lab (Indian index options, real Dhan minute data)

`research/obuy/` backtests strategies that BUY NIFTY / BANKNIFTY / FINNIFTY / SENSEX options intraday. It uses real
1-minute option prices with OI, the app's fills and charges, and overfitting controls built for testing thousands of
strategy x parameter variants. Validation results: `research/OBUY_VALIDATION.md`.

```
python3 -I research/obuy/run.py list                                   # strategies and their grid sizes
python3 -I research/obuy/run.py run liquidity15_5 solo_midday --name x # run, analyse, rank -> <cache>/runs/x/REPORT.md
python3 -I research/obuy/run.py run orb_grid --pool 5                  # a grid (384 variants), 5 random entries per signal
python3 -I research/obuy/run.py validate                               # parity + unit tests + calibration
```

Always run with `python -I`, because the market data is untrusted. `run.py` puts `research/` on the path itself.
Paths default to this session's scratchpad. Set `OBUY_SCRATCH` (the folder with `dhan/repo/dhan-data`, `jx/`, `liqx/`)
or set `OBUY_DATA` / `OBUY_CACHE` to use other folders. Outputs go to `<OBUY_CACHE>/runs/<name>/`: `REPORT.md`,
`families.csv` (one row per strategy, with the walk-forward results and gates), `variants.csv` (one row per variant)
and `trades.csv.gz`.

## Layout

| file | what |
|---|---|
| `config.py` | paths, sessions (09:15-15:29, 375 columns a day), strike steps, the exchange lot-size schedule |
| `data.py` | `Options` (lazy per-year loader of Dhan's rolling ATM±10 files, re-keyed by strike; `chain(day, series)` gives dense [strike, minute] arrays of o/h/l/c/volume/OI/IV), `Index` (per-day index minutes, lot, expiry flag), `Vix`, `market()` |
| `costs.py` | `Fills` (`app` ±5/10 bps exact to the paisa, `liq` adds a spread that widens with thin volume, `flat`), `Costs` (`app` = SandboxCosts today, `dated` = rates in force on the date, `flat`) |
| `engine.py` | `StrikeRule`, `Execution`, `Exits`, `prepare_many()` (signals to path packs, in ONE data pass, with random-entry pools), `run_exits()` (vectorised), `positions()` |
| `stats.py` | headline stats, per year, bootstrap CIs, Monte Carlo (1 lot, 1% / 2% risk) |
| `overfit.py` | walk-forward, random-entry baseline p, BH / Holm, White RC / Hansen SPA, Deflated Sharpe, PBO (CSCV) |
| `lab.py` | `Lab(strategies).run()`: everything end to end plus the gated ranking and report |
| `strategies/*.py` | `liquidity`, `solo`, `orb`, `straddle`, `hero`, `bigbar`, `random_entry` (control); `registry.py` lists them |
| `validate.py` | the validation suite |
| `strategies/lv05_multiday.py`, `lv05_run.py` | multi-day positions (catalog LV-05): a day-by-day walker on real option bars with rolls, expiry exits, Black-Scholes fill-in outside the ATM±10 window and a futures mirror; same-day parity with `engine.py` is checked in every run. Results: `research/OBUY_LV05_MULTIDAY.md` |

## How a backtest works

1. **Signals.** A strategy's signal function returns a DataFrame with one row per entry candidate:
   - Required: `und`, `day`, `sig_min` (the minute whose 1-minute bar CLOSE triggers the trade) and `side`
     (+1 buys a call, -1 a put, 0 a straddle / strangle).
   - Optional:
     - `book`: one position at a time per book.
     - `gate`: the minute from which the book must be flat. Default `sig_min + 1`.
     - `ref_spot`: the price used to pick the strike. Default: the index close at `sig_min`.
     - `strike`: an explicit strike.
     - Structural exits: `idx_stop` (an absolute index level), `idx_target`, and `exit_at` (a minute).
     - `lot`, to override the lot size, and `tag`.

   Signals may use anything known up to `sig_min`. For example, `mk.index(u).mat()` gives index minutes,
   `mk.options(u).chain(day)` gives prices, OI and IV for ATM±10, and `mk.vix` gives India VIX. Signals are cached by a
   hash of the function's source and parameters.
2. **Contract and fill.** `StrikeRule` picks the contract:
   - `money`: +n ITM, -n OTM, 0 ATM, on the index strike step from `ref_spot`.
   - `series`: `near` (weekly when Dhan has it, else monthly), `week` or `month`.
   - `prem_band`: the nearest OTM strike priced within a range, for hero tickets.

   The fill is the option's OPEN at `sig_min + 1`, or the first bar within `max_delay` minutes, plus slippage.
   `Execution` sets the entry filters and sizing:
   - `min_premium`, `min_vol_lots` (skip strikes that are illiquid that day).
   - `expiry`: `skip` / `allow` / `only`.
   - `lot_mode`: `data` = the day's lot read from the OI moves (the lot as of each date), `official` = the exchange
     schedule, `today` = the latest lot.
   - `budget` sizing (a Rs 5k ticket).
3. **Exits** (`Exits`, all optional and combinable, swept as a list):
   - Premium stop `stop_pct` / `stop_pts`, and premium target `tgt_pct` / `tgt_pts`.
   - Profit-lock ladder: `ladder` rungs on R = `ladder_ref_pts` or `ladder_ref_pct`. Rungs never sit below
     breakeven after charges.
   - Trailing stop: `trail_pct` after `trail_arm`, or `pine_trail` (ProfitLock.Trail).
   - Time stop: `time_stop` minutes, unless the premium is up `time_gain`.
   - Index stop / target: `idx_stop_pts` / `idx_tgt_pts` from the entry reference, `idx_tgt_r` x the signal's risk,
     or the signal's own levels.
   - `exit_at`, and the square-off `sq_off`.
   - `intrabar=False` evaluates premium exits on minute closes. Straddles always do.

   Order within a minute:
   1. Resting orders on the option's bar: the stop first (the higher of the premium stop and any lock or trail, set
      from the peak BEFORE this bar), then the target. A stop fills at the trigger, or at the open if the bar gapped
      through it (-10 bps). A target fills at the target or a better open.
   2. Decisions on the minute's close, filled at the NEXT minute's open (MARKET): index stop, time stop, index target,
      `exit_at`, close-mode premium exits, square-off.

   This is the order of the app's arms and of earlier replays, and it reproduces them to the paisa (see validation).
4. **Positions.** `Strategy.pos`: `one_at_a_time` per book (flat again the minute after the exit), `max_per_day`,
   `day_loss`.
5. **Speed.** `prepare_many` reads each year file once for all jobs and keeps every contract's day path ONCE in a
   float32 store that all candidates and random alternatives share. Exits then run as numpy matrix operations over
   (trades x 375 minutes), at about 0.1 s per variant per 2,000 trades. A data pass costs about 1 minute per
   underlying, and later variants are almost free.

## How to add a strategy

Create `strategies/mystrat.py`:

```python
import numpy as np, pandas as pd
from ..engine import Execution, Exits, StrikeRule, LADDER
from .base import Strategy
from .common import day_arrays, daily_atr, vix_prev, candles, pcr

def signals(mk, k=0.5, unds=("NIFTY", "BANKNIFTY")):
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            o, h, l, c = day_arrays(ix, d)          # dense 375-minute arrays, NaN = no bar
            ...                                     # use only data up to the signal minute
            out.append(dict(und=und, day=d, sig_min=col + 555, side=+1, book=f"my_{und}", idx_stop=level))
    return pd.DataFrame(out)

STRATEGIES = [Strategy(
    name="mystrat", family="breakout", signal_fn=signals,
    sig_grid=[dict(k=k) for k in (0.3, 0.5, 0.8)],                 # signal parameters
    rules=[StrikeRule(0), StrikeRule(1)],                          # ATM, 1 ITM
    exits=[Exits(stop_pct=s, tgt_pct=t) for s in (0.2, 0.3) for t in (0.4, 0.8)] + [Exits(stop_pct=0.3, pine_trail=True)],
    exe=Execution(expiry="skip"),                                  # app fills/charges, lot as of each date
    pos=dict(one_at_a_time=True, max_per_day=2),
    window=(9 * 60 + 20, 14 * 60 + 30),                            # random-entry baseline window
    doc="one line")]
```

Then add `"mystrat"` to `strategies/registry.MODULES` and run `python3 -I research/obuy/run.py run mystrat`.
The variant count is |sig_grid| x |rules| x |exits|. Every variant counts as a trial in the corrections, so declare
the whole grid you tried and never prune it after looking at the results.

## Statistics and overfitting control

- **Per variant:** trades, net, net per year, PF, win rate, average R, max drawdown (daily equity), worst day / month,
  Sharpe / Sortino (daily P&L on Rs 5 lakh, x sqrt(248)), years positive, charges. Bootstrap CIs (`stats.bootstrap_ci`).
- **Monte Carlo** (`stats.monte_carlo`) resamples the trades into 1-year sequences. It reports P(profit after a year),
  P(drawdown >= 20% / 50%) and the 5th / median / 95th percentile P&L on Rs 5 lakh, at 1 lot and at 1% / 2% risk per
  trade. Risk sizing uses whole lots, sized on the money at risk to the stop (or the full premium without one).
- **Walk-forward** (anchored, yearly). For each test year, the variant with the best net over all earlier years is
  traded. The first 2 calendar years only train, and a variant needs at least 20 training trades to be picked.
- **Same-exit random baseline** ("coin flip with the same exits"). For each candidate the data pass also builds
  `--pool` alternatives on the same day, underlying and book, with:
  - a uniform random minute in the strategy's entry window, and a coin-flip side;
  - the same strike rule and execution;
  - the same index stop / target DISTANCES and holding offsets.

  The same exit set runs on them. p = (1 + #{random mean per trade >= real}) / (B + 1), over B draws of one
  alternative per real trade. It is calibrated: under the null, 4.5% of p < 0.05.
- **Multiple testing:**
  - Benjamini-Hochberg q and Holm across all variants. Variants that lost money in full sample are not baseline-tested
    and get p = 1, which only makes the corrections stricter.
  - Holm / BH across families for the walk-forward test.
  - White's Reality Check and Hansen's SPA_c (stationary bootstrap, mean block 5 days) over the (days x variants)
    daily-P&L matrix against not trading.
  - The Deflated Sharpe Ratio of each family's best variant, with N = all variants in the run.
  - PBO by CSCV: 16 blocks, up to 4,000 of the 12,870 splits.
- **Promotion gates** (`lab.Gates`; all four must pass):
  1. Walk-forward net > 0 after costs.
  2. Walk-forward trades beat the random baseline at Holm-adjusted p < 0.05.
  3. Positive in more than half the walk-forward years.
  4. Walk-forward max drawdown <= 20% of Rs 5 lakh at 1 lot, and Monte Carlo P(50% drawdown) <= 5%.

  DSR, PBO and SPA are shown next to the gates.
- **Big grids:** `--pool 5` halves the baseline cost. Pools run only for variants that made money. To subsample a
  grid, list a random subset of its variants (fixed seed) in `sig_grid` / `exits` and say so in the report. The
  corrections still use every variant that was run.

## Data notes and limits

- **Options:** Dhan's rolling "nearest expiry, ATM±10" minute series, weekly and monthly, re-keyed by strike. It is
  identical to the `maxloss/prep` export that the earlier studies used (checked series by series).
  - Coverage: NIFTY from Aug 2020, BANKNIFTY / FINNIFTY from Aug 2021 (weekly series to Nov 2024, monthly after),
    SENSEX from May 2023.
  - There is no second (next-week) weekly expiry in the data. `series="month"` gives the nearest monthly.
  - Strikes beyond ATM±10 are missing. A contract has gaps while it sits outside that window.
- **Index minutes:** the per-day files earlier studies used (`jx/ix_<U>.pkl`: real external or Dhan index OHLC checked
  against Dhan's spot, else the spot print). SENSEX is built from Dhan's index minutes, with the lot and expiry flag
  read from its options.
- **Expiry days:** detected from the options (min CE+PE at the close < 0.75 strike step). On an expiry day `near` is
  TODAY's expiry. `Execution.expiry` chooses `skip` (the arms' rule), `allow`, or `only` (hero tickets).
- **No bid/ask** in the data. Fills are modelled: `app` matches the app's paper account, and `liq` adds a 1-4 tick
  half-spread from the contract's last 5 minutes of volume. Expect real fills on Rs 1-5 options to be worse.
- **Futures** in the data are only the contracts live at fetch time (Oct 2026), so they are not usable historically.
  India VIX has daily bars from 2008 and minute bars from Oct 2021.
- **Not modelled:** partial exits (sell half at a target), order rejection, freeze limits, margin. Multi-leg positions
  are single-entry / single-exit only.
