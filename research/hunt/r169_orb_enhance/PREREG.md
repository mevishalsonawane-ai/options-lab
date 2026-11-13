# R169 pre-registration: can the newer knowledge enhance the ORB arms? (ORB, ORB Fresh, ORB Sweep)

Written 13 Nov 2026, BEFORE any R169 number. Only code, earlier reports and the lake's file list were read. Committed and
pushed on its own before the first run on data. Research only: nothing in the app changes.

Boss, 13 Nov: *"Can we use all the new knowledge on ORB and try to enhance it."*

## 0. The arms (android/engine/.../orb: OrbRules.kt, SweepRules.kt, ReentryCap.kt, ProfitLock ladder)

All three are BANKNIFTY-only, paper, and parked on Boss's choice since 13 Oct (FamilyPark.kt). Signal source: **the index**
(BANKNIFTY spot 5-minute bars); the option is only bought.

| arm | entry | exits |
|---|---|---|
| ORB | 5-min close beyond the 09:15-10:00 range, bars 10:05-13:55; ATM strike of the 09:20 bar (half-up, 100), held all day; nearest expiry on or after the day; at most 1 entry a side a day (R14) | -40 / +40 premium points, the ProfitLock ladder (25% of target -> breakeven incl. charges, 50% -> 25%, 75% -> 50%), 15:10 square-off (15:05 on expiry) |
| ORB Fresh | the same, only a fresh break (previous bar not beyond) | the same |
| ORB Sweep | a bar pokes beyond a range edge and closes back inside: buy the reversal; bars 10:05-14:25; at most 2 entries a day | -40 / +80, the ladder on +80, 15:10 |

Engine: R3's (`research/hunt/r3orb/r3engine.py`), which R144b reproduced to the rupee. Note (R144b): on BANKNIFTY with the
1-a-side cap, ORB Fresh takes exactly ORB's trades (the first break of a side is always fresh). Fresh is still reported and
counted as its own arm in the BH family.

## 1. Parity first (nothing else runs until it holds)

