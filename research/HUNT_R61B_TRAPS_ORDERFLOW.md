# R61b: do fake-breakout, trap and order-flow checks help Solo? (15 Oct 2026)

Boss, 15 Oct: "Fake breakouts, order flow, traps - all should also be checked by Solo." This follows R61
(`HUNT_R61_SOLO_3.md`), which kept Solo 2.0 (A2) because no option-chain, Greek or context concept added skill on
unseen data. Solo is Jarvis's own trader and stays **paper only, forever**. Research only: nothing in the app changed
(no `android/**`, no `.github/**`, no knowledge-pack rebuild). Code: `research/hunt/r61b/`.

## The answer

**No. Trap, fake-breakout and order-flow checks do not make Solo better on the final days, so Solo 2.0 (A2) stays.**

- **Pre-registered first.** `hunt/r61b/PREREG.md` (commit b2cc247) was written before any data was scored. The design
  results were committed (10be46e) before the one final read.
- **Same comparator as R61.** V1 is A2 refitted in the same pipeline. It reproduces R57 and R61 to the rupee:
  - design: 166 trades, +Rs 55,910;
  - final: 128 trades, +Rs 99,172.
- **The best design idea did not hold up.** F4, "skip a candidate on any price-only trap sign", made the most in
  design (+Rs 65,534 against V1's +Rs 55,910), so it was the pre-registered candidate. On the final days it made
  +Rs 75,675 against V1's +Rs 99,172:

  | replace check (PREREG section 7) | F4 | V1 (Solo 2.0) | result |
  |---|---|---|---|
  | final net | +Rs 75,675 | +Rs 99,172 | lower |
  | final AUC | 0.575 (on the candidates it keeps) | 0.538 | higher, but not like for like |
  | paired-day bootstrap | p 0.89 | - | not significant |
  | net without MIDCPNIFTY | +Rs 18,048 | +Rs 27,586 | lower |

  It does not replace Solo 2.0.
- **No variant made more than Solo 2.0 on the final days.** Six of the seven new variants pass the R47-R61 gates. That
  is A2's own edge again: all of them choose from the same S1-S4 candidates. The final nets ranged from +Rs 28,954 (F3)
  to +Rs 90,775 (F1).
- **As model inputs, the 15 new numbers add nothing measurable.** Removing any group from the all-inputs model, or
  adding any one input alone to A2's 29, moved walk-forward AUC by less than 0.01 in design and in final. The largest
  change was the signal-bar shape, removed from M1: -0.0055 in design, -0.0078 in final.
- **What the flags do say (descriptive, design only).** Candidates on choppy days do worse, whichever way the trap
  went:
  - after any failed break, either side;
  - after a same-side sweep;
  - after a big rejection wick.
  On 5,780 design candidates:
  - with F4's trap signs on: 3,328 candidates, win 43.9%, mean -Rs 57;
  - without them: 2,452 candidates, win 48.8%, mean +Rs 175.

  A2's own inputs already see most of this chop (day range, efficiency ratio, recent volatility). So skipping these
  candidates did not add money once A2 had picked its trade.

**Honest caveats.**
1. **This final window is not fresh.** It is its fourth use for these same candidates (R57, R61, R61-N, R61b).
2. **The "order flow" here is a proxy.** Real order flow needs tick and order-book data (see the next section). This
   study used:
   - index minute bars, which have no volume;
   - near-the-money option volume and OI as stand-ins.
   A "no" here is a "no" for minute-bar proxies, not for real delta or OFI.
3. **Live paper from 15 Oct 2026 is the only truly unseen data.**
4. **One small code gap was found after the final read** (PREREG amendment 1). The opening-range trap flags were 0
   instead of missing for S2 candidates entering at 09:30 (96 of 8,028 rows). It changes nothing for F1-F4, M1 or M3.
   Only M2 could differ, on at most 26 final rows. The spec is fixed; the final was not re-read.

## What the data can and cannot say about order flow

- **The lake.** It has 1-minute OHLC for each index, with no volume, and 1-minute OHLC, volume, OI and IV for each
  option contract. It has no ticks, no bid / ask and no order book. Dhan does not provide historical depth.
- **Futures volume.** Index futures minutes exist only from 29 Jul 2026 (live contracts), all inside the final window.
  So futures volume was **skipped**. "Volume at the break" uses near-the-money option volume instead (calls + puts,
  5 strikes), the same stand-in R9 used.
- **The phone's own recordings.**
  - TickZoneRecorder (Data Brain L1b, built 12 Oct 2026) records:
    - instruments: NIFTY and BANKNIFTY near-month futures, ATM ±3 options and India VIX;
    - each second: last price, volume, OI, buy / sell quantity and 5 bid + 5 ask levels;
    - each minute: tick-rule delta and CVD, book imbalance, spread, large prints and an absorption flag.
  - At most four NSE sessions can exist so far (12-15 Oct). None has been uploaded or analysed: OF1 found 0 releases
    and an empty catalogue on 13-14 Oct.
  - OrderFlowLive's 1-second file (OFI, depth / queue imbalance) is kept on the phone for 30 days.
- **What becomes testable, and when.** TickZoneRecorder covers NIFTY and BANKNIFTY only. Those two give about 170 Solo
  candidates a month (2,760 in the 16 design months).
  - After about **3 months** (roughly 500 candidates), a design read is possible: real delta / CVD, OFI, book imbalance
    and the absorption flag at each candidate's signal minute, used as a skip filter and as A2 inputs, the same way as
    here.
  - After about **6 months** (roughly 1,000 candidates), the second half can be held out as a fresh final test. That is
    enough to see an AUC lift of about 0.05 (standard error about 0.02 at that size).
  - A test on Solo's own trades would take years. In the final window, V1 took only 20 NIFTY / BANKNIFTY trades in
    6.5 months. So the honest test is at the candidate level.
  - R7 already suggested 8-12 weeks of recording before any order-flow test. `TODO.md` S7 asks for 2-4 weeks of
    sessions for the footprint checks.

## Part 1: the concepts (added to `hunt/r61/CONCEPTS.md` / `concepts.json`)

Eleven new entries, in the same format as R61's 26, give 37 in all. Each has what it is, how it is computed, whether the
phone has it, how traders use it, its pitfalls, our own studies (with sample sizes) and the R61b result. The short
version:

| concept | our best evidence (study, sample) | verdict |
|---|---|---|
| Fake (failed) breakout | R11 (fakes 12.8-21.1% of big breakout candles; nothing separates them in advance), H18 (break-buying 4,537 trades, net +Rs 3k, walk-forward -Rs 1.73 lakh), R61b (below) | descriptive only; no rule |
| Bull / bear traps, turtle soup | R1 N7 (777 + 657 design trades, -Rs 25 / -77 a day), R52 re-test (166 final trades, -Rs 17,852), R61b F1 | failed |
| Stop-hunts / liquidity sweeps | H18 ("sweep then reversal is folklore"; sweep fades t -2.6 / -3.3), R3 ORB Sweep (3,156 trades, -Rs 5.06 lakh; 0 of 6 years green), R49 (2,093 + 870 trades, 0 of 336 tests pass), R8 (11 MCX tick events) | failed |
| Absorption | R8 (real MCX ticks: flagged levels broke 58-68% vs 55%), R9 proxy (52 events, q 0.29), R61b `absorb15` | failed |
| Delta / CVD | R7 (same-time correlation +0.71-0.90, no lead in 66 cells; the app's option-flow signal is 0.42x its cost), R9 divergence proxy, R61b `delta30` / `cdelta_day` | no lead; proxy adds nothing |
| Order-flow imbalance (OFI) | R7 (no lead), R10 (record first), R61b `optvol_imb15` (activity tilt, not OFI) | not testable on our history |
| Depth imbalance | R8 (one evening, 11,700 walls; median life 1.2 s, half pulled, 5% traded), OF1 (no recordings analysed yet) | needs the phone's recordings |
| Volume profile (POC / VA / HVN / LVN) | R9 (0 of 32 design tests beat placebo levels), LEVEL_MAP (0 of 24), R52 H37-10 (= Solo's S3, 294 final trades, +Rs 48,772, paper only) | R62's subject |
| Order blocks | no study in our research yet | R62's subject |
| Fair value gaps | R1 N6 (814 / 982 design trades, flipped to -Rs 196 / -334 a day), R52 (110 + 97 final trades, both lose), ADVANCED (209 trades, -Rs 35,814) | failed; R62 re-tests |
| BOS / ChoCH | MATRIX_V22 (38 versions lose in both years), ADVANCED (167 trades, -Rs 54,886) | failed; R62 re-tests |

**R62.** Boss's 15 Oct volume-profile / SMC video strategy (POC, value area, low-volume nodes, order blocks, FVG,
BOS / ChoCH) is tested separately as R62 on branch `claude/r62-volume-profile-smc`. That branch is not in this
checkout, so its results are not quoted here. R61b does not repeat its trading test. Those concepts are explained in
CONCEPTS.md and are not R61b inputs.

## Part 2: what was tested (pre-registered)

**Setups and labels.** Unchanged from R57 / R61:
- S1 SoloMidday (index 1-min);
- S2 Donchian-20 (index 5-min);
- S3 open outside value and go (index 5-min + the previous day's profile);
- S4 15-min opening-range break (index 5-min).

Each candidate is priced on its held contract, with real charges (STT 0.15% from 1 Apr 2026) and R17 fills, after
R57's cleaning. Cleaning reproduced R61's table exactly: bad ticks 4,905 / 590 / 1,323 / 1,207 and foreign rows
0 / 440 / 115,698 / 0 for NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY. There is no trade on an index's own expiry day,
and only data from 20 Nov 2024 is used. Candidate rows: 5,780 in design, 2,248 in final.

**The 15 new inputs.** All are read at the signal minute. Only earlier minutes are used, and a test checks this.

| group | inputs | what it means |
|---|---|---|
| traps | `trap_same_or`, `trap_opp_or`, `trap_same_pd`, `trap_opp_pd` | earlier today, a break of the opening range (09:15-09:29) or of the previous day's high / low, on the trade's side or the other side, closed back inside within 15 minutes |
| sweeps | `sweep_same30`, `sweep_opp30` | in the last 30 minutes, a bar made a new day high (low) but closed back inside: a stop-hunt on the trade's side (a warning) or the other side |
| signal bar | `brk_wick`, `brk_clv` | the 5 minutes before entry: the rejection wick's share of the range, and where the bar closed |
| break volume | `vol_brk_rel` | near-ATM option volume in those 5 minutes vs the same 5 minutes of the last 20 non-expiry sessions (log) |
| OI at the break | `oi_chg15_pct`, `bu15` | 15-minute % change of near-ATM OI; build-up code (long build-up / short covering / long unwinding / short build-up), by side |
| delta proxies | `delta30`, `cdelta_day` | volume-weighted close location of the index bars, last 30 minutes and since 09:15, by side |
| option-volume tilt | `optvol_imb15` | (call - put volume) / total, near ATM, last 15 minutes, by side |
| absorption | `absorb15` | 15-minute volume vs normal, minus 15-minute range vs normal (both logs): high volume with little movement |

The opening-range and sweep inputs are missing before 09:30. That leaves them missing on S3 (which signals at 09:20),
so 77% of design rows have them. The OI inputs are on 76% of rows and everything else on 97-100%.

**Eight variants, one BH family.**

| id | what | how it uses the new inputs |
|---|---|---|
| V1 | A2 refitted (= Solo 2.0, the comparator) | none |
| F1 | skip filter | skip after a failed same-side break (opening range or previous day's high / low) |
| F2 | skip filter | skip a signal bar whose rejection wick is half its range or more |
| F3 | skip filter | skip a break on below-normal option volume |
| F4 | skip filter | F1 or F2 or a same-side sweep (the price-only "trap" union) |
| M1 | logistic meta-labeller | A2's 29 + all 15 |
| M2 | boosted trees (R61 V5 settings) | A2's 29 + all 15 |
| M3 | logistic meta-labeller | A2's 29 + the 8 price-only inputs (the phone needs no chain for them) |

How the variants work:
- The filters keep V1's model and scores. A skipped candidate is removed before A2's take rule, so a later candidate
  that day can still be taken.
- All variants use A2's take rule: the first candidate of the day scoring at least the training 70th percentile, then
  nothing more that day.
- All use a purged walk-forward with monthly refits and a 1-session embargo.

## Results

Net is in Rs, 1 lot, after real costs:
- "design WF" covers the 9 out-of-fold months, Jul 2025 - Mar 2026;
- "final" covers 1 Apr - 14 Oct 2026, read once.

The final AUC of F1-F4 is V1's score on the candidates the filter keeps. It is not comparable with the others.

| variant | design WF: trades, net | final: trades, win | final net (Rs/trade) | 2x costs | max DD | months green | top-5 share | AUC WF design / final | p | q | without MIDCPNIFTY (MIDCPNIFTY share) | gates |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **V1** (Solo 2.0) | 166, +55,910 | 128, 59% | **+99,172 (+775)** | +80,849 | 18,056 | 6/7 | 40% | 0.525 / 0.538 | 0.002 | 0.002 | +27,586 (72%) | PASS |
| F1 failed same-side break | 158, +57,050 | 126, 60% | +90,775 (+720) | +72,890 | 13,953 | 6/7 | 44% | 0.515 / 0.556 | 0.002 | 0.002 | +15,540 (83%) | PASS |
| F2 rejection wick | 166, +55,698 | 127, 59% | +86,888 (+684) | +68,810 | 18,855 | 5/7 | 45% | 0.520 / 0.540 | 0.002 | 0.002 | +34,846 (60%) | PASS |
| F3 low break volume | 95, +46,298 | 84, 52% | +28,954 (+345) | +18,297 | 16,349 | 5/7 | 124% | 0.546 / 0.520 | 0.032 | 0.032 | +11,991 (59%) | FAIL: top-5 ≥ net |
| **F4** trap union (candidate) | 144, **+65,534** | 117, 59% | +75,675 (+647) | +59,283 | 16,692 | 6/7 | 52% | 0.522 / 0.575 | 0.002 | 0.002 | +18,048 (76%) | PASS |
| M1 logistic + all 15 | 166, +31,324 | 128, 59% | +81,096 (+634) | +62,815 | 15,669 | 6/7 | 48% | 0.525 / 0.547 | 0.002 | 0.002 | +23,853 (71%) | PASS |
| M2 trees + all 15 | 163, +45,299 | 120, 57% | +54,812 (+457) | +39,223 | 9,049 | 7/7 | 54% | 0.532 / 0.542 | 0.002 | 0.002 | +35,643 (35%) | PASS |
| M3 logistic + price-only 8 | 171, +42,985 | 128, 59% | +87,166 (+681) | +68,912 | 23,478 | 5/7 | 45% | 0.525 / 0.547 | 0.002 | 0.002 | +29,199 (67%) | PASS |

What the p values mean:
- Each variant is compared with 500 random-entry twins, which lost Rs 12.6k-24.1k on average.
- The selection-twin p (random picks from the same day's candidates) was 0.002-0.006, except F3 at 0.08.
- BH is over the 8 variants.
- Random-split AUC (leaky) in design was 0.550-0.573 for the models, against 0.52-0.53 walk-forward.

Final test per index and per setup (trades, net Rs):

| variant | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY | S1 | S2 | S3 | S4 |
|---|---|---|---|---|---|---|---|---|
| V1 | 8, +9,973 | 12, +1,912 | 26, +15,701 | 82, +71,586 | 3, +7,523 | 31, +26,255 | 92, +63,937 | 2, +1,457 |
| F1 | 8, +10,232 | 19, +640 | 27, +4,668 | 72, +75,236 | 2, +4,822 | 32, +17,758 | 90, +67,145 | 2, +1,050 |
| F2 | 10, +13,835 | 13, +15,396 | 31, +5,615 | 73, +52,043 | 2, +5,864 | 36, +27,421 | 85, +62,732 | 4, -9,129 |
| F3 | 15, +6,193 | 16, -995 | 13, +6,793 | 40, +16,963 | 5, +3,893 | 26, -8,798 | 50, +35,619 | 3, -1,760 |
| F4 | 10, +4,461 | 17, +12,070 | 29, +1,518 | 61, +57,627 | 1, -5,169 | 33, +14,242 | 82, +68,371 | 1, -1,770 |
| M1 | 9, +16,369 | 10, +2,104 | 25, +5,380 | 84, +57,244 | 1, -2,869 | 36, +36,306 | 88, +45,433 | 3, +2,226 |
| M2 | 29, +24,257 | 31, +6,599 | 26, +4,787 | 34, +19,169 | 1, +230 | 106, +73,312 | 11, -12,411 | 2, -6,319 |
| M3 | 8, +7,715 | 14, +8,285 | 25, +13,199 | 81, +57,967 | 1, +4,673 | 34, +28,263 | 90, +52,004 | 3, +2,226 |

What the per-index table shows:
- **Every filter and logistic model still leans on MIDCPNIFTY's S3 trades**, as A2 does.
- **The trees (M2) spread the trades across indices again, as R61's V5 did:**
  - only 35% of the profit from MIDCPNIFTY;
  - the most without MIDCPNIFTY: +Rs 35,643;
  - the smallest drawdown: Rs 9,049;
  - 7 of 7 months green.
  M2 still made less than half of V1's final net and was not the candidate. It is a second note, next to R61's V5, that
  a tree model spreads Solo's risk. It is not a result.

**With vs without the trap / order-flow block** (`results/design.json`, `results/final.json`, "ablation"; same days):

| comparison | design WF net (AUC) | final net (AUC) | reading |
|---|---|---|---|
| logistic: V1 (without) vs M1 (with) | +55,910 (0.525) vs +31,324 (0.525) | +99,172 (0.538) vs +81,096 (0.547) | the block cost money both times |
| trees: T0 (without, reference) vs M2 (with) | +31,460 (0.528) vs +45,299 (0.532) | +56,850 (0.536) vs +54,812 (0.542) | helped in design, not in final |

Each group, removed from M1 / each input added alone to A2 (walk-forward AUC change, design / final):

| group | removed from M1 | added alone to A2 |
|---|---|---|
| traps | +0.002 / +0.000 | `trap_same_or` +0.000 / +0.000; `trap_opp_or` -0.001 / -0.000; `trap_same_pd` -0.002 / -0.000; `trap_opp_pd` -0.001 / +0.000 |
| sweeps | +0.002 / +0.000 | `sweep_same30` -0.001 / +0.002; `sweep_opp30` -0.001 / -0.000 |
| signal bar | -0.006 / -0.008 | `brk_wick` +0.003 / +0.000; `brk_clv` -0.000 / +0.000 |
| break volume | +0.001 / -0.001 | `vol_brk_rel` -0.001 / -0.001 |
| OI at the break | +0.000 / +0.000 | `oi_chg15_pct` -0.000 / -0.002; `bu15` -0.003 / +0.000 |
| delta proxies | -0.002 / -0.003 | `delta30` +0.004 / +0.002; `cdelta_day` +0.000 / -0.001 |
| option-volume tilt | +0.001 / +0.001 | `optvol_imb15` -0.001 / -0.001 |
| absorption | +0.002 / -0.001 | `absorb15` -0.001 / -0.001 |

How to read this table:
- A positive "removed" number means the model was better WITHOUT the group.
- Every change is under 0.01, which is noise on these samples.
- The net effects went both ways. The largest was `delta30` added alone: -Rs 9,415 in design and -Rs 32,868 in final,
  even though its AUC rose slightly.

**Descriptive flag table** (design, all 5,780 candidates, held-contract result; not gated):

| flag on the candidate | on: n, win, mean net | off: n, win, mean net |
|---|---|---|
| failed same-side opening-range break | 1,673, 43.0%, -Rs 48 | 4,107, 47.2%, +Rs 78 |
| failed other-side opening-range break | 1,328, 42.5%, -Rs 49 | 4,452, 47.0%, +Rs 68 |
| failed same-side previous-day break | 998, 45.7%, +Rs 18 | 4,782, 46.0%, +Rs 46 |
| failed other-side previous-day break | 1,005, 41.5%, -Rs 113 | 4,775, 46.9%, +Rs 74 |
| same-side sweep (last 30 min) | 2,084, 45.0%, -Rs 37 | 3,696, 46.6%, +Rs 85 |
| other-side sweep | 138, 43.5%, -Rs 93 | 5,642, 46.0%, +Rs 44 |
| F2 rejection wick ≥ half | 314, 41.4%, -Rs 336 | 5,466, 46.2%, +Rs 63 |
| F3 below-normal break volume | 3,447, 45.5%, -Rs 28 | 2,333, 46.6%, +Rs 143 |
| F4 any trap sign | 3,328, 43.9%, -Rs 57 | 2,452, 48.8%, +Rs 175 |

What this table says:
- A failed break hurts **in both directions**. So the flags pick out choppy days. They do not say which way is safer.
- Low break volume (F3) removes too much: 60% of candidates. It made the final result depend on its top 5 trades.
- This all fits our earlier studies. Breaks continue more often than sweeps reverse (H18), and fakes cannot be told
  apart from real breaks in advance (R11).

## What this means for Solo

- **Solo 2.0 stays.** Every R61b check stays a research note: none is a Solo rule, and nothing is ported to the app.
- **Jarvis can still explain these ideas.** Each now has a CONCEPTS.md entry, so Jarvis can say what a fake breakout,
  a stop-hunt, delta or absorption is, and what our own tests found.
- **Real order flow is the open question.** It is a separate question from this study. The test sketched above can run
  once about 3-6 months of the phone's tick and depth recordings exist, for NIFTY and BANKNIFTY only. Anything from it
  that could skip or resize a live trade would start in shadow, behind a switch that is off.

## Running it on the phone (only if a variant ever replaces Solo 2.0)

- **Nothing replaces Solo 2.0**, so nothing is ported.
- **The passing meta-labellers are exported anyway.** M1, M2 and M3 are in `hunt/r61b/results/models/` as R57's small
  JSON with 25 golden vectors each. The largest gap from scikit-learn is 1.1e-16.
- **F1, F2 and F4 need no new model.** They are V1's model plus `r61beval.skip`.
- **`hunt/r61b/r61beval.py` is the spec.** It is pure Python: no numpy, files or network. It holds every feature formula
  and the scorer. The research code calls the same functions, so a Kotlin port can be checked line by line.

## How it was run (re-run)

```
L=~/.cache/iraalgo/lake
rclone copy gdrive:IraAlgo-data-lake/options_rolling_1m $L --include "opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY}_WEEK_202[456].parquet"
rclone copy gdrive:IraAlgo-data-lake/index_1m $L --include "index_1m_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,INDIAVIX}_202[456].parquet"
R57_OUT=~/.cache/iraalgo/r61b python research/hunt/r57/r57data.py build      # R57's day stores (labels, A2's inputs)
python research/hunt/r61b/r61bdata.py build                                   # near-ATM option volume / OI per minute
python research/hunt/r61b/r61bstudy.py design                                 # results/design.json (design months only)
python research/hunt/r61b/r61bstudy.py dryrun                                 # crash check on the last 3 design months
python research/hunt/r61b/r61bstudy.py final                                  # the ONE final read (refuses a second)
python research/hunt/r61b/r61breport.py                                       # concept results -> CONCEPTS.md / concepts.json
```

Timings:
- day stores: about 8 minutes;
- option aggregates: 3.5 minutes;
- design: about 12 minutes (mostly building the candidate rows);
- final: under a minute.

The lake copy and the cache were deleted afterwards. `final` now refuses to run (`results/FINAL_READ`).

## Tests

`python -m pytest research/hunt/r61b/test_r61b.py -q`: 22 tests on hand-made bars, with no lake needed. They cover:
- every feature;
- failed breaks:
  - the opening range and the previous day's high / low;
  - a break that holds;
  - one that comes back too late;
  - an open already beyond the level;
  - a first-bar break;
- sweeps vs real new highs;
- the signal bar's wick and close location, including a bar with no range;
- the volume and absorption baselines, including too little history;
- the OI build-up codes, with OI carried forward;
- the delta proxy and the option-volume tilt;
- the look-ahead guard (every input is unchanged when every later minute is altered or deleted);
- missing minutes and no chain at all;
- the previous session across a holiday;
- expiry days left out of the baselines;
- nothing from before 20 Nov 2024;
- the skip filters (a missing value never skips);
- filters keeping V1's scores, with at most one trade a day;
- the day aggregates from option rows (volume never carried forward, OI carried forward);
- the one-read guard;
- the replace rule;
- the MIDCPNIFTY share.

R61's 35 tests and the brain tests also pass.

Signal source of every rule:
- S1-S4 read their signal from the underlying index.
- Every R61b input is a filter or context: trap flags come from the index minutes, and volume / OI proxies from the
  option chain.
- No rule here reads its signal from an option's own price chart.
