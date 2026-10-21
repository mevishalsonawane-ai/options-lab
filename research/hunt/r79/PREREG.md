# R79 pre-registration: direction filters on Liquidity 15+5 (Supertrend, a 10-candle wait, cross-index breadth)

Written 18 Oct 2026, **before any R79 result was computed**. Research only (no `android/**`). Any app change is Boss's
decision, made in the parent session.

Boss, 18 Oct: *"For liquidity strategy should we use Supertrend or wait for 10 candles for getting accurate direction?
Or like Breadth Donchian, should we check all the other indices like BANKNIFTY, SENSEX and NIFTY at once to find the
best direction?"*

## 0. Priors (stated before any number)

- `research/LIQUIDITY_WAIT5.md`: a standalone "find / break a level, wait 5 candles, read the direction, buy" rule lost
  money in every reading (and so did its ordinary-candle control). That was a different rule (no Liquidity target, no
  Liquidity exits), but it says a short wait does not reveal the direction by itself.
- `research/FINDINGS.md`: breadth (the other indices / heavyweights) does not call the next 5-30 minutes.
- R73: Supertrend(10,3) on 5-minute bars (T7) passed as a filter on Breadth Donchian, a trend-following arm. Liquidity
  is a level-break arm with its own index target, so that result need not carry over.
- Expectation before running: most likely no variant passes.

## 1. Arm (exactly as R76 ran it)

Liquidity 15+5, `hunt/r51/fam_liq.py make(und, books)` on R51's Amendment A clean stores (`R51_CLEAN=1`), exactly as
R63 / R70 / R76: BANKNIFTY 15 + 5, FINNIFTY 30 + 5 (parked), MIDCPNIFTY 15 + 5 (index stop 8 points); 1-ITM; one
position per book (`common.take`, scope "all"); expiry days skipped. Entry window: the signal's entry row 09:20-14:00.

Note: the task text says "each index the arm trades" as BANKNIFTY / NIFTY / SENSEX. The harness the app's numbers come
from (R63 / R70 / R76) trades BANKNIFTY, FINNIFTY and MIDCPNIFTY, so R79 filters those three books. NIFTY and SENSEX
enter only as breadth context in X1 / X2.

- **Signal source (every variant):** the index. Liquidity's level breaks (swing + pool zones) on 15-, 30- and 5-minute
  index bars, unchanged. Every filter below also reads **index** bars only (the traded index, or the other indices for
  X1 / X2). No option-chart signal anywhere. The option (1-ITM) is bought.
- **Exits (all variants):** Liquidity's own: -15% premium stop; 20-minute time stop unless +5%; the index exits (index
  stop, next-level target, failed break, new level); 15:10. Unchanged.

## 2. Variants (B0 + 6 tests)

Filters sit on top of B0. A skipped signal frees the book's slot, so a later signal of the same book can be taken (the
arm walks its candidates in time order through `common.take`). Each variant's trades are therefore made afresh through
`fam_liq` with the filter, not by deleting B0 trades.

| id | rule (all on index bars) |
|---|---|
| **B0** | Liquidity 15+5 as now (plain rules) |
| **S1** | take only when Supertrend(10, 3) on the index's 5-minute bars agrees with the side (+1 for a call, -1 for a put) |
| **S2** | the same with Supertrend(10, 3) on the index's 15-minute bars |
| **W10a** | wait 10 one-minute candles after the signal; enter only if the 10th closes beyond the signal close on the trade's side; entry at the next minute's open |
| **W10b** | the same with 10 five-minute candles (50 minutes) |
| **X1** | take only when at least 2 of the OTHER indices (of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX) have their own 5-minute Supertrend(10, 3) agreeing with the side |
| **X2** | the same with at least 3 others |

Definitions (fixed now):

- **5-minute bars:** the continuous series of every session's 09:15-anchored 5-minute index bars (`fam_r44.bars5`
  logic: O = first minute's open, H / L = max / min, C = last minute's close; a 5-minute slot with no minute is skipped),
  from 1 Sep 2024 (warm-up), across days. **15-minute bars:** the same with 15-minute slots.
