# R169: can the newer knowledge enhance the ORB arms? No. 0 of 207 tests passed

13 Nov 2026. Research only: nothing in the app changes. Boss, 13 Nov: *"Can we use all the new knowledge on ORB and try
to enhance it."*

## For Boss, in short

- **Nothing passed. ORB, ORB Fresh and ORB Sweep stay as they are (parked since 13 Oct).** I tried 20 ideas from the
  recent studies one at a time, plus the best 2-3 together. That is 23 changes x 3 arms x 1, 2 and 3 lots = 207 tests.
  None cleared every gate.
- **The arms today (BANKNIFTY, 1 lot, real dated charges and slippage):**
  - **ORB (ORB Fresh takes exactly the same trades):**
    - walk-forward year (Apr 2025 - Mar 2026): 231 trades, 35.9% won, -Rs 25,965;
    - Apr - 12 Oct 2026: 128 trades, 43.8% won (42.0% needed to break even), +Rs 2,876;
    - without its 5 best trades it loses (-Rs 7,469).
  - **ORB Sweep:**
    - walk-forward year: 326 trades, 24.8% won, -Rs 98,449;
    - Apr - Oct 2026: 176 trades, 28.4% won, -Rs 54,421;
    - its worst month (-Rs 15,900) breaks your -Rs 13k month limit even at 1 lot.
