# R53 MCX book: implementation notes and deviations from PREREG

Written 15 Oct 2026, after the design run and before the final-test read. Code: `mcx/data.py` (data, expiry days,
EIA calendar), `mcx/sim.py` (held-contract option and futures simulation, costs), `mcx/rules.py` (MA1-MA9),
`mcx/trend.py` (MA10), `mcx/run.py` (design / final runner), tests in `mcx/test_mcx.py`.

Run from the repo root with `IRAALGO_LAKE=<lake>` and `IRAALGO_CACHE=<cache with yahoo/h and xau>`:
`python -m research.hunt.r53.mcx.run design`, then once `python -m research.hunt.r53.mcx.run final`.

## Data, as used

- **Near future = per-minute median of the option rows' `spot`** (lake `options_rolling_1m`, 5 Aug 2025 - 12 Oct 2026).
  It has closes only. Every futures fill, stop and target uses minute closes; every 5m / 15m bar is built from minute
  closes (o = first close, h = max close, l = min close, c = last close).
- **Options:** one held contract (day, side, strike) per trade, printed minutes only (volume > 0), after R42's F1
  filter (`hunt/r42/spikes.py flag_spikes`, k 5, 15 min, ratio 1.5) on the printed rows, as R42 did for M4. F1 judges a
  print with up to 15 minutes of later prints of the same contract: this is data cleaning fixed by PREREG, not a signal
  input, but it is a small look-ahead in what counts as a price.
- **Expiry days:** M3's rule (ATM straddle jumps at a day boundary), made robust: median of a day's first 10 straddle
  values over the median of the previous day's last 10, > 2.5, strongest first, one per 15 days. Reason: M3's raw
  first/last version found a false GOLDM expiry on 8 Oct 2025 (a volatility jump) and missed March 2026 (a bad last
  print on the 26 Mar holiday session). The robust rule finds exactly one switch a month in all four books. The rolling
  series switches contracts at the next session's start, so a (day, side, strike) key never mixes two expiries.
- **Session end:** 23:30 (US summer time) / 23:55 (US winter time), as `McxSession.kt`; a short session ends with its
  data; stray bars after the close are ignored for the close. "10 minutes before the close" = the last minute at or
  before close - 11 (23:19 / 23:44 bar close).
- **Yahoo 1h** (CL=F, NG=F, GC=F, SI=F, INR=X) parsed with `json` only; price at a time = R2's `px_at` (open of the bar
  starting then, else the close of the last bar at most 2 h old).
- **XAUUSD** Dukascopy 1-minute bid closes, shifted to IST, forward-filled at most 2 grid minutes (R2 C4).
- **EIA (MA9):** Wednesday 10:30 ET, DST-exact (zoneinfo), official 2025-26 exceptions from
  `hunt/nn_crude/calendar_events.py` (eia.gov schedule). Weeks I could not verify against eia.gov this session and took
  as the normal Wednesday 10:30 ET: Thanksgiving week (26 Nov 2025) and New Year week (31 Dec 2025). The Christmas 2025
  release (29 Dec 17:00 ET = 03:30 IST) falls outside MCX hours, so no trade.
- **Proxies (PREREG):** SILVERMIC = SILVERM near future per kg x 1; GOLDTEN = GOLDM per 10 g x 1; NATGASMINI =
  NATURALGAS x 250; CRUDEOILM = CRUDEOIL x 10; MA1b GOLDM future = GOLDM x 10 (1 lot of 100 g).

## Deviations from PREREG or from the original study (all minimal)

1. **Futures fills are minute closes.** PREREG: "entry at the next bar's open". The spot series has no opens, so a
   futures entry is the close of the minute after the signal minute (one minute later than an open fill would be);
   futures stops fill at the close that crossed the level (that close is the "worse price" of a gap-through).
2. **MA2's 3% disaster stop reads minute closes**, not the minute's high/low wick as the app does (no highs/lows in the
   spot series). The app's entry from 09:05 is the close of the first minute at or after 09:05 (to 09:09).
   McxExpiry's "no new delivery future near expiry" refusal is not modelled beyond the roll-day skip (the proxy is the
   SILVERM options' underlying, which rolls at the option expiry).
3. **MA3 / MA4 enter one minute later than R2:** R2's `cand.py` filled at the 09:05 close (the signal minute); R53
   enters at the next minute's close (PREREG next-bar fills).
4. **MA4 CRUDEOILM exit:** R2's frozen crude cell exited at the close ("09:05 -> close"); PREREG's MA4 row says
   "exit 14:00" for all three books. PREREG is binding, so 14:00 is used.
