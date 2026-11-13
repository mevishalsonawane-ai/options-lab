# R168 pre-registration: the app's own ORB arms with 10-15 point exits

Written 13 Nov 2026, **before any R168 number was computed** (committed and pushed on `claude/r168-orb-10-15` ahead of
the code's first run). Research only; nothing in `android/**` changes.

Boss, 13 Nov: *"Try ORB with 10-15 points only."*

**Signal source for every rule: the index** (CLAUDE.md default). Every entry is read from 5-minute bars of the index spot
(built from its 1-minute bars); the option bought is the ATM option of that index held all day (the arms' strike rule).
Reading A puts the target and stop on the option's premium (CLAUDE.md allows stops and targets on the premium); reading B
puts them on the index. No rule reads its signal from an option's own chart.

## 0. What is already known (cited, not re-run)

- **R154** (`research/hunt/r154_scalp_10_15/`): a plain first-15-minute range break (E1 OR15, 1-minute closes, from
  09:31) with the same 24 scalp exits lost on every index. NIFTY A10s10t15: walk-forward 331 trades, 49% won, -Rs 27,381;
  final 177 trades, 41%, -Rs 28,181. 0 of 792 versions passed. **R155** added 20 filters: none fixed it. R168 does NOT
  repeat E1.
- **R14** (`research/HUNT_R14_REENTRY_CHURN.md`) and **H20** (`research/HUNT_H20.md`): the app's ORB, ORB Fresh and ORB
  Sweep lose with their own exits on real BANKNIFTY option minutes (Aug 2021 - Oct 2026). H20 tried 24 other exits
  (smaller targets and tighter locks included): none worked.
- **R106 / R152 / R154**: small targets on the R72, R51 and Liquidity arms made less than the arms' own exits.

What is new in R168: the **app's own ORB arms' exact entry rules** (not E1), with only their exits replaced.

## 1. The arms, as the app has them today (`android/engine/.../orb/OrbRules.kt`, `SweepRules.kt`, `ReentryCap.kt`)

All three share ONE opening range: the high / low of the 5-minute index bars labelled 09:15 .. 10:00 (the range covers
09:15:00 - 10:04:59, 50 minutes; it exists once the 10:00 bar has closed). **None of the app's ORB arms uses a 5, 15 or
30-minute range**: those lengths were research variants (R22 / R23 / R92), never arms. The day is skipped (as h20 / R14)
when more than 2 of the 10 range bars have no print or the 09:20 bar has no close.

| arm | decides on (5-min bars, labelled) | entry (on the bar's close; filled at the next minute's open) | taking rule |
|---|---|---|---|
| **ORB** | 10:05 .. 13:55 | close above the range high buys the CE; close below the low buys the PE | one position at a time; after an exit the next decision bar must START after the exit's 5-minute bar; **at most 1 entry a side (CE / PE) a day** (R14's cap, the arms' paper default since 13 Oct) |
| **ORB Fresh** | 10:05 .. 13:55 | as ORB, and the bar before must NOT already be beyond the same edge (a fresh break) | as ORB |
| **ORB Sweep** | 10:05 .. 14:25 | high above the range high and close back below it buys the PE; low below the range low and close back above it buys the CE | one position at a time, the same cool-down, **at most 2 entries a day** |

- Strike: ATM from the close of the 09:20 bar, half-up to the index's strike step (BANKNIFTY 100), held all day.
- Expiry: the nearest listed expiry ON or after the day (the same-day option on an expiry day: `OrbRules.expiryOnOrAfter`);
  the lake's WEEK series is exactly that.
- A buy whose real fill (with R17 slippage) is 40 or less is refused (the -40 stop has no level; as the arms and R14 / h20).
- A decision bar with no print gives no signal.
- **Index**: the arms trade BANKNIFTY only. The SAME rules (with that index's strike step) are also run on NIFTY,
  FINNIFTY, MIDCPNIFTY and SENSEX: labelled "same rules, not an app arm" and gated / counted exactly like BANKNIFTY.

**B0 = each arm's own exits (the baseline)**: one resting stop at max(floor_tick(fill - 40), the profit lock); the lock
ladder on the arm's target (ORB / Fresh +40: +10 -> breakeven, +20 -> +10, +30 -> +20; Sweep +80: +20 -> breakeven,
+40 -> +20, +60 -> +40), the breakeven floored at the fill plus the round trip's charges per unit, earned on minute HIGHS
and active from the next minute (a rung counts only below the best price), filled at its level or the gap open (R17 stop
fill); the target is the app's check on the minute high (+40, Sweep +80), sold at the NEXT minute's open (R17 market
sale); square-off 15:10 (15:05 on expiry days), sold at that minute's open.

## 2. Data, engine, fills and charges (R154's, unchanged)

- Lake `gdrive:IraAlgo-data-lake`, copied 13 Nov 2026 (`index_1m`, `index_daily`, `options_rolling_1m` WEEK). From 20 Nov
  2024 R51's Amendment A clean stores; before that R154's early store (`r154data.build_early`, the same code). Every trade
  is priced on the ONE contract bought.
- Scalp pricing: `r154engine.price` with the strike given (R106's numba simulator, R17 fills, dated Zerodha charges,
  dated lot sizes; a stop and a target in one minute count as the stop). B0 pricing: a small simulator in R168 using the
  same R17 fill functions and charges (`engine.SLIP`, `engine.charges`).
- Expiry days: R51's straddle-time-value flag (as R154).
- Square-off for every scalp rule: **15:15, 15:05 on expiry days** (Boss's ask, CLAUDE.md).

## 3. Exit rules (fixed now; R154's 24): 2 readings x 3 sizes x 2 stops x 2 time stops

| reading | target | stop | time stop |
|---|---|---|---|
| **A: premium points** | a resting limit at the real buy fill + P | a resting stop at the real fill - S | T minutes after the fill |
| **B: index points** | the index +P in the trade's favour from its level at the fill (sold at the next minute's open) | the index S against | as A |

P in {10, 12, 15}; S = P (1:1) or 1.5 x P (1:1.5); T in {15, 30} minutes; plus the square-off. Rule ids as R154
(`A10s10t15` ... `B15s22.5t30`). The arm's taking rule runs on each rule's own exits (a shorter trade frees the arm sooner:
the cool-down and the caps then decide).

Tests: 3 arms x 5 indices x 24 exits = **360** (at 1 lot). Lots 1, 2 and 3 (qty = lots x the dated lot size) are shown for
every rule and for B0; 1 lot is the gated test.

## 4. Parity with the published figures (stated, not a gate)

Published (Dhan minutes through `research/obuy`, the app's charges at TODAY's rates for every year, h20's "real" fills:
+-5 bps, stops -10 bps, plus a 1-4 tick half-spread from volume):

| arm | design / pre window | holdout window (1 Oct 2025 - 5 Oct 2026) | source |
|---|---|---|---|
| ORB (cap 1 a side) | 4 Aug 2021 - 30 Sep 2025: 962 trades, -Rs 96,740 | 246 trades, -Rs 18,439 | R14 R1 |
| ORB Fresh (cap 1 a side) | the same 962 / -Rs 96,740 | 246 / -Rs 18,439 | R14 R1 |
| ORB Sweep | 996 sessions to 30 Sep 2025: 1,330 trades, -Rs 1,94,184 | 331 trades, -Rs 78,962 | H20 fixed |

R168 reruns B0 on those windows twice: (i) **parity mode** (obuy's app charges at today's rates and its "liq" fills), and
(ii) R168's own mode (dated charges, R17 fills). Parity target: trades within 5% and net within 15% in parity mode. The data
differ (the lake's rolling WEEK file, not obuy's Dhan pull), so exact parity is not expected; any miss is reported with its
cause. B0 in R168's own mode is the baseline the scalps are judged against (same data, same fills, same charges).

## 5. Periods (R154's)

- **P1 design**: the first option day (Aug 2021; MIDCPNIFTY Jan 2023; SENSEX May 2023) to 31 Mar 2025.
- **P2 walk-forward**: 1 Apr 2025 - 31 Mar 2026. Nothing is fitted; every rule is its own out-of-sample run. Also a
  walk-forward pick (descriptive, never gated): per arm x index, each quarter and the final take the exit with the best
  1-lot net on all earlier data.
- **P3 final**: 1 Apr - 13 Oct 2026, read ONCE (`results/FINAL_READ`). **Weak evidence: this window was already read by
  R14 / H20 (the ORB arms' holdout, Oct 2025 - Oct 2026), R51 - R167 and SCALP17.**
- P4 (14 - 19 Oct) is NOT run: its stores are not rebuilt here (4 sessions; never gated in R154 either).

## 6. What is reported (per arm x index x exit rule, each period; lots 1 / 2 / 3)

Trades, win rate, break-even win rate (average net loss / (average net win + average net loss)), net, per session, worst
day, max drawdown (daily), net without the 5 best trades, exit mix, average charges per trade; the same for B0; twin p and
BH q; the verdict. A charges table (round trip per trade and the premium points it eats, by index and lots).

## 7. Gates (all must hold, at 1 lot; never relaxed)

1. Net > 0 in P1, in P2 and in P3.
2. Beats its random-exit twins: P3, 500 twin sets (seed 168); each twin keeps the rule's own trades and sells each instead
   at the open of a uniformly random minute strictly after its fill and no later than the end of its time-stop window;
   p = (1 + #twins with total net >= the rule's) / 501. BH q <= 0.10 with every earlier test counted at p = 1:
   R167's tally of 2,664 (R154 - R167 included) + R14's 10 + H20's 24 ORB-family tests = 2,698, plus R168's 360:
   **m = 3,058**.
3. At least 40 trades in P3.
4. Net above the arm's own exits (B0, same arm and index, 1 lot) in P2 AND P3.

## 8. What a pass means (CLAUDE.md)

A passing rule may act on PAPER straight away (labelled, logged in the Autopilot change timeline, one-tap undo, shown to
Boss). Nothing goes live from R168; anything new on live trades starts in shadow behind a switch that is off by default.
The ORB family is parked today (13 Oct, `FamilyPark`); R168 changes nothing in the app either way. Every failed rule stays
a note here and in `research/experiments/entries/r168-*.yaml` (one entry per arm x index x reading, 12 exit rules each).

## 9. Tests

`test_r168.py` (pytest): the opening range and its day filter, each arm's signal on hand-built bars (ORB break, Fresh's
"not a fresh break", Sweep's sweep of high / low), the decision windows, the strike rule (half-up), the **look-ahead guard**
(every arm's signals up to bar k are unchanged when every minute after bar k is erased), the taking walk (one at a time,
the cool-down, 1 a side, 2 a day), the B0 simulator (stop, lock rungs from the next minute, target sold next minute,
square-off, gap through a stop), the premium <= 40 refusal, the parity-mode charges, stats / break-even / BH.

## Amendment 1 (13 Nov 2026, before any number; the first hash is in `results/PREREG_HASH`)

Read in `research/obuy/engine.py` while coding: h20 / R14 measure the -40 stop, the lock rungs, the +40 / +80 target and the
premium <= 40 refusal from the REAL buy fill (with slippage), as the app does from its paper fill. B0 does the same (the
"fill" in section 1 is the real fill). Nothing else changes.
