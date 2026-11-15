# R179 pre-registration: a win rate above 55% with profit kept (picker + breakeven lock, on the Rs 1,500 rule and today's)

Written 15 Nov 2026, **before any R179 number was computed**; committed on `claude/r179-winrate-55` before the code's first
run on data. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 15 Nov 2026: *"1,500 stop, +Rs 500 per Rs 1,000. Check how could we increase the winrate ... by keeping the profit
and increase the winrate"*, then *"More then 55%"*, then *"Yes"* to the plan below.

**Signal source: the index** (entries unchanged; R151's picker scores index features at the decision minute). Only trade
selection (the picker) and the option's stop (the breakeven lock) change.

## 0. Evidence before this study (why these two levers)

R151: 0 of 20 reached 60% with profit; its pickers raised win to 48.9% (R72) / 49.9% (R51) on Apr - Oct 2026 with MORE
net. R162 BE10 (stop to net Rs 0 after +10%): win 50.6-53.2% in design / walk-forward, 48.7% / 49.5% in the final, net a
little lower. R105C: 60% only by selling at +5%, then both arms lose. R101: VIX / gap / time filters add 3-4 points and
cut net. Expected (said now): today's rules + picker + BE10 might approach 55%; the Rs 1,500 rule versions about 50%.

## 1. Rules (fixed now)

- **SC** (R177 Amendment C): entry stop -Rs 1,500 a lot where tighter than -15%; stop up Rs 500 a lot per Rs 1,000 a lot
  of best open P&L; no target; every other exit stays.
- **P** = R151's app picker as live (R72 0.4656, R51 0.4469; a skipped break still holds its side and counts in the
  day's caps; one it cannot score is taken).
- **BE** = R162's BE10 exactly: once the premium is up 10% on the raw fill, the stop moves to the premium whose stop sale
  nets Rs 0 after charges (r106 stop_price, T = 0); it only raises the stop.
- Variants (R72 and R51): **V1 = SC + P**, **V2 = SC + BE**, **V3 = SC + P + BE**, **V4 = today's rules + P + BE**.
  Also reported: today's rules (A), A + P, A + BE, SC; and Liquidity 15+5 with BE (no picker), descriptive.
- **Tests: 4 variants x 2 arms = 8.** BH **m = 4,030 + 8 = 4,038**.

## 2. Window and periods

The last year, **20 Oct 2025 - 19 Oct 2026** (stores built to 19 Oct). Periods as R177: early (to 31 Mar 2026), final
(1 Apr - 13 Oct 2026), last 6 months, the year. **The picker was fitted on trades up to 31 Mar 2026, so the early part is
in-sample for any variant with P; the final is the clean test**, and the gates require the final as well as the year.

## 3. Gates (per arm and variant, 1 lot; all must hold)

1. **Win rate >= 55%** in the whole year AND in the final.
2. **Net >= its base's net** in the whole year AND in the final (base: V1 - V3 = SC; V4 = today's rules A).
3. Net > 0 in early, final, last 6 months and the year.
4. Twin BH q <= 0.10 (m = 4,038). Twins (500, seed 179): V1 selection twin vs SC (drop as many SC trades at random);
   V2 random-exit twin vs SC (a random minute between the fill and SC's exit, today's exits active, as R162 / R176);
   V3 selection twin vs SC + BE; V4 selection twin vs A + BE. (The BE part of V3 / V4 is not twin-tested separately.)
5. At least 40 final trades.
6. The account stop rule (max drawdown <= Rs 40,000, worst month >= -Rs 13,000) in every period; today's rules already
   break it, so the report also says which variants pass everything else.

Lots 2 and 3 are reported, not gated.

## 4. Engine, costs, parity

R176's engine (R106 simulator) with R162's BE lock added as an option (default off; R176 tests unchanged); R106 walk; R17
fills; dated Zerodha charges. Parity: today's rules at 1 lot on the final = R172's check (R72 1,200 / Rs 1,70,336;
R51 1,668 / Rs 1,31,546); A + BE on the final is compared with R162's published BE10 final (R72 1,203 / Rs 1,64,609;
R51 1,673 / Rs 1,27,044, on the 13 Oct stores; the only expected gap is NIFTY's 13 Oct expiry day, as R172 explains).

## 5. Tests

pytest (synthetic): the BE lock in the simulator (moves to the net-zero premium after +10%, never lowers the stop, off by
default), the gates (55% boundary, both periods, base nets), the BH count; look-ahead guard as R176.

## Amendment A (15 Nov 2026, before any number for it was computed): a Rs 1,000 maximum loss

Boss, 15 Nov 2026, after reading R179: *"15% is too much for the stoploss, i want 1k max loss for all the above strategies"*.

- **Rule M**: on top of V4 (today's rules + R151 picker + BE lock) on R72 and R51, and of today's rules + BE lock on
  Liquidity 15+5 (no picker): the stop is set at the premium whose stop sale nets **-Rs 1,000 after charges and the expected
  stop slippage** (r106engine.stop_price, as R152), used only where it is tighter than the -15% stop (where -15% loses less,
  -15% stays). A gap can still fill below it; how often is reported. Every other exit stays (BE lock, ladder / L25,
  30-minute time stop, square-off).
- **M1** = Rs 1,000 a lot (Rs 2,000 / 3,000 at 2 / 3 lots); **M2** = Rs 1,000 for the whole position at every lot size.
  At 1 lot M1 = M2.
- **Tests: 2** (M at 1 lot on R72 and R51); Liquidity and 2-3 lots reported. BH **m = 4,038 + 2 = 4,040**.
- Gates (1 lot): win >= 55% in the year and the final; net >= today's rules (A) in the year and the final; net > 0 in every
  period; random-exit twin vs V4 (500, seed 179) BH q <= 0.10; >= 40 final trades; the account stop rule (reported which
  pass apart from it).
- Window and periods as R179. Honesty: data-led follow-up on the same year; the final is read again
  (`results/FINAL_READ_A`).

## Amendment B (15 Nov 2026, before any number for it was computed): a Rs 1,500 maximum loss

Boss, 15 Nov 2026, while Amendment A was being written up: *"What about 1.5k"*.

- **Rule M15**: as Amendment A's M1 with **Rs 1,500 a lot** (after charges and expected stop slippage; only where tighter
  than -15%). Amendment A showed the per-trade version collapses at 2-3 lots, so only the per-lot version is run (M2's slot
  repeats M1 at every lot size and is not reported).
- **Tests: 2** (R72, R51 at 1 lot); BH **m = 4,040 + 2 = 4,042**. Gates, window, twins: as Amendment A.
- Honesty: data-led; the final is read again (`results/FINAL_READ_B`).
