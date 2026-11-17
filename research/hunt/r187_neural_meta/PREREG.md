# R187 pre-registration: one multi-layer neural network over the lake, used to skip trades and pick exits on the ORB family, Liquidity, R51 and R72

Written 16 Nov 2026, **before any R187 model, feature value or result number was computed**; committed and pushed on
`claude/r187-neural-meta` before the code's first run on data. Research only: nothing in `android/**` changes. Owner: Boss
(they/them).

Boss, 16 Nov 2026 (verbatim): *"1st create a neuro network of multiple layers with all the data we have in data lake for all
the index, then i want you to use all the study till now, and check all the combinations with it and find if it will be
enhance and make the orb family and other stratergies more profitable with more winrate And less loss"*.

**Signal source: the index.** Every arm's entries stay exactly as today (index signals). The network only (a) skips an
entry the arm would take (a skip, as R151's picker) or (b) chooses among exit rules already studied on the option premium.
Option-chain data (OI, IV, PCR, option volume) enters the network only as context around the index signal.

## 0. What was looked at before this file (no outcome)

- The lake's file lists (STATUS.txt, MANIFEST.txt on the Drive; the local copies), the R51 store's date span (first and
  last day only), the structure (field names) of R72 / Liquidity candidates and priced trades on 1-10 Sep 2025 (no net,
  no count of wins was printed), the options file schema.
- Code and published reports of R3, R144b, R151, R152, R169, R176, R177, R179, R183, R184, R185, R186 (their own results,
  not R187 outcomes).
- Nothing else. No R187 feature, score or trade outcome exists yet.

## 1. Inventory (fixed now)

### 1a. Lake datasets (gdrive:IraAlgo-data-lake; IST)

| dataset | span | used here |
|---|---|---|
| index_1m: NIFTY | Apr 2017 - 19 Oct 2026 (+ fill to 13 Nov) | yes (features; 2017-2020 only as warm-up, no arm trade before Aug 2021) |
| index_1m: BANKNIFTY, FINNIFTY, SENSEX, BANKEX, INDIAVIX | Aug 2021 - 19 Oct 2026 (+ fill to 13 Nov) | yes (cross-index context, VIX) |
| index_1m: MIDCPNIFTY | Jan 2022 - 19 Oct 2026 (+ fill) | yes |
| options_rolling_1m index WEEK (ATM-10..+10, O H L C, IV, volume, OI) | NIFTY / BANKNIFTY / FINNIFTY Aug 2021, MIDCPNIFTY 2023, SENSEX 2023 - 19 Oct 2026 (+ gap1113 to 13 Nov) | yes: as context features (2024-2026 for all five; 2021-2023 BANKNIFTY for the ORB rows) and through the arms' own stores |
| fut_1m_live_index | live contracts only (from Jul 2026) | no: no history before Jul 2026 |
| index_daily, fut_daily_continuous | long | no (the 1-minute files cover what is needed) |
| nse_bhavcopy | a validation sample | no |
| 1-second order flow (R184) | 20 days, Oct - Nov 2026 | no: 20 days cannot train or test a model over 2021-2026 |

### 1b. Arms and simulators

| arm | simulator (reused, not rebuilt) | span with trades | in R187 |
|---|---|---|---|
| R72 Breadth Donchian (app: V5 = R151 picker 0.4656 + own stop + breakeven at +10% + L500) | R176 / R179 / R186 engine on R51's clean stores (R106 `clean_post`, built to 19 Oct 2026; R186's extended copy to 13 Nov) | 20 Nov 2024 - 13 Nov 2026 | yes |
| R51 Donchian 20 + Supertrend (app: V5 as R72, picker 0.4469) | same | same | yes |
| Liquidity 15+5 (app: own exits + breakeven at +10% + LBE1000, no picker) | same | same | yes |
| ORB, ORB Fresh, ORB Sweep (BANKNIFTY, parked since 13 Oct) | R3 engine through R169's `r169lib` (R169's B0: dated charges, R17 fills) on R3's session store, rebuilt here from the lake (Aug 2021 - 13 Nov 2026) | Aug 2021 - 13 Nov 2026 | yes, if the store rebuild reproduces R144b's design parity (section 6); otherwise reported as not simulated |
| Range Fade (parked) | R3's engine only (no dated-charge B0, no exit menu in R169) | - | no: R3 found it loses in every year 2021-2026; adding it needs a new B0 and exit menu (not cheap and faithful) |
| S5 Trail (R128), CISD S3-V2-E3 (R133) | their own engines, both driven by R123's N1 network predictions (derived/r123 on the Drive, not local; they end Oct 2026, so no gap month) | - | no: out of this study's disk (3.3 GB free) and time budget, and the exit menu of section 3 was never built for them |
| FT3, Night, VIX divergence, Hero | their own engines (R144c, R147-R149, R74, R98) with different instruments, sessions or holding (overnight) | - | no: same reason; their exits are not the menu of section 3 |

