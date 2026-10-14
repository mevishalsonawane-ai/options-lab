# R45: audit of the "NIFTY premium mean-reversion scalp" (14 Oct 2026)

**Verdict: the +Rs 174 a trade is not real.** It comes from the lake's *rolling* ATM series switching to a
different contract in the middle of a trade. Priced on one contract you could actually hold, the same signals lose
**Rs 65 a trade** before real costs (2,826 trades). With every fix applied they lose **Rs 150-170 a trade** (1,125-1,250
design trades, every year negative). The holdout (27 Apr - 13 Oct 2026, read once) also loses: Rs -106 and -127 a trade
(122 and 130 trades). **Nothing survives the gates.** Nothing goes to paper.

**Signal source:** this rule reads its signal from the **option's own price chart** (5-minute bars of the ATM call
premium), not from the index. Under CLAUDE.md that is not the default. It is reported separately here, and only Boss
could switch such a rule on for paper. There is nothing to switch on.

Pre-registered in `hunt/r45/PREREG.md` (commit b1b24f3, pushed before any P&L). Code, tests and results are in `hunt/r45/`.
Experiment entries are `experiments/entries/r45-*.yaml` (21 files).

## The claim

Another AI, working on the same Drive lake, reported the following.

- **Result:** 3,726 trades from 2021 to 2026, +Rs 649,957 net, +Rs 174 a trade, 53.4% wins, profit factor 1.45, 52 of
  63 months green, max drawdown Rs 24,809, and every year positive. BANKNIFTY made +Rs 14 a trade.
- **Rule:**
  - On 5-minute bars of the NIFTY ATM call premium, buy at the next bar's open when the close crosses below the
    Bollinger(20, 2) lower band.
  - Stop at 10% below entry. Stepped profit lock at 25/50/75/100%. Time exit after 15 minutes.
  - At most 2 trades a day, square-off at 15:15, expiry days skipped. Costs Rs 95 a trade, lot 75.
- **Code:** the reference `simulate_trades()` was not attached to the request. The rule was rebuilt from its text, with
  the details listed in the PREREG.

## Step 0: reproduction

We tried 12 pre-registered combinations on design sessions (to 13 Apr 2026): the WEEK series, the MONTH series, or both;
a band reset each day or carried across days; population or sample SD. The closest match was **WEEK + MONTH pooled,
band carried across days**:

| | their claim | our reproduction, design (Aug 2021 - 13 Apr 2026) | our reproduction, full span (after the holdout read) |
|---|---|---|---|
| trades | 3,726 | 2,843 (claim scaled to design ≈ 3,360) | 3,108 |
| net / trade | +174 | **+264** | +252 |
| win rate | 53.4% | 59.8% | 59.2% |
| profit factor | 1.45 | 1.70 | 1.67 |
| green months | 52/63 | 51/57 | 54/63 |
| max drawdown | 24,809 | 25,937 | 26,083 |
| per year (Rs/trade) | 231, 195, 190, 73, 120, 125 | 384, 323, 347, 177, 241, −21 (to Apr) | 2026 full: +66 (422 trades) |

The claim reproduces **in kind**: our version is even more profitable than theirs, and like theirs it is green almost
every month, with 2024 the weakest full year. It does not match exactly, which is not surprising without their code.
- **Series:** WEEK alone made +Rs 303 a trade (1,362 trades), MONTH alone +Rs 229 (1,481).
- **Trades a day:** 3.2 a day (their figure) is only possible with two series pooled (see P4).

## The fixes, one at a time (design sessions; Rs per trade; T0 = the reproduction)

