## Outcome: none of the 24 pre-registered tests passes the gate

Boss's question: *"Does support/resistance help R51, R72 and Liquidity?"* Short answer: **this study cannot show that any
support / resistance piece helps any of the three arms reliably.** Every number below is 1 lot, after dated Zerodha
charges and R17 slippage, 1 Nov 2024 - 13 Nov 2026 (W1 = Nov 2024 - Mar 2026, where R151's pickers were trained; W2 =
Apr - 13 Nov 2026, out-of-sample for the pickers but already read by R151-R193, so not fresh).

**R72 Breadth Donchian**
- *S/R inside the picker (A, B).* With its five S/R features set to "missing", the frozen picker keeps 1,848 trades and
  makes Rs 4,99,737 instead of 2,256 trades and Rs 6,52,008. But all of that difference is in W1, where the picker was
  trained (Rs 4,99,387 vs Rs 3,46,009). In W2 the S/R features changed almost nothing: 726 trades / Rs 1,52,620 with them,
  607 / Rs 1,53,728 without (drawdown Rs 37,082 vs Rs 41,116). A picker retrained by R151's own procedure without the S/R
  features made more money: Rs 6,60,987 over the span and Rs 1,73,629 in W2 (828 trades), with a larger W2 drawdown
  (Rs 55,383). So the S/R features look useful only where the model saw the answers.
- *Explicit S/R filters (C).* All six cut money badly in both windows (skipping breaks with a level within 0.15% / 0.30%
  ahead, or taking only breaks that crossed a level), on own exits and on top of the picker.
- *S/R target (D).* Selling at the next 3+-touch level ahead cut net in both windows on top of the picker (Rs 4,31,272 vs
  Rs 6,52,008) and on top of the live V5 (Rs 2,77,466 vs Rs 3,58,078).

**R51 Donchian 20 + Supertrend**
- *S/R inside the picker.* Ablated: Rs 4,78,169 vs Rs 4,88,964 over the span, but in W2 the picker WITHOUT its S/R inputs
  did better (709 trades / Rs 1,60,497 vs 791 / Rs 1,40,702). Retrained without S/R: Rs 4,83,954 vs Rs 4,88,964, and again
  more in W2 (Rs 1,60,119). The S/R features help R51's picker only in-sample, if at all.
- *Filters and target.* Same picture as R72: every filter and the target lost money in both windows. The filters did lower
  drawdowns on top of the picker (e.g. WALL15: W2 drawdown Rs 26,705 vs Rs 38,799) but by giving up about half the net.

**Liquidity 15+5**
- *Room check.* The live check (room >= 1 index stop to the next level) is the best of the four rooms on net in W1
  (Rs 2,49,576 vs 2,15,413 with no check, 2,39,895 at 0.5, 2,36,955 at 2). Removing it (room 0) or loosening it (0.5)
  lost money in both windows, and the skips it makes were better than random skips of the same number (L1's net sits high
  among the twins: lower-tail p 0.998 / 0.965, i.e. roughly p 0.003 / 0.04 the other way; descriptive, not a pre-registered
  test in that direction). A stricter room 2 made a little more in W2 (Rs 6,715 vs Rs 5,717) with a smaller W2 drawdown,
  but less in W1 and twin p 0.14: no evidence.
- *New-level exit.* Switching it off made less in W1 (Rs 2,16,139 vs Rs 2,49,576), more in W2 (Rs 10,546 vs Rs 5,717,
  smaller drawdown), twin p 0.10: no evidence either way. Liquidity barely made money in W2 under any setting.

**Which piece, in one line each.** R72 / R51: no S/R piece helps; the picker's S/R features add money only in its training
years, and explicit S/R skips and targets cost money. Liquidity: the room check that is live now is the S/R piece with the
best case (it beats no check and a looser check on net in both windows), but it did not have to pass a test in that
direction here, and a stricter check did not help. Nothing in the app changes.

**Caveats.** W2 is out-of-sample for the pickers but has been read by many studies (R151-R193), so it is not fresh. Family
C repeats the kind of filter R150 already rejected on other years (not independent). The exit twins (a random sale between
the fill and the baseline's exit) are a weak benchmark: D-TGT on V5 has twin p 0.0005 although it lost money against V5 in
both windows; its verdict rests on the net and drawdown checks. The Liquidity room 0 / 0.5 and NL0 twins test the reverse
tail as pre-registered. The B retrain reproduced R151's app models to the last digit (max difference 0), and every
baseline reproduced R193 to the rupee. Nothing here speaks for years before Nov 2024.
