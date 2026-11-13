# R171 pre-registration: four liquidity / execution-cost gates on the arms

Written 13 Nov 2026, BEFORE any R171 gate, trade or P&L number was computed. Research only: nothing under `android/**`
changes. Owner: Boss (they/them).

Boss, 13 Nov: four upgrades to liquidity / execution-cost filtering, motivated by `options_lab/costs.py` (the spread is
70-89% of the cost and is an ASSUMPTION; brokerage Rs 20 an order is 2.05% of a Rs 40 option but 0.437% of a Rs 785 one;
regimes roll 0.30% / quoted 0.90% / stress 2.00%) and `options_lab/features/gates.py` (today `liquid()` judges d-1 only).

## 0. What data this can run on (the honest limit, stated first)

- The Dhan lake (`gdrive:IraAlgo-data-lake`) that R51 / R72 / R151 / R169 / R170 ran on is on Google Drive and is NOT
  reachable from this session (no rclone remote, no Drive credentials). Its final window (Apr - Oct 2026) can therefore
  not be read here at all.
- What IS reachable: the public `research-data` GitHub release (1-minute OHLCV + OI, ONE expiry a day (the nearest
  expiry after the day; on an expiry day the next one), 20 strikes a day, real traded volume):
  - NIFTY options + index: `nifty_prev.parquet` (2024-04-01 - 2025-04-03) + `nifty_year.parquet` (2025-04-04 - 2026-04-13);
  - BANKNIFTY options + index: `banknifty_prev_year_wide.parquet` (2024-02-13 - 2025-02-14) + `banknifty_year_wide.parquet`
    (2025-02-17 - 2026-02-23);
  - FINNIFTY and SENSEX index minutes (`finnifty_index.parquet`, `sensex_index.parquet`, 2024-02-12 - 2026-02-24), used only
    for R72's breadth count;
  - `options_lab/data/expiry_cache/` (NIFTY expiry-day chains, close + volume, 2023-01-05 - 2026-04-13), for the options_lab
    expiry put.
- **No window spans the 2026-02-24 .. 2026-09-05 hole (docs/design.md:83): every session on or after 2026-02-24 is dropped.**
  NIFTY's 2026-02-24 - 2026-04-13 sessions are therefore NOT used.
- No bid / ask history exists anywhere free. Every spread below is the `options_lab/costs.py` law (an assumption). The
  phone's recorded depth (sealed recordings in the private IraAlgo repo) would be needed to validate any spread-based
  gate; it is encrypted and is NOT opened here.

## 1. The strategies (signal on the index, the option is bought; CLAUDE.md default)

Re-built here on the release data because the lake engine cannot run. They follow the app's rules as written in
`android/engine/.../DonchianArm.kt`, `research/hunt/r72/APP_SPEC.md`, `research/liquidity_indices.py` and
`options_lab/strategy/expiry_put.py`. **Absolute P&L is NOT comparable with the arms' published records** (different
data feed, strike band and fill model); only the SAME-TRADES comparison "gate vs no gate vs random skip" is the result.

