# R170: every other Donchian-study rule on the ORB arms. 0 of 576 passed

13 Nov 2026. Research only: nothing in the app changes.

Boss, after R169: *"All the other studies we tried on r51 and r72, try that too"*. During the run:
- *"Multiple support and resistance at once on all directions, fake breakout, fake volume, actual volume, delta, fake
  buyer seller, and all the other things"* (Amendment A);
- *"40 points are too big, check 20pts max"* (Amendment B).

## For Boss, in short

- **Nothing passed. ORB, ORB Fresh and ORB Sweep stay as they are (parked).** I ran:
  - every rule from R152, R153, R155, R161, R162, R163, R165, R166 and R167 that R169 had not already run;
  - the extra checks you asked for during the run.
  That is 64 changes x 3 arms x 1, 2, 3 lots = 576 tests. None cleared every gate. Even counting R170 alone, the
  smallest BH q is 1.0.
- **Today's rules, for comparison (BANKNIFTY, 1 lot):**
  - ORB: walk-forward year 231 trades, 35.9% won, -Rs 25,965; Apr - Oct 2026: 128 trades, 43.8% won (42.0% needed),
    +Rs 2,876.
  - ORB Sweep: -Rs 98,449, then -Rs 54,421.
- **20 points max (your message):** it does not help.

  | ORB, 1 lot | walk-forward year | Apr - Oct 2026 | win (needed) Apr - Oct |
  |---|---|---|---|
  | today -40 / +40 | -Rs 25,965 | +Rs 2,876 | 43.8% (42.0%) |
  | -20 / +20 | -Rs 17,494 | -Rs 3,506 | 44.5% (47.5%) |
  | -20 stop, +40 target | -Rs 33,055 | -Rs 4,527 | 35.9% (39.0%) |
  | -15 / +20 | -Rs 17,322 | -Rs 5,740 | 40.6% (45.5%) |

  - With a 20-point target, the win rate needed to break even jumps to 47-48%: charges and slippage are about 3-4
    premium points a trade on BANKNIFTY, a bigger share of a 20-point win.
  - A -20 stop alone gets hit far more often (win rate down to 36%).
  - It is the same at 2-3 lots. -20 / +20 lost less in the walk-forward year (-Rs 23,828 vs -Rs 38,183 at 2 lots) but
    turned the Apr - Oct profit into a loss.
  - ORB Sweep loses less with 20 points (-Rs 27,323 vs -Rs 54,421, Apr - Oct) but still loses. Random earlier exits cut
    its loss even more (p 0.999).
- **Multiple support / resistance at once, all directions:**
  - The map: 5-minute swings of 50 sessions, 15-minute swings of 60 sessions, floor pivots, yesterday's and last week's
    high / low, and today's confirmed swings.
  - "No level just ahead" (S1) left too few trades: 16 in the walk-forward year, 4 from April.
  - "More room ahead than behind" (S2): 64 trades, 43.8% won, +Rs 1,259 from April vs +Rs 2,876. Worse.
  - "Not boxed in both ways" (S3): too few trades.
  - On ORB the break happens right at the morning's levels, so a level is almost always close by.
- **Fake breakout:** selling if the index has not gone past the signal candle within 3 / 5 minutes made less in the
  walk-forward year. Waiting one bar (R169) was worse.
- **Fake volume / actual volume:**
  - Skipping breaks on low option volume (V1) and skipping when the whole option band is quieter than usual (A1) were
    the best of this group. A1 gave +Rs 1,475 in the walk-forward year (vs -Rs 25,965) and +Rs 6,717 from April
    (63 trades, 47.6% won).
  - But skipping the same number of trades at random did about as well (p 0.16 for A1). ORB loses on most kinds of day,
    so almost any skip "helps" it.
  - Skipping a volume climax (V2) made it worse.
- **Delta:** delta bands (0.45-0.55 / 0.55-0.65) and a delta-0.70 strike did not help (Apr - Oct: -Rs 1,076,
  +Rs 1,402, -Rs 250).
- **Fake buyer / seller:**
  - Skipping when the option's IV fell (I1) gave 68 trades, +Rs 2,050.
  - Skipping when the other side got dearer (I2) gave +Rs 3,458 from April but less than today in the walk-forward year.
  - Skipping a short build-up in the option (Q1) changed almost nothing.
  - None beat random skips.
- **Best filter overall:** a narrow opening range (SQUEEZE, R161 E5).
  - Walk-forward year: 125 trades, 40.8% won, +Rs 2,462 vs -Rs 25,965.
  - Apr - Oct 2026: 69 trades, 47.8% won, +Rs 6,062 vs +Rs 2,876.
  - At 2 lots: +Rs 13,462 and +Rs 14,532.
  - It still did no better than random skips of the same size (p 0.19). Interesting, but not proven.
- **What it means:** on ORB, filters mostly just remove trades from an arm that loses on most days. Tighter exits mostly
  give back the few runners. Every exit rule built around Rs 1,500-2,000 does nothing at 1 lot: a full +40-point ORB
  win is about Rs 1,200-1,400.

## How it was tested

- Pre-registered in `PREREG.md` before any number:
  - b5129e4: the main list;
  - ab9de2f: Amendment A, your support / resistance and volume message;
  - 42d2faf: Amendment B, 20 points max.
  - Code, 90 tests and the walk-forward results were committed in 67092dd, before the final look
    (`results/FINAL_READ`).
