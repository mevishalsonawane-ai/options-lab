# R204 + R205 pre-registration: an earlier breakeven, an earlier time stop, Liquidity without the MIDCPNIFTY 5-minute book, and what a real account size can take

Written 20 Nov 2026, **before any R204 / R205 number was computed**. Committed and pushed on `claude/trend-exit-study`
before the code's first run on a rule below. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 20 Nov, after the R202 diagnosis (`research/HUNT_R202_ARMS_DIAGNOSIS.md`) offered four next steps: *"Run 2 3 4"*:

- **2** Liquidity without the MIDCPNIFTY 5-minute book, and Liquidity judged from April 2026 on;
- **3** the arms re-run with only the trades the money can pay for (Boss gave no account size, so a range is run);
- **4** two tests: an earlier breakeven (once a trade is up Rs 500 a lot) and an earlier time stop for trades that never
  get going.

R204 = the tests (items 4 and 2, 10 tests). R205 = the account-size run (item 3, descriptive, no gate).

**Signal source: the index** for every entry (the arms' own signals, unchanged). The new rules here are exits and a
book switch: the breakeven is a stop on the option premium and the time stop reads the held option's own premium after
the fill (stops, targets, trails and locks may sit on the premium, CLAUDE.md; R142's no-progress exit did the same).
No entry signal reads an option's chart.

## 0. Honest status of these ideas (read first)

**Every idea here came from looking at the whole span, holdout included.** The diagnosis that suggested them sliced the
baseline trades of 21 Nov 2024 - 5 Oct 2026 after R202 / R203 had read the holdout (37% of losers were once up Rs 500 a
lot; time exits that were never up Rs 250 a lot lost Rs 1.86 lakh on R72 and Rs 3.27 lakh on R51; the MIDCPNIFTY 5-minute
book made Rs 412 on 293 trades). So **1 Apr - 5 Oct 2026 is not a fresh holdout for R204**: it is a second look, and the
"skip the book that made nothing" test is close to circular. The design / holdout split and R202's gate are kept, so the
numbers can be read the same way, but **a pass here is weaker than a pass in R202 and would still need new days**
(the 6 Oct - 13 Nov 2026 lake fill when it can be copied, and forward days) before it counts as passed for CLAUDE.md.
This is said again in every table heading and log entry.

Already tested and not repeated: R142's no-progress exit on the old exits (premium never up X% in N minutes; R72, R51,
R52: all rejected), R81's index no-progress exit on Liquidity, R162 / R179's breakeven at +10% (V4), R186's step lock and
breakeven at +Rs 1,000 (V5), R176 / R179's rupee stops. Not tested before: a breakeven at +Rs 500 a lot net on top of V5,
and a net-rupee "never got going" time stop on top of V5.

## 1. Data, engine, baseline, windows

As R202 (`research/hunt/r202_trend_exit/PREREG.md` sections 1 - 2), unchanged: the `dhan-data` build to **5 Oct 2026**
(not R190's lake to 13 Nov), R51 clean stores, R186's engine, R152's picker. **Baseline B = today's rules (V5)**, 1 lot,
dated charges, R17 fills. Design 21 Nov 2024 - 31 Mar 2026 (four walk-forward quarters); holdout 1 Apr - 5 Oct 2026
(a second look, section 0); last year 24 Nov 2025 - 5 Oct 2026 (descriptive).

R186's engine source is not edited. The breakeven level is added at run time by wrapping `r186engine.lock_levels` while a
BE500 variant is priced (the original is restored before the twins, which use today's rule).

## 2. R204: the tests (10)

| id | arms | rule (on top of B; the first exit wins; a stop never moves down) |
|---|---|---|
| **BE500** | R72, R51, LIQ | **Earlier breakeven.** Once the option's high has reached the premium whose sale nets **+Rs 500 a lot** after charges (`r106engine.target_price`), the stop moves, from the next minute, to the premium whose stop sale nets **Rs 0** after the R17 stop slippage and charges (`r106engine.stop_price`, exactly R186's breakeven level). B's breakeven at +10%, the step lock from +Rs 1,000 (R72 / R51) and the breakeven at +Rs 1,000 (Liquidity) all stay; the highest stop applies. Sold as a stop order (a gap can fill below it). |
| **ET10** | R72, R51, LIQ | **Earlier time stop, 10 minutes.** If the option's minute highs from the fill minute to fill + 9 never reach the premium whose sale nets **+Rs 250 a lot** after charges, it is sold at the **open of minute fill + 10** (a market sale, the engine's forced exit, R17 fill). The decision reads only minutes before the sale. B's own time stops (30 minutes; Liquidity 20 minutes unless up 5%) stay. |
| **ET15** | R72, R51, LIQ | The same with 15 minutes (highs from fill to fill + 14; sold at the open of fill + 15). |
| **NOM5** | LIQ | **Liquidity without the MIDCPNIFTY 5-minute book.** Its trades are removed; every other book is untouched (a book holds its own position, so nothing else changes). |

BE500, ET10 and ET15 are re-priced and re-walked (an earlier exit can free the arm's side for its next signal); the
picker's skips are applied after the walk. A missing high counts as "not reached". Fixed numbers, nothing fitted:
Rs 500, Rs 0, Rs 250, 10 and 15 minutes.

## 3. The gate

R202's gate, unchanged (its section 6): better than B in design and in the holdout (net above B's, or within 5% with
drawdown and worst month each 20% smaller); better in at least 3 of the 4 design quarters; drawdown not above B's in
either window; at least 40 trades changed or removed in each window; holdout random-twin p < 0.05 (2,000 twins, seed
204; exit rules: the changed trades sold at random minutes between the fill and B's exit with B's exits active; NOM5:
the same number of Liquidity trades removed at random); BH q <= 0.10 with m = 4,869 (the entry files on this branch
before R204) + 10 = **4,879**, on the normal-tail twin p (a rule with no changed holdout trade enters with p = 1, R202
Amendment A).

**Verdict words** (because of section 0): a rule that clears every item is reported as **"clears the gate on a second
look"**, never as "passed": it is logged `inconclusive` and offered to Boss for shadow or for their own decision; one
that fails is `rejected`.

## 4. R205: what an account of a given size can take (descriptive, no gate)

The baseline trades of the three arms (B, 1 lot each, both R51 and R72 buying a shared break, as in Live) are replayed
through one shared pot of money. Boss gave no account size, so these are run: **Rs 56,000** (R193's figure),
**Rs 1,00,000, Rs 1,50,000, Rs 2,00,000, Rs 3,00,000, Rs 5,00,000** and **no limit**.

The model (fixed now):
- Every day starts with free cash = the account size (profits are not added and losses not carried between days, so the
  sizes can be compared; the drawdown is reported against the size).
- A trade is taken at its fill minute if free cash >= its premium x its lot (the real fill, slippage in); otherwise it is
  **not taken** (counted). Taken: free cash falls by that amount. At its exit minute free cash rises by that amount plus
  the trade's net (sale money can buy options again the same day).
- Exits are booked before entries of the same minute. Entries of the same minute are taken in this order: R72, then
  Liquidity, then R51 (R72 decides first on a shared bar in the app), then by index name and side.
- No re-walk: a trade the money could not pay for is dropped and the arm's other trades stay as they were. In the app a
  refused entry would free the arm's side for its next signal, so this is an approximation (R193's funded table had the
  same one). 1 lot only; Boss's lots setting and the lots cut (3, 2, 1) are not modelled.

Reported per size and window: trades taken and not taken, win %, net, max drawdown, worst month, worst day, net as a
share of the account, the most money in use. Also the same for **R72 + Liquidity without R51** (R51's second lot on a
shared break uses the same cash), descriptive.

Also descriptive: **Liquidity from 1 April 2026**, by month and by book (trades, win %, net), from the baseline.

## 5. Order of work

1. This file is committed and pushed before any rule is run.
2. `r204study.py design` -> `results/design.json`; the code and design results are committed and pushed.
3. `r204study.py final` (writes `results/HOLDOUT_READ` first; the second look) -> `results/final.json`;
   `r204study.py decide` -> `results/decision.json`; `r204study.py funds` -> `results/funds.json`;
   `r204report.py` -> `results/report.md` and the log entries (10 tests + 1 descriptive).
4. No rule, number or gate item changes after the design numbers; an amendment is dated and written here first.

## 6. Tests (pytest, synthetic, no data)

The look-ahead guard (the early time stop's row is unchanged when the option's minutes from the sale minute on are
replaced by noise), the time stop with missing minutes, a fill too late for the window, the threshold reached exactly, the
breakeven level merged in trigger order with the lock levels (and never lowering a lock), the book filter, the funded
replay (exits before entries, the same-minute order, a trade the cash cannot pay for, money freed by a sale used the same
minute, the daily reset, an empty day), and the windows / gate count.
