# HUNT h11: Liquidity 15+5 on NIFTY / SENSEX, the indices with the most capacity

Code: `research/hunt/h11/`
- `PREREG.md`: the grid, model, choice and PASS rules, written before any h11 run.
- `sig.py`: h4's port, generalised. The raw signals match h4's NIFTY/SENSEX signals exactly: 1,431 against 1,431.
- `build.py`: one data pass. It runs all 24 variants x 2 indices under h10's `cap.py` capacity model. It also rebuilds
  h10's BANKNIFTY plan, which reproduces h10's 4,928.43/day exactly.
- `pre.py`: the pre-registered choice.
- `pre2.py`: a post-hoc 10-lot re-ranking, labelled as post-hoc.
- `hold.py`: the single holdout run.

Caches and logs are in `scratchpad/hunt/h11/` (about 30 MB). Everything here is option BUYING.

## Verdict (plain language)

**NO. No NIFTY or SENSEX variant of Liquidity 15+5 has an edge after costs, so they add nothing to the BANKNIFTY plan.**
- Capacity is not the problem. NIFTY's weekly 1-ITM option can take about 2,800 lots, and SENSEX about 600.
- The problem is that the entries hardly beat random ones. In the gross P&L (bar prints, no costs), NIFTY makes about
  Rs 30 per lot per trade.
- Spread, STT, fees and impact cost about Rs 25-60 per lot per trade, depending on size, so they take 90-150% of that gross.
- None of the 24 pre-registered variants passed, on either index.
- The holdout confirms it: the chosen variants lost money net.
- **Recommendation: keep NIFTY and SENSEX at 0 lots. Rs 5,000/day still rests on BANKNIFTY (h10).**

## What was tested (PREREG.md; 24 variants per index = 48 daily series)

The arm's rules are unchanged except for these settings:

| setting | values |
|---|---|
| level books | 15m+5m or 30m+5m |
| index stop | 1x (NIFTY 15, SENSEX 50 points) or 2x |
| room to the next level | at least 1 or at least 2 stops |
| strike | ATM or 1-ITM |
| series | nearest weekly (16 variants) or nearest monthly (8 variants, room 1) |

The monthly series stands in for the next weekly, which is not in Dhan's data.

How it was run:
- **Fills:** h10's 'real' fills, a 15% participation cap, square-root impact κ = 0.02, and the app's charges. GROSS
  means bar prints with no costs.
- **Choice:** the best pre-holdout net/day at 1 lot.
- **PASS** needed all three of:
  - the anchored walk-forward result out of sample above 0;
  - random-entry BH q ≤ 0.05;
  - at least 2 positive years.

## Pre-holdout results (data before 2025-10-01; 1 lot)

- **SPA p = 0.81 and White RC p = 0.82 over the 48 series.** Per index, SPA p is 1.00 for NIFTY and 0.67 for SENSEX.
  The best of the 48 has t = 0.28.
- No random-entry p reached BH significance. The best q is 0.024, for SENSEX monthly 15+5 2x ITM1, but that variant
  loses money net (-34k).
- **NIFTY.** All 24 variants are net negative at 1 lot, between -15k and -33k.
  - Gross is +7k to +48k.
  - The choice was 30+5, 1x stop, room 2, 1-ITM, weekly: -15.1/day net, +17.0/day gross.
  - By year: 2022 -22.8k, 2023 +3.3k, 2024 +15.0k, 2025 (to Sep) -3.4k.
  - Walk-forward out of sample: +1.2k (2023 -5.6k, 2024 +10.3k, 2025 -3.4k).
  - Random-entry p = 0.32, BH q = 0.59. **FAIL.**
- **SENSEX.** The choice was 15+5, 1x stop, room 2, 1-ITM, weekly: +11.4/day net, +34.3/day gross.
  - By year: 2023 -7.9k, 2024 -0.7k, 2025 +20.0k.
  - Walk-forward out of sample: -7.0k.
  - Random-entry p = 0.09, BH q = 0.44. **FAIL.**
