# R19b: Boss's Rs 500 give-back trail for Liquidity ("down Rs 500 from its highest point, sell it")

Written 13 Oct 2026 for Boss. The pre-registration is `research/hunt/r19b/PREREG.md`. It was committed on its own
(be04d3d) before any test ran. The simulator, the data build and the design results were committed (69faf9d) before the
holdout was read. The holdout was read once, guarded by a flag file in the cache (read 13 Oct 2026 08:19 UTC).

R19b is separate from R19 (`claude/liquidity-lock`, the target-relative profit locks). Neither used the other's results.

- Code: `research/hunt/r19b/` (`sim.py` the engine-exact simulator, `data.py`, `run.py`, `test_r19b.py`).
- Results: `r19b/results/design.md` / `.json`, `holdout.md` / `.json`.
- Experiment entry: `research/experiments/entries/r19b.yaml`.
- Raw data stays out of git. The Dhan API was not called.

## Verdict (read this first)

**Boss's rule FAILED. So did every pre-registered sibling, on both books.** It is not added to the app's rules.

- On the locked holdout (3 Mar - 22 Aug 2025, 118 sessions), Boss's rule exactly (A1) made the Liquidity BANKNIFTY
  books earn **less**, not more:
  - 15-minute book: +Rs 4,584 against +Rs 14,633 on plain rules (n = 27 trades): **-Rs 10,048**.
  - 5-minute book: +Rs 2,968 against +Rs 22,124 (n = 83 trades): **-Rs 19,156**.
- It **cut 12 of the 13 big winners** (trades that made at least Rs 2,000 a lot on plain rules). Those 12 gave up
  Rs 62,175 between them.
- It was **worse than random early exits**: cutting the same number of trades at a random minute did better than the
  trail in 97% (15m) and 99.8% (5m) of 2,000 tries.
- No BH q-value was below 0.92 (14 hypotheses). The same picture held in the design year (Feb 2024 - Feb 2025): every
  variant lost Rs 13k-52k against plain rules on each book.
- **MIDCPNIFTY was not tested.** No MIDCPNIFTY option minutes can be reached without the Dhan API (see "Data"). So none
  of this is evidence about MIDCPNIFTY, for or against.

Why it fails, in plain words: Liquidity makes its money from a few trades that run far (the next liquidity level, or
15:10). A BANKNIFTY option moves Rs 500 a lot (about 17 points at lot 30) inside an ordinary minute or two. So a
Rs 500 trail fires on most trades (43 of 83 in the 5-minute holdout), usually early, and the big runs are cut on their
first pullback. It raises the win rate (42% to 51%) but shrinks the average winner far more. This is the same lesson as
the percentage trails in LIQUIDITY_TRAIL.md (2024-26) and R12's trade manager.

**What changed in the app:** nothing in the arm's rules. As Boss asked for a failed result, an opt-in **"Boss's choice"
paper switch** is built and tested, **off by default**, labelled "failed research, your choice" (details below).

## Today's trade (an illustration only, never a test)

Liquidity 5m MIDCPNIFTY PE, entry 13:20 @ 195.45, lot 120. It peaked at about +Rs 2,500 (about 216.28) and was sold at
13:29 @ 188.25 on the index stop. The minute prices are not on hand, so this assumes the price fell steadily from the
peak and each trail filled at its trigger (before charges):

| variant | exit | why | P&L before charges |
|---|---|---|---|
| plain rules (what happened) | 188.25 | index stop | -Rs 864 |
| A1 Boss's rule (+Rs 500, then Rs 500 below the best) | 212.15 | trail | +Rs 2,004 |
| A2 the same from the entry | 212.15 | trail | +Rs 2,004 |
| B300 | 213.80 | trail | +Rs 2,202 |
| B750 | 210.05 | trail | +Rs 1,752 |
| B1000 | 207.95 | trail | +Rs 1,500 |
| P3 (3% of the premium) | 210.45 | trail | +Rs 1,800 |

On this one trade the trail would have helped, by about Rs 2,900. The history above shows why one trade is not enough:
the same rule gives back much more on the trades that would have run.

## Data and setup

