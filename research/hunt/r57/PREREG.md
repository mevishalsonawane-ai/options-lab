# R57 pre-registration: "Solo 2.0" - can a trained Solo pick its own trades better than plain SoloMidday?

Written 15 Oct 2026, **before any R57 profit, loss or model score was computed**. Commit this file first; results come
after. Research only: nothing here changes the app (no `android/**`, no `.github/**`, no knowledge-pack rebuild).

Boss, 15 Oct: "Now we have all data, can you train the solo options, and make it more powerful, smart to take the trades
on his own." Solo is Jarvis's own trader and stays **paper only, forever** (CLAUDE.md). Today it runs SoloMidday
(`android/engine/.../orb/SoloMidday.kt`): unseen record +Rs 4k on 178 trades, PF 1.02, not proven.

Lessons carried in: labels on a ROLLING ATM series are fake (R45, R46) - every label and every P&L here is on the HELD
contract; foreign option rows are dropped (R47); one-minute bad ticks are removed (lake `spikes.py`); same-side random
twins leak the day's direction to breakout rules (R52), so the twin draws the side by coin flip.

## 1. Data, window and split

- Drive lake `gdrive:IraAlgo-data-lake`: `index_1m` (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, INDIAVIX), and
  `options_rolling_1m` **WEEK** files only (ATM-10..+10 with OI, IV, volume, spot). For BANKNIFTY, FINNIFTY and
  MIDCPNIFTY the WEEK and MONTH files are the same contract since 20 Nov 2024 (CATALOG `same_contract_as`): MONTH is
  never read, so nothing is counted twice. Raw data and caches stay outside git (`~/.cache/iraalgo`) and are deleted at
  the end.
- **Indices: the four NSE F&O indices** NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY. SENSEX is left out: the app's paper
  account is NSE F&O only, so Solo could never trade it (as SoloMidday.kt says).
- Trades only from **20 Nov 2024** (first session 21 Nov). Index minutes from 1 Sep 2024 are read only to warm up
  indicators (ATR14, Donchian, the 10-day range); nothing before 20 Nov 2024 is traded, labelled or trained on.
- **DESIGN: 21 Nov 2024 - 31 Mar 2026**, purged walk-forward. **FINAL TEST: 1 Apr 2026 - the lake's last session
  (14 Oct 2026 NIFTY, 13 Oct the others), read ONCE** by `r57study.py final` (guard file `results/FINAL_READ`; the
  harness refuses to label or price a final-test session before that).
- Honest note: earlier studies (R47-R55) already looked at parts of Apr-Oct 2026, so the final test is not pristine.
  Only live paper from 15 Oct 2026 is truly unseen.

## 2. Measurement (never relaxed)

- **One held contract per trade**: the strike bought at entry, rebuilt from every offset row that carried it
  (R51's day store, `hunt/r51/r51data.py` logic). Never the rolling ATM series.
- **Cleaning**: option rows whose `spot` is more than 3% off the index close at that minute are dropped (R47
  `foreign_rows`); a contract-minute whose close is more than 50% away from both neighbouring closes that revert
  (lake `spikes.py` definition) is removed (its O/H/L/C set missing); rows with |strike - spot| > 12 steps dropped (R25).
- **Fills** (R51 engine, `hunt/r51/engine.py`, unchanged): a signal on a bar's close fills at the next minute's open
  (up to 2 minutes later, else no trade); R17 "liq" fills (+-5 bps, half-spread 1/2/4 ticks by the contract's volume in
  the 5 minutes before, doubled for stops); resting stops on the minute low (a gap through fills at the open); the stop
  wins a tie; locks act from the next minute. **Dated charges** (`obuy.costs.Costs('dated')`, STT 0.15% from 1 Apr
  2026). **Lots in force** on each date (`r51cfg.LOTS`). 1 lot.
- Index-level exits (index stop, breakeven lock): decided on a 1-minute index close (stop) or high/low (lock), the
  option sold at the next minute's open.
- **2x costs** (reported, not gated): charges and fill slippage (spread + bps) both doubled.
- Expiry days (R51 straddle rule): **Solo never trades an expiry day** (as SoloMidday: the nearest expiry, never
  today). Every candidate and decision on an expiry day of its index is dropped.

## 3. Candidate setups for the meta-labeller (family A) - signal source stated for each

Every setup's signal is read from the **underlying index** (1-minute bars aggregated as stated), except C4 (option
chain, non-default). Strike from the index price at the signal minute (ATM = the rolling offset-0 strike of that
minute). Each candidate carries a stop and a profit lock (Solo always has both); a rule that had none gets the **Solo
safety layer: -30% premium stop (resting) and once +40% is reached, +10% is locked**.