- **Engine and baseline:** R169's. R3's engine reproduces R144b to the rupee. B0 here equals R169's B0 exactly. Costs
  are dated Zerodha charges with R17 fills sized to the order, at 1, 2 and 3 lots. **Signal source: the index**
  (BANKNIFTY 5-minute bars). The option's volume, OI, IV and the chain's put/call ratio are used only as filters.
- **The app's skip rule:** a skipped break still holds its side.
- **Periods:**
  - walk-forward window Apr 2025 - Mar 2026, each quarter fitted only on earlier data (pickers retrained, combinations
    re-chosen);
  - final Apr - 12 Oct 2026. R169 already read it today, as did many studies before: weak evidence.
- **Gates:** as R169.
  - Walk-forward AND final: net up with drawdown not worse, or +5 win points with net not lower.
  - Random twins p <= 0.05.
  - BH q <= 0.10 with m = 3,841. That is 3,058 tests through R168, + R169's 207, + R170's 576.
  - >= 40 final trades.
  - Inside -Rs 40k drawdown / -Rs 13k month at the tested lots.
- **Not taken, with the reason:**
  - R154 / R155's 10-15 point targets: R168 did them on ORB today.
  - R162's scale-out: it sells past ORB's +40 target, which is already the exit.
  - R167's design-trained exit models (SR, CTX, ML).
  - R153's 10:00 entry cut: ORB's first entry is 10:05, so it can never fire.
  - R164: there is no aggressor data.

## Results (all 576 rows, every column, in `tables.md`)

ORB, 1 lot. Columns: trades / win % / net. ORB Fresh takes the same trades.

| change | walk-forward year | Apr - Oct 2026 | twin p | first reason it failed |
|---|---|---|---|---|
| **today** | 231 / 35.9% / -25,965 | 128 / 43.8% / +2,876 | | |
| E5 SQUEEZE (narrow range) | 125 / 40.8% / +2,462 | 69 / 47.8% / +6,062 | 0.19 | no better than random skips |
| A1 BANDVOL (actual volume) | 109 / 37.6% / +1,475 | 63 / 47.6% / +6,717 | 0.16 | no better than random skips |
| E3 BIGBAR | 144 / 39.6% / -9,083 | 78 / 44.9% / +8,739 | 0.08 | twin p, BH q |
| NIP30 (out at 30 min if below the fill) | 231 / 33.8% / -23,531 | 128 / 43.8% / +4,168 | 0.017 | BH q (1.0) |
| V1 low option volume | 147 / 40.8% / -2,649 | 75 / 41.3% / +4,441 | 0.31 | no better than random |
| I1 IV fell | 115 / 40.9% / -2,490 | 68 / 44.1% / +2,050 | 0.45 | final net lower |
| S2 ROOM | 145 / 40.7% / -8,580 | 64 / 43.8% / +1,259 | 0.52 | final net lower |
| S1 ALLSR | 16 / 31.2% / -4,770 | 4 / 75.0% / +1,487 | 0.23 | 4 trades |
| K1 trees / K2 neural picker | 203 / 36.0% / -18,741; 135 / 37.0% / -13,668 | 92 / 43.5% / +440; 65 / 50.8% / +16 | 0.63 / 0.60 | final net lower |
| D70 delta strike | 230 / 39.6% / -24,760 | 128 / 46.1% / -250 | 0.14 | final net lower |
| FK3 fake breakout exit | 231 / 33.3% / -26,208 | 128 / 41.4% / +5,228 | 0.34 | worse in walk-forward year |
| Z1 -20 / +20 | 231 / 39.0% / -17,494 | 128 / 44.5% / -3,506 | 0.999 | final net lower |
| Z3 -15 / +20 | 231 / 35.9% / -17,322 | 128 / 40.6% / -5,740 | 1.0 | final net lower |
| C1-C3 (design-chosen) | chose the skips that remove most trades | 0-4 trades | | too few trades |

ORB Sweep: every change with enough trades still lost money in both periods. The least bad from April was -20 / +20 or
-15 / +20 (-Rs 27k vs -Rs 54k), and random earlier exits did better still.

## Honest limits

- **Another look at a much-read window.** The walk-forward year carries the weight. The best walk-forward results
  (SQUEEZE, BANDVOL) are skips of a losing arm, and random skips matched them.
- **The combination rule was followed literally** (rank by design net gain, drawdown not worse). On a losing arm it
  picks the filters that remove the most trades, so C1-C3 had 0-4 final trades. Recorded, not changed after the fact.
- **Implementation choices made before the run:**
  - variants of one rule never combine (BE15 / BE20, LF1 / LF2, FK3 / FK5, Z1-Z3, the rupee locks, the strikes);
  - pickers and gradient trees use fixed defaults (random_state 0).
- **Rupee exits** (Q2K, QT2K, QS2K, LCK, G1R, G2R) can only act at 2-3 lots on ORB. At 1 lot a whole ORB win is
  smaller than their trigger.
- **R168** ran in parallel and is counted in m (its 360 tests).

## Files

- `PREREG.md` (with Amendments A and B)
- Code: `r170lib.py` (pure), `r170study.py`, `r170report.py`
- Tests: `test_r170.py` (90 tests)
- Results: `results/wf.json`, `final.json`, `decision.json`, `raw_pvalues.csv`, `tables.md`
- Experiment entries: `research/experiments/entries/r170-*.yaml` (576, all rejected)
