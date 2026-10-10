# HUNT h35: the app's support and resistance levels. Are we using them? Do they pay for an option buyer?

Boss asked: "check the support and resistance whatever we are calculating (in the app). Are we using it or not?"

## Verdict (plain language)

**Only two kinds of levels actually trade by themselves.**
1. The **Liquidity 15+5** swing and pool levels. They decide the Liquidity arm's entries, targets and room filter. This is the one edge that has held up in earlier research.
2. The **ORB opening range** (09:15-10:05). It drives the ORB, ORB Fresh, ORB Sweep and Range Fade arms, on paper. Those arms lost Rs 2.5-9.8 lakh each over 2021-26.

**Jarvis's own levels play one small part.** They are yesterday's high, low and close, the 15-minute opening range, and recent 15-minute swings. They can block a Jarvis candle-pattern trade *idea* when a level is within 0.2%. Boss must still approve any idea.

**Everything else is shown on screen or spoken by Jarvis. It never places a trade.** That covers:
- max pain;
- the biggest call / put OI strikes (read as "resistance / support");
- classic daily and weekly pivots;
- Structure swings;
- round numbers;
- the VIX "usual-day range";
- GEX bands.

**We tested every level the earlier research had not tested exactly as the app computes it. As an option buyer, none of them pays.**
- There were 8 level families, 3 ways to trade each (break-and-hold, retest-then-go, bounce), and 17 exits. That is **408 variants**, all fixed before any result.
- **All 408 lost money after costs** before the holdout.
- **Only 6 of the 408 were positive even before charges.** The best was +Rs 20k over five years.
- None beat not trading: Hansen SPA p = 1.00, White's Reality Check p = 1.00.
- No walk-forward was positive.
- The single best variant was checked once on the locked holdout, for information only. It lost **Rs 453/day** at 1 lot with the real spread. 9 of its 13 months lost money.

**The same levels used as a FILTER on Liquidity 15+5 do not help either.** We tried 16 filters (8 families x 2 distances). None passed. Several would have *cut* profit, because they skip some of Liquidity's best trades.

**Recommendation:** keep the levels as information for Boss. Do not trade them, and do not add them as filters to Liquidity. Keep Liquidity 15+5 as it is.

**Rs 5,000/day from these levels: NO.** Every variant loses, so no number of lots gets there.

## 1. Audit: every support / resistance calculation in the app

Paths are under `android/`. "Used for trading" means the level itself decides an automatic order.

