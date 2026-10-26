# R105D: "volume confirms the breakout" (the books' volume idea) on real index-futures volume

26 Oct 2026. Exploratory, research only. Pre-registered in `research/hunt/r105d_volume/PREREG.md` (76e5f84) before any
number. Code, 6 tests and the numbers: `research/hunt/r105d_volume/` (`results/volume.json`).

Boss, 26 Oct: *"Test the volume idea from the books."* The books (Wyckoff, Aziz, Woods, Porwal, Larry Swing) say a
breakout on high volume is real, and one on low volume is suspect.

## The answer

**No. On the futures volume we have, high-volume breakouts did no better than low-volume ones on either Donchian arm.**

| 29 Jul - 19 Oct 2026, 1 lot | R72 Breadth Donchian (457 trades) | R51 Donchian 20 + Supertrend (704 trades) |
|---|---|---|
| all trades | 46% win, Rs 1,18,506 (Rs 259 a trade) | 44% win, Rs 67,756 (Rs 96 a trade) |
| **high-volume break** (signal bar >= 1.5x its usual volume at that time of day) | 187 trades, 44% win, Rs 322 a trade | 229 trades, 45% win, Rs 148 a trade |
| **low-volume break** | 133 trades, **52% win**, **Rs 344 a trade** | 255 trades, 45% win, Rs 140 a trade |
| volume not known yet (first days of a contract) | 137 trades, Rs 92 a trade | 220 trades, -Rs 8 a trade |
| high better than low? (permutation p) | no (p 0.54) | no (p 0.48) |

Read across:
- **On R72, low-volume breaks won more often (52% vs 44%)** and made about the same per trade.
- **The halves disagree.** High volume was better in Aug and worse in Sep - Oct on R72. On the 4 unseen sessions
  (14-19 Oct) high-volume breaks lost on both arms (R72 -Rs 2,020 on 15 trades; R51 -Rs 2,707 on 17).
- **The second measure agrees** (the bar against the previous 20 bars): p 0.57 (R72) and 0.23 (R51), and the halves
  flip again. BH q 0.57 for all 4 tests.

**This matches R104's tests of the same idea:**
- option-volume surges as confirmation lost Rs 3.77 lakh in design and Rs 1.04 lakh in the holdout;
- daily futures volume also failed.

So the books' volume rule does not help these arms. Nothing changes.

## Honest limits

- **Only 57 sessions of futures minutes exist** (29 Jul - 19 Oct), all inside periods already studied. The test
  cannot be a full design / holdout test; it can only show whether a strong difference exists, and it does not.
- **The recorded contract was the far month until 30 Sep.** The Aug and Sep contracts were not recorded, so for two
  months the volume is a thinner contract's. Its volume still rises and falls with the market, and it is compared
  only with itself.
- **The phone's own futures-tick recorder will give near-month volume.** Re-run this after about 3 months of it
  (around January 2027). That is the only clean test still open.
- **Signal on the index (unchanged); volume only filters it.** No trade was re-priced.