- **Supertrend(10, 3):** `hunt/r73/r73rules.py supertrend_dir` (Wilder ATR 10, hl2 bands x 3; 0 until the ATR exists),
  the same function R73 T7 used.
- **Which bar is read:** the signal's entry row `er` is the minute right after the breaking bar closed. The filter reads
  the last bar of the series that is **fully closed at the start of row `er`**: the same day with slot end <= `er`, else
  any earlier day. A direction of 0 (warm-up) fails.
- **X1 / X2:** for each other index, its own 5-minute Supertrend at its last 5-minute bar closed at the start of row
  `er` **on the same day**. An index with no bar that day before `er` counts as "not agreeing" (missing data never
  helps). FINNIFTY signals have 4 others; so do BANKNIFTY and MIDCPNIFTY.
- **W10a:** the candles are rows `er .. er+9`; all 10 must exist (a missing minute drops the signal, as R73's waits);
  the 10th close `c[er+9]` must satisfy `sgn * (c[er+9] - close) > 0`, where `close` is the signal bar's close. Entry
  row `er + 10`.
- **W10b:** the candles are the 10 five-minute slots that start at row `er` (rows `er .. er+49`); every slot must have
  at least one minute; the last minute's close of the 10th slot (the last non-missing close in rows `er+45 .. er+49`)
  must be beyond the signal close on the trade's side. Entry row `er + 50`.
- **Waits, both:** the entry row after the wait must still be inside the arm's entry window (<= 14:00, `fam_liq.LAST`)
  and before the trade's index exit: if Liquidity's index exit row (`forced_row`: index stop, target, failed break, new
  level) is at or before the new entry row, the signal is dropped (the trade would already be over). The index exit row
  itself is unchanged (it is computed from the signal, so it is the same row). The strike is the arm's rule at the new
  entry: 1-ITM from the 10th candle's close (`atm(close) - side * step`). The 20-minute time stop counts from the new
  fill. During a wait the book is not marked busy (a different signal may be taken); after it, `common.take` handles
  the slot as usual.

## 3. Measurement, periods, gates, decision

R51 / R64 engine through `fam_liq` (held contract, R17 liquidity fills, next-minute fills, stops on the minute low with
gap fills at the worse open, `Costs('dated')`, 1 lot). The study asserts that its B0 equals R76's B0 (881 design trades,
Rs 2,57,249; 357 final, Rs 11,155) before reporting.

- **Periods:** design 20 Nov 2024 - 31 Mar 2026, with the walk-forward quarters Apr 2025 - Mar 2026 (walk-forward =
  their sum: the rules are fixed, nothing is fitted, so walk-forward is simply the out-of-sample later year of design);
  final 1 Apr - 13 Oct 2026, read once (`results/FINAL_READ`). The final period was already read by R51, R63, R70, R76
  and others for this arm, so it is **weak evidence**.
- **Reported per variant and period:** trades, net (1 lot), win %, avg win / avg loss, max drawdown, worst day, net
  with the top 5 trades removed, per index; what was skipped (count, net of the skipped B0 trades, losers avoided,
  winners missed) and what was newly taken.
- **p:** a paired one-sided day bootstrap (2,000 resamples, seed 79) of variant - B0 on the **final** period (share of
  resampled mean daily differences <= 0). **BH** across the 6 tests (S1, S2, W10a, W10b, X1, X2). The design-period
  bootstrap is reported for information.
- **Random-skip p:** 500 twins (seed 79), final period: each twin drops k = (B0 trades - variant trades) B0 trades at
  random (k <= 0: none dropped) (`hunt/r73/r73study.py skip_twins`); p = (1 + twins >= variant net) / 501.
- **Verdict PASS** only if the variant's net beats B0's in walk-forward AND in final, AND its BH q < 0.10 AND its
  random-skip p < 0.10. Otherwise FAIL. Design is reported but is not a gate (the rules have no fitted parameter).
