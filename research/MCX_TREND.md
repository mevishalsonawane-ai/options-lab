# MCX trend following and carry with Rs 1 lakh (Zerodha, fixed lots)

M2, 8 Oct 2026. Code: `research/hunt/m2/`. Logs and results: `scratchpad/hunt/m2/`. Rules were written down
before any P&L: `research/hunt/m2/PREREG.md`. Six later changes are logged there (D1-D6).

## Verdict

**No. With Rs 1 lakh and fixed lots, trend following on MCX is not a plan you can rely on.**

- **The edge is real but small.** A 7-commodity trend book had a Sharpe of about 0.3-0.7 in 2013-2024, when
  capital was not a limit. Over 2001-2024 on world futures priced in rupees, it was 0.1-0.3. That matches the
  published research: the edge has been weaker since 2009.
- **It does not pass the multiple-testing checks.** We tested 198 futures variants and 110 option variants.
  White Reality Check p = 0.13. Hansen SPA p = 0.79. Zero variants pass Benjamini-Hochberg at 10%.
- **Rs 1 lakh is far too small.** One lot of each mini contract needs about **Rs 1.7-1.9 lakh of margin
  today**. A book you can hold through the 2013-2024 drawdowns needs **Rs 8-10 lakh**. With Rs 1 lakh, 56 of
  198 futures variants fell to the Rs 25k ruin stop in development.
- **Starting date decides the outcome.** We started fresh with Rs 1 lakh every six months from 2013 to 2022
  and ran each for 2 years. The main ensemble book (long and short) hit ruin from **45%** of those starts.
- **The holdout (2025 to Oct 2026) made big money: Rs 1 lakh grew to Rs 4.2-6.0 lakh.** But it was one huge
  trend period: silver roughly tripled and crude spiked. Even then the drawdowns were 52-58%. The worst month
  lost Rs 1.3 lakh and the worst day Rs 1.06 lakh. Do not expect that again.
- **Buying options is the worst way to do it.** 48 of 55 option variants hit ruin in development (prices are
  modelled). The walk-forward option picks lost Rs 3.3 lakh over 2014-2024. In the holdout the walk-forward
  pick lost 87%.
- **Short futures means selling risk with unlimited loss** (one bad day: -Rs 1.06 lakh in the holdout).
  **Boss must choose this explicitly.** Long-only did no better in development.

**If Boss still wants trend:** use the 12-month rule (TSM252) on 1 lot each of the mini contracts, and only
with about Rs 8-10 lakh. In development that made about Rs 50k a year (Sharpe 0.53), with drawdowns of Rs 2-3
lakh. With Rs 1 lakh, the honest answer is to wait.

## What Rs 1 lakh can hold (Zerodha NRML margin, 8 Oct 2026)

| contract | lot | margin per lot | fits Rs 1 lakh? | used here as |
|---|---|---|---|---|
| CRUDEOILM | 10 bbl | Rs 27-31k | yes, 1 lot | CRUDE leg |
| NATGASMINI | 250 mmBtu | Rs 10-13k | yes | NATGAS leg |
| GOLDPETAL | 1 g | Rs 1.5k | yes, 5 lots = Rs 7.5k | GOLD leg |
| GOLDTEN / GOLDGUINEA | 10 g / 8 g | Rs 12-14k | yes | (alternative) |
| SILVERMIC | 1 kg | Rs 29-55k | 1 lot, uses half the money | SILVER leg |
| ZINCMINI / LEADMINI / ALUMINI | 1,000 kg | Rs 32-39k / 14-22k / 31-40k | 1 lot each, barely | base-metal legs |
| CRUDEOIL, GOLD, GOLDM, SILVERM, SILVER, COPPER, ZINC futures | full | Rs 1.6-16.6 lakh | **no** | excluded |
| Options to buy: CRUDEOIL, NATGASMINI, GOLDM, SILVERM | 1 lot | premium about Rs 17-35k | 1 lot of 1-2 of them | OPT legs |

