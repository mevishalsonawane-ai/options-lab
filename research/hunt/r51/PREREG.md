# R51 pre-registration: every other cancelled, paused or failed index-option strategy, Nov 2024 onwards only

Written 15 Oct 2026, **before any R51 profit or loss was computed**. Commit this file first; results come after.
Research only: nothing here changes the app.

Boss, 15 Oct: "add the Nov 2024 onwards view. Don't test any data before that, and now find a hero-zero, ORB, and all
the strategies which we have cancelled". The ORB family (ORB, ORB Fresh, ORB Sweep, Range Fade) is R49 and the
zero-to-hero rules (Hero arm, R21/R26/R43, the 14:30 lottery L3) are R50; R51 skips them.

## 1. Data window and split

- Trades only from **20 Nov 2024** (NSE's weekly-expiry change; 20 Nov itself was a holiday, so the first session is
  21 Nov) to the lake's last session (**13 Oct 2026** for NIFTY weekly options, 12 Oct for the rest). Index minutes
  from 1 Sep 2024, and brain features from Jun 2023, are read only to warm up indicators and percentiles; **nothing
  before 20 Nov 2024 is traded or reported**. Model training (B2, R41) uses only rows from 21 Nov 2024 on.
- **DESIGN: 21 Nov 2024 - 31 Mar 2026** (walk-forward). **FINAL TEST: 1 Apr - 13 Oct 2026, read ONCE** by
  `study.py final` (the guard file `results/FINAL_READ` refuses a second read).
- Honest note: earlier studies (F1 design, R29 2025/26, R41 folds, B2, R44, R45, R27, R42 holdouts) already looked at
  parts of 2025-26, so the final test is **not pristine**. Only live paper from 15 Oct 2026 is truly unseen.
- Lots and charges in force on each date (`r51cfg.LOTS`; `obuy.costs.Costs('dated')`: STT 0.1% on option sells to
  31 Mar 2026, 0.15% from 1 Apr 2026).
- M4 SILVERM (MCX): the lake has SILVERM options only from **5 Aug 2025**, so its design is 5 Aug 2025 - 31 Mar 2026
  and its final test 1 Apr - 12 Oct 2026. Nov 2024 - Aug 2025 cannot be tested.

## 2. Measurement (never relaxed)

- Every trade is priced on **one held contract**: the strike bought at entry, rebuilt from every offset row of the
  rolling file that carried that strike (`r51data.py`). Never the rolling ATM series.
- Only the WEEK files are read (BANKNIFTY / FINNIFTY / MIDCPNIFTY WEEK and MONTH are the same contract in this
  window; NIFTY / SENSEX WEEK is the nearest weekly expiry).
- Next-bar fills: a signal on a bar's close fills at the open of the next minute (up to 2 minutes later if that
  minute has no trade, else no fill). Stops on the minute low, gap-through fills at the open (the worse price), the
  stop wins a tie with the target, limit targets at their price, locks apply from the next minute.
- Real fills: R17 "liq" model (+-5 bps, stops -10 bps, half-spread 1/2/4 ticks by the contract's volume in the 5
  minutes before, doubled for stops). Real dated charges. 1 lot.
- Expiry days are found from the data (ATM straddle time value < 0.6% of spot at 15:15 and the next session > 2x).
  Square-off 15:10 (15:05 on expiry day) unless a rule has its own.
- No look-ahead: every signal uses bars completed before the fill.

## 3. Gates (all must hold; BH over every variant in this study)