- **Closest misses (all on ORB; none is good enough to switch on):**
  1. **Out after 20 minutes unless +5%** (Liquidity 15+5's time stop; it only lowers risk).
     - Better in both periods at every lot size. 1 lot: walk-forward +Rs 486, Apr - Oct +Rs 2,328 (+Rs 5,204 vs +Rs 2,876).
     - It beat random exits (p 0.01).
     - But the gain is tiny against the 2,871 tests now on the books (BH q 1.0; 0.19 even counting R169 alone).
     - At 2-3 lots the arm's own drawdown (Rs 58k-77k in the walk-forward year) breaks your -Rs 40k limit, with or
       without it.
  2. **Strike by delta 0.50** (R165): walk-forward -Rs 13,951 vs -Rs 25,965, Apr - Oct +Rs 6,607 vs +Rs 2,876. Random
     strikes did about as well (p 0.05), and it changes the entry, so it would need your explicit choice anyway.
  3. **Skip trades against a big gap or with a small gap** (R160 / MARKET_HOW): Apr - Oct won 50.0% vs 43.8% (+6.2
     points) on 72 trades, +Rs 4,403. But skipping the same number of trades at random did as well (p 0.30): ORB loses on
     most kinds of day, so almost any skip "helps" it.
- **The two ideas that helped the Donchian and CISD arms did not help ORB:**
  - **The trade picker** (R151-style) won 51.5% on 66 trades in Apr - Oct 2026 but made less (-Rs 2,769 vs +Rs 2,876).
    At the moment of an ORB break, a win is close to a coin toss, as R163 found for Donchian.
  - **R123's network** says "go" on only about 2% of BANKNIFTY moments since 2025. It left 15 ORB trades in the
    walk-forward year and 4 from April, far too few.
- **Liquidity 15+5's lesson:**
  - What makes it work: it buys a break of a real level, one strike in the money, with a stop in % of premium, a quick
    20-minute time stop, and no small fixed target, so the rare long runs pay for the many small losses.
  - Borrowing all of its exits made ORB far worse: -15% stop, no +40 target, 20-minute time stop gave -Rs 94,647 in the
    walk-forward year vs -Rs 25,965. On a Rs 500-1,000 option a -15% stop is 75-150 points, much wider than ORB's -40.
  - Its 20-minute time stop alone is the closest miss above. Its 1-ITM strike did not help.
- **What not to try again:** ORB Sweep (fading breaks) loses with every change. MARKET_HOW already says: trade breaks,
  never fades.
- **If you ever want ORB back,** the only lead is the 20-minute time stop on ORB / ORB Fresh at 1 lot. It would need a
  fresh forward log: unseen days, a trade count fixed in advance, in shadow. Paper costs nothing, but the evidence is weak.

## What was tested

Pre-registered in `PREREG.md` (commit e833471, pushed before any number). Code, tests and the walk-forward results were
committed in 61838e5 / 5dc9163 before the one final read (`results/FINAL_READ`).

- **Arms** (`OrbRules.kt`, `SweepRules.kt`, `ReentryCap.kt`, the ProfitLock ladder):
  - ORB, ORB Fresh and ORB Sweep, on BANKNIFTY, the app's only index for them.
  - **Signal source: the index** (BANKNIFTY 5-minute bars). The option is only bought. Option-chain data and the network
    are used only as context.
  - With the 1-a-side cap, ORB Fresh takes exactly ORB's trades. It is reported and counted as its own arm anyway.
- **Parity first:** R3's engine reproduces R144b's design figures exactly:
  - ORB 320 trades / -Rs 41,427;
  - ORB Fresh 320 / -Rs 41,427;
  - ORB Sweep 454 / -Rs 1,12,523 (`results/parity.json`).
  - The study then re-prices everything with **dated Zerodha charges** (STT 0.1% to Mar 2026, 0.15% from Apr 2026) and
    R17 fills sized to the order (1, 2 or 3 lots). That is B0 below.
- **Periods:**
  - Training history Aug 2021 - 19 Nov 2024. It is used only to fit the picker.
  - Design 20 Nov 2024 - 31 Mar 2026. Its walk-forward window is Apr 2025 - Mar 2026, in 4 quarters. Everything learned
    or chosen (picker weights, the combinations) was fitted only on data before each quarter.
  - Final 1 Apr - 12 Oct 2026, read once.
  - **Honest note:** this window has been read by many earlier studies (R14, R49, R92, R101, R144b, R151-R167), so even
    a pass would have been weak evidence.
  - The lake's last days (13-19 Oct, 5 sessions) are only an extra look (`results/post.json`). ORB took 2 trades there
    (-Rs 973) and Sweep 3 (-Rs 966). The near-misses changed nothing there.
- **Gates** (never relaxed), each versus the same arm's B0 at the same lots:
  1. In the walk-forward window AND the final, either net up with drawdown not worse, or the win rate up >= 5 points
     with net not lower.
  2. Better than 2,000 random twins of the same size (p <= 0.05):
     - filters: random skips under the app's rule (a skipped break still holds its side);
     - exits: random earlier exits;
     - strike, timing and combination changes: random entry bars.
  3. BH q <= 0.10 with m = 2,664 earlier tests (through R167) + 207. R168 (run today on its own branch) is not
     counted; adding it can only raise q.
  4. At least 40 final trades.
  5. Inside your limits at the tested lots: -Rs 40k drawdown and -Rs 13k month, in both periods.

| id | idea (where it came from) | type |
|---|---|---|
| F1 PICK | logistic trade picker on ORB's own 11 entry features (R151 / R163) | skip only |
| F2 NET | R123's N1 network: mean EV of the side's 8 cells > 0 (R133) | skip only |
| F3 BUSY | 5-minute ATR at or above its 20-session median (R161 E9) | skip only |
| F4 HTF | agrees with the 60-minute move (R161) | skip only |
| F5 GAP | skip against a big gap (>= 0.6%) and with a small gap (< 0.3%) (R160 / R146 / MARKET_HOW) | skip only |
| F6 EXT15 | the relevant range edge set in the first 15 minutes (MARKET_HOW) | skip only |
| F7 FVG | the break leaves a 5-minute fair-value gap (ICT displacement; R130-R139) | skip only |
| F8 ROOM | skip when yesterday's high / low sits within 30 points ahead (Liquidity's room filter) | skip only |
| F9 NOEXP | no expiry-day trades (Liquidity) | skip only |
| X1 BE10 | breakeven once +10% (R162) | tighten only |
| X2 T40 | out at 40 minutes unless +5% (R162 T40) | tighten only |
| X3 T20 | out at 20 minutes unless +5% (Liquidity's time stop) | tighten only |
| X4 G3 | 3-minute index-low trail once +50% of the target (R167 G3, tighten-only) | tighten only |
| X5 SRT | out at yesterday's high / low (R150 levels as targets) | tighten only |
| X6 FAIL | out when a 5-minute bar closes back through the range edge (failed break) | tighten only |
| S1 D60 / S2 D50 | strike by delta 0.60 / 0.50 (R162 D60, R165) | entry change |
| S3 ITM1 | 1-ITM strike (Liquidity) | entry change |
| W1 WAIT1 | wait one bar for confirmation (R161 E2) | entry change |
| L1 LIQX | Liquidity's exits: -15% stop, 20-minute time stop, no target | exit structure (wider risk) |
| C1 / C2 / C3 | best two, best three, best filter + best exit, chosen on design only | combination |

## Results, 1 lot (all lots, all columns in `tables.md`)

Columns: trades / win % / net. WF = the walk-forward window. Final = Apr - 12 Oct 2026.

### ORB (ORB Fresh identical)

| change | WF | final | final worst month / max DD | twin p | q | why it failed (first reason) |
|---|---|---|---|---|---|---|
| **B0 today** | 231 / 35.9% / -Rs 25,965 | 128 / 43.8% / +Rs 2,876 | -Rs 3,333 / -Rs 7,493 | | | |
| F1 PICK | 159 / 34.0% / -20,069 | 66 / 51.5% / -2,769 | -5,890 / -7,456 | 0.80 | 1 | final net lower |
| F2 NET | 15 / 20.0% / -5,954 | 4 / 75.0% / +2,409 | 0 / -19 | 0.10 | 1 | final net lower; 4 trades |
| F3 BUSY | 136 / 31.6% / -26,578 | 59 / 47.5% / +21 | | 0.62 | 1 | WF worse |
| F4 HTF | = B0 (every break agrees with the hour) | = B0 | | 1 | 1 | no change |
| F5 GAP | 147 / 36.7% / -11,786 | 72 / **50.0%** / +4,403 | -2,287 / -5,739 | 0.30 | 1 | no better than random skips |
| F6 EXT15 | 163 / 33.7% / -23,662 | 96 / 39.6% / -2,704 | | 0.85 | 1 | final worse |
| F7 FVG | 88 / 35.2% / -2,839 | 48 / 45.8% / +2,510 | | 0.37 | 1 | final net lower |
| F8 ROOM | 220 / 34.5% / -28,853 | = B0 | | 1 | 1 | WF worse |
| F9 NOEXP | 220 / 36.4% / -24,959 | 125 / 43.2% / +1,962 | | 0.69 | 1 | final worse |
| X1 BE10 | = B0 (+10% is past the +40 target) | = B0 | | 1 | 1 | no change |
| X2 T40 | 231 / 34.6% / -25,352 | 128 / 43.8% / +3,822 | -2,387 / -7,493 | 0.046 | 1 | BH q |
| **X3 T20** | 231 / 33.3% / -25,479 | 128 / 44.5% / **+5,204** | -1,857 / -7,493 | **0.0095** | 1 (0.19 alone) | BH q |
| X4 G3 | 231 / 35.9% / -26,659 | = B0 | | 1 | 1 | WF worse |
| X5 SRT | 231 / 36.4% / -27,485 | 128 / 43.8% / +2,897 | | 0.0005 | 0.24 | WF worse (final +Rs 21) |
| X6 FAIL | 231 / 29.0% / -34,156 | 128 / 42.2% / +2,801 | | 0.68 | 1 | worse |
| S1 D60 | 231 / 36.4% / -38,107 | 128 / 46.9% / +6,790 | | 0.07 | 1 | WF worse |
| **S2 D50** | 231 / 40.7% / -13,951 | 128 / 44.5% / +6,607 | -3,307 / -6,198 | 0.0525 | 1 | twin p, BH q |
| S3 ITM1 | 231 / 37.2% / -22,681 | 128 / 46.9% / -1,717 | | 0.19 | 1 | final worse |
| W1 WAIT1 | 216 / 37.0% / -32,945 | 121 / 34.7% / -27,504 | | 0.97 | 1 | worse |
| L1 LIQX | 230 / 30.4% / -94,647 | 128 / 40.6% / -4,684 | -20,738 / -44,755 | 0.87 | 1 | far worse |
| C1 (F2+F7) | 25 / 28.0% / -3,516 | 0 trades | | 1 | 1 | too few trades |
| C2 (F2+F7+F5) | 6 / 0% / -2,093 | 0 trades | | 1 | 1 | too few trades |
| C3 (F2+X3) | 70 / 24.3% / -16,391 | 4 / 75.0% / +2,409 | | 0.03 | 1 | final net lower; 4 trades |

### ORB Sweep

| change | WF | final | twin p | why it failed |
|---|---|---|---|---|
| **B0 today** | 326 / 24.8% / -Rs 98,449 | 176 / 28.4% / -Rs 54,421 (worst month -Rs 15,900) | | |
| best by final net among >= 40 trades: F3 BUSY | 191 / 23.6% / -60,547 | 87 / 34.5% / -16,837 | 0.12 | still loses; no better than random skips |
| W1 WAIT1 | 265 / 21.1% / -95,653 | 150 / 26.7% / -35,470 | 0.66 | still loses; random entries do as well |
| F2 NET | 21 / 42.9% / +1,404 | 3 / 33.3% / -864 | 0.43 | 3 final trades |
| X3 T20 | 341 / 29.6% / -73,916 | 184 / 30.4% / -48,889 | 0.96 | still loses |
| every other change | | still loses Rs 30k-90k in the final | | |

Every Sweep change with enough trades lost money in both periods. 13 of the 23 still broke your -Rs 13k month limit at
1 lot in the final.

### At 2 and 3 lots

- The picture is the same at 2 and 3 lots, with bigger numbers.
- ORB B0, final: +Rs 10,637 at 2 lots and +Rs 22,303 at 3 lots. Charges weigh less per lot.
- ORB B0, walk-forward year: -Rs 38,183 at 2 lots (drawdown -Rs 58,380) and -Rs 45,665 at 3 lots (drawdown -Rs 77,097).
  Both drawdowns break your -Rs 40k limit before any change.
- X3 T20 again beat B0 in both periods at 2 and 3 lots (final +Rs 15,340 and +Rs 29,380; p 0.007-0.011), but fails BH
  and the account limits.

## Honest limits

- **The final window has been read many times.** The design and walk-forward carry the weight. Several "wins" there
  (F5, F7, F2) only remove trades from an arm that loses, and random skips of the same size did as well.
- **BH family:** 2,871 tests (R168 not counted). Even counting R169 alone, the best real candidate (X3 T20) has q 0.19.
- **ORB Fresh and ORB are the same trades** on BANKNIFTY with the 1-a-side cap, so their tests duplicate each other.
- **The network filter** used R123's stored out-of-fold predictions. There are none for 16-31 Mar 2026 (R123's embargo);
  those breaks were taken.
- **The combinations followed the pre-registered rule literally:** rank by design net gain over B0, drawdown not worse.
  On a losing arm that rule picks the filters that remove the most trades (F2, F7), which left too few trades. This is a
  weakness of the rule, recorded rather than fixed after the fact.
- **R17 fills sized to the order:** the "thin contract" test counts the order's own lots, so 2-3 lots pay a little more
  slippage. Charges are dated and on the whole quantity.
- **Where the changes would sit (CLAUDE.md):**
  - Filters F1-F9 and exits X1-X6 only lower risk. A pass would have gone to shadow first.
  - Strike, timing, LIQX and combinations containing them change entries or size, so they would need your explicit
    choice.
  - Nothing passed, so there is no APP_SPEC.

## Files

- `PREREG.md`
- Code: `r169cfg.py`, `r169lib.py` (pure), `r169study.py` (runner), `r169report.py`
- Tests: `test_r169.py` (81 tests, including R3 parity on synthetic days, skip-only, tighten-only and look-ahead guards)
- Results: `results/parity.json`, `wf.json`, `final.json`, `decision.json`, `raw_pvalues.csv`, `tables.md`, `post.json`
- Experiment entries: `research/experiments/entries/r169-*.yaml` (207, all rejected)