| version | trades | win | net/trade | change vs T0 | PF | green months | max DD | holdout trades, net/trade |
|---|---|---|---|---|---|---|---|---|
| **their claim** | 3,726 | 53.4% | +174 | | 1.45 | 52/63 | 24,809 | |
| T0 reproduction | 2,843 | 59.8% | +264 | | 1.70 | 51/57 | 25,937 | 265, +118 |
| **P1a one fixed contract** | 2,826 | 42.5% | **−65** | **−329** | 0.85 | 17/57 | 187,146 | 265, −116 |
| **P1b 09:15 contract all day** | 2,647 | 42.3% | **−109** | **−373** | 0.73 | 16/57 | 301,831 | 291, −59 |
| P2 gap fills + no same-bar lock | 2,833 | 55.3% | +133 | −132 | 1.34 | 43/57 | 28,511 | 265, +36 |
| P3a 15-minute hold (as stated) | 2,856 | 58.5% | +240 | −24 | 1.65 | 52/57 | 24,814 | 267, +153 |
| P3b 20 minutes, last bar checked | 2,843 | 59.6% | +262 | −3 | 1.69 | 53/57 | 27,530 | 265, +117 |
| P4 WEEK only (max 2/day holds) | 1,362 | 57.5% | +303 | +39 | 1.87 | 53/57 | 14,220 | 131, +132 |
| P5 real charges, no slippage | 2,843 | 60.6% | +288 | +24 | 1.79 | 54/57 | 25,971 | 265, +131 |
| P5 real charges + 0.5% slippage | 2,843 | 55.8% | +158 | −107 | 1.38 | 43/57 | 40,687 | 265, −14 |
| P5 real charges + 1% slippage | 2,843 | 51.9% | +28 | −237 | 1.06 | 29/57 | 136,524 | 265, −159 |
| P5 historical lots (50/25/75/65) | 2,843 | 58.3% | +158 | (smaller lots) | 1.57 | 46/57 | 23,112 | 265, +89 |
| P6 real expiry days skipped | 2,719 | 61.1% | +274 | +10 | 1.72 | 53/57 | 25,948 | 254, +107 |
| **F-a all fixes, fixed contract** | 1,250 | 36.6% | **−170** | −434 | 0.60 | 9/57 | 213,921 | 122, **−106** |
| **F-b all fixes, 09:15 contract** | 1,125 | 38.4% | **−149** | −414 | 0.59 | 12/57 | 173,368 | 130, **−127** |
| F-a at 1% slippage | 1,250 | 33.4% | −253 | | 0.48 | 5/57 | 316,500 | 122, −201 |
| F-b at 1% slippage | 1,125 | 34.5% | −227 | | 0.44 | 2/57 | 258,577 | 130, −220 |
| F-a, historical lots | 1,250 | 35.7% | −143 | | 0.55 | 7/57 | 179,973 | 122, −98 |
| F-b, historical lots | 1,125 | 36.4% | −121 | | 0.54 | 9/57 | 140,029 | 130, −116 |
| BANKNIFTY T0 (lot 75, theirs) | 3,066 | 55.1% | +237 | | 1.25 | 39/57 | 139,428 | 278, +163 |
| BANKNIFTY F-a, real lots | 1,405 | 37.9% | −124 | | 0.70 | 10/57 | 199,288 | 139, −480 |

**All fixes, by design year (Rs/trade):**
- **F-a:** 2021 −75 (104 trades), 2022 −130 (251), 2023 −93 (270), 2024 −196 (279), 2025 −226 (272), 2026 to Apr −408 (74).
- **F-b:** −266 (106), −112 (251), −114 (245), −135 (234), −179 (221), −190 (68).
- **Worst month:** Rs −11,960 (F-a), Rs −11,389 (F-b).

### P1, the rolling-ATM artifact (the whole story)

- **What the lake series is:** the lake's option files hold, for each minute, whichever contract was ATM *at that
  minute*.
- **How the fake bounce happens:**
  1. When NIFTY falls, the ATM call's premium falls, and that is exactly when the rule's "close below the lower band"
     fires.
  2. If NIFTY keeps falling past the halfway point to the next strike, the series switches to the lower strike's call.
     That call is worth about Rs 25-30 more, so the "premium" jumps **up**.
  3. Nobody holding the original call gets that jump. It is a contract switch, not a price move.
- **In the reproduction:**
  - the ATM strike changed between entry and exit in **68.6% of trades** (1,950 of 2,843);
  - those trades averaged **+Rs 341**, and the trades with no change averaged +Rs 98.
- **Priced on the strike actually bought at entry (P1a):**
  - the trades with a strike change average **−Rs 140** and the rest +Rs 101, so the total falls to −Rs 65 a trade;
  - lock exits drop from 501 to 127, because the jumps were what reached the 25-100% locks.
- **Against random entries:**
  - On the rolling series, random entries on the same days lose Rs 190 a trade. The signal beats them by miles
    (p 0.003) because it is timed to catch the switch.
  - On the fixed contract (F-a), the signal is **no better than random entries** (twins −Rs 164, p 0.59, q 0.62).
  - F-b beats its random twins (q 0.008), but both lose money (−Rs 149 against −Rs 212).

### P2-P7

