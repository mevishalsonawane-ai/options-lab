# R133 pre-registration: every R131 ICT / SMC entry, filtered by R123's network, with each R132 exit

Written 29 Oct 2026, before any R133 number. Research only: nothing in the app changes, nothing trades.

Boss, 29 Oct, after R132: *"What about other smc/ict strategies enhance them too, use neuro network and profit lock
and stop and try again"*.

## 0. What this adds, and its limits

- R132 tried R131's ten as-stated entries with the four R132 exits (no network), and with R123's network filter
  (R131's own exits only). Only CISD + the network's EV filter passed the design gates, then it had 21 trades on the
  holdout period.
- R133 applies the **network's EV filter (N-EV, R132 Amendment A, unchanged)** to **all 39 R131 entries** (V1-V3 of
  all ten strategies and the 9 wick-only W variants), each with the **five exits**: E0 (R131's own index stop and
  structural target) and E1-E4 (R132's premium stops, the 60% lock after +Rs 1,000, and the breakeven trail,
  unchanged). That is 39 x 5 = 195 combinations. The 10 V1-E0 combinations were already reported by R132 and are not
  counted again, so **185 new variants, one BH family**.
- **The limits are R132's.** Network views exist out of sample only for 2023 - 15 Mar 2026, so that is the design. The
  holdout period (1 Apr - 19 Oct 2026) was already read for these entries, so it is a check, not proof. The lake ends
  on 19 Oct. **Many tries on the same data:** R131-R133 together try about 285 rules. At best a passer is proposed for
  a forward test, by Boss's choice.

## 1. Rules (nothing new is defined)

- Entries: R131's detectors and entry filters, unchanged (`research/hunt/r131_ict_ten/r131.py`, `ALL_VARIANTS`).
- Exits: E0 is R131's priced trades; E1-E4 use R132's simulator (`research/hunt/r132_ict_exits/r132.py`, `sim_exit`),
  one trade at a time per index per variant, out by 15:20.
- Filter: N-EV (R132 `r132nn.py`). Keep a trade only if the mean `ev_j` of its side's 8 cells, in R123 N1's latest
  walk-forward view from at most 5 minutes before entry, is > 0.
- One trade at a time is applied **before** the filter (the filter only removes trades), as R132 Amendment A did.

## 2. Tests and gates (per variant, design = folds F1-F4)

- **The filter test:** p = the share of 2,000 random subsets of the same entry and exit's trades, with the same count
  in each fold, whose total is ≥ the kept trades' total. BH over the 185.
- **Gates:** ≥ 300 kept trades; average net > 0 at 1x and 2x slippage; ≥ 3 of 4 folds positive; BH q ≤ 0.10;
  drawdown above -Rs 40,000 and no month below -Rs 13,000 on 1 lot.
- **The seen holdout period:** read once, as a check. A passer must also be positive there on ≥ 50 kept trades.
- The 65% rule does not apply (Boss's strategies). Win rates are reported.

## 3. Outputs

- `research/hunt/r133_ict_nn_exits/` (`r133.py`, `test_r133.py`); `results/design.json`, `results/holdout_seen.json`.
- `research/HUNT_R133_ICT_NN_EXITS.md`; entries `research/experiments/entries/r133-*.yaml`; RESULT.md updated.
