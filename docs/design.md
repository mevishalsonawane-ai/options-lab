# options_lab — design

Date: 2026-09-07. Status: approved, M1 in progress.

Supersedes nothing. `nifty_trading_bot/fo/` is retracted, not extended;
`strategy_lab/` is untouched and stays green.

## Why this exists

The user asked for free 1-minute BANKNIFTY data, option selection from it, many
strategies, an iterate-on-failures loop, and a 90%+ win rate, using OI and any
other parameter that matters.

Three findings reshaped that request, each verified rather than assumed.

**Free 1-minute option data with OI does exist.** Kaggle
`samardubey/niftybanknifty-options-data` (MIT, 4.1 GB, no account) gives 772
BANKNIFTY days 2023-01-02..2026-02-23 and 804 NIFTY days to 2026-04-13, 375
bars/day, every live expiry, with intraday OI reconciling to NSE bhavcopy at a
median ratio of 1.0000. Upstox `/v3/historical-candle` serves 1-minute bars with
genuinely per-minute OI and needs no Authorization header. Neither serves
expired contracts, so forward collection is the only way the archive grows.

**The 90% win-rate target is a geometry dial, not a measurement.** For a
driftless path with target +X and stop -Y, the hit rate is exactly
`p0 = Y/(X+Y)` and gross profit factor is identically 1.000 for every geometry.
With cost `c`, break-even is `p* = (Y+c)/(X+Y)`; at c = 1.44% of premium the
classic 5/45 "90% strategy" needs 92.9%. Measured over 5,390 random-entry
trades x 56 configurations on 246 real sessions, 5/45 achieved 69-85% and lost
1.6-3.4% per trade. Not one of the 56 configurations exceeded PF 1.15. The
objective is therefore **net expectancy per trade, with PF > 1.3 on a sealed
holdout**; win rate is reported beside its break-even and never optimised.

**This repo has produced this artifact twice already.**
`nifty_trading_bot/strategies/optimized_exits.py:11-16` retracts a 90%+ equity
result. `fo/backtest_v3.py:229` then decayed Black-Scholes `T` (in years) by
`bars_held/375.0` (minutes per *day*), so one 1-minute bar consumed 0.973
calendar days. Changing that one divisor and nothing else moved
`short_straddle_hwr` from 98.8% WR / +Rs 21.8M to 55.7% / -Rs 409k, and
`hwr_options` from 1.0% / -Rs 6.47M to 44.3% / +Rs 65k. One expression
manufactured both the miracle and the disaster.

## Organising rule

**Prefer the option that makes a wrong number impossible over the one that
makes it unlikely.** Every structural decision below traces to a failure that
actually happened here.

| Failure | Structural fix |
|---|---|
| Theta decayed in bar counts | The backtest reads **traded prices**. No code path exists from a rule to a model price. The pricer serves features only, pinned by an independent closed-form anchor. |
| Zero costs anywhere in `fo/` | No zero-cost model is constructible. `OptTrade.__post_init__` computes net and raises `CostsOmitted` unless `charges_rs > 0` and `spread_rs > 0`. The costs-zeroed ablation is a projection onto the stored `gross_pnl_rs`. |
| `step=15 > time_stop=8` so `bars_held == {15}` | Exits walk **every minute**, outside the decision stride. That state is unreachable. |
| A 98.8% result that survived deleting its own signal | Null baseline and ablation are columns on every result row, not an appendix. |
| Holdout leaking as prose, voiding 24 ideas | The seal lives in `store.read_day`, the lowest reader. Contract selection reads prior-day liquidity through a reader capped at `d-1`. |
| Recycled broker tokens | Contract identity is `(underlying, expiry, strike, right)`. `instrument_key` is an attribute, never a join key. |
| A 65x units step at the splice boundary | `units.to_contracts` asserts divisibility and refuses to guess. The store keeps one convention: contracts. |

## Objective

Net expectancy per trade in rupees, after all charges and a crossed spread.
Secondary: profit factor > 1.3, day-clustered t >= 2 after Bonferroni.
Win rate is descriptive only and is always printed beside `p0` and `p*`.

## Data