| # | level and formula | where computed | used where | drives an automatic trade? | tested for option buying? (study, result) | gap |
|---|---|---|---|---|---|---|
| 1 | Liquidity swing zones: a pivot high/low with 20 bars on each side; the zone is the pivot candle's full range | engine `orb/LiquidityRules.kt:136-148` | Liquidity 15+5 (BN 15m+5m, FIN 30m+5m, MIDCP 15m+5m); chart overlay `LiquidityOverlay.kt`; `LevelAlarm.kt`; Jarvis LiquidityMap / WhyNot / TomorrowPlan | **YES**: entry (a pool taken on an active swing zone) | Yes: LIQUIDITY*.md, HUNT_H4 (beats random, p 0.001), H9 (other timeframes add nothing), H10/H13/H14 | none |
| 2 | Liquidity pools: a wick rejected twice, contacts 5 bars apart, 10 bars to confirm | `LiquidityRules.kt:150-179` | same | **YES**: trigger (a close through a pool) | same | none |
| 3 | Next liquidity level ahead = target; room filter (skip if less than 1 index-stop unit) | `LiquidityRules.kt:197-208, 256-259` | Liquidity exit "next_liquidity" and entry skip | **YES** | Yes: liq2 quarterly walk-forward; LiquidityShadow (a) | none |
| 4 | ORB opening range: 5-min bars 09:15..10:00 (to 10:05) | `orb/OrbRules.kt:44-45, 60-64` | ORB, ORB Fresh (break), ORB Sweep (failed break), Range Fade (edge fade), on PAPER since 07 Oct, below Liquidity (`ArmPriority.kt`) | **YES** (paper) | Yes: `RetiredArms.kt:22-24` (lost Rs 9.77L / 2.53L / 2.91L / 2.74L); OBUY_GA OR-01..04 (ORB15 walk-forward -551k); HUNT_H18; HUNT_H21 | none |
| 5 | OI wall at the range edge (the edge strike holds the most OI among ATM..ATM+10) | `orb/ShadowRules.kt:198-213` | Shadows S17 / R20 only | NO: shadow record, never an order | Yes: R20 -2.7k on 134 trades; OBUY_GA LV-06 (walk-forward -79.8k) | none |
| 6 | Max pain (least total writer pain over the chain) | engine `options/ChainAnalytics.kt:141-158`; ira `ExpiryPin.kt:112`, `ChainDrift.kt:73` | Options tab; Jarvis ChainRead / ChainDrift / ExpiryDay / ExpiryPin | NO: display and talk | Partly: OBUY_GC EXP-04 expiry convergence (27 trades, p 0.16); H18 (no magnet) | intraday level: **h35** |
| 7 | Biggest call OI strike = "resistance", biggest put OI strike = "support" | ira `ChainRead.kt:22-24`, `ChainIntel.kt:191-192`, `ChainDrift.kt:83-84`; app `ToolsScreen.kt:254`; `Coach.kt:113` (OiShift) | Options / Tools screens; Jarvis chain answers; IraCoach wall-shift notices (`IraCoach.kt:406-419`) | NO: display and talk | Partly: OBUY_GC OI-04 max-OI magnet (walk-forward -339k); H18 ("toward max-OI" filter); LV-06 | break / retest / bounce, and as a filter: **h35** |
| 8 | Classic daily pivots: P=(H+L+C)/3, R1=2P-L, S1=2P-H, R2=P+(H-L), S2=P-(H-L), from the previous session | ira `Reason.kt:226-260` (`Pivots.of` :233) | Jarvis "pivots"; 09:00 morning brief (`Outlook.brief`, `Reason.kt:852`, via `IraHub.kt:1393`); `OutlookCheck.kt:75` grades it | NO: talk | Only CPR width (OBUY_GA LV-02, walk-forward -62.3k) and OBUY_SEARCH CPR atoms. P/R1/S1/R2/S2 themselves were never tested | **h35** |
| 9 | Weekly pivots: same formula over the last 5 sessions | `Reason.kt:822-826` | Jarvis week outlook | NO: talk | No | **h35** |
| 10 | Jarvis Brain levels: yesterday's high / low / close; first-15-minute opening range; 15-min swing highs/lows (k=3) of the last 5 days | ira `Brain.kt:76-97`, `Candles.kt:108-115` | Jarvis level talk (`Ira.kt:91, 126`), `LevelInfo` (`Reason.kt:617`); **PatternExpert room veto** (`PatternExpert.kt:24, 132-134`) | SEMI: only blocks a Jarvis trade idea; the idea needs Boss's Approve | Previous-day high/low: OBUY_GA LV-01 (walk-forward -129.7k), H18 (about Rs 2/day net). OR15: OR-01. Previous close and the 5-day 15-min swings: never | **h35** (as the app combines them) |
| 11 | Structure swings: today's closed 5-min candles, k=2 | ira `Structure.kt:23, 142, 173` | Jarvis "structure / swing levels" | NO: talk | No (H18 used rolling 60-min highs and lows) | **h35** |
| 12 | Round numbers: every 500 (NIFTY, FIN, else) / 1,000 (BN, SENSEX) | ira `RoundCloses.kt:103`; `Reason.kt:635` | Jarvis round-number record; "what's at 25000" | NO: talk | No | **h35** |
| 13 | VIX "usual-day range": close ± close x VIX/100/√252 | ira `Reason.kt:107-110, 855-857`; app `DailyReports.kt:244` | morning brief; expected-move answers; graded by `OutlookCheck` | NO: talk | No | **h35** |
| 14 | GEX and gamma-density sigma bands | `ChainAnalytics.kt:402, 451` | Options tab | NO: display | H18 T1: tells how much the price will move, not which way | not a price level |
| 15 | Level records: opening-range breaks (`RangeBreaks.kt`), prior-day take-outs (`PriorDay.kt`), `WeekRange.kt`, opening-range alert (`Reason.kt:415`) | ira-core | Jarvis talk and one alert per break | NO | the levels in rows 4 and 10 | covered |
| 16 | Gold liquidity levels (same swing / pool code) | `gold/GoldLiquidity.kt:74-76` | Gold arm | YES, but on gold | LIQUIDITY_GOLD*.md | out of scope |