| | |
|---|---|
| source | the `research-data` release, BANKNIFTY 13 Feb 2024 - 23 Feb 2026 (index minutes + the nearest expiry after the day, strikes within 1,000 points of the 09:20 index) |
| books | Liquidity BANKNIFTY 15m (`liquidity15`) and 5m (`liquidity5`), one position per book; FINNIFTY parked |
| MIDCPNIFTY | not tested: the release has BANKNIFTY and NIFTY options only; h4's MIDCPNIFTY option cache was in an earlier session's scratchpad (not in git, not in any release, not in the IraAlgo data repo) |
| rules | engine `LiquidityRules` exactly (a port of `liquidity_exits_3060.py`): pool-on-swing break, 1 ITM, room >= 30 points, 09:20-14:00, expiry days skipped; exits -15% resting stop, 30-point index stop, 20-minute +5% time stop, next level, failed break, new liquidity, 15:10 |
| the trail | checked after the -15% stop and before the peak update; the peak from the option's 1-minute highs, counting from the next minute; Rs per lot / lot = points (lot 15 / 30 / 35 by date) |
| fills | app paper fills plus a half-spread: market +-5 bps + 1 tick; resting stops (the -15% stop and the trail) -10 bps - 2 ticks at the trigger, or at the open on a gap. Sensitivity: the trail sold at the next minute's open (column "next-open fill") |
| charges | the app's model at today's rates (Rs 20 an order, STT 0.15% of the sell, exchange 0.03553%, SEBI, stamp, GST 18%); 1 lot |
| design / holdout | design 13 Feb 2024 - 28 Feb 2025 (258 sessions); holdout 3 Mar - 22 Aug 2025 (118 sessions); 25 Aug 2025 onward not used (already read as holdouts by R12, R14, R16, R17, h4) |

Caveat stated before the run: the arm's own exits were chosen on data that includes this holdout, so the CONTROL is
in-sample here. The trail is not: no rupee give-back trail had been run on any window.

## The variants (fixed in advance)

| id | rule |
|---|---|
| CTRL | today's rules |
| A1 | **Boss's rule**: once the open P&L per lot reaches +Rs 500, sell when it is Rs 500 a lot below its best |
| A2 | the same, active from the entry (the first trigger is Rs 500 a lot under the fill) |
| B300 / B750 / B1000 | A1 with a Rs 300 / 750 / 1,000 give-back |
| P3 | the same in premium percent: from +3%, sell 3% of the entry price below the best |

## Results

"Delta" is the variant's net minus plain rules', each book re-run (an earlier exit can take a later break). "MFE
captured" is on the control's own entries: the rupees kept over the best each trade reached. "Big winners cut": control
trades netting >= Rs 2,000 a lot that the variant sold earlier, and what they lost. Worst day and drawdown are daily, 1 lot.

### Design 2024-02-13 .. 2025-02-28 (258 sessions)

