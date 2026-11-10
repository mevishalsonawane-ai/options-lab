# R157 pre-registration: the scalp network, enhanced for win rate and profit

Written 10 Nov 2026, **before any R157 label, model or number was computed** (committed and pushed on
`claude/r157-scalp-network-plus` ahead of the code's first run). Research only; nothing in `android/**` changes.

Boss, 10 Nov, after R156 (0 of 51): *"Enhace it for more winrate and profit among"* (the message ends there; read as:
enhance the network for a higher win rate and more profit across the indices and exits).

**Signal source: the index** (R156's, plus the other four indices' own 1-minute bars). Option-chain OI and India VIX stay
context only. The 1-ITM nearest-expiry option is bought. The new exits put a stop and a trailing lock on the option
premium, which CLAUDE.md allows.

## 0. What R156 found, and the four enhancements (fixed now)

R156's network separated winning from losing scalps only slightly (AUC 0.53-0.57); its best tenth of moments won 58%
where 69% was needed; trading its picks lost Rs 59-142 a trade. It leaned on the inputs that predict the SIZE of a move.

| # | enhancement | why it could help |
|---|---|---|
| 1 | **Cross-index input:** the sequence gets, next to the traded index's 5 channels, the last 60 one-minute returns and a printed flag of each of the other four indices (side-signed, each in its own scale): 13 channels | lead-lag between indices is the one direction clue R156 never saw |
| 2 | **3 lots as well as 1 lot:** every label is priced at 1 lot and at 3 lots (charges and R17 slippage of the whole quantity) | brokerage is per order: R154's loss per lot fell from Rs 101 to Rs 70 at 3 lots |
| 3 | **"Let the winner run" exits (4 new, on the premium):** stop P points below the buy fill; once the premium has been +P above it, the stop trails P points below the highest premium seen (never below the buy price); time stop T; square-off. P in {10, 15}, T in {30, 60} minutes: `R10t30`, `R10t60`, `R15t30`, `R15t60` | the size of a move is predictable (R124); a capped target throws that away |
| 4 | **Pickier rules:** forecasts in the top 1% of the model's training forecasts (TOP99) | R156's top tenth was closer to break-even than its average moment |

Everything else is R156's, unchanged: the moments (66 a session, both sides, 5 indices, Aug 2021 - 19 Oct 2026), the
43-number table, the network (three 1-D convolutions + table branch; now 13 input channels and 56 x 2 outputs: 28 exits x
2 lot sizes, each a win logit and an expected net), the loss, Adam, 6 epochs, early stopping on the last 10% of training
sessions, 3 seeds averaged, and the expanding walk-forward folds (2023, 2024, 2025, Jan - Mar 2026, final + post).
The comparison trees are not re-run (R156: equal to the network).

## 1. Trading rules (per lot size L in {1, 3}; one open position per index, 5 trades per index a day)

| id | rule | tests |
|---|---|---|
| EV-r@L | exit r: take the side with the higher forecast net if > 0 | 28 x 2 |
| TOP99-r@L | exit r: take the side with the higher forecast win chance if at or above the 99th percentile of the model's training forecasts for r | 28 x 2 |
| BEST@L | the (exit, side) with the highest forecast net among the 28, if > 0 | 2 |

114 tests, pooled over the five indices (per index shown).

## 2. Periods and gates (R156's, never relaxed)

O1 2023 - Mar 2025; P2 Apr 2025 - Mar 2026; **P3 final 1 Apr - 13 Oct 2026, read once by R157: a FOURTH look for scalps
(R154, R155, R156), weak evidence**; P4 post 14 - 19 Oct, reported only.
1. Net > 0 in O1, P2 and P3.
2. Beats 2,000 random-entry twins in P3 (R156's twin: same number of trades per index and session at random moments and
   sides, same exit and lots; seed 157), BH q <= 0.10 with m = 2,013 earlier (R156's tally) + 114 = 2,127.
3. At least 40 final trades.

## 3. Reported (never gated)

- **Did the enhancements help?** For R156's 24 exits at 1 lot: AUC, the top tenth's win rate / win needed / net a trade,
  and BEST, R157 against R156, same periods.
- Each enhancement's own effect: the shuffle test with the cross-index channels as their own group; 1 lot against 3 lots
  (net per lot); the run exits against the fixed-target exits (unconditional and the network's picks); TOP99 against
  R156's top tenth.
- Win rate, win needed, net a trade and a lot for every rule; calibration in tenths plus the top 1%.

## 4. What a pass means

A passing rule could become a PAPER arm only by Boss's choice. Nothing goes live from R157. Failures stay notes here and
in `research/experiments/entries/r157-*.yaml`.

## 5. Tests

`test_r157.py`: the cross-index sequence (mirror, own scale, missing index, look-ahead guard), the run exits against
hand-built premium paths (stop, lock trail, never below the buy price, time), 3-lot labels (quantity x 3, charges less than
3x), labels of the 24 old exits at 1 lot equal to R156's stored labels, rule scores and thresholds, and R156's walk /
twin / gates reused.
