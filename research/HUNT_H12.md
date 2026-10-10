# HUNT h12: can a filter or regime sizing raise Liquidity 15+5's edge per trade?

Code: `research/hunt/h12/`
- `PREREG.md`: 28 rules, the metric, the choice rule and the plan test, written before any P&L by feature was computed.
- `feat.py`: the features (all known at the signal minute's close) and the 28 rules.
- `run.py`, run in stages:
  - `pre`: chooses on data before 1 Oct 2025.
  - `plan`: h10's capacity plan at the same risk, before the holdout.
  - `hold`: one holdout test.

Logs and CSVs are in `scratchpad/hunt/h12/` and `scratchpad/hunt/h12/cache/h12/`. The 67 MB packs were deleted
afterwards; `h7/build.py` rebuilds them in about 1 minute.

Everything here is option BUYING. Entries, the 1-ITM strike and the exits are the app's Liquidity 15+5 arm,
unchanged, using the h4/h7 port. It reproduces h7's 1-lot real net to the rupee: BN 144,334 / FIN 91,911 /
MIDCP 102,311. In the plan, BASE reproduces h10 exactly: holdout net Rs 4,830/day, or 6,075 with limits.

## Verdict (plain language)

**NO. None of the 28 pre-registered filters or sizing rules raises Liquidity's net Rs per trade robustly enough to
use. The h10 plan stays as it is, at about Rs 4.8k/day net in the holdout (Rs 6.1k with the loss limits), Rs 13.9k
gross, at the same risk.**

1. **Before the holdout, no rule beat "take every trade" at the same risk once multiple testing was counted.**
   - SPA p = 0.88 and White RC p = 0.87 over the 28 risk-matched excess series.
   - BH q = 1.0 for every rule.
   - The pre-registered choice was therefore **BASE (no filter)**.
2. **The best-looking rule failed exactly as the gates predicted.**
   - Before the holdout the top rule by Sharpe was F04: trade only counter-daily-trend breaks. Its Sharpe was 1.40
     against 1.03 for BASE, and it made +Rs 160/lot-trade.
   - It won only 1 of 3 walk-forward years.
   - In the holdout it **reversed**: Rs -61/lot-trade against BASE. In the h10 plan at the same risk it made
     **-Rs 275/day**, against +Rs 4,830 for BASE.
3. **Some features do pick better trades.** These are directional per-trade lifts that held in both periods and beat
   the random-entry control both times. They are **not** chosen. They are hypotheses for a forward test:

   | rule | uplift, Rs per lot-trade (pre / holdout) | edge over random entries with the same rule (pre / holdout) |
   |---|---|---|
   | puts only (F01) | +80 / +217 | +47 / +109 |
   | room ≥ 6 stops (F21) | +137 / +160 | +93 / +203 |
   | room ≥ 3 stops (F20) | +56 / +154 | +35 / +175 |
   | large gap ≥ 0.3 ATR (F17) | +89 / +289 | +87 / +397 |
   | VIX up on the day (F09) | +70 / +217 | +59 / +251 |

   - These raise Rs per trade, but they also halve the number of trades. **Rs/day at the same risk did not improve
     before the holdout**: risk-matched excess was -15 to +26 Rs/day at 1 lot, which is noise.
   - Doubling size on these conditions (S23 puts, S27 room) instead of skipping trades gave +1 to +2 Rs/day before
     the holdout. That is nothing.
   - Calls lost money in the holdout (Rs -2 per lot-trade net). Puts made Rs +428. This matches h8 (the edge sits on
     the short side), but it was also true only weakly before the holdout (Rs 52 against Rs 204), and puts-only was
     not better at the same risk then.
4. **Time of day flipped.** Before 11:00 was best before the holdout (+103/lot-trade, worst after 11:00). In the
   holdout it was the reverse (+131 against +311). This is a reminder that single-period filter "finds" don't carry
   over.
5. **"60-minute trend agrees" is structural.** Every Liquidity break already agrees with the 60-minute move, because
   a break of a 20-bar swing level implies it. So F05 and S26 are identical to BASE. They are reported and counted.

## The rule (unchanged, h10)

Trade Liquidity 15+5 as the app does, with no extra filter:
- BANKNIFTY 15m+5m: 26 lots;
- MIDCPNIFTY 15m+5m: 11 lots (4 is safer);
- FINNIFTY 30m+5m: 3 lots (post-hoc: 1 lot or drop it).

Use 1-ITM monthly options, with h10's 15%-of-volume order cap and the Rs 75k/day and Rs 2.5L/month loss limits.

## Results at the h10 plan size, capacity/impact model, cap 15%

| rule | κ | window | net/day | gross/day | worst day | worst month | max DD | P(lose month) |
|---|---|---|---|---|---|---|---|---|
| BASE 26/3/11 | 0.02 | Dec 2024 to Sep 2025 | 4,928 | 11,676 | -1.39 L | -2.88 L | -5.3 L | 39% |
| BASE | 0.02 | **HOLDOUT** | **4,830** | **13,933** | -2.12 L | -6.63 L | -13.2 L | 46% |
| BASE + limits | 0.02 | **HOLDOUT** | **6,075** | 14,005 | -1.66 L | -2.77 L | -8.8 L | 45% |
| BASE | 0.01 / 0.04 | HOLDOUT | 7,810 / **-1,131** | 13,933 | | | | 39% / 59% |
| F04 (top pre rule), BN 32/unit, same sd | 0.02 | Dec 2024 to Sep 2025 | 8,021 | 11,901 | -1.27 L | -0.83 L | -4.2 L | 32% |
| F04 | 0.02 | **HOLDOUT** | **-275** | 4,779 | -2.04 L | -3.30 L | -7.4 L | 58% |

The F04 row shows how the pre-holdout capacity numbers flattered a filter: half the trades means half the impact, so
it looked +60% better. Then the filter itself failed.

Holdout at 1 lot per unit (all three indices), BASE:
- Rs 211/lot-trade net (Rs 346 gross), 650 trades.
- Random entries with the same exits: Rs -191, p = 0.0005. The entries are real; the filters add nothing robust.

**Capital and margin:** unchanged from h10.
- Premium tied up: about Rs 14.6 L at p95 and Rs 24 L at most.
- Drawdown: Rs 9-13 L.
- Total: about **Rs 35-40 lakh**. Bought options need no margin beyond the premium.

**Rs 5,000/day realistically: NO, not dependably.** It is about Rs 4.8-6.1k/day in the holdout at κ 0.02 (gross about
Rs 14k). It turns negative at κ 0.04, and about 45% of months lose. No filter changes that.

## What was done (honesty)

- **Features:** side, daily trend (previous close against SMA20), 60-minute and intraday momentum, VIX level (against
  the previous 250-day median), VIX change on the day, time of day, day of week, trading days to expiry, gap/ATR14,
  range-so-far/ATR14, and room to the next level.
- **Rules:** 22 filters and 6 two-unit sizing rules. All 28 were counted.
- **How rules were scored and chosen:**
  - Metric: daily net Sharpe at 1 lot per unit, which equals Rs/day at the same risk without impact.
  - Walk-forward anchored by year: 2023, 2024 and 2025 to September.
  - Random-entry control: the pool's 5 random entries per signal, with the same exits, filtered on their own
    features.
  - Choice gates: positive walk-forward excess in at least 2 of 3 years, SPA p < 0.10, real uplift greater than random
    uplift.
- **Results of the choice:**
  - Pre-holdout table: 1,629 trades, Oct 2021 to Sep 2025.
  - Walk-forward picks: F04 for 2023 (excess -65/day), F21 for 2024 (-25), F04 for 2025 (+105).
- **Holdout:** run once. The full 28-rule holdout table is in `hold_rules.csv` and is **information only**. Every
  per-trade claim in point 3 is post-hoc.
- **Limits:**
  - Exits were tuned on data up to Feb 2026 (h4), so they are partly in-sample.
  - The regime window (monthly-only expiries) is only 10 months.
  - Feature thresholds (0.3 ATR, 0.5 ATR, 3/6 stops, 11:00, DTE 3) were fixed a priori, not tuned.
