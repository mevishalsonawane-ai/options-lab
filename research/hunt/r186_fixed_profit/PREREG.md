# R186 pre-registration: a hard fixed profit target (Rs 1,000 / 1,250 / 1,500 a lot) and a Rs 500 step lock on Liquidity, R51 and R72

Written 16 Nov 2026, **before any R186 number was computed**; committed and pushed on `claude/r186-fixed-profit` before
the code's first run on data. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 16 Nov 2026: *"I want you to check if i had a hard 1-1.5 fix profit what will be effect on liquidity,r51,r72"*.

Boss, 16 Nov 2026 (folded in before any number, section 7): *"As i observation from a month, the profit of alot is 1000,
then ends of negative, then its 1.5k again negative, again 2k again negative, this has happened every single day"*.

**Signal source: the index** (entries unchanged; R151's picker scores index features at the decision minute). Only the
exit on the option premium changes.

## 1. Reading of the request

"A hard 1-1.5 fix profit" = a hard fixed profit target: the option is sold as soon as the trade's open P&L reaches
**+Rs 1,000 / +Rs 1,250 / +Rs 1,500 a lot, net after charges**.

## 2. The base (the rule in the app today)

main, commit 407865713 "Exit rule v3 (V4)": each arm's own stop (-15%, ladder / L25, the 30-minute or 20-minute time
stop, square-off) + R162's breakeven lock at +10% (once the premium is up 10% on the raw fill, the stop moves to the
premium whose stop sale nets Rs 0 after charges); on R72 and R51 also R151's picker (R72 0.4656, R51 0.4469; a skipped
break still holds its side and counts in the day's caps; one it cannot score is taken). Liquidity 15+5 with the breakeven
and no picker. This is exactly R179's V4 (R72, R51) and R179's "Liquidity with BE" (R179 `BE`).

Also reported for comparison: today's plain rules as R179 called them (**A**: each arm's own exits, no picker, no
breakeven).

## 3. Rules (fixed now), each on top of the base; whichever exit comes first wins

- **T1000 / T1250 / T1500**: a resting limit sell at the premium X whose sale nets +T after the dated Zerodha charges:
  (X - real entry fill) x qty - charges(entry, X) >= T, X on the 0.05 grid rounded up (r106engine.target_price), with
  T = Rs 1,000 / 1,250 / 1,500 x lots. Solved from each trade's own real entry fill (known at the fill: no look-ahead).
  Fill: at X when a minute's high reaches X; at the minute's open when the minute opens above X (a gap through the limit).
  The target acts from the fill minute on, never before the fill. A minute where both the stop and the target are touched
  (and the open is not above X) is taken as the stop (the cautious order, as R106). Every other exit is kept.
- **Tests: 3 targets x 3 arms (R72, R51, Liquidity) = 9.**

## 4. Window and periods

As R179: the last year, **20 Oct 2025 - 19 Oct 2026** (stores built to 19 Oct). Periods: early (to 31 Mar 2026), final
(1 Apr - 13 Oct 2026), last 6 months (20 Apr - 19 Oct 2026), the year. R151's picker was fitted on trades up to 31 Mar
2026, so the early part is in-sample for R72 / R51.

## 5. Gates (per arm and rule, 1 lot; all must hold)

