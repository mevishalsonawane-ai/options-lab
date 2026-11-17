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

## Amendment B (16 Nov 2026, written before any number for it was computed): three more answers to "+Rs 1,000, then negative"

Boss asked for other solutions to the same problem. Same base, window, periods, gates (section 5) and twins (500, seed 186);
every rule on top of the base, whichever exit comes first.

- **P60** (as S5 Trail's lock): once the best open P&L reaches +Rs 1,000 a lot net, the stop locks 60% of the best open
  P&L (net), and only rises. Built on the step-lock ladder in Rs 50 a lot steps of the best open P&L: at a best of
  +1,000 + 50k a lot net the stop is the premium whose stop sale nets 60% of that (so it locks at most Rs 30 a lot less
  than an exact 60%). Acts from the next minute; sold as a stop. Gated at 1 lot; 3 lots reported.
- **SPLIT** (2 and 3 lots only): one lot carries a resting target at +Rs 1,000 net for that lot (filled as section 3);
  when it fills, the other lots' stop moves to the premium whose stop sale nets Rs 0 for those lots (from the next
  minute; only raises the stop); they keep every base exit. Both legs use the whole position's entry fill; charges are
  counted per leg (so the Rs 20 entry brokerage is counted twice, a few rupees against SPLIT). The trade's net is the sum
  of the legs; it ends when the last leg ends. **Gated at 3 lots** against the base at 3 lots (the account stop rule at 3
  lots as at 1 lot: Rs 40k / Rs 13k); 2 lots reported. Its twin: each trade where the target leg filled is instead sold
  whole at a random minute between the fill and the base's exit (base exits active before it, 3 lots).
- **IDX** (signal on the underlying): once the option has been +Rs 1,000 a lot net (the trigger premium of section 7b),
  the trade is sold at the next minute's open (as a market sale, the R17 stop fill) when a 1-minute index close crosses
  back through the index level at the entry fill (the index open of the fill minute): for a call a close below it, for a
  put a close above it. Checked from the minute the trigger is reached (its close comes after its high). Gated at 1 lot;
  3 lots reported.
- **Tests: 3 rules x 3 arms = 9; BH m = 4,867 + 9 = 4,876** (the first 15 tests' q are not re-stated; their verdicts
  stand either way). The final is read again (`results/FINAL_READ_B`, written before the one read).

## Amendment C (16 Nov 2026, written before any number for it was computed): a Rs 1,000 or Rs 1,500 a lot entry stop on V5

Boss, 16 Nov 2026, before installing a new APK: *"Just a quick check before installing a new apk in my phone, instead of
1.5k stop loss what will happen if we make it 1k, also show me detailed difference before and after"*.

**The base here is "V5", the exit rule in the app now**: on R72 and R51, R151's picker + the arm's own exits (-15% stop,
ladder / L25, time stop, square-off) + R162's breakeven at +10% + this study's **L500** step lock (section 7b); on
Liquidity 15+5, the arm's own exits + the breakeven at +10% + this study's **LBE1000** (no picker). V5's year numbers are
this study's L500 (R72, R51) and LBE1000 (Liquidity) rows in results/year.json.

**Signal source: the index** (entries unchanged). Only the stop on the option premium changes.

- **V5+S1000** (exactly R179 Amendment A's rule M1): an entry stop at the premium whose stop sale nets **-Rs 1,000 a lot**
  after the dated Zerodha charges and the expected R17 stop slippage (r106engine.stop_price, as R152 / R179), used only
  where it is tighter (a higher premium) than the arm's own -15%; where -15% loses less, -15% stays. It is a real stop
  order: a minute that opens below it fills at that open (a gap), so a trade can still lose more than Rs 1,000. Every
  other V5 exit stays (breakeven, L500 / LBE1000, ladder / L25, time stop, square-off); whichever exit comes first wins.
- **V5+S1500** (R179 Amendment B's M15): the same with **-Rs 1,500 a lot**.
- Per lot: Rs 2,000 / 3,000 at 2 / 3 lots (and Rs 3,000 / 4,500 for S1500).
- **Tests: 2 rules x 3 arms (R72, R51, Liquidity) at 1 lot = 6; BH m = 4,876 + 6 = 4,882.**
- Window, periods (early, final 1 Apr - 13 Oct 2026, last 6 months, year 20 Oct 2025 - 19 Oct 2026), the gates of section
  5 (with "the base" = V5 for that arm) and the random-exit twin (500 twins, seed 186): the changed trades are the ones the
  rule closes earlier than V5 (at least 2 minutes before V5's exit), each sold at the open of a random minute between its
  fill and V5's exit with V5's exits (own stop, breakeven, L500 / LBE1000, ladder / L25, time stop) active before it.
- **Parity:** with the stop off, V5 must reproduce results/year.json at 1 lot, year: R72 1,097 trades / Rs 2,13,110, R51
  1,430 / Rs 1,62,959, Liquidity 688 / Rs 98,239. If parity fails, nothing is read.
- **Reported** (1 lot and 3 lots, year and final; 3 lots not gated): trades, win rate, net, average win, average loss,
  biggest loss, how many trades the stop closed, how many of those V5 would have closed with a profit (and the profit
  given up), how many trades lost more than Rs 1,000 a lot (gaps, slippage), max drawdown, worst month, worst day; V5,
  V5+S1500 and V5+S1000 side by side. Also **21 Oct - 13 Nov 2026** at 1 lot (descriptive only), from the extended stores
  built outside git for section 7a (the lake's gap1113 files; no Dhan call), if they still load.
- **Honesty:** a data-led follow-up on the same year that R179 and this study read; the final is read again
  (`results/FINAL_READ_C`, written before the one read). The amounts come from Boss, not from a search. Logged in
  `research/experiments/entries/` (r186c-*) whatever it shows.