| id | setup (as in its study) | signal source | strike | exits |
|---|---|---|---|---|
| S1 | **SoloMidday** (SoloMidday.kt exactly): at 12:00, `abs(c - o) >= 0.5 ATR14` (daily, 14 completed days), close in the outer 25% of the 09:15-11:59 range; entry 12:00 | index 1-min | 4-ITM | index stop 0.3 ATR14 on a 1-min close; breakeven once the index has gone 0.45 ATR its way; 14:30 out |
| S2 | **R51 Donchian-20-RT**: 5-min index bar closes beyond the 20-bar Donchian channel (R44 `ind.signals`, warm-up from 1 Sep 2024); entries 09:20-15:10; max 2 a day a side, one open a side | index 5-min | 1-ITM | -15% stop, +30% target (the profit taker), 30-minute time stop, 15:15 out |
| S3 | **R52 H37-10** "open outside value and go": previous day's 70% value area (30-min TPO, `h37/sigs.profile_levels`); the day opens above VAH and the first 5-min bar closes above VAH -> call; below VAL -> put; entry 09:20 | index 5-min + previous day's profile | 1-ITM | 60-minute time exit + Solo layer |
| S4 | **ORB 15-min**: range = 09:15-09:29 high/low; the first 5-min bar (09:30-13:55) closing beyond it -> that side; one a day per index | index 5-min | 1-ITM | -25% stop, +50% target, 60-minute time stop, + lock (+10% once +40%) |
| C4 | **R47 C4 BANKNIFTY PCR follow** (NON-DEFAULT: signal from the option chain, not the underlying; labelled and reported separately; only in variant A4): at 10:15, BANKNIFTY put/call OI (ATM-10..+10, nearest expiry) in its top fifth of the previous 60 sessions -> call; bottom fifth -> put | option chain (OI) | 3-ITM | -30% stop, lock +30% once +60%, 15:15 out |

Each candidate's **label** is its own held-contract net P&L (Rs, 1 lot, after real charges and fills); "win" = net > 0.
Candidates that cannot fill (no contract minute within 2 minutes) are dropped before training.

## 4. Features (known at the signal minute; the feature row is the bar that closes at the fill minute's open)

