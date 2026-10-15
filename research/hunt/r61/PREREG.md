# R61 pre-registration: "Solo 3.0" - does knowing OI, IV, gamma and the calendar make Solo pick better trades?

Written 15 Oct 2026, **before any R61 profit, loss or model score was computed** (no R61 data has been read yet).
Commit this file first; results come after. Research only: nothing here changes the app (no `android/**`, no
`.github/**`, no knowledge-pack rebuild). Solo is Jarvis's own trader and stays **paper only, forever**.

Boss, 15 Oct: "Make Solo more advanced, intelligent and smart; it should understand each and every thing - what OI is,
what gamma is, etc., their features, why and how they are used - so it can think on its own and trade."

Starting point: R57's **A2** (Solo 2.0, now in the app): a logistic meta-labeller over four index setups, final test
1 Apr - 14 Oct 2026 128 trades, +Rs 99,172, p 0.002, but walk-forward AUC only ~0.53 and 72% of the profit from
MIDCPNIFTY. Our own studies already found that option-chain, volatility and positioning data mostly predict the SIZE
of the next move, not its direction (`hunt/r61/CONCEPTS.md` lists every study). R61 asks one narrow question: **given
A2's candidates, does an expanded, pre-listed feature set that "knows" OI, PCR, max pain, OI walls, IV, skew, term
structure, VIX, Greeks, GEX, basis, value area, events and participant positioning pick the candidates better, out of
sample, than A2's 29 features?**

## 1. Data, window and split (as R57, unchanged)

