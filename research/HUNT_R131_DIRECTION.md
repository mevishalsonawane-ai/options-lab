# R131: a better way to call the direction? Every dataset, nine horizons, "at least 60%"

Written 28 Oct 2026 for Boss ("find a better way to find a direction, check all the data we have, at least 60").
Research only: no app code, no orders.

- Pre-registration: `research/hunt/r131_direction/PREREG.md` (commit `7f39c58`, before any number). Amendment 1 (two
  tradeable twins, a stricter counting rule) was added after the walk-forward and BEFORE the holdout (`56d93ca`).
- Code and tests: `research/hunt/r131_direction/` (22 pytest tests, including the look-ahead guard).
- Results: `results/wf.json`, `results/holdout.json`, `results/report.md` (every table), `results/extra_gap_t.json`.
- Experiment log: `research/experiments/entries/r131-*.yaml` (4 entries).
- Rows, predictions and trades: Drive `derived/r131/` (never git).

## The answer first

**Intraday: no.** No way of calling the next 60 minutes, the day from 09:30, or the rest of the day from 11:00 or 13:00
reaches 60% out of sample, with any dataset. The best AUC is 0.52-0.54 (0.50 = coin). This matches R16 and R123-R126.

**Overnight: yes, one narrow thing, but it is not yet a money-maker.**

- **What it is:** "Will the index open tomorrow ABOVE where it trades at 14:55 today?" (GAP_T)
  - The models say "up" on only the ~10% of index-days they are surest about.
  - Signal source: the index itself (its day, its VIX and its recent days). Option-chain data was only optional
    context in some versions.
- **How often it was right, out of sample:**
  - Walk-forward 2023 to Mar 2026: **74-78% right** (463-528 calls; the plain "always up" rate was 61.9%). It held in
    every one of the 4 periods (61-84%).
  - Locked Apr-Oct 2026: **64-68% right** (49-79 calls; "always up" was 51.0%).
  - 14 versions pass every pre-registered gate (BH q <= 0.09).
- **Why it does not make money reliably** (ATM call, 1 lot, real charges):
  - The moves are small: median 0.33-0.39% of the index.
  - Most calls (70%) are on **MIDCPNIFTY**. Its midcap stocks trade less often, so the index lags and catches up at the
    next open.
  - More than half of the calls could not be traded. The option had no traded price at 14:54 or 09:15, or the weekly
    contract rolled overnight.
  - The profit depends on selling in the very first minute (09:15):
    - selling at 09:15: Rs +216 to +742 a trade on Apr-Oct 2026 (15-32 trades);
    - selling at 09:19, four minutes later: Rs -315 to -603 a trade.
  - Jan-Mar 2026 lost (Rs -1,116 to -1,734 a trade, 17-22 trades).
- **Verdict:** a real but thin effect. It is not a robust option-buying edge, and nothing goes to paper or live.

**Every other "60%" came from a measurement trap.** The overnight move measured from NSE's **official close** shows 60-79%
"accuracy" (25 versions passed every gate). But the official close is an average of the last 30 minutes. Nobody can
trade at it while also knowing it. Re-measured from a price you can actually trade at, only the 14:55 version above
survives. Next-day close-to-close was similar: one version passed (62% in both periods), but its tradeable twin failed
and the futures trade lost Rs -1,726 a trade on Apr-Oct 2026 (39 trades).

**New data sources added nothing that reaches 60%:**

- These added nothing overnight and at most +0.4 AUC points intraday: participants' positioning (FII/DII/Pro/Client),
  FPI flows, the futures basis/OI/rollover, the end-of-day chain, and the rupee, G-sec and MCX gold/silver/crude/copper.
- The intraday breadth of the 59 large stocks helped the most: +0.4 to +2.0 AUC points. That is still nowhere near 60%.

## 1. What was asked and how it was tested

The question: "Is there a way to call the direction (up/down) of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY or SENSEX
that is right at least 60% of the time out of sample, using every dataset we have?"