Store layout: `options_lab/data/bars/<UNDERLYING>/<YYYY-MM-DD>.parquet`, one
partition per underlying per session holding the whole chain **and** the index
(`right="IX"`), so a backtest reads one file and needs no join. Upsert keyed on
`(contract_id, ts)`, so a retried harvest converges rather than double-counting.
Every row carries `source` and `fetched_at`.

Feeds and their conventions:

| Feed | Granularity | OI | Units | Expired contracts |
|---|---|---|---|---|
| Upstox (forward) | 1-min | per-minute | **lot-multiplied** | no |
| Kaggle (history) | 1-min | ~3-min ffill | contracts | n/a, static |
| NSE bhavcopy | daily | EOD | contracts | yes, to 2000 |

Known and permanent limits, recorded because they bound every result:

- The 2026-02-24..2026-09-05 hole is unrecoverable. No window may span it.
- The 20 partitions collected 2026-08-10..09-04 hold **one expiry each**
  (NIFTY 2026-09-08, BANKNIFTY 2026-09-29) because contracts were resolved from
  today's master and back-filled. The front chains actually traded on those days
  are gone. These sessions are marked partial-chain and are ineligible for
  chain-aggregate features.
- OI cadence differs across the splice and cannot be fully repaired. Per-minute
  features are refused on 3-minute forward-filled data.
- The spread is an assumption, not data — and it is 70-89% of total cost.
- Effective sample is ~800 sessions, not ~90M bars. One underlying means one
  path per session; day-clustering is mandatory.

## Costs

`options_lab/costs.py` imports nothing from `nifty_trading_bot`.
`strategy_lab/costs.py:13` calls `set_cost_mode()` at import time, mutating a
module-level global in shared `engine.costs`; two regimes in one interpreter
race silently. A subprocess test asserts `nifty_trading_bot.engine.costs` is
never in `sys.modules`.

Itemised, each rate dated: brokerage Rs 20/order flat; STT 0.15% of premium on
the sell leg; STT 0.125% of **intrinsic** on a long leg at settlement; NSE
transaction 0.03553% of premium both sides; SEBI Rs 10/crore; stamp 0.003% buy
side; GST 18% on (brokerage + transaction + SEBI). Plus a crossed half-spread
under three named regimes — `roll`, `quoted`, `stress` — and tick rounding.

Because brokerage is flat, the explicit fraction is premium-dependent: 0.437%
on a Rs 785 BANKNIFTY option but **0.600% at a Rs 200 NIFTY option and 2.053%
at Rs 40**. The rate table is dated and per-underlying; there is no single
headline cost number.

## Measurement before rules

M2 produces an IC table and no backtest exists until it has. Each row carries
`feature, horizon, n_obs, n_sessions, ic, daily_ic_mean, daily_ic_lo,
daily_ic_hi, partial_ic, null_ic_hi, decile_spread_pts, breakeven_pts,
edge_over_cost, stamp`.

- **null** — the feature's own values with sign permuted within each session,
  20 draws. Preserves magnitude and time-of-day profile, destroys direction.
- **partial_ic** — after residualising the index's contemporaneous return,
  lagged 1-minute return, and the time-of-day vol multiplier. `ic.py` refuses
  to print a raw IC without its partial beside it. That omission is exactly how
  PCR and max pain looked real.
- **edge_over_cost** — decile spread in index points over break-even in index
  points. Signed flow's prior benchmark is **0.42x**.

Features built: signed option order flow (the only leading signal found,
IC +0.109 at 1 min after controls); DTE regime, liquidity state and
time-of-day vol as **gates, not signals**; ATM IV, CE/PE IV spread and skew
slope from Black-76 off a parity-recovered forward.

Refused, with their measured numbers in `features/BANNED.md`: PCR (partial IC
-0.086 +/- 0.057 at 15 min), max pain (changes once a day, correlates -0.933
with the index level; "distance to the day's open" beats it, +0.644 vs +0.584),
BANKNIFTY futures basis (futures are 0.7% of option volume), dealer GEX (not
observable).

## Backtest loop

A rule receives only the feature frame and the index frame and returns a
`LegSpec` — a *relative* description (right, side, atm_offset, expiry_rank,
lots). It cannot name a contract and never sees `day_df`. The engine resolves
the spec against the snapshot and fills from `day_df`. Look-ahead into the fill
contract is unrepresentable rather than discouraged.

