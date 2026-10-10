# HUNT h4: getting to Rs 5,000/day by combining and scaling the rules with the best evidence

Code: `research/hunt/h4/` (`comps.py` components, `run_comps.py` one data pass with three executions, `portfolio.py`
daily matrix, portfolios, walk-forward, sizing, risk, random baseline, SPA, holdout; `liq3.py` is a diagnostic chosen
after the fact and labelled that way). Caches: `scratchpad/hunt/h4/cache/h4/` (`components.csv`, `corr.csv`,
`wf_portfolios.csv`, `daily_real.csv`, `summary.json`).
Data: real Dhan option minute bars, 1 Oct 2021 to 6 Oct 2026, with 1 lot at the lot size in force on each date. All of
it is option BUYING.

## Verdict (plain language)

**NO: Rs 5,000/day is not realistic as a dependable income from these rules.** On paper it can be reached by scaling,
but only with:
- about 29 "units" (roughly 15-45 lots per component);
- Rs 60 lakh to Rs 1 crore of capital;
- a walk-forward drawdown of about **Rs 25 lakh**;
- a **48% chance that any month loses money**;
- an edge that does not pass the correction for everything tried (SPA p = 0.17, White RC p = 0.43).

The holdout result looks very good: **+Rs 12,400/day net and +Rs 30,800/day gross** at the size fixed beforehand.
That figure is **flattered** for two reasons:
- Liquidity's exits were tuned on Feb 2024 to Feb 2026, which covers 5 of the 12 holdout months.
- Most of the holdout profit comes from a few very large days.

**The single best simple rule (it is the core of the portfolio):** run the app's Liquidity 15+5 arm, unchanged, on
**BANKNIFTY, FINNIFTY and MIDCPNIFTY**.
1. Look for a 15-min (FINNIFTY: 30-min) or 5-min bar that closes through a "pool" level which sits on a swing level.
   Entries run 09:20-14:00. Only take it if there is at least one index stop of room to the next level.
2. Buy the 1-strike-ITM option in the break's direction.
3. Exit at whichever comes first:
   - a -15% premium stop;
   - the index stop (BANKNIFTY 30 / FINNIFTY 15 / MIDCPNIFTY 8 points beyond the level);
   - 20 minutes without +5%;
   - the next liquidity level;
   - a failed break;
   - a new level;
   - 15:10.
4. Skip expiry days. Hold one position per book.

MIDCPNIFTY is new here. Its rules are unchanged except the index stop, which was set beforehand at the arms'
~0.065% of the index and not tuned. It is the cleanest evidence in this study because none of its periods was used for
tuning:
- Net per lot by year: 2023 (from May) -9.5k, 2024 +49.6k, 2025 +15.7k, 2026 +46.4k.
- It beats random entries with the same exits, both before the holdout and in it (p 0.001).

**What this rule needs for Rs 5,000/day.** The figures below are post-hoc: I picked this subset after seeing the
results.
- About 16 lots of each of the three indices.
- Rs 14 lakh of premium tied up on a busy day (95th percentile); Rs 29 lakh at the worst coincidence.
- About Rs 25-40 lakh of capital once the drawdown is included.

| Rule at 16 lots each | net per day | gross per day | max drawdown | losing months | worst month |
|---|---|---|---|---|---|
| Pre-holdout (Jun 2023 to Sep 2025) | Rs 5,000 (sized to this) | Rs 8,360 | -6.2 lakh | 14 of 28 | -3.1 lakh |
| Holdout (Oct 2025 to Oct 2026) | **Rs 8,830** | Rs 14,490 | -9.5 lakh | 6 of 13 | -6.1 lakh |

Bootstrap P(losing month) = 42%. Profits come in lumps: only 28-37% of days are positive, and the best day
(+Rs 6.6-8.4 lakh at this size) is bigger than the worst month.

**Capacity was not tested.** At 16+ lots the fills will be worse than modelled, most of all in the thin MIDCPNIFTY
and FINNIFTY monthly contracts: 1,920 MIDCPNIFTY units in one 1-ITM monthly option is a lot. The 'real' fill model
adds 1-4 ticks per side, sized for 1 lot.

## What was tested (all fixed before any portfolio result was read)

Three executions of the same signals, strikes and exits:
- **gross**: fills at the bar print, no charges.
- **app**: the app's paper fills (±5/10 bps) plus SandboxCosts. Liquidity BANKNIFTY/FINNIFTY reproduces the
  validated **Rs 242,421 over 1,651 trades** exactly.
- **real (net)**: the 'app' fills plus a 1-4 tick half-spread from the contract's last 5 minutes of volume, plus
  SandboxCosts.

There are 12 components.
- **Liquidity 15+5** on BANKNIFTY, FINNIFTY (the validated port), and NIFTY / SENSEX / MIDCPNIFTY. For the last three
  the same rules are re-implemented. The port's signals are identical to the validated ones on BANKNIFTY and FINNIFTY:
  999 / 694 signals, 0 differences.
