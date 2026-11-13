# R170 pre-registration: every other Donchian-study rule (R152-R167) on the ORB arms

Written 13 Nov 2026, BEFORE any R170 number. Committed and pushed on its own before the first run. Research only:
nothing in the app changes.

Boss, 13 Nov (after R169): *"All the other studies we tried on r51 and r72, try that too."*

## 0. Scope

R169 already ran on ORB, ORB Fresh and ORB Sweep (BANKNIFTY):
- R151 / R163's logistic picker;
- R123's network;
- R162's BE10, T40 and D60;
- R165's D50;
- R161's E2 (wait one bar) and E9 (ATR hot);
- R167's G3;
- R150's levels as targets;
- Liquidity's pieces;
- the gap and ICT filters.

R170 takes **every other rule** from the Donchian series:
- R152 (+-Rs 2k), R153 (lose less), R155 (scalp filters), R161 (entries), R162 (exits), R163 (pickers), R165 (delta),
  R166 (six checks), R167 (smart exits);
- each rule is adapted to the ORB arms exactly as written below.

Not taken, with the reason:
- R154 / R155's 10-15 point targets: R168 ran them on the ORB arms today (360 tests, 0 passed).
- R162 scale-out SO8-SO15: an ORB win is a +40 / +80 point target, so selling one lot early at +8-15% of the premium is
  past the target. It is already the arm's exit.
- R162 ATM: that is today's strike.
- R163 E1 ensemble and R165 PKD: more pickers of the same kind. R170 keeps two, G1 trees and N1 net.
- R167 SR, CTX and ML: design-trained exit models that need R167's level and regime builds. Of R167's rules, R170 keeps
  only the ones that can be written as a fixed rule.
- R164: R164 itself found no aggressor data; it ran no test.
- R153 C4 (entries until 10:00): ORB's first possible entry is 10:05, so the rule can never fire.

