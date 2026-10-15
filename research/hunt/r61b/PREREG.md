# R61b pre-registration: do fake-breakout, trap and order-flow checks help Solo pick its trades?

Written 15 Oct 2026, **before any R61b profit, loss, model score or feature value was computed**. The lake was copied
and only its file list, column names and last timestamps were looked at (last sessions: 14 Oct NIFTY / BANKNIFTY,
13 Oct FINNIFTY / MIDCPNIFTY, the same lake as R61). Commit this file first; results come after. Research only:
nothing here changes the app (no `android/**`, no `.github/**`, no knowledge-pack rebuild). Solo is Jarvis's own trader
and stays **paper only, forever**.

Boss, 15 Oct: "Fake breakouts, order flow, traps - all should also be checked by Solo." R61 (`HUNT_R61_SOLO_3.md`)
found that Solo 2.0 (A2) stays and that no option-chain, Greek or context concept added out-of-sample skill.

R61b asks one narrow question: **given A2's candidates (S1-S4), do pre-listed trap / fake-breakout / order-flow-proxy
features, computable at the signal minute from 1-minute data, improve Solo on unseen days, either as a "skip this
candidate" filter or as extra inputs to the A2 meta-labeller?**

## 0. What the data can and cannot say (stated before any result)

- The lake has **1-minute OHLC for the index (no index volume)** and **1-minute OHLC, volume, OI and IV per option
  contract**. It has no ticks, no bid/ask, no order book. True order flow (signed trades, delta, cumulative delta, OFI,
  depth imbalance) needs tick and order-book data, so every "order-flow" feature here is a **proxy**.
- Index futures minutes exist only from 29 Jul 2026 (live contracts; all inside the final window), so **futures volume
  cannot be used** (it would be missing for the whole design period). "Volume at the break" therefore uses the
  **near-the-money option volume** of the index (calls + puts, offsets -2..+2 of the WEEK file), the same proxy R9
  used (OPTV). Futures volume is skipped, and the write-up says so.
- The phone's TickZoneRecorder (ticks + 5-level depth, Data Brain L1b) was built 12 Oct 2026; at most a few sessions
  exist and none has been analysed. R61b cannot use them.

## 1. Data, window, split, labels (exactly R61 / R57, unchanged)

- Drive lake `gdrive:IraAlgo-data-lake`: `index_1m` (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, INDIAVIX);
  `options_rolling_1m` **WEEK** files only. Raw data and caches stay outside git and are deleted at the end.
