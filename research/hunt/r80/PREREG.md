# R80 pre-registration: better ENTRIES for Liquidity 15+5

Written 18 Oct 2026, **before any R80 number was computed** (the stores were still building). Research only: no
`android/**`, no `.github/**`. Liquidity BANKNIFTY is the 26 Oct go-live candidate, so its plain rules stay as they are;
any pass would be a separate, labelled PAPER variant, logged in the Autopilot timeline and kept apart from the
plain-rules record (CLAUDE.md). Boss, 18 Oct: "use multiple agents/sessions to enhance the liquidity strategies".

Another session (R79) is testing direction filters (Supertrend agreement, a 10-candle wait, cross-index breadth). R80
does not repeat them.

## 1. The arm (B0, unchanged)

Liquidity 15+5 exactly as R63 / R70 / R76 ran it: `hunt/r51/fam_liq.py` on R51's Amendment A clean stores
(R51_CLEAN=1), BANKNIFTY 15-minute + 5-minute books, a pool break where an active swing zone of the same side overlaps,
entry at the open of the minute after the breaking bar closes, 09:20-14:00, room >= 1 index stop (30 points) to the next
level, 1-ITM nearest weekly/monthly contract, one position per book, expiry days skipped. Exits: -15% premium stop,
20-minute time stop unless +5%, index stop 30 points through the broken level, next-level target, failed break, new
level, 15:10. R51 engine: one held contract, dated charges, R17 liquidity fills, next-minute fills, stops on the minute
low with gap fills at the worse open, 1 lot.

- **Signal source (every rule below): the index** (BANKNIFTY / NIFTY / SENSEX 1-minute bars folded to 15 and 5
  minutes). The option is only bought. No rule reads an option's own price chart; no option-chain context is used.

Cross-index check (only for a BANKNIFTY pass): the same rules on NIFTY (index stop 15, strike step 50) and SENSEX
(index stop 50, step 100), 15+5 books, the a-priori stops of h4 (`IDX_STOP_EXT`), not tuned.

## 2. What the log says is already done (not repeated)

`research/experiments/entries/` (grep "liquid", 75 entries) and the topic files:

| idea in the brief | already logged | R80 |
|---|---|---|
| time of day (before / after 11:00) | H12 F11/F12 (flipped in holdout) | only a NEW cut: no entries before 10:15 (never tried on BANKNIFTY; helped NIFTY/SENSEX in the *_PLUS studies) |
| gap size, prior-day range, India VIX level / change | H12 F07-F10, F16-F19; R15 F1-F5 | not repeated |
| first-hour range | NIFTY_PLUS / SENSEX_PLUS only (not BANKNIFTY, not the Nov 2024+ market) | tested once on BANKNIFTY (V7) |
| expiry week / day | H12 F14/F15 (dte <= 3); expiry days already skipped | not repeated |
| OI walls / PCR | R15 F3 (PCR), shadow A1/A2 (OI veto), R28 G7 (GEX) | not repeated |
| strike ATM / 1-ITM / 1-OTM | H13, H16 (keep 1-ITM) | not repeated |
| volume / tick-speed surge | R77: the lake's futures minutes start 29 Jul 2026; the option-volume proxy correlates 0.18-0.38 with futures volume | **not tested** (no design data) |
| room to the next level | H12 F20/F21 | not repeated |
| candle shape before the decision | R28b | not repeated (V8 is the breaking bar only, see below) |
| meta-label model | H44, R41 | not repeated |

## 3. The ten variants (each a FILTER on B0's candidate signals; none adds a trade)

Each is fixed now; no threshold is chosen from data. Each removes signals BEFORE the one-position-per-book walk
(V9 and V10 act after it), so a removed signal can free its book for a later one, exactly as the app would.