Solo and Hero use no support/resistance level: Solo uses an ATR move, Hero a straddle jump. Pine scripts can call `ta.pivothigh`, but those scripts are the user's own.

## 2. What was tested (pre-registered: `research/hunt/h35/PREREG.md`)

- **Levels.** The 8 "h35" rows above, ported faithfully in `research/hunt/h35/levels.py`, with the Kotlin lines cited:
  - pivD: daily pivots;
  - pivW: weekly pivots;
  - brain: Jarvis Brain levels;
  - struct: Structure swings;
  - maxoi: the two OI walls;
  - maxpain: max pain;
  - round: round numbers;
  - vix: the VIX band.
- **Data and look-ahead.** NIFTY, BANKNIFTY, FINNIFTY, SENSEX and MIDCPNIFTY, 2020-2026. A level is used only once it is known: at the previous 5-min bar's close, and from OI read at that minute.
- **Entries.** Made on closed 5-min bars, 09:30-14:30:
  - **break-and-hold**: a fresh close beyond the level, and the next bar closes beyond it too;
  - **retest-then-go**: after the break, a bar within 60 min comes back within 0.03% of the level and closes beyond it;
  - **bounce / rejection**: a bar reaches the level (within 0.03%) from one side and closes back on that side.
- **What was bought.** One lot of the 1-ITM nearest-expiry CE/PE, bought at the next minute's open with the app's fills and charges. Expiry days are skipped. One position at a time per book, at most 3 a day.
- **Exits (17).** All checked on the option's 1-minute high/low; if the stop and target are hit in the same minute, the stop counts first.
  - premium targets +15 / +20 / +25 / +30 points against stops -10 / -15 / -20;
  - the Liquidity arm's exits (-15% premium, 20-min time stop at +5%, index stop at the level);
  - the app's -40/+40 profit-lock ladder;
  - pure 15 / 30 / 60-minute time stops.
- **Costs.** Results are reported four ways:
  - gross;
  - net app;
  - **net REAL** = net app minus the HUNT_H24 half-spread on both legs (BN 0.16%, NIFTY 0.16%, MIDCP 0.21%, FIN 0.42%, SENSEX 0.20%);
  - stress = 1.5x that spread.
- **Controls.**
  - a same-exit random-entry baseline (5 per signal);
  - BH across all 408 variants;
  - an anchored yearly walk-forward per family;
  - Hansen SPA and White's RC on daily net-REAL P&L.
- **Holdout.** Locked from 2025-10-01 and opened once.

## 3. Results: choice window (before 2025-10-01), 1 lot, all five indices together

For each family and mode: the best of its 17 exits by net REAL. Rs/day is over all 1,279 trading days.

