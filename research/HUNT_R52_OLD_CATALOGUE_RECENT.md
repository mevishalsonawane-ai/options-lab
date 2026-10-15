# R52: older rejected index-option rules, re-tested on Nov 2024 onwards only (15 Oct 2026)

Boss's asks:
- 15 Oct 2026: "any more strategies we rejected, try that too".
- 15 Oct 2026: "add the Nov 2024 onwards view. Don't test any data before that".

**Verdict: 1 of 188 final-test reads passes every gate.** It is the Market Profile **"open outside value and go"**
rule (H37-10), at its re-tuned exit: buy 1-ITM in the opening direction and sell 60 minutes later. It is a candidate
for PAPER only, with real caveats (below):
- its final-test profit comes almost all from MIDCPNIFTY;
- the original exit lost money.

**Two more rules fail only the same-side random-twin test** (they pass every other gate):
- R4-8, the Gann 2-bar swing break, 30-minute exit;
- H45-1, Liquidity entries with a ±10% premium bracket and a 30-minute stop.

They stay off.

**Everything else stays off:**
- **Coverage:** 114 rules tested (1,468 design settings), from a catalogue of 268 distinct older rejected rules.
- **Final test:** 123 reads fail and 64 are "too few trades to judge".
- **Not reached:** 159 catalogue rows. They are listed for a follow-up in `hunt/r52/CATALOGUE.md`.

Nothing in the app changes.

**Signal source:**
- Every tested rule computes its signal on the index (the CLAUDE.md default).
- Some rules use India VIX or option-chain OI, IV or volume only as a filter around an index signal. Each rule's row
  says so.
- No tested rule reads an option's own price chart.

Code, tests and results are in `hunt/r52/`. Pre-registration: `hunt/r52/PREREG.md`, with each rule's original setting
and grid committed in `results/rules_prereg.json` before its design run, and Amendment 1 on the twins. Experiment
entries: `experiments/entries/r52-*.yaml` (114).

## What was done

1. **Catalogue (`hunt/r52/CATALOGUE.md`).**
   - **Sources read:** every write-up in scope, which means the hunt series H1-H47 plus R1, R4, R5, R7-R13, R15, R16,
     R18, R19, R20b/c, R24, OF1, IV1 and X1-X3; and the topic studies OBUY_*, OPTION_BUYING_CATALOG, TAS_*, SCALP*,
     EXPIRY_*, SWING*, BIG_BAR*, REVERSAL_SETUP, GREEN/RED_CANDLES, TREND_FILTER, SIDEWAYS_GUARD, LIQUIDITY_* (index),
     CHANDELIER_EXIT, PROFIT_LOCK*, NONDIRECTIONAL, LONG_VOL, STRAD_INDEX, BOTH_SIDES, MATRIX_*, ST_EMA_PINE, SOLO,
     HOLD/SELL_LEVELS, DIRECTION_MODEL, VOLATILITY, OI_FILTER, EVENT_SHIELD and ENTRY_CUTOFF.
   - **Rows:** 268 distinct rejected rules, after near-duplicates were merged. Each row gives the source file, the old
     verdict and sample, and the signal source.
   - **Left out:** descriptive studies, adopted arms, non-index rules, and the scope of R49 (ORB family), R50
     (zero-to-hero and expiry hero) and R51 (F1, R44, R29, R28, R27, R41, B2, R45, M4, paused arms).
   - **Tiers:**
     - P1 = came "close" the first time: 96;
     - P2 = proposed by Boss: 27;
     - P3 = the rest: 127;
     - X = not testable on the lake, or a selling rule: 18.
2. **Re-test.** Each rule runs on **its own study's code**, imported unchanged wherever it still runs:
   - obuy strategies, `hunt/r1/signals.py`, `hunt/r4/signals4.py`, `hunt/r5/pine5.py`, `hunt/h37/sigs.py`,
     `hunt/h4/comps.py`, `indicator/liquidity.py`, `swing_deep.py`, h42/h28/h31 helpers and others;
   - where the original read deleted caches, its code was ported line by line, with a citation;
   - the code is in the eight `hunt/r52/fam_*.py` modules, with 281 unit tests (plus 9 for the harness), including a
     look-ahead guard per signal function;
   - signals feed research/obuy's engine through `lake2obuy.py` (the Drive lake laid out the way obuy reads it).
3. **Which rules were tested:**
   - every P1 rule except four (SI-1, LV-6, R16-2, OBUY_LV05_MULTIDAY-1; reasons below);
   - the P2 rules that are stand-alone rules (Boss's Pine scripts ST-1, MP-1, MV-1 and R5-1; BS-1; CH-1;
     REVERSAL_SETUP-1; the Liquidity variants; SCALP17-2; H43-2/3/7; R20C-4; R24-2/4).
   The P2 overlays on adopted arms and the P3 tier were not reached (see "What is left").

## Data, split and measurement (PREREG.md; never relaxed)

- **Data:** Drive lake `gdrive:IraAlgo-data-lake`: index 1-minute bars, `options_rolling_1m` WEEK series (nearest
  expiry, ATM±10, with OI, IV and volume) and India VIX.
  - **One series:** WEEK and MONTH are the same contract for BANKNIFTY, FINNIFTY and MIDCPNIFTY in this window, so one
    series is used ('near').
  - **Monthly series:** rules that said "monthly" use the MONTH series.
- **Window:**
  - **Trades:** only from **20 Nov 2024**. Jan-Nov 2024 sessions serve only to warm up indicators, and their signals
    are dropped before pricing.
  - **DESIGN:** 20 Nov 2024 to 31 Mar 2026, with a walk-forward over four quarters (Apr 2025 to Mar 2026), each
    scored with settings chosen on the sessions before it.
  - **FINAL TEST:** 1 Apr to 13 Oct 2026, **read once** (`results/FINAL_READ`), after every design result was
    committed (commit 1676ec5).
- **Measurement:**
  - **Contract:** one **held contract** per trade. obuy re-keys the rolling rows by strike, so the strike bought is the
    strike sold. It is never the rolling ATM series (R45).
  - **Fills:** next-minute fills, R17's liquidity spread model (`Fills("liq")`), and stops that gapped through filled
    at the open.
  - **Charges:** the rates in force on each date (`Costs("dated")`; STT 0.1% to 31 Mar 2026, 0.15% after).
  - **Lot:** the contract's own lot in force (for example NIFTY 25 on the Dec 2024 contracts, 75, then 65).
  - **Size:** one lot per trade.
  - **Multi-day holds:** they stay on the same contract through obuy's multi-day path, and must end inside their
    period.