- Drive lake `gdrive:IraAlgo-data-lake`: `index_1m` (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, INDIAVIX);
  `options_rolling_1m` **WEEK** files (offsets -10..+10, OI, IV, volume, spot). The **NIFTY MONTH** file is read only
  for the term-structure feature (NIFTY's monthly contract differs from its weekly one); for the other three indices
  WEEK and MONTH are the same contract, so MONTH is never read for them and nothing is counted twice. NSE
  participant-wise OI files (`participants/oi`, OF1's loader). Raw data and caches stay outside git and are deleted at
  the end.
- Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY (the paper account's NSE F&O indices).
- Boss's data rule: **only 20 Nov 2024 onwards** (first session 21 Nov). Index minutes from 1 Sep 2024 only warm up
  indicators; nothing before 20 Nov 2024 is traded, labelled, ranked or trained on (rank features need their own
  history from 20 Nov 2024 and are missing until they have it).
- **DESIGN: 21 Nov 2024 - 31 Mar 2026**, purged walk-forward; test months Jul 2025 - Mar 2026 (9 folds).
- **FINAL: 1 Apr 2026 - the lake's last session, read ONCE** by `r61study.py final` (guard file `results/FINAL_READ`;
  the harness refuses to label or price a final-test session before that).
- **Honest note: this final window is NOT fresh.** R57 read it for A2 with the same four setups (and R47-R55 read parts
  of it). R61's final test therefore measures whether the extra features add anything on a window where the A2 result
  is already known; only live paper from 15 Oct 2026 is truly unseen. The write-up says so next to every final number.

## 2. Measurement (never relaxed; R57's code reused unchanged)

- Candidates and labels are R57's: `hunt/r57/r57setups.py` S1-S4 on the four indices, each priced on its **HELD
  contract** by R51's engine (next-minute fills, R17 liq spread and bps doubled on stops, dated charges with STT 0.15%
  from 1 Apr 2026, lots in force, 1 lot). Label = win (net > 0).
- Cleaning before any contract or chain feature is built (R57 `r57data`): rows with |strike - spot| > 12 steps; R47
  foreign rows (spot > 3% off the index close at that minute); `spikes.py` one-minute bad ticks (a close > 50% away from
  both neighbours that agree). The chain features below are computed from the same cleaned rows.
- Expiry days: Solo never trades an index on its own expiry day (candidates on it are dropped).
- No WEEK+MONTH double counting (above).

## 3. Candidate setups (exactly R57's S1-S4; C4 is NOT used)

| id | setup | signal source | strike | exits |
|---|---|---|---|---|
| S1 | SoloMidday at 12:00 | index 1-min | 4-ITM | index stop 0.3 ATR14, breakeven at 0.45 ATR, 14:30 |
| S2 | R51 Donchian-20-RT | index 5-min | 1-ITM | -15% / +30%, 30 min, 15:15, max 2 a side a day |
| S3 | R52 H37-10, open outside the previous day's 70% value area | index 5-min + previous day's TPO profile | 1-ITM | 60 min + Solo layer (-30%, +10% locked at +40%) |
| S4 | 15-min opening-range break | index 5-min | 1-ITM | -25% / +50%, 60 min, + lock |

**Every entry signal is read from the underlying index.** The new features are filters/context around that signal:
they can only make Solo skip a candidate or prefer one index's candidate; they never create a trade or choose a side.

## 4. Features (all read at the signal row = the bar that closes at the fill minute's open; known before the trade)

**A2's 29** (R57 PREREG section 4, `r57model.names_a()`): 12 brain index features, `atr14d_pct`, `minute`, `dte`,
`vix`, `vix_chg_pct`, `pcr_oi`, `iv_atm`, `iv_skew`, 4 index one-hots, `side`, 4 setup one-hots (directional ones
signed by side, as in R57).

**R61 additions (30), in three pre-listed blocks.** "Chain snapshot" = the cleaned WEEK option rows of the index at
that minute, each (side, offset) slot carried forward from its latest minute at or before the row (point in time).
Offsets ±5 = the 11 strikes nearest the money; ±10 = all 21. Signed (x side, "s") features are multiplied by +1 for a
call candidate and -1 for a put candidate, so that "positive" always means "on the trade's side".

Block **CH - option chain** (context/filters only; the phone computes all of them from the live chain, `ChainAnalytics`):

| name | definition | s |
|---|---|---|
| `oi_near_log` | log10(1 + call OI + put OI at offsets -2..+2) | |
| `oi_near_chg_pct` | % change of call + put OI (offsets ±5) from the day's first chain minute to the row | |
| `writer_tilt30` | % change of put OI minus % change of call OI (offsets ±5) over the last 30 minutes (or since the first chain minute if fewer) | s |
| `buildup` | build-up code from the last 30 min: index up & near OI (±5) up = +2 (long build-up), up & OI down = +1 (short covering), down & OI down = -1 (long unwinding), down & OI up = -2 (short build-up), 0 if either is flat | s |
| `pcr_oi_chg30` | put/call OI ratio (±5) now minus 30 minutes earlier | s |
| `pcr_vol` | put volume / call volume (±5), summed from 09:15 to the row | s |
| `pcr_rank5`, `pcr_rank20` | share of the previous 5 / 20 sessions' `pcr_oi` at the SAME minute that are below today's (needs 4 / 15) | s |
| `maxpain_dist_pct` | (max-pain strike - index) / index x 100, max pain over the ±10 chain (strike minimising the total payout to OI holders) | s |
| `wall_ahead_pct` | distance to the biggest OI wall on the trade's way, % of index: call trade = (max call-OI strike at or above the index - index), put trade = (index - max put-OI strike at or below the index); NaN if none in the ±10 chain | |
| `wall_behind_pct` | the same for the other side's wall (call trade: index - max put-OI strike below; put trade: max call-OI strike above - index) | |
| `iv_rank60` | (ATM IV - min) / (max - min) of the previous 60 sessions' ATM IV at the same minute (needs 40) | |
| `iv_chg_day` | ATM IV now minus at the day's first chain minute (IV points) | |
| `smile_curv` | (put IV at -3 + call IV at +3) / 2 - ATM IV | |
| `term_slope` | NIFTY only: MONTH-file ATM IV minus WEEK-file ATM IV (0 when both are the same contract; NaN for the other indices) | |
| `gex_norm` | net gamma exposure / gross: sum over ±10 strikes of (gamma_C x OI_C - gamma_P x OI_P) / sum of (gamma_C x OI_C + gamma_P x OI_P); Black-Scholes gamma from each row's own IV, r = 6.5%, T = (sessions to expiry + rest of today / 375) / 252 years; the "dealers long calls, short puts" sign convention (R28) | |
| `gex_sign` | sign of `gex_norm` (+1 / -1 / 0) | |
| `flip_dist_pct` | gamma-flip level: re-price the net GEX at hypothetical index levels -3%..+3% (0.1% steps, same IVs and OI); the nearest level where it changes sign, linearly interpolated, minus the index, % of index; NaN if no sign change | s |
| `synth_basis_bps` | synthetic forward from put-call parity at the ATM strike: (K + call - put - index) / index x 10,000 (the futures premium the phone can compute without futures minutes, which the lake has only from Jul 2026) | |

Block **GK - Greeks of the candidate's own held contract** (the strike the setup would buy; phone: `ChainAnalytics`
Greeks from the contract's own IV):

| name | definition |
|---|---|
| `delta_abs` | |Black-Scholes delta| |
| `gamma_1pct` | change in delta for a 1% index move (gamma x index x 0.01) |
| `theta_pct_day` | theta per calendar day / premium x 100 (negative) |
| `vega_pct` | premium change for +1 IV point / premium x 100 |

Block **CX - context known before the trade** (phone source in brackets):

| name | definition |
|---|---|
| `vix_rank60` | share of the previous 60 sessions' VIX at the same minute below today's (needs 40) [VIX stream + own history] |
| `va_pos` | (index - previous day's POC) / (VAH - VAL), signed by side (previous day's 30-min TPO 70% value area, R57 `prev_value_area`) [index minutes] |
| `event_rbi_budget` | 1 on an RBI policy day or Union Budget day (pre-listed research calendar, `r61events.py`: the RBI and Budget lists of `obuy/strategies/gc_common.py`) [EventCalendar.kt] |
| `post_us_event` | 1 on the first NSE session after a US FOMC decision or US CPI release (dates as listed in `hunt/strad_crypto/events.py`; both are released after the Indian close) [needs a bundled static list on the phone] |
| `other_expiry_today` | 1 when another of the four indices has its expiry today [expiry calendar] |
| `fii_fut_long_share` | FII index-futures long / (long + short) from the PREVIOUS session's NSE participant-wise OI file (published after 18:30) [MarketRecorder records it daily] |
| `fii_fut_long_chg` | its change from the session before that |

Calendar lists are frozen as committed with this file; no date is added after a result is seen. Where the app's own
EventCalendar differs (RBI Aug 2026: research list 7 Aug, app 6 Aug) the research list is used and the gap reported.

Not used as model inputs, and why (CONCEPTS.md has the details): real futures basis and futures build-up (index
futures minutes only from Jul 2026), rollover (not built), DII cash flows (not reachable), VWAP of the index (the index
has no volume; A2's `dist_tpavg_pct` is the typical-price proxy already in), 25-delta skew (the ±3-strike skew is used
instead; deltas of far strikes are noisy on the ±10 chain), India CPI (not in a pre-listed calendar).

Missing values (no chain minute yet, rank without history, NaN by definition) are left missing; the models impute the
training median (logistic) or route them (trees).

## 5. Models: 7 pre-registered variants, one BH family

All are family-A meta-labellers on S1-S4 with **A2's take rule**: each day, candidates in time order (same minute:
highest score first); the first whose score is at least the 70th percentile of that fold's training scores is bought,
1 lot; then nothing more that day. Purged walk-forward with monthly refits, 1-session embargo (R57 `walk`). Final test
continues the monthly refits (training on everything before each month, minus the embargo).

