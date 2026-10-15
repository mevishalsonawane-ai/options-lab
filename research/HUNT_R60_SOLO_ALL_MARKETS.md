# R60: can Solo work on every index and on MCX commodities? (15 Oct 2026)

Boss, 15 Oct: "Solo should work for all index and commodities." Solo is Jarvis's own trader and stays **paper only,
forever**. Under CLAUDE.md's PAPER rule, Solo may act on a new market only once its rules have PASSED the research gates
there. R60 runs that test for SENSEX, MIDCPNIFTY and six MCX commodities.

The test was pre-registered in `hunt/r60/PREREG.md` (commit 8043f8d, before any P&L) and the code is in
`research/hunt/r60/`. Research only: nothing in the app changed.

## The answer, per market

| market | does Solo pass? | final test (1 Apr - 13 Oct 2026, read once) |
|---|---|---|
| **SENSEX** | **No.** It also cannot be traded: SENSEX is BSE and the app's paper account is NSE F&O only (IraSolo.kt). | SoloMidday: 23 trades, +Rs 7,603, p 0.34 (too few trades). A2 on SENSEX only: 49 trades, +Rs 3,306, p 0.22, top-5 = 6x net. |
| **MIDCPNIFTY** | **SoloMidday: no.** A2, which already covers it, passes again (row below). | SoloMidday: 27 trades, +Rs 4,916, p 0.37 (too few trades). Inside A2: 80 trades, +Rs 72,642. |
| **all five indices together** | **Plain SoloMidday: no.** **A2 with SENSEX added: PASS**, but it is R57's A2 again: it took 2 SENSEX trades. | SoloMidday, strongest of five: 52 trades, -Rs 11,654. A2 with SENSEX: **129 trades, +Rs 96,088, p 0.002, q 0.042**. |
| **GOLD** | **No** | noon 6 trades, -Rs 49,120; evening 15 trades, -Rs 84,888. One lot costs Rs 2.5-7 lakh of premium. |
| **GOLDM** | **No** | noon 9 trades, -Rs 13,373; evening 21 trades, -Rs 1,397 |
| **SILVER** | **No** | noon 4 trades, +Rs 13,058; evening 8 trades, -Rs 12,882. One lot costs about Rs 2 lakh. |
| **SILVERM** | **No** | noon 7 trades, -Rs 2,043; evening 21 trades, +Rs 870 |
| **CRUDEOIL** | **No** | noon 5 trades, -Rs 1,154; evening 25 trades, -Rs 25,163 |
| **NATURALGAS** | **No** | noon 1 trade, -Rs 3,343; evening 17 trades, -Rs 12,203 |
| **all six MCX, strongest first** | **No** | noon 19 trades, -Rs 85,019; evening 59 trades, +Rs 5,314 (p 0.35; the top 5 trades are 48x the net) |
| **MCX meta-labeller (A2 retrained for MCX)** | **No** | M1: 125 trades, -Rs 34,569 (corrected after a bug: 123 trades, -Rs 7,783, p 0.32). M2: 33 trades, -Rs 3,25,769. |
| CRUDEOILM, NATGASMINI (and GOLDPETAL, SILVERMIC) | **Not tested** | The lake has no option minutes for them. |

**In short.** Solo does not pass on any new market: not SENSEX, not MIDCPNIFTY on its own, and not any MCX commodity.
Nothing in this study gives Solo a new market to act on. The one variant that passed (I4) is R57's A2 with SENSEX
added to its candidate list. It almost never picked SENSEX, so it confirms A2 on the four NSE indices and adds nothing
for SENSEX.

## Why the MCX version cannot work as SoloMidday is built

- **The noon rule rarely fires.** On MCX, most of the day's range comes in the US evening. SoloMidday needs a move of
  half the daily ATR by its decision time. By noon the median move from the 09:00 open is only 0.07 to 0.19 of the daily
  ATR, so the rule fired on 1-8% of sessions (CRUDEOIL: 2 design signals in 167 sessions). The evening version fires
  more (9-17% of sessions), but still on too few days. No single commodity reached 40 final trades.