- **The app's four ORB arms** (ORB, ORB Fresh, ORB Sweep, Range Fade), BANKNIFTY, rules as in `arms_long.py`.
- **Solo midday** (Jarvis C0 exits).
- **BTST strong close** (overnight, so NOT intraday): the 48-variant grid.
- **Camarilla fade**: the 72-variant grid.

For BTST and Camarilla, the variant with the best pre-holdout net was frozen for the holdout. The walk-forward series
uses the variant that was best on the earlier years.

Trials counted: **131 daily P&L series** = 127 component variants + 4 portfolio rules. The post-hoc `liq3` diagnostic
is the 132nd.

### Components, net per lot ('real'), before the holdout and in the holdout

Pre-holdout runs from 1 Oct 2021, or from each component's first trade, to 30 Sep 2025. The holdout runs from 1 Oct
2025 to 6 Oct 2026. In the random-entry columns, "p" is the chance that random entries with the same exits did at
least as well; small is good.

| component | trades | gross pre | net pre | gross holdout | net holdout | random-entry p, pre (BH q) | random-entry p, holdout |
|---|---|---|---|---|---|---|---|
| Liquidity BANKNIFTY | 998 | +155,299 | +85,152 | +90,570 | **+59,182** | 0.0005 (0.0015) | 0.0005 |
| Liquidity FINNIFTY | 653 | +107,792 | +67,371 | +45,823 | **+24,540** | 0.0005 (0.0015) | 0.001 |
| Liquidity MIDCPNIFTY (new) | 628 | +90,330 | +48,787 | +88,873 | **+53,524** | 0.0005 (0.0015) | 0.001 |
| Liquidity NIFTY (new) | 765 | +19,830 | -26,937 | +12,542 | +8 | 0.82 | 0.23 |
| Liquidity SENSEX (new) | 503 | +31,530 | +5,824 | +12,131 | -359 | 0.34 | 0.14 |
| Solo midday | 586 | +40,301 | +8,186 | +30,817 | +16,512 | 0.014 (0.028) | 0.19 |
| BTST strong close (in-sample pick) | 1,100 | +269,506 | +200,452 | +17,319 | -8,454 | (in-sample) | 0.23 |
| Camarilla fade (in-sample pick) | 1,416 | +236,876 | +149,173 | -42,365 | **-77,306** | (in-sample) | 0.89 |
| ORB (app arm) | 7,932 | -276,827 | -873,257 | -89,035 | -355,673 | 0.63 | 0.19 |
| ORB Fresh | 2,021 | -83,360 | -232,965 | +16,701 | -42,923 | 0.73 | 0.016 |
| ORB Sweep | 1,323 | -45,961 | -134,639 | -53,495 | -88,265 | 0.55 | 0.97 |
| Range Fade | 1,451 | -110,173 | -204,042 | -70,263 | -110,555 | 0.91 | 0.96 |

- **The four ORB arms that were just switched back on all lose.** They lose gross and net, before the holdout and in
  it, which repeats FINDINGS.md / SIDEWAYS_GUARD.md. ORB on its own costs about Rs 3.5 lakh a year per lot.
- **BTST and Camarilla both decayed.** BTST went from +Rs 67k a year in 2021-22 to -Rs 18k in 2026. Camarilla lost
  Rs 77k in the holdout.

**Correlations (daily, pre-holdout)** are low across families (|r| ≤ 0.2), so diversification is real. The exceptions
are inside families:
- Liquidity NIFTY and SENSEX: 0.70.
- ORB and ORB Fresh: 0.53.
- ORB Sweep and Range Fade: 0.55.
- Liquidity across indices: 0.13-0.31.

### Portfolio rules (walk-forward: each year's weights come only from earlier data; test years 2023 to Sep 2025)

| rule | net per day, 1 unit | total net | gross | max DD | losing months | 2023 | 2024 | 2025 (to Sep) |
|---|---|---|---|---|---|---|---|---|
| Equal lots, all components | -1,095 | -744,743 | +205,772 | -761,933 | 26/33 | -238,737 | -204,170 | -301,836 |
| Risk parity, all | -612 | -416,300 | +278,728 | -431,535 | 25/33 | -161,454 | -36,371 | -218,475 |
| Only components positive on all earlier data, equal lots | +157 | +106,431 | +334,932 | -84,973 | 18/33 | +16,348 | -32,173 | +122,256 |
| **Same, risk parity (chosen: best walk-forward Sharpe)** | **+173** | +117,723 | +319,490 | -85,271 | 18/33 | +10,024 | -22,056 | +129,755 |

**Weights frozen at 1 Oct 2025**, in lots per unit:

| Liquidity BANKNIFTY | Liquidity FINNIFTY | Liquidity MIDCPNIFTY | Liquidity SENSEX | Solo | Camarilla | BTST |
|---|---|---|---|---|---|---|
| 0.83 | 1.28 | 0.94 | 1.27 | 1.62 | 0.60 | 0.46 |

