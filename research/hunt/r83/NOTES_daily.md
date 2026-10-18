# R83 daily book (E5, E8, E9, E11, E12): conventions and deviations from PREREG

Written 18 Oct 2026, after the design run and before the final read (the final-read numbers are only in
`results/*_final.json`). Code: `daily.py`, `run_daily.py`; tests `test_daily.py` (15 tests, synthetic data, including a
look-ahead guard for Donchian, trend, OPEC and rollover rules).

Run: `R83_DATA=/dev/shm python -m research.hunt.r83.run_daily design`, then once `... run_daily final`.

## Conventions

- Decisions at a daily close using data up to that close; fills at the next day's open. Stops are checked on daily
  closes against a level fixed from data up to the previous close (initial stop uses the signal day's ATR14), and fill
  at that close with the 0.02% stop slippage. The design run used series cut at 31 Dec 2022 (no 2023+ row was loaded).
- MCX series: lake `fut_daily_continuous` (expiry code 0, not back-adjusted). Rupee P&L = price move x lot units
  (CRUDEOILM 10, NATGASMINI 250) via `common.fut_net` (McxCosts charges, half-spread each side, stop slippage).
- Rolls: per calendar month the row with the largest open-interest jump (> 1.15x) on or after day 10 (crude) / day 15
  (natural gas) is the first row of the next contract. A held position is closed at the previous close and re-opened at
  that row's open: the roll gap is not earned and each roll pays a full round trip. R53's rule (> 1.8x) found false rolls
  (e.g. 10 Mar 2026) and missed some (Jun 2019 has no OI jump: the expiry row is absent). With this rule crude misses
  5 of 178 months (2021-01, 2021-02, 2021-10, 2021-11, 2026-10) and natural gas 8 (2018-03, 2020-03, 2021-01, 2021-11,
  2021-12, 2023-05, 2025-03, 2026-10); in those months one roll gap is booked as P&L and no roll cost is paid.
- COPPER (proxy, labelled): Yahoo HG=F daily x 2.20462 x the previous day's USD/INR, lot 2,500 kg (MCX COPPER; one lot
  needs about Rs 3.3 lakh margin, so it does not fit Rs 1 lakh). From 2004 (USD/INR history starts Dec 2003). Yahoo's
  front-month series is not roll-adjusted; its roll gaps are not removed (small for copper).
- Twins: 200 sets; each trade re-run with the same side and holding rule from a random row of the same calendar year
  (E5: excluding OPEC signal days and the day after; E11: a leg-month piece of the same length on the same leg, no stop).

## Choices the plan left open (minimal)

1. **E5 OPEC:** dates are NN_CRUDE's approximate list (2014-2025, typed from memory, not verified). Signal row = the
   first MCX day strictly after the meeting date; side = sign of that day's close-to-close move (open-to-close on a roll
   row). Exit at the close of the 3rd / 5th held day, or the 2 x ATR stop.
2. **E8 Donchian:** a breakout = close above the previous 20 closes' max (below the min). One position per market;
   opposite breakouts while in a trade are ignored (the stops exit). Breadth reads the other market's close strictly
   before the decision day (no same-day timing doubt between MCX and COMEX closes). One frozen pick across all six
   variants (PREREG "within each idea"); its baseline is unfiltered Donchian on the same market, and the baseline
   walk-forward is the unfiltered rule of each fold's picked market.
3. **E9 seasonality:** Henry Hub monthly returns = Yahoo NG=F month-end closes (front month, not roll-adjusted). The
   baseline used by the gate is the better of "always short" and flat over the same window.
4. **E11 trend:** both legs together as one book. Spells are split into leg-month pieces (daily marks; costs half in the
   entry month, half in the exit month) so folds and the final window get the months they cover (R53 counted
   leg-months too). The app rolls 5 trading days before expiry; the series' roll row is used instead. Roll TIMING is not
   tested (no next-month prices).
5. **E12 rollover week:** expiry = the row before a detected roll row; held from the open 5 rows before the roll row to
   the close of the row before it. In live use the expiry calendar is published in advance, so this is not look-ahead;
   here it is read from the series.