- All 7 mini legs together: about Rs 1.7-1.9 lakh margin. **The full book does not fit in Rs 1 lakh.**
- The 4 energy and bullion minis (PORT4) fit, at about Rs 0.8-1.1 lakh. But PORT4 had almost no edge.
- Volatility scaling cannot work at this size. One lot is already the smallest position, so the only choice
  is whether to hold a leg or skip it.

## Development 2013-2024 (Rs 1 lakh start, fixed lots, all costs, daily margin check)

| book (signal, mode) | CAGR | Rs/month | max DD | worst month | green months | Sharpe | trades/yr | beats random | end value |
|---|---|---|---|---|---|---|---|---|---|
| **PORT, ENS, long+short (primary)** | ruin 2016 | -Rs 528 | -Rs 1.10 L (-82%) | -Rs 47k | 15% | -0.23 | 10 | 90th pct | Rs 24k |
| **PORT, ENS, long-only (primary)** | ruin | -Rs 524 | -Rs 1.19 L | -Rs 44k | 8% | -0.25 | 4 | 58th | Rs 24k |
| **Options, ENS, 4 contracts (primary)** | ruin 2014 | -Rs 521 | -Rs 2.89 L | -Rs 1.09 L | 5% | -0.13 | 8 | 48th | Rs 25k |
| PORT, 12-month TSM, long+short | +16.9% | +Rs 3,847 | -Rs 1.81 L (-73%) | -Rs 92k | 55% | 0.54 | 11 | 98th | Rs 6.5 L |
| PORT4, ENS, long+short (post-hoc) | +1.4% | +Rs 125 | -Rs 1.29 L | -Rs 53k | 49% | 0.03 | 15 | 66th | Rs 1.18 L |
| ZINC mini, 1-month TSM, long-only (best of 198) | +14.2% | +Rs 2,711 | -Rs 80k (-36%) | -Rs 37k | 38% | 0.85 | 2 | 98th | Rs 4.9 L |
| CRUDEOILM, ENS, long+short | +4.5% | +Rs 478 | -Rs 38k | -Rs 13k | 51% | 0.27 | 3 | 83rd | Rs 1.69 L |

PORT = 1 lot each of CRUDEOILM, NATGASMINI, SILVERMIC, ZINCMINI, LEADMINI, ALUMINI, plus 5 GOLDPETAL.
ENS = the sign of the average of 9 classic trend signals. "Ruin" = the account fell below Rs 25k and stopped.
"Beats random" = the percentile of the Sharpe against 100 random position paths with the same holding periods.

**Per year, Rs (12-month TSM book, the only portfolio that survived):**
2013 -12k, 2014 +66k, 2015 +38k, 2016 -60k, 2017 +44k, 2018 -88k, 2019 -20k, 2020 +152k, 2021 +142k,
2022 +67k, 2023 +94k, 2024 +132k. That is 8 green years out of 12. Most of the profit came after 2020, when
base metals and bullion trended hard.

**By signal (median over single commodities, unconstrained Sharpe):** long-only 0.15-0.34, long+short
0.03-0.29. 12-month TSM and 100-day Donchian were the most stable. 1-month TSM and the 20-day breakout were the
noisiest. **By commodity:** zinc, aluminium and lead were positive. Crude was about zero. Gold was small.
**Natural gas and silver were negative.**

**Carry** (approximate: estimated from the roll-day jump, because Dhan gives only the near-month series):
- Without the capital limit it was the best portfolio signal (Sharpe 0.7-0.77).
- With Rs 1 lakh it ran into ruin.
- Average annual carry: natgas -31%, crude -18%, silver -9%, gold -2%, zinc +6%, lead +6%.
- Natgas really does lose money when you hold it long and roll (contango), as M1 also found.

## Statistics (all variants counted)

