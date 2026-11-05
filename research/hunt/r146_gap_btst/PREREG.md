# R146 pre-registration: can the close predict tomorrow's opening gap, and does a BTST option trade on it pay?

Written 5 Nov 2026, BEFORE any R146 feature, outcome or result was computed. Only file schemas and date ranges of the
data were looked at. Boss (5 Nov): "Can you properly study, as most professional traders are able to tell the
prediction, and keep today's orders open, and then sell it tomorrow with good profit."

Research only. Nothing under `android/` changes. Any app idea that comes out of R146 is written as a NOTE.

## 0. What earlier work already says (read before writing this; it sets the bar)

- **Night (R3)** (`NightRules`, X2): buys a 1-ITM option at 15:20 on a strong close the day-end OI build-up agrees with,
  sells at 09:16. No premium stop. X2: about Rs 207 a day before its holdout, Rs 150 a day in it. R27 rebuild on the
  lake: Rs 301 a trade (2023 to Sep 2025, 472 trades) and Rs 245 a trade (Oct 2025 to Oct 2026, 173 trades).
- **R27:** holding past 09:16 did not help (0 of 8 variants).
- **H27:** overseas cues correlate about 0.5-0.65 with the 09:15 gap, but most of them arrive overnight (the US
  session ends 01:30-02:30 IST).
- **H28:** the session after an FOMC decision opens higher (BANKNIFTY +0.36% vs +0.10%, BH q 0.03). It is a gap, not an
  intraday move.
- **R41 (FINDINGS):** a neural net for the next-morning gap from 15:20 only learned the gap-up base rate. Its call rule
  made +Rs 24 a day; "always the call" on the same nights made +Rs 22.
- **R47:** overnight late-day momentum looked best in design and lost Rs 1,031 a trade on the BANKNIFTY holdout
  (90 trades).
- **R144c:** Night's skip when India VIX is down more than 3.13% (Boss's exception, failed gates) is on in paper.

The prior is therefore "mostly not predictable at 15:20". R146 tests that honestly and says what part of the gap only
arrives overnight.

## 1. Data (nothing raw goes into git)

The Drive lake (`gdrive:IraAlgo-data-lake`) cannot be read in this session: the container's rclone build has no Drive
backend. R146 uses two sources instead.

- **M (minute): the repo's `research-data` release**, downloaded with `gh api` to `~/.cache/iraalgo/release`:
  - `nifty_prev` + `nifty_year`: NIFTY index (IX) and option minutes (nearest expiry after the day), 1 Apr 2024 to
    13 Apr 2026;
  - `banknifty_prev_year_wide` + `banknifty_year_wide`: BANKNIFTY, the same, 13 Feb 2024 to 23 Feb 2026;
  - `finnifty_index`, `sensex_index`: index minutes only, 12 Feb 2024 to 24 Feb 2026;
  - `extra_series`: INDIAVIX and NIFTY minutes, 1 Feb 2024 to 27 Feb 2026.
  - Timestamps are naive IST bar starts. A duplicated minute keeps its first row (as R79 / R110).
  - Option rows whose price is more than 20% from the contract's neighbouring minutes are NOT cleaned. Instead,
    every fill uses the bar OPEN of the stated minute, and the fill guard (section 5) skips a trade whose entry or exit
    open is more than 20% from that bar's close (R47's stray-print rule).
  - **M nights:** the entry day d runs from the first day where all features exist to the last d whose next session
    is in the data for that index (BANKNIFTY to 20 Feb 2026, NIFTY to 20 Feb 2026, because VIX / FINNIFTY / SENSEX end
    there). NIFTY nights start when its options start (1 Apr 2024).
