# R136: the ICT books' concepts inside R123's network made it worse

29 Oct 2026. Research only. Boss asked to *"add all the knowledge to neuro network"*. Step 2 of
`research/ICT_BOOKS_STUDY.md`.

## The answer: keep R123's network as it is

R123's single network (N1) was retrained here twice, the same way, with the same seeds and the same walk-forward
folds. **N1-base** has R123's 42 inputs. **N1-ICT** adds 35 inputs that carry the books' concepts. The two were
compared on the same 258,872 decision rows (2023 - 15 Mar 2026, 792 sessions).

| test (pre-registered) | N1-base | N1-ICT | p | q | result |
|---|---|---|---|---|---|
| **T1** direction over 30 minutes (log-loss, lower is better) | 0.6939 | 0.6939 | 0.99 | 0.99 | no change: both a coin flip |
| **T2** the 16 trade cells' outcome (log-loss) | **0.846** | 0.854 | < 0.001 | < 0.001 | **ICT worse** |
| **T3** the EV filter on CISD (S3-V2-E0, 3,211 trades) | **428 kept, +Rs 94,531** (Rs 221 a trade, 60% won) | 229 kept, -Rs 3,974 | 0.025 | 0.037 | **ICT worse** |

**The same holds for the other rules (reported, not tested):**

| rule (R133 passers, R131's entries) | N1-base filter | N1-ICT filter |
|---|---|---|
| S3-V2-E3 CISD + index stop + lock | 428 trades, +Rs 97,396 | 232, +Rs 18,907 |
| S7-V2-E4 Turtle Soup PDH/PDL + trail | 402, +Rs 38,904 | 198, -Rs 38,709 |
| S3-V1-E3 CISD as stated + index stop + lock | 315, +Rs 89,195 | 169, +Rs 14,913 |
| S9-W-E4 Breaker wick-only + trail | 725, +Rs 40,592 | 394, +Rs 29,155 |
| S3-W-E1 CISD wick-only + -Rs 1k stop + lock | 513, +Rs 18,792 | 286, -Rs 12,414 |
| all 39 R131 entries with R131's exits, summed | 16,252 trades, **+Rs 7.87 lakh** | 8,850 trades, -Rs 0.74 lakh |

On those 39 entries, the ICT network did better on 18 and worse on 21.

**Verdict:** the books' concepts do not help the network forecast direction, make its trade forecasts worse, and make
its filter pick worse trades. R123's original network stays the one used (in the R134 forward test and for R137).

## Why it hurt

- **R135 already showed the concepts carry little information on Indian indices.**
  - Sweeps continue rather than reverse.
  - The strong/weak and opening-range effects are 2-3 points of percentage.
- **Inputs that carry little information make a network overfit.** Its training loss improves on noise, and its
  forecasts on new folds get worse.
- **R125 found the same** with support/resistance, sweeps, VWAP and RSI.
- **The network already sees the last 120 minutes of price.** So every wick-level idea is a summary of something it
  already has.

## A useful check

The base network retrained here from scratch reproduces R133's lead. CISD + the EV filter kept 428 trades for
+Rs 94,531, against R133's 413 trades for +Rs 1,11,520 with R123's own fold models. So the R133/R134 lead is not an
accident of one training run.

## How it was done

- **Pre-registration:** `research/hunt/r136_ict_network/PREREG.md`, committed before any feature value.
- **The 35 inputs** (`ict_features.py`):
  - liquidity tiers (PDH/PDL, PWH/PWL, PMH/PML: distances and sweep flags);
  - the opening range;
  - day position;
  - 15-minute structure (last swings, strong or weak, last break and its strength, premium/discount in the leg);
  - 5-minute sweep with displacement;
  - open FVGs;
  - the 200-day bias;
  - TDI (RSI(50) against its bands).
  - A look-ahead test checks every input with every later minute deleted.
- **Training:** R123's code, seeds, epochs and early stopping, folds F1-F4 with a 10-day embargo.
- **Tests:**
  - T1 and T2: a paired session bootstrap.
  - T3: a paired sign-flip of the kept-trade difference.
  - BH over the three tests.
- **Not done:** the shuffle importance of the new groups (in the pre-registration as "reported"). The fitted networks
  were not kept, so it would mean training again. It would not change the verdict.
- **Code and tests:** `research/hunt/r136_ict_network/`. Results: `results/compare.json`. Predictions:
  `gdrive:IraAlgo-data-lake/derived/r136/parts/`.
