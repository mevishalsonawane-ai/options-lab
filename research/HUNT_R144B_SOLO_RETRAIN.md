# R144b-S: retraining Solo 2.0 on four years, judged only on months each model never saw

1 Nov 2026. Research only: nothing in the app or the Solo 2.0 arm changes. Boss: *"Do option 3, retrain Solo 2.0
study"*. Pre-registration: `research/hunt/r144b_solo_retrain/PREREG.md` (a720427, before any number).

## Outcome first: no retrained version passes. Solo 2.0's model has no pick skill that holds up

- **3 variants** were retrained every month on all earlier data (Dec 2022 onwards) and judged only on months they had
  not seen (Jul 2023 - Oct 2026). **None passed all three periods.**
- **The models barely tell winners from losers:** walk-forward AUC 0.52-0.53 (0.50 is a coin flip).
- **The closest, V3** (gradient-boosted trees, no S4):
  - beat a random same-day pick in 2023-24 (+Rs 15,961, p 0.043) and in Apr - Oct 2026 (+Rs 58,269, p 0.015);
  - but in Nov 2024 - Mar 2026 it made +Rs 13,676 where random picks averaged +Rs 28,955 (p 0.65), with a worse
    drawdown than today's model.
  - A picker that is worse than chance for 16 months is not one to switch to.
- **No new model is proposed.** Solo 2.0 stays as it is, and its own forward judge (switch off after 60 paper trades
  at net <= 0, or at -Rs 25,000) remains its guard.

## Method (as pre-registered)

- **Candidates:** R57's setups, unchanged: S1 SoloMidday 12:00, S2 Donchian 20, S3 open outside value, S4 15-minute
  opening range. Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY. Each priced by R57's engine with its own exits,
  1 lot, after charges.
  - 6,795 candidates in Dec 2022 - Nov 2024 (R144b's old R57 store);
  - 8,022 in 20 Nov 2024 - 13 Oct 2026 (R57's store).
- **Features:** A2's 29 inputs. Label: net > 0. Signal source: the index; options and VIX only as model inputs.
- **Walk-forward (R57's own code):** monthly refits on every earlier candidate, with a 1-session gap; the bar is the top
  30% of training scores; the first candidate of the day that clears it is taken.
- **Gates, in each period:** net > 0; beats 2,000 random same-day picks (p <= 0.05); >= 40 trades; drawdown no worse
  than today's frozen model; BH q <= 0.10 over 9 tests.
- **Caveat:** the lake ends 14 Oct 2026, so no month is untouched. R144b had already looked at these months, and V2 /
  V3 drop S4 because of that look.

## Results

| version | 2023-24 (Jul 23 - Nov 24): trades, net, win | Nov 24 - Mar 26 | Apr - Oct 26 | passed |
|---|---|---|---|---|
| today's frozen model (reference) | 322, -Rs 51,415, 39% | 310, +Rs 1,02,774, 53% (in-sample) | 127, +Rs 89,382, 58% (in-sample) | - |
| V1 retrained logistic, S1-S4 | 309, -Rs 70,845, 38%; p 0.98 | 311, +Rs 16,595, 51%; p 0.32 | 127, +Rs 83,042, 60%; p < 0.001 | no |
| V2 retrained logistic, no S4 | 301, -Rs 55,292, 39%; p 0.98 | 300, +Rs 33,469, 48%; p 0.37 | 122, +Rs 92,470, 61%; p < 0.001 | no |
| V3 retrained trees, no S4 | 295, +Rs 15,961, 46%; p 0.043 | 297, +Rs 13,676, 49%; p 0.65 | 117, +Rs 58,269, 56%; p 0.015 | no |
| S1 alone, every signal (reference) | 220, +Rs 16,735, 49% | 305, +Rs 91,770, 48% | 89, +Rs 1,329, 52% | - |

"p" is the share of random same-day picks that did at least as well.

**What it shows:**
1. **2026 is easy, earlier is not.** Every version does well in Apr - Oct 2026, mostly on MIDCPNIFTY S3 trades (V2:
   83 MIDCPNIFTY trades, +Rs 64,734). The same recipe loses in 2023-24 and is no better than random in 2025. That is a
   good stretch for one setup on one index, not skill in the picker.
2. **The model rarely picks S1** (2-14 trades a period), the one setup that made money in every period on its own.
   - S1 is Solo (midday)'s setup, which already runs as its own paper arm.
   - Its result here is seen data (R144b), so it is a lead, not a proof.
3. **Dropping S4 helps in 2023-24** (V2 lost less than V1), but not enough to make money.

## What this means for Boss

- **Nothing to switch on.** A retrained Solo 2.0 would not have been reliably better than picking a random candidate
  each day.
- **Options:**
  - keep Solo 2.0 on paper and let its forward judge decide;
  - pause it with its own switch;
  - watch Solo (midday), the S1 setup, which already trades on its own.
- **Not worth repeating:** more model recipes on the same 29 inputs. The inputs carry almost no information about which
  candidate wins (AUC 0.52-0.53 for three recipes).

## Files

- `research/hunt/r144b_solo_retrain/`: `PREREG.md`, `r144bs.py`, `test_r144bs.py`, `results/retrain.json` (every
  period by index and setup), `results/pvalues.json` (9 raw p-values, BH within the study).
- Entry: `research/experiments/entries/r144b-solo-retrain.yaml` (rejected).