- **D (daily): Yahoo chart API**, fetched with plain HTTPS to `~/.cache/iraalgo/r146/yahoo`, 2007 to 4 Nov 2026:
  - `^NSEI` NIFTY, `^NSEBANK` BANKNIFTY, `NIFTY_FIN_SERVICE.NS` FINNIFTY, `NIFTY_MID_SELECT.NS` MIDCPNIFTY, `^BSESN` SENSEX;
  - `^INDIAVIX`;
  - `^N225` Nikkei, `^HSI` Hang Seng, `^KS11` KOSPI;
  - `^GSPC` S&P 500, `USDINR=X`.
  - **Data check, fixed now:** a year of an index is dropped from D if more than 5% of its days have open equal to the
    previous close (a known Yahoo fault for old Indian index opens). A day is dropped if its open, high, low or close
    is missing or the bar is inconsistent (high < max(open, close) or low > min(open, close)). The count is reported.
- **Events:** `research/hunt/r98/events.csv` (RBI, BUDGET, ELECTION, FOMC, USCPI with the Indian session each one hits;
  Aug 2021 to Oct 2026, sources per row). All of these dates are scheduled and known in advance, except ELECTION result
  days, which are also announced in advance.

## 2. Periods: one locked holdout for both parts

- **HOLDOUT: entry days on or after 1 Mar 2025**, read ONCE (`study.py holdout` writes `results/HOLDOUT_READ.flag` and
  refuses a second read).
  - M: 1 Mar 2025 to 20 Feb 2026, about 12 months.
  - D: 1 Mar 2025 to 4 Nov 2026, about 20 months. The same cut is used for both, so no D choice can leak M-holdout
    gaps.
- **DESIGN:** everything before 1 Mar 2025.
  - D: 2008 to Feb 2025. Walk-forward, retrained each calendar year on all earlier years (expanding), test years 2012
    to 2024 plus Jan-Feb 2025.
  - M: Feb / Apr 2024 to Feb 2025. Walk-forward, retrained at each month start on all earlier M nights (expanding);
    the first test month is Aug 2024 (about 100+ training nights). Design test months: Aug 2024 to Feb 2025.
  - The holdout uses the same procedure: monthly (M) or yearly (D) retraining on all earlier data, so the holdout
    stays out-of-sample without a frozen model.

## 3. Targets

- **Gap (M):** `g = ln(O_next / x)`, where x is the index's 15:19 close on d (the decision price, about the 15:20 buy
  price) and O_next is the next session's 09:15 open. The direction target is `g > 0`.
- **Gap (D):** `g = ln(O_{t+1} / C_t)`, from the 15:30 close. **Caveat (said now): C_t is 10 minutes after the 15:20
  decision.** D's features use the full day's bar, so D slightly overstates what is known at 15:20. If D finds
  nothing, that is a stronger "no". If D finds something, M must confirm it.
- **Size:** `|g|`, and g itself for the regression R².

## 4. Predictors (all known by 15:19 IST on d; anything that is not is dropped)

**M features, per index (NIFTY, BANKNIFTY), read at the 15:19 bar's close:**

| id | feature |
|---|---|
| m_move | x / previous session's last close - 1 |
| m_loc | (x - low) / (high - low) of the day to 15:19 (0.5 with no range) |
| m_l30 / m_l60 | x / close of the 14:49 / 14:19 bar - 1 |
| m_oc | x / the day's 09:15 open - 1 |
| m_gap0 | today's gap: 09:15 open / previous last close - 1 |
| m_brd | breadth across indices: mean of sign(day move to 15:19) over NIFTY, BANKNIFTY, FINNIFTY, SENSEX |
| m_vix / m_dvix | India VIX at 15:19, and ln(VIX 15:19 / VIX previous last close) |
| m_bu / m_buz | NightRules' BUopen build-up at 15:19 (nearest series, ATM +-2, ATM from x) and its z against the previous 60 sessions (needs 20) |
| m_pcr / m_dpcr | put OI / call OI over ATM +-5 at 15:19, and its change since the day's first bar |
| m_ivx | ATM straddle (call + put close at 15:19) / x: the option market's priced move |
| m_dte | trading sessions to the nearest expiry; m_exp (d is an expiry day); m_post (d is the day after one) |
| m_dow | weekday of d (one-hot); m_wkend: the next session is more than 1 calendar day away |
| m_us_ev | the next session is hit by an overnight FOMC or US CPI release (r98 calendar, scheduled in advance) |
| m_in_ev | the next session is an RBI, Budget or election-result day |
| m_asia | Nikkei, Hang Seng and KOSPI close-to-close returns on d (they close at 11:30 / 13:40 / 12:00 IST, before 15:19); 0 if that market was shut |
| m_spx_prev | S&P 500's last session before d (already known at 09:15 on d) |