| id | rule | signal source |
|---|---|---|
| R51 | Donchian 20 on 09:15-anchored continuous 5-minute index bars (close beyond the previous 20 bars' high / low) AND Supertrend (10, 3) on the same bars agrees (R73 T7, as the arm runs it); 1-ITM strike from the signal bar's last close; fill at the next minute's open (2-minute slack); entries 09:20-15:10; one position a side, at most 2 entries a side a day; never on the index's own expiry day; exits XL: stop -15%, ladder +30% -> breakeven + charges, +60% -> +30%, +100% -> +60%, L25 (+25% -> +5%), out at the close of the 30th minute, square-off 15:15; 1 lot | the index |
| R72 | R51's Donchian break (no Supertrend), taken only when at least 4 of the 5 indices break the same way on the same bar. MIDCPNIFTY has no data here and "counts as not breaking" (APP_SPEC 1.3), so all 4 of NIFTY, BANKNIFTY, FINNIFTY, SENSEX must break. Traded on NIFTY and BANKNIFTY (the only option data); R51's strike, fill, caps and XL exits | the indices |
| LIQ | Liquidity 15+5: `research/liquidity_break.simulate` unchanged (swing lookback 20, pool 2 contacts 5 bars apart, 10 confirmation; source "both"; stop on a failed break; out at the next liquidity / new liquidity / 15:10), 15-minute and 5-minute books side by side, ATM option of the nearest expiry after the day, NIFTY and BANKNIFTY | the index |
| EXP | options_lab expiry put: on a NIFTY expiry day at 11:00 sell the put nearest F x (1 - 0.75%), hold to settlement (`expiry_put.run_session`) | parity forward (options) / the index at settlement |

Sizes: 1 lot at the dated lot (`research/hunt/r51/r51cfg.LOTS`; EXP: the chain's own lot as `expiry_put` does).

## 2. Costs (dated, with the spread shown under all three regimes)

- Statutory + brokerage: `research/obuy/costs.Costs("dated")` (STT, exchange, SEBI, stamp, GST at the rates in force on
  the trade date; Rs 20 an order).
- Spread: `options_lab.costs.spread_per_unit(premium, regime)`, half crossed on entry, half on exit (sell price floored at
  0). PRIMARY regime: `quoted`. Every result is also printed under `roll` and `stress`.
- EXP: its own `settle_trade` (sell_to_settle) cost, per regime.

## 3. The gates (each is SKIP-ONLY; computed at the decision minute from data before the fill minute)

Notation: `e` = the fill minute; the pre-entry window is minutes `[e - W, e)` clipped at 09:15 (an entry at 09:20 sees 5
minutes); "traded" = volume > 0; volumes are in units, lots = units / lot. A value that cannot be computed never skips.

| id | family | rule (take the trade only when ...) |
|---|---|---|
| G1a | 1 same-day window | W = 30: >= 80% of the window's minutes traded on the chosen contract, AND it traded in at least one of the last 5 minutes |
| G1b | 1 | W = 60, >= 80%, last-5 rule |
| G1c | 1 | W = 30, >= 95%, last-5 rule |
| G2a / G2b / G2c | 2 cost cap | `options_lab.costs.round_trip(premium, lot, 1 lot, "quoted").fraction_of_premium` <= 2.0% / 2.5% / 3.0% (premium = the last traded close before `e`; EXP: `sell_to_settle`) |
| G2d | 2 | premium >= Rs 10 (tick floor: one Rs 0.05 tick is >= 0.5% of the premium below it) |
| G2e / G2f | 2 (assumption check) | as G2b but the gate's spread is `roll` / `stress` |
| G3a / G3b | 3 depth | median lots traded a minute over the 30-minute window >= 10 / 50 x the order's lots (1) |
| G3c / G3d | 3 Roll spike | the window's relative Roll spread <= 2x / 3x the contract's own normal. Roll = 2 sqrt(-cov(dp_t, dp_t-1)) on the 1-minute closes of traded minutes (>= 10 changes, else not computable; cov >= 0 -> 0), divided by the window's mean close. Normal = the median relative Roll over the non-overlapping 30-minute windows (09:15-based, >= 20 traded minutes each) of the SAME contract in the previous 5 sessions it traded, floored at one tick / its mean close; >= 6 windows needed, else not computable |
| G4a | 4 DTE-coupled | `spread_in_theta_minutes(quoted spread, premium, DTE)` <= 60 for DTE >= 2; DTE 0-1: no cap |
| G4b | 4 | caps tighten with DTE: 2-3: 60, 4-7: 45, 8-21: 30, 22+: 20 minutes; DTE 0-1: no cap |
| G4c | 4 | G4b AND G1's window rule with a DTE-coupled threshold: W = 30, >= 60% on DTE 0-1, >= 80% otherwise |
| G4d | 4 (real-data spread) | G4b with the MEASURED pre-entry Roll spread (floored at one tick; quoted when not computable) |
| C | combination | per strategy: in each family, the variant with the highest DESIGN gain in net per trade (quoted) that is > 0 with design random-skip p <= 0.10; the families that have one are joined with AND. Chosen on DESIGN only; none -> no C test |

DTE = calendar days from the session to the traded contract's expiry (`gates.dte`). The data hold no 0-DTE contract for
R51 / R72 / LIQ (on an expiry day the next expiry is stored and R51 / R72 skip the day), so "0-1 DTE" is DTE 1 there; EXP
is always DTE 0 (theta undefined, `gates.atm_theta_share_per_day` refuses it), so G4a/b/d never skip EXP.

The app's skip convention holds: a gated break still holds its side and counts in the day's caps (and LIQ's one-at-a-time
book), so every gated set is a SUBSET of the no-gate trades and the random-skip control is exact.

Measurement (not a test): the share of trades whose order would be beyond typical depth, "stress" = order lots > 10% of
the window's median lots a minute, at 1 lot and at 3 lots.

## 4. Periods (walk-forward; the gates have NO fitted parameter except C's choice)

| period | dates | role |
|---|---|---|
| warm-up | each series' first 2 sessions | indicators only |
| DESIGN | start - 2025-03-31 | C is chosen here; single gates are only described |
| OUT-OF-SAMPLE (OOS) | 2025-04-01 - 2026-02-23 | read once by `r171study.py oos`, reported in 4 blocks (2025 Q2, Q3, Q4, 2026 Jan - 23 Feb) |

EXP: its last 30 sessions are `options_lab`'s sealed holdout (`expiry_put.split_sessions`) and are NOT read; its OOS is
2025-04-01 up to that holdout (expected < 40 sessions, so EXP cannot pass and is reported for information).
The arms' rules were themselves designed on 2024-2026 data, so this OOS is out-of-sample for the GATES, not for the arms.

## 5. Pass rule (all, on OOS, primary costs `quoted`)

1. >= 40 kept OOS trades;
2. net per trade above the no-gate arm's;
3. random-skip control: keep the same number of the arm's OOS trades at random. One-sided p for the gate's mean net
   per trade, from the finite-population normal approximation (exact for a sum of a random subset in the limit; 20,000
   Monte-Carlo draws printed beside it as a check, and a gate whose Monte-Carlo p > 0.01 fails);
4. Benjamini-Hochberg q <= 0.10 over m = 3,841 earlier tests (R170's count, which includes every earlier study) + R171's
   72 (18 per strategy x 4 strategies) = **3,913**; earlier tests taken as p = 1 (as R167 / R169 / R170);
5. the gain in net per trade is also > 0 under `roll` AND `stress` costs (the spread assumption must not decide it);
6. the gain is > 0 on DESIGN too;
7. worst day and max drawdown no worse than the no-gate arm's.

Reported for every gate and strategy (design and OOS): trades kept, net per trade, total net, win %, worst day, max DD,
random-skip p, q, under quoted (plus roll / stress net). Sample sizes with every number.

## 6. If a gate passes

Write the exact app spec (skip-only; on LIVE it starts in SHADOW behind an off-by-default switch, CLAUDE.md), and add the
gate code + tests to `options_lab/features/gates.py` without changing any default existing tests rely on. The gate
functions are added to `options_lab` in any case (they are needed to run this study); `android/**` does not change.

---

## Amendment A (13 Nov 2026, after the gate results, BEFORE any Amendment A number): measure the spread, don't assume it

Boss, 13 Nov: *"think with new perspective, and enhance it"*.

The gate study's main finding was not about liquidity: near-the-money NIFTY / BANKNIFTY options always trade, and
depth never binds. What decides whether R51 / R72 / Liquidity make money is the ASSUMED spread (out of sample they are
positive at `roll` and negative at `quoted`). So Amendment A stops guessing and measures it from the real 1-minute
prices. **It is a MEASUREMENT, not a new gate: nothing here can pass or fail, it adds no BH test, and it changes no rule.**
It re-reads the out-of-sample trades for costing only (a second look, disclosed).

### A1. Three spread estimators from real prices (each on the traded minutes of the 30-minute pre-entry window)

| id | estimator | reads | known bias |
|---|---|---|---|
| ROLL | Roll (1984): 2 sqrt(-cov(dp_t, dp_t-1)) (already in R171) | closes | low when prices trend (cov >= 0 -> 0) |
| AR | Abdi-Ranaldo (2017): s^2 = 4 mean((c_t - eta_t)(c_t - eta_t+1)), eta = mid of log high / low, c = log close | close, high, low | robust to trend; noisy on few bars |
| CS | Corwin-Schultz (2012) two-bar high-low estimator, negative pairs set to 0, averaged | high, low | high when the option moves a lot inside a minute (volatility read as spread) |

Each is relative (a share of the price), needs >= 10 traded minutes (else not computed) and is turned into points at
the trade's premium. MEASURED = the median of the estimators that exist, floored at one tick (Rs 0.05). CS is kept
separately as the conservative (wide) bound.

### A2. What is reported (design and out-of-sample, per strategy and index)

1. The distribution of each estimator in points and as a share of premium, beside the costs.py law at the same premium
   (roll / quoted / stress), and the share of trades whose MEASURED spread is below roll, between roll and quoted, between
   quoted and stress, above stress.
2. A refit of the spread law on DESIGN trades only: MEASURED points = a + b x premium (least squares, and a median
   (quantile 0.5) fit), compared with costs.py's 0.162 + 0.00292 x premium (in-session) and its regime scalings, and
   checked on the out-of-sample trades (median absolute error of each law).
3. Every strategy re-priced with the MEASURED spread (and with CS as the wide bound): net per trade, total, win %, worst
   day, max DD, design and out-of-sample, beside the three costs.py regimes.
4. The R171 gates' gains re-priced the same way (sensitivity only; the gate verdicts stay as read).

### A3. Honest limits written before the numbers

- These are statistical estimates from trade prices, not quotes. They measure the EFFECTIVE spread paid by the
  average 1-minute trade, which can be narrower than the quoted top of book and says nothing about a market order larger
  than the depth at the touch (the phone's sealed depth recordings remain the only true check; still not opened).
- A 1-minute bar mixes many trades; high-low estimators read part of the minute's real move as spread (CS biased wide),
  Roll reads trends as zero spread (biased narrow). Showing all three brackets the truth; none of them is it.
- Data: the same release data as R171 (nothing on or after 2026-02-24). The expiry put is left out (its cache has
  closes only and no high / low).
