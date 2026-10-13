# R19c pre-registration: consistency, not total P&L (Boss's +Rs 1,000 / Rs 500 profit floor, and day rules)

Written 13 Oct 2026, BEFORE any result of these tests was computed. This file is committed on its own before the first
run; its commit hash is quoted in the write-up (`research/HUNT_R19C_CONSISTENCY.md`). Nothing below may change after
that commit. A change needs a new entry, a new pre-registration and a fresh holdout.

R19c reuses R19b's Liquidity simulator and data build (`research/hunt/r19b`) and R17's arm simulators and data
(`research/hunt/r17`). It does not reuse any of their results to choose anything here: every number below is Boss's own
or fixed in this file.

## Boss's words (13 Oct 2026)

> "I don't want Rs 10k from one trade; Rs 1,000 is enough for me. If we add a stop loss after Rs 1,000 (at the highest
> point after Rs 1,000), I will always get a minimum of Rs 500. I face this on all the strategies that have no profit
> lock. My whole portfolio goes red because of 2-3 orders. If you think this is not good, suggest the best strategy where
> I will not lose money."

## The objective (fixed): CONSISTENCY

Not total P&L. A change is better when it gives:

1. more green days (a larger share of the days with at least one trade that end with net P&L > 0);
2. a smaller (or equal) worst day and a smaller (or equal) maximum drawdown of the daily equity curve;
3. fewer "was in profit, ended in loss" trades (reported; not a gate);
4. while net P&L after costs stays positive and gives up at most X = 30% of the plain-rules net.

## Definitions (fixed)

- **Open P&L per lot** = (option price - entry fill) x the lot in force that day. Gross (no charges), as the open P&L
  Boss watches. Per lot, so the number of lots never changes the rule (Hero buys several lots: the rule is per lot).
- **Peak** = the entry fill, then the highest 1-minute HIGH since the entry. A minute with no trade adds nothing. A peak
  earned in a minute counts from the NEXT minute (`ProfitLock.nextPeak`).
- **Floor F(A, G)**: once the peak is at least A rupees per lot above the entry fill, a resting sell stop sits at
  `peak - G / lot` on the 0.05 tick rounded up (`ProfitLock.onTick`). It only rises. With A >= G + 500 (Boss's rule),
  it is never below +Rs 500 a lot once active. It only ADDS an earlier exit: every other exit of the arm is unchanged.
  Filled like the arm's own resting stops in each reused simulator (at the trigger, or at the open on a gap, less the
  stop slippage that simulator uses).
- **Day**: a session on which the arm (or the portfolio) had at least one trade. Day P&L = the sum of the nets of the
  trades entered that day (every arm here is intraday). Green day: day net > 0.
- **Max drawdown**: the deepest fall of the cumulative daily net from its running best (starting at 0).
- **"Was in profit, ended in loss"**: a trade whose peak reached +Rs 1,000 a lot and whose net was < 0.

## Variants per arm (fixed; 6 or fewer)

| id | rule |
|---|---|
| CTRL | the arm's rules today |
| F1000_500 | **Boss's rule**: from +Rs 1,000 a lot, sell Rs 500 a lot below the peak |
| F750_500 | from +Rs 750, Rs 500 below the peak |
| F1500_500 | from +Rs 1,500, Rs 500 below the peak |
| F1000_300 | from +Rs 1,000, Rs 300 below the peak |
| F1000_700 | from +Rs 1,000, Rs 700 below the peak |
| R1000_500 | ORB family only: Boss's rule INSTEAD of the ProfitLock ladder (the F variants there are added ON TOP of the ladder, the higher stop wins) |

## Arms (the ones with no profit lock today, plus the laddered ORB family for comparison)

| arm | index | simulator reused | notes |
|---|---|---|---|
| Liquidity 15m, Liquidity 5m | BANKNIFTY | R19b `sim.walk` (engine `LiquidityRules`) | |
| ORB, ORB Fresh, ORB Sweep, Range Fade | BANKNIFTY | R17 `arms` signals, contract choice, fills; exit loop re-written here with the ladder on / off | fixed 40-point stop (Sweep target 80) |
| VIX divergence | BANKNIFTY, NIFTY | R17 `arms` | two arms |
| Hero (expiry) | NIFTY | R17 `arms` (Rs 5,000 budget, several lots) | very few trades: reported, gated like the rest |
| Solo (midday) | NIFTY, BANKNIFTY | ported here from engine `SoloMidday` | FINNIFTY has no option minutes in the release: Solo picks the stronger of the two |
| Night (R3) | - | not run | its breadth (all F&O stocks) and OI build-up inputs are not in the release; it holds 15:20 -> 09:16, so a floor has about 11 open minutes to act |
| MCX paper arms | - | not run if no MCX option/futures minutes are found | |
| MIDCPNIFTY | - | not run if no MIDCPNIFTY option minutes are found in the release or the IraAlgo data repo | Dhan API not called |
| Pine auto-trade | - | not run | every Pine strategy already carries a compulsory profit lock (06 Oct) |

