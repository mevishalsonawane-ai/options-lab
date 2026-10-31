# R139 Amendment B pre-registration: reversal logic

Written 31 Oct 2026, after the R139 results and before any Amendment B number. Research only (no app code).

Boss, 31 Oct, while Amendment A was running: *"Check the adding reversal logic too and check if it could help??"*

"Reversal logic" is tested in its two usual meanings:

1. **Flip (F1-F11):** most R139 rules lost money, so take each one **the other way**.
   - The same signal minute; the **opposite** ATM option (a put where the rule bought a call).
   - The stop is the same index distance as the rule's, on the other side of the entry open; target 2R.
   - F1 is S1 flipped, ..., F11 is S11 flipped. S12, which needs the network, is left out.
2. **Stop-and-reverse on S3 (SR3):** S3 as in R139. When an S3 trade exits at its stop, buy the **opposite** ATM
   option at the next minute (if it is 15:00 or earlier).
   - Stop 0.02% beyond S3's entry level; target 2R.
   - Once per S3 trade: a reverse trade that is stopped is not reversed again.
   - The reverse trade blocks the index like any trade.

## Honest limits

- **The holdout months (1 Apr - 19 Oct 2026) were already read once for these signals** (R139 read the rules the
  right way round). Amendment B gets one read with its own lock (`HOLDOUT_READ_B.json`), labelled **"already seen"**.
- **A flipped losing rule is not a winner by arithmetic.** Both directions pay the option's time decay, charges and
  slippage. A rule that lost after costs can lose both ways.
- Signals stay on the index (R139's detectors, unchanged). Only the side bought and the exits change.

## Tests, gates and what follows (R139's, unchanged)

- 100 random-entry twins per trade (entry uniform from the trade's minute to 15:00, the same stop and target).
- BH over the 12 (F1-F11, SR3).
- **Design gates:**
  1. >= 300 trades;
  2. average net > 0 at 1x and 2x slippage;
  3. twins p < 0.05;
  4. q <= 0.10;
  5. >= 3 of 4 folds positive;
  6. Boss's account stop on 1 lot (drawdown above -Rs 40,000, no month below -Rs 13,000).
- **Holdout (already seen), read once:** average net > 0 at 1x and 2x on >= 50 trades.
- **Win rate vs 65%** is reported for every version.
- For SR3, the reverse legs are reported on their own too.
- A version that passes goes to Boss as a **forward-test proposal only**.
- Files:
  - `r139b.py`, `test_r139b.py`;
  - `results/design_b.json`, `results/holdout_b.json`;
  - entries `r139b-*.yaml`.