- **Statistics:**
  - **Random-entry twins:** 20 alternatives per real trade, drawn 2,000 times: same day, index and side, a random
    minute in the rule's window, the same strike rule and exits. This gives a one-sided p.
  - **Benjamini-Hochberg:** run over all 188 final reads.
  - **Gates:**
    1. final net > 0;
    2. p < 0.05;
    3. q ≤ 0.10;
    4. 40 or more final trades (fewer: "too few trades to judge");
    5. the top 5 final trades below 100% of the final net;
    6. design walk-forward net > 0.
  - **Re-tune:** each rule's ORIGINAL setting was read first. The re-tune pick is the best design net among the
    original study's own grid (at most 23 settings), counting only settings with walk-forward > 0. Both were read once
    in the final test.
- **Amendment 1:** made after the first design runs, with no final-test session read.
  - **The problem:** a same-side twin keeps the real trade's side, so on a breakout day it "knows" the direction and
    may enter before the breakout. For a rule that always trades at one fixed minute, the twins are the real trades
    themselves, so p = 1 by construction (the calendar rules H28, H42 and BS-1).
  - **The gate stays as pre-registered:** on the same-side p, the stricter test.
  - **Added, information only:** the coin-flip-side p (obuy's own default). A rule that fails only on the same-side p
    is flagged and still does not pass.
- **Honesty about "unseen":**
  - The earlier studies' holdouts already covered parts of 2025-26 (Oct 2025 to Oct 2026 for most of the hunt series),
    so the final test is not unseen data for the people who first wrote these rules.
  - R24's frozen tercile cuts were fitted on data to 13 Apr 2026, which overlaps the first two weeks of the final
    test.
  - **Only live paper from 15 Oct 2026 is truly unseen.**

## The one rule that passes: H37-10 "open outside value and go", 60-minute exit

**Signal (index, the default):**
1. Build the previous session's Market Profile from 30-minute TPO periods, with a 70% value area (VAH / VAL).
2. If today **opens above VAH** and the **first 5-minute bar (09:15-09:20) also closes above VAH**, buy a CE.
3. If today **opens below VAL** and the first bar closes below VAL, buy a PE.
4. The decision is on the 09:20 close; the fill is the next minute's open.

**Trade:**
- 1-ITM strike, nearest expiry ('near');
- **out 60 minutes after entry** (time exit, no premium stop or target);
- square-off 15:10;
- expiry days skipped;
- one position at a time per index;
- all five indices (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX).

Code: `hunt/h37/sigs.py mp_signals` (unchanged) through `hunt/r52/fam_h25h37.py h37_mp`. The setting is grid point
`g17`: `{'name': 'MP_OOV', 'tf': 5}` · ITM1/near · `Exits(time_stop=60, time_gain=None, sig_levels=False)`.

| read | trades | win | net | net/day | avg win | avg loss | worst day | max DD | p | p coin-flip | q | result |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (H37, 2021-Sep 2025; holdout to Oct 2026) | 426 | - | +22,950 (WF, 2 of 4 years) | - | - | - | - | - | - | - | Holm 0.31 | rejected |
| NOW design, original (+15 / -10 premium points) | 714 | 43% | -29,084 | -86 | +898 | -753 | -8,031 | -60,910 | 0.011 | 0.014 | | WF -1,481 |
| NOW design, re-tune g17 (60-minute exit) | 714 | 54% | +292,648 | +868 | +2,583 | -2,147 | -21,273 | -85,666 | 0.000 | 0.000 | | WF +272,367 |
| **FINAL test, g17** | **294** | **50%** | **+48,772** | **+367** | **+2,244** | **-1,885** | **-15,837** | **-46,363** | **0.001** | **0.014** | **0.03** | **PASS** |
| FINAL test, original | 294 | 45% | -1,912 | -14 | +980 | -799 | -6,230 | -16,930 | 0.003 | 0.010 | 0.06 | FAIL |

**Read it with care:**
- **The exit was re-tuned on design.** At the original +15 / -10 point exit the rule lost on design (-29k) and was
  flat in the final test (-1.9k). The expanding-window re-tune (each quarter scored with the pick from before it) still
  made +195.7k on design. But 140k of that came in Jan-Mar 2026, and three of the four quarters picked a neighbour of
  g17 (g16 or g13), not g17 itself.
- **Final profit is one index.**
  - Final test by index: MIDCPNIFTY +46,329, BANKNIFTY +6,794, SENSEX +4,775, FINNIFTY -4,647, NIFTY -4,479.
  - On design, FINNIFTY (+144k) and BANKNIFTY (+107k) carried it, and MIDCPNIFTY made +19k.
  - The leading index changed. That is consistent with a real but noisy effect, or with luck.
- **Top 5 trades = 82% of the final net.** It is under the 100% gate, but the margin is thin.
- **Coin-flip twins:** q over all reads would be 0.29. This rule passes only on the pre-registered same-side twin
  test.
- **No premium stop:** a 60-minute hold on a 1-ITM option. The worst final day was -15.8k on 1 lot in each of 5
  indices.

## The two near misses (fail only the same-side twin test; they stay off)

| rule (setting) | FINAL trades | win | net | net/day | avg win / loss | worst day | max DD | p | p coin-flip | q | design WF |
|---|---|---|---|---|---|---|---|---|---|---|---|
| R4-8 Gann 2-bar swing chart break, 15-min, exit after 30 min (g03), NIFTY / BANKNIFTY / FINNIFTY, 1-ITM | 242 | 50% | +38,895 | +292 | +1,549 / -1,251 | -8,517 | -18,807 | 0.128 | 0.001 | 0.67 | +77,536 |
| H45-1 Liquidity 15+5 entries (BN 15+5, FIN 30+5, MIDCP 15+5), 1-ITM, ±10% premium bracket, 30-minute time stop (g14) | 341 | 53% | +31,275 | +237 | +1,335 / -1,315 | -10,975 | -22,106 | 0.101 | 0.001 | 0.62 | +151,307 |

- **R4-8:** both were re-tuned picks. FINNIFTY made most of the final net (+29.2k), and the original R4-8 setting lost
  -43.7k in the final test.
- **H45-1:** the original ±5% bracket lost -31.0k in the final test. The re-tuned version is the arm's own entries
  with a different exit, so it is an exit overlay on adopted books, not a new rule.
- **Both:** under coin-flip twins both would be at q 0.047. Their entries beat a random minute only when the random
  minute also gets a random side. That is a weaker claim than the gate asks for.

## Results: every rule tested (OLD vs NOW design vs FINAL)

- **How to read the table:**
  - "WF" is the design walk-forward net.
  - Final reads show trades, net, same-side p and BH q.
  - "too few trades" = under 40 final trades.
- **Per-rule detail:** full tables per rule, with trades, win rate, net, net/day, average win and loss, worst day, max
  drawdown, p, coin-flip p and q, for the design and final reads of both the original and the pick, plus the original
  setting, why it was chosen, design by index and by fold, are in `hunt/r52/results/report.md`.
- **Machine-readable:** `results/design.json` and `results/final.json`.

| rule | signal source | OLD verdict | NOW design: original (trades, net, walk-forward) | re-tune pick (design net, WF) | FINAL original (trades, net, p, q) | FINAL pick (trades, net, p, q) | result |
|---|---|---|---|---|---|---|---|
| H37-10 | index | Open-outside-value WF 2/4 yrs +22,950 (426 tr), Holm 0.31; MP pooled -112/tr | 714, -29,084, WF -1,481 | `g17` +292,648, WF +272,367 | 294, -1,912, 0.00, 0.06 | 294, +48,772, 0.00, 0.03 | PASS |
| LIQ-19 | index | Rejected (risk): N3 +53-55k/yr, better than A in 4/6 yrs but DD -49..-55k vs -37k | 559, +164,533, WF +160,504 | `g03` +159,114, WF +174,366 | 221, +45,878, 1.00, 1.00 | 224, -1,606, 1.00, 1.00 | FAIL |
| H28-3 | index (calendar) | Pre 75 tr +72,743, 4/4, p coin 0.023; hold +33,434 (20) | 19, +21,721, WF +21,721 | `g07` +27,445, WF +27,445 | 20, +35,567, 1.00, 1.00 | 20, +41,174, 1.00, 1.00 | too few trades |
| R4-8 | index | NIFTY 15m 679 tr -77/day, hold 113 tr +42/day; FIN 60m -2 / -44 | 407, -118,389, WF +24,999 | `g03` +50,942, WF +77,536 | 171, -43,710, 1.00, 1.00 | 242, +38,895, 0.13, 0.67 | FAIL (fails only the same-side twin test) |
| OBUY_GC-14 | index | Paper control only. IS best +318k (1,236 tr, 7/7; 2024 +4.1k, 2025 +0.7k); WF -42.4k (863)... | 316, -14,304, WF -12,247 | none with WF > 0 | 122, +37,885, 0.99, 1.00 | - | FAIL |
| MP-1 | index | ATM -109.4k (356 tr) / -109.4k (352); H22 1,491 tr -2.21L | 459, -184,661, WF -132,415 | none with WF > 0 | 171, +32,583, 0.00, 0.02 | - | FAIL |
| H45-1 | index | 528 variants, 0 BH. BN +-5% -76/day pre, -255/day hold; MIDCP hold +93/day but pre -114/tr... | 863, +27,725, WF +54,530 | `g14` +136,921, WF +151,307 | 344, -31,049, 0.84, 1.00 | 341, +31,275, 0.10, 0.62 | FAIL (fails only the same-side twin test) |
| H35-1 | index (grid rows maxoi / maxpain: levels from chain OI) | REJECT. 0/408 net > 0; e.g. pivot break 7,525 tr -7.10L (gross +7.7k); round numbers hold ... | 2735, -331,316, WF -176,393 | none with WF > 0 | 1039, +31,137, 0.67, 1.00 | - | FAIL |
| H31-4 | index | R03: 82% of variants +, median +151/tr; hold 48% +. R08: 65%, +71/tr; WF 2023 pick +30,450 | 788, +163,436, WF -80,611 | `g01` +210,837, WF +51,736 | 291, -10,803, 1.00, 1.00 | 291, +30,745, 1.00, 1.00 | FAIL |
| SWING-3 | index | 1-day ATM +129.8k (yr1, 244 tr) then -83.0k (yr2); ITM1 -94.9k / -67.5k | 303, -121,199, WF -106,182 | none with WF > 0 | 112, +26,411, 0.00, 0.02 | - | FAIL |
| R24-8 | index + option-chain context (IV rank) | Design 826 tr +49,439; hold 62 tr +14,737; rand p 0.138, pattern p 0.030 | 185, -63,305, WF -54,123 | none with WF > 0 | 84, +26,083, 0.08, 0.55 | - | FAIL |
| H43-5 | index + option-chain context (OI) | Pre +401/day (2025 alone +186k); hold +228/day but top 5 tr made it, t 0.6; nested WF BB -... | 280, +292,475, WF +233,083 | `g13` +314,916, WF +270,048 | 111, -15,676, 0.95, 1.00 | 110, +24,737, 0.52, 1.00 | FAIL |
| R1-14 | index | NIFTY 0.6% LIQ 242 tr -29/day, hold 36 tr +83/day; BN -80 / +47 | 138, -13,005, WF -3,960 | none with WF > 0 | 57, +24,511, 1.00, 1.00 | - | FAIL |
| R24-7 | index + option-chain context (ATM IV) | Design 1,015 tr +51,329; hold 61 tr +804 (-220 at 2x spread) | 176, -26,566, WF +9,072 | `g01` +4,125, WF +11,125 | 81, +24,427, 0.02, 0.20 | 81, -9,340, 0.52, 1.00 | FAIL |
| OBUY_GC-15 | index | IS best +68.4k on 66 tr (ST, Varsity, hold 5); WF -404 on 17 tr. SWING_DEEP-5 NIFTY monthl... | 17, +103,540, WF +87,492 | `s4.r0.x0` +74,013, WF +72,940 | 7, +19,978, 0.88, 1.00 | 7, +23,686, 0.42, 1.00 | too few trades |
| OBUY_GA-11 | index | IS best +31.5k (329 tr) 5/7; WF -28.1k (168) 1/5; PBO 76% | 23, -21,898, WF -10,829 | `s6.r1.x0` +83,789, WF +72,472 | 7, +21,335, 0.00, 0.02 | 66, -24,983, 0.91, 1.00 | FAIL |
| R4-3 | index | NIFTY 15m NAT 653 tr -56/day, hold 108 tr -17; BN -159 / -88 | 343, -51,863, WF -9,727 | `g01` +21,359, WF +31,231 | 118, +20,694, 0.00, 0.03 | 126, -26,721, 0.07, 0.55 | FAIL |
| H28-2 | index (calendar) | Pre 177 tr +79,759, p coin 0.027; as WF pick 2024 -29.3k, 2025 -15.0k; hold +12,855 (13 tr) | 17, -21,699, WF -6,313 | `g08` +20,216, WF +14,693 | 6, +19,443, 1.00, 1.00 | 6, -1,993, 1.00, 1.00 | too few trades |
| R1-4 | index | NIFTY expiry 268 tr -33/day, hold 51 tr +6; BN -96 / +35 (13 tr). TD-02 IS best -11.4k 5/7... | 88, -36,286, WF -21,367 | `g06` -10,490, WF +38,047 | 34, +19,309, 1.00, 1.00 | 129, -9,797, 1.00, 1.00 | FAIL |
| R24-3 | index + option-chain context (ATM IV) | Fail gate 3. Design 749 tr +24,947; hold 47 tr +14,066 (19% won), rand p 0.073, pattern p ... | 114, -81,094, WF -85,479 | none with WF > 0 | 64, +17,335, 0.13, 0.67 | - | FAIL |
| LIQ-5 | index | Reject: almost all negative; 15-min found/both/colour -8.8k (58 tr) / +25.3k (43); found/p... | 75, -4,495, WF -2,464 | none with WF > 0 | 22, +16,267, 1.00, 1.00 | - | too few trades |
| LIQ-22 | index | 14:00 adopted (+110.4k / +33.6k); 14:30 +103.0k / +30.3k; off +87.0k / +21.8k | 360, +94,528, WF +68,583 | `g01` +84,845, WF +59,746 | 147, +14,698, 0.55, 1.00 | 154, +15,754, 0.53, 1.00 | FAIL |
| LIQ-12 | index | Never adopted: est. +24,660 (333 tr, t 1.31) / held-out +35,890 (335, t 1.55); strict mode... | 422, +1,980, WF -1,889 | `g05` +25,170, WF +38,055 | 161, +15,162, 0.08, 0.55 | 113, +12,283, 0.51, 1.00 | FAIL |
| R13-1 | index (weekly NIFTY); original traded futures / modelled calls, R52 tests the nearest monthly ATM call | Not proven. Wait +1000/-500 futures 8 tr +38.6k; strict 8 tr -69.3k; plain touch hold 4w 9... | 1, -18,196, WF -18,196 | none with WF > 0 | 1, +14,815, 1.00, 1.00 | - | too few trades |
| R24-5 | index | Design 513 tr +33,125; hold 18 tr +3,187 (p 0.116), under 30 trades | 75, -30,380, WF -34,033 | `g02` +17,788, WF +4,180 | 20, +14,682, 0.03, 0.26 | 20, -14,527, 1.00, 1.00 | too few trades |
| H33-1 | index + option-chain context (option volume) | REJECT. FIN 193 tr +43/day (gross 67), 4/5 yrs, BH q 0.90; hold -16/day. NIFTY 226 tr +26/... | 68, +35,555, WF +39,534 | `g03` +40,788, WF +57,946 | 20, +12,037, 0.20, 0.88 | 29, +14,287, 0.02, 0.20 | too few trades |
| H42-6 | index | Not candidate: +288/tr, n 128, p 0.23 | 36, +11,135, WF +14,673 | `g23` +11,334, WF +7,182 | 17, +14,124, 1.00, 1.00 | 17, +1,868, 1.00, 1.00 | too few trades |
| R24-4 | index | Design 430 tr +25,815; hold 32 tr, 3% won, -18,601 | 88, -31,195, WF -21,636 | none with WF > 0 | 34, +12,529, 0.02, 0.20 | - | too few trades |
| R4-18 | index | BN ABCD 15m OPT 147 tr +70/day (p 0.04), hold 30 tr -160; BN all 60m 80 tr +45, hold 13 tr... | 53, -17,590, WF -12,253 | `g01` +23,911, WF +14,895 | 19, -27,351, 0.53, 1.00 | 7, +12,506, 0.02, 0.21 | too few trades |
| H25-2 | index | Gate passer; hold 76 tr +90/day (gross +121); '3 of 31 is chance level' | 123, +27,279, WF +25,953 | `g10` +25,708, WF +24,091 | 40, +12,217, 0.00, 0.08 | 45, -15,146, 0.80, 1.00 | FAIL |
| R24-6 | index + option-chain context (other-side OI build-up) | Design 1,247 tr +9,766; hold 84 tr +15,952; rand p 0.49, pattern p 0.040 | 281, +21,162, WF +51,220 | `g02` +16,967, WF +30,915 | 98, +11,523, 0.61, 1.00 | 98, -7,371, 0.89, 1.00 | FAIL |
| H18-1 | index | No pass, no holdout. Best R60 / arm+30%: 4,537 tr, gross +3.25L, net +3k, rand p <0.001, W... | 1847, -203,562, WF -121,669 | `g03` -161,278, WF +34,604 | 732, +11,125, 0.97, 1.00 | 1190, -204,055, 1.00, 1.00 | FAIL |
| H25-10 | index | Post-hoc Welch q < 0.05, about +30/tr; 0 pass the gate | 327, +79,834, WF +30,072 | `g01` +79,834, WF +30,072 | 127, +9,304, 0.00, 0.06 | 127, +9,304, 0.00, 0.06 | FAIL |
| H9-1 | index | Do not add. BN10 310 tr +22.2k (q 0.006); MIDCP30 46 tr +28.9k (q 0.049); hold extras +17/... | 157, +29,279, WF +29,623 | `g05` +32,197, WF +16,399 | 66, +8,285, 0.42, 1.00 | 89, +3,982, 0.27, 1.00 | FAIL |
| H25-3 | index | Gate passer; hold 83 tr +39/day (T30), +12 (T15) | 100, +27,447, WF +16,739 | `g11` +20,814, WF +9,326 | 49, +3,266, 0.11, 0.63 | 49, +8,255, 0.02, 0.21 | FAIL |
| H43-6 | index | Pre +246/day (2024 +183k only); hold -82/day; nested WF EMA -138/day | 243, +5,335, WF +45,509 | `g13` +11,970, WF +31,207 | 99, +8,226, 0.18, 0.83 | 99, +2,304, 0.40, 1.00 | FAIL |
| REVERSAL_SETUP-1 | index | Too few: next-expiry 3:1 15-37 tr -2.5k..-11k; 20k move 0.3% +10% no stop +4,506 (37 tr), ... | 59, +9,658, WF +7,259 | `g04` +18,073, WF +15,344 | 16, +2,229, 0.00, 0.08 | 16, +6,729, 0.00, 0.03 | too few trades |
| H37-3 | index | MIDCP 574 tr +19,565 (+28/day); hold 294 tr -4,223; WF 2/4 +11,454, Holm 0.61 | 393, +50,592, WF +36,754 | `g15` +104,938, WF +88,893 | 151, +6,702, 0.66, 1.00 | 140, -33,822, 1.00, 1.00 | FAIL |
| H37-9 | index | Family net -111/tr; SENSEX double-top top-10 by t (pre +42..134/day), hold +1..10/day | 97, +4,369, WF +18,039 | `g03` +43,101, WF +51,249 | 41, +3,754, 0.91, 1.00 | 59, +4,285, 0.92, 1.00 | FAIL |
| H42-9 | index | Not candidate: +314/tr on 35 tr (NIFTY/BN -290..-342) | 25, -2,929, WF +1,334 | `g01` +19,844, WF +23,068 | 17, +3,981, 1.00, 1.00 | 17, +2,142, 1.00, 1.00 | too few trades |
| R1-8@NIFTY | index | BN 0.2 60m 471 tr +53/day, hold -8; NIFTY 0.1 LIQ -2 / +18 | 73, -1,141, WF +15,284 | `g05` +2,035, WF +15,145 | 35, +3,687, 1.00, 1.00 | 35, -13,404, 1.00, 1.00 | too few trades |
| H11-2 | index | FAIL. Pre +11.4/day (2023 -7.9k, 2024 -0.7k, 2025 +20.0k), WF -7.0k; hold 105 tr -18.5/day... | 165, +15,533, WF +21,696 | `g02` +15,121, WF +26,277 | 58, +762, 0.40, 1.00 | 71, +3,529, 0.36, 1.00 | FAIL |
| R1-2@BANKNIFTY | index | NIFTY k0.75 EOD 500 tr +61/day; hold 95 tr +93/day (rand p 0.23, 7/13 months); BN LIQ +101... | 142, -13,812, WF +11,056 | `g01` -26,128, WF +20,926 | 59, +3,515, 0.93, 1.00 | 127, -55,889, 0.84, 1.00 | FAIL |
| R20C-1 | index | Rejected. Design -21,292 (491 tr); hold +25,603 (183): t p 0.021 (q 0.33), random-level p ... | 221, +9,075, WF +9,060 | `g01` +28,810, WF +27,257 | 100, +3,013, 0.02, 0.21 | 107, -14,573, 0.41, 1.00 | FAIL |
| R4-13 | index | Too few: BN 33 tr -3/day, hold 8 tr +42; NIFTY 41 tr -5 / -39 | 22, -3,022, WF +6,259 | none with WF > 0 | 8, +2,260, 1.00, 1.00 | - | too few trades |
| OBUY_SEARCH-4 | index + option-chain context (PCR of OI) + VIX | IS 61 tr t 3.59 +714/tr; hold 10 tr +453/tr (+4,534), random -140/tr; the only hold winner... | 4, +2,263, WF +2,263 | `g07` +4,132, WF +4,132 | 4, -1,987, 1.00, 1.00 | 4, +2,238, 1.00, 1.00 | too few trades |
| R4-2 | index | BN brk T30 1,114 tr -62/day, hold 271 tr -6/day; NIFTY -140 / -61 | 374, -9,604, WF +6,652 | `g01` -73,267, WF +10,003 | 140, +2,075, 0.30, 1.00 | 164, -58,585, 0.90, 1.00 | FAIL |
| R1-1 | index | NIFTY m1.0 EOD 659 tr +89/day, q 1.0, rand p 0.23; hold 133 tr -297/day. BN m1.5 trail +26... | 308, -69,698, WF -3,391 | `g08` +37,704, WF +65,449 | 126, -58,788, 1.00, 1.00 | 97, +1,234, 0.79, 1.00 | FAIL |
| R20C-1N | index | Rejected. Design -21,292 (491 tr); hold +25,603 (183): t p 0.021 (q 0.33), random-level p ... | 193, +4,851, WF +10,254 | `g01` +16,980, WF +13,722 | 80, +703, 0.10, 0.62 | 82, -2,825, 0.40, 1.00 | FAIL |
| H28-4 | index (calendar) | Jan NIFTY pre 105 tr +68,119, hold -22,987; first-3 BN pre 148 tr +67,619 (p coin 0.001), ... | 91, -12,529, WF -18,545 | `g04` +20,434, WF +2,004 | 21, -10,852, 1.00, 1.00 | 21, +586, 1.00, 1.00 | too few trades |
| LIQ-9 | index | Paper candidate only: +36,979 (55 tr, t 1.88) / held-out +6,191 (33 tr, t 0.78); knife edg... | 37, +4,387, WF +8,012 | `g21` +9,114, WF +13,254 | 8, -716, 0.31, 1.00 | 7, +441, 0.19, 0.85 | too few trades |
| SWING_DEEP-8 | index | NIFTY monthly ATM 10d +145k (44 tr) 4/7; BN WF picks -71k/+134k/-100k. SWING_EXITS-3 NIFTY... | 16, -127,178, WF -102,426 | `g09` +6,202, WF +8,876 | 5, -80,880, 0.09, 0.61 | 9, +438, 0.45, 1.00 | too few trades |
| H28-1 | index (calendar) | Not significant over 8,820 variants. Pre 105 tr +100,164, WF 4/4, p coin 0.047; hold +22,7... | 43, +45,326, WF +23,284 | `g05` +31,444, WF +32,628 | 0, +0, 1.00, 1.00 | 0, +0, 1.00, 1.00 | too few trades |
| OBUY_SEARCH-2 | index | IS 41 tr t 3.81 +836/tr; hold 0 trades (FIN weekly gone) | 2, +5,568, WF +5,568 | `g05` +9,256, WF +9,256 | 0, +0, 1.00, 1.00 | 0, +0, 1.00, 1.00 | too few trades |
| R5-1 | index | 'Do not run, even on paper'. AS_WRITTEN 0 trades; pooled 77 tr, 66 instant stop-outs; best... | 0, +0, WF +0 | `g07` +2,636, WF +2,636 | 0, +0, 1.00, 1.00 | 0, +0, 1.00, 1.00 | too few trades |
| H42-7 | index | Not candidate: +275/tr, p 0.12 (index effect q 0.006) | 50, +21,419, WF +22,608 | `g23` +4,032, WF +2,241 | 18, -9,001, 1.00, 1.00 | 18, -150, 1.00, 1.00 | too few trades |
| H37-6 | index | Daily cycles WF 2/3 yrs +13,886 on 41 tr, Holm 0.97; intraday WF -262k/-362k; R4-11/12 los... | 20, -371, WF +25 | `g01` +11,709, WF +5,166 | 5, -221, 0.39, 1.00 | 36, -8,099, 0.41, 1.00 | too few trades |
| H25-5 | index | Top pre t: 110 tr +182/tr, t 2.44, +20/day; hold 36 tr -38/day | 60, +15,887, WF +14,325 | `g10` +18,991, WF +14,631 | 19, -1,506, 0.06, 0.53 | 19, -1,101, 0.06, 0.53 | too few trades |
| LIQ-3 | index | Reject: none positive both yrs; best pool/no stop/overnight +53k (57 tr) / -16k (68 tr) | 87, -118,677, WF -96,276 | none with WF > 0 | 28, -1,170, 1.00, 1.00 | - | too few trades |
| H37-2@NIFTY | index | NIFTY 997 tr +25,024 (+20/day), hold 199 tr +4,017; MIDCP 497 tr +35,201, hold 264 tr -17,... | 269, +32,861, WF +26,637 | `g18` +36,236, WF +39,552 | 112, -3,801, 0.80, 1.00 | 115, -1,404, 0.38, 1.00 | FAIL |
| R1-9 | India VIX + index | Too few: NIFTY chg15 18 tr in 5 yrs +44/day, rand p 0.04; hold 7 tr +13. BN 14 tr +51 / -26 | 14, -9,077, WF -3,161 | `g03` +52,864, WF +59,261 | 4, -1,881, 1.00, 1.00 | 10, -1,911, 1.00, 1.00 | too few trades |
| LV-5 | event calendar (no price signal; straddle) | 1 day before, sold 15:10 +60,335 (14, t 0.67), all from the 2024 election; RBI -3.6k. VOL-... | 9, -16,435, WF -313 | none with WF > 0 | 3, -2,575, 1.00, 1.00 | - | too few trades |
| H42-4 | index | Pre +53/day; hold 11 tr -15/day, p 0.47 | 32, +5,786, WF +676 | `g02` +8,560, WF +8,345 | 12, -5,088, 1.00, 1.00 | 12, -2,691, 1.00, 1.00 | too few trades |
| OBUY_GC-3 | index | OBUY: 1/day WF -23.4k (421) 0/5. H34 points WF +2,635 (gross +28.5k) 3/4 yrs, fails BH/SPA... | 88, -7,575, WF -6,447 | none with WF > 0 | 34, -3,019, 0.88, 1.00 | - | too few trades |
| H43-2 | index | 1-min 1,690 tr -261/day, 1/5 yrs; hold +13/day. 5-min/30 -259 pre, hold +88 (p 0.01) | 640, -698, WF +18,518 | `g03` +125,681, WF +106,609 | 252, -3,601, 0.17, 0.83 | 248, -4,409, 0.35, 1.00 | FAIL |
| SWING-4 | index | 3-day ATM +40.6k (42 tr) then -46.0k (39); 1-day -11.6k / -40.8k | 44, -27,937, WF -22,188 | none with WF > 0 | 19, -3,633, 0.60, 1.00 | - | too few trades |
| TAS_OPTIONS-1 | index | Rejected. NIFTY 30m targets + 1/3 lock +54.1k (209 tr) t 0.68; wait-5 15m +43.4k (326 tr);... | 113, -12,252, WF +510 | `g14` +67,076, WF +75,921 | 56, -34,039, 1.00, 1.00 | 54, -4,923, 0.94, 1.00 | FAIL |
| H42-3 | index | Q0176 pre +69/day, hold 80 tr -55/day (gross +15); Q0177 pre +108, hold 90 tr -23/day (gro... | 120, +36,889, WF +35,917 | `g01` +59,543, WF +19,230 | 39, -5,368, 1.00, 1.00 | 51, -13,496, 1.00, 1.00 | FAIL |
| R1-2@NIFTY | index | NIFTY k0.75 EOD 500 tr +61/day; hold 95 tr +93/day (rand p 0.23, 7/13 months); BN LIQ +101... | 131, +38,731, WF +46,380 | `g07` +58,124, WF +61,787 | 58, -6,801, 1.00, 1.00 | 58, -8,984, 1.00, 1.00 | FAIL |
| R1-8@BANKNIFTY | index | BN 0.2 60m 471 tr +53/day, hold -8; NIFTY 0.1 LIQ -2 / +18 | 163, +7,639, WF +7,122 | none with WF > 0 | 67, -6,862, 1.00, 1.00 | - | FAIL |
| LIQ-6 | index | Replaced: index +5.8 pts (494 tr, t 1.98) / +3.6 (475); real options -6.0k / 83 tr. B-pick... | 612, +47,358, WF +66,357 | `g10` +67,804, WF +71,490 | 209, -24,273, 0.91, 1.00 | 219, -6,996, 0.51, 1.00 | FAIL |
| LV-2 | index + option-chain context (straddle price vs past index moves; no direction) | No filter positive both yrs; best cost_vs_oc10 mid +20.0k (36 d) / -19.7k (79 d) | 107, -14,771, WF +3,161 | none with WF > 0 | 40, -7,011, 0.09, 0.61 | - | FAIL |
| OBUY_GB-1 | index | Paper control. IS best s0.r0.x0 +67,141 (1,498 tr), BH q 0.077; WF -16,289 (1,417) 2/5, p ... | 514, +19,077, WF +82 | `s0.r0.x1` +14,201, WF +4,136 | 144, -8,420, 0.17, 0.83 | 144, -18,320, 0.37, 1.00 | FAIL |
| R9-4 | index + option-chain context (option volume) | 0/12 BH. NIFTY 52 events +2.2/+2.7/+4.7 bp vs control (p 0.034, q 0.29); hold 18 events -2... | 33, -8,399, WF -7,859 | none with WF > 0 | 21, -8,674, 0.19, 0.86 | - | too few trades |
| H25-6 | index | Pre 173 tr +151/tr, t 1.96, q 1.0; hold 84 tr +3/day (-2 at 1.5x spread) | 100, +25,017, WF +20,272 | `g08` +16,562, WF +12,955 | 50, -8,983, 0.74, 1.00 | 50, -23,336, 0.95, 1.00 | FAIL |
| R24-2 | index + option-chain context (ATM IV, own-side OI build-up) | Design 817 tr +7,117; hold 45 tr -20,777 | 131, -25,848, WF -20,974 | none with WF > 0 | 57, -9,175, 0.47, 1.00 | - | FAIL |
| SWING-5 | index | Too few (4 / 8-10 tr): yr2 1-day +2.6k, 3-day +16.6k, 5-day +11.0k ATM; ITM1 negative | 10, +24,318, WF +24,318 | `g02` +14,486, WF +14,486 | 6, -10,121, 0.64, 1.00 | 5, -25,567, 0.74, 1.00 | too few trades |
| R4-6 | index | NIFTY NAT 629 tr -56/day, hold 130 tr -1/day (win 57%); BN -102 / -161 | 206, +10,908, WF +17,333 | none with WF > 0 | 73, -12,125, 0.70, 1.00 | - | FAIL |
| H43-7 | index + VIX | Pre +81/day (2022 +92k); hold -140/day; WF -104/day | 97, -7,532, WF +14,193 | `g12` -4,463, WF +14,059 | 73, -28,444, 0.80, 1.00 | 75, -12,288, 0.35, 1.00 | FAIL |
| H11-1 | index | FAIL. H11 choice pre -15.1/day net (+17 gross), 2023 +3.3k, 2024 +15.0k, WF +1.2k; hold 83... | 145, +3,027, WF +17,462 | `g20` +6,899, WF +13,076 | 60, -13,114, 0.96, 1.00 | 81, -15,888, 0.92, 1.00 | FAIL |
| R20C-4 | index + VIX | Design +66,057 (115 tr); hold -33,153 (127), the worst | 170, +16,275, WF -6,331 | none with WF > 0 | 66, -14,098, 0.86, 1.00 | - | FAIL |
| H43-3 | index | 1,027 tr -205/day, 1/5; hold -190/day | 388, -46,633, WF -21,205 | none with WF > 0 | 141, -15,137, 0.66, 1.00 | - | FAIL |
| R1-6@BANKNIFTY | index | Flipped. NIFTY 982 tr +125/day, rand p 0.006 (all from 2024); hold -334/day. BN LIQ 814 tr... | 263, -38,539, WF -20,949 | none with WF > 0 | 110, -15,329, 0.77, 1.00 | - | FAIL |
| OBUY_GA-1 | index | IS best +25.4k (207 tr) 4/7; WF -83.4k. H34 points WF +2,622 (gross +19.2k) 2/4 | 99, +13,866, WF +17,930 | `s3.r0.x0` +54,900, WF +69,352 | 30, -16,135, 0.41, 1.00 | 100, -36,978, 0.08, 0.55 | FAIL |
| H5-1 | index + option-chain context (OI) | NO. Train 795 tr +386/day (1 lot +106); hold 215 tr -334/day (1 lot -150); BH 0/63,072; SP... | 283, -13,322, WF -21,014 | none with WF > 0 | 124, -16,828, 1.00, 1.00 | - | FAIL |
| R4-4 | index | BN OPT 1,115 tr -190/day, hold 230 tr +17/day; NIFTY -269 / -329 | 992, -233,121, WF -124,482 | none with WF > 0 | 372, -17,696, 0.28, 1.00 | - | FAIL |
| R1-7 | index | BN native 657 tr -77/day; hold 163 tr +247/day (rand p 0.045, 8/13 months). NIFTY -25 / -222 | 431, -90,465, WF -7,406 | none with WF > 0 | 166, -17,852, 1.00, 1.00 | - | FAIL |
| BS-1 | time; exits on premium (no price signal) | All 54 versions lose both yrs (t -2.8..-5.7); owner's set -107k / -36k | 638, -43,828, WF -33,185 | none with WF > 0 | 252, -17,944, 0.01, 0.18 | - | FAIL |
| LIQ-2 | index | Reject, mostly negative. 15-min 'both' no stop +18k (132 tr) / +37k (110), t 0.69/0.98; 15... | 172, -2,508, WF +26,400 | `g11` +21,355, WF +52,494 | 60, -18,212, 1.00, 1.00 | 109, -51,562, 1.00, 1.00 | FAIL |
| H29-3 | index + option-chain context (ATM straddle IV) | Rejected. Best: 380-444 tr, gross +4.6/day, net -30/day, rand p 0.02 (BH 0.80); WF -1.44L,... | 263, -37,487, WF -36,341 | none with WF > 0 | 102, -18,353, 0.27, 1.00 | - | FAIL |
| R1-6@NIFTY | index | Flipped. NIFTY 982 tr +125/day, rand p 0.006 (all from 2024); hold -334/day. BN LIQ 814 tr... | 267, -79,962, WF -65,786 | `g03` +31,558, WF +4,787 | 97, -18,658, 0.95, 1.00 | 108, -59,669, 0.98, 1.00 | FAIL |
| H25-11 | index | WF +21,934 on 144 tr over 4 yrs, 2/4 yrs +, Holm 1.0 | 277, -41,686, WF -41,553 | `g07` -17,691, WF +2,818 | 179, -23,784, 0.76, 1.00 | 159, -66,696, 0.73, 1.00 | FAIL |
| R4-16 | index | NIFTY major OPT 506 tr +22/day, hold 117 tr -55; FIN -66 / -7 | 165, -3,085, WF +17,949 | none with WF > 0 | 66, -23,924, 1.00, 1.00 | - | FAIL |
| OBUY_GB-4 | index | IS best -33.2k; WF -73.6k 1/5. H34 points WF +982 (gross +69.8k) 2/4 | 289, -10,762, WF +4,639 | `s1.r0.x9` -68,830, WF +6,830 | 107, -24,522, 0.98, 1.00 | 236, -59,049, 1.00, 1.00 | FAIL |
| LIQ-1 | index | Reject: every 1-min version loses both yrs (1-min swing no stop 1,688 tr -125k / -145k); 3... | 1623, -117,982, WF -45,863 | none with WF > 0 | 538, -27,115, 1.00, 1.00 | - | FAIL |
| SCALP17-2 | index | Train 1,190 tr 73% won, +10 net app; hold 671 tr -182 net app (random p 0.049); mirrors -1... | 509, -53,197, WF -51,301 | none with WF > 0 | 214, -28,486, 0.17, 0.83 | - | FAIL |
| H37-2@MIDCPNIFTY | index | NIFTY 997 tr +25,024 (+20/day), hold 199 tr +4,017; MIDCP 497 tr +35,201, hold 264 tr -17,... | 354, +74,452, WF +63,772 | `g15` +89,221, WF +85,650 | 137, -28,871, 0.93, 1.00 | 135, -35,052, 0.97, 1.00 | FAIL |
| OBUY_GA-12 | index (+ VIX filter) | Passed gates mechanically, not recommended. WF +85.8k (282 tr) 3/5 (+95.7k/+33.7k/-14.3k/+... | 71, +16,075, WF -25,903 | `s7.r1.x1` +48,443, WF +18,768 | 30, -28,882, 1.00, 1.00 | 65, -63,677, 1.00, 1.00 | FAIL |
| ST-1 | index | ST-1 5-min +8.5 pts/tr (357 tr, t 0.85), ATM +14.2k; Yr A +0.7 pts. H22 5-min 1,565 tr -2.... | 464, -23,371, WF +55,308 | `g03` -18,304, WF +64,535 | 177, -33,753, 1.00, 1.00 | 177, -43,014, 1.00, 1.00 | FAIL |
| H31-5 | index (calendar) | Median +262 / +413 per tr, q 0.06 / 0.13; only ~30 FOMC nights per index | 39, -73,083, WF -55,411 | none with WF > 0 | 16, -35,431, 1.00, 1.00 | - | too few trades |
| LIQ-4 | index | Not adopted: 15+5(L10) +22.9k (636 tr) / +20.6k (699), t 0.41/0.66 vs adopted +58k/+32k; 1... | 925, -26,178, WF +6,579 | none with WF > 0 | 326, -40,637, 1.00, 1.00 | - | FAIL |
| TREND_FILTER-1 | index | Best 3 HH/HL, -40/+40, filter on: 462 tr, 52%, -126 (t 0.00), halves +11.1k / -11.2k | 599, -76,474, WF -43,602 | none with WF > 0 | 226, -41,861, 0.88, 1.00 | - | FAIL |
| R4-1 | index | BN bnc OPT 702 tr +63/day, p twin 0.01, q 1.0; hold 155 tr -153/day. NIFTY -7 / -238 | 393, -92,563, WF -91,823 | `g06` +60,213, WF +37,899 | 145, -55,173, 0.69, 1.00 | 164, -47,430, 0.91, 1.00 | FAIL |
| CH-1 | index | Not reliable: 60-min SL1/TP1 +38.3k (71 tr) / +5.8k (76); 5-min loses all | 101, +38,068, WF +26,545 | `g05` +29,817, WF +18,201 | 58, -50,649, 1.00, 1.00 | 77, -48,518, 1.00, 1.00 | FAIL |
| SCALP17-3 | index | Train 3,006 tr +76 gross, +2 net app; hold 1,066 tr -113 net app. SCALP17-5 hold 617-631 t... | 887, -95,142, WF -61,592 | none with WF > 0 | 311, -49,259, 1.00, 1.00 | - | FAIL |
| H46-2 | index | Not run on hold. +60..+335/day, beats random p 0.0005, BH q 0.12, SPA 0.75; yrs +18k / -7k... | 583, +129,969, WF +100,244 | `g03` +132,569, WF +100,594 | 243, -72,632, 1.00, 1.00 | 243, -58,548, 1.00, 1.00 | FAIL |
| LV-4 | time (no price signal; straddle) | +18,988 (202 tr, t 0.75) / -117,193 (231, t -5.89) | 303, -170,739, WF -176,026 | none with WF > 0 | 112, -67,565, 0.00, 0.02 | - | FAIL |
| H42-8 | index | Not candidate: +93 gross, -41 net per tr | 1061, -132,464, WF -83,931 | none with WF > 0 | 399, -73,229, 0.61, 1.00 | - | FAIL |
| SWING_DEEP-6 | index (futures traded in the original; R52 tests the monthly ATM option version) | NIFTY fut 20d +570k 8/11; BN fut 2ATR+10d +590k 7/11; WF out-of-sample -21k/-41k/-9k/+33k | 23, -158,993, WF -123,669 | `g01` -16,369, WF +21,667 | 11, -75,374, 0.34, 1.00 | 27, -91,871, 0.10, 0.62 | too few trades |
| R4-10 | index | Flipped. NIFTY 2x1 NAT 1,270 tr +75/day, p twin 0.01; hold 239 tr -240/day | 1000, -131,083, WF -5,414 | `g03` -22,173, WF +24,332 | 384, -220,375, 1.00, 1.00 | 382, -82,886, 0.85, 1.00 | FAIL |
| SWING_DEEP-3 | index | NIFTY monthly ATM 10d +23.3k (63 tr) 4/7; BN options -229k..-289k; BN fut 2ATR+10d +264k | 24, -162,093, WF -121,270 | none with WF > 0 | 6, -86,573, 0.12, 0.64 | - | too few trades |
| MV-1 | index | All 38 versions lose both yrs; best 15-min CHoCH-only -55.5k (177 tr) | 1536, -349,818, WF -122,388 | none with WF > 0 | 602, -86,873, 1.00, 1.00 | - | FAIL |
| R4-14 | index | NIFTY 1,276 tr -4/day (p twin 0.02), hold -167; BN -60 / -171; R4-15 all lose | 1002, +14,170, WF +20,330 | `g16` +69,754, WF +183,806 | 371, -97,209, 0.86, 1.00 | 362, -100,290, 0.01, 0.17 | FAIL |
| R20C-3 | index | Design +33,393 (331 tr; one day +20.8k); hold -7,117 (127; BN -17.9k, NIFTY +10.8k) | 289, +51,310, WF +67,100 | none with WF > 0 | 141, -103,476, 1.00, 1.00 | - | FAIL |

## What is left for a follow-up

- **P1 rules not tested:**
  - SI-1 (straddle forecast ensemble): needs pre-2024 minute history and deleted caches; retraining on 10 months would
    be a different rule.
  - LV-6 (expiry-afternoon straddle): `long_vol2.py` calls it "the hero-zero trade", so it belongs to R50.
  - R16-2: a fitted model that needs R16's deleted grids.
  - OBUY_LV05_MULTIDAY-1: obuy's own multi-day Donchian walker. Its single-day leg OBUY_GA-12 was tested.
- **P2 rules not reached:** H17-1 (day-level P&L locks), H34-1 (premium-point exits on Liquidity), H35-9, LIQ-15,
  LIQ-20, R18-1, R19B-1, SIDEWAYS_GUARD-1, SOLO-2, SCALP17-1.
  - Almost all of these are overlays on the adopted Liquidity books, or Solo (R51's paused arm).
  - SCALP17-1 is random entry, not a rule.
- **P3 (127 rows) and X (18 rows):** not reached. These are mostly overlays on adopted arms, FAR rules, and NON-DEFAULT
  option-chain-only signals.
- **Where to find them:** every untested row is marked "left (not reached in R52)" or "not testable" in
  `CATALOGUE.md`'s R52 status column.
- **How to finish:**
  1. A follow-up writes `fam_*.py` modules to `hunt/r52/FAMILY_BRIEF.md`.
  2. It runs `r52study.py prereg` and then `design`.
  3. It needs a NEW final period (live paper from 15 Oct 2026), because this one is read.

**Deviations recorded in the modules** (each module's docstring has the full list):
- **Rules split by index:** rules whose two indices' frozen picks needed different premium exits are split by index
  (R1-2, R1-6, R1-8, H37-2, R20C-1 / R20C-1N).
- **No frozen variant:** where none was recorded (H25-11, H37-6/9/10, OBUY_GA-1, LIQ-1/2/3/5/19/22), the original is
  the study's first setting or the one its write-up names, and the re-tune grid plays the original walk-forward's
  role.
- **Expiry days:** these are skipped where an original bought the next expiry's contract on an expiry day, because the
  lake holds only the nearest one.
- **BS-1 (Boss's both-sides rule):** run as its three 1-lot scale-out lots (+5% / +8% / +10%), each its own read. All
  three lose.
- **MV-1:** runs two 1-lot halves per entry.

## What could come back on paper, and what stays off

**Could come back on PAPER, as a candidate for Boss to decide.** Per CLAUDE.md, a passed idea may act on paper arms,
logged in the Autopilot timeline and undoable. R52 itself changes nothing.
- **H37-10 "open outside value and go", 60-minute exit:**
  - previous day's 30-minute TPO value area (70%);
  - today opens above VAH and the 09:15-09:20 bar closes above VAH → buy 1-ITM nearest-expiry CE (below VAL → PE);
  - decision at the 09:20 close, fill at 09:21;
  - sell after 60 minutes;
  - square-off 15:10, skip expiry days, one position per index;
  - NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX; 1 lot.
- **Suggested with it:**
  - a premium safety stop (for example the app's usual -15%), recorded as a change from the tested rule;
  - a per-index record, because the final profit came from MIDCPNIFTY alone.
- **Live:** never, until paper from 15 Oct 2026 shows it holds.

**Stay off:** every other rule tested here, including:
- the two near misses (R4-8 g03 and H45-1 g14);
- every rule with "too few trades to judge" (the calendar rules H28, H42 and H31-5, the swing rules, R13-1, the R24
  patterns, OBUY_GC-15 and others);
- Boss's own Pine scripts (ST-1, MP-1, MV-1, R5-1);
- the Chandelier rule (CH-1);
- the reversal setup (REVERSAL_SETUP-1);
- the both-sides rule (BS-1).

Nothing about BANKNIFTY's frozen-then-unfrozen arm or any live setting is changed.
