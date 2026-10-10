# HUNT h5: spot the big trend day early, then pyramid option buys (buying only)

Code: `research/hunt/h5/` (`build.py` builds the cache, `sim.py` runs the simulations, `pick.py` runs the train-only selection, `holdout.py` runs the one-time holdout). Caches are in `scratchpad/hunt/h5/`.

## Verdict (plain language)

**NO. Rs 5,000 a day is not realistic with this method.**

- **The money is concentrated, as you suspected.** In training, the 5 best days made 84% of the best rule's net profit.
- **Big trend days can be partly recognised by 10:00–11:30.** About 18% of index-days are trend days, roughly 3.3 a month per index. A strong opening drive (≥0.5 ATR from the open), a wide range so far, a rising straddle, or OI building on one side lifts the chance of a trend day from 18% to 30–46%.
- **But by then the move has mostly happened.** Once a day qualifies, the average move from the signal to 15:15 in the trend's direction is about 0 (−0.04 to +0.15 ATR). Recognising a trend day is not the same as profiting from what is left of it.
- **Pyramiding raises gross profit but not net profit.**
  - Median over all variants: about Rs 5/day gross with 1 lot, about Rs 29/day gross with up to 4 lots.
  - Net it is negative either way (about −Rs 15/day with 1 lot, −Rs 4/day with up to 4 lots).
- **The best rule found on training data failed the locked holdout**, both gross and net.

## The best simple rule (picked on Aug 2020 – Sep 2025 only)

1. At 11:30, note the direction of NIFTY or BANKNIFTY from the day's open.
2. The day qualifies only if, within ATM±5, the OI change since the open favours that direction: (put OI added − call OI added) / total change ≥ +0.3 for an up move, and the mirror for a down move.
3. Buy 1 lot of the 1-ITM nearest-expiry option in that direction (CE for up, PE for down).
4. Add 1 lot each time the index extends another 0.15×ATR14 beyond the entry level. Maximum 4 lots. Each add is the 1-ITM strike at that moment.
5. One trailing stop covers the whole position: exit everything when the index falls back 0.6×ATR14 from its best close.
6. Exit everything if there has been no add within 60 minutes.
7. Otherwise exit everything at 15:15.

Sizing: today's lots (NIFTY 65, BANKNIFTY 30). Net uses the app's charges (`Costs('app')`, STT 0.15%), the app's ±5 bps fills, and a realistic spread of 0.5 pt per side on NIFTY and 1 pt per side on BANKNIFTY. Gross is mid prices with no costs.

| | trades | qualifying days/month | gross Rs/day (avg over all days) | net Rs/day | net Rs/trade | win % | worst day (net) | worst month (net) | max drawdown (net) | losing months |
|---|---|---|---|---|---|---|---|---|---|---|
| Train 2020-08 to 2025-09, pyramid (≤4 lots) | 795 | 10.3 | +546 | +386 | +613 | 31% | −46.4k | −44.5k | −1.55 L | 27/62 |
| Train, same rule with 1 lot | 795 | 10.3 | +196 | +106 | +168 | 37% | −16.0k | −18.8k | −0.69 L | 29/62 |
| **HOLDOUT 2025-10 to 2026-10, pyramid** | 215 | 12.7 | **−105** | **−334** | −385 | 27% | −25.7k | −49.8k | −1.37 L | 9/13 |
| HOLDOUT, same rule with 1 lot | 215 | 12.7 | −11 | −150 | −173 | 34% | −10.9k | −20.6k | −0.55 L | 9/13 |
| HOLDOUT, same exits on ALL days (no filter) | 487 | 21 | −762 | −1,285 | −655 | 31% | −38.5k | −122.5k | −3.73 L | 9/13 |

Training P&L by year (net, Rs): 2020: +70k, 2021: +51k, 2022: +220k, 2023: −79k, 2024: +314k, 2025 (Jan–Sep): −89k.

## Honesty checks

**Variants tried: 63,072**, all declared up front:

- 4 decision times;
- 146 conditions: no filter, 17 single filters, and 128 pairs drawn from the features drive, rngr, gap-unfilled, VIX rise, straddle expansion, OI side, breadth, NR4/NR7 and range location;
- add step X: 3 values;
- trail Y: 3 values;
- time stop: on or off;
- maximum lots: 1, 3 or 4;
- expiry days: skip or allow.

The day-level P&L of the whole grid was computed from about 393k simulations on real minute option prices.

**Multiple testing.**
- Benjamini-Hochberg: 0 of 63,072 variants have q < 0.05.
- White's Reality Check, net: p = 0.78.
- Hansen's SPA, net: p = 0.98.
- On gross: RC p = 0.26, SPA p = 0.73.
- Nothing beats not trading once the search is accounted for.

**Walk-forward**, anchored by year, picking the best net variant from earlier years.

| test year | net (Rs) | gross (Rs) |
|---|---|---|
| 2022 | +296k | +455k |
| 2023 | −195k | −49k |
| 2024 | +53k | +119k |
| 2025 (Jan–Sep) | −310k | −227k |
| **total** | **−156k** | **+299k** |

The pick swings between "all days" and other filters from year to year. Gross is positive, but costs on 1.7 lots × 2 sides per trade eat it.

**Random days, same rule and exits, holdout.**
- Same number of random days, trading in the drive direction: −Rs 582/day (95th percentile −Rs 83). The rule's p = 0.22.
- Coin-flip direction: −Rs 2/day (95th percentile +Rs 783). The rule's p = 0.75.
- The rule is not distinguishable from random days.
- In training, the same test gave p = 0.02. That is selection bias: this was the best of 63k variants.

**Breadth caveat.** The F&O-stock breadth feature only has data from Oct 2024 (about 245 training days), so it was barely testable. It did not make the top of the ranking.

**Probability of a losing month**, by bootstrap: 50% on the training data and 64% on the holdout.

## Size for Rs 5,000/day

Even at its in-sample (training) best of +Rs 386/day net, the rule needs about **13 sets**: up to 52 lots on a full-pyramid day.

- **Premium deployed:** about 13 × 4 × ~Rs 21k (current premiums, average ~Rs 434 per unit) ≈ **Rs 11 lakh**. A bought option's margin is the premium, so this is the margin too.
- **Training drawdown at that size:** about **−Rs 20 lakh**.
- **Holdout at that size:** about **−Rs 4,300/day net**, with a drawdown of about **−Rs 17.8 lakh**.
- **Capital needed:** about Rs 30 lakh to survive.
- **Conclusion:** not realistic.
