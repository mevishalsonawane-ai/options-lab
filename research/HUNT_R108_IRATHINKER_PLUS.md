# R108: can IraThinker's strategies be improved? (26 Oct 2026)

Boss, 26 Oct, after R107: "Can we enhance it" → "Improve the strategies".

## The answer in short

**One change looks worth pursuing; the rest did nothing.** I tested 10 pre-registered changes on the same data as R107.

- **Strangle + stop (E1):** the BANKNIFTY Monday strangle with a stop, closing both legs once buying them back costs twice
  the credit.
  - Results: +₹4,117 a week over 36 weeks, 78% won, p 0.002, passes the multiple-testing check (q 0.01, also 0.02
    counted with R107's 13 tests).
  - The worst week falls from −₹31,283 to −₹15,552 and the worst month from −₹10,080 to −₹5,617.
  - **It is still not proven.** 36 weeks is under the 40 required. That was known before running: the data has real
    monthly BANKNIFTY prices only from Nov 2024.
  - It is still naked option selling, with a gap risk the stop cannot stop (the loss can exceed one credit; one stopped
    week lost −₹24,548), and about ₹2 lakh margin.
- **Strangle with protective wings (iron condor, E2): much worse.** +₹697 a week (29 weeks, p 0.14). The wings cost more
  than the tail they remove.
- **Strangle skipping expiry weeks (E3):** +₹4,423 a week (25 weeks), but this idea came from looking at R107's losses
  (post-hoc), on even fewer weeks. Not trustworthy.
- **mom60: no fix found.**
  - One strike in the money: NIFTY +₹56, BANKNIFTY +₹12 a trade.
  - No 30% stop: NIFTY +₹64, BANKNIFTY +₹9.
  - The weekly contract instead of the monthly: NIFTY −₹13 over 473 trades.
  - All are about zero (p 0.40–0.56), and BANKNIFTY loses at 2x slippage.
- **Prediction engine, strong votes only: no.** NIFTY −₹184 a trade (156 trades), BANKNIFTY +₹1 (172).

**I made a mistake.** While checking E1's stopped weeks I printed one holdout week (19 Jan 2026), which I had promised
not to look at.
- In that week E1 lost −₹24,548, where holding would have made +₹3,492.
- No other holdout number was seen, and no strangle variant could pass design anyway.
- But the strangle's holdout (Oct 2025 – Feb 2026) can no longer serve as a clean judge.

**What to do next with E1 (recommended):**
1. Test it on data nobody has looked at: the 2021 – Nov 2024 BANKNIFTY monthly options in the Drive lake
   (`opt_BANKNIFTY_MONTH_*`). That needs this session to have the Drive data, through rclone access or a release upload.
2. That gives over 150 more Mondays: enough to pass or fail it for real.

Until then it stays a note (CLAUDE.md: ideas that have not passed the gates are notes only).

## The 10 changes (design period, ₹ per trade or week, 1 lot, after charges and slippage)

| ID | Strategy | Change | Tag | Trades | Won | Mean ₹ | Total ₹ | p | q (10) | Total at 2x slip | Worst month ₹ | Gates |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E1 | BANKNIFTY strangle | stop at 2x the credit | ex-ante | 36 | 77.8% | **+4,117** | +1,48,224 | **0.002** | **0.01** | +1,47,002 | −5,617 | all but n ≥ 40 (descriptive by design) |
| E2 | BANKNIFTY strangle | wings at ATM ± 600 | ex-ante | 29 | 58.6% | +697 | +20,206 | 0.14 | 0.48 | +18,609 | −5,893 | fail |
| E3 | BANKNIFTY strangle | skip expiry weeks | post-hoc | 25 | 84.0% | +4,423 | +1,10,585 | < 0.001 | < 0.001 | +1,09,528 | −3,845 | all but n ≥ 40 (post-hoc) |
| E4 | NIFTY mom60 | one strike ITM | ex-ante | 118 | 50.8% | +56 | +6,635 | 0.42 | 0.62 | +4,716 | −9,056 | fail |
| E5 | BANKNIFTY mom60 | one strike ITM | ex-ante | 214 | 50.5% | +12 | +2,459 | 0.47 | 0.62 | −2,874 | −22,728 | fail |
| E6 | NIFTY mom60 | no 30% stop | ex-ante | 118 | 50.0% | +64 | +7,577 | 0.40 | 0.62 | +5,921 | −11,003 | fail |
| E7 | BANKNIFTY mom60 | no 30% stop | ex-ante | 213 | 50.7% | +9 | +2,001 | 0.48 | 0.62 | −2,947 | −21,658 | fail |
| E8 | NIFTY mom60 | weekly contract | ex-ante | 473 | 44.4% | −13 | −5,925 | 0.56 | 0.62 | −13,041 | −28,905 | fail |
| E9 | NIFTY prediction | strong votes only | ex-ante | 156 | 41.0% | −184 | −28,718 | 0.86 | 0.86 | −30,732 | −9,778 | fail |
| E10 | BANKNIFTY prediction | strong votes only | ex-ante | 172 | 40.7% | +1 | +217 | 0.51 | 0.62 | −2,722 | −13,386 | fail |

### Strangle details

| | R107 original | E1 + stop | E2 + wings | E3 skip expiry weeks |
|---|---|---|---|---|
| Weeks | 36 | 36 | 29 | 25 |
| Net, % of premium | +13.9% | +15.9% | +11.4% | +12.6% |
| Worst week | −₹31,283 | −₹15,552 | −₹7,326 | −₹8,213 |
| Exits | 25 Thursday, 11 expiry | 25 Thursday, 8 expiry, 3 stopped | 18 Thursday, 11 expiry | 25 Thursday |

The stop changed 3 design weeks, all expiry weeks:
- Mar 2025: stopped −₹13,656, where holding made +₹2,628;
- Jun 2025: −₹14,612 instead of −₹17,637;
- Aug 2025: −₹11,445 instead of −₹31,283.

## How it was tested

Exactly as R107 (`research/HUNT_R107_IRATHINKER.md`):
- IraThinker's own signals;
- real NIFTY and BANKNIFTY options, dated charges, 'liq' slippage, one lot;
- monthly-contract rules only where the nearest contract is the monthly;
- design to 30 Sep 2025.

Only the trade built on each signal changed; no IraThinker signal rule changed.

The 10 changes were fixed in `research/hunt/r108_irathinker_plus/PREREG.md` before any result, each tagged ex-ante or
post-hoc. The ORB alert and gap fade were deliberately not retried: ORB has failed here and in three earlier studies.

| File | What |
|---|---|
| `research/hunt/r108_irathinker_plus/PREREG.md` | the plan, with the note on the holdout week seen by accident |
| `research/hunt/r108_irathinker_plus/r108study.py` | the 10 variants (reuses `r107study.py`) |
| `research/hunt/r108_irathinker_plus/test_r108.py` | 8 tests (stop timing, no look-ahead, wings, settlement, ITM strike) |
| `research/hunt/r108_irathinker_plus/results/results.json` | every number |
| `research/experiments/entries/r108-*.yaml` | 10 entries |