| check | result |
|---|---|
| variants tested | 198 futures (11 signals x 2 modes x 9 universes) + 55 options (near-month) + 55 options (next-month, first run) |
| White Reality Check (stationary bootstrap, 500 draws) | p = 0.13 |
| Hansen SPA | p = 0.79 |
| Benjamini-Hochberg, q < 0.10 | 0 variants (best q = 0.13) |
| variants beating 95% of random paths | futures 20/198, options 1/55 (about what chance gives) |
| walk-forward (best development Sharpe each year, 2014-2024) | futures, all variants: +Rs 3.1 L total; portfolios only: +Rs 1.5 L; options: **-Rs 3.3 L** |
| fresh Rs 1 lakh every 6 months 2013-2022, 2-year runs | ruin: PORT ENS L/S 45%, PORT ENS long-only 10%, PORT 12-month TSM 5%, options 80%. Lost money: 65% / 70% / 25% / 90% |
| capital to keep the 7-leg book's max drawdown under 30% | Rs 7.8-10 lakh |

## Long proxy, 2001-2024 (Yahoo CL, Brent, NG, gold, silver, copper front futures x USDINR)

- Volatility-scaled long+short portfolio Sharpe: TSM21 0.21, TSM252 0.29, ENS 0.21, Donchian 0.05-0.10,
  moving averages 0.07-0.12.
- Against random paths: 62nd-94th percentile.
- Long-only Sharpe was about 0.4, but random long-only also scored about 0.4. **That is just commodities
  rising in rupees, not trend skill.**
- Holdout 2025-26 on the proxies: Sharpe 0.6-1.5 (a strong trend period worldwide, led by gold and silver).

## Locked holdout 1 Jan 2025 to 7 Oct 2026 (run once, fresh Rs 1 lakh)

| book | CAGR | Rs/month | max DD | worst month | worst day | green months | Sharpe | trades/yr | end value |
|---|---|---|---|---|---|---|---|---|---|
| **PORT ENS long+short (primary)** | +126% | +Rs 14.6k | -Rs 1.99 L (-58%) | -Rs 1.35 L | -Rs 1.06 L | 64% | 0.94 | 24 | Rs 4.22 L |
| **PORT ENS long-only (primary)** | +175% | +Rs 22.5k | -Rs 2.05 L (-52%) | -Rs 1.30 L | -Rs 1.07 L | 73% | 1.38 | 14 | Rs 5.96 L |
| **Options ENS (primary; real Dhan IV from Aug 2025, modelled before)** | +175% | +Rs 22.5k | -Rs 11.9 L (-74%) | -Rs 2.25 L | -Rs 3.13 L | 45% | 0.48 | 59 | Rs 5.94 L |
| PORT4 ENS long+short (post-hoc) | -41% | -Rs 2.7k | -Rs 88k (-73%) | -Rs 36k | -Rs 17k | 45% | -0.72 | 14 | Rs 40k |
| PORT4 ENS long-only (post-hoc) | +39% | +Rs 3.5k | -Rs 2.55 L (-61%) | -Rs 72k | -Rs 1.08 L | 55% | 0.29 | 9 | Rs 1.78 L |
| Walk-forward pick: ZINC 1-month TSM long-only | +80% | +Rs 8.2k | -Rs 35k (-31%) | -Rs 17k | -Rs 23k | 64% | 1.93 | 2 | Rs 2.81 L |
| Walk-forward pick, portfolios: PORT 12-month TSM L/S | +92% | +Rs 9.8k | -Rs 2.0 L (-52%) | -Rs 87k | -Rs 1.16 L | 77% | 0.76 | 12 | Rs 3.15 L |
| Walk-forward pick, options: CRUDE 1-month TSM | -68% | -Rs 3.9k | -Rs 1.46 L (-92%) | -Rs 37k | -Rs 24k | 14% | -0.63 | 7 | Rs 13k |

- Across all variants on the holdout (we did not choose anything from these), the median Sharpe was 0.49 and
  70% ended above Rs 1 lakh.
- The holdout rewarded trend. The development years mostly did not. That is the nature of trend following:
  long flat or losing stretches, then a few big years.
- The worst-day losses above Rs 1 lakh on a Rs 1 lakh start come from profits that had built up earlier,
  together with the large sizes of 2026. Silver at Rs 2.5 lakh/kg makes 1 SILVERMIC lot worth Rs 2.5 lakh.

