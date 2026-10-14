# IV1 pre-registration: option-market signals (IV skew, straddle, PCR, OI build-up) for CE / PE buying

Written 14 Oct 2026, BEFORE any IV1 strategy was run. Boss said "ok" to the follow-up proposed in
`research/HUNT_R30_ALL_PARAMETERS.md`: build a study around the option-chain inputs R30's models leaned on.

**Honest framing.** These inputs were chosen BECAUSE they ranked high on R30's walk-forward test periods (Jul 2024 –
Oct 2026). Testing them again on those periods would re-use what chose them. So the walk-forward below is
**descriptive only** (it can rule a strategy out, not in), and the **confirmation is forward**: the same first 40 NIFTY
/ BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX sessions from 13 Oct 2026 as R30's forward test. F1's locked holdout
(Jan 2023 – Jun 2024) stays untouched.

## Data, grid, trades

R30's decision table (`research/hunt/r30`): every 5-minute bar close 09:30–14:45, 5 indices, ATM CE / PE bought at the
next minute's open, Boss's lock (X1), R29/R30 fills and app charges, one trade at a time, at most 3 a day.

Option-market inputs (R30's chain features, point in time): `atm_iv`, `atm_iv_chg15`, `iv_skew2`, `pcr_oi`,
`pcr_oi_chg30`, `oi_ce_chg30_pct`, `oi_pe_chg30_pct`, `oi_ce_atm_chg30_pct`, `oi_pe_atm_chg30_pct`, `straddle_pct`,
`straddle_vs_0920`, `straddle_chg15_pct`, `vol_ce_15`, `vol_pe_15`, `vol_ratio_15`, `maxoi_call_dist_pct`,
`maxoi_put_dist_pct`; plus context `minute_of_day`, `dte_days`, `is_expiry_day`, `vix`, `und_id`.

## Strategies (family: 3 × 5 indices = 15)

| id | rule |
|---|---|
| O1 | R30's M4 (P(net > 0) per side, top 5% of training, same fixed GBM settings) on the option-market inputs only |
| O2 | R30's C1 (best 20 tercile-pair cells per side, ≥ 300 rows, ≥ 60 days, positive every training year) on the option-market inputs only |
| O3 | a fixed hand rule, nothing fitted: **CE** when puts are being written and calls covered (`oi_pe_atm_chg30_pct` > 0 and `oi_ce_atm_chg30_pct` < 0), PCR rising (`pcr_oi_chg30` > 0) and the straddle expanding over 15 minutes (`straddle_chg15_pct` > 0); **PE** the mirror (calls written, puts covered, PCR falling, straddle expanding) |

## Walk-forward (descriptive) and the forward test (confirmatory)

- Walk-forward: R30's folds F1-F3, reported with R30's metrics and gate, labelled descriptive.
- Every strategy × index whose walk-forward net is > 0 in every test period it trades is **frozen** (the F3-fold
  model / cells, or O3 as written) and scored on the first 40 forward sessions from 13 Oct 2026, logged only, read
  once together with R30's forward test.
- **Forward pass:** net > 0, p < 0.05 against 1,000 random-twin sets, > 0 at 2× spread, and BH q ≤ 0.10 over the
  forward tests (R30's M4-NIFTY + the frozen IV1 ones). A pass becomes a PAPER candidate behind an off switch; never
  live.