| family | mode | trades (median) | best gross over the 17 exits | best exit | net app | **net REAL** | stress 1.5x | **REAL Rs/day** |
|---|---|---|---|---|---|---|---|---|
| pivD | brk | 7525 | +7,652 | time 60 | -447,394 | -709,567 | -840,654 | -555 |
| pivD | rt | 6731 | +17,685 | time 60 | -381,109 | -621,375 | -741,508 | -486 |
| pivD | bo | 7695 | -152,806 | time 60 | -583,318 | -803,871 | -914,147 | -629 |
| pivW | brk | 3364 | -92,816 | time 60 | -332,078 | -444,000 | -499,961 | -347 |
| pivW | rt | 2807 | -49,249 | +30/-15 | -243,961 | -364,175 | -424,282 | -285 |
| pivW | bo | 3952 | -149,872 | time 30 | -386,533 | -535,128 | -609,426 | -418 |
| brain | brk | 9312 | -211,831 | time 60 | -714,949 | -971,864 | -1,100,322 | -760 |
| brain | rt | 9087 | -396,551 | time 60 | -1,100,569 | -1,361,733 | -1,492,315 | -1,065 |
| brain | bo | 8264 | -187,803 | time 60 | -670,097 | -851,050 | -941,526 | -665 |
| struct | brk | 9313 | -81,946 | time 60 | -745,431 | -1,050,625 | -1,203,222 | -821 |
| struct | rt | 9153 | -207,738 | +30/-20 | -921,420 | -1,269,601 | -1,443,692 | -993 |
| struct | bo | 7968 | -295,681 | time 60 | -616,141 | -808,461 | -904,620 | -632 |
| maxoi | brk | 3966 | -55,296 | time 30 | -305,140 | -444,701 | -514,481 | -348 |
| maxoi | rt | 3347 | -68,470 | time 30 | -280,400 | -398,284 | -457,226 | -311 |
| maxoi | bo | 4647 | -73,123 | time 60 | -296,117 | -422,742 | -486,055 | -331 |
| maxpain | brk | 5382 | -79,064 | time 30 | -455,823 | -646,078 | -741,206 | -505 |
| maxpain | rt | 4663 | -70,784 | time 15 | -393,176 | -566,851 | -653,689 | -443 |
| maxpain | bo | 5981 | -72,742 | time 15 | -501,765 | -742,777 | -863,283 | -581 |
| round | brk | 2543 | +19,925 | +30/-10 | -161,576 | -272,345 | -327,730 | -213 |
| round | rt | 2048 | -14,122 | time 15 | -161,572 | **-248,041** (best of all 408) | -291,275 | -194 |
| round | bo | 3011 | +2,283 | time 60 | -171,040 | -255,310 | -297,445 | -200 |
| vix | brk | 2226 | -61,717 | time 60 | -230,010 | -308,717 | -348,071 | -241 |
| vix | rt | 1839 | -50,214 | time 60 | -195,159 | -259,273 | -291,330 | -203 |
| vix | bo | 2715 | -79,513 | time 60 | -258,692 | -341,513 | -382,924 | -267 |

What the table says:
- **0 of 408 variants are positive net of app costs, and 0 net of the real spread.** Only 6 are positive even gross (round 4, daily pivots 2).
- **Walk-forward.** Every family is negative. The best is round numbers at -Rs 2.52 lakh. Years positive: 0 of 4 for seven families and 1 of 4 for vix.
- **Gate counts.** S1 (net REAL > 0): 0. S2 (BH q < 0.10): 0. S3 (walk-forward): 0. SPA p = 1.00, RC p = 1.00. **No survivors.**
  - The random baseline is only run for variants that made money, which is the obuy lab's two-stage rule. So every p here is 1 by construction.
- **Where it goes wrong.** The "busy" families lose most: Brain and Structure trade 8-9k times and lose Rs 8-13 lakh. Bounces are worst on average (median gross -2.5 lakh). That matches H18: levels do not reverse the price.
- **Per year, best variant** (round, retest, 15-min time stop, net REAL): 2020 +2.4k, 2021 -24.1k, 2022 -9.2k, 2023 -82.2k, 2024 -91.4k, 2025 (to Sep) -43.5k.

## 4. Locked holdout (2025-10-01 .. 2026-10-06), opened once, for information only

PREREG: with no survivors, only the single best choice-window variant is run once. It was **round numbers, retest-then-go, 15-minute time stop**.

| | value |
|---|---|
| trades | 736 |
| gross | +Rs 27,255 (+Rs 109/day) |
| net app | -Rs 41,708 |
| **net REAL** | **-Rs 113,297 (-Rs 453/day)** |
| stress 1.5x | -Rs 149,092 |
| max drawdown | -Rs 118,791 |
| worst day / worst month | -Rs 11,728 / -Rs 32,278 |
| losing months | 9 of 13 |
| beats random entries | p 0.054 (not significant; one look) |
| lots for Rs 5,000/day | not possible (it loses) |

It is a little positive before charges. The app's charges and the bid-ask spread then turn it into a steady loss, which is the usual story for buying options on frequent signals.

## 5. The levels as filters on Liquidity 15+5