## How it was tested

- **Data:**
  - Dhan `/charts/historical`, MCX_COMM, expiryCode 0: continuous near-month daily OHLC, volume and OI, from
    2 Jan 2012 to 7 Oct 2026, for 21 contracts (CRUDEOILM from 2015; NATGASMINI from 2023; GOLDTEN and
    SILVER100 only recent).
  - expiryCode 1 and 2 return the same series, so there is no next-contract history. Carry is estimated from
    roll-day jumps.
  - Dhan rolling options (60-minute, near and next month, ATM ±1, from Aug 2025) for GOLDM, SILVERM,
    NATGASMINI, NATURALGAS and CRUDEOIL. These were used to calibrate IV.
  - Yahoo daily from 2000.
  - Zerodha margins from api.kite.trade.
- **Price adjustments:**
  - Mini contracts use the big contract's price series (same price unit) with today's lot sizes.
  - Roll days were found from OI jumps (base metals: the first trading day of each month).
  - The overnight gap on a roll day is not earned, and every roll pays a round trip.
  - 20 Apr 2020: crude was set to the -2,884 settlement, so the negative-price day is included.
- **Execution:** signal at the close, trade at the next open. Costs:
  - Zerodha futures charges (Rs 20 or 0.03%, CTT 0.01% on sells, MCX 0.0021%, stamp, GST).
  - Spread of Rs 3-100 per lot, plus 0.02% slippage per side.
  - Options: Rs 20, CTT 0.05%, MCX 0.0418%, and a 0.5% (crude) or 1.5% (others) round-trip spread.
- **Margin model:** margin% = max(floor, 0.6 x 60-day volatility), checked against Zerodha's numbers. It is
  still somewhat below Zerodha now for crude and silver, so the margin results are a little optimistic.
  - A leg opens only if the total margin then stays at or below equity.
  - If margin is above equity, the largest leg is closed.
  - Below Rs 25k, the account stops.
- **Options:**
  - Black-76 with IV = 20-day realised vol x the calibrated ratio (crude 1.065, gold 0.96, silver 1.0,
    natgas 1.05). Natgas's real ratio is 1.17, so natgas options are flattered.
  - We buy the 1-ITM option of the nearest expiry that has 8 or more days left, and roll or exit at 6 days or
    fewer. This follows M1's finding that next-month MCX option books are dead.
  - In development the option prices are MODELLED.
  - Sensitivity: IV x1.25 or a 2x spread made every option book worse. IV x0.85 helped only crude.
  - The crude option results depend on April 2020 (puts under negative prices).

## Limits

- Today's lot sizes are applied to all years. The real 2013 sizes and liquidity of the mini contracts were
  different.
- Delivery contracts must be rolled about 5 days before expiry. The tests roll on the switch day.
- Carry is approximate.
- Option history before Aug 2025 is modelled.
- The holdout is only 21 months and one market regime.
- Data truncation: development scripts cut the data at 31 Dec 2024 before computing signals. The holdout ran
  once (`scratchpad/hunt/m2/holdout.lock`, `proxy_holdout.lock`).

## Files

- Code: `research/hunt/m2/`
  - `fetch_dhan.py`, `fetch_opts.py`, `fetch_yahoo.sh`, `parse_yahoo.py`
  - `prep.py`, `engine.py`, `opt.py`
  - `dev.py`, `dev_opt.py`, `proxy.py`, `startdates.py`, `holdout.py`
  - `PREREG.md`
- Results: `scratchpad/hunt/m2/`
  - `dev_results_fut.csv`, `dev_results_opt.csv`, `dev_results_opt_nextmonth.csv`
  - `dev_wf_*.csv`, `dev_spa_fut.json`
  - `proxy_dev.csv`, `proxy_holdout.csv`
  - `startdates.csv`
  - `holdout_main.csv`, `holdout_all.csv`, `holdout_monthly.csv`
  - `*.log`
- Data: `scratchpad/hunt/m2/data/`, 13 MB in total.