| book | variant | trades | win | net | Delta vs plain | next-open fill | MFE kept | big winners cut (Rs lost) | worst day | max DD | 3 folds | gate 1 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 15m | CTRL | 65 | 43% | +35,535 | - | - | 36% | of 9 | -1,831 | -4,129 | - | - |
| 15m | A1 | 65 | 51% | +7,469 | -28,066 | -30,160 | 11% | 7 of 9 (40,527) | -1,763 | -3,505 | -5,176 / -10,044 / -12,846 | no |
| 15m | A2 | 65 | 38% | +3,233 | -32,302 | -32,966 | 7% | 7 of 9 (41,453) | -824 | -3,641 | -5,904 / -10,825 / -15,572 | no |
| 15m | B300 | 65 | 60% | +7,155 | -28,379 | -29,760 | 11% | 8 of 9 (43,005) | -1,763 | -3,270 | -3,909 / -10,176 / -14,295 | no |
| 15m | B750 | 65 | 51% | +6,965 | -28,570 | -29,709 | 10% | 7 of 9 (39,145) | -1,831 | -5,892 | -7,579 / -9,789 / -11,202 | no |
| 15m | B1000 | 65 | 52% | +22,457 | -13,077 | -14,059 | 24% | 7 of 9 (23,591) | -1,831 | -6,512 | +951 / -6,441 / -7,587 | no |
| 15m | P3 | 65 | 55% | +11,431 | -24,103 | -26,046 | 14% | 7 of 9 (40,371) | -1,763 | -2,235 | -4,418 / -9,156 / -10,529 | no |
| 5m | CTRL | 161 | 39% | +38,975 | - | - | 26% | of 15 | -2,787 | -17,803 | - | - |
| 5m | A1 | 161 | 44% | -9,074 | -48,049 | -47,798 | 3% | 13 of 15 (61,873) | -3,857 | -16,711 | -15,957 / +1,411 / -33,503 | no |
| 5m | A2 | 162 | 35% | -6,125 | -45,100 | -47,820 | 4% | 14 of 15 (65,862) | -1,638 | -12,856 | -14,413 / +3,592 / -34,278 | no |
| 5m | B300 | 161 | 48% | -13,161 | -52,136 | -53,254 | 1% | 14 of 15 (65,381) | -2,661 | -19,859 | -15,045 / -3,541 / -33,550 | no |
| 5m | B750 | 161 | 43% | +1,452 | -37,523 | -35,929 | 8% | 11 of 15 (50,983) | -2,787 | -16,503 | -15,447 / +3,191 / -25,267 | no |
| 5m | B1000 | 161 | 43% | +23,506 | -15,469 | -12,249 | 19% | 9 of 15 (29,565) | -2,787 | -12,689 | -1,196 / +4,655 / -18,929 | no |
| 5m | P3 | 161 | 48% | -10,560 | -49,535 | -47,086 | 2% | 13 of 15 (61,681) | -2,787 | -16,535 | -16,277 / -1,239 / -32,020 | no |

No variant passed gate 1 (it needs Delta > 0 in 2 of 3 folds and overall). The folds: 13 Feb - 21 Jun 2024,
24 Jun - 25 Oct 2024, 28 Oct 2024 - 28 Feb 2025.

### Holdout 2025-03-03 .. 2025-08-22 (118 sessions, read once)

p: one-sided sign-flip on the daily Delta (20,000 flips); q: Benjamini-Hochberg over the 14; twin: the share of 2,000
random-early-exit twins the variant beat (needs 95%).

| book | variant | trades | win | net | Delta vs plain | next-open fill | MFE kept | big winners cut (Rs lost) | worst day | max DD | p | BH q | twin | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 15m | CTRL | 27 | 44% | +14,633 | - | - | 30% | of 3 | -2,985 | -6,729 | - | - | - | - |
| 15m | A1 | 27 | 67% | +4,584 | -10,048 | -9,438 | 13% | 3 of 3 (21,647) | -1,799 | -1,799 | 0.70 | 0.92 | 3% | fail * |
| 15m | A2 | 27 | 48% | -288 | -14,920 | -16,510 | 5% | 3 of 3 (22,594) | -922 | -2,725 | 0.78 | 0.92 | 0.2% | fail * |
| 15m | B300 | 27 | 63% | +4,724 | -9,909 | -10,120 | 13% | 3 of 3 (21,969) | -1,799 | -1,799 | 0.71 | 0.92 | 3% | fail * |
| 15m | B750 | 27 | 56% | +4,845 | -9,788 | -9,640 | 14% | 3 of 3 (22,140) | -1,799 | -2,212 | 0.70 | 0.92 | 3% | fail * |
| 15m | B1000 | 27 | 52% | +4,424 | -10,209 | -11,150 | 13% | 3 of 3 (19,871) | -1,799 | -2,965 | 0.75 | 0.92 | 2% | fail * |
| 15m | P3 | 27 | 59% | +4,273 | -10,360 | -9,936 | 13% | 3 of 3 (21,893) | -1,799 | -3,009 | 0.70 | 0.92 | 3% | fail * |
| 5m | CTRL | 83 | 42% | +22,124 | - | - | 23% | of 10 | -4,183 | -10,806 | - | - | - | - |
| 5m | A1 | 83 | 51% | +2,968 | -19,156 | -22,872 | 9% | 9 of 10 (40,528) | -4,183 | -12,671 | 0.85 | 0.92 | 0.2% | FAIL |
| 5m | A2 | 83 | 42% | +6,403 | -15,721 | -20,883 | 11% | 9 of 10 (41,531) | -1,584 | -5,703 | 0.80 | 0.92 | 0.1% | FAIL |
| 5m | B300 | 83 | 57% | +3,742 | -18,382 | -21,297 | 9% | 9 of 10 (44,506) | -4,183 | -9,271 | 0.82 | 0.92 | 0.2% | FAIL |
| 5m | B750 | 83 | 49% | +865 | -21,259 | -24,734 | 7% | 9 of 10 (39,188) | -4,183 | -17,156 | 0.89 | 0.92 | 0.2% | FAIL |
| 5m | B1000 | 83 | 47% | -919 | -23,044 | -23,078 | 6% | 9 of 10 (36,587) | -4,183 | -17,753 | 0.92 | 0.92 | 0.1% | FAIL |
| 5m | P3 | 83 | 49% | +4,229 | -17,895 | -19,886 | 10% | 9 of 10 (40,024) | -4,183 | -12,911 | 0.84 | 0.92 | 0.2% | FAIL |