| id | model | features |
|---|---|---|
| V1 | logistic C 0.1 (= A2 refitted here; the comparator) | A2's 29 |
| V2 | logistic C 0.1 | A2 + CH + GK + CX (59) |
| V3 | logistic C 0.1 | A2 + GK + CX (no option-chain block) |
| V4 | logistic C 0.1 | A2 + CH |
| V5 | boosted trees, depth 3, 150 trees, lr 0.03, leaf 80, l2 5 | A2 + CH + GK + CX |
| V6 | the same trees | A2 + GK + CX (no option-chain block) |
| V7 | L1 logistic (saga, C 0.05; sparse: picks its own concepts) | A2 + CH + GK + CX |

Logistic: median-imputed, standardised (as A2). Trees: scikit-learn HistGradientBoostingClassifier, no early
stopping, random_state 61.

## 6. Reports

- **AUC on held-contract labels** (win/lose): random 5-fold (leaks), design walk-forward, final.
- **What each concept adds** (reported, not gated): (a) ablation - V2 refitted with one concept group removed at a
  time (groups: OI level/change, build-up, PCR, max pain, OI walls, IV level/rank, skew/smile/term, GEX/flip, basis,
  Greeks, VIX rank, value area, events, other expiry, participants) - change in walk-forward AUC and net, design and
  final; (b) each new feature alone added to A2's 29 (logistic), walk-forward AUC change in design; (c) the V7 L1
  model's non-zero coefficients; (d) V2 vs V3 and V5 vs V6 = the option-chain block as a whole.