| id | keep a signal only if ... | idea and source |
|---|---|---|
| **V1 PRIOR** | the broken pool's origin bar is on an EARLIER session than the break | older liquidity: stops that rested overnight (ICT "previous-day liquidity") |
| **V2 STACK** | at least 3 active same-side zones (pools + swings, known before the break, not broken before it) overlap the broken pool's band | "equal highs / stacked pools hold more stops" (LuxAlgo, practitioner sources) |
| **V3 ROUND** | a round number lies in [level - s, close + s] (calls; mirrored for puts), s = one index stop; round = multiple of 500 (BANKNIFTY, SENSEX) or 100 (NIFTY) | stop orders cluster just beyond round numbers and cascade when crossed (Osler 2003, J. Finance; Osler 2005, J. Int. Money & Finance) |
| **V4 CLEAN** | the pool was NOT swept before the break (no bar after it was known had a wick beyond its edge and closed back inside) | unswept liquidity still holds all its stops; a swept level has already been "used" (ICT sweep-and-reclaim vs clean break) |
| **V5 PDX** | the breaking close is beyond the previous session's high (calls) / low (puts) | the break also takes the prior day's liquidity (higher-timeframe level) |
| **V6 T1015** | the entry is at or after 10:15 | skip the opening hour's noise (NIFTY_PLUS / SENSEX_PLUS ingredient, new for BANKNIFTY) |
| **V7 FHR** | entry at or after 10:15 AND the day's first-hour range (09:15-10:14 high - low) >= the median of the previous 20 sessions' first-hour ranges | a day-type gate: only active days (NIFTY_PLUS "vol" factor) |
| **V8 DISP** | the breaking bar's body abs(close - open) >= 1.5 x the mean body of the 20 bars before it (same book) | a displacement break (ICT "displacement"), not a drift through the level |
| **V9 CAP2** | it is one of the first 2 trades of the day across both books | a max-signals-per-day cap |
| **V10 L1** | no earlier trade of the day (either book) has closed at a loss before this entry's row | stop for the day after the first loss |

Look-ahead guard: every input is known at the end of the breaking bar (the entry row). V1-V4 and V8 read only bars up
to and including the breaking bar; V5 reads the previous session; V7 reads rows 0-59 of the day (complete at 10:15) and
the 20 sessions before; V10 reads trades whose exit row is strictly before the entry row. pytest tests check each.

## 4. Periods, measurement

- Design 20 Nov 2024 - 31 Mar 2026; walk-forward window Apr 2025 - Mar 2026 (quarters reported). No parameter is fitted,
  so the walk-forward window is simply the part of design after the first 4 months, judged quarter by quarter.
- Final 1 Apr - 13 Oct 2026, read ONCE (`results/FINAL_READ`). It was already read by R51, R63, R70 and R76 for this
  arm, so it is **weak evidence**; B0 there is +Rs 10,978 on 131 trades and negative without its top 5.
- Reported per variant and period: trades, win rate, net, per trade, worst day, max drawdown, trades removed, net of the
  removed trades.
- **Random-skip twins** (500, seed 80) in each period: drop as many candidate signals at random as the filter dropped
  (V9 / V10: as many taken trades), from the same period's candidates, then walk the books again. p = share of twins
  whose net >= the variant's. A paired day bootstrap of variant - B0 (2,000, seed 80) is information only.
- **BH** across all 10 BANKNIFTY variants on the final twin p.
- **Top-5 check:** the improvement must survive removing the 5 best trades from both the variant and B0 (final).

## 5. Pass rule (all must hold, BANKNIFTY)

1. net > B0's net in the walk-forward window AND in the final;
2. final twin p < 0.10 and BH q < 0.10;
3. (variant net - its top 5) > (B0 net - its top 5) in the final;
4. at least 40 final trades.

A pass is then run on NIFTY and SENSEX (design and final, same rule). "Holds" there = beats that index's plain rules in
both periods. A BANKNIFTY pass that does not hold elsewhere is reported as BANKNIFTY-only.

A pass gets `APP_SPEC.md` (paper variant, separate and labelled, never live by itself). Nothing passes -> Liquidity
keeps its entries. Fails stay notes.