5. **MA6 (R3):** the ATM strike is read at the signal minute (PREREG section 2); R3 read it at the entry minute. R3 built
   5m bars from Dhan futures OHLC; R53 has closes only, so EMA/ADX are on close-built bars. R3 sold at the last printed
   close at or before fill + 60 min; R53 uses the harness rule for every time exit (that minute's close if it printed,
   else the next print's open within 10 minutes, else the last printed close, marked stale). Expiry days are kept, as R3.
6. **MA7 (R6):** R6's costs (0.03% of notional round trip + charges) are replaced by R53's (PREREG). Everything else
   is R6's code path: 15-minute bars of the close-only panel, the swing state carried across days, signals on ok days
   (>= 300 real minutes, data to 22:00) that are not roll days, signal minute 09:05-22:45, fill at the next minute's
   close, NAT exit (close back through the swing stop, at least one minute after the fill) else 23:15, one at a time,
   at most 3 a day. Reproduction: on R6's design window (5 Aug - 30 Sep 2025) both sides give 48 trades and a gross of
   +Rs 32.77 a trade, exactly R6's `design_variants.csv` row (48 trades, +Rs 32.77).
7. **MA10 (trend):** the lake's continuous daily series is not back-adjusted. A roll row (open interest > 1.8x the day
   before, or volume < 5% of its 20-day median: R2's `load_mcx_daily`) books no overnight gap and charges a roll
   (close + reopen at that open). The app rolls 5 trading days before expiry; the series' own roll day is used. The
   "trade" is one leg-month (entry_ts = the month's first session): 4 legs x 16 design months = 64; positions are
   marked to market at a period's end, not closed. The final read carries the March 2026 position into April (as the
   rule would). The lake's daily series has a few missing rows; the 252-day lookback counts rows.
8. **Twins.** Range-break and swing rules' futures stops / targets depend on that day's range; a twin keeps the stop
   and target DISTANCES of a randomly chosen real trade of the same version, measured from the twin's own signal price
   (M3 `run.py randoms()`). Fixed-time rules (MA2, MA3, MA4) have a one-minute entry window, so their twins are random
   eligible days with a random side and the same exits. MA5's window is every session minute where the 5-minute
   catch-up is computable; MA6's is every 5-minute bar close in its entry window; MA7's is every minute 09:05-22:45 of
   ok days (one-way: long; the "both" variant two-way). MA10's twin flips a coin for the side of every leg-month the
   rule holds. Twin days are drawn from the same eligible days as the rule (option rules: no expiry days; gap rules:
   no roll days).
9. **One position at a time** inside each version (M3 `run_book`), with the per-day caps of the original rules
   (MA6 two a day, MA7 three a day, others one a day by construction).
10. **MA3 option -30% stop** never triggered in design (no trade's option low reached -30% before 14:00), so the
    `stop30` variant equals the original there; its twins differ.

## Reproduction checks inside the design window (no final-period data used)

| rule | old study, window | old | R53 code, same window |
|---|---|---|---|
| MA7 both sides (R6 G3_SWING tf15 NAT, SILVERMIC) | 5 Aug - 30 Sep 2025 | 48 tr, gross +Rs 32.77/tr | 48 tr, gross +Rs 32.77/tr (net differs only by cost book) |
| MA3_fut (R2 C1 NG, 09:05 -> 14:00) | 5 Aug - 30 Sep 2025, minute check | -Rs 120/tr | 36 tr, -Rs 133/tr (entry one minute later, R53 costs) |
| MA2 (R2 C2 silver, 09:05 -> close) | 5 Aug - 30 Sep 2025, minute check (31 d) | -Rs 246/tr | 33 tr, -Rs 321/tr (app's real close time, next-minute entry, 3% stop) |
| MA6 (R3 5m ATM AM) | Aug - Sep 2025 | 18 tr, -Rs 182/tr | 11 tr, -Rs 56/tr (close-built bars vs R3's futures OHLC; different data) |

Checks that need data after 31 Mar 2026 (M3's EVE design to 7 Jul 2026, R2's holdout) are run only after the final
read (`mcx/repro.py`, which refuses to run before `results/final_read_mcx.json` exists) -> `results/mcx_repro.json`.

## Added after the final read (15 Oct 2026): a bug found, and the reproduction checks

### BUG (found after the one final read; nothing was re-run)

- **MA5 was capped at one entry a day.** `rules.Rule.max_per_day = 1` is the default, and MA5 did not override it.
  R2's C4 (PREREG: "one at a time") has no daily cap. So MA5's design (55 trades) and final (48 trades) numbers, and
  its twins (the twins have no cap, so they are not affected), are for a "first signal of the day only" version, not
  the pre-registered rule. It was found while reproducing R2's holdout: on 1 Oct 2025 - 6 Oct 2026 there are 580
  one-at-a-time candidates, 310 of them priced, where the capped code took 95.
- The fix is one line (`max_per_day = 10**9` on `MA5`). It was NOT applied and the final was NOT re-run, because the
  final read is locked (instructions: stop and report). The code is left exactly as it produced
  `mcx_design.json` / `mcx_final.json`. Whether to fix MA5 and read it again (a logged amendment) is for the study
  owner. Every other rule's per-day cap was checked against PREREG: MA1/MA1b/MA1c/MA8/MA9 (range breaks) and
  MA2/MA3/MA4 (one 09:05 signal) take at most one a day by construction; MA6 two a day; MA7 three a day.

### Data note: GOLDM option prints

GOLDM's printed option minutes (09:30-12:00, all strikes) go from about 5,000-7,700 a month (Aug 2025 - Mar 2026) to
20,000-35,000 a month (Apr - Sep 2026). With the "print within 3 minutes or no trade" rule, MA8 took 45 of 143 design
signals but 115 of 124 final signals, and MA6 88 of 323 against 175 of 281. The design and final samples of the GOLDM
option rules are therefore not like for like.

### Reproduction on the old studies' own windows (after the final read; diagnostic only)

| check | window | old study | R53 code (lake data, R53 costs) | read |
|---|---|---|---|---|
| M3 EVE NG option | design 5 Aug 2025 - 7 Jul 2026 | 197 tr, gross +Rs 201.5/tr, net +47 | 197 tr, gross +Rs 201/tr, net +12 | gross exact; R53's NG spread (0.43%) costs more |
| M3 EVE NG option | holdout 8 Jul - 8 Oct 2026 | 55 tr, +93 | 56 tr, +134 | close |
| M3 EVE GOLDM future | design | 192 tr, +313 | 220 tr, -386 | the 192 shared trades are the same trades (same side; 157 identical fills and exits); the 28 extra lake days (Dec 2025 - Mar 2026, missing from M3's download) lost Rs 1.47 lakh gross |
| M3 EVE GOLDM future | holdout | 53 tr, +143 | 54 tr, +83 | close |
| M3 EVE CRUDEOIL option | design / holdout | 203 tr -633 / 60 tr +303 | 207 tr -657 / 60 tr +299 | reproduces |
| M3 INORB GOLDM option | design / holdout | 68 tr +110 / 58 tr -67 | 99 tr -315 / 59 tr -141 | more design trades (data holes in M3); the design "close" result does not survive the fuller data |
| M3 EVT EIA crude | design / holdout | 41 tr -361 / 14 tr +645 | 42 tr -401 / 14 tr +642 | reproduces |
| R2 C2 silver (app rule) | R2 holdout 1 Oct 2025 - 6 Oct 2026 | 166 tr, +1,189 | 195 tr, +1,078 | close (app's real close time, 3% stop, next-minute entry) |
| R2 C1 NG future | R2 holdout | 233 tr, +346 | 232 tr, +355 | reproduces |
| R2 C1 NG option | R2 holdout | 208 tr, +181 | 220 tr, +270 | close |
| R2 C3 GOLDTEN | R2 holdout | 123 tr, +126 | 143 tr, +54 | weaker (one-minute-later entry; R2 said it "dies with a 10-min delay") |
| R2 C3 NG option | R2 holdout | 116 tr, +262 | 128 tr, +89 | weaker |
| R2 C3 CRUDEOILM (R2's close exit) | R2 holdout | 141 tr, +274 | 142 tr, +266 | reproduces |
| R2 C4 XAU lead (MA5, capped: see BUG) | R2 holdout | 128 tr, +228 | 95 tr, -395 | not comparable (the cap; R2 also kept only signals where both the call and the put printed) |
| R3 GOLDM 5m ATM AM | 1 Oct 2025 - 8 Oct 2026 | 222 tr, -274 | 250 tr, -272 | reproduces |
| R6 SILVERMIC swing, both sides | R6 holdout 1 Oct 2025 - 8 Oct 2026 | 189 tr, gross +588/tr, +439/day | 242 tr, gross +269/tr, +146/day | code exact on R6's design window; the holdout gap is data (the lake has about 21% more SILVERM minutes than R6's files, as R42 found) |

### MA5u: the pre-registered MA5, added as a post-lock correction (15 Oct 2026, coordinator's decision)

- **MA5 stays as run**, labelled "MA5 as run: first signal a day only, a deviation". Its code (`max_per_day = 1`, the
  base default) and its rows in `mcx_design.json` / `mcx_final.json` are unchanged and still reproduce.
- **MA5u** (`rules.MA5u`, a subclass of MA5 with `max_per_day = 10**9`) is the pre-registered rule: one position at a
  time, no daily cap. Run with `run ma5u-design` (orig + hold30, 500 twins, walk-forward mcx=True) ->
  `results/mcx_design_ma5u.json`, then once `run ma5u-final` -> `results/mcx_final_ma5u.json`. Design pick: none
  (both variants lost), so the final read was `MA5u:orig` only, recorded in `final_read_mcx.json`.
- **This final read is a post-lock correction.** The capped MA5's final-test result had been seen before MA5u was
  read, so MA5u's final cannot count as a clean pass even if it clears every gate. (It also fails on its own: design
  walk-forward -Rs 1.20 lakh, and the top 5 trades are 216% of the final net.)
- Test: `test_ma5u_has_no_daily_cap_and_ma5_keeps_one`.