final-test net > 0; random-entry twin p < 0.05 (500 twins: same days, same number of entries a day and side, uniform
entry minutes in the rule's own entry window, same strike rule and option exits); BH q <= 0.10 across every final-test
p in this study (originals and re-tunes; reference baselines excluded); 40+ final-test trades (else "too few trades
to judge"); net without its top 5 trades > 0; design walk-forward net > 0 (an original rule has no fitted setting,
so its walk-forward net is its design net; a re-tune's is the sum of its out-of-fold quarters, below).

## 4. Re-tune protocol (small, pre-registered, design only)

Each original rule is tested first, unchanged. Then one **re-tune** per rule: a small grid fixed here. Walk-forward on
design: folds Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026; in each fold the grid point with the best net on
all design days before the fold (at least 20 trades; else the original) is traded in the fold. The re-tune's final-test
setting is the grid point with the best whole-design net (at least 30 trades). Only that one setting is read in the
final test.

## 5. Strategies (signal source stated for each)

Priority as asked: the app's paused arms and the strategies Boss named first, then the factory / ribbon / NN families.

### A. App arms (paused, or rejected add-ons to an arm)
1. **LIQ-FIN: Liquidity 15+5 FINNIFTY** (parked 9 Oct). Signal: the FINNIFTY index's 30-minute and 5-minute bars
   (pool-on-swing break, pivot 20, 2 contacts 5 bars apart, 10-bar confirmation, room >= 1 index stop). Buys 1-ITM.
   Exits: -15% premium stop, index stop 15 points, 20-minute +5% time stop, next-level target, failed break, new
   level, 15:10; expiry days skipped; 10 sessions of history for the levels (h4/comps.py logic). Entry 09:20-14:00.
   Re-tune grid: books {30+5 (original), 15+5, 30 only, 5 only} x room {1, 2 index stops}.
2. **G7: GEX filter on Liquidity BANKNIFTY 15+5** (R28, rejected): skip the trade when the normalised net GEX at the
   signal minute is > +1 (brain GEX). Compared with the unfiltered arm (a reference, not gated). Tested only if the
   brain GEX feature can be built for the window; else reported as not testable.
3. **NIGHT holds (R27, rejected)**: the Night (R3) entry unchanged (index close location, 59-stock breadth, 15:19 OI
   build-up z >= 0.5 on ATM+-2 of the nearest series; NIFTY weekly + BANKNIFTY monthly, 1-ITM, 15:20 buy). Signal
   source: the index (location, move), stock breadth and option-chain OI as a filter. B0 = sell at the next 09:16
   open (the arm itself: reference, not gated). V1-V8 exactly as R27 (index +-20 / +-30 scaled and raw, option +-Rs
   20 / 30, H2, Boss's Rs 1,000 / 500 floor), each with the 10:30 cap. Re-tune grid: option target = stop in {Rs 10,
   15, 20, 30, 40} a unit, 10:30 cap.
4. **G10: GEX filter on Night** (R28): keep only nights with S1 net GEX < 0 at 15:15 (if buildable).
5. **R41 arm filters (R9 Liquidity, R11 Night)**: modelled only if the arm has at least 300 design trades (R41's own
   rule); else "too few design trades to model".
6. **M4 SILVERM morning call** (R42; switched off 14 Oct). Signal: the clock (15-minute grid 09:15-13:45, while 11-20
   MCX trading days are left), strike from the SILVERM future: the call two listed strikes above ATM. Next-minute
   fill, exit 240 minutes later (or 10 minutes before the close), no stop, one position at a time, 1 lot (5 units),
   MCX charges + the liq spread model. Re-tune grid: exit {120, 240} minutes x strike {ATM, OTM2}.

### B. Named by Boss
7. **R44: Boss's 7 sweep strategies** (signal: 5-minute index bars; ATM option): EMA8/21+ADX15, the same with the
   stepped lock, EMA9/21+ADX20, EMA9/26+ADX20, RSI fade 30/70, RSI momentum 55/45, Donchian 20. R44's exits (stop
   -25%, target +50%, 60 minutes, square-off 15:15, max 2 a day a side, expiry days skipped), all 5 indices pooled.
   Re-tune grid: exits {(-25%, +50%, 60 min), (-15%, +30%, 30 min), (-35%, +100%, 120 min)} x strike {ATM, 1-ITM}.
8. **R45 NIFTY premium mean-reversion scalp** - signal source: **the option's own price chart (NOT the default;
   reported separately; only Boss can switch such a rule on)**. F-a: signal on the 5-minute rolling ATM call's
   Bollinger(20, 2) lower-band cross, trade priced on the strike that was ATM at the entry bar; F-b: the 09:15 ATM
   strike for signal and trade. R45's FIX rules (max 2 a day, -10% stop, stepped lock, 15-minute hold, expiry days
   skipped, 15:15 square-off), real charges + the liq model. Re-tune grid (F-a): hold {15, 30} minutes x stop {10%,
   20%}.

