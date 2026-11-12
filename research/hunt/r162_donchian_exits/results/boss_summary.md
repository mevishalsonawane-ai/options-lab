## For Boss: short answer (12 Nov 2026)

**No trade-structure change lifted the Donchian win rate by 5 points without losing money, so nothing changes in the
app.** I tested 18 ways of managing the same entries on both arms (R72 and R51) at 1, 2 and 3 lots: 102 tests. None
passed. The pick of the bunch:

- **Tight lock steps (LF2)** gave the biggest win-rate rise (final: +4 to +5 points, walk-forward: +5 to +6), but cost
  money in every period: R72 at 1 lot made Rs 28,038 less in the final and Rs 85,848 less in the walk-forward year. More
  small wins, fewer of the big ones.
- **Breakeven after +10% (BE10)** added +3 to +4 points (R72 1 lot final 45.8% -> 49.5%, 1,203 trades), with a little
  less net (-Rs 6,757 final, -Rs 54,606 walk-forward).
- **Selling 1 lot early at +8 / +10 / +15% (scale-out)** won more rounds but cut net hard: R72 at 2 lots made Rs 1.5 L to
  Rs 2.7 L less in the final (1,202 trades). Same lesson as R105C / R152: small targets cap the big winners these arms
  live on.
- **Holding winners past 30 minutes (NIP30)** was the worst: win rate fell 11 points and most of the profit went.
- **Strike and time changes** moved net, not the win rate. Delta-0.6 strikes (D60) on R72 made more in both periods
  (1 lot: +Rs 21,674 final, +Rs 84,652 walk-forward) but won 0.3-0.7 points less; on R51 it lost in walk-forward.
  2-ITM (ITM2) made more in the final but not in walk-forward; a 40-minute time stop (T40) made more in walk-forward
  but less in R51's final. (D60 on R72 is a profit idea, not a win-rate one; it would need its own pre-registered test.)
- **Index stop instead of / as well as the -15% premium stop** lowered the win rate by 3 to 5 points.

Today's numbers, for comparison (final, 1 Apr - 13 Oct 2026, 1 lot): R72 1,202 trades, 45.8% won, Rs 1,71,366; R51
1,670 trades, 44.9% won, Rs 1,34,743. The win rate needed to break even is about 41-42%, because the average win (R72 Rs 1,748) is
bigger than the average loss (Rs 1,217), so the arms make money at ~46%. Everything that pushed the win
rate up did it by selling winners sooner.

**On the picker's trades (what the app trades now; second look, not a test):** the same picture: BE10 +4 to +5 points
with about the same net; LF2 +5 to +6 points but lower net.

**The separate picker question (when a skipped break frees its side):** most of the gap between the app and R151's
research comes from the skipped break **counting in the 2-a-side daily cap**, not from how long it holds the side.
R72: the app's rule 603 trades / Rs 1,69,674; releasing at once (still counted in the cap) 613 / Rs 1,74,383; holding
5 or 10 minutes 607 / Rs 1,79,703 and 603 / Rs 1,80,006; R151's research walk (not counted at all) 652 / Rs 2,19,057.
R51: 653 / Rs 1,42,884 vs at once 657 / Rs 1,47,459 vs R151's 693 / Rs 1,59,040. Not counting it in the cap would let
the arm take trades it would not have taken without the picker, which is what your 6 Nov rule forbids, so this is
for information only.

Honesty notes: Apr - Oct 2026 has been read by many studies (weak evidence). The best twin p-values (0.002, scale-out
and the 20-minute stop) do not survive Benjamini-Hochberg once all 1,349 tests so far are counted (q 0.19). The
next-week-expiry idea could not be tested: the data has no next-week option series.

**Correction (12 Nov, later the same day):** the multiple-testing count above started from R153's chain (1,247 earlier
tests). The repository's latest count before R162 was R160's m = 2,164, so the right m is 2,164 + 102 = 2,266. With it
the smallest q is 0.32 instead of 0.19. Nothing passed either way; the verdict does not change.