\* The 15-minute book had 27 holdout trades, under the pre-registered 30, so the script labels it "inconclusive". It
failed gates 1, 2 and 3 all the same, so it could not have passed. The one gate the trail sometimes passes is risk
(gate 4): on the 15-minute book it made the worst day and the drawdown smaller (A1: worst day -Rs 1,799 against
-Rs 2,985). On the 5-minute book A1 made the drawdown worse (-Rs 12,671 against -Rs 10,806).

How Boss's rule exits (holdout, A1): the trail sold 18 of 27 trades (15m) and 43 of 83 (5m). Plain rules' exits on the same
books: index stop 5 / 27, time stop 10 / 13, next level 6 / 21, failed break 3 / 12.

Filling the trail at the next minute's open instead of at the trigger changes little (the "next-open fill" column): the
loss is in WHEN it sells, not the fill.

## In the app (13 Oct)

Nothing in Liquidity's rules changed. CLAUDE.md: a failed idea stays a note. As Boss asked for this case, one opt-in
switch is built and tested so he can decide after reading the numbers:

- **"Try the ₹500 trail on paper"** on the Strategy lab card, under the Liquidity BANKNIFTY freeze line. **Off by
  default.** Its line says "failed research, your choice" with R19b's numbers. One tap switches it on, one tap back to
  plain rules. Each flip goes to the diagnostics.
- **Paper only.** A new Liquidity PAPER entry carries the trail only while the switch is on. A live entry never carries
  it, whatever the switch says (engine `LiquidityTrail.forEntry`). Open trades keep the rule they entered with.
- It works like the ORB's profit lock (`ProfitLock.raise`): from +Rs 500 a lot, the paper book's resting stop is moved
  UP to Rs 500 a lot under the best price (stream ticks or 1-minute highs), never down, so it works with the app closed.
  If the stop is gone, the app sells it itself. It never widens a stop. Nothing new is read on the order path: the
  switch is a memory read at a paper entry.
- **Labels.** Every Liquidity paper trade's notices say "Rules: plain rules" or "Rules: with ₹500 trail". A trail exit
  reads "₹500 trail". Liquidity BANKNIFTY's paper-test verdict (the 26 Oct decision) counts **plain-rules trades only**.
  Trail trades are kept apart on their own line, so the plain-rules record stays clean.
- Code: engine `LiquidityTrail` (pure, tested: activation, never lower, rupees to points per lot size, gap through the
  trigger, missing peak); ira-core `LiquidityFreeze.State.trail` and its words; app `OrbArms` (paper path only),
  `LiquidityTest.setTrail`, `StrategyLabCard.LiquidityTrailRow`.
- Not done: the Autopilot change timeline entry. That was asked for a PASSING idea that acts by itself. This switch is
  only Boss's own manual choice, said back on the card and in the diagnostics.

## Where this leaves Liquidity

Keep the plain rules. The arm's edge is in its runners. Any exit that trails the best price closely (R12's managers,
LIQUIDITY_TRAIL.md's percentage trails, and now Rs 300-1,000 give-backs) gives most of them back. A MIDCPNIFTY test
needs its option minutes again (a Dhan token, or the Dhan lake once it is published).