The R17 simulators were built with an expected-range warm-up (first 60 sessions skipped); R19c runs every session
(the expected range is not used). Each arm is 1 lot (Hero Rs 5,000). Costs: each reused simulator's own fills and the
app's charges at today's rates.

## Periods (fixed)

One cut for every arm and the portfolio: **design before 1 Apr 2025, holdout from 1 Apr 2025**.
- BANKNIFTY: design 13 Feb 2024 - 31 Mar 2025; holdout 1 Apr 2025 - 23 Feb 2026.
- NIFTY: design 1 Apr 2024 - 31 Mar 2025; holdout 1 Apr 2025 - 13 Apr 2026.

Honest note: no window of the release is unseen. Parts of this holdout were read by earlier studies (R19b read
Liquidity Mar - Aug 2025 with a +Rs 500 / Rs 500 trail; R12, R14, R16, R17 read Aug 2025 on). R19c's rules were fixed by
Boss's words and this file, not chosen on any window, and the holdout is read ONCE for R19c (a flag file in the cache).
"Both years" in the portfolio part means the design year and the holdout year.

## Gates for each (arm, floor variant) (all must pass; on BOTH design and holdout for 1 and 2)

1. **Daily.** Green-day share strictly higher than CTRL's, AND worst day not worse, AND max drawdown not worse (Rs 1
   tolerance for rounding).
2. **Net.** Net after costs > 0, AND net >= 70% of CTRL's net when CTRL's net is positive (X = 30%).
3. **Holdout significance.** One-sided exact sign test on the holdout days whose colour changed (red/flat -> green
   counts for, green -> red/flat against). Benjamini-Hochberg over every (arm, variant) and portfolio hypothesis in this
   file; pass at q <= 0.10. At least 30 holdout trades, else "inconclusive" (cannot pass).
4. **Random twin.** On CTRL's holdout entries (each trade held on its own), every trade the floor sold early is instead
   sold at a uniformly random minute between the minute the floor became active and CTRL's exit (market fill), all
   other trades as CTRL. 1,000 twins. The variant's net must beat at least 95% of them.

## Portfolio part (fixed)

Portfolio = every arm above that runs, 1 lot each, CTRL rules, summed per day.

| id | rule |
|---|---|
| P0 | everything, plain rules (the baseline) |
| P1 | ORB, ORB Fresh, ORB Sweep and Range Fade switched off |
| P2 | day loss cap -Rs 2,000: once the day's realised + open P&L is <= -Rs 2,000, no new entry that day (open trades keep their own exits) |
| P3 | day loss cap -Rs 3,000 |
| P4 | day profit lock: once the day's realised + open P&L reaches +Rs 2,000, if it later falls to +Rs 1,000 every open trade is sold at that minute (market) and no new entry that day |
| P5 | P1 + P4 |
| P6 | P1 + P2 |
| P7 | P1 + P2 + P4 |

Day P&L is checked on each minute's close (open trades marked at the option's close less the exit charges at that
price). The same gates 1-3 apply on the portfolio's daily series (design and holdout), with CTRL = P0. Gate 4 for
P2-P4: the same number of days, chosen at random among the traded days, get the same action (no new entry / sell all)
at a random minute between 09:30 and 15:00; the rule must beat 95% of 1,000 twins on net. P1 is not an exit rule: no
twin. P5-P7 use the twin of their day rule on top of P1.

## Look-ahead guards (fixed)

- The floor reads only the peak from minutes before the one checked.
- The day rules read only minutes up to the one checked.
- A test truncates the option minutes after a trade's exit and checks the decisions before it do not change.

## Reported, not gated

Rs 1,000 a lot in option points for each index and lot; each arm's "in profit, ended in loss" count; big winners cut;
Boss's rule on today's MIDCPNIFTY trade (13:20, entry 195.45, peak about 216.3, lot 120) as an illustration only.

## What passing means for the app (CLAUDE.md, 13 Oct)

A floor variant or day rule that passes every gate may act on PAPER at once (logged in the Autopilot change timeline,
undoable in one tap, each trade labelled "plain" or "with floor"); live stays unchanged. Anything that fails stays a
note; at most an OFF-by-default "your choice" paper switch labelled with its result.