```
for d in store.list_sessions(split, underlying):
    day   = store.read_day(underlying, d)          # SEAL ENFORCED HERE
    index = store.read_index(underlying, d)
    elig  = store.prior_day_liquidity(underlying, d)   # reader capped at d-1
    feats = features.build(index, day, elig)
    for i, ts in enumerate(calendar.session_grid(d, "option")):
        for pos in open_positions:
            exits.step(pos, day_bar(pos.contract_id, ts), ts)   # EVERY minute
        if i % DECISION_STEP: continue
        spec = rule(feats.iloc[:i+1], index.iloc[:i+1])
        if spec and gates.pass_all(feats.iloc[i]) and capital.can_open(...):
            queue_entry(spec, at=ts + 1min)
    exits.force_close_all(d)
```

Liquidity filter: a contract is tradeable on session `d` only if it traded in
>= 80% of minutes on `d-1`. Measured on 2026-09-04, that keeps 88 of 136 NIFTY
contracts; 37.7% of all option rows are stale prints where O==H==L==C, and
filling against those is how a harness manufactures edge.

## Testing

~120 tests of **invariants**, not one per idea. Ideas are disposable;
invariants are what stop a repeat of `fo/`.

- `test_theta_units.py` — the regression test for the bug that caused all of
  this. A sign-only test does not catch it (signs stayed opposite), nor does a
  price->IV->price round trip (the same T appears on both sides). It needs an
  independent closed-form anchor: an ATM Black-76 option is ~`0.3989*F*sigma*sqrt(T)`,
  so `d(price)/dt = -0.5*0.3989*F*sigma/sqrt(T)` per year. This does not reuse
  `pricing.theta()`, so a pricer sharing the bug cannot fool it. Rejects the
  `fo/` divisor at 1452x and a 375-minute-per-day slip at 3.84x.
- `test_long_short_mirror.py` — long and short of the same contract over the
  same bars sum to **exactly** zero gross and **strictly negative** net.
- `test_exits_fire.py` — more than one distinct `bars_held`, at least three
  distinct `exit_reason`.
- `test_no_engine_import.py` — subprocess assertion that the shared equity cost
  global is never imported.
- `test_units.py` — the 65x/30x splice trap.
- `test_contract_id_*` — the recycled-token trap.

## Milestones

| # | Deliverable | Measurable output |
|---|---|---|
| M1 | Harvester correct and durable, on a schedule | Tonight's partition holds >= 3 expiries including the front weekly. Units assertion passes on every contract. |
| M2 | **IC table against the cost bar** | Signed flow at h in {1,5,15,30}, each row with null band, partial IC and `edge_over_cost`. |
| M3 | Kaggle splice, reconciled | ~800 NIFTY / ~770 BANKNIFTY sessions frozen; cross-source OI ratio 1.00 +/- 0.01 and *not* approximately the lot size. |
| M4 | Clock, pricer, forward, IV surface | `test_theta_units` green; parity to 1e-9; IV round trip to 1e-6. |
| M5 | The engine | `test_long_short_mirror` and `test_exits_fire` green. |
| M6 | Report card, nulls, ablations, capital | Every row: expectancy, PF, WR beside `p0` and `p*`, day-clustered t with `t_iid` beside it, three nulls, five-gate ablation. **Minimum detectable effect computed and printed.** |
| M7 | First rule, pre-registered before running | A complete measured card. Prior evidence puts edge/cost at 0.42x, so the honest expectation is that it does not clear. **The milestone is the complete table, not a positive number.** |
| M8 | BANKNIFTY through the identical pipeline | No new code; `--underlying BANKNIFTY`. Any change required is a bug in the instrument-agnostic design. |
| M9 | Forward holdout, opened once | Only if a pre-registered candidate cleared PF > 1.3 with day-clustered t >= 2 after Bonferroni. Deferred to ~December 2026. |

## Stopping rule

M6 computes the minimum detectable effect at the realised per-session
dispersion and prints it. **If it exceeds the plausible edge, stop before M7**
rather than spend the sample. A clean negative from a harness whose invariants
hold is a real result; a positive number from one whose invariants do not is
what this project has produced twice.
