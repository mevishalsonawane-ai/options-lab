# R83 events worker: implementation notes and deviations (E1, E2, E3, E4, E6)

Written 18 Oct 2026, before the design run. Code: `events.py`; tests: `test_events.py` (synthetic data, look-ahead
guard). Run from the repo root with `R83_DATA=/dev/shm IRAALGO_LAKE=/dev/shm/lake IRAALGO_CACHE=/dev/shm/r53cache`:
`python3 -m research.hunt.r83.events design`, then once `python3 -m research.hunt.r83.events final`.

Signal source of every rule: the US benchmark future (WTI for E1, E2, E6-crude; Henry Hub for E3, E4, E6-NG) as a
PROXY for the MCX future; trades priced as CRUDEOILM / NATGASMINI with MCX costs. The final read also replays the
frozen rule on real MCX minutes (CRUDEOIL / NATURALGAS near future) as the mini future and as the 1-ITM option.

## Choices the PREREG left open (fixed before any result)

1. **Fills.** Signal on a 1-minute close; entry at the next available minute's close (at most 5 minutes later, else no
   trade). Stops, targets and time exits are checked on minute closes; a gap through a level fills at the close that
   crossed it. A trade whose entry price is already past its target or stop is still taken and exits at the next close
   (as M3's USORB simulation would).
2. **Event-rule twins.** PREREG says "a non-event day of the same weekday". Every Wednesday is an EIA day and every
   Thursday a storage day, so no such day exists. Twin days are non-event Mondays, Tuesdays and Fridays of the same
   calendar year, entered at the same IST clock minute with the same side, the same stop and target distances (as
   fractions of the entry price) and the same maximum hold. E6 twins: any trading day of the same year.
3. **E1 / E3 spike extreme.** On the proxy: the highest high / lowest low of the T..T+15 one-minute bars. On the real
   MCX replay the near future has closes only, so the extreme is the highest / lowest close.
4. **NG storage calendar.** Thursday 10:30 ET; weeks with a US federal holiday Monday-Thursday are skipped in every
   year, including 2025-26 (the moved official dates were not verified this session). EIA crude: Wednesday 10:30 ET,
   official 2025-26 exceptions (`hunt/nn_crude/calendar_events.py`), pre-2025 holiday weeks (Mon-Wed) skipped. A
   release after IST midnight (29 Dec 2025, 17:00 ET) is outside MCX hours: no trade.
5. **E6 picks per market.** E6 has a different baseline per market (USORB on WTI vs on Henry Hub), so it is run as two
   ideas, E6_CRUDE and E6_NG, each with variants {sma (primary), breadth}, each frozen on its own design net and read
   once. Variant count is unchanged (4).
6. **ATR14d / SMA20.** From IST-day bars of all proxy minutes (not only MCX hours), shifted one day. On the real MCX
   replay: the lake's continuous daily series (not back-adjusted, so a roll day's true range includes the roll gap).
7. **Real-MCX option replay.** R53 `sim.sim_option` (strike at the signal minute, 1-ITM, first print within 2 minutes,
   exits on the future's stop / target and the time exit, printed and F1-cleaned minutes only), re-costed with R83's
   strict half-spreads (CRUDEOIL 0.30% / 0.60%, NATURALGAS 0.45% / 0.90%, from / before 17:00) and Zerodha charges.
   Full lots (CRUDEOIL 100 bbl, NATURALGAS 1,250 mmBtu). Option trades are skipped on expiry days.
8. **Seeds.** Twin seeds are fixed per idea (`C.SEED` plus a stable offset from the idea name).
9. **BH.** The worker reports twin p-values; the lead applies BH across all 32 R83 variants.