- Per variant, design WF and final: trades, win rate, net, per trade, max DD, worst day, months green, top-5 share,
  2x costs, per index, per setup.
- Random-entry twins (500, seed 61, as R57: same day and index, entry minute uniform 09:20-14:00, side by coin flip,
  same strike rule and exits); selection twins (reported, not gated). BH over the 7 final p values.

## 7. Gates (as R47-R60)

Final net > 0; twin p < 0.05; BH q <= 0.10; 40+ final trades; net without its top 5 > 0 (top-5 < 100% of net);
design walk-forward net > 0.

## 8. Replacing Solo 2.0 (pre-registered; passing the gates is not enough)

The Solo 3.0 candidate is the variant among V2-V7 that passes every gate and has the highest **design walk-forward
net** (so the pick does not use the final numbers beyond the gates). It replaces Solo 2.0 only if, on the same final
sessions, all of these hold against V1:
1. final net > V1's final net;
2. final AUC > V1's final AUC;
3. paired day bootstrap (10,000 resamples of the final sessions, daily P&L of candidate minus V1): one-sided p < 0.10;
4. net without MIDCPNIFTY > V1's net without MIDCPNIFTY (it must not just lean harder on one index).
Otherwise Solo 2.0 stays and R61's models stay research notes. Either way Solo stays paper only.

## 9. On-device export (only a model that replaces Solo 2.0 would ever be ported)

Small JSON (R57 `export` format) + 25 golden vectors + `r61eval.py`, a pure-Python reference scorer **and** pure-Python
formulas for the chain features (max pain, walls, PCR, GEX and flip, Greeks, synthetic basis), parity-tested against
the numpy research code, so a Kotlin port can be checked feature by feature. Nothing on the tick or order path touches
disk or the network: the JSON is loaded once at start-up.

## 10. Not changed after this commit

Window, split, setups, labels, the feature list and definitions, the calendars, the 7 variants and their settings,
the take rule, twins, gates and the replace rule. A bug fix after results is written up as an amendment with its effect.

## 11. Implementation details fixed in the code before any data was read (15 Oct 2026, still no R61 data or result)

Written after section 1-10 were committed (701b8c18) and before the lake was read; they make the definitions above
exact, they do not change them:
- Signed ratio and rank features are centred before the side sign is applied, so "neutral" is 0 for both sides:
  `pcr_vol` - 1, `pcr_rank5` - 0.5, `pcr_rank20` - 0.5.
- `theta_pct_day` is theta per SESSION (1/252 year, the same convention as T), not per calendar day.
- V7's L1 logistic is scikit-learn's elastic net with l1_ratio 1 (identical to pure L1, and the same in scikit-learn
  1.5 and 1.9).
- The chain snapshot carries each (side, offset) slot forward whole (strike with its OI, IV and close); volume is never
  carried forward. The "first chain minute" is the first minute with call + put OI at the 11 near strikes.
- Ties: max pain takes the lowest strike; an OI wall takes the strike nearest the index.
- Same-minute ranks use only sessions from 20 Nov 2024 and strictly before the day.
- Participant OI: the latest NSE file dated before the session (OF1's loader and checks); missing files leave the two
  features missing.
- Code: `r61cfg.py`, `r61chain.py`, `r61data.py`, `r61feat.py`, `r61study.py`, `r61eval.py`, `r61events.py`; tests
  `test_r61.py` (synthetic data only).