The test used the 6 app books (BN15, BN5, FIN30, FIN5, MID15, MID5) from the h4 port, with app fills and the real spread. That is 1,629 trades before the holdout, worth +Rs 1.20 lakh net REAL (+2.09 lakh at app costs).
- **The filter.** Skip an entry when a level of the family lies ahead of the signal close, in the trade's direction, within D.
- **The two distances.** D = 1 index-stop unit (BN 30 / FIN 15 / MID 8), or D = 0.2% (Jarvis's PatternExpert room rule).
- **The keep rule.** A filter is kept only if it is better in both halves AND its skipped trades are worse than a random skip, with BH q < 0.10.

| filter | skipped | skipped trades' mean | kept mean | change to net | p (skip vs random) | BH q | kept? |
|---|---|---|---|---|---|---|---|
| daily pivots, 1 unit | 200 (12%) | -38 | +89 | +7.6k | 0.18 | 0.57 | no (worse in 2021-23) |
| daily pivots, 0.2% | 542 (33%) | +24 | +99 | -12.8k | 0.22 | 0.58 | no |
| weekly pivots, 1 unit | 70 | +563 | +52 | -39.4k | 0.99 | 0.99 | no |
| weekly pivots, 0.2% | 217 | +358 | +30 | -77.7k | 0.99 | 0.99 | no |
| Brain levels, 1 unit | 303 (19%) | -65 | +105 | +19.7k | 0.07 | 0.35 | no |
| Brain levels, 0.2% | 725 (45%) | +5 | +128 | -3.9k | 0.09 | 0.35 | no |
| Structure swings, 1 unit | 159 | -120 | +95 | +19.1k | 0.07 | 0.35 | no |
| Structure swings, 0.2% | 231 | -115 | +105 | +26.5k | 0.045 | 0.35 | no |
| max OI, 1 unit | 60 | +471 | +58 | -28.3k | 0.95 | 0.99 | no |
| max OI, 0.2% | 172 | +225 | +56 | -38.7k | 0.88 | 0.99 | no |
| max pain, 1 unit | 29 | +160 | +72 | -4.6k | 0.62 | 0.99 | no |
| max pain, 0.2% | 57 | +1 | +76 | -0.1k | 0.40 | 0.82 | no |
| round, 1 unit | 45 | +519 | +61 | -23.4k | 0.94 | 0.99 | no |
| round, 0.2% | 120 | +204 | +63 | -24.5k | 0.78 | 0.99 | no |
| VIX band, 1 unit | 60 | +259 | +67 | -15.5k | 0.79 | 0.99 | no |
| VIX band, 0.2% | 205 | +46 | +78 | -9.4k | 0.41 | 0.82 | no |

(Means are Rs per trade, net REAL.)

- **No filter is kept, so no filter holdout was run.**
- **Liquidity trades running into a weekly pivot, an OI wall or a round number did BETTER, not worse.** A break of liquidity that heads toward a big level is often the move that carries through it. Skipping those trades would have cost Rs 23-78k.
- Structure swings and Brain levels came closest: skipped trades lost Rs 65-120 each, raw p 0.045-0.07. They did not survive the correction across 16 filters. Treat them as a weak lead at most.
- H18 had already found that "toward max-OI" does not help Liquidity.

## 6. Honesty notes

- **Variants counted.** 408 trading variants and 16 filters = **424**, all declared in PREREG.md before any P&L. Nothing was pruned. The holdout was opened once, for one variant.
- **Deviations.**
  1. Signals were thinned to the first 6 per index-day, to fit the data pass in memory. With at most 3 trades a day, one at a time, later signals rarely matter.
  2. Max OI and max pain are computed over the ATM±10 strikes only, because that is all the data holds; the app reads the broker's window.
  3. Jarvis has no MIDCPNIFTY, but its formulas were applied to it anyway.
  4. The spread is charged on entry + exit as a flat percentage of premium. That slightly understates the cost at stop fills.
  5. "Gross" includes the app's ±5/10 bps fill slippage but no charges.
- **Data.** Real Dhan minute candles. NIFTY from Aug 2020; BN / FIN from 2021; SENSEX from 2023; MIDCP from 2022-23. Days with a synthetic index print were dropped.

## Files

- `research/hunt/h35/PREREG.md`: the plan, written first.
- `research/hunt/h35/levels.py`: the level ports (Kotlin lines cited) and the event builder.
- `research/hunt/h35/run.py`: obuy Lab runs (`pre <family>`, `combine`, `hold`), net REAL inside the Lab.
- `research/hunt/h35/filt.py`: the Liquidity filter test.
- Logs and small results: `scratchpad/hunt/h35/` (pre_variants.csv, pre_summary.json, hold_summary.json, filt_pre.csv). The large signal cache was deleted.
