# R109: what IraAlgo can learn from IraThinker's two strategies (26 Oct 2026)

Boss asked: "Do we get to learn something from IraThinker?" IraThinker (`irathinker-app/`, Boss's separate alert app)
paper-tracks two strategies from its own studies, and prices its paper log on a Black-Scholes tape, not real fills.
R109 tested both rules exactly as IraThinker's source code states them, on our lake's real option prints, with our
fills and dated charges.

- **Pre-registration:** `research/hunt/r109_irathinker/PREREG.md`, pushed before any R109 number existed (commit
  `fc8f094`). Code and 27 tests (`tests/test_r109.py`, incl. look-ahead guards) were pushed before the first run.
- **Code and tables:** `research/hunt/r109_irathinker/` (`r109engine.py`, `r109study.py`, `results/report.md` has every
  column). Final window read once (`results/FINAL_READ`).
- **Research only.** Nothing in `android/**` or `irathinker-app/**` changed. Nothing goes on paper or live from this.

## Answer first

**No. Neither IraThinker strategy passes our gates, so nothing goes on paper in IraAlgo. 0 of 8 tests pass.**

1. **mom60 (buy the ATM option after a big 60-minute move) does not hold up on real prints.** It lost money in our
   design window (20 Nov 2024 to 31 Mar 2026) on BANKNIFTY (-Rs 47,521, n = 307 trades) and SENSEX (-Rs 24,954,
   n = 225). It also lost from 2021 to Nov 2024 (BANKNIFTY -Rs 1.14 lakh, n = 1,087; FINNIFTY -Rs 1.30 lakh, n = 593).
   FINNIFTY made only +Rs 8,326 in design (n = 314, Rs 27 a trade).
   - It made money in the final window (1 Apr to 13 Oct 2026): BANKNIFTY +Rs 63,263 (n = 146), FINNIFTY +Rs 91,509
     (n = 132), SENSEX +Rs 49,775 (n = 81).
   - It never beat random entry minutes on the same days in the same direction (twin p 0.17 to 1.00).
   - It lost on every index in the four sessions after 13 Oct (n = 4 to 6 each).
   - The good final window looks like a friendly six months, not an edge.
2. **The BANKNIFTY Monday short strangle does make money on real prints:** design +Rs 1,67,686 (69 weeks, 72% win),
   final +Rs 61,920 (26 weeks, 69% win), 2021 to Nov 2024 +Rs 1,01,345 (155 weeks, 70% win).
   - **It breaks Boss's account rule at one lot.** The worst month was -Rs 24,307 in design and -Rs 32,436 in the
     final, against the -Rs 13,000 monthly stop. The worst weeks were -Rs 31,283 (25 Aug 2025) and -Rs 31,696 (6 Apr
     2026). The deepest drawdown was Rs 37,926, close to the Rs 40,000 limit.
   - It needs about Rs 2.2 lakh of margin per lot (estimate).
   - Its entry time adds nothing over a random time that Monday (twin p 0.18 / 0.28 / 0.995).
   - It is option selling with an open-ended tail, so it stays a note.
3. **IraThinker's Black-Scholes tape is wrong in both directions.**
   - It flatters mom60 by about 0.5 to 3.7 points of premium per trade before costs. Our costs take another 0.6 to
     1.8 points.
   - It under-states the strangle by about 11 to 31 points of premium. Option sellers collect implied vol, and implied
     vol sits above the realised vol IraThinker uses.
   - IraThinker's paper logs are therefore not evidence for either rule.
4. **mom60 mostly repeats what our arms already see.** On 90-92% of BANKNIFTY mom60 trades, R72 Breadth Donchian had
   a same-direction signal on the same index that day; on 74-75% it came within 60 minutes or during the hold. For R51
   Donchian 20 the figures are 96-99% and about 90%. R51 fires many times a day, so its high overlap partly reflects
   how often it fires.

**Signal sources:** mom60 reads the index spot's own 60-minute return (the underlying, CLAUDE.md default) and buys the
option; its 30% stop is on the option premium (allowed). The strangle has no directional signal; its strikes come from
the index spot at 09:30.

## What was tested

| id | rule (from IraThinker's source) | chain | threshold |
|---|---|---|---|
| m60-bn | mom60 BANKNIFTY | monthly | 0.50% |
| m60-fin | mom60 FINNIFTY | monthly | 0.5025% |
| m60-sx | mom60 SENSEX | monthly | 0.4735% |
| m60-nf | mom60 NIFTY (out of family, IraThinker does not run it) | monthly | 0.3747% (2021-24 q90, n = 207,565 bars) |
| m60-mid | mom60 MIDCPNIFTY (out of family) | monthly | 0.4967% (2022-24 q90, n = 176,086 bars) |
| m60-nf-w | mom60 NIFTY on the weekly chain (our variant) | weekly | 0.3747% |
| m60-sx-w | mom60 SENSEX on the weekly chain (our variant) | weekly | 0.4735% |
| strangle-bn | BANKNIFTY Monday short strangle | monthly | - |

**mom60:**
- **Signal:** closed 1-minute index bars. When |index close / close 60 minutes earlier - 1| reaches the threshold, buy
  the ATM call (up) or put (down) at the next minute's open.
- **Exit:** sell if a minute's close of the option is 30% below entry, else after 60 minutes.
- **Limits:** one position at a time, entries at least 60 minutes apart, last entry 14:30.

**Strangle:**
- **Entry:** every Monday, spot = the 09:30 bar's close. Sell the monthly CE at ATM + 200 and the PE at ATM - 200 at
  09:31.
- **Exit:** buy both back at Thursday's 15:29 open. If the monthly expiry falls inside the week, settle at intrinsic
  value instead. No stop.

**Our engine:**
- Real 1-minute prints of the exact contract bought (R51's cleaned held-contract stores of the MONTH / WEEK chains).
- Next-minute fills with liquidity slippage (5 bps + 1-4 ticks; stops twice that) and dated Zerodha charges.
- One lot, the lot in force on the day.
- A leg with no print near its exit is repriced by Black-Scholes at its last implied vol (R103's stale-leg rule).
- Periods: history (2021 to 19 Nov 2024, descriptive); design 20 Nov 2024 to 31 Mar 2026; walk-forward window Apr 2025
  to Mar 2026; final 1 Apr to 13 Oct 2026 (read once); post-13 Oct 14 to 19 Oct (descriptive).
- **Twins:**
  - mom60: the same day and direction, entered at a random minute 10:16-14:30, with the same exits. A pool of 50 per
    trade and 400,000 draws.
  - Strangle: the same Monday, a random entry minute 09:31-14:30, the same exit.
- **Gates:** net > 0 and twin p < 0.05 in design, walk-forward AND final. BH q <= 0.10 over 14,253 tests (R109's 8 plus
  14,245 earlier tests, R1-R108). At least 40 design / 20 final trades. The strangle also needs Boss's account rule and
  a design bootstrap p < 0.05.

## Results (1 lot, after charges and slippage; n = trades)

| test | period | n | win | net | worst day / week | worst month | max drawdown | twin p |
|---|---|---|---|---|---|---|---|---|
| mom60 BANKNIFTY | history | 1,087 | 42.5% | -Rs 1,14,009 | -Rs 18,272 day | -Rs 23,111 | Rs 1,54,320 | - |
| | design | 307 | 43.3% | -Rs 47,521 | -Rs 11,952 day | -Rs 26,088 | Rs 76,644 | 1.000 |
| | walk-forward | 185 | 38.9% | -Rs 31,435 | -Rs 11,952 day | -Rs 26,088 | Rs 63,027 | 1.000 |
| | final | 146 | 46.6% | Rs 63,263 | -Rs 14,187 day | -Rs 9,160 | Rs 17,784 | 0.891 |
| | post-13 Oct | 4 | 25% | -Rs 4,181 | -Rs 2,302 day | | | |
| mom60 FINNIFTY | history | 593 | 34.2% | -Rs 1,29,917 | -Rs 30,575 day | -Rs 33,015 | Rs 1,43,065 | - |
| | design | 314 | 45.5% | Rs 8,326 | -Rs 15,504 day | -Rs 18,324 | Rs 42,039 | 0.992 |
| | walk-forward | 196 | 44.9% | Rs 13,693 | -Rs 15,504 day | -Rs 9,121 | Rs 35,372 | 0.966 |
| | final | 132 | 57.6% | Rs 91,509 | -Rs 10,754 day | -Rs 10,580 | Rs 19,044 | 0.168 |
| | post-13 Oct | 5 | 0% | -Rs 4,805 | -Rs 2,051 day | | | |
| mom60 SENSEX | history | 135 | 30.4% | -Rs 17,498 | -Rs 3,848 day | -Rs 7,078 | Rs 24,063 | - |
| | design | 225 | 43.6% | -Rs 24,954 | -Rs 22,726 day | -Rs 19,353 | Rs 62,955 | 0.909 |
| | walk-forward | 127 | 46.5% | -Rs 527 | -Rs 22,726 day | -Rs 19,353 | Rs 53,580 | 0.690 |
| | final | 81 | 53.1% | Rs 49,775 | -Rs 6,136 day | -Rs 421 | Rs 8,962 | 0.287 |
| | post-13 Oct | 5 | 20% | -Rs 5,441 | -Rs 3,794 day | | | |
| mom60 NIFTY (out of family) | design | 451 | 39.9% | -Rs 65,739 | -Rs 12,491 day | -Rs 28,430 | Rs 1,12,507 | 1.000 |
| | final | 152 | 41.4% | -Rs 969 | -Rs 7,408 day | -Rs 14,117 | Rs 27,366 | 0.989 |
| mom60 MIDCPNIFTY (out of family) | design | 544 | 43.0% | -Rs 2,33,205 | -Rs 20,418 day | -Rs 45,941 | Rs 2,50,628 | 1.000 |
| | final | 154 | 43.5% | -Rs 6,083 | -Rs 15,537 day | -Rs 24,256 | Rs 56,527 | 0.993 |
| mom60 NIFTY weekly | design | 452 | 35.8% | -Rs 95,448 | -Rs 10,880 day | -Rs 36,784 | Rs 1,29,706 | 1.000 |
| | final | 153 | 39.2% | -Rs 4,221 | -Rs 7,408 day | -Rs 16,418 | Rs 33,184 | 0.990 |
| mom60 SENSEX weekly | design | 255 | 31.8% | -Rs 68,345 | -Rs 11,565 day | -Rs 26,925 | Rs 95,209 | 1.000 |
| | final | 93 | 41.9% | Rs 25,887 | -Rs 4,915 day | -Rs 5,043 | Rs 12,313 | 0.962 |
| **Strangle BANKNIFTY** | history | 155 wk | 70.3% | Rs 1,01,345 | -Rs 30,147 week | -Rs 15,694 | Rs 36,983 | - |
| | design | 69 wk | 72.5% | Rs 1,67,686 | -Rs 31,283 week | **-Rs 24,307** | Rs 34,925 | 0.184 |
| | walk-forward | 50 wk | 74.0% | Rs 1,71,510 | -Rs 31,283 week | -Rs 10,289 | Rs 34,925 | 0.279 |
| | final | 26 wk | 69.2% | Rs 61,920 | -Rs 31,696 week | **-Rs 32,436** | Rs 37,926 | 0.995 |
| | post-13 Oct | 2 wk | 100% | Rs 5,058 | | | | |

The BH q is 1.0 for every test, over all 14,253 tests and also over R109's 8 alone. Every test also fails gate 1 or
gate 2 by itself. The full gate list for each test is in `results/report.md`.

**A note on the mom60 twin.** As pre-registered, each twin keeps its real trade's day and direction. A twin entered
before the signal therefore rides the very move that made the signal, so the twins average +Rs 360 to +Rs 470 a trade.
That makes the twin a high bar. It does not change any verdict: four of the five monthly mom60 tests lost money in
design anyway, and FINNIFTY made Rs 27 a trade.

### The strangle in rupees (BANKNIFTY, 1 lot)

- **Margin:** about Rs 2.2 lakh per lot (estimate: 13% of spot x lot, calibrated on IraThinker's own Rs 2.14 lakh; the
  broker decides on the day).
- **Return on margin:** design +Rs 1.68 lakh in 69 weeks is about 74% of one margin over 16 months.
- **Worst weeks:**
  - design: 25 Aug 2025 -Rs 31,283 (-285% of premium); 16 Dec 2024 -Rs 22,244; 17 Mar 2025 -Rs 22,233;
    2 Dec 2024 -Rs 19,989; 23 Jun 2025 -Rs 17,637.
  - final: 6 Apr 2026 -Rs 31,696.
  - Weeks at or below -Rs 13,000: 6 of 69 in design, 1 of 26 in the final. No week reached -Rs 40,000.
- **Boss's account rule:** -Rs 13,000 a month was broken in 1 of the design months (-Rs 24,307) and in the final
  (-Rs 32,436). The drawdown came within Rs 2,100 of the Rs 40,000 limit (Rs 37,926). One lot alone uses most of the
  room the rule allows.
- **Is the money real?** The design bootstrap says the average week is above zero (p 0.020; walk-forward 0.005; final
  0.091). That is the R103 lesson again: selling premium wins often and is mildly positive, but one bad week takes a
  month's profit and more. R103 found the naked 09:20 straddle positive too, with -Rs 29k days, and hedged versions
  lost after costs.
- **Stale legs:** 15 of the 138 design legs (and 7 of 52 in the final) had no print near the exit, because the index
  had moved more than 10 strikes away. They were repriced by Black-Scholes, as R103 did.

## The BS-vs-prints gap (IraThinker's own tape on the same trades)

mom60, mean % of premium per trade:

| test | period | n | IraThinker BS tape | real prints, gross | real, after our costs | IraThinker's claim |
|---|---|---|---|---|---|---|
| BANKNIFTY | history | 1,087 | +1.42% | -0.13% | -0.93% | train +2.29% / val +0.99% / test +4.75% |
| | design | 307 | +0.11% | -0.35% | -1.00% | |
| | final | 146 | +1.86% | +1.99% | +1.39% | |
| FINNIFTY | history | 593 | +1.75% | -0.73% | -2.51% | +2.33% (n = 713) |
| | design | 314 | +1.46% | +0.52% | -0.14% | |
| SENSEX | history | 135 | +2.60% | -1.05% | -2.39% | +2.03% (n = 593) |
| | design | 225 | +1.12% | -0.23% | -0.93% | |
| NIFTY | design | 451 | +1.70% | -0.65% | -1.35% | (not claimed) |
| MIDCPNIFTY | design | 544 | +0.24% | -1.75% | -2.35% | (not claimed) |

- The BS tape beats real prints by 0.5 to 3.7 points per trade in every history and design cell; only BANKNIFTY's
  final is about equal.
- The tape also charges no costs, and our costs take about another 0.6 to 1.8 points.
- One reason: real ATM premium already prices the move. After a big 60-minute move, implied vol rises, and BS at
  20-day realised vol does not see that.

Strangle, % of premium:

| period | weeks | IraThinker BS tape (net of its 1 pp) | real prints, pooled net | IraThinker's claim |
|---|---|---|---|---|
| history | 155 | -28.0% | +3.0% | +16.42% pooled, win 77.8% |
| design | 69 | -8.5% | +7.5% | |
| final | 26 | -4.3% | +6.6% | |

- For the strangle the tape is too pessimistic. It prices the premium sold at realised vol, and the market sells at a
  higher implied vol. That gap is the seller's whole edge.
- On real prints the strangle earns well under the claimed +16.42% and wins less often: 70-74% against 77.8%.
- The worst week matches IraThinker's -285% exactly (25 Aug 2025), so both studies price the same week the same way.

## Overlap with our paper arms (signals, design / final)

| mom60 | n (design / final) | R72 same day, same way | R72 within 60 min or during the hold | R51 same day, same way | R51 within 60 min / hold |
|---|---|---|---|---|---|
| BANKNIFTY | 307 / 146 | 92% / 92% | 74% / 75% | 99% / 99% | 90% / 89% |
| FINNIFTY | 314 / 132 | 90% / 92% | 77% / 77% | 96% / 98% | 90% / 89% |
| SENSEX | 225 / 81 | 91% / 95% | 76% / 84% | 97% / 100% | 90% / 90% |

These are R72 / R51 candidate signals, not the app's trades: the app's R51 also has a Supertrend filter and both skip
expiry days. A big 60-minute move is nearly always also a Donchian break, so mom60 would mostly double up on the
direction our arms already take. Under the same-direction cap of 2 it would compete with them for places.

## Leads (not tests; any use needs its own pre-registration)

- **mom60 at 3-7 days to expiry** (IraThinker's own "sweet spot"): BANKNIFTY design +Rs 20,235 (n = 58, +2.77% gross)
  and FINNIFTY +Rs 15,450 (n = 50). But BANKNIFTY in 2021-24 made +Rs 665 (n = 171), SENSEX design lost (-Rs 6,830,
  n = 48) and NIFTY's final lost (-Rs 15,787, n = 23). This is a slice found after the fact.
- **mom60 at 0-2 days to expiry** is the worst cell nearly everywhere, e.g. SENSEX design -Rs 46,304 (n = 34). This
  agrees with IraThinker's own low-DTE warning.
- **Weekly short strangles on a smaller lot or with a defined-risk wing** were not tested here. R103 found that wings
  cost more than they save after costs on intraday structures; a weekly hold is different and untested.

## What IraThinker should change in its labels

These are suggestions for Boss; R109 changes nothing in `irathinker-app/**`.

1. **mom60 footers** (BANKNIFTY "train +2.29% / val +0.99% / test +4.75%", FINNIFTY "+2.33% PF 1.40 n=713", SENSEX
   "+2.03% PF 1.31 n=593"): on real prints with costs the same rule made -0.9% (BANKNIFTY, n = 1,087), -2.5%
   (FINNIFTY, n = 593) and -2.4% (SENSEX, n = 135) per trade from 2021 to Nov 2024, and -1.0% / -0.1% / -0.9% in
   20 Nov 2024 to Mar 2026. Suggested footer:
   - "Not confirmed on real option prints (IraAlgo R109): lost in 2021-24 and Nov 2024 - Mar 2026, won Apr - Oct 2026;
     never beat random entry times."
2. **"Premium is a BS estimate"** should say which way it errs:
   - mom60: "the BS tape has run 0.5-3.7 points per trade above real fills, before costs";
   - strangle: "the BS tape under-states the premium you would really collect".
   - The paper logs should not be read as evidence for the rules.
3. **Strangle footer** "+16.42% of premium pooled, win 77.8%": on real prints with costs, +3.0% (history, n = 155
   weeks), +7.5% (design, n = 69) and +6.6% (final, n = 26), win 70-74%. Keep the -285% worst week. Add:
   - "worst month -Rs 32,436 at 1 lot (Apr 2026), above Boss's -Rs 13,000 monthly stop".
4. **The FINNIFTY / SENSEX threshold labels**: the live config uses 0.50% / 0.47%, while the studies froze 0.5025% /
   0.4735%. This is a small difference, but the footers should name the number the app actually uses.

## What can go on paper in IraAlgo

**Nothing.** No R109 test passed every gate, so under CLAUDE.md none may act on the paper arms. All 8 are logged as
rejected (`research/experiments/entries/r109-*.yaml`).

## Limits

- The final window has been read by R51-R108, so it is not fresh. The post-13 Oct window is 4 sessions (n = 2-6 per
  test).
- Expiry dates:
  - from the chain itself (the day the series rolls) and the NSE bhavcopy;
  - SENSEX has no bhavcopy here, so three missing SENSEX monthly expiries came from BSE's last-Thursday rule. That
    affects only DTE labels and stale repricing, not entries or exits.
- Strangle expiry weeks settle at intrinsic against the index's last 1-minute close, not NSE's last-30-minute average.
- The margin is an estimate. No SPAN file is in the lake.
- "Entries skipped (no print)" in the report counts signal bars where the ATM contract had no open within 3 minutes. It
  is large for FINNIFTY / MIDCPNIFTY in 2021-24 (thin monthly chains) and small since Nov 2024.
- The brain checks were run in this sandbox:
  - `python -m research.brain.features catalog --check` is up to date.
  - `research/brain/tests`: 174 passed, 6 failed and 3 errored, because this sandbox has pandas 3.0 against the pinned
    2.2.3 (string-dtype changes). `research/brain` is untouched by R109.
