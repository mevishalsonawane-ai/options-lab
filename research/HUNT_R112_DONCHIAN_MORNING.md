# R112: both Donchian arms, mornings only, +Rs 1,500 / -Rs 1,000 an order

26 Oct 2026. Boss: *"Can you analyse one more thing: keep both Donchian strategies open only till 10 am, and exit if any
order gets Rs 1.5k per order, and Rs 1k as loss. What will be the results?"*

- **Pre-registered:** `research/hunt/r112_donchian_morning/PREREG.md`, commit b7aeaeb, pushed before any number.
- **Order of steps:** code, 18 tests and the design read were committed in b323cbe before the one final read
  (`results/FINAL_READ`), then the post window (`results/POST_READ`).
- **Code, tests and every table:** `research/hunt/r112_donchian_morning/` (`results/report.md`).
- **Entries:** `research/experiments/entries/r112-*.yaml` (10 tests, all rejected).
- **Signal source of every rule: the index.** R72 = 4 of 5 indices break their Donchian-20 channel on the same 5-minute
  bar; R51 = the index's own Donchian-20 break with Supertrend (10, 3) agreeing, as on paper since 16 Oct. The bought
  option is the 1-ITM weekly, as today. The +Rs 1,500 target and the -Rs 1,000 stop sit on the option premium, solved for
  each order from its own fill, lot and charges.
- **Same engine as the arms' records:** today's rules reproduce R101's and R73's published figures exactly (R72
  3,071 trades / Rs 2,16,929 in design, 1,202 / Rs 1,71,366 in the final; R51 + Supertrend 4,208 / Rs 50,425 and
  1,670 / Rs 1,34,743). 1 lot, real option prints, dated Zerodha charges, real slippage, gaps through a stop filled at
  the next price.

## The answer first

**Boss's rule makes almost nothing, against about Rs 3 lakh for today's rules. Don't use it.**

Boss's rule = new trades only until 10:00, each order out at +Rs 1,500 or -Rs 1,000 (after charges), otherwise the
arm's usual exits. Final period (1 Apr - 13 Oct 2026, 133 sessions), both arms, 1 lot:

- **Boss's rule:** 1,087 trades, **win rate 42%**, **net Rs 4,919**.
- **Today's rules:** 2,872 trades, win rate 45%, net **Rs 3,06,109**.
- With the app's same-direction cap of 2: Boss's rule **-Rs 5,607**, today's **Rs 62,060**.
- In design (Nov 2024 - Mar 2026): Rs 22,228 against Rs 2,67,355. In the walk-forward year (Apr 2025 - Mar 2026):
  Rs 58,374 against Rs 5,49,630.

**Which part does what:**