- Boss's data rule: **only 20 Nov 2024 onwards** (first session 21 Nov). Index minutes from 1 Sep 2024 only warm up
  indicators (and give the previous session's high / low for 21 Nov). Option volume / OI baselines use chain data from
  20 Nov 2024 only.
- **DESIGN: 21 Nov 2024 - 31 Mar 2026**, purged walk-forward, monthly refits, 1-session embargo; test months
  Jul 2025 - Mar 2026 (9 folds).
- **FINAL: 1 Apr 2026 - 14 Oct 2026 (the lake's last session), read ONCE** by `r61bstudy.py final` (guard file
  `results/FINAL_READ`; R57's period guard refuses to price a final session before that).
- Candidates S1-S4 and their labels are R57's, unchanged: each priced on its **held contract** by R51's engine
  (next-minute fills, R17 spread and bps, dated charges with STT 0.15% from 1 Apr 2026, lots in force, 1 lot), R57's
  cleaning (mislabelled strikes, foreign rows, bad ticks), no trade on an index's own expiry day, WEEK files only.
  Label = win (net > 0).
- **Honest note: the final window is NOT fresh.** R57 read it for A2 with these setups, then R61 and R61-N. This is its
  fourth use for the same candidates. Live paper from 15 Oct 2026 is the only truly unseen data.

## 2. Signal source (house rule)

S1-S4 read their entry signal from the **underlying index**. Every R61b feature is read from the index minutes, or
from the option chain's volume / OI as context. They can only make Solo **skip** a candidate (filters) or change its
take / skip score (meta-labeller); they never create a trade or pick a side. No rule reads its signal from an option's
own price chart.

## 3. Features (block TR, 15 inputs), all read at the signal row frow = entry row - 1

Notation: `o, h, l, c` = the index's 1-minute bars of the day; `s` = +1 for a call candidate, -1 for a put. Only rows
`<= frow` are ever read. A missing minute (NaN close) is skipped, never filled from a later minute. Levels:
- **OR15**: high / low of the 09:15-09:29 minutes (rows 0-14); known from row 14; NaN before.
- **PD**: the previous session's high / low (the previous day in the index store, so holidays and weekends are skipped).
- "Beyond on side +1" means a close above the upper level; "on side -1" a close below the lower level.

**Trap / fake-breakout flags (price only, N = 15 minutes):**
A *break* of a level on side q happens at row t when `c[t]` is beyond the level and the previous valid close is not
(the previous session's last close for the first bar; for OR15, only rows t >= 15). The break *fails* if a close at a
row in t+1 .. min(t+15, frow) is back inside (at or below the upper level / at or above the lower level). A break
whose 15 minutes are not over by frow and which has not come back yet is NOT failed (no look-ahead).

| name | definition |
|---|---|
| `trap_same_or` | 1 if a break of OR15 on the trade's side (q = s) failed earlier today (before or at frow), else 0; NaN if frow < 15 |
| `trap_opp_or` | the same on the other side (q = -s): a failed break against the trade (a trap for the other side) |
| `trap_same_pd` | 1 if a break of the previous day's high (call) / low (put) failed today by frow, else 0 |
| `trap_opp_pd` | the same for the other side's previous-day level |
| `sweep_same30` | 1 if, in rows frow-29 .. frow, a bar's high (call) / low (put) went beyond the session's running extreme of all earlier bars of the day (rows >= 15 only) but that bar closed back inside it: a stop-hunt / liquidity sweep on the trade's side (read as a warning) |
| `sweep_opp30` | the same on the other side (a sweep of the lows for a call; read as support) |

**Break quality of the signal bar** (the 5 one-minute bars frow-4 .. frow, i.e. the 5-minute bar whose close gave
S2-S4 their signal; for S1 the 11:55-11:59 bar; for S3 09:15-09:19):

| name | definition |
|---|---|
| `brk_wick` | rejection wick: call (H - max(O, C)) / (H - L); put (min(O, C) - L) / (H - L); NaN if H = L |
| `brk_clv` | close location ((C - L) - (H - C)) / (H - L) x s; NaN if H = L |
| `vol_brk_rel` | log of (near-ATM option volume, calls + puts, offsets -2..+2, summed over the 5 bars) / (the mean of the same 5-minute window over the previous 20 non-expiry sessions of that index, at least 10 needed) |

**OI with price at the break (option OI; futures OI is not in the design window):**

| name | definition |
|---|---|
| `oi_chg15_pct` | % change of call + put OI at offsets -5..+5 from frow-15 to frow (chain slots carried forward within the day, R61's snapshot rule) |
| `bu15` | build-up code over the 15 minutes: index up and OI up = +2 (long build-up), up and OI down = +1 (short covering), down and OI down = -1 (long unwinding), down and OI up = -2 (short build-up), 0 if either is flat; x s |

**Order-flow proxies from minute bars:**

| name | definition |
|---|---|
| `delta30` | volume-weighted close location over rows frow-29 .. frow: sum(clv_i x v_i) / sum(v_i) x s, clv_i = ((c-l)-(h-c))/(h-l) of the index bar (0 if h = l), v_i = that minute's near-ATM option volume; NaN if sum(v_i) = 0 |
| `cdelta_day` | the same from 09:15 to frow (a cumulative-delta proxy) |
| `optvol_imb15` | (call volume - put volume) / (call + put volume), offsets -2..+2, rows frow-14 .. frow, x s |
| `absorb15` | log(volume of rows frow-14..frow / its 20-session same-window mean) - log(index range (max h - min l) of rows frow-14..frow / its 20-session same-window mean): high volume with a small range (absorption proxy); same baseline rule as `vol_brk_rel` |

Signed features (x s): `brk_clv`, `bu15`, `delta30`, `cdelta_day`, `optvol_imb15`. "Positive" then means "on the
trade's side". The 20-session baselines exclude the index's own expiry days (the WEEK file then holds a 0-DTE contract
whose volume is not comparable) and use only sessions strictly before the day.

Missing values stay missing: logistic models impute the training median, trees route them; filters treat a missing
flag as "do not skip".

## 4. Variants: 8, one BH family (V1 + 7 new)

All family-A models use **A2's take rule**: each day, candidates in time order (same minute: highest score first); the
first whose score is at least the 70th percentile of that fold's training scores is bought, 1 lot; then nothing more
that day.

**(a) Skip filters on V1** (V1's own model and scores, unchanged; a skipped candidate is removed before the take rule,
so a later candidate that day may be taken instead). Thresholds are fixed here, never tuned:

| id | skip the candidate when |
|---|---|
| F1 | a same-side break already failed today: `trap_same_or` = 1 or `trap_same_pd` = 1 |
| F2 | the signal bar has a rejection wick of half its range or more: `brk_wick` >= 0.5 |
| F3 | the break came on below-normal volume: `vol_brk_rel` < 0 |
| F4 | F1 or F2 or `sweep_same30` = 1 (the price-only "trap" union) |

**(b) Meta-labeller with the TR block:**

| id | model | inputs |
|---|---|---|
| V1 | logistic C 0.1 (= A2 refitted; the comparator) | A2's 29 |
| M1 | logistic C 0.1 | A2's 29 + TR (15) |
| M2 | boosted trees, R61 V5 settings (depth 3, 150 trees, lr 0.03, leaf 80, l2 5, random_state 61) | A2's 29 + TR (15) |
| M3 | logistic C 0.1 | A2's 29 + the 8 price-only TR inputs (`trap_*`, `sweep_*`, `brk_wick`, `brk_clv`): the version the phone can run with no chain |

**Ablation (reported, not gated):** with vs without the block on the same days: V1 vs M1 (logistic) and T0 vs M2
(T0 = the same trees on A2's 29 only, a reference); M1 with one TR group dropped at a time (groups: traps, sweeps,
break bar, break volume, OI at break, delta proxies, option-volume imbalance, absorption) and each TR input added alone
to A2's 29 (change in walk-forward AUC and net, design and final). Also descriptive, design only: how often each flag
fires per setup, and the held-contract win rate / mean net of candidates with the flag on vs off (with counts).

## 5. Reports

Per variant, design walk-forward and final: trades, win rate, net, per trade, net at 2x costs, max drawdown, months
green, top-5 share, net without MIDCPNIFTY and MIDCPNIFTY's share of net, per index, per setup; AUC (random split,
walk-forward, final) for M1-M3; 500 random-entry twins (seed 61, R57 / R61 rules) for p; BH over the 8 final p values.

## 6. Gates (as R47-R61, never relaxed)

Final net > 0; twin p < 0.05; BH q <= 0.10; 40+ final trades; net without its top 5 > 0 (top-5 < 100% of net); design
walk-forward net > 0.

## 7. Replacing Solo 2.0 (passing the gates is not enough)

The candidate is the variant among F1-F4 and M1-M3 that passes every gate with the highest **design walk-forward net**.
It replaces Solo 2.0 only if, on the same final sessions, all of these hold against V1:
1. final net > V1's final net;
2. final AUC > V1's (for F1-F4, which keep V1's scores, the AUC of V1's scores on the candidates the filter keeps);
3. paired day bootstrap (10,000 resamples of the final sessions, daily P&L candidate minus V1): one-sided p < 0.10;
4. net without MIDCPNIFTY > V1's.
Otherwise Solo 2.0 stays and R61b's checks stay research notes. Either way Solo stays paper only, and anything that
would ever reach live starts in shadow behind a switch that is off.

## 8. Export

Any variant that passes the gates is exported: model JSON (R57 format) + 25 golden vectors, and `r61beval.py`, a
pure-Python reference (no numpy, file or network) with every TR feature formula and the scorer, parity-tested against
the research code so a Kotlin port can be checked line by line.

## 9. Not changed after this commit

Window, split, setups, labels, the feature list and definitions (N = 15, 30-minute sweep window, 20-session baselines),
the filter thresholds, the 8 variants and their settings, the take rule, twins, gates and the replace rule. A bug fix
after results is written up as an amendment with its effect.