- **The metal options are thin and huge.**
  - In design, a GOLD, GOLDM, SILVER or SILVERM option printed within three minutes of noon on only 18-50% of days
    (CRUDEOIL and NATURALGAS 97-100%).
  - One GOLD lot is 1 kg (Rs 2.5-7 lakh of premium a lot) and one SILVER lot is 30 kg (about Rs 2 lakh). A single
    stop-out costs Rs 30-70k. M2's whole final loss is 11 GOLD trades (-Rs 2.63 lakh); its worst was the 10 Jun US CPI
    release at 18:00 IST.
- **The app's NG evening breakout, applied to every commodity, lost everywhere.** Final, unfiltered: NATURALGAS 116
  trades -Rs 1,234, CRUDEOIL -Rs 98,792, GOLD -Rs 2.94 lakh, SILVER -Rs 3.18 lakh. So the MCX meta-labeller had
  nothing good to pick from. Its skill was small: out-of-sample AUC 0.50-0.52 in design, 0.55 (M1) and 0.51 (M2) in the final.

## What passed, exactly (I4), and what it means

I4 is **R57's A2 with SENSEX added**, retrained exactly as R57 did it:
- logistic regression, C = 0.1; a missing input is filled with its training median, then inputs are standardised;
- labels: the held contract's net > 0;
- purged walk-forward with monthly refits and a 1-session embargo.