Index features reuse the **research/brain feature definitions** (`research.brain.features.registry.compute` on the
index minutes, look-ahead-guarded there): `ret_15m`, `ret_60m`, `ret_day`, `gap_pct`, `day_range_vs_adr10`,
`pos_in_day_range`, `rv_30m`, `er_day`, `dist_tpavg_pct`, `range_30m_pct`, `dist_prev_close_pct`, `or30_state`.
R57's own (point-in-time, unit-tested): `atr14d_pct` (daily ATR14 / close), `minute` (minutes since 09:15), `dte`
(sessions to the index's next expiry; the expiry calendar is known in advance), `vix` (India VIX last close at or
before the minute), `vix_chg_pct` (vs the previous session's last VIX close), and option-chain CONTEXT only:
`pcr_oi` (puts / calls OI, offsets -5..+5, latest minute at or before), `iv_atm` (mean of offset-0 call and put IV),
`iv_skew` (put IV at offset -3 minus call IV at offset +3). Index one-hots (4).

- Family A adds the setup one-hot (S1-S4, + C4 in A4) and makes the directional features **signed by the trade's side**
  (`ret_*`, `gap_pct`, `dist_*`, `or30_state` x side; `pos_in_day_range` -> distance from the side's own edge), plus
  `side`.
- Family B uses them unsigned, plus the decision-time one-hot.

## 5. Models (10 gated variants, listed in full; BH over all 10)

Common: training rows are design rows only until the final read. **Purged walk-forward with monthly refits**: the test
months are Jul 2025 - Mar 2026 (9 folds); each month's model is trained on every row whose session is before the
month, **purged** of rows whose label window could reach the month and with an **embargo of 1 session** (the last
session before the month is dropped from training). The design walk-forward net = the sum of the 9 out-of-fold months.
The **final test continues the same monthly refits** (Apr - Oct 2026: training on everything before each month,
minus the embargo; hyper-parameters, thresholds and rules frozen here), which is what the monthly retrain job would do.

Trees: scikit-learn `HistGradientBoosting` (max_depth 3, 150 trees, learning rate 0.05, min_samples_leaf 40,
l2 1.0, no early stopping, random_state 57). Logistic: median-imputed, standardised, L2 (C = 0.1).

### Family A - meta-labeller ("take or skip, and which index")
Candidates of the day are walked in time order; the **first** whose score passes the rule is taken (several at the same
minute: the highest score); then nothing more that day (**at most one trade a day**, MAX_OPEN 1).

| id | model | candidates | take rule |
|---|---|---|---|
| A1 | GBT classifier (win) | S1-S4 | score >= the 70th percentile of the model's scores on its own training rows |
| A2 | logistic (win) | S1-S4 | the same |
| A3 | GBT regressor (Huber) on net / premium outlay | S1-S4 | predicted net return > 0 (the label is after costs, so > 0 = the edge beats the cost) |
| A4 | GBT classifier (win), **non-default: includes C4** | S1-S4 + C4 | as A1 |

### Family B - direct direction model
At fixed decision times **09:45, 10:30, 12:00, 13:30** on each index (not on its expiry day), predict whether the index
is up over the next H minutes (label: index close at entry + H vs the entry minute's open; H = 30 or 60). Expected
edge in index points = `(2p - 1) x` the mean absolute H-minute move of that index at that decision time in the training
rows; option points = edge x delta (0.6 for 1-ITM, 0.5 for ATM). Trade only if option points x lot > the expected
round trip (charges + R17 spread and bps both ways at the decision-minute premium). Up -> call, down -> put; nearest
(non-expiring) weekly/monthly contract. At most one trade a day: at each decision time in order, the index with the
largest edge minus cost; then no more that day.
Exits (Solo always has a stop and a lock): **index stop 0.3 ATR14 on a 1-min close; option stop -25% (resting); lock
+10% once +30%; time exit at H minutes; square-off 15:10.**

| id | model | H | strike |
|---|---|---|---|
| B1 | GBT classifier | 30 | 1-ITM |
| B2 | GBT classifier | 60 | 1-ITM |
| B3 | logistic | 30 | 1-ITM |
| B4 | logistic | 60 | 1-ITM |
| B5 | GBT classifier | 30 | ATM |
| B6 | GBT classifier | 60 | ATM |

Reference (not gated, not in BH): **plain SoloMidday** as the app runs it (NIFTY, BANKNIFTY, FINNIFTY; strongest
first; 4-ITM; its index exits), and each setup S1-S4 (and C4) taken unfiltered.

## 6. Reports

- **AUC on held-contract labels**: family A score vs win, (i) random 5-fold split of the design rows (leaks: same-day
  and neighbouring rows on both sides), (ii) the purged walk-forward out-of-fold months, (iii) the final test.
  Family B: AUC of p(up) vs the index direction label, and on held-contract labels (call trade net > 0 against p;
  put trade net > 0 against 1 - p; both simulated with B's exits at every decision point), random split vs walk-forward.
- Per variant, design walk-forward and final: trades, win rate, net, net per trade, max drawdown, worst day, months
  green, top-5 share, at 1x and 2x costs, per index.
- **Random-entry twins** (500 per variant, seed 57): each trade is replaced by one on the same day and index, entry
  minute uniform in 09:20-14:00 (A) or 09:45-13:30 (B), side by coin flip, same strike rule and the same exits
  (index exits recomputed from the twin's entry). One-sided p = share of twins with total net >= the variant's.
  Also reported, not gated: for A a selection twin (same number of trades drawn at random from the day's candidates).
- **Benjamini-Hochberg** over the 10 final-test p values.
- Compared with plain SoloMidday on the same days.

## 7. Gates (all must hold; same as R47-R55)

Final-test net > 0 after costs; twin p < 0.05; BH q <= 0.10; 40+ final-test trades (else "too few to judge"); net
without its top 5 trades > 0 (top-5 < 100% of net); design walk-forward net > 0.

## 8. On-device and retraining (built whatever the result; only a pass would ever be ported)

- Export: the model as a small JSON (tree nodes: feature, threshold, missing-goes-left, children, leaf values; or
  logistic medians, means, scales, coefficients), the feature list with definitions, and the take rule.
- A pure-Python reference evaluator (no numpy) and golden test vectors (inputs -> score), parity-tested against the
  trained model, so a Kotlin port can be parity-tested later. No network or disk on the tick/order path: the phone
  scores from the JSON loaded at start-up.
- `r57retrain.py`: monthly, offline. Rebuilds labels on the new lake months, refits, and **replaces the deployed model
  only if** the new model's walk-forward over the latest 6 months passes: net > 0, twin p < 0.05, 40+ trades, net
  without its top 5 > 0, and the JSON parity check. Otherwise the old model stays.

## 9. Not changed after this commit

Splits, indices, setups and their exits, features, model settings, take rules, the 10 variants, twins, gates. Any bug
fix after results is written up as an amendment with its effect.
