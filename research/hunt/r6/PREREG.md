# R6 pre-registration: R4 (Gann / Fibonacci / "magic numbers" / harmonics) repeated on MCX commodities

Written 9 Oct 2026 (about 13:40 UTC), BEFORE any R6 result (P&L or level statistic) was computed. Pipeline smoke tests
printed request and trade counts and a few individual fills only (to check strikes and prices), no aggregate P&L and
no level statistics. Nothing below changes after results are seen; anything added later is labelled POST-HOC.

## Data (what exists; checked before writing this)

- Dhan serves no expired MCX futures, and MCX rollingoption starts 5 Aug 2025 (R3, M3). So the only continuous MCX
  near-month series is the rollingoption `spot` (near-month futures, ONE price per minute, close only), 5 Aug 2025 -
  9 Oct 2026, for CRUDEOIL (STRAD-CRUDE cache), NATURALGAS, NATGASMINI, GOLD, GOLDM, SILVER, SILVERM (M3 cache), plus
  a top-up fetched today for 8-9 Oct (`research/hunt/r6/fetch_tail.py`; GOLDM tail from R3).
- Options: the same files, ATM-3..ATM+3 CE/PE near-month, 1-minute OHLC + volume + OI, 5 Aug 2025 - 9 Oct 2026.
- CRUDEOILM and NATGASMINI futures track CRUDEOIL / NATURALGAS of the same month (same price). SILVERMIC trades the
  SILVERM months; GOLDPETAL is quoted per gram (= GOLDM per-10 g price / 10). The futures books therefore use the
  CRUDEOIL / NATURALGAS / SILVERM / GOLDM near-month series with the mini/micro lot multipliers (labelled).
- World proxies (level tests only, USD levels, MCX hours in IST, labelled PROXY): XAUUSD 1-min 2015 - Sep 2026
  (R3), XAGUSD 2018 - 2025 and WTIUSD 2018 - Nov 2023 (histdata.com, `fetch_hd.py`). No natural-gas proxy exists
  locally.
- Close-only minutes: bar high/low are built from minute closes; a level "touch" is a minute close at or through it.
- The first session of each new contract (the day after an option expiry, when the near-month series rolls) is
  dropped from every test (its previous-day levels belong to another contract, and bars jump at the roll).

## Periods

- DESIGN = 5 Aug - 30 Sep 2025 (about 40 MCX sessions). **This is thin**: it is all the MCX data before October.
- HOLDOUT = 1 Oct 2025 - 8 Oct 2026 (about 250 sessions), opened ONCE by `design.py holdout` / `placebo6.py holdout`
  after `design.py design` writes `frozen.json` (a marker file blocks a second trading-holdout run).
- PROXY design = everything before 1 Oct 2025; PROXY holdout = 1 Oct 2025 onwards where it exists (XAU, XAG).

## Frame (fixed)