1. Net >= the base's net in the whole year AND in the final.
2. Net > 0 in early, final, last 6 months and the year.
3. A random-exit twin vs the base (500 twins, seed 186): every trade the rule closes earlier than the base (and at least
   2 minutes before the base's exit) is instead sold at the open of a random minute strictly between its fill and the
   base's exit, with the base's exits (the arm's own stop, ladder / L25, time stop and the breakeven lock) still active
   before it; the other trades keep their net. p = (1 + #twins >= the rule's total) / 501. **BH q <= 0.10** with
   **m = 4,852 (the count after R185) + 15 = 4,867** (section 7 adds 6 tests to section 3's 9).
4. At least 40 final trades.
5. The account stop rule (max drawdown <= Rs 40,000, worst month >= -Rs 13,000) in every period; the base already breaks
   it on some arms, so the report also says which rules pass everything else.

Lots 2 and 3: reported (3 lots), not gated.

## 6. What is reported

Per arm x rule at 1 lot (and 3 lots, descriptive), by period: trades, win rate, net, average win, average loss, how many
trades the target (lock) closed, how many of those would have made MORE under the base and the total profit given up
(and the total saved on the others), max drawdown, worst month. Also the base and A.

Engine: R176's engine (R106 simulator, `r176engine.simulate_step`) with a net-rupee target (`tgt_abs`) and a net-rupee
step lock added as options that are off by default (R176 / R179 results unchanged with them off, tested). R106 walk; R17
fills (locks sold as stops with the stop slippage); dated Zerodha charges.

**Parity:** the base must reproduce R179's published numbers (results/year.json): V4 at 1 lot, year R72 1,069 trades /
Rs 3,24,629, R51 1,367 / Rs 2,49,903; final R72 604 / Rs 1,67,233, R51 655 / Rs 1,44,802; Liquidity with BE year 688 /
Rs 1,00,222, final 357 / Rs 6,432. A at 1 lot on the final = R172's check (as R179). If parity fails, nothing is read.

## 7. Amendment (16 Nov 2026, folded in before any number): "+1,000, then negative" and a Rs 500 step lock

**7a. Descriptive (no test).** For each arm under the base at 1 lot: how many trades reached a best open P&L of at least
+Rs 1,000 / +1,500 / +2,000 a lot net, then ended with a loss; the total given back (best open P&L minus the final net,
summed over those trades, and their summed final loss); and the share of all losing trades that had been +Rs 1,000 or more
first. Best open P&L net = (the highest premium from the fill minute to the minute before the exit - real entry fill) x qty
- charges(entry, that premium); the exit minute's high is counted only for a time exit at the close (the simulator's order:
a stop in a minute ends the trade before that minute's high counts). By period, and by month for Aug, Sep and Oct 2026
(Oct to the 19th). **20 Oct - 13 Nov 2026** (the month Boss watched): attempted only from the lake's gap files
(`index_1m_*_2026_gap1113.parquet`, `opt_*_WEEK_2026_gap1113.parquet`) with no Dhan call and never Boss's app export; if
the arms' signals or stores cannot be built from them faithfully, it is reported as not possible.

**7b. Rules (fixed now), each on top of the base; whichever exit comes first wins:**

- **L500**: the arm's own entry stop is kept (no tight entry stop, unlike R176). Once the best open P&L reaches
  **+Rs 1,000 a lot net**, the stop rises to the premium whose stop sale nets **+Rs 500 a lot**; at +1,500 it locks +1,000,
  at +2,000 +1,500, ... (always Rs 500 a lot under the last Rs 500 step reached). "Net" for the trigger: (premium - real
  entry) x qty - charges(entry, premium), solved to the 0.05 grid (rounded up, as the target); "net" for the lock: the stop
  sale after the R17 stop slippage and charges (r106engine.stop_price with a negative loss, rounded up). A level reached in
  a minute acts from the next minute (no look-ahead). It only raises the stop and is sold as a stop order (a gap can fill
  below it).
- **L-BE1000**: once the best open P&L reaches +Rs 1,000 a lot net, the stop moves to the net Rs 0 (breakeven) premium;
  nothing more.
- Amounts are per lot (x lots at 3 lots).
- **Tests: 2 rules x 3 arms = 6** (BH m as section 5: 4,867). Gates as section 5; the twin's changed trades are the ones
  the lock closes earlier than the base.

## 8. Honesty

Data-led on the same year that R179 read; the final was read by many studies and is read again here
(`results/FINAL_READ` is written before the one read). The targets and step sizes come from Boss, not from a search. Every
rule is logged in `research/experiments/entries/` whatever it shows.

## 9. Tests

pytest (synthetic): the target fills at the net target price; a gap through fills at the open; the target never fires
before the fill; target / lock off leaves R176's simulator unchanged; the L500 levels (+500 at +1,000, +1,000 at +1,500,
only up, from the next minute); L-BE1000 locks Rs 0 only; the gates; the BH count.
