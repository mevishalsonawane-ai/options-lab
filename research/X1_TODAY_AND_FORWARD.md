# X1: Boss's 8 Oct logs, the forward paper tests against the backtests, and what more could be done

Written 8 Oct 2026, after `research/PROFIT_LOCK_8OCT.md`. Everything is paper, option buying, net of the app's charges
unless marked gross. Code: `research/hunt/x1/`. Data: Dhan 1-minute candles with OI, fetched for 6 Oct (afternoon),
7 Oct and 8 Oct, for every Oct-27 monthly strike of BANKNIFTY, FINNIFTY and MIDCPNIFTY near the money, plus the indices.
The replays reuse h19's validated ports unchanged (ORB family; Liquidity 15+5 through the h4 port and obuy's engine,
which reproduces the app to the paisa).

## Verdict (read this first)

1. **The app trades the rules it is meant to trade. The forward paper tests are what the backtests predict, except
   for ORB's luck.** I replayed the same days (6-8 Oct) with the 5-year replay code:

   | arm | Boss's paper test | the replay, same days | what it means |
   |---|---|---|---|
   | ORB | 30 trades, **+5,068**, won 60% | 30 trades, **+839**, won 47% | same trades; the app was **+4.2k luckier** in its fills (8 Oct alone: +2.0k, explained trade by trade below) |
   | ORB Fresh | 3 trades, +2,427 | 3 trades, +2,101 (parked on 8 Oct, as Jarvis did) | match |
   | ORB Sweep | 6 trades, -4,498 | 6 trades, -3,677 | match |
   | Range Fade | 6 trades, -1,256 | 6 trades, -395 | close |
   | Liquidity 15+5 (BN + FIN + MIDCP) | 20 trades, **-6,186**, won 30% | 19 trades, **-5,963**, won 32% (1 lot) | match: the loss is the market, not the app |

   The trade counts match exactly for the ORB arms, so the paper tests count from **6 Oct** (the day the arms' rules
   and the one-index guard changed). That is an inference from the counts; the app's own log would confirm it.

2. **ORB's +5,068 is luck.** Over 5 years, only **1.8%** of all 30-trade stretches of ORB made that much (2.9% in the
   last year; 0.6-2.3% by bootstrap). The replay of the same 30 trades made +839, itself a top-15% stretch. The rest
   is timing luck in the app's fills. With six strategies on paper tests, one of them showing a 1-in-50 run is about
   a 1-in-9 event. ORB lost Rs 9.8 lakh at 1 lot over 5 years and never had two green months in a row (h19, h20, h21).
   **Do not take ORB live.**

3. **Liquidity's -6,186 is ordinary bad luck.** 10-13% of all its 20-trade stretches lost that much or more (19% in
   the last 6 months). Counted at 1 lot (the 7 Oct 2-lot trade halved), the record is about -4,225, which happens
   17-28% of the time. Its expected forward pace is still about **+Rs 65 to +167 a day** for BANKNIFTY at 1 lot
   (h36). One 20-trade stretch says nothing about that.

4. **The app's own "paper test" (Vetting.kt) is too weak to judge any of these arms, and today it gave both
   verdicts the wrong way round.** From random start dates in the 5-year replay:
   - a long-run **loser** (ORB, Fresh, Sweep, Fade) is first told "held up" **13-31%** of the time;
   - **Liquidity**, the one arm that makes money, is first told "failed" **50-55%** of the time.
   That is the real bug behind both of Jarvis's requests.