- **Monthly contracts** (NIFTY's monthly stands in for the next weekly). Their entries beat random entries in gross
  (NIFTY monthly 15+5, 1x stop: p 0.002-0.006). Their gross is about 2x the weekly gross. But the monthly options' wider
  spread makes them the worst net: NIFTY -28k to -33k, SENSEX -34k to -69k.

**Post-hoc check: is the loss just brokerage at 1 lot?** The 1-lot metric penalises the fixed Rs 20/order brokerage,
which is exactly the cost that size removes. So I re-ranked the same 48 series at 10 lots per trade (`pre2.py`).
- Costs are still 84-150% of gross. The best NIFTY variant makes +25/day on 249/day gross; the best SENSEX makes
  +27/day on 167/day.
- SPA p = 0.88.
- Random entries tested on gross p ≥ 0.10 for both chosen variants.
- **Still FAIL.**

## Holdout (2025-10-01 to 2026-10-06, run once)

| chosen variant | trades | net/day (1 lot) | gross/day (1 lot) | random-entry p | confirmed? |
|---|---|---|---|---|---|
| NIFTY 30+5, 1x, room 2, ITM1, weekly | 83 | **-3.9** | +28.8 | 0.31 | no |
| SENSEX 15+5, 1x, room 2, ITM1, weekly | 105 | **-18.5** | +19.0 | 0.09 | no |

The post-hoc 10-lot choices gave:
- NIFTY 30+5, 1x, room 1, ITM1: +38/day net on 305/day gross. Random-entry p on gross = 0.27.
- SENSEX: -44/day net.

Post-hoc across the whole grid, the best holdout cell was NIFTY 15+5, 1x, room 2, ITM1 at +21.5/day per lot. That is
noise-sized, and it was -24/day before the holdout.

## Capacity and size

Capacity is 15% of the 25th-percentile 5-minute volume at the exit, measured over Dec 2024 to Sep 2025:

| index | 25th-pct exit volume | capacity | holdout 25th-pct exit volume |
|---|---|---|---|
| NIFTY weekly 1-ITM | 18,600 lots | **N = 2,788 lots** | 18,900 |
| SENSEX weekly 1-ITM | 4,170 lots | **N = 625 lots** | 5,600 |

So liquidity is about 10x BANKNIFTY monthly's (1,914) and does not bind.

The pre-registered size rule was applied only for reporting, because neither index passed. It gave NIFTY 500 lots and
SENSEX 8 lots. At those sizes in the holdout:

| | NIFTY 500 lots | SENSEX 8 lots |
|---|---|---|
| gross per day | +Rs 14,724 | +152 |
| net per day at κ 0.01 | +Rs 1,967 | -15 |
| net per day at κ 0.02 | **-Rs 2,722** | -35 |
| net per day at κ 0.04 | -Rs 12,100 | -75 |
| worst day | -Rs 13.8 lakh | |
| max drawdown | -Rs 47 lakh | |
| P(losing month) | 54% | |
| largest single position (premium) | Rs 1.07 crore | |

At 500 lots the NIFTY trade is gross-positive and net-negative: the costs scale with size, but the edge does not grow
to cover them.

**Combined with the BANKNIFTY plan** (h10 flat BN 26 / FIN 3 / MIDCP 11, κ 0.02; holdout figures):

| | daily correlation | combined net/day | max drawdown | P(losing month) |
|---|---|---|---|---|
| BANKNIFTY plan alone | | 4,830 (both limits: 6,075) | -13.2 L | 45% |
| + NIFTY at 500 lots | 0.12 | 2,108 | -54 L | 52% |
| + SENSEX at 8 lots | 0.38 | 4,795 | -14.6 L | 45% |

The correlation is low before the holdout too: 0.10-0.22 for NIFTY and 0.02-0.06 for SENSEX. So the
diversification would be real if there were an edge, but there is none to add.

## Answer for Boss

| | |
|---|---|
| Best simple rule from this angle | none; NIFTY/SENSEX Liquidity is not tradeable net (the best cell is noise) |
| Holdout, chosen variants, 1 lot | NIFTY net -Rs 3.9/day (gross +28.8); SENSEX net -Rs 18.5/day (gross +19.0) |
| Rs/day NIFTY/SENSEX adds within capacity | **Rs 0**: the recommended allocation is 0 lots. The pre-registered 500-lot NIFTY size would have cost Rs 2.7k/day net in the holdout (κ 0.02), even though gross was +Rs 14.7k/day |
| Rs 5,000/day realistically? | **Not from NIFTY or SENSEX.** It stays a BANKNIFTY-capacity question (h10: about Rs 5-6k/day, fragile to κ) |

## Honesty notes

- The grid and rules were fixed in `PREREG.md` before any run, and the holdout ran once.
  - `hold.py` crashed once on a JSON key type before printing anything, and was then re-run.
- The 10-lot re-ranking is post-hoc, and it changes nothing.
- 48 series were counted for SPA/RC. The 10-lot re-ranking reuses the same 48 series.
- The baseline cell (15+5, 1x, room 1, 1-ITM, weekly) matches h4: the same 503 SENSEX trades. NIFTY's 1-lot net is
  -28.2k against h4's -26.9k; the difference is the extra 1-lot impact and the window boundary.
- Random-entry pools have 5 alternatives per signal, a random side, and the same exits and contract rule.