**Dropped as not point-in-time at 15:20:** US index futures at 15:20 and USD/INR at 15:20. Only daily bars exist, and
their close comes after 15:20 IST. USD/INR is used lagged one day (d_fx). FII flows are published after the close.

**D features (from daily bars, the C_t caveat above):** d_ret (close-to-close), d_loc (close in the day's range),
d_gap0, d_oc, d_ret5, d_rng (ln high/low), d_vix, d_dvix, d_asia (Nikkei, HSI, KOSPI on d), d_spx_prev, d_fx (USD/INR
change, previous day), d_dow, d_wkend.

**Overnight information, never a predictor; used only to measure what arrives after 15:20:** ov_spx is the S&P 500
return of the US session dated d (it ends 01:30-02:30 IST on d+1).

## 5. Models and rules (all fixed now; nothing is tuned)

Each index is separate.

**Prediction rules (a side or none):**

| id | rule |
|---|---|
| P0 | base rate: the side of the training-period mean gap (always one side). It is the bar, not an idea |
| R_mom | sign of the day move (m_move / d_ret) |
| R_loc | loc >= 0.6 up, loc <= 0.4 down, else none |
| R_l30 | sign of the last 30 minutes (M only) |
| R_r17 | Night's strong close: loc >= 0.6, move >= 0 and breadth >= 0 up (mirror down). Breadth here is m_brd, the cross-index proxy (M only) |
| R_r3p | R_r17 with the OI build-up agreeing (side x m_buz >= 0.5): **the Night (R3) proxy** (M only) |
| R_asia | sign of the Hang Seng's return on d |
| LR | L2 logistic (C = 0.1) on all standardised features of its set (median-imputed with a missing flag), side = p >= 0.5 |
| GB | HistGradientBoostingClassifier(max_depth=2, learning_rate=0.05, max_iter=150, min_samples_leaf=20, l2_regularization=1.0, random_state=146) |

- **Size models** (information only): ridge (alpha = 10) and a GB regressor of the same settings on g and on |g|.
- **Decomposition** (D, information only): the out-of-sample R² of g from ridge on (a) the known D set, (b) ov_spx
  alone, and (c) both together. This is "what part arrives overnight".

**Prediction gate.** An idea predicts the gap if, on the design walk-forward, all of these hold:
1. its hit rate on the nights it picks a side is above P0's hit rate on the same nights;
2. its permutation p is <= 0.05 (2,000 shuffles of its sides across its own nights, the share at least as good) for
   rules; for LR / GB, the AUC's permutation p (2,000 shuffles of the labels) is <= 0.05;
3. the BH q over all R146 prediction ideas is <= 0.10;
4. it covers at least 100 nights.

The holdout then needs a hit rate above P0's and p <= 0.05.

The prediction ideas are D: 5 indices x {R_mom, R_loc, R_asia, LR, GB} = 25; M: 2 indices x {R_mom, R_loc, R_l30,
R_r17, R_r3p, R_asia, LR, GB} = 16. Total: 41.

## 6. The trading test (M only: NIFTY and BANKNIFTY have option minutes)

- **Contract (Night's rules):**
  - the nearest expiry in the data, 1 step in the money from x (ATM = floor(x / step + 0.5) x step; call one step
    below, put one step above);
  - not on an expiry day of the index, never held into expiry (the contract must expire after the next session);
  - 1 lot at the lot in force (R27's dated table: NIFTY 75 -> 65 from 31 Dec 2025, BANKNIFTY 30 / 35 / 30).
- **Entry:** the open of the first bar in 15:20-15:22. No bar there means no trade.
- **Exits:**

  | id | exit |
  |---|---|
  | X16 | **09:16 open, as Night (the primary exit)** |
  | X15 | 09:15 open |
  | X20 | 09:20 open |
  | X30 | 09:30 open |

  X15, X20 and X30 are comparisons only: R27 already rejected holding past 09:16. A missing exit bar uses the next bar
  up to 09:45, else no trade (the same night drops from every strategy and exit).
- **Stop variant S30** (information only): a -30% premium stop resting at the broker (SL-M).
  - It is watched on 15:21-15:29 of d (bar lows) and from the next session's 09:15 bar.
  - A next-morning open below the stop fills at that open, -10 bps (the gap-through-stop risk).
  - Otherwise it exits at X16.
- **Costs:**
  - app fills: +5 bps buy, -5 bps sell, -10 bps stop;
  - app charges, `research/obuy/costs.Costs('app')`: Rs 20 brokerage an order, STT 0.15% of the sell premium,
    exchange 0.03553%, SEBI, stamp 0.003%, GST 18%;
  - plus the h24 half-spread of 0.16% on both legs;
  - **stress:** 1.5x the spread.
- **Fill guard:** a trade whose entry or exit bar's open is more than 20% from its own close is dropped. The count is
  reported.

**Strategies (side per night or none):**

| id | side |
|---|---|
| T0 | **Night (R3) proxy** (R_r3p): the baseline |
| T1 | always the call (the gap-up base rate) |
| T2 | R_r17 (strong close, no OI) |
| T3 | LR, only when abs(p - 0.5) >= 0.05 |
| T4 | GB, only when abs(p - 0.5) >= 0.05 |
| T5 | R_l30, only when abs(m_l30) >= 0.05% |

**Random-side twin:** each strategy's own nights with a random side, 2,000 draws. Twin p is the share with a total net
at least the real one.

**Trading gate (design walk-forward Aug 2024 to Feb 2025, X16), all needed:**
1. net per trade > 0, also at 1.5x spread;
2. rupees per session >= T0's (sessions = the index's design nights);
3. twin p <= 0.05;
4. BH q <= 0.10 over the 10 non-baseline strategy x index tests (T1-T5 x 2);
5. at least 30 trades.

T1's twin is the always-put side; its p is the share of twins at least as good, which is the same coin-flip test.
**Holdout (once):** a design passer must also show net > 0, rupees per session >= T0's in the holdout, twin p <= 0.05
and at least 30 trades. If nothing passes design, the holdout is reported as information only.

**Reported for every strategy, index, exit and period:** trades, net per trade, win rate, total, worst month, months
positive / months, max drawdown, worst trade, the share of trades losing more than 30% of premium, net at 1.5x spread,
and twin p.

## 7. Verdict rules

- **Paper only, ever.** A strategy passing both gates would be described for PAPER only (behind a switch, off by
  default, logged in the Autopilot timeline, one-tap undo). Live stays shadow-first (CLAUDE.md).
- **Failure is a valid answer.** If nothing passes, the answer is "not predictable enough at 15:20 to trade", with the
  size of the known vs overnight parts.
- **Signal source:** every R146 rule reads the underlying index (plus India VIX, option-chain OI / straddle as context,
  and Asian index closes) and buys the option. None reads the option's own chart.

## 8. Multiple testing and logging

- **BH:** within R146's 41 prediction ideas and within its 10 trading tests.
- **Wider family:** the overnight family already holds over 4,000 tries (R27's note). Any q against that family is
  stated alongside.
- **Experiment log:** every idea, including failures, is logged in `research/experiments/entries/r146-*.yaml` with
  `variants_tried` counted.

## 9. Look-ahead guard (tested)

- A night's features must not change when every minute after 15:19 on d, and the whole next session, are replaced by
  random numbers.
- The walk-forward never trains on a night whose next session is on or after the test month's (or year's) first day.
- The holdout flag blocks a second read.