### C. Families
9. **F1 factory, 20 rules** (signal: index / brain features on the underlying; option-chain features as filters):
   C1, C2, S1-S18 exactly as `hunt/f1/results/rules.json` (top / bottom quintile against an expanding reference of
   that index's earlier window rows; the final test uses all design rows), 1-ITM, F1's x1 exit (-15%, +Rs 1,000 keep
   500 trail 500, 30-minute time stop unless +5%), first fill per side per index a day, decisions every 5 minutes
   09:30-14:30. Re-tune grid: threshold {quintile, decile} x exit {x1, x2 = no time stop}. Needs the brain feature
   store for the window (built from the lake); MIDCPNIFTY is not in F1.
10. **R29 EMA ribbons** (signal: index ribbons on 1/3/5/15-minute bars): B0 (EMA 9x21 on 5-minute, follow), Boss's
    three ideas (P9_5 15-min stack + 5-min pullback; squeeze then fan-out P11_5 and P5_15; MTF alignment P8_1), R1-R10
    (fades), 5 indices pooled, ATM, X1 (-30%, +Rs 1,000 keep 500, 60 minutes, square-off 15:20 / 15:05), max 3 a day,
    one at a time. Re-tune grid: exit {X1, X1 with 30 minutes, -20% / +40% / 60 min} x strike {ATM, 1-ITM}.
11. **R41 neural-net rules** (signal: index minutes + brain features): R1-R6 (direction 15/30/60 minutes, top 10%
    confidence; R4-R6 also need a size forecast >= its median), R13 next-morning gap (1-ITM at 15:20, sold 09:16, top
    20% confidence, non-expiry days) and the "always the call" baseline on all non-expiry nights. **Deviation stated
    now:** torch is not available, so the network is scikit-learn's MLP (64-32, 3 seeds averaged) on the brain
    features plus a 60-minute summary of the minute bars, trained walk-forward on design folds (21 Nov 2024 on only)
    and, for the final test, on all design. Re-tune grid: confidence {top 10%, top 5%} x stop {-30% close stop, -15%}.
12. **B2 all-at-once rules** (signal: index / brain features): G15, G30, G60, OPT with a gradient-boosted model
    (B2's settings) on the brain's base features (B2 found the 261 extra sweep inputs added at most 0.005 AUC; they
    are not rebuilt - stated deviation), trained walk-forward like R41; ATM, x1. Re-tune grid: threshold {90th, 95th
    percentile} x exit {x1, x2}.

If a family's inputs cannot be built for the window, it is reported as **not testable**, never as a pass or a fail.

## 6. Reporting

Per strategy: OLD verdict (full data) vs NOW (design) vs FINAL TEST: trades, win rate, net, net/day (net over the
sessions of the period), average win / loss, worst day, max drawdown, p, q, pass/fail. Experiment entries
`research/experiments/entries/r51-*.yaml`; a FINDINGS.md section; the end of the write-up lists which strategies (exact
settings) would come back on PAPER under the Nov-2024+ view and which stay off. A pass would be a candidate for paper
only (Boss decides; paper changes never carry over to live by themselves).

## Amendment A (15 Oct 2026, after the one final read): foreign-rows and stray-print re-pricing

Foreign-rows and stray-print re-pricing of the passing rule and the near-misses; same rules, same spans, same gates;
the final test is re-priced, not re-selected.

Why: R47 (branch integ-1014c, `research/hunt/r47/r47data.py foreign_rows`) found that the lake's rolling option files
carry rows of ANOTHER underlying (their own `spot` is far from the index at that minute), and added a stray-print guard
(`r47price.py`). R51 applied neither. Written before any cleaned number exists.

- **Cleaning** (`research/hunt/lakeclean.py`, the same code for R51 and R52):
  1. *Foreign rows* (R47's rule): an option row is dropped when its own `spot` is more than 3% from the index close at
     that minute (the session's last earlier close if that minute has none; dropped if the session has neither).
  2. *Stray prints* (R47's thresholds), per contract (side, strike) and session, on the rows left after step 1: ref = the
     median of the closes of the row before, the row and the row after (its own close at the ends). A row whose open is
     more than 2 ticks AND more than 20% away from ref is dropped. A high more than 20% above ref is capped at ref x 1.2.
     Lows are left alone (a stray low can only hurt a buyer). Difference from R47: R47 fills at the worse of the open
     and ref; here the row goes, so the engine's existing next-minute rule fills at the next traded minute.
  3. The ATM strike of each minute comes from the rows after step 1 and before step 2 (a stray price does not change a
     strike's label).
- **What is re-priced:** the passing rule R44-donchian20-RT, and every gated variant that failed with a final q <= 0.20
  on index options (26 variants, listed by `amend.py targets`). M4-SILVERM (q 0.141) is an MCX contract with no index to
  check against and keeps its original numbers.
- **Same rules and spans:** design 20 Nov 2024 - 31 Mar 2026, final test 1 Apr - 13 Oct 2026; index warm-up as before.
  Only lake files for 2024 onwards are copied; no option row before 20 Nov 2024 is used.
- **Design:** each re-priced variant is re-run on the cleaned design data (re-tunes: the whole grid, so the walk-forward
  gate is re-computed the same way). **Final:** the ORIGINAL frozen config of each variant (`final.json` config), never
  re-picked; if the cleaned design would pick another grid point, that is only reported.
- **Twins and BH:** the same random-entry twins (500, seed 51). BH over the same 125 gated variants, with the re-priced p
  for the re-priced variants and the original final p for the rest. Gates unchanged.
- **Also reported (information, no gate):** drop counts per index; how many of the passing rule's ORIGINAL trades
  touched a dropped or capped row (entry, exit or any minute between, or a contract that existed only in foreign rows);
  the passing rule at twice the spread and slippage (bps and tick half-spreads doubled, charges unchanged).
- **Read once:** `amend.py run` writes `results/amend_clean.json` and refuses if it exists or if this amendment is not
  committed unchanged.

**Note to Amendment A (15 Oct 2026, before the cleaned read).** The Drive lake's option files were re-fetched from Dhan
and re-uploaded at 04:25-04:47 UTC on 15 Oct, after R51's final read (04:06 UTC). On the current lake the ORIGINAL code
reproduces R44-donchian20-RT's design exactly (4,517 trades, +Rs 33,681) but not its final test (now 1,787 trades,
+Rs 66,047, against the committed 1,775 and +Rs 48,689; same 133 sessions). So the write-up reports three numbers: the
committed result, the original rules on the current lake (raw), and on the current lake cleaned. The share of profit due
to foreign or stray rows is measured as raw-now minus cleaned-now (same lake version). Gates are judged on cleaned-now,
as registered. (R52 reproduces exactly on the current lake: H37-10:g17 294 trades, +Rs 48,772.)

**Second note (15 Oct 2026, before the cleaned read).** The first cleaned build showed that dropping stray-open rows
hid 15:15 straddle prices and so un-flagged expiry days (NIFTY 99 -> 71), which would change which days a rule skips: a
re-selection, not a re-pricing. The expiry test now reads its 15:15 straddle from the same rows as the ATM label (after
the foreign step, before the stray step), as point 3 already does for the ATM strike. Result: the cleaned stores carry
exactly the original expiry days for every index; the ATM label changes on 22 FINNIFTY days (foreign rows) only.

**Third note (15 Oct 2026, before any number of these variants was seen).** The 10 near-misses of the F1, R41 and B2
families (F1-C1, F1-C1-RT, F1-S7, F1-S7-RT, R41-R1, R41-R2, R41-R2-RT, R41-R5, R41-R5-RT, B2-G60-RT) read the brain
feature store, which R51 built from the lake from June 2023 and which is itself computed from the uncleaned option
chain. It is not in this session, and rebuilding it needs option data before this amendment's window. They are not
re-priced and keep their original p in the BH. The re-priced set is the passing rule and 16 near-misses (the R44 and R29
families). R29 reads index bars from 2023 for warm-up only (as in R51); on the current lake its design reproduces
exactly (R29-R3: 200 trades, -Rs 5,826; R29-R8: 987, -Rs 188,223).