ORB Fresh takes exactly ORB's trades on BANKNIFTY with the 1-a-side cap (R144b, R169). It is reported and counted as its own
arm; in the network it shares ORB's arm identity (its rows would be duplicates).

### 1c. Earlier studies' features and rules used

| study | used as |
|---|---|
| R151 pickers (R72 / R51) | their 28 features (R151 `study.features`, wall at the decision minute) and the app's picker score as inputs; the picker itself stays in the base |
| R150 support / resistance, R101 context (f_*) | inside R151's 28 features |
| R183 sideways | ADX(14), efficiency ratio (12), choppiness (14) on 5-minute bars, range so far / ADR20, as inputs |
| R144c FT3 / Night | the side's ATM option price change and OI change over 15 minutes (FT3's "both fell"), the underlying (monthly-expiry indices), VIX change today (Night's VIX rule) as inputs |
| R169 ORB features | its 11 entry features (VIX, gap / ATR, range width / ATR, ...) as inputs for ORB rows |
| R162 / R176 - R186 exits; R131 index stop | the exit menu of section 3 (breakeven +10%, L500, LBE1000, P60, IDX index lock, T1000, T1500, own exits); R169's X1-X6 for the ORB family |
| R123 N1 network | not used: predictions are not local, cover only to Oct 2026 and said "go" on about 2% of moments (R169) |
| R184 / R185 absorption, exhaustion | not used: R184's flow covers 20 days; R185's flow is estimated from price bars (already in the price features) |

## 2. The network

**Inputs** (all known at the decision minute; for R72 / R51 / Liquidity the decision minute is `er - 1` (the entry fills at
the open of row `er`); for the ORB family it is the signal bar's last row `sig_row` (the entry fills at `sig_row + 1`).
Nothing from a later row is read; minute rows are the grid 09:15 + t):

1. Traded index (from the lake's 1-minute bars): signed (by the trade's side) returns over 5, 15, 30, 60 minutes; move from
   today's open; move from the previous close; today's gap; range so far / ADR20; close position in today's range; distance
   to the previous day's high / low / ATR14; 30-minute realised volatility; ADX(14), ER(12), CHOP(14) on 5-minute bars (the
   previous 3 sessions plus today's complete bars); time of day (row); weekday; days to expiry and expiry day (from the
   arm's store); the side.
2. Cross-index: for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX: signed move from the previous close and signed
   15-minute return; the share of the six up on the day. India VIX: level, log change from the previous close, 15-minute
   log change.
3. Option-chain context (traded index, the lake's rolling ATM-10..+10 at the decision minute): ATM IV (mean of call and
   put), put-call IV skew (put at ATM-2 minus call at ATM+2), PCR of OI over the 21 strikes, 15-minute log change of total
   call OI and of total put OI, the side's ATM premium and OI 15-minute log changes, the side's ATM volume over the last 15
   minutes / the 60 before.
4. Studies' blocks: R151's 28 features and the app picker score (R72 / R51 rows; Liquidity rows get the 28 features, no
   score); R169's 11 features (ORB rows). Not-applicable values are imputed with the training median (the arm identity
   tells the network which block is real).
5. Identity: the arm (R72, R51, LIQ, ORB [ORB and ORB Fresh], SWEEP) and the underlying, one-hot; the exit rule (section 3),
   one-hot.

**Rows (stacked):** one row per trade x exit rule of its arm's menu. Training trades: every trade the arm's base walk takes
(R72 / R51 before the picker's post-walk skip; Liquidity; ORB / Sweep B0), each priced under every exit of its menu (an exit
is priced on the same entry fill; the walk is not re-run for the labels).

