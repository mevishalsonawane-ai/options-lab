# R163 notes: things learnt while running (nothing in the rules or the bar changed after PREREG.md)

## Before any R163 number

- **N0. Reproduction.** R151's `export_picker.py fit` and `study_c.py run` were re-run unchanged on this session's lake copy
  (Drive, fetched 12 Nov 2026; files to 19 Oct 2026) and R51's clean stores rebuilt here. R72's picker gave exactly
  R151's committed numbers (Apr - Oct 2026: 652 trades, 48.9%, Rs 2,19,057; WF 634 trades, 52.5%). The committed
  `c_r72_picker.json` was restored afterwards (only its timestamp differed).
- **N1. Python.** Everything ran on Python 3.11 with `research/brain/requirements.txt`, plus lightgbm 4.7.0, numba and pyyaml
  (R106's engine needs numba). The R51 stores were built with `R51_CLEAN=1`.
- **N2. The MLP's early stopping** is done by hand (one epoch at a time, validation on the last 20% of the training rows in
  time order, 20 epochs patience), because sklearn's built-in early stopping takes a random 20%. This is what PREREG 5 says.
- **N3. Dry run.** Before the FINAL read, the `final` code ran on design data only (training to 31 Dec 2025, "final" = Q1
  2026, outputs in a scratch folder) to make sure a crash could not waste the one read.

## After the walk-forward (before FINAL)

- **N4.** No model passed WF (section 7.1). PREREG 6 says FINAL is still reported; it was read once (`results/FINAL_READ`).

## After FINAL (reporting only)

- **N5. Missing inputs at 09:20.** About 22% of trades are 09:20 breaks; their 15 / 30-minute windows (breadth 15-minute return,
  VIX 30-minute change, OI changes over 15 minutes) do not exist yet and are missing (trees handle it; the MLP / logistic use the
  training median). Expected, not a bug.
- **N6. A raw threshold can move with the model.** E1 on R51 found no WF threshold reaching P151's net; its report-only
  threshold (the WF point with the best win rate) is 0.4696. The frozen E1 model (trained on 25% more data) scored every
  FINAL trade between 0.40 and 0.50, so that threshold kept 2 trades. This is the known risk of a raw-score threshold
  (PREREG 6 fixed it as raw, as the app would run it). It changes no verdict: E1 had already failed WF.
- **N7. The app's rule matters for R151's R72 picker.** In R151's research walk (skipped breaks freed their side) its
  Apr - Oct 2026 result is 652 trades / 48.9% / Rs 2.19 lakh (reproduced exactly here). In the app's walk (the side stays
  busy) it is 603 trades / 49.1% / Rs 1.70 lakh, against the plain arm's Rs 1.71 lakh. R51's: 693 / 49.9% / Rs 1.59 lakh vs
  653 / 50.4% / Rs 1.43 lakh (plain Rs 1.35 lakh). So in the app the pickers buy a higher win rate and about half the drawdown,
  not extra money. Worth telling Boss; no app change is proposed by R163.
- **N8. Not used:** R123's network score (never stored; rebuilding out of reach), the OLD period, and the post window (14 - 19
  Oct, 4 sessions).
- **N9. Multiple testing.** m = 2,164 (R160's tally) + 8. R161 / R162's entries are not on main yet; their raw p-values and
  R163's (`results/pvalues.csv`) go into one joint BH. With m >= 2,172 no p-value here can clear q 0.10 (the smallest is
  0.00078; it would need about 0.00004).
- **N10. Found in the self-review after FINAL: one feature's sign.** PREREG 4 describes `writers0920` as "own side minus
  other side"; the code computes the OTHER side's OI added minus the own side's (R105F O2's reading: writers add on the side
  they expect to hold). A feature's sign does not change what trees, the MLP or a logistic model can learn (the split or the
  weight flips): the trees and the logistic are equivalent either way; the MLP's (and so E1's) exact numbers could differ
  slightly through their random start, not in kind. Disclosed, not re-run (FINAL was read once). A dead variable in `f4` was removed in the same review (no effect).