R3 engine, R3 costs (`Costs('app')`, today's rates, R17 `Fills('liq')`), 1 lot, BANKNIFTY, R144b's design period
20 Nov 2024 - 31 Mar 2026: ORB 320 trades / -Rs 41,427; ORB Fresh 320 / -Rs 41,427; ORB Sweep 454 / -Rs 1,12,523
(R144b `results/design.json`). If not reproduced exactly, R169 stops and reports why.

Then **the study's own baseline B0** is the same rules priced with **dated Zerodha charges** (`Costs('dated')`, the rates in
force on each trade date, STT 0.15% from 1 Apr 2026) and R17 fills sized to the order (1, 2 or 3 lots: the fill model's
"thin" test counts the order's own lots). Charges are on the whole quantity.

## 2. Periods

- Training history: Aug 2021 - 19 Nov 2024 (only to train the picker and to set fixed cut values; never a test).
- **Design: 20 Nov 2024 - 31 Mar 2026; walk-forward (WF) window Apr 2025 - Mar 2026 in 4 quarters.** Every learned
  or chosen thing (picker weights, thresholds, cut values, the combinations) is fitted only on data before each quarter.
- **Final: 1 Apr - 12 Oct 2026** (the session store's end, as R3 / R144b), read once (`results/FINAL_READ`). Honest
  note: this window has been read by many studies (R14, R49, R92, R101, R144b, R151-R167 ...), so even a pass is weak
  evidence.
- **Post window 13-19 Oct 2026** (the lake's last days): an extra look, counts only, never gated (about 5 sessions).

## 3. The changes (one at a time), per arm

The ideas came closest in recent studies (each report read before use). Each is defined exactly here.

### 3a. Skip-only entry filters (the app's rule: a skipped break still holds its side)

A filtered break is simulated as if taken: it still counts in the side cap (ORB, Fresh) or the 2-a-day cap (Sweep) and
blocks the arm until its would-be exit, so a filter can only remove trades from B0's list. Unknown values never skip.

| id | idea (source) | rule |
|---|---|---|
| F1 PICK | R151 / R163 picker | L2 logistic regression (sklearn, C = 1, standardised) on the trade's entry features below, trained on every B0 trade of the arm that closed before the quarter starts (from Aug 2021; at least 150, else no skip). Keep a break when its predicted P(win) >= the training trades' win rate. Final: trained on everything to 31 Mar 2026. Features (all from rows <= the signal bar's last minute): previous VIX close, VIX change today, gap the trade's way / ATR14, range width / ATR14, signal row, expiry day, distance from the day's TWAP the trade's way / ATR14, signal bar range / ATR14, push of the last 3 five-min bars the trade's way / ATR14, distance beyond yesterday's high (calls) / low (puts) / ATR14, log of the option's last close before entry |
| F2 NET | R123 N1 network EV (the one thing that helped in R133) | take only when R123's walk-forward N1 view (the latest 5-minute view at most 5 minutes old, r132nn.view_row) has mean EV of the side's 8 cells > 0. Predictions are R123's out-of-fold walk-forward rows (2023 - 15 Mar 2026) and its frozen holdout rows (Apr - Oct 2026). No view (the 16-31 Mar 2026 embargo, missing rows): taken |
| F3 BUSY | R161 E9 (ATR hot) | the index's 14-bar 5-minute ATR at the signal bar >= the median of that ATR at the same bar over the previous 20 sessions (needs 10) |
| F4 HTF | R161 higher-timeframe trend | the trade's side agrees with the 60-minute move: close of the signal bar vs the index close 60 minutes earlier |
| F5 GAP | R160 / R146 / MARKET_HOW (small gaps fill, big gaps hold) | skip a trade against a big gap (abs gap >= 0.6%, side opposite the gap) and a trade in the direction of a small gap (0 < abs gap < 0.3%, same side as the gap) |
| F6 EXT15 | MARKET_HOW (the day's extreme set in the first 15 minutes on 56-62% of days) | ORB / Fresh: take a break only if the OPPOSITE range edge was set in 09:15-09:29. Sweep: take a reversal only if the swept edge was set in 09:15-09:29 |
| F7 FVG | ICT (R130 / R131 / R139: displacement, FVG) | the three 5-minute bars ending at the signal bar leave a fair-value gap the trade's way (call: high of bar b-2 < low of bar b; put: low of b-2 > high of b) |
| F8 ROOM | Liquidity 15+5's room filter | skip when yesterday's high (calls) / low (puts) lies beyond the signal close but within 30 BANKNIFTY points (a level right in the way) |
| F9 NOEXP | Liquidity 15+5 skips expiry days | skip on an expiry day |

### 3b. Tighten-only exits (all of today's exits stay; a rule can only add an earlier exit or raise the stop)

| id | idea (source) | rule |
|---|---|---|
| X1 BE10 | R162 BE10 | once the option's best price (earlier minutes' highs) is >= +10% over the fill, a stop rests at the breakeven price (net Rs 0 after charges) |
| X2 T40 | R162 T40 | 40 minutes after the fill, sell at the next open unless the best price has reached +5% over the fill |
| X3 T20 | Liquidity 15+5's time stop | the same at 20 minutes, +5% |
| X4 G3 | R167 G3 (3-minute index-low trail), tighten-only | once the best price is >= +50% of the target over the fill: sell at the next open when a 1-minute index close falls below the lowest low of the previous 3 minutes (calls; puts mirrored) |
| X5 SRT | R150 levels as tighten-only targets | sell at the next open when the index touches yesterday's high (calls) / low (puts), if that level was beyond the index at entry |
| X6 FAIL | Liquidity's failed-break exit / R131 index stop / R144b's finding (stop-outs on no follow-through) | ORB / Fresh: sell at the next open when a 5-minute bar closes back inside the range. Sweep: when a 5-minute bar closes back beyond the swept edge |

### 3c. Strike, timing and exit-structure changes (NOT risk-lowering: these change entries or size and would need Boss's explicit choice)

| id | idea (source) | rule |
|---|---|---|
| S1 D60 | R162 D60 / R165 | the strike of the trade's side whose Black-Scholes delta (the option's own IV, solved from its close at the signal bar's last minute; r = 6.5%; time to 15:30 on expiry) is nearest 0.60; chosen among the stored strikes (ATM +-4) |
| S2 D50 | R165 SD50 | the same, nearest 0.50 |
| S3 ITM1 | Liquidity 15+5's strike | one strike in the money (R3's `itm=1`) |
| W1 WAIT1 | R161 E2 | the NEXT 5-minute bar must also close beyond the range (ORB / Fresh) or back inside the swept edge (Sweep); entry at the minute after it; dropped if that bar is past the arm's last entry bar |
| L1 LIQX | Liquidity 15+5's exits | -15% premium stop, 20-minute time stop unless +5%, no fixed target and no ladder, 15:10 square-off (wider risk than -40 on dear options) |

### 3d. Combinations (chosen on design / WF only)

Before each WF quarter (and once on all design data for the final), rank the single changes of 3a-3c by their net gain
over B0 on the training part of the design period (20 Nov 2024 to the quarter start; for Q1 the first quarter of design
Nov 2024 - Mar 2025), among those whose training max drawdown is not worse than B0's. C1 = the best two; C2 = the best
three; C3 = the best skip filter + the best tighten-only exit. A combination applies its filters (skip-only, as above)
and its exits together; a strike / timing change is applied first. If fewer than the needed changes qualify, the
combination is B0 (and is reported as such).

That is 20 single changes + 3 combinations = 23 per arm; x 3 arms (ORB, Fresh, Sweep) x 3 lots (1, 2, 3) = **207 tests**.

## 4. Gates (all must hold; never relaxed)

For each arm x change x lots, against the same arm's B0 at the same lots:
1. **WF window AND final:** EITHER (net > B0's net AND max drawdown not worse) OR (win rate >= B0's + 5 points AND net
   >= B0's).
2. **Beats random twins of the same size** (2,000 twins, final period, one-sided p <= 0.05 and BH q <= 0.10):
   - skip filters: as many of the arm's B0 trades skipped at random (the same walk);
   - exits: as many trades as the rule changed, each sold at a random minute between its fill and B0's exit;
   - strike / timing / LIQX / combinations: each trade re-drawn at a random bar in the arm's window with the same side,
     strike rule and exits (R3's twin);
   - the metric is the one the passing branch of gate 1 used (net, or win rate).
3. **BH q <= 0.10** over m = 2,664 earlier tests (the repository's count through R167) + R169's 207. R168 (run today in
   parallel on another branch) is not in this count; that is stated with the result.
4. **>= 40 final trades.**
5. **Boss's account limits at the tested lots:** max drawdown >= -Rs 40,000 and worst month >= -Rs 13,000, in the WF
   window and in the final.

Reported for every arm x change x lots (WF and final): trades, win %, break-even win %, net, net per session, worst day,
worst month, max drawdown, net without the 5 best trades, twin p, q.

**Risk classes** (CLAUDE.md): 3a and 3b only lower risk (fewer entries, earlier exits): if one passed it would go to
**shadow** first (logged only, a switch off by default). 3c and the combinations that contain a 3c change alter entries
or size and would need Boss's explicit choice. If anything passes, an exact rule spec (as `research/hunt/r72/APP_SPEC.md`)
is written; nothing in the app is changed by R169.

## 5. Tests and the look-ahead guard

Every new pure function has a pytest test (empty days, missing minutes, gaps through a stop, expiry day, the square-off,
NaN prints, no levels, no network view). Look-ahead guard: every feature and every exit decision at minute m is unchanged
when all data after m is replaced with NaN / junk.

## 6. Data

The Drive lake (`gdrive:IraAlgo-data-lake`: index_1m BANKNIFTY + INDIAVIX, index_daily, options_rolling_1m BANKNIFTY WEEK)
and R123's prediction rows (`derived/r123/cleaned/wf_rows_preds_N1.parquet`, `holdout_rows_preds_N1.parquet`), copied with
rclone on 13 Nov 2026. R3's session store is rebuilt from it. Nothing raw goes into git.