**Two networks** (sklearn, CPU; torch is not installed):
- `MLPClassifier`: P(net after charges > 0 | inputs, exit);
- `MLPRegressor`: expected net (Rs / 1,000, winsorised at the training window's 1st / 99th percentile) | inputs, exit.
- Both: ReLU, Adam (learning rate 0.001), batch 256, up to 200 epochs, early stopping (10% of the training rows, 10 epochs
  without improvement); inputs median-imputed and standardised on the training rows only.
- **Hyperparameters, chosen inside each training window only:** hidden layers (64, 32, 16) or (128, 64, 32), L2 alpha
  0.001 or 0.01 (4 combinations), by the inner validation loss (log-loss / squared error) of a model trained on the
  training window minus its last quarter and scored on that last quarter.
- The chosen setting is refitted on the whole training window with seeds 187, 188, 189; the three are averaged.

**Walk-forward (expanding, refit every quarter, then frozen):**

| fold | scored (test) | trained on trades before | inner validation |
|---|---|---|---|
| F1 | 1 Apr - 30 Jun 2025 | 1 Apr 2025 | 1 Jan - 31 Mar 2025 |
| F2 | 1 Jul - 30 Sep 2025 | 1 Jul 2025 | 1 Apr - 30 Jun 2025 |
| F3 | 1 Oct - 31 Dec 2025 | 1 Oct 2025 | 1 Jul - 30 Sep 2025 |
| F4 | 1 Jan - 31 Mar 2026 | 1 Jan 2026 | 1 Oct - 31 Dec 2025 |
| F5 | 1 Apr - 13 Nov 2026 (final, post, new month) | 1 Apr 2026 (frozen) | 1 Jan - 31 Mar 2026 |

Training starts: ORB rows Aug 2021; R72 / R51 / Liquidity rows 20 Nov 2024 (the stores' first usable day). Every trade is
intraday, so "before the fold" means a trade day before the fold's first day. Every score in the year (20 Oct 2025 - 19 Oct
2026) is out of sample.

## 3. The uses (fixed now; per arm; each arm x use is one test)

Exit menus (all on the premium; whichever exit comes first wins inside each rule):
- **R72, R51, Liquidity** (R186 engine): V5 (the base: own + breakeven +10% + L500 on R72 / R51, LBE1000 on Liquidity); V4
  (own + breakeven, no step lock); A (own exits only); the other lock (LBE1000 on R72 / R51, L500 on Liquidity); P60; V5 +
  IDX index lock; V5 + T1000; V5 + T1500. Eight rules.
- **ORB, ORB Fresh, ORB Sweep** (R169's engine): B0 (the base); B0 + X1 (BE10), X2 (T40), X3 (T20), X4 (G3), X5 (SRT),
  X6 (FAIL). Seven rules.

Uses:
- **U1 skip:** skip a base trade whose P(win | inputs, base exit) < theta. A skipped trade still holds its side and counts in
  the caps (the app's rule: removed from the record after the walk). theta per arm and fold, from {0.300, 0.325, ..., 0.700},
  chosen on the inner validation quarter (the inner model's scores): the theta with the largest validation net among those
  keeping >= 40% of the validation trades with a kept win rate above the validation base's; if none beats the validation
  base's net, no skip (U1 = base in that fold).
- **U2 exit choice:** for each candidate, the exit with the highest expected net; used only when it beats the base exit's
  expected net by more than delta, delta per arm and fold from {0, 100, 250, 500 Rs, never}, chosen on the inner validation
  quarter by the validation net (each validation trade at its chosen exit's own net; no re-walk). Evaluation re-walks the
  arm with each candidate's chosen exit (a different exit can free or block later candidates, as in the app); R72 / R51 then
  apply R151's post-walk keep (V5).
- **U3 = U2 then U1:** U2's walk, then skip trades with P(win | inputs, the chosen exit) < theta3 (chosen as U1's theta, on
  the validation trades at their chosen exits).
- **U4 network picker (R72, R51 only):** the walk without R151's picker; skip trades with P(win | inputs, V5) < theta4
  (grid as U1, keeping >= 25% of the validation trades, best validation net). Compared with V5 (which has R151's picker).

**Tests:** U1, U2, U3 x 6 arms (R72, R51, LIQ, ORB, ORB Fresh, ORB Sweep) = 18, + U4 x 2 = **20**. If the ORB store fails
parity, the 9 ORB tests are not run but stay in the count. **BH m = 4,882 (after R186 Amendment C) + 20 = 4,902.**

## 4. Periods

- Design / walk-forward: 20 Nov 2024 (Aug 2021 for ORB rows) - 31 Mar 2026; folds F1-F4 score Apr 2025 - Mar 2026.
- **Year: 20 Oct 2025 - 19 Oct 2026**; early 20 Oct 2025 - 31 Mar 2026; **final 1 Apr - 13 Oct 2026**; last 6 months
  20 Apr - 19 Oct 2026. The final is read ONCE for R187 (`results/FINAL_READ` written before), with the code committed
  first.
- **Contamination, plainly:** this final has been read by R151 - R186 many times for these arms, and the V5 base itself
  was chosen on it (R179 / R186). A pass here is a second look, not fresh evidence.
- **New month: 21 Oct - 13 Nov 2026** (18 sessions + the Muhurat session of 8 Nov), from the lake's gap / fill files (R186's
  extended stores for R72 / R51 / Liquidity; the rebuilt R3 store for ORB). Never used to choose anything in R187; R186
  already read the base arms' trades on it (descriptive counts) and Boss watched it live. Reported descriptively only
  (about 100-300 trades per arm at most; small).

## 5. Gates (per arm x use, 1 lot; all must hold; never relaxed)

1. Net >= the base's net, in the year AND the final.
2. Win rate > the base's, in the year AND the final.
3. Max drawdown smaller than the base's OR worst month better than the base's, in the year AND the final.
4. Net > 0 in early, final, last 6 months and the year.
5. Random twin (500, seed 187) over the year, p = (1 + #twins >= the use's net) / 501, **BH q <= 0.10 with m = 4,902**:
   U1 / U4: skip the same number of trades at random from the same walk's trades; U2: each trade whose exit was switched
   gets a uniformly random other exit of the menu (the trade set held fixed); U3: U2's twin, then U1's random skip of the
   same count.
6. >= 40 final trades.

The base is each arm as the app runs it today (R72 / R51 V5 with R151's picker; Liquidity with breakeven and LBE1000; ORB
family R169's B0). Reported for every arm x use and period: trades, win rate, net, average win, average loss, worst month,
max drawdown; the account stop rule (max drawdown <= Rs 40,000 and worst month >= -Rs 13,000) is reported, not gated. 1 lot
only (3 lots are not run: compute).

## 6. Parity (before anything is read)

- R72 / R51 / Liquidity V5 at 1 lot on the year reproduce R186 Amendment C's published V5: R72 1,097 trades / Rs 2,13,110,
  R51 1,430 / Rs 1,62,959, Liquidity 688 / Rs 98,239 (trades exact, net within Rs 1). Checked in the final run, first.
- ORB store: R3 engine with `Costs('app')` on 20 Nov 2024 - 31 Mar 2026 reproduces R144b's design figures (ORB 320 / -Rs
  41,427; ORB Fresh 320 / -Rs 41,427; ORB Sweep 454 / -Rs 1,12,523), and B0 (dated) on the WF year reproduces R169's
  (ORB 231 / -Rs 25,965; Sweep 326 / -Rs 98,449). If not, the ORB family is reported as not simulated.
- R186's engine is copied unchanged from `claude/r186-fixed-profit` (91f895e49), which is not on main.

## 7. Honesty and verdicts

- Nothing about the network, features, grids, uses or gates changes after any walk-forward number is seen. A bug fix is
  allowed and disclosed in the report.
- A pass is a second look on a contaminated final: at most a SHADOW log (CLAUDE.md: anything new that could skip or resize
  live trades starts in shadow, behind a switch off by default); nothing in the app changes in R187.
- Every arm x use is logged in `research/experiments/entries/` (r187-*.yaml), failures included.

## 8. Tests

pytest: each new feature's look-ahead guard (junk after the decision row changes nothing); the fold builder (no training
trade on or after the fold's first day; the inner validation strictly before the fold); scaling / imputation fitted on
training rows only; theta / delta choice on synthetic scores; the U2 exit composition; the gates; the twins; the BH count.

## Amendment A (16 Nov 2026, a bug fix, before any use or trade result was computed)

**What was seen:** the first `train` run printed only the inner-validation losses of the hyperparameter choice (no use,
no trade outcome, no theta / delta result was looked at beyond the first fold's printed choices). The classifier's
inner-validation log-loss was 3.2 - 4.6 in every fold (a constant guess scores about 0.69; a plain logistic regression on
the same rows of F1 scored 0.85), with a training log-loss of 0.04: the networks memorised.

**Cause (a bug):** sklearn's built-in early stopping holds out a RANDOM 10% of the stacked rows. A trade appears once per
exit rule (7-8 rows with almost the same label), so its rows sat on both sides of that split and the stopping loss kept
falling while the network memorised trades. Early stopping, as pre-registered, never acted.

**Fix (no setting changed):** the same early stopping, implemented by hand: Adam one epoch at a time (`partial_fit`, the
pre-registered learning rate, batch, alpha and layers), stopping after 10 epochs without improvement (at most 200) on the
LATEST 10% of the training TRADES by date (all their stacked rows), keeping the best epoch. The grids, the uses, the
gates and every other line of this file are unchanged. The first run's models were discarded.

**Disclosed:** after the fix, F1's inner-validation log-loss of the chosen classifier is about 0.91 (F1's training is
mostly ORB rows from 2021-2024, its validation mostly R72 / R51 rows from Jan - Mar 2025 with a different win rate). That
is worse than a constant guess: an early sign the network finds little. Nothing is re-tuned for it.