- **Sample sizes** (trades, days) are given with every number.

Liquidity BANKNIFTY is the 26 Oct go-live candidate. A passing change would go on paper only, labelled, logged and
undoable in one tap, so its plain-rules record stays separable (CLAUDE.md). R79 builds nothing in the app.

## 4. Look-ahead guard (tests in `test_r79.py`)

- The Supertrend read at row `er` changes not at all if every index minute at or after `er` is altered.
- The waits read only rows `er .. er+N-1` (N = 10 or 50); changing rows at or after the entry row does not change the
  decision.
- X1 / X2 read the other indices only before `er` on the same day.
- The final period is opened once (`r79study.py final`), guarded by `results/FINAL_READ`; design runs cannot read it
  (R51's `common.check_range`).

## 5. Data

R51's Amendment A stores for BANKNIFTY, FINNIFTY, MIDCPNIFTY (`R51_CLEAN=1 r51data.py build`), from the Drive lake
(`index_1m`, `options_rolling_1m` WEEK), as R76. NIFTY and SENSEX index minutes from the same lake's `index_1m` files
(`r51data.index_days`, index only). Data lives outside git (`/dev/shm`); results/ holds small JSON only.

## Amendment 1 (18 Oct 2026, before any R79 result was computed): BANKNIFTY only, GitHub release data

Why: the Drive lake that R76 used could not be reached from this session (rclone was not available), and the repo's
`research-data` release has no index with option prices for the whole of Nov 2024 - Oct 2026. Boss, 18 Oct: *"Take 1
index whose all data is present"*; the closest is BANKNIFTY, chosen by Boss from the options offered. No R79 number of
any kind had been computed when this amendment was written. Everything above stays, except:

- **Index and data.** BANKNIFTY only (the 26 Oct go-live candidate), books 15 + 5, index stop 30 points, 1-ITM,
  Liquidity's own exits, expiry days skipped. Data: the `research-data` release, `banknifty_prev_year_wide.parquet` +
  `banknifty_year_wide.parquet` (index minutes and the nearest expiry after the day at strikes within 1,000 points of
  the 09:20 index; 311 sessions from 20 Nov 2024 to 23 Feb 2026, every one with options). Built into an R51-format day
  store by `r79data.py` (index O H L C on the 09:15 grid; each contract's own O H L C V; ATM = the index close rounded
  to 100; expiry days = the contracts' expiry dates seen in the data, with Jan 2025's move from 29 to 30 Jan as
  `brain/adapters/release.py` records it). The lake's cleaning step (Amendment A of R51) does not apply to this source;
  a strike outside the stored band means no trade (as with any missing contract).
- **Periods.** Design 20 Nov 2024 - 31 Dec 2025; walk-forward = the quarters Apr-Jun, Jul-Sep, Oct-Dec 2025 (summed);
  **final 1 Jan - 23 Feb 2026, read once** (`results/FINAL_READ`). This final is short (about 36 sessions) and was
  inside the design period of R51 / R63 / R70 / R76, so it is weak evidence. There is no Apr - Oct 2026 data here.
- **B0 check.** R76's exact B0 cannot be reproduced on a different source and window. Instead the study reports this
  B0 next to R76's BANKNIFTY figures, as a sanity check only.
- **Breadth (X1 / X2).** The release has index minutes for NIFTY (`nifty_prev` + `nifty_year`, IX rows), FINNIFTY
  (`finnifty_index`) and SENSEX (`sensex_index`), not MIDCPNIFTY. So the "others" are NIFTY, FINNIFTY, SENSEX:
  **X1 = at least 2 of the 3 agree, X2 = all 3 agree** (the "at least 3 others" of section 2 with 3 available).
- **Gates unchanged:** PASS only if the variant's net beats B0 in walk-forward AND final, BH q < 0.10 over the 6 tests
  (final-period paired day bootstrap) and random-skip p < 0.10. With about 36 final sessions a PASS is unlikely even
  for a good filter; that is stated now, before any number.
