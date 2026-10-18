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
10. **Data as used.** Dukascopy proxies: WTI 3,639,541 minutes (13 failed days), Henry Hub 3,028,113 (9 failed days),
    both 4 Jan 2016 - 16 Oct 2026. Scale checked against Yahoo daily closes: WTI /1e3 (median ratio 0.9996), gas /1e4
    (median ratio 0.999, the lead's fix in common.py). The WTI download was restarted once (a parallel 12-thread run was
    stopped; the file used is the 32-thread run, copied to /dev/shm/proxy).
11. **Run order.** E3 and E4 (gas) were designed, frozen and read first, while WTI was still downloading; then E1, E2,
    E6_CRUDE, E6_NG. Each idea's final was read once (`results/final_read_<idea>.json`).

## Results (Rs per lot, after MCX costs; proxy futures unless stated)

| idea | variant | design trades / net (twin p) | WF | frozen | final trades / net / twin p / net without top 5 | baseline final / WF | real MCX fut | real MCX 1-ITM option |
|---|---|---|---|---|---|---|---|---|
| E1 EIA fade | k0.20 / k0.35 | 148 / -10,620 (0.66); 64 / -3,901 (0.62) | -2,039 | k0.35 | 9 / -2,258 / 0.90 / -1,874 | flat | 0 trades | 0 trades |
| E2 EIA follow | R30 / R15 | 279 / -15,387 (0.33); 307 / -14,986 (0.14) | -10,995 | R15 | 175 / -19,301 / 0.67 / -22,393 | flat | 61 / -8,492 | 58 / -41,088 |
| E3 storage fade | k0.20 / k0.35 | 165 / -10,541 (0.12); 87 / -9,170 (0.38) | -7,480 | k0.35 | 39 / -6,397 / 0.57 / -12,723 | flat | 10 / -1,881 | 9 / -720 |
| E4 storage follow | R30 / R15 | 273 / -22,189 (0.13); 291 / -20,543 (0.07) | -10,354 | R15 | 157 / -8,681 / 0.02 / -15,511 | flat | 51 / +4,003 | 49 / +20,781 |
| E6 crude ORB | sma / breadth | 937 / -58,942 (0.27); 956 / -67,257 (0.58) | -37,166 | sma | 502 / -53,446 / 0.71 / -58,684 | USORB 966 / -102,127; WF -68,957 | 144 / -19,966 | 139 / -75,285 |
| E6 NG ORB | sma / breadth | 915 / -124,264 (0.74); 937 / -122,322 (0.66) | -85,575 | breadth | 540 / -74,598 / 0.63 / -81,541 | USORB 963 / -137,669; WF -163,408 | 170 / -24,624 | 162 / -60,025 |

Verdict: **all six fail** (every final net is negative; E1 also has too few final trades). Gross is near zero in every
cell; MCX costs (about Rs 85-130 a crude/gas mini round trip) decide the sign. E4's twin p 0.02 only says random clock
times lose more; its own net is negative. E4 on real MCX minutes (+Rs 4,003 futures, +Rs 20,781 option, ~50 trades in
14 months) disagrees with the proxy in the same window (+Rs 1,674 on 51 trades) only in size; it cannot pass alone. The
ORB filters halve the USORB loss mostly by halving the trade count; they do not make it positive.