Everything else is as R169's `PREREG.md`:
- the arms and the engine (R3's, parity already shown in R169);
- dated Zerodha charges, R17 fills sized to the order, lots 1 / 2 / 3;
- the periods: training history Aug 2021 - 19 Nov 2024; design 20 Nov 2024 - 31 Mar 2026 with the walk-forward window
  Apr 2025 - Mar 2026 in quarters; final 1 Apr - 12 Oct 2026;
- the app's skip rule: a skipped break still holds its side and counts in the caps;
- the combination rule C1 / C2 / C3;
- the twins, the gates and the risk classes.

**The final window was read once by R169 today and by many studies before. R170's read of it is another look: weak
evidence.** Signal source: the index (BANKNIFTY 5-minute bars) for every entry. The option's own data (volume, OI, IV,
the chain's PCR) is used only as context or filters, as CLAUDE.md allows. Every exit level is set on the premium or the
index.

Definitions:
- ATR5 = the 14-bar ATR of BANKNIFTY's continuous 5-minute bars (earlier sessions warm it up);
- the signal bar = the 5-minute bar whose close gives the entry;
- R / L = the opening range's high / low.
A value that cannot be computed never skips a trade.

## 1. Skip-only entry filters (take the break only when ...)

| id | from | rule (call; put mirrored) |
|---|---|---|
| E1 STRONG | R161 E1 | the signal bar closes in its outer 30%: (close - low) / (high - low) >= 0.70 |
| E3 BIGBAR | R161 E3 | signal bar range >= 1.0 ATR5 |
| E4 FAR | R161 E4 | ORB / Fresh: close - R >= 0.20 ATR5. Sweep: the poke beyond the edge >= 0.20 ATR5 (high - R for a put) |
| E5 SQUEEZE | R161 E5 | today's range width (R - L) <= the median of the previous 100 sessions' widths (needs 50) |
| E6 HTF15 | R161 E6 | the last closed 15-minute bar (09:15-anchored, continuous across days) closes above the midline of its 20-bar 15-minute Donchian channel |
| E7 HTF60 | R161 E7 | the same on 60-minute bars (09:15-anchored; the day's last bar 15:15-15:29 is short) |
| E8 PDR | R161 E8 / R166 SR2 | the signal close is beyond yesterday's high |
| E10 VIXWAY | R161 E10 | India VIX at the signal bar's last minute is below the day's 09:15 VIX open (a put needs it above) |
| E11 BREADTH | R161 E11 | at least 4 of the 5 indices (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX) have the same 5-minute bar closing above their own day open |
| E12 NOLUNCH | R161 E12 | the entry minute is not 11:30-13:29 |
| E13 PASTHOUR | R161 E13 | entries from 10:15 on: the close is beyond the first hour's (09:15-10:14) high |
| P5 PIV | R155 F5 | no floor pivot (P, R1, S1, R2, S2 from yesterday's H / L / C) lies within 0.25% ahead of the close |
| P8 EMA20 | R155 F8 | the signal close is above the EMA 20 of 5-minute closes |
| P9 ST | R155 F9 | Supertrend (10, 3) on 5-minute bars is up |
| P10 TWAP | R155 F10 | the close is above the session's average 1-minute close so far |
| P13 FT3 | R155 F13 / R144c | NOT (the bought option's price AND OI both fell over the 15 minutes to the signal bar's end) |
| P12 PCR | R155 F12 | the stored chain's put/call OI ratio (ATM +-4) rose over the 15 minutes (a put needs it to fall) |
| P14 WKA | R155 F14 | the signal bar's wick against the trade (upper wick for a call) < 50% of the bar |
| P16 WKF | R155 F16 | the signal bar's wick for the trade (lower wick for a call) >= 50% of the bar (a rejection candle) |
| P18 BODY | R155 F18 | the body >= 60% of the bar, coloured the trade's way |
| V1 VOL | R166 V1 | the bought option's volume in the signal bar >= the median of its earlier 5-minute bars today |
| V2 CLIMAX | R166 V2 | NOT (that volume >= 3x the median): skip a volume climax |
| I1 IVDN | R166 E1 | NOT (the bought option's IV, solved from its close, fell over the 15 minutes) |
| I2 OTHER | R166 E2 | NOT (the opposite side's ATM option got dearer over the signal bar) |
| T1 OPP30 | R166 F1 | NOT (a 5-minute close beyond the opposite range edge in the 30 minutes before) |
| T2 OUTSIDE | R166 F2 | NOT (the signal bar is an outside bar: high above and low below the bar before) |
| D1 DB1 / D2 DB2 | R165 DB1 / DB2 | the bought (ATM) option's delta is in 0.45-0.55 / 0.55-0.65 |
| O1 OF1 | R165 OF1 (proxy) | the bought option's tick-rule signed volume over the last 3 minutes is > 0 (price up = buy) |
| K1 TREES | R163 G1 | gradient trees (sklearn HistGradientBoosting, fixed defaults, random_state 0) on R169's 11 features + PCR, ATM IV, signal-bar option volume / its median, Supertrend side, EMA20 distance / ATR5. Trained as R169's F1 (B0 trades before the quarter, >= 150). Keep the top 55% (threshold = the 45th percentile of the training scores) |
| K2 NET | R163 N1 | a small neural net (sklearn MLP (16,), alpha 1e-3, random_state 0, standardised) on the same inputs; keep the top 60% |

## 2. Tighten-only exits (all of today's exits stay)

Premium levels are solved from the real fill. Rupee levels are for the whole position, after the trade's charges.

| id | from | rule |
|---|---|---|
| B15 / B20 | R162 BE15 / BE20 | once the best premium is >= +15% / +20%: a stop at breakeven |
| LF1 | R162 LF1 | once +10%: lock -5%; once +15%: lock +3%; once +20%: lock +8% |
| LF2 | R162 LF2 | once +10%: lock +2%; +15%: +7%; +20%: +12%; +25%: +17% |
| T45 | R162 T45 | out at 45 minutes unless +5% |
| NIP30 | R162 NIP30 | at 30 minutes, out if the minute's close is below the fill |
| IXA | R162 IXA | out when a 1-minute index close goes beyond the signal bar's low (call) / high (put) |
| Q2K | R152 +2k / -2k | out at net +Rs 2,000 (sell at the next open once the high reaches it) and a stop at net -Rs 2,000 (the higher of it and -40 acts) |
| QT2K | R152 +2k only | out at net +Rs 2,000 |
| QS2K | R152 -2k only | a stop at net -Rs 2,000 (the higher of it and -40 acts) |
| LCK | R153 C1 | the -Rs 2,000 stop, and once +Rs 2,000, a lock at +Rs 1,000 |
| DCAP | R153 C2 | no new entry once the day's closed trades are <= -Rs 4,000 |
| VOLX | R167 VOLX, tighten-only | out when the index closes 3 ATR5 (at entry) below its highest close since entry, or 4 ATR5 above the entry index |
| VOLP | R167 VOLP, tighten-only | a lock at 3x the option's 14-minute ATR (at entry) below its highest high since the fill |
| MOM | R167 MOM | out when the 1-minute index Supertrend (10, 3) turns against the trade |
| G1R | R167 G1 | once net +Rs 1,500: a lock at breakeven |
| G2R | R167 G2 | once net +Rs 1,500: a lock at net +Rs 500, trailing Rs 1,000 below the best |
| FK3 / FK5 | R166 fake breakout | out at the next open if the index has not traded beyond the signal bar's high (call) / low (put) within 3 / 5 minutes of the fill |

## 3. Strike and stop changes (they change entries or risk, so they would need Boss's explicit choice)

| id | from | rule |
|---|---|---|
| ITM2 | R162 ITM2 | two strikes in the money |
| D70 | R162 D70 / R165 SD70 | strike by delta 0.70 (R169's method) |
| IXR | R162 IXR | the index stop of IXA REPLACES the -40 premium stop |

## 4. Combinations and count

C1 / C2 / C3 are chosen as in R169's section 3d, from R170's singles only, before each walk-forward quarter. A strike
change (ITM2, D70) excludes the other. The time rules (T45, NIP30) exclude each other.

That is 31 filters + 19 exits + 3 strike / stop changes + 3 combinations = **56 per arm** (61 with Amendment A); x 3 arms x 3 lots =
**504 tests** (549 with Amendment A).

## 5. Gates

The gates are R169's, unchanged:
- walk-forward AND final: net up with drawdown not worse, or the win rate +5 points with net not lower;
- twins p <= 0.05;
- BH q <= 0.10 over **m = 3,265 earlier tests** (R168's 3,058, which includes the earlier studies, + R169's 207) **+ 504**;
- >= 40 final trades;
- -Rs 40k drawdown and -Rs 13k month at the tested lots.

Risk classes as CLAUDE.md:
- filters and tighten-only exits only lower risk; a pass would go to shadow first;
- strike / stop changes and combinations containing them need Boss's choice.

A pass gets an APP_SPEC; nothing in the app changes.

## 6. Tests

pytest for every new function (empty / NaN data, warm-up, expiry day, square-off, the rupee levels from the real fill).
The look-ahead guard covers every filter and exit: a decision at minute m is unchanged when all data after m is junk.
The skip-only and tighten-only properties are tested on synthetic days.

## Amendment A, 13 Nov 2026: Boss's mid-run message (written before any R170 number)

Boss: *"Multiple support and resistance at once on all directions changes, fake breakout, fake volumn, actual volume,
delta, fake buyyer seller, and all rhe other things."*

Already covered above:
- fake breakout: FK3, FK5, and R169's X6;
- fake volume: V1, V2;
- delta: D1, D2, D70, and R169's S1 / S2;
- fake buyer / seller: I1, I2;
- order-flow proxy: O1.

Five filters are added (skip only; unknown never skips).

**The level map.** Each session gets its levels from earlier sessions only, plus today's swings once confirmed:
- 5-minute swing highs / lows (3 bars each side) of the previous 50 sessions;
- 15-minute swing highs / lows (3 bars each side) of the previous 60 sessions;
- floor pivots (P, R1, S1, R2, S2);
- yesterday's high / low;
- the previous week's high / low;
- today's 5-minute swings, each confirmed 3 bars after it, so only swings confirmed by the signal bar.

The broken range edge itself is not a level. "Ahead" means the trade's way from the signal close; "behind" means the
other way.

| id | from | rule (take the break only when ...) |
|---|---|---|
| S1 ALLSR | R155 F6 (all lines at once) | no level of the map lies within 0.25% ahead |
| S2 ROOM | all directions | the nearest level ahead is at least as far away as the nearest level behind |
| S3 CLEAR | all directions | NOT (levels within 0.25% both ahead AND behind: a crowded zone) |
| A1 BANDVOL | actual volume (R144b V1) | the stored option band's (ATM +-4) volume so far today >= its median at the same minute over the previous 20 sessions (needs 10) |
| Q1 NOSHORT | OI-price build-up (R144b F2, R86) | NOT (the bought option is in short build-up over the 15 minutes to the signal: price down, OI up) |

New count:
- 36 filters + 19 exits + 3 strike / stop changes + 3 combinations = **61 per arm**;
- x 3 arms x 3 lots = **549 tests**;
- BH m = 3,265 + 549 = **3,814**.

## Amendment B, 13 Nov 2026: Boss: "40 points are too big, check 20pts max" (before any R170 number)

Three exit versions with at most 20 premium points. Entries are unchanged: the arm's own entry check, a premium above
40, stays.

The ProfitLock ladder sits on the target in use (25% / 50% / 75% of it). All three exit earlier than today or at the
same time: a tighter stop or a nearer target. So they count as tighten-only (risk-lowering) exits. R168 already ran
10-15 point exits on ORB, so these sit just above them.

| id | stop | target (ORB / Fresh; Sweep) |
|---|---|---|
| Z1 | -20 points | +20 (Sweep +20) |
| Z2 | -20 points | today's: +40 (Sweep +80) |
| Z3 | -15 points | +20 (Sweep +20) |

The exit time stays the 15:10 square-off. Z1-Z3 exclude each other in combinations.

New count:
- 36 filters + 22 exits + 3 strike / stop changes + 3 combinations = **64 per arm**;
- x 3 arms x 3 lots = **576 tests**;
- BH m = 3,265 + 576 = **3,841**.
