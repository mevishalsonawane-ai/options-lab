# R158 pre-registration: can anything beat Breadth Donchian on win rate AND profit?

Written 10 Nov 2026, **before any R158 label, model or number was computed** (committed and pushed on
`claude/r158-beat-breadth-donchian` ahead of the code's first run). Research only; nothing in `android/**` changes.

Boss, 10 Nov, after R154 - R157 (10-15 point scalps fail): *"Anything other then 10-15 points stratergy, you found
anything which could be win over with winrate and profit of breath donchain, do deep research, you could use all the
resources you could."*

**Signal source: the index** (the traded index's and the other four indices' 1-minute bars; index-derived context);
option-chain OI and India VIX as context only. The 1-ITM nearest-expiry option is bought and managed with Breadth
Donchian's own premium exits (CLAUDE.md allows exits on the premium).

## 0. The bar, and what the repo already holds (cited, not re-run)

**R72 Breadth Donchian (4 of 5), 1 lot, 5 indices, exits XL** (-15% stop, the 30-60-100 ladder + L25, 30-minute time stop
unless in profit, 15:15): design 20 Nov 2024 - 31 Mar 2026: 3,071 trades, Rs 2,16,929; final 1 Apr - 13 Oct 2026: 1,202
trades, 46% won, Rs 1,71,366 (reproduced first, asserted).

Records on the same final period (published, 1 lot): R51 Donchian 20 + Supertrend 1,670 trades / Rs 1,34,743; R51 plain
1,781 / Rs 81,845; R52 Open outside value Rs 48,612; Liquidity 15+5 357 / Rs 11,155; Solo 2.0 128 / Rs 99,172; **R151's
picker on R72: 652 trades, 48.9% won, Rs 2,19,057 (the only rule on record above R72 on both counts; it failed its own
gates and runs by Boss's 6 Nov exception)**. R154 - R157: every 10-15 point scalp loses. The experiment log holds 3,479
entries: 3,120 rejected, 7 adopted, 51 paper-only (the arms and their own earlier exit choices).

## 1. The idea (new)

R154 - R157 trained on small fixed targets. Breadth Donchian earns from its EXIT (a tight stop, locks that let winners
run). R124 showed a network can see that a large move is coming. So: **train the network on the result of Breadth
Donchian's own exit at every 5-minute moment**, with the cross-index bars (the breadth) as input, and ask it (a) for
entries of its own and (b) which of R72's entries to keep.

- **Examples:** R156 / R157's 733,260 moments (every 09:15-anchored 5-minute close 09:19 - 14:44, both sides, 5 indices,
  Aug 2021 - 13 Oct 2026; post 14 - 19 Oct), with R157's 13-channel sequence and the 43-number table, unchanged.
- **Label:** the net result (1 lot, R17 fills, dated charges) of buying the 1-ITM option at the next minute with exits XL,
  priced by R106's engine exactly as the arm's own trades. Expiry days have no label (the arm never trades them).
- **Network:** R157's (three 1-D convolutions over 13 channels + the table branch), one output pair: win logit and
  expected net; same loss, optimiser, 6 epochs, early stopping, **3 seeds (158, 159, 160) averaged**, same expanding
  walk-forward folds (2023, 2024, 2025, Jan - Mar 2026, final + post).

## 2. Rules (5 tests, 1 lot, fixed now)

Standalone rules take a side at a moment under R72's own taking rule (per index: one open position a side, at most 2
fills a side a day; no expiry days):

| id | rule |
|---|---|
| NET-EV | take a side when its forecast net is > 0 |
| NET-TOP5 | take a side when its forecast net is in the top 5% of the model's training forecasts |
| NET-TOP1 | the same, top 1% |
| PICK | Breadth Donchian's own signals, kept only when the forecast net for that moment and side is > 0 (removed before the arm's walk, as R101). A signal after 14:45 (outside the examples) cannot be scored and is taken |
| PICK50 | the same, kept only when the forecast net is at or above the median of the model's training forecasts |

## 3. Periods and gates

O1 2023 - Mar 2025 (standalone rules only; R72's stores begin 20 Nov 2024); P2 Apr 2025 - Mar 2026; P3 final 1 Apr - 13
Oct 2026, read once by R158 (**R72's own trades there were read by R72 - R153; weak evidence**); P4 post, reported.
1. Net > 0 in P2 and P3 (standalone: also O1).
2. **Beats Breadth Donchian: a higher win rate AND a higher net than R72 (B0) in P2 and in P3.**
3. Better than chance: P3, 2,000 twins (seed 158). Standalone: R156's random-entry twin (the same number of trades per
   index and session at random labelled moments and sides). PICK rules: random subsets of R72's P3 trades of the same
   size. BH q <= 0.10 with m = 2,127 earlier (R157's tally) + 5 = 2,132.
4. At least 40 final trades.

Reported, never gated: against R72 + R151's picker (652 / 48.9% / Rs 2,19,057); worst day, drawdown, net without the 5
best trades; per index; AUC and calibration tenths of the forecast; the shuffle test; how many of the network's own trades
coincide with R72's.

## 4. What a pass means

A passing rule could act on PAPER (a labelled switch; PICK rules only remove R72 trades). Nothing goes live from R158:
anything new that could skip live trades starts in shadow. Failures stay notes here and in
`research/experiments/entries/r158-*.yaml`.

## 5. Tests

`test_r158.py`: XL labels equal to the arm's own engine on the arm's own candidates, no label on expiry days, the
side-wise walk (one open a side, 2 a side a day, thresholds, missing labels), the picker's keep rule (unscored = taken),
fold thresholds from training rows only, the beat-R72 gate, and R156's twins reused.
