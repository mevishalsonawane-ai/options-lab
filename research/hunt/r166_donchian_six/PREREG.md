# R166 pre-registration: six checks on the Donchian arms, one by one (delta, support / resistance, fake breakout, fake volume, fake buyer / seller, traps)

Written 12 Nov 2026, **before any R166 number was computed** (committed on `claude/r166-donchian-six` before the code's
first run). Research only; nothing in `android/**` changes.

Boss, 12 Nov (after R162): *"Can we enhance it more with delta"*, then *"Delta, support and resistance, fake breakout,
fake volumn, fake buyyer/seller, traps try to ananlyis all 1 by 1"*, then *"Study only from nov 24- to present"*.

**Numbering:** R164 (order flow from the phone's recordings) and R165 (delta) were pushed by sibling sessions today, so
this study is R166. A first draft of this file was committed locally as "R164" and never pushed; it was renumbered and
narrowed (below) before any number was computed.

Arms: R72 Breadth Donchian and R51 Donchian 20 + Supertrend (10, 3), exactly as R152 / R162 (entries, walk, engine,
dated Zerodha charges, R17 slippage, today's exits XL: -15% stop, ladder + L25, 30-min time stop, 15:15, 1-ITM).
Signal source for every rule: **the index**. Option-chain data (an option's volume, implied vol, delta, the opposite
option's premium) is used only as context around the index signal, which CLAUDE.md allows; no rule's signal is read
from an option's own chart.

## 0. What is already known or covered elsewhere (so R166 does not repeat it)

- Delta: **R165 (sibling) covers delta bands, strikes at delta 0.5 / 0.6 / 0.7 / 0.8, delta-scaled index stops, Greeks
  in the picker and a tick-signed option-volume "order-flow delta".** R162's D60 made more on R72 in both periods but
  won slightly less; it was picked after seeing Apr - Oct 2026. R166 adds only two delta variants R165 does not make.
- Support / resistance: R150 (3-month and 5-minute turning-point levels: wall ahead, break through, fake-break exit;
  pivots, EMA, VWAP, volume profile). R20c, R89 (previous-day high / low breaks as their own entries).
- Fake breakout: R138 (M-top fake), R142 BB1 (skip after an earlier fake poke), R150 FX exits, R73 waits before entry,
  R142 NP (premium "no progress" exit).
- Volume: R105D (index-futures volume at the break), R144a (heavy futures-volume day), R77 (absorption proxy); R165 OF
  (tick-signed option volume).
- Buyers / sellers: R105F (PCR, writers adding OI, our option's OI piling, OI walls), R86 (seller detector), R164
  (recorded order flow).
- Traps: R8 / R61b (order-book and price-trap signs for Solo).

## 1. The rules (fixed now)

**A. Delta (the strike bought; R162's delta method: Black-Scholes at the minute before entry, rate 6.5%, time to the next
expiry; fallback 1-ITM).**
- D60C2: delta 0.60, but never deeper than 2 strikes in the money (deeper -> the 2-ITM strike).
- D60W: delta 0.60 on the weekly-expiry indices only (NIFTY, SENSEX); 1-ITM on the monthly ones.

**B. Support / resistance from the previous session's high and low (PDH / PDL; known before the open).**
- SR1 (room ahead): skip a call when PDH is above the index close at the minute before entry and within 0.25% of it;
  a put when PDL is below it and within 0.25%.
- SR2 (with the bigger level): take a call only if that close is above PDH, a put only if below PDL.

**C. Fake breakout: index follow-through after entry (exit rules).**
- FT3 / FT5: if over the first 3 / 5 minutes from the fill the index never trades beyond the signal 5-minute bar's
  extreme (its high for a call, its low for a put), sell at the next minute's open (fill + 3 / + 5). Today's exits run
  as before until then.

**D. Fake volume: the bought option's own volume in the signal bar.** Ratio = the contract's volume over the five
signal minutes / the median of its 5-minute volumes over the earlier five-minute blocks of the same day (needs >= 4
blocks; otherwise unknown and taken).
- V1 (no participation): skip if ratio < 1.0.
- V2 (climax): skip if ratio >= 3.0.

**E. Fake buyer / seller: the option market during the signal bar.**
- E1 (sellers capping): skip if the bought contract's implied vol at the minute before entry is below its implied vol
  six minutes before entry (the premium rose less than the index move implies). Unknown -> taken.
- E2 (the other side is bid): skip if the opposite-side ATM option's premium (the put on a call break, the call on a
  put break) is higher at the minute before entry than six minutes before. Unknown -> taken.

**F. Traps.**
- F1 (whipsaw): skip a break if the same index gave an arm signal on the OTHER side within the previous 30 minutes.
- F2 (sweep bar): skip if the signal 5-minute bar is an outside bar: its high above the previous 5-minute bar's high
  AND its low below that bar's low.

14 rules x 2 arms = **28 tests, at 1 lot** (lots 2 and 3 reported for any rule that passes). No threshold is fitted:
0.25% is R150's band; 3 / 5 minutes, 1x / 3x, 6 minutes and 30 minutes are round numbers fixed now.

## 2. Periods (Boss: Nov 2024 to now only)

- Design 20 Nov 2024 - 31 Mar 2026 with the walk-forward window Apr 2025 - Mar 2026.
- Final 1 Apr - 13 Oct 2026 (the lake's end for these stores), read once by R166 (`results/FINAL_READ`). Already read by
  many studies on these arms: weak evidence. **For the delta rules it is not a fresh test at all** (D60 was chosen on
  it), so family A is judged on the walk-forward window, and its final is reported only.
- Nothing before 20 Nov 2024 is used.

## 3. How skips work, and the twins

A skipped break still holds its side and counts in the 2-a-side cap as if taken (CLAUDE.md's rule for R151's pickers),
so a filter can never free a place for a trade the arm would not have made. Exit rules as R162.
- Filters (B, D, E, F): random-skip twins, 500 (seed 166): the same number of the arm's breaks marked skipped at random,
  walked the same way.
- Exit rules (C): R162's random-exit twins.
- Delta rules (A): R162's random-strike twins (0-3 ITM).
- p_net and p_win = (1 + twins >= the rule) / 501. A rule's p is p_net, or the larger of p_net and p_win if it passes by
  the win route. Family A's twins are run on the walk-forward window (its deciding period); the others on the final.

## 4. Gates (all must hold)

Better than today's rules (B0, 1 lot) by one of two routes, in the walk-forward window AND the final (family A: the
walk-forward window only):
- **Win route:** win rate up >= 5 points and net not lower; or
- **Profit route:** net higher and max drawdown at most 10% larger.
And:
1. Net > 0 in design, the walk-forward window and the final.
2. BH q <= 0.10 with m = R160's m 2,164 + R162's 102 + R166's 28 = **m 2,294** (earlier tests p = 1; R161, R163 - R165
   run in parallel and are left to a joint step). q within R166 is shown; raw p in `results/raw_pvalues.csv`.
3. At least 40 final trades.

## 5. What a pass means

A passing rule may act on PAPER for that arm (labelled switch, timeline, one-tap undo). Live: a skip or an earlier exit
lowers risk but starts in shadow behind an off switch; a strike change is not risk-lowering and needs Boss. Nothing
changes in the app from R166; every test is logged in `research/experiments/entries/r166-*.yaml`.