Every day it scores the candidates from setups S1-S4 on NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX (never on an
index's own expiry day). It takes them in time order (at the same minute, the highest score first) and buys the first
whose score is at least the 70th percentile of that month's training scores, 1 lot. Then nothing more that day. Each
setup keeps its own strike rule and exits:

| setup | signal source | strike | exits |
|---|---|---|---|
| S1 SoloMidday | index 1-min | 4-ITM | index stop 0.3 ATR14, breakeven at 0.45 ATR, 14:30 |
| S2 Donchian-20 | index 5-min | 1-ITM | -15% / +30%, 30 min, 15:15 |
| S3 H37-10 (open outside value) | index 5-min + previous day's value area | 1-ITM | 60 min + Solo layer (-30% stop, +10% locked at +40%) |
| S4 15-min opening-range break | index 5-min | 1-ITM | -25% / +50%, 60 min + lock |

Model and scorer:
- The deployment model is `hunt/r60/results/models/I4.json`, fitted on every labelled row from 21 Nov 2024 to
  13 Oct 2026. Its take threshold is **0.4843**. It has 30 inputs: R57's 29 plus the SENSEX one-hot.
- `I4_golden.json` holds 25 parity rows. Scored with `hunt/r57/r57eval.py`, the largest gap from scikit-learn is
  1.1e-16.
- Biggest weights: ATM IV -0.22 (dear options are skipped), India VIX +0.20, the last 15 minutes in the trade's
  direction +0.13, far from its own side's edge of the day -0.12, MIDCPNIFTY +0.09. SENSEX gets -0.05.

Final test, and how it compares:

| | trades | win | net (per trade) | 2x costs | max DD | worst day | months green | top-5 share | p | q |
|---|---|---|---|---|---|---|---|---|---|---|
| I4 (R60) | 129 | 57% | **+Rs 96,088 (+745)** | +Rs 77,713 | Rs 18,627 | -Rs 6,120 | 6/7 | 41% | 0.002 | 0.042 |
| R57 A2 (4 indices, for comparison) | 128 | 59% | +Rs 99,172 (+775) | +Rs 80,849 | Rs 18,056 | -Rs 6,120 | 6/7 | 40% | 0.002 | 0.010 |

- Per index: MIDCPNIFTY 80 trades +Rs 72,642; FINNIFTY 27, +Rs 12,677; NIFTY 7, +Rs 8,374; BANKNIFTY 13, +Rs 2,279;
  SENSEX 2, +Rs 117.
- Per setup: S3 92 trades +Rs 55,530; S2 31, +Rs 31,231; S1 4, +Rs 7,870; S4 2, +Rs 1,457.
- Design walk-forward (Jul 2025 - Mar 2026): 174 trades, +Rs 78,449.
- Capital per lot (median): Rs 23,442. It trades on 0.97 of the 133 final sessions.

What it means: **I4 is not a new Solo.** Its 129 trades carry R57's caveats unchanged:
- 76% of the profit is MIDCPNIFTY;
- 92 of the 129 trades are S3, whose exit R52 chose on this same final window.

Recommendation: the app keeps A2 as R57 specified it (four NSE indices). Adding SENSEX gains nothing, and Solo cannot
trade SENSEX anyway.

## Does not pass (honest list)

| id | what | final: trades, net | p | q | why it fails |
|---|---|---|---|---|---|
| I1 | SoloMidday on SENSEX | 23, +7,603 | 0.34 | 0.75 | fewer than 40 trades; p; q; top 5 = 2.9x net |
| I2 | SoloMidday on MIDCPNIFTY | 27, +4,916 | 0.37 | 0.75 | fewer than 40 trades; p; q; top 5 = 8.4x net (design was +78,627 on 83) |
| I3 | SoloMidday, strongest of five indices | 52, -11,654 | 0.55 | 0.78 | final loss (design +82,054 on 161) |
| I5 | A2 (five-index model), SENSEX only | 49, +3,306 | 0.22 | 0.75 | p; q; top 5 = 6x net; design walk-forward +212 on 60 |
| X1 / X2 | GOLD noon / evening | 6, -49,120 / 15, -84,888 | 0.66 / 0.49 | 0.87 / 0.76 | losses; too few trades |
| X3 / X4 | GOLDM noon / evening | 9, -13,373 / 21, -1,397 | 0.83 / 0.37 | 0.96 / 0.75 | losses; too few trades |
| X5 / X6 | SILVER noon / evening | 4, +13,058 / 8, -12,882 | 0.18 / 0.41 | 0.75 / 0.75 | too few trades; design losses |
| X7 / X8 | SILVERM noon / evening | 7, -2,043 / 21, +870 | 0.51 / 0.20 | 0.76 / 0.75 | too few trades; design losses |
| X9 / X10 | CRUDEOIL noon / evening | 5, -1,154 / 25, -25,163 | 0.43 / 0.30 | 0.75 / 0.75 | losses; too few trades |
| X11 / X12 | NATURALGAS noon / evening | 1, -3,343 / 17, -12,203 | 0.95 / 0.88 | 0.96 / 0.96 | losses; too few trades (design +26,074 / +23,768) |
| X13 | all six MCX, noon | 19, -85,019 | 0.81 | 0.96 | loss |
| X14 | all six MCX, evening | 59, +5,314 | 0.35 | 0.75 | p; q; top 5 = 48x net; -Rs 39,574 at 2x costs |
| M1 | MCX meta-labeller, SN SE EV MO | 125, -34,569 | 0.17 | 0.75 | loss; design walk-forward -5,885 |
| M2 | MCX meta-labeller, SN SE | 33, -3,25,769 | 0.96 | 0.96 | loss (11 GOLD trades -2.63 lakh); too few trades |

Full tables, with design numbers, 2x costs, capital per lot, trades per day and months, are in
`hunt/r60/results/r60_tables.md`.

The twins: 500 per variant. A twin buys the same market on the same day at a random minute, with a coin-flip side, the
same strike rule and the same exits. On MCX the twins lost on average: -Rs 0.5k to -Rs 82k per variant over the final
period, and -Rs 1.8 lakh for M2. Buying a random MCX option for two hours loses money.

Every candidate, unfiltered, in the final test (several a day; context, not gated):
- the app's NG evening breakout on NATURALGAS made -Rs 1,234 on 116 trades (R53 had -Rs 3,508 on 115);
- MIDCPNIFTY's S3 made +Rs 45,466 on 66 trades;
- SENSEX's S2 made +Rs 15,669 on 339 trades.

## Constraints Boss should know

- **SENSEX cannot be traded by Solo.** The paper account and the instrument master are NSE F&O only (IraSolo.kt,
  SoloMidday.kt). It was tested anyway; it does not pass.
- **MCX would need new plumbing.** Solo's account is NSE F&O. MCX paper orders exist only inside the MCX paper arms
  (McxPaperArms). With nothing passing, there is nothing to plumb.
- **No data for the minis.** CRUDEOILM and NATGASMINI have no option files in the lake, and neither do GOLDPETAL and
  SILVERMIC.
- **The MCX window is short.** MCX option minutes start on 5 Aug 2025, so the MCX design is only 5 Aug 2025 -
  31 Mar 2026 (ATR needs 14 sessions first).
- **The final test is not fresh.** R53 read MCX Apr-Oct 2026, and R57 read the four NSE indices. Only live paper from
  15 Oct 2026 is truly unseen.

## Method

### Data
Drive lake `gdrive:IraAlgo-data-lake`, copied with rclone and deleted after use. Nothing raw is in git. Splits:
- Index: design 21 Nov 2024 - 31 Mar 2026, then the final test 1 Apr - 13 Oct 2026, read once (`results/FINAL_READ`).
- MCX: design 5 Aug 2025 - 31 Mar 2026, then the same final.

### Indices
R57's code was run unchanged, with SENSEX added:
- R51's held-contract engine;
- next-minute fills and R17 liq fills;
- dated charges, with BSE rates for SENSEX;
- lots in force (SENSEX 20);
- no trade on an index's own expiry day.

Cleaning, rows dropped:

| index | foreign rows (R47) | bad ticks (spikes.py rule) | strike > 12 steps from spot |
|---|---|---|---|
| NIFTY | 0 | 4,905 | 2 |
| BANKNIFTY | 440 | 590 | 5 |
| FINNIFTY | 115,698 | 1,323 | 43,571 |
| MIDCPNIFTY | 0 | 1,207 | 15,463 |
| SENSEX | 0 | 2,826 | 53 |

The four NSE rows match R57's table exactly.

### MCX
R53's harness, plus:
- **Signal series.** The near future is the per-minute median of the option rows' spot. Checked against the live MCX
  future in 1-13 Oct 2026: median gap 0.000%, and 99.7-99.9% of minutes exact for CRUDEOIL, NATURALGAS, SILVER and
  SILVERM. GOLD and GOLDM are 75% exact, because the live file lacks their 5-Oct contract. The full check is
  `results/mcx_proxy_check.json`.
- **Cleaning.** R47 foreign rows: 0 found. R42's F1 bad-print filter flagged CRUDEOIL 370, NATURALGAS 1,620, GOLD 149,
  GOLDM 333, SILVER 261 and SILVERM 379 prints (R42's census exactly). The lake spikes.py flag removed 64-637 more per
  commodity.
- **Held contract.** The strike is read at the signal, then that one contract is priced on printed minutes only. The
  rule is 1-ITM, else ATM, in a 4-minute fill window; 12 of 214 final trades used the ATM fallback.
- **Exits and sales.**
  - A future-level exit is decided on a 1-minute close and the option is sold at the next minute.
  - A premium stop rests on the printed low; a gap through it fills at the open.
  - 21 of 214 final exits had no print and used the last printed close (marked stale).
- **Expiry and holidays.**
  - No trade on an option expiry day. MCX options devolve into futures, so every trade is out by 23:00 on the day
    before expiry.
  - Sessions run to 23:30 in US summer time and 23:55 in winter.
  - On evening-only holiday sessions the noon rule cannot fire.
- **Costs.**
  - Zerodha MCX option charges (CTT 0.05% of the sell premium).
  - Half the commodity's spread per side: crude 0.30%, natural gas 0.43%, gold and silver 0.40%. It is doubled before
    17:00 and doubled again on a side with no print in the 5 minutes before.
  - "2x costs" doubles both the charges and the spread.

### SoloMidday translated to MCX
On the commodity's near future:
- the move from the 09:00 open to 11:59 (or 17:59) must be at least 0.5 ATR14, with the close in the outer 25% of the
  day's range;
- ATR14 comes from daily bars of the proxy; on a contract-roll day the true range is high - low only;
- buy at 12:00 (or 18:00);
- exits: future stop 0.3 ATR14 on a close; breakeven once the future has gone 0.45 ATR14 its way; out at 14:30 (or
  20:30);
- 1 lot.

### Meta-labeller for MCX
R57's method. Candidates:
- SN / SE: the translated SoloMidday, noon and evening;
- EV: the app's NG evening breakout, applied to all six commodities;
- MO: the app's SILVERM morning call.

Features from the future: returns over 15 and 60 minutes and since the open, the range / ATR, the distance from its
own side's edge of the range, 30-minute volatility, ATR %, minute of day, a US-session flag and sessions to expiry.
ATM IV and put/call OI are context only. USD/INR is not in the lake, so it was not used.

The US-night silver rule and the 12-month trend rule were left out: the first needs US hourly prices that are not in
the lake, and the second trades monthly.

### Gates
As R47-R59, and never relaxed. All of these are needed:
- final net > 0;
- p < 0.05 against 500 random-entry twins (a twin with no fill is redrawn);
- q <= 0.10 after Benjamini-Hochberg over all 21 variants;
- 40+ trades;
- top-5 trades < 100% of the net;
- design positive (rules: design net; models: design walk-forward net).

## Signal source of every rule

Every R60 rule reads its signal from the **underlying**: the index (S1-S4, I1-I5) or the commodity's near future (SN,
SE, EV; MO enters on the clock and picks its strike from the future). It then buys the option chosen by the strike rule.
Option IV and OI are context-only model inputs. No rule reads an option's own chart.

## Honesty notes and amendment

- **Amendment 1: a bug found after the final read.** Sessions-to-expiry was NaN for every MCX day, because a date-type
  slip meant no session ever matched an expiry. Expiry days themselves were still skipped.
  - It did not touch I1-I5 or X1-X14. `r60amend.py` checked that SN and SE reproduce trade for trade after the fix.
  - It did touch M1 and M2: the `dte` input was a constant, M1 had no MO candidates, and EV could hold to 23:15 on an
    expiry eve.
  - M1 and M2 keep their as-run verdicts. The corrected M1c (-Rs 7,783 on 123 trades, p 0.32) and M2c (-Rs 2.54 lakh
    on 32 trades, p 0.91) were read once, as a labelled post-lock correction. Both fail anyway.
- **Two small deviations, found in the self-review; no number changed.**
  - The index twins (R57's code) score a no-fill twin as 0 instead of redrawing it. Index twins fill 97-100% of the
    time, and a 0 is better than the average twin, so the p-values are slightly conservative.
  - The pooled picks (I3, X13, X14) rank only the candidates that filled inside the 4-minute window, as R57's plain
    SoloMidday did.
- **Not pristine.** R53 already read MCX April-October 2026, and R57 read the four NSE indices; R57's A2 is I4's twin.
- **Versions.** Stores and results were computed with pandas 3.0.5. The R60 tests and the brain tests pass under the
  CI pins (Python 3.11, pandas 2.2.3).
- **Tests:** `python -m pytest research/hunt/r60/test_r60.py -q`, 30 tests on synthetic data. They cover:
  - cleaning: foreign rows, spikes, and a bad evening print;
  - ATR14 across a roll;
  - the Solo signal, with its refusals and the look-ahead guard;
  - the held contract, the ATM fallback and the 4-minute fill window;
  - future stop, breakeven, the premium stop's gap fill and the step lock;
  - the expiry-eve cap and the session end (US summer / winter);
  - expiry days, costs, the thin spread and sale fallbacks;
  - the look-ahead guard on every feature, and point-in-time context;
  - the period guard, twins, BH, the gates and pooled ties;
  - missing data, and the sessions-to-expiry regression.
- **Re-run:**
  1. `python research/hunt/r60/r60idx.py build`
  2. `python research/hunt/r60/r60mcx.py build`
  3. `python research/hunt/r60/r60study.py design`
  4. `python research/hunt/r60/r60study.py final` (refuses a second read)
  5. `python research/hunt/r60/r60amend.py` (once)
  6. `python research/hunt/r60/r60report.py`