5. **New, tested changes beyond the lock fixes already shipped** (details in part 2):

   | # | change | kind | evidence (5-year replay) |
   |---|---|---|---|
   | 1 | **Make the paper test compare the forward record with the arm's own backtest band**, count rupees per lot, and need 60+ trades and 40+ days before any verdict. Never say "held up" for an arm whose 5-year replay is red. | bug fix (judgement) | false "held up" 13-31% and false "failed" 50-55% with today's 15-trade rule |
   | 2 | **Fire Liquidity's entries within seconds of the bar close** (wake the arms at each 5-minute boundary, not on the next 60-second pass) | bug fix (latency) | a 1-minute-late buy costs **Rs 45-80 a BANKNIFTY/FINNIFTY trade** and **Rs 130-145 a MIDCPNIFTY trade**, same sign before and in the holdout (t -1.6 to -5.0). BN's whole edge is Rs 116-242 a trade. Today the app bought 1-2 minutes late on every Liquidity trade |
   | 3 | **Judge BANKNIFTY Liquidity on its own record**; keep FINNIFTY and MIDCPNIFTY as separate paper tests | risk / clarity | h24, h36, h44: BN carries the edge; FIN and MIDCP add about zero after the real spread and double the drawdown. Today BN lost 6,440 while MIDCP made 2,457 in the replay, so the mixed record hides both |
   | 4 | **Keep Liquidity at 1 lot on paper, and set the code default to 1** (`LiquidityLots.DEFAULT = 2`) | risk | the 7 Oct 2-lot trade doubled one loss (-3,922); the plan is 1 lot (h36, HUNT_FINAL) |
   | 5 | **Turn Jarvis's "lessons" off under 30 trades, or check each one against the replay** | bug fix (judgement) | "Liquidity loses before 10:00" (6 trades) is the reverse of history: its pre-10:00 entries are its **best** (+188 / +317 a trade against +84 / +132 later). "ORB loses 13:00-14:00" is no different from its other hours |

   Tested and **not** recommended: pausing Liquidity after a bad stretch (all 27 versions lose money in both periods);
   applying the new ThinOption gate to Liquidity (it removes mostly winners; see part 2); any account lock beyond the
   shipped +Rs 8,000 stop; a Solo premium trail (lock report).

6. **Jarvis's two requests:**
   - **Stop Liquidity 15+5 on paper? No.** The replay of the same days loses the same amount, so the app is not
     broken. A loss this size happens 1 time in 5 to 1 time in 10, and stopping after bad stretches lost money in
     every version tested. Stopping it on paper costs nothing in rupees, but it throws away the forward record the
     plan needs (about 300 trades, HUNT_FINAL). Keep it, at 1 lot, with BN judged separately.
   - **Stop the Pine FinNifty breakdown script? Yes, on FINNIFTY.** Not because 3 trades is abnormal: on a 1.5% trend
     day a script that averages 0.9 trades a day gives 3 or more about 6% of the time on Poisson odds alone, and more
     on trend days. The reasons are:
     - FINNIFTY's monthly options are too thin for automatic stops and locks. They have the widest spread of the four
       indices (0.42% half-spread, h24). Today a breakeven lock was gapped by 11.6 points, and a buy filled at a price
       nobody traded (lock report).
     - The script has no long-run record in our data. The scripts on the phone are not in the repo, and the two known
       Pine scripts lose Rs 1.7-2.2 lakh over 5 years (h22).
     - Its paper test is -962 on 9 trades, which is too small to say anything.
     - The ThinOption gate shipped today would have skipped the worst of today's three trades (13:31, -2,285) anyway.
     If Boss wants the idea tested properly, export the script's text and it can be replayed on 5 years of BANKNIFTY
     or NIFTY, where the spread is a third as wide.