- Rs 1,00,000, FIXED 1 lot, no compounding, one position at a time per variant, at most 3 trades a day.
- (a) OPTION books: CRUDEOIL (lot 100 bbl), NATURALGAS (1250), NATGASMINI (250), GOLDM (Rs/10 g x 10), SILVERM (5 kg).
  1-ITM near-month strike (Dhan's ATM-1 call / ATM+1 put at the signal minute), long signal = CE, short = PE, bought
  at the OPEN of the first PRINTED (volume > 0) option minute in s+1..s+3. Exits on the option's printed 1-minute
  high/low (stop first on a tie); index exits at the open of the first printed minute at/after the exit column
  (within 10 minutes, else the last printed close). Everything out at 23:15 IST. **Option-expiry days skipped.**
  - Costs: McxCosts.kt (Zerodha) on each leg + half the bid-ask spread on each side: full spread before 17:00 IST /
    after 17:00: CRUDEOIL and NATURALGAS 0.60% / 0.30%; GOLDM, SILVERM 0.80% / 0.40%; NATGASMINI 0.80% / 0.40%
    (thin book, not measured: assumed like GOLDM). Each side pays max(half-spread, 5 bps); stop exits +5 bps.
    `scratchpad/hunt/m3/work/spreads.json` is still the placeholder (no measured non-crude values exist).
- (b) FUTURES books (both directions): CRUDEOILM (10 bbl), NATGASMINI (250 mmBtu), SILVERMIC (1 kg), GOLDPETAL (1 g).
  Fill = the futures close of minute s+1; exits on minute closes; cost = 0.03% of notional per round trip (half each
  side) + Zerodha futures charges (McxCosts.kt). GOLDM / SILVERM futures need Rs 1.6-2.7 lakh margin and are not
  traded; GOLDPETAL / SILVERMIC results scale linearly to them (information).
- Signal minutes 09:05 - 22:45 IST; flat by 23:15.

## Exits (each its own variant)

- NAT = the rule's own futures-price stop / target (from the source), else none, out 23:15.
- OPT = options: premium stop -25%, target +50%. Futures book: the same row is "PCT": futures stop -0.4%,
  target +0.8% (about what -25% / +50% of a 1-ITM monthly premium means for the underlying).
- T30 (and T60 for time rules) = out after 30 / 60 minutes.

## Signals (R4's rules; MCX translation)

- M1 SQ9I: grid (floor(sqrt(ref)) - 2 + 0.125 i)^2; ref in {09:25 close (from 09:26), 09:00 open, prev close,
  **17:00 close (from 17:01, evening open)**}; first 1-min close >= buy_above -> CE, <= sell_below -> PE; NATk1 / NATk3.
- M2 SQ9H: step 0.25; ref in {09:25, prev close, 17:00}; NATk1.
- M3 RMAG (magnet): Rs grids per contract: CRUDEOIL 100 (minor) / 500 (major); NATURALGAS 10 / 50; GOLDM 500 / 1000;
  SILVERM 1000 / 5000. 5-min close 0.05-0.15% from the level -> trade toward it (once per level per day).
- M4 RFADE: touch-and-close-back fade at the "50s" and "00s": CRUDEOIL 50 / 100; NATURALGAS 5 / 10; GOLDM 100 / 500;
  SILVERM 500 / 1000. NAT: stop 0.10% beyond, target 0.20% back.
- M9 ORFIB: 09:00-09:14 range (or15) and the **17:00-17:14 evening range (or15eve)**; first 5-min close beyond ->
  follow; NAT stop = range middle, target 1.272 / 1.618 x range.
- G2 HILO tf 5/15/60; G3 SWING tf 15/60; G4 MID50 bnc/brk; G5 ANG 1x1 / 2x1 from the 09:00-09:29 range
  (unit = previous day's range / number of 5-min bars in the session); G6 TOPEN 45/90/180 min from 09:00 and from
  17:00 (|move| > 0.2%, fade), G6 TEXT anchor (later of the 09:00-09:59 high / low) + 90 / 144; G7 SEAS (Gann
  dates, fade the 5-day trend at 09:05).
- F1 FIBPIV brk/bnc; F2 PDRFIB brk/bnc; F3 golden pocket tf 3/5/15; F4 Upstox 61.8% 3-min; F6 Fibonacci time zones
  tf 5; F7 confluence of an F1/F2 level within 0.05% of the contract's "00s" grid.
- Harmonics tf 3/5/15/60 (Gartley, Bat, Alt Bat, Butterfly, Crab, Deep Crab, Shark, Cypher, 5-0, ABCD, ALL),
  ZigZag k = 2 ATR14, PRZ confirmation entry, NAT1 / NAT2 (0.382 / 0.618 AD).
- Bars anchored 09:00, continuous across days, built from minute closes.

## Placebo-level test (the main science)

R4's method unchanged (20 placebo draws a day; first touch per level per day after 09:05 to 23:00; outcome within
60 min: reversal if it retreats d before going d through, d in {0.10%, 0.25%}; touch rate within 0.5% of the open;
day-cluster bootstrap, B = 1000, one-sided claimed > placebo). Families per commodity (CRUDEOIL, NATURALGAS, GOLDM,
SILVERM, GOLD, SILVER; NATGASMINI = NATURALGAS prices, CRUDEOILM = CRUDEOIL):
- SQ9_125 / SQ9_25 (from prev close, placebo phase ~ U(0, step));
- RND_B ("50s") / RND_A ("00s") / RND_MAJ: CRUDEOIL 50 / 100 / 500; NATURALGAS 5 / 10 / 50; GOLD(M) 100 / 500 /
  1000; SILVER(M) 500 / 1000 / 5000 (Rs); placebo = grid + U(0, spacing);
- NUM9 (digital root 9 = multiples of 9 in the quoted price; NATURALGAS: multiples of 0.9, i.e. digital root 9 of the
  price in paise-free ticks);
- GANNPTS: open +- {45, 90, 144, 180, 360} x scale (CRUDEOIL 1, NATURALGAS 0.1, gold / silver 10), placebo common
  shift U(+-22.5 x scale);
- FIBPIV, PDRFIB, GQTR, SWFIB as R4.
- Time placebo: GANN_TOPEN (45/90/180 from 09:00), GANN_TOPEN_EVE (from 17:00), GANN_TEXT, FIB_TZ (R4's POST-HOC
  fix adopted in advance: a time-zone mark cannot score on its own anchor pivot), shifted +-10..30 min.
- PROXIES (USD): XAUUSD grids 10 / 50 / 100, Gann scale 0.1, NUM9 9; XAGUSD 0.25 / 0.5 / 1, scale 0.01, NUM9 0.09;
  WTIUSD 0.5 / 1 / 5, scale 0.1, NUM9 0.9.
- BH within each run (MCX design, MCX holdout, proxy).
- A level family "matters on MCX" only if: claimed > placebo with BH q < 0.05 in a design set (MCX design, OR the
  matching proxy design: gold <- XAU, silver <- XAG, crude <- WTI) AND p < 0.05 with the same sign in the MCX holdout
  for that commodity. Information only: MCX-holdout BH q < 0.05 on its own.

## Statistics and gates (trading)

- Per variant: trades, win%, Rs/day (all sessions in the period, 0 on no-trade days), Rs/trade, max drawdown,
  day-clustered t, green months. 10 random twins per trade (same day, random side, random signal minute within
  +-30 min clamped to 09:05-22:45, same exit type; index exits keep the holding time); p_rand one-sided Welch.
- BH over ALL design variants of all books (t vs 0) and separately on p_rand; White Reality Check (max-t, stationary
  bootstrap, block 5, B = 1000) per book over its design daily P&L matrix. Variants with < 30 design trades get p = 1.
- Holdout picks: per family per book, the design variant (>= 30 trades) with the highest daily t (any sign).
- PASS-A (R4's) = design Rs/day > 0 AND BH q < 0.10 AND design p_rand < 0.05 AND RC p < 0.10 AND holdout Rs/day > 0
  AND holdout p_rand < 0.10.
- Because design is thin, a pre-registered SECONDARY test (PASS-B): every variant is also run on the holdout, BH over
  all holdout variants, RC per book on the holdout; PASS-B = holdout Rs/day > 0 AND BH q < 0.05 AND holdout
  p_rand < 0.05 AND holdout RC p < 0.10 AND design Rs/day > 0 (same sign in design). PASS-B alone = "paper-trade
  candidate", not "works".
- Margin check for anything that passes: premium (options) or exchange margin (futures) must fit Rs 1 lakh.