- **P2, gap fills and order inside a bar (−Rs 132):** their code lets a bar's own high arm a 25-100% lock, then fills
  that lock at the lock price on the same bar's low. That assumes the high came first and that no gap happened. Filling
  at the worse of the level and the open, with locks armed from the previous bar, halves the profit.
- **P3, hold length (−Rs 24 / −Rs 3):** their loop holds 20 minutes and never checks the last bar. The stated
  15 minutes costs Rs 24 a trade. Checking the last bar costs Rs 3.
- **P4, trades a day:** 3.2 trades a day means the WEEK and MONTH series were both traded. They are different contracts
  for NIFTY, but the rule's "max 2 a day" is then broken: 404 of 983 trade days had 4 trades and 182 had 3. WEEK alone
  averages 1.65 a day.
- **P5, costs:**
  - Real charges on one lot of a ~Rs 100 option are less than Rs 95, so they add +Rs 24 a trade.
  - Slippage is what hurts: 0.5% a side costs Rs 107 a trade, and 1% costs Rs 237. Their own live note expects
    0.5-1%.
  - **Lots:** historical lots were 50 until Apr 2024, 25 from Apr to Nov 2024, then 75, and 65 from 31 Dec 2025. With
    those, T0 makes +Rs 158 a trade.
- **P6, expiry filter (+Rs 10):** "any of the last 5 closes < 1.0" caught only 182 of 246 weekly and 46 of 56 monthly
  design expiry days. The days it missed left 124 expiry-day trades in T0, averaging +Rs 47.
  - The real expiry days come from the lake's time-value method. They agree with the weekday rule on 270 of 271 weekly
    expiries.
- **P7, look-ahead:** none. We cut 200 sessions at every bar (14,748 prefixes), and signals and finished trades were
  identical to the full-session run each time. Synthetic paths in the unit tests show the same.

## Gates (pre-registered; only F-a and F-b could survive)

| gate | F-a | F-b |
|---|---|---|
| G1 design net/trade > 0 (real charges + 0.5% slippage) | −170 ✗ | −149 ✗ |
| G2 positive in ≥ 4 of 5 years 2022-2026 | 0/5 ✗ | 0/5 ✗ |
| G3 BH q < 0.05 against random twins (20 variants) | 0.62 ✗ | 0.008 ✓ (beats random, still loses) |
| G4 holdout net/trade > 0 | −106 ✗ | −127 ✗ |
| G5 design net/trade > 0 at 1% slippage | −253 ✗ | −227 ✗ |
| **survives** | **no** | **no** |

## Method notes

- **Data:**
  - Drive lake `options_rolling_1m`, NIFTY and BANKNIFTY, WEEK and MONTH series, Aug 2021 to 13 Oct 2026, 1-minute
    bars resampled to 5 minutes per session.
  - Fixed contracts are rebuilt from the rows of every offset (ATM±10) that carried that strike.
  - Raw data is not in git. `results/` holds only aggregates.
- **Split:** design runs to 13 Apr 2026, with an embargo from 14 to 26 Apr. The holdout runs from 27 Apr 2026 (the
  Data Brain's split) and was read once (`r45study.py holdout` refuses a second read).
- **Walk-forward:** nothing is fitted, so each calendar year is its own out-of-sample block.
- **Costs:** `research/obuy/costs.py` `Costs("dated")`, the charges in force on the trade date. Slippage is a percentage
  of the price on each side, including stop and lock fills.
- **A check added after the PREREG (descriptive only, not used for any variant):** allowing overlapping trades, or a
  signal on the exit bar, adds at most 3% more trades (2,868-2,927) at the same +Rs 259-261 a trade. It does not close
  the gap in trade count with the claim.
- **Statistics:** 300 random-entry twins per variant (same sessions, same number of trades, random eligible 5-minute
  bars, same exits and costs); p is one-sided; Benjamini-Hochberg runs over the 20 variants.

## Plain-English verdict

The +Rs 174 a trade is an artifact of testing on a series that quietly switches contracts as NIFTY moves. On one
contract you can actually buy and sell, with real charges and 0.5% slippage, the rule loses about Rs 150-170 a trade.
It loses in every year from 2021 to 2026 and in the untouched holdout. Even with no slippage at all, the fixed-contract
version loses (−Rs 65 a trade).

Nothing survives. If a version had survived, it would still be an option-chart signal, and only Boss could switch it
on for paper.

Lesson for every study on the lake: **never trade the `offset == 0` series as if it were one instrument.** Hold the
strike you bought.