7. **Could anything have warned of today's trend-down day? No.** FII flows, positioning and OI were all bearish on
   7 Oct (h40's composite was -1.5). But after such readings BANKNIFTY fell from open to close on only 49% of days
   before the holdout and 42% in it, no better than other days (h40 found the same: flows move the gap, not the day).
   The composite had been as bearish on 30 Sep (BN +1.0%), 1 Oct and 5 Oct. h26 (OI), h27 (global cues), h31
   (overnight), h41 (market timing) and h43/h44 all say the same: direction is not readable in advance at a size
   that pays an option buyer.

## 1. Forward against backtest, trade by trade

### Which days the paper tests cover

`IraExpert.verdicts()` judges every closed paper trip by its owner. The counts fit a start on 6 Oct exactly:

| arm | replay trades 6 / 7 / 8 Oct | sum | Boss's paper test |
|---|---|---|---|
| ORB | 6 / 14 / 10 | 30 | 30 |
| ORB Sweep | 2 / 2 / 2 | 6 | 6 |
| Range Fade | 2 / 2 / 2 | 6 | 6 |
| ORB Fresh | 2 / 1 / 0 (parked by Jarvis on 8 Oct) | 3 | 3 |
| Liquidity (6 Oct ATM rules, then 1-ITM) | 7 / 5 / 7 | 19 | 20 |

From 1 Oct the counts would be about twice as large, so a 1-Oct start does not fit. ORB's 30 = 30 also means the
app took about as many ORB trades as the rules allow. It took 7 on 8 Oct against the replay's 10, because the guard
blocked 3 (below).

### 8 Oct, app against replay (the only day with Boss's fills)

ORB family (replay: the app's ladder with breakeven = entry + charges, resting stop moved up, fills at the trigger):

| app trade | replay trade | difference (Rs) | cause |
|---|---|---|---|
| ORB 11:31 759.18 → stop 714.19, -1,448 | 11:30 762.98 → lock 756.42, -298 | -1,150 | **entry 1 minute late**: the +10 rung was earned in the 11:30 minute, before the app bought |
| Sweep 11:41 825.15 → 842.85, +424 | 11:35 821.01 → 840.59, +481 | -57 | 6 minutes late: **one-index-one-side guard** (ORB's PE held BN until 11:36) |
| none | Range Fade 11:35 821.01 → 824.12, -12 | +12 | guard (ORB PE held) |
| Range Fade 12:05 835.95 → 838.66, -26 | 12:05 847.62 → stop 806.79, -1,330 | +1,304 | different entry price (the app's sampled price was 11.7 lower); its +10 rung then held |
| Sweep 12:21 833.00 → 792.21, -1,328 | 12:20 827.21 → 786.41, -1,328 | 0 | match |
| none | ORB 12:25 PE, -286 | +286 | guard (Sweep CE held) |
| ORB 12:38 773.70 → 779.87, +83 | 12:35 783.19 → 786.23, -12 | +95 | 3 minutes late |
| ORB 12:51 → target (est.), +1,096 | 12:50 → target, +1,084 | +12 | match |
| ORB 13:05 767.00 → 774.62, +126 | 13:05 763.38 → 772.99, +186 | -60 | match |
| ORB 13:15 795.85 → target (est.), +1,094 | 13:15 780.84 → breakeven, -78 | +1,172 (uncertain) | entry 15 points higher; the exit is not in Boss's paste (lock report: it may have been a breakeven exit) |
| none | ORB 13:25 +484, 13:35 -12 | -472 | **Liquidity priority**: Liquidity held BN 13:20-13:40, so ORB was refused (63f9e0a) |
| ORB 13:40 792.40 → 853.90, +1,738 | 13:45 821.31 → 840.89, +481 | +1,257 | with no 13:35 trade the app decided a bar earlier and caught the run; the target was a market sell **21.5 points above +40** |
| ORB 13:55 835.35 → 883.85, +1,346 | 13:55 843.12 → lock 862.69, +479 | +867 | the replay's minute low touched the +20 lock; the app's ticks did not, and its target sold 8.5 points above |
| **ORB family +3,104** | **-162** | **+3,266** | |

Liquidity 15+5 (replay: 1-ITM + room rules):

| app trade | replay trade | difference | cause |
|---|---|---|---|
| BN 09:22 54900PE 694.20 → 668.10, -878 | 09:20 719.06 → 696.50, -774 | -104 | 2 minutes late; same trade |
| MIDCP 09:2x 13675PE 219.20 → 238.75, +2,232 | 09:20 213.36 → 225.59, +1,357 | +875 | same trade (confirms the inferred strike); the app's exit came later and higher |
| BN 11:31 54800PE 705.00 → 699.70, -256 | 11:30 719.11 → 680.66, -1,250 | +994 | the index stop was acted on a pass later, after a bounce (luck of the 60-s pass) |
| FIN 11:3x 24750PE 357.80 → 336.95, -1,347 | 11:30 366.18 → 338.53, -1,755 | +408 | same trade |
| BN 12:38 54800PE 731.60 → 735.55, +19 | 12:35 738.27 → 735.78, -174 | +193 | 3 minutes late; same trade |
| BN 13:20 54700PE 714.50 → 699.40, -550 | 13:20 717.66 → 681.61, -1,178 | +628 | index stop acted on later, at 13:40 after a bounce |
| none | BN 15m book 13:30 54700PE, -1,559 | +1,559 | **the app's guard allows one BN position**; the replay stacks the 15m and 5m books |
| **Liquidity -780** | **-5,332** | **+4,552** | |

**What the mismatches are, and what they are not:**
- **Not a broken app.** Every replay trade has its app twin within 0-6 minutes, at the same strike, with the same
  exit reason. The extra trades on either side are explained by the guard and the priority rule.
- **Entry latency** (1-3 minutes, once 6) moves single trades by up to Rs 1,300 either way. On history it costs money
  on average (part 2, change 2).
- **The guard and the priority rule** remove trades the replay counts. For ORB that helps on average, because its
  trades lose about Rs 100 each. For Liquidity it costs: one automatic position per index removes 86 BN trades worth
  +Rs 15.9k before the holdout, and 30 worth +Rs 17.7k in it. So the app's Liquidity BN is a **+73 / +172 a day** arm,
  not the +89 / +244 of h19's table. h36's Plan A (+65 / +167) already matches the guarded figure.
- **Exits on the 60-second pass** (Liquidity's index exits until today's Fix 3) were lucky today (+1.6k). They are
  noise in both directions.
- **Target sells after the cross.** The app sells at market once it sees +40. Over 1,932 ORB-family targets in the
  replay, the target minute closed on average **+2.0 points** above the +40 level (median +0.5), worth about +Rs 60
  a target trade. That is a small, real tilt in the app's favour on paper. It does not change the arms' sign.
- **Stale prices and feed drops** (Paper.kt:134) are the cause of the Pine 13:31 fill. They did not move any ORB or
  Liquidity trade by more than a few points today.
- **2 lots on 7 Oct.** `LiquidityLots.DEFAULT = 2` (Boss's 6 Oct choice, applied to any book with no saved size). The
  paper test then counts that trade's -3,922 as one trade. At 1 lot it would have been about -1,961. The replay's
  1-lot twin is BN 10:30 55100CE, index stop, -2,323.
- **6-7 Oct day split.** The totals match, but the app's Liquidity lost about Rs 5.4k on 6-7 Oct and only 780 on
  8 Oct; the replay lost about 0.6k and 5.3k. Without the 6-7 Oct trade log (the "who placed it" export) the cause
  cannot be pinned down. The likely sources are the 2-lot size, latency and stale fills.

### Is it luck? (`luck.py`; 5-year replay, rolling windows of consecutive trades and iid bootstrap)

| arm (forward) | share of backtest stretches at least this good / bad: all 2021-26 · holdout · last 6 months | bootstrap | backtest 5-95% band for that many trades (holdout) |
|---|---|---|---|
| ORB, 30 trades ≥ +5,068 | 1.8% · 2.9% · 2.4% | 0.6-2.3% | -11,068 to +3,691 → **forward is above the band** |
| ORB, 30 trades won ≥ 60% | 3.5% · 2.8% · 2.6% | | |
| ORB, replay of the same 30 trades ≥ +839 | 11.5% · 14.1% | | |
| ORB Fresh, 3 trades ≥ +2,427 | 0.8% · 2.8% · 0.8% | 0.2-1.9% | |
| ORB Sweep, 6 trades ≤ -4,498 | 5.8% · 14.7% · 15.7% | 2.6-12% | |
| Range Fade, 6 trades ≤ -1,256 | 36% · 43% · 37% | 37-45% | |
| Liquidity BN+FIN, 20 trades ≤ -6,186 | 9.8% · 12.3% · 19.1% | 11-18% | -9,670 to +23,761 → **inside the band** |
| the same at 1 lot (≤ -4,225) | 17.5% · 20.4% · 27.8% | 20-25% | |
| Liquidity, 20 trades won ≤ 30% | 44% · 29% · 27% | | |

### The app's paper test cannot tell a winner from a loser at 15-60 trades

`Vetting.judge`: 15 trades or more; "held up" if net > 0, profit factor ≥ 1.2 and the worst run ≤ 60% of the gains;
"failed" if net ≤ 0. I applied it from 3,000 random start points in each arm's 5-year replay:

| arm (5-year net per trade) | first verdict "held up" | first verdict "failed" | "held up" at exactly 60 trades |
|---|---|---|---|
| ORB (-101) | 19-23% | 77-81% | 4-5% |
| ORB Fresh (-103) | 17-31% | 69-83% | 1-2% |
| ORB Sweep (-176) | 14-21% | 79-86% | 0-2% |
| Range Fade (-148) | 13-31% | 69-87% | 0-1% |
| Liquidity BN (+147) | 47-63% | 37-53% | 45-66% |
| Liquidity BN+FIN (+147) | 45-50% | 50-55% | 52-53% |

A 15-trade test is a coin flip for Liquidity and gives a losing arm a 1-in-5 chance of "held up". Even 60 trades
separate them only partly, because Liquidity's edge is small against its swings: a 60-trade stretch of BN ranges from
-14k to +36k (holdout 5-95%). This matches h19's power estimate: about 35 days to show ORB's loss, and many months to
confirm Liquidity's edge.

## 2. What more could be done (each idea checked on history, not on today)

`history.py`, `thin_delay.py`, `thin_today.py`. Pre = Aug 2021 - Sep 2025, holdout = Oct 2025 - 6 Oct 2026. Rupees at
1 lot, app fills and charges.

**Change 1: the paper test.** Recommended. In `Vetting.kt` / `IraExpert`:
- Count each trade's rupees **per lot**.
- Need **60 trades and 40 trading days** before any verdict (h19's PassRule).
- Show the forward sum **next to the arm's backtest 5-95% band for the same number of trades**. Three outcomes:
  "in line with the backtest", "worse than the backtest: check the app", "better than the backtest: check the
  fills".
- **Never say "held up" for an arm whose 5-year replay is red** (ORB, Fresh, Sweep, Fade, the known Pine scripts,
  Solo's learner).

Today the band would have said:
- ORB: "better than its backtest (top 2%): check the fills", not "held up".
- Liquidity: "in line with its backtest", not "failed".

**Change 2: entry latency.** Recommended (bug fix). Arm entries run only in the full watch pass (`Jobs.kt:624`
`OrbArms.tick`), which repeats every pass time + 60 s. Today's Liquidity buys came 1-3 minutes after the bar closed.
On the replay's own trades, the cost of buying later at the later minute's open, with the exits unchanged (they are
index events):

| | 1 minute late, Rs/trade (t) | 2 minutes late |
|---|---|---|
| BANKNIFTY, pre / holdout | -48 (-2.8) / -51 (-1.6) | -37 / -80 |
| FINNIFTY, pre / holdout | -45 (-2.8) / -81 (-2.4) | -31 / -75 |
| MIDCPNIFTY, pre / holdout | -132 (-5.0) / -144 (-3.4) | -121 / -143 |

The median is near 0. The mean is pulled by the breaks that run away, which are exactly Liquidity's winners. Fix:
wake an entry-only check a few seconds after each 5-minute boundary for Liquidity (and the ORB arms, for an honest
record), using the stream or a fresh quote. Combine it with h14's limit entry (+0.5%, resting 3 minutes).

**Change 3: separate records for BN, FIN and MIDCP Liquidity.** Recommended. Evidence:
- h24: half-spreads are BN 0.16%, MIDCP 0.21% and FIN 0.42%.
- h36: BN alone makes +65 / +167 a day; MIDCP adds about zero and doubles the drawdown.
- h44: the meta-model's only gain came from skipping FIN and MIDCP.
- Today's replay: BN -6,440, FIN -1,980, MIDCP +2,457.

**Change 4: 1 lot on paper.** Recommended. `LiquidityLots.DEFAULT` is 2. Boss already trades 1 lot (8 Oct fills),
but any reset book goes back to 2.

**Change 5: Jarvis's lessons.** Recommended (turn them off under 30 trades, or check each against the replay).
History says the opposite of two of today's lessons:

| lesson (trades it was drawn from) | 5-year replay, pre | holdout |
|---|---|---|
| "Liquidity loses entered 09:00-10:00" (6) | before 10:00 **+188/trade** (500) vs later +84 (735) | **+317** (169) vs +132 (247) |
| "ORB loses 13:00-14:00" (9) | -97 vs -96 elsewhere | -123 vs -122 |

**Tested and rejected:**
- **Pausing Liquidity after a bad stretch** (Jarvis's request, as a rule): pause K = 5/10/20 sessions once the last
  N = 10/15/20 trades lost ≥ 3k/5k/7.5k. **All 27 versions lose money in both periods**: -0.2 to -108 Rs/day before
  the holdout and -0.5 to -376 in it, against a base of +160 / +351. The losing stretches are followed by the
  rebounds.
- **The ThinOption gate on Liquidity's buys** (≥ 4 of 6 minutes traded, ≥ 25 lots):
  - BN: it almost never fires.
  - FIN: it skips 3% of trades before the holdout and 52% in it. Those skipped trades were winners in the replay
    (+17.7k and +17.0k).
  - MIDCP: it skips 4% / 13% of trades, worth +20.4k / -3.1k.
  - Read with care: winners in thin options are the fills least likely to be real (h24: MIDCP's big winners fill only
    when the spread is ≤ 0.3%). So this is no reason to add the gate, and also no proof those profits exist. Change 3
    (separate records) is the honest answer.
  - Today it would have skipped only the Pine 13:31 trade (-2,285); it already applies to Pine.
- **The one-index guard for Liquidity** (allowing the 15m book to stack on the 5m book) would add +16-18k per period
  but doubles the position. It is not risk-lowering, so it is information only. The forward expectation should use
  the guarded +73 / +172 a day.
- **Day locks, a Solo trail, capping ORB re-entries, premium-point exits on Liquidity**: already rejected (lock
  report, h19, h21, h34, h45).

## 3. What the other studies add to today's reading (H1-H45)

- **ORB family (h19-h21, h35, h43):** lost Rs 9.8 / 2.5 / 2.9 / 2.7 lakh at 1 lot over 5 years. No smart filter,
  lock or target fixes them (180 trials). The outside bake-off's ORB versions are 25-day luck too. A +5k week is what
  a noisy loser shows about once in 50 stretches.
- **Liquidity (h4, h7, h10, h13-h17, h22, h24, h36, h44, h45):** the only real edge, small. It makes +65 / +167 a day
  for BN at 1 lot after the real spread, loses 4-5 months in 10, and has drawdowns of 32-40k. It is not improved by
  point targets (h34), a ±5% bracket (h45), meta-labels (h44), OI or FII filters (h26, h40), or pauses (above).
  Its forward record should look like today's roughly one stretch in five.
- **Which arm makes which day (h22):** the big paper days come from one or two runaway trades (1 Oct: Liquidity's
  first-day rules; today: Solo +4,199 and ORB's 13:40-13:59 runs). The forward record is dominated by a few trades,
  which is why 20-30 trades cannot judge an arm.
- **Advance warning (h26, h27, h31, h40, h41):** flows and OI tilt the next gap, not the day. Today's FII selling and
  RBI hike were known and priced. A trend-down day was not predictable from them.

## Files

- `research/hunt/x1/fetch.py`: Dhan minutes for 6-8 Oct (credentials read at run time, never printed).
- `research/hunt/x1/overlay.py`: an overlay data tree so obuy / h19 code replays the new days unchanged.
- `research/hunt/x1/replay.py`, `replay_midcp.py`: the arms on 1 Sep - 8 Oct 2026.
- `research/hunt/x1/window.py`: the paper-test window against the replay.
- `research/hunt/x1/luck.py`: windows, bootstrap, Vetting false-pass/false-fail.
- `research/hunt/x1/history.py`: stacking, lessons, pause rule, target overshoot.
- `research/hunt/x1/thin_delay.py`, `thin_today.py`: ThinOption on Liquidity, entry latency.
- Outputs are in `scratchpad/hunt/x1/` (`replay_trades.csv`, `replay_midcp.csv`, `luck.json`, `thin_delay.csv`,
  `raw/`, `data/`).

## Limits

- **Only 8 Oct has Boss's fills.** For 6-7 Oct only the paper-test totals are known, so the per-trade comparison is
  for 8 Oct. Two of the ORB exits on 8 Oct are estimates (lock report).
- **The 6-Oct start of the paper tests is inferred** from the trade counts.
- **MIDCPNIFTY's history here uses app fills without the real spread.** The h24 / h36 figures, with the real spread,
  are the ones to plan on.
- **Pine and Solo cannot be replayed:** the scripts on the phone are not in the repo, and Solo's NIFTY weekly is not in
  Dhan's history.
- **Dhan's minutes are not the app's feed.** Single fills can differ by a few points.