| id | decision | the move called | tradeable? |
|---|---|---|---|
| GAP | daily close of D | close D -> open D+1 | only approximately (official close, see section 5) |
| C2C | daily close of D | close D -> close D+1 | only approximately |
| PRE | pre-open of D+1 (incl. last evening's files) | close D -> open D+1 | no (informational) |
| DAY | 09:30 | 09:30 -> 15:30 | yes |
| R11 / R13 | 11:00 / 13:00 | -> 15:30 | yes |
| M60 | 10:00, 11:00, 12:00, 13:00, 14:00 | next 60 minutes | yes |
| GAP_T (Amendment 1) | 14:55 of D | 14:55 -> next open | yes |
| C2C_T (Amendment 1) | 14:55 of D | 14:55 -> next close | yes |

- **Signal source:** in every rule here it is the index (the underlying). Market-wide positioning, flows and chain
  state enter only as extra inputs.
- **Models:** logistic regression (LR), gradient-boosted trees (GBM) and a small neural net (MLP), all with fixed
  settings.
- **Input sets:** 8 sets, each the index/VIX history (IDX) alone or plus one source.
- **Selective calls:** the 100%, 50%, 20% and 10% most confident calls. The confidence cut-off came from the training
  rows only.
- **Baselines:** always the majority direction; follow the last move; fade it.
- **Walk-forward:** trained only on the past and tested on 2023, 2024, 2025 and Jan-15 Mar 2026. Then the holdout
  (Apr-Oct 2026) was read once.
  - That holdout has already been seen by R123-R130, so it is not virgin.
  - The read was resumed twice inside that one read, while I fixed the money check's speed. No holdout number was seen
    before the final run. `results/HOLDOUT_READ.json` records it.
- **Test:** each hit rate against the best constant call (base rate p0), with calls clustered by day (the 5 indices
  move together).
  - Benjamini-Hochberg was applied over all **891 tests**.
- **">= 60%" had to clear all of these:** n >= 100 calls, hit >= 60%, q <= 0.10, >= 60% in 3 of the 4 walk-forward
  years, >= 60% and above the base rate on the holdout, and a tradeable horizon.
  - A GAP/C2C pass counts only if its 14:55 twin passes too.

## 2. Data used: every source, span, and what it could know when

| source | span used | fields used | known |
|---|---|---|---|
| index_daily (5 indices + VIX) | 2006-01 -> 2026-10-19 (5,155 NIFTY days; MIDCPNIFTY from 2022-01, 1,170) | OHLC | at the close |
| NSE index files (participants/indices) | 2012-02 -> 2026-10-13 (3,599 days) | data check of closes; midcap-100, smallcap-100; NIFTY50-USD (rupee, from 2013-04); 10-yr G-sec (from 2015-11) | evening |
| participant-wise OI (participants/oi) | 2012-01 -> 2026-10-13 (3,640 of 3,644 files; 4 failed the totals check) | FII/DII/Pro/Client index futures long/short, index call/put long/short | evening |
| NSDL FPI flows | 2012-01 -> 2026-10-13 (3,560 days) | net equity flow | one more trading day later |
| index bhavcopy (participants/bhav_index) | 2012-01 -> 2026-10-13 (9,872 index-days, NSE indices only) | near-expiry PCR (OI, volume), max-OI strikes, ATM straddle, skew, futures rollover; actual expiry dates | evening |
| fut_daily_continuous | index futures 2009/2011 -> 2026-10-19; MCX to 2026-10-16 | basis, futures OI change; MCX GOLD/SILVER/CRUDEOIL/COPPER 1-day returns | close (basis) / evening (OI, MCX) |
| equity_daily (59 stocks) | 2002 -> 2026-10-12 | breadth: share up, mean, spread, share above 20-day average | at the close |
| stock_fut_daily_continuous | 2011-10 -> 2026-10-13 | long / short build-up share | evening |
| index_1m + R123 decision rows | 2021-08-02 -> 2026-10-19 | intraday path, VIX, other-index breadth, live chain (PCR, IV, skew, straddle) | at the bar |
| equity_1m (59 stocks) | 2021 -> 2026 | intraday breadth since the previous close / open / last 15 minutes | at the bar |
| options_rolling_1m (WEEK) | 2021 -> 2026 | the money check's option prices | at the bar |

Looked at, not used:

- fut_1m_live_index (29 Jul-19 Oct 2026 only, too short to train on);
- xauusd_1m (the Drive folder has only its README and a US-events calendar, no prices);
- fii_stats (XLS; the same information as participant OI);
- participant volume files;
- stock_options_rolling_1m, MCX 1-minute data, and the weekly nse_bhavcopy zips (their index rows are already in
  bhav_index).

There is **no US-market or GIFT-Nifty data in the lake**. The overnight world reaches us only through MCX's 23:30
close.

Biases:

- The 59 stocks are today's large caps (survivorship).
- Continuous MCX futures have roll jumps (clipped at +-4%).
- 4 participant files were dropped.
- 45 NIFTY daily opens equalled the previous close and were blanked as stale.

## 3. Base rates: the trap in "60%"

| horizon | up share, 2023-Mar 2026 (n) | follow the last move | up share, Apr-Oct 2026 (n) |
|---|---|---|---|
| GAP (official close -> open) | **63.7%** (3,970) | 59.2% | 53.2% (684) |
| GAP_T (14:55 -> open) | **61.9%** (3,791) | 59.5% | 51.0% (680) |
| C2C | 54.6% (3,969) | 53.3% | 51.1% (685) |
| DAY 09:30 -> close | 50.4% (3,811) | 50.6% | 51.7% (685) |
| R11 / R13 | 50.8% / 51.6% (3,803) | 51.5% / 52.1% | 50.5% / 54.7% (685) |
| M60 | 51.0% (19,007) | 51.2% | 50.4% (3,423) |

From 2023 to March 2026, simply saying "the index will open higher tomorrow" was right 62-64% of the time. That is
India's well-known overnight drift in a rising market. It faded to 51-53% in Apr-Oct 2026. A "60% direction" claim
that does not beat this base rate is no skill at all, so every test here had to beat it.

## 4. Results by horizon (best of 99 tests each; F1-F4 pooled, then the holdout)

All calls (100% coverage):

| horizon | best AUC (test) | hit (n) | base | holdout hit (n) |
|---|---|---|---|---|
| GAP | 0.609 (ALL LR) | 64.9% (3,970) | 63.7% | 48.5% (684) |
| C2C | 0.542 (IDX+FUT MLP) | 54.4% (3,969) | 54.6% | 51.7% (685) |
| PRE | 0.608 (IDX+BRD LR) | 63.9% (3,970) | 63.7% | 54.4% (684) |
| DAY | 0.539 (ALL LR) | 52.3% (3,811) | 50.4% | 50.2% (685) |
| R11 | 0.543 (IDX+GLOB LR) | 53.5% (3,803) | 50.8% | 55.2% (685) |
| R13 | 0.535 (IDX+BRD LR) | 51.9% (3,803) | 51.6% | 51.5% (685) |
| M60 | 0.524 (IDX+BRD MLP) | 52.0% (19,007) | 51.0% | 50.7% (3,423) |
| GAP_T | 0.604 (IDX+BRD LR) | 61.9% (3,791) | 61.9% | 54.3% (680) |
| C2C_T | 0.551 (IDX+BRD MLP) | 55.5% (3,790) | 54.3% | 51.5% (680) |

The 10% most confident calls (best test with n >= 100):

| horizon | test | n (days) | hit | base | q | 2023 / 2024 / 2025 / 2026Q1 | holdout hit (n) | holdout, moves >= tiny (n) | calls "up" |
|---|---|---|---|---|---|---|---|---|---|
| GAP | IDX+PART GBM | 162 (93) | 79.0% | 63.7% | 0.004 | 75.8 / 74.2 / 88.9 / 66.7% | 68.2% (22) | 80.0% (15) | 100% |
| C2C | IDX+CAL LR | 376 (245) | 67.0% | 54.6% | 0.002 | 71.1 / 72.6 / 65.3 / 51.4% | 55.4% (65) | 54.5% (55) | 96% |
| DAY | IDX+CHAIN MLP | 334 (247) | 55.7% | 50.4% | 0.218 | 63.0 / 55.7 / 54.9 / 36.8% | 48.6% (70) | 47.5% (61) | 86% |
| R11 | ALL LR | 645 (262) | 59.4% | 50.8% | 0.017 | 60.9 / 59.0 / 57.1 / 64.3% | 67.8% (90) | 69.9% (83) | 75% |
| R13 | IDX+CAL MLP | 395 (227) | 55.7% | 51.6% | 0.315 | 52.6 / 56.7 / 53.4 / 68.8% | 65.3% (101) | 65.6% (93) | 83% |
| M60 | IDX+BRD GBM | 491 (110) | 54.2% | 51.0% | 0.431 | 33.3 / 52.9 / 61.6 / 32.6% | 75.0% (64) | 74.1% (54) | 63% |
| GAP_T | IDX+CHAIN LR | 463 (345) | 77.5% | 61.9% | 0.000 | 79.8 / 74.4 / 81.3 / 71.4% | 67.3% (49) | 69.0% (42) | 97% |
| C2C_T | ALL GBM | 100 (41) | 68.0% | 54.3% | 0.128 | 62.5 / 70.4 / 68.9 / 65.0% | 70.0% (10) | 66.7% (9) | 99% |

- The nearest intraday miss is **R11 ALL LR** (11:00 -> close).
  - 59.4% over 2023-Mar 2026 (645 calls).
  - It fell below 60% in 2 of the 4 years, so it fails the gate.
  - Its 67.8% on the holdout (90 calls) is not enough on its own.
- **M60 at 10%** collapses in 2023 and 2026 (33%), so it is noise.

Gate count (891 tests):

| horizon | walk-forward passes | + holdout | counts (tradeable + twin rule) |
|---|---|---|---|
| GAP | 64 | 25 | 9 (see section 5: the official close) |
| C2C | 8 | 1 | 0 (twin fails) |
| PRE | 0 | 0 | 0 (not tradeable anyway) |
| DAY, R11, R13, M60 | 0 | 0 | 0 |
| **GAP_T** | 47 | **14** | **14** |
| C2C_T | 1 | 0 | 0 |

## 5. The official-close trap (why Amendment 1 was added before the holdout)

- **What the walk-forward picked.** The GAP models picked days that closed near their high: the mean close location
  was +0.31 on the 443 picked days, against +0.02 on all days.
- **Why that is not a real signal.** NSE's daily close, and the bhavcopy futures close, is a weighted average of
  15:00-15:30. After a strong last half hour, the last traded price sits above that average. So tomorrow's open
  "beats the close" partly because the close was understated.
  - The pre-registered GAP and C2C used that close for both their inputs and their entry.
  - So Amendment 1 rebuilt both from a 14:55 price and 14:55 information, using minute bars only (GAP_T, C2C_T).
- **What survived.** GAP_T still passes (14 tests). C2C_T does not.
- **How the 9 official-close GAP tests are treated.** They "count" under the twin rule, but only as the same effect as
  GAP_T. Their futures money check is entered at the 30-minute average close, so it is not a real fill.

## 6. The one survivor, GAP_T, up close

Signal source: the index (the underlying). Its strongest weights in the index-only logistic model, refitted on
2021-Mar 2026 with standardised inputs:

- VIX at 14:55 (+0.35); yesterday's VIX close (-0.23). So VIX up today means an up open is more likely.
- The previous day's return (+0.29).
- The 20-day average range (-0.25); 20-day realised volatility (+0.24).
- Distance above the 200-day average (+0.21).
- MIDCPNIFTY (+0.20).

The four 10%-coverage LR tests that pass:

| test | calls (n) | hit 2023-Mar 2026 | hit Apr-Oct 2026 (n) | MIDCPNIFTY share of calls | median move |
|---|---|---|---|---|---|
| IDX LR | 515 | 76.5% | 68.4% (76) | 69% | 0.35% |
| IDX+CAL LR | 498 | 77.5% | 67.6% (68) | 69% | 0.37% |
| IDX+CHAIN LR | 463 | 77.5% | 67.3% (49) | 71% | 0.33% |
| IDX+FUT LR | 528 | 77.3% | 65.8% (79) | 70% | 0.36% |

The other indices are right less often on their own: NIFTY 61-70% on 33-44 calls, BANKNIFTY 67-76% (28-37), FINNIFTY
68-71% (34-49), SENSEX 62-79% (28-40). The two GBM versions at 50% coverage spread more evenly (MIDCPNIFTY 30-34%).

**Money check:** buy the ATM call at 14:54's close and sell the same contract at 09:15's close the next day.
1 lot, Fills("liq"), Costs("dated"). Pre-registered.

| test | 2023-Mar 2026 trades | win | mean Rs | Apr-Oct 2026 trades | win | mean Rs |
|---|---|---|---|---|---|---|
| IDX LR 10% | 290 (225 skipped) | 63.8% | +862 | 28 (48 skipped) | 50.0% | +404 |
| IDX+CAL LR 10% | 255 (243) | 66.3% | +991 | 27 (41) | 44.4% | +404 |
| IDX+CHAIN LR 10% | 207 (256) | 66.2% | +793 | 15 (34) | 46.7% | +742 |
| IDX+FUT LR 10% | 296 (232) | 63.9% | +986 | 32 (47) | 53.1% | +216 |
| IDX+PART GBM 50% | 420 (261) | 59.8% | +583 | 66 (89) | 57.6% | +1,431 |
| ALL GBM 50% | 290 (187) | 61.7% | +599 | 49 (72) | 59.2% | +1,250 |

The robustness checks below were added after the holdout read. They are descriptive only, not pre-registered and not
gates (`extra.py`, `results/extra_gap_t.json`).

- **Selling 4 minutes later (09:19) wipes most of it out.**
  - 2023-Mar 2026 means fall by about half (Rs +127 to +622).
  - On Apr-Oct 2026, 4 of the 6 turn negative (Rs -315 to -603 a trade, 28-49 trades). ALL GBM falls to Rs -2; only
    IDX+PART GBM stays positive (Rs +678, 101 trades).
- **The skips are not random.**
  - Half or more of the calls could not be priced: 87-136 because the weekly contract rolls overnight, 97-144 because
    there was no traded print at 09:15, and 14-24 because there was none at 14:54.
  - In 2023-Mar 2026, the skipped calls were right less often (62-72%) than the traded ones (74-85%).
  - So the traded sample flatters the rule.
- **Jan-Mar 2026 lost** for every LR version (Rs -1,116 to -1,734 a trade, 17-22 trades).
- **Buying a call every day at 14:55** (no model):
  - 2023-Mar 2026: Rs +274 a trade (2,050 trades), from the bull-market overnight drift.
  - Apr-Oct 2026: Rs -180 a trade (306 trades).
  - The model's days do beat it, but on 15-66 holdout trades.

**Verdict for GAP_T:**

- The direction call is real and passes every gate (Rs numbers above, n stated).
- It is not a robust option trade: small moves, mostly MIDCPNIFTY, half the calls untradeable, and a profit that lives
  in the first minute's price.
- A futures version cannot be checked: there is no 14:55 futures price history before Jul 2026.
- It does not go to paper.
- If Boss wants, the same call could be logged daily as a forward watch, with no orders, for a clean out-of-sample
  record. That is a decision for Boss.

## 7. What each new source added

Differences against the index-only set, mean of the 3 models, F1-F4 pooled. Each cell is AUC at 100% / hit at 20%
coverage, in percentage points.

| horizon | +breadth | +participants & FPI | +futures | +chain | +rupee/G-sec/MCX | +calendar | all |
|---|---|---|---|---|---|---|---|
| GAP | -0.5 / -1.0 | -1.1 / -1.0 | -0.4 / -0.9 | -0.2 / -0.6 | -1.2 / -0.9 | +0.8 / +0.5 | -1.0 / -2.4 |
| C2C | -0.2 / +0.1 | -0.6 / -1.3 | +0.8 / +2.3 | +0.1 / +1.7 | -1.1 / +0.6 | -1.3 / +1.4 | -1.0 / -1.4 |
| PRE | -0.0 / -0.7 | -1.8 / -2.3 | -0.4 / -0.6 | -0.3 / -0.6 | -1.7 / -3.1 | +0.8 / +0.5 | -1.7 / -2.6 |
| DAY | +1.0 / +3.4 | +0.4 / +0.2 | -0.0 / -1.2 | +0.2 / -0.1 | +0.3 / +2.2 | +0.2 / -2.4 | +1.9 / +7.9 |
| R11 | +0.4 / +4.3 | +0.4 / +3.7 | -0.1 / +0.0 | +0.2 / +1.5 | +0.3 / +0.5 | -1.0 / -0.7 | +0.9 / +5.5 |
| R13 | +0.4 / +1.2 | -2.0 / -1.3 | -0.9 / -0.2 | -1.0 / -1.7 | -0.2 / -0.1 | -0.4 / -2.1 | +0.0 / +6.6 |
| M60 | +0.9 / +2.1 | -0.5 / -0.3 | -0.3 / -0.9 | +0.4 / +0.3 | -0.6 / -0.6 | +0.1 / +0.8 | -0.2 / +0.0 |
| GAP_T | -0.8 / -2.3 | -2.1 / -4.0 | -1.3 / -1.4 | -1.1 / -1.6 | -0.6 / -0.7 | -1.0 / -0.6 | -2.1 / -2.9 |
| C2C_T | +2.0 / +2.2 | -1.8 / -1.7 | +0.2 / -1.2 | -0.1 / +1.6 | +0.7 / +2.1 | -0.6 / -1.6 | -0.4 / +2.6 |

Index-only AUC (mean of 3 models): GAP 0.585, PRE 0.585, GAP_T 0.588, C2C 0.523, C2C_T 0.524, DAY 0.511, R11 0.519,
R13 0.515, M60 0.510.

- **Participants and FPI** (the "big players") hurt at almost every horizon. This agrees with OF1: its "follow the
  FII" signals flipped sign on the last 12 months.
- **The futures basis/OI/rollover, the end-of-day chain, and the rupee/G-sec/MCX** add at most a point here and
  there, inside the noise.
- **Breadth of the 59 large stocks** is the only source that helps intraday fairly steadily: +0.4 to +1.0 AUC points,
  and +2.0 at C2C_T. That is far from 60%.
- **Putting everything together** helps the intraday 20% selective hit by 5-8 points (DAY 57.2%, R11 56.7%), still
  below 60%.

## 8. Money checks of the fixed comparison set (ALL-set GBM, 20% coverage)

| horizon | 2023-Mar 2026 trades / mean Rs | Apr-Oct 2026 trades / mean Rs |
|---|---|---|
| DAY (ATM option, 09:30 -> 15:20) | 52 / +1,379 | 19 / -1,265 |
| R11 | 87 / -431 | 28 / -1,328 |
| R13 | 36 / +1,409 | 18 / -567 |
| M60 | 1,100 / -242 | 323 / +139 |
| GAP (near future, official close -> open) | 275 / -90 | 28 / -515 |
| C2C (near future) | 115 / +1,264 | 38 / -8,567 |
| GAP_T (ATM option, 14:55 -> 09:15) | 84 / +244 | 18 / +1,950 |
| C2C_T (ATM option, 14:55 -> next 15:20) | 106 / -590 | 17 / -264 |

The other gate-passing tests' money checks are in `results/report.md`. The official-close GAP versions made money on
futures in both periods for most tests, but that entry price cannot be had (section 5).

## 9. What this means for Boss

1. **The intraday direction is still a coin**, whatever data is added. The useful forecast remains move SIZE (R16,
   R123), not direction.
2. **The "60%" that exists is an overnight up-drift.** It mostly shows up in MIDCPNIFTY, and its moves are too small
   to pay for an option reliably. It also faded in Apr-Oct 2026 (51% up).
3. **Nothing goes to paper or live.** No app change was made.
4. **A cheap next step, if Boss wants one:**
   - Log the GAP_T call each day at 14:55 as a forward-only watch, with no orders.
   - Re-check it with 3 months of clean data, ideally with real futures quotes.
   - Get US-market or GIFT-Nifty data into the lake. It is the biggest missing input for anything overnight.

## 10. Honesty notes

- Amendment 1 (the twins and the stricter rule) was written after seeing the walk-forward, but before the holdout. It
  only removed passes; it added none.
- The holdout read was resumed twice, inside that one read, to fix the money check's speed and memory. No holdout
  number was seen before the final run.
- The overnight money check now uses the exchange's real expiry dates (bhavcopy) to keep the same weekly contract
  overnight. This was committed before the resumed read; SENSEX keeps R123's expiry flag.
- The section-6 robustness checks were done after the holdout read and are labelled as such.
- Sample sizes are given with every number. 891 tests ran; all of them are in `results/wf.json` and
  `results/holdout.json`.