NIFTY Liquidity and all ORB arms get 0.

### Sizing to Rs 5,000/day and the holdout (chosen portfolio)

The scale k = 5,000 / 173 = **28.9 units**, so the lots are:

| Liquidity BANKNIFTY | Liquidity FINNIFTY | Liquidity MIDCPNIFTY | Liquidity SENSEX | Solo | Camarilla | BTST |
|---|---|---|---|---|---|---|
| 24 | 37 | 27 | 37 | 47 | 17 | 13 |

| at k = 28.9 | walk-forward 2023 to Sep 2025 (net) | holdout gross | holdout app | **holdout net (real)** |
|---|---|---|---|---|
| Rs per day | 5,000 (by construction); gross 13,600 | **30,761** | 13,886 | **12,395** |
| total | 34.0 lakh / 680 days | 76.6 lakh | 34.6 lakh | 30.9 lakh / 249 days |
| worst day | -4.3 lakh | -5.8 lakh | -6.3 lakh | -6.4 lakh |
| worst month | -6.0 lakh | -14.3 lakh | -17.6 lakh | -17.8 lakh |
| max drawdown | **-24.6 lakh** | -20.6 lakh | -24.9 lakh | **-25.1 lakh** |
| losing months | 18 of 33 | 3 of 13 | 5 of 13 | 5 of 13 |
| P(losing month), bootstrap | **48%** | - | - | 39% |
| P(drawdown ≥ 10 lakh within a year), bootstrap | 76% | | | |

Capital:
- **Premium tied up at once: Rs 41 lakh** on a busy day (95th percentile), and up to Rs 89 lakh if every component's
  daily peak coincided.
- Option buying needs no margin beyond the premium. Add a buffer for the 25-lakh drawdown, which gives **Rs 60 lakh to
  Rs 1 crore**.
- That is roughly 12-20% a year on the capital for Rs 5,000/day, with half the months losing.

### Controls

- **Random entries with the same exits, strikes and weights**: the portfolio beats them both before the holdout and
  in it.

  | period | portfolio | random entries (mean) | p |
  |---|---|---|---|
  | pre-holdout, 2023 on | +1.55 lakh | -2.28 lakh | 0.0005 |
  | holdout | +1.07 lakh | -1.52 lakh | 0.0005 |

  Almost all of this comes from the Liquidity books. The pre-holdout figure uses the in-sample BTST and Camarilla
  picks.
- **White's Reality Check p = 0.43 and Hansen SPA p = 0.17** over all 131 series (daily P&L 2023 to Sep 2025 against
  not trading): **no series is significant once everything tried is counted.**
- **Contamination:**
  - Liquidity BANKNIFTY/FINNIFTY exits were chosen on Feb 2024 to Feb 2026 (LIQUIDITY_EXITS_3060.md). Their 2024-25
    profits and 5 of the 12 holdout months are partly in-sample. Before Feb 2024 the arm made only about Rs 3k net.
  - ORB Sweep and Range Fade were chosen on Aug-Sep 2026, which is inside the holdout; it does not matter because both
    lose.
  - MIDCPNIFTY, SENSEX and NIFTY Liquidity are clean, apart from the a-priori index stop.

## Answer for Boss

| | |
|---|---|
| Best simple rule | Liquidity 15+5 (the app's arm, unchanged) on BANKNIFTY + FINNIFTY + MIDCPNIFTY, 1-ITM, -15% stop and its structural exits |
| Holdout (Oct 2025 to Oct 2026), 1 lot each | net +Rs 551/day (gross +Rs 905/day) |
| Holdout, chosen 7-component portfolio, 1 unit | net +Rs 429/day (gross +Rs 1,065) |
| Size for Rs 5,000/day (set on pre-holdout data) | ~16 lots of each index (rule), or 29 units of the portfolio |
| Holdout at that size | rule: net Rs 8,830/day, gross Rs 14,490/day; portfolio: net Rs 12,395/day, gross Rs 30,761/day |
| Worst drawdown at that size | rule 6-9.5 lakh; portfolio about 25 lakh |
| Capital | rule Rs 25-40 lakh; portfolio Rs 60 lakh to Rs 1 crore |
| P(losing month) | 42-48% |
| Rs 5,000/day realistically? | **NO, not as a dependable daily figure.** It is an average of lumpy, skewed P&L: about 1 day in 3 is green and half the months are red. The edge is not significant after multiple testing (SPA p 0.17). The holdout is partly in-sample for BANKNIFTY/FINNIFTY, and fills at 16-40 lots are untested. |
| Worth doing next | Paper-trade Liquidity on MIDCPNIFTY (the clean, untuned out-of-sample evidence) next to BANKNIFTY/FINNIFTY. Drop all four ORB arms. Retire Camarilla and BTST. |