1. **The 10:00 cut on its own is fine.** Morning trades alone kept most of the money: Rs 2,38,376 in the final (78% of
   today's), on a third of the trades, with a 52% win rate and a worst day of -Rs 28,128 (today: -Rs 42,305). In design it
   even made more than today (Rs 4,70,833 against Rs 2,67,355). But it made less than today in the walk-forward year and
   the final, so it does not pass.
2. **+Rs 1,500 / -Rs 1,000 is what loses the money.** All day with those exits: -Rs 1,70,223 in the final, -Rs 4,63,455
   in design.
   - On paper it needs only a 40% win rate (1,000 / 2,500).
   - In practice it needs 42-43%, because many orders leave on the 30-minute time exit before reaching either level, and
     a gap can push a loss past Rs 1,000.
   - The arms then win only 40%. The -Rs 1,000 stop sits only 3-10% below the fill (BANKNIFTY 4.5%, MIDCPNIFTY 3.3%),
     so ordinary wiggles stop out about half of all orders. The +Rs 1,500 cap cuts off the big winners that pay for the
     losers.
3. **Both together:** the morning cut shrinks the trade count and the 1,500 / 1,000 exits shrink each trade's edge to
   about zero.

**Which reading did Boss mean?** Most likely **"take new trades only until 10:00"** (reading A, above). The other
reading, "nothing open after 10:00" (last entry ~09:55, everything closed at 10:00), gives the same answer: Rs 26,831 in
the final against Rs 3,06,109, and -Rs 6,251 in design.

**Gate: no rule passes, on either arm** (10 tests). None beat today's rules in all three periods. Even the best ones
are only coin-flip-strong against a random twin (p 0.03-0.14), so q = 1.0 over 5,829 tests.

**What can go on paper: nothing from R112. Nothing goes live.**

One lead for Boss, which is **not** a pass: **morning-only with everything closed at 10:00, but today's exits**
(CUT10X).
- On R51 alone it beat today's rules in design and the final, but not in the walk-forward year: Rs 2,32,582 against
  Rs 50,425 in design, Rs 1,49,855 against Rs 1,34,743 in the final, Rs 1,64,320 against Rs 2,13,748 in the
  walk-forward year. On R72 alone it made less than today in the walk-forward year and the final.
- In the app's capped book (R72 + R51 together, the cap of 2) it beat today's book in all three periods: Rs 73,255
  against -Rs 1,66,185 in design, Rs 42,461 against -Rs 15,177 in the walk-forward year, Rs 64,269 against Rs 62,060 in
  the final. It did that with about a quarter of the trades and smaller worst days.
- That comparison was not one of the gated tests (the capped book was descriptive), and the final is not fresh. It
  would need its own pre-registered test before any paper change.

## One table: both arms, 1 lot, after charges and slippage

n = trades. "Needs" = the break-even win rate from the rule's own trades (average loss / (average win + average loss)).
"Bad months" = months at or below -Rs 13,000 (Boss's account rule). Green days are % of the days with a trade.

| period | rule | n | win (needs) | net | avg win / avg loss | green days | worst day | max drawdown | bad months |
|---|---|---|---|---|---|---|---|---|---|
| design (337 sessions) | **today's rules** | 7,279 | 45% (44%) | Rs 2,67,355 | Rs 1,736 / -Rs 1,378 | 45% | -Rs 1,08,351 | Rs 4,00,609 | 6 of 17 |
| | only the 10:00 cut | 2,282 | 50% (45%) | Rs 4,70,833 | Rs 2,168 / -Rs 1,783 | 44% | -Rs 52,554 | Rs 1,58,939 | 5 of 17 |
| | only +1,500 / -1,000 | 7,647 | 40% (43%) | -Rs 4,63,455 | Rs 1,260 / -Rs 937 | 42% | -Rs 25,559 | Rs 5,18,185 | 12 of 17 |
| | **Boss's rule (A)** | 2,737 | 42% (42%) | Rs 22,228 | Rs 1,393 / -Rs 1,006 | 38% | -Rs 20,660 | Rs 1,50,956 | 6 of 17 |
| | closed at 10:00 (B), today's exits | 2,185 | 50% (44%) | Rs 4,43,549 | Rs 2,020 / -Rs 1,615 | 46% | -Rs 52,554 | Rs 1,54,736 | 4 of 17 |
| | Boss's rule (B) | 2,650 | 42% (42%) | -Rs 6,251 | Rs 1,334 / -Rs 973 | 39% | -Rs 20,660 | Rs 1,81,349 | 6 of 17 |
| walk-forward (Apr 2025 - Mar 2026) | **today's rules** | 5,282 | 47% (44%) | Rs 5,49,630 | Rs 1,695 / -Rs 1,318 | 49% | -Rs 51,451 | Rs 1,53,852 | 3 of 12 |
| | only the 10:00 cut | 1,612 | 52% (46%) | Rs 3,87,179 | Rs 2,090 / -Rs 1,758 | 45% | -Rs 52,554 | Rs 1,29,973 | 3 of 12 |
| | only +1,500 / -1,000 | 5,520 | 42% (43%) | -Rs 1,51,438 | Rs 1,235 / -Rs 932 | 44% | -Rs 23,887 | Rs 2,00,017 | 7 of 12 |
| | **Boss's rule (A)** | 1,926 | 44% (42%) | Rs 58,374 | Rs 1,375 / -Rs 1,012 | 38% | -Rs 20,660 | Rs 1,06,424 | 4 of 12 |
| | closed at 10:00 (B), today's exits | 1,553 | 51% (45%) | Rs 3,38,150 | Rs 1,952 / -Rs 1,583 | 46% | -Rs 52,554 | Rs 1,35,536 | 3 of 12 |
| | Boss's rule (B) | 1,884 | 43% (42%) | Rs 31,693 | Rs 1,320 / -Rs 975 | 41% | -Rs 20,660 | Rs 1,30,195 | 4 of 12 |
| final (1 Apr - 13 Oct 2026, 133 sessions) | **today's rules** | 2,872 | 45% (42%) | Rs 3,06,109 | Rs 1,692 / -Rs 1,205 | 49% | -Rs 42,305 | Rs 1,37,028 | 1 of 7 |
| | only the 10:00 cut | 932 | 51% (43%) | Rs 2,38,376 | Rs 1,854 / -Rs 1,384 | 52% | -Rs 28,128 | Rs 86,643 | 2 of 7 |
| | only +1,500 / -1,000 | 2,982 | 40% (43%) | -Rs 1,70,223 | Rs 1,228 / -Rs 914 | 40% | -Rs 22,824 | Rs 2,09,705 | 4 of 7 |
| | **Boss's rule (A)** | 1,087 | 42% (42%) | Rs 4,919 | Rs 1,339 / -Rs 971 | 46% | -Rs 15,077 | Rs 94,578 | 3 of 7 |
| | closed at 10:00 (B), today's exits | 912 | 52% (41%) | Rs 2,93,761 | Rs 1,841 / -Rs 1,293 | 52% | -Rs 28,128 | Rs 75,869 | 1 of 7 |
| | Boss's rule (B) | 1,068 | 44% (43%) | Rs 26,831 | Rs 1,281 / -Rs 959 | 45% | -Rs 15,077 | Rs 75,163 | 2 of 7 |
| post 14-19 Oct (4 sessions) | today's rules | 71 | 52% (51%) | Rs 2,788 | Rs 1,567 / -Rs 1,624 | 2 of 4 | -Rs 31,929 | Rs 39,298 | 0 of 1 |
| | only the 10:00 cut | 28 | 54% (58%) | -Rs 5,571 | Rs 1,830 / -Rs 2,540 | 1 of 3 | -Rs 26,242 | Rs 29,960 | 0 of 1 |
| | only +1,500 / -1,000 | 81 | 35% (46%) | -Rs 22,575 | Rs 1,333 / -Rs 1,130 | 2 of 4 | -Rs 25,152 | Rs 30,386 | 1 of 1 |
| | **Boss's rule (A)** | 30 | 23% (47%) | -Rs 19,741 | Rs 1,501 / -Rs 1,315 | 1 of 3 | -Rs 17,512 | Rs 22,758 | 1 of 1 |
| | closed at 10:00 (B), today's exits | 28 | 54% (69%) | -Rs 14,477 | Rs 995 / -Rs 2,262 | 1 of 3 | -Rs 26,242 | Rs 26,347 | 1 of 1 |
| | Boss's rule (B) | 30 | 23% (64%) | -Rs 25,042 | Rs 744 / -Rs 1,315 | 0 of 3 | -Rs 17,512 | Rs 25,042 | 1 of 1 |

These are the two arms run side by side with no book rules. Each arm alone, and the book with and without the cap, are
below.

## Each arm, final period (1 Apr - 13 Oct 2026), net (n, win)

| rule | R72 Breadth Donchian | R51 Donchian 20 + Supertrend | both, app's cap of 2 | both, one book, no cap |
|---|---|---|---|---|
| today's rules | Rs 1,71,366 (1,202, 46%) | Rs 1,34,743 (1,670, 45%) | Rs 62,060 (1,226, 43%) | Rs 1,48,965 (2,044, 45%) |
| only the 10:00 cut | Rs 1,12,986 (366, 52%) | Rs 1,25,389 (566, 50%) | Rs 59,918 (348, 48%) | Rs 1,18,426 (583, 50%) |
| only +1,500 / -1,000 | -Rs 68,210 (1,251, 40%) | -Rs 1,02,013 (1,731, 40%) | -Rs 1,14,374 (1,417, 39%) | -Rs 1,56,277 (2,133, 40%) |
| **Boss's rule (A)** | Rs 5,147 (413, 43%) | -Rs 228 (674, 42%) | -Rs 5,607 (446, 41%) | -Rs 2,597 (696, 42%) |
| closed at 10:00 (B), today's exits | Rs 1,43,906 (366, 53%) | Rs 1,49,855 (546, 51%) | Rs 64,269 (332, 48%) | Rs 1,47,291 (563, 50%) |
| Boss's rule (B) | Rs 11,848 (413, 44%) | Rs 14,983 (655, 44%) | Rs 1,257 (429, 42%) | Rs 12,614 (677, 44%) |

Design, each arm (today's / 10:00 cut / 1,500-1,000 / Boss's A / closed at 10 / Boss's B):
- R72: Rs 2,16,929 / Rs 2,56,410 / -Rs 1,61,066 / Rs 47,322 / Rs 2,10,966 / Rs 28,197.
- R51 + Supertrend: Rs 50,425 / Rs 2,14,424 / -Rs 3,02,389 / -Rs 25,094 / Rs 2,32,582 / -Rs 34,449.
- App's capped book: -Rs 1,66,185 / Rs 9,163 / -Rs 3,60,679 / -Rs 75,779 / Rs 73,255 / -Rs 83,160.

Walk-forward year, each arm, same order:
- R72: Rs 3,35,882 / Rs 2,27,459 / -Rs 19,360 / Rs 48,530 / Rs 1,73,830 / Rs 34,578.
- R51: Rs 2,13,748 / Rs 1,59,720 / -Rs 1,32,078 / Rs 9,844 / Rs 1,64,320 / -Rs 2,885.
- Capped book: -Rs 15,177 / -Rs 7,042 / -Rs 1,67,006 / -Rs 36,586 / Rs 42,461 / -Rs 41,844.

The cap cuts every version a lot because R72 and R51 often fire the same way at the same time, and the cap holds only
2 positions a way. That is R108's finding again.

## Break-even for +Rs 1,500 / -Rs 1,000

- If every order ended exactly at +Rs 1,500 or -Rs 1,000 after charges, the break-even win rate would be **40%**
  (1,000 / (1,500 + 1,000)).
- In the arms' own trades it is **42-43%**:
  - many orders leave on the 30-minute time exit with a small win or loss (average win Rs 1,230-1,390, not Rs 1,500);
  - gaps push some losses past Rs 1,000 (the worst order lost Rs 4,218);
  - the arms win only 40-44%.

Where the levels sit on a typical premium (median 1-ITM entry of the arms in design, lots of 15 Oct 2026):

| index (lot) | typical premium | round-trip charges | +Rs 1,500 needs | -Rs 1,000 is at | loss at today's -15% stop |
|---|---|---|---|---|---|
| NIFTY (65) | Rs 149 | Rs 70 | +16.2% | -9.4% | Rs 1,523 |
| BANKNIFTY (30) | Rs 657 | Rs 94 | +8.1% | -4.5% | Rs 3,046 |
| FINNIFTY (60) | Rs 317 | Rs 92 | +8.4% | -4.6% | Rs 2,940 |
| MIDCPNIFTY (120) | Rs 215 | Rs 108 | +6.2% | -3.3% | Rs 3,976 |
| SENSEX (20) | Rs 461 | Rs 68 | +17.0% | -10.0% | Rs 1,449 |

On BANKNIFTY, FINNIFTY and MIDCPNIFTY the -Rs 1,000 stop is 3-5% below the fill. A 1-ITM option moves that much in a
minute or two of ordinary noise.

## The gates (PREREG section 5)

| arm | rule | design / walk-forward / final net vs today | twin p | BH q (5,829 tests) | passed |
|---|---|---|---|---|---|
| R72 | 10:00 cut | better / worse / worse | 0.071 | 1.0 | no |
| R72 | +1,500 / -1,000 | negative in all | 1.0 | 1.0 | no |
| R72 | Boss's rule (A) | worse in all | 1.0 | 1.0 | no |
| R72 | closed at 10:00 | better (net within 5%, far less risk) / worse / worse | 0.033 | 1.0 | no |
| R72 | Boss's rule (B) | worse in all | 1.0 | 1.0 | no |
| R51 | 10:00 cut | better / worse / worse | 0.143 | 1.0 | no |
| R51 | +1,500 / -1,000 | negative in all | 1.0 | 1.0 | no |
| R51 | Boss's rule (A) | worse in all; negative in design and final | 1.0 | 1.0 | no |
| R51 | closed at 10:00 | better / worse / better | 0.062 | 1.0 | no |
| R51 | Boss's rule (B) | worse in all; negative in design and walk-forward | 1.0 | 1.0 | no |

Two kinds of random twin were used:
- An entry twin: the same number of trades a day, picked at random from the all-day version.
- An exit twin: each order the rule closed early is sold instead at a random minute before today's exit.

For every rule with +1,500 / -1,000, the random exits beat the rule in all 100,000 twins (p = 1.0). Selling at a random
earlier minute does better than these two levels.

## Limits (said plainly)

- **The final is not fresh.** R51-R111 have all read 1 Apr - 13 Oct 2026.
- **The paper days after 19 Oct are not in the data.** The lake ends 19 Oct, and Dhan refused the fetch for 20-26 Oct
  (token expired, HTTP 401), so the post window is 4 sessions. Four sessions decide nothing.
- **The capped book holds only the two Donchian arms.** Liquidity and R52 also use places under the app's cap, so their
  absence flatters every capped row a little. Solo 2.0 is not modelled (as R108).
- **The 10:00 close (reading B)** sells at the open of the 10:00 minute. Reading B's last entry is the 09:50 bar, bought
  at ~09:55.

## Files

- `research/hunt/r112_donchian_morning/PREREG.md`: the plan, pushed first.
- `r112cfg.py`, `r112lib.py` (pure, tested), `r112study.py` (data), `r112report.py`, `test_r112.py` (18 tests,
  including look-ahead guards on the cut, Supertrend and the engine).
- `results/design.json`, `final.json`, `post.json`, `decision.json`, `report.md`, `FINAL_READ`, `POST_READ`.
