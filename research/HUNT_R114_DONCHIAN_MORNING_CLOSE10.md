# R114: the Donchian arms in the morning only, closed at 10:00, in the app's full paper book (26 Oct 2026)

**Answer: it did not pass. Nothing changes on paper or live.** Letting R72 and R51 + Supertrend buy only until 09:55 and
selling them at 10:00 made the app's four-arm book better than today's in design, walk-forward and final, with a smaller
worst day and drawdown. But random "twin" books do as well: the gain comes from taking fewer Donchian trades, and
dropping the same number at random gives most of it. The 10:00 sale itself adds nothing (twin p 0.50). BH q = 1.0 over
5,841 tests; the gate needs 0.10.

Research only. Pre-registration: `research/hunt/r114_donchian_close10/PREREG.md`. It was first committed as "R113"
in `749734f` before any number. It was renumbered because R113 is the time-of-day map. Only the name changed, plus one
stricter amendment: R113's 11 tests are added to the BH count.

## What was tested

- **Today's book (BOOK0):** the four paper arms, in the app's order and sharing the cap of 2 open a way across all
  indices (R108's model of the app, checked against R108's published figures to the rupee). The arms are Liquidity 15+5,
  R72 Breadth Donchian, R51 Donchian 20 + Supertrend (10, 3) and R52 Open outside value. Entries run 09:20-15:10, with
  today's exits.
- **X10 (the one test):** R72 and R51 take only signals whose 5-minute bar closes by 09:55. Anything still open is sold
  at the open of the 10:00 minute, and today's exits apply otherwise. Liquidity and R52 are unchanged; they can use the
  places the Donchian arms free up.
- **Signal source of every rule: the index.** Each arm reads the index's own bars and buys the 1-ITM weekly option.
  Stops and locks are on the option premium.
- Engine and costs: R106's engine with R17 fills (stops 10 bps worse plus 2x the half-spread, gaps fill at the open),
  dated Zerodha charges, 1 lot.

## The result (four-arm book, cap of 2 as the app runs it)

| period | book | trades (n) | win % | net Rs | worst day | max drawdown | months <= -13k | days <= -13k | Rs 40k breaches |
|---|---|---|---|---|---|---|---|---|---|
| design 20 Nov 24 - 31 Mar 26 (337 sessions) | today | 3,271 | 42 | -1,33,339 | -27,108 | 2,32,715 | 6 | 9 | 1 |
| | **X10** | 1,340 | 44 | **1,45,385** | -16,236 | 45,664 | 3 | 1 | 1 |
| walk-forward 1 Apr 25 - 31 Mar 26 (247 sessions) | today | 2,387 | 44 | 21,247 | -14,528 | 1,30,106 | 3 | 5 | 1 |
| | **X10** | 979 | 46 | **1,32,920** | -12,661 | 45,664 | 1 | 0 | 1 |
| final 1 Apr - 13 Oct 26 (133 sessions; read many times before) | today | 1,310 | 43 | 40,786 | -14,149 | 57,479 | 2 | 2 | 1 |
| | **X10** | 521 | 44 | **55,912** | -12,204 | 29,835 | 0 | 0 | 0 |
| post 14-19 Oct 26 (4 sessions, not gated) | today | 39 | 44 | 7,325 | -5,227 | 8,798 | 0 | 0 | 0 |
| | X10 | 22 | 41 | 987 | -4,444 | 4,841 | 0 | 0 | 0 |

Gates: 1 (net above today's in all three periods) **holds**; 2 (Boss's account rule no worse) **holds**; 4 (at least 30
final trades differ; 931 do) **holds**; 3 (beat the random twins at BH q <= 0.10) **fails**:

- **Refusal twin** (100,000 books, seed 113). Today's final book with the same number of Donchian trades removed at
  random each day (857 removed in all). Mean Rs 39,599; 26% of twins did as well as X10 (p 0.265).
- **Exit twin.** The 69 X10 trades that the 10:00 sale cut short are instead sold at a random minute before their own
  exit. Mean Rs 55,940, the same as X10 (p 0.502). The 10:00 clock adds nothing; most morning trades are already out
  before 10:00.
- p = 0.502 (the larger), q = 1.0 over m = 5,841 (R64-R111 5,819, R112 10, R113 11, R114 1).

## What it means, plainly

1. **Fewer Donchian trades help the capped book, and the time cut is one way to get fewer.** Today, R51 + Supertrend
   loses inside the book: -Rs 3,09,075 in design on 1,847 trades and -Rs 25,255 in the final on 739. Its afternoon
   entries also fill the cap's places. X10 cuts R51's book trades to 472 in design (-Rs 5,390) and 148 in the final
   (+Rs 674).
2. **The freed places are not free money.** Liquidity and R52 gained 161 trades worth +Rs 20,925 in design, but
   71 trades worth **-Rs 14,100** in the final. Liquidity's own record is weak in the final: 357 trades, Rs 11,155
   alone.
3. **10:00 is not a sharp edge, but the later clocks fade.** Final nets in the capped book (n): 09:45 Rs 57,970 (463),
   10:00 Rs 55,912 (521), 10:15 Rs 41,803 (559), 10:30 Rs 31,265 (594), 11:00 Rs 3,489 (646). Earlier is better in the
   final, while in design 10:00 was best. That pattern fits R113's finding that the arms earn almost all their money on
   09:15-09:30 entries.
4. **Without the cap the picture changes.** No-cap final: X10 Rs 1,67,094 (969 trades) against today's Rs 1,68,769
   (2,450 trades), so roughly equal. The morning cut mainly helps because of how the cap shares its places.
5. **Weak evidence either way.** The final window has been read by R51-R113 and this lead was found in it, so even a
   pass would have been weak. The only data no study has seen are the sessions after 19 Oct. They are not in the lake
   (Dhan's token expired; `r114study.py forward` refuses until they are). The 4 post sessions (14-19 Oct, already read
   by R106 / R112) went the other way: X10 Rs 987 against Rs 7,325. That is too few to mean anything. R113's reading of
   the phone's paper record for 19-26 Oct (points only, 27 round trips) had R72's four 11:00-13:00 entries all winning
   and its two 09:20 entries losing on 26 Oct. That is also far too few to judge.

## Checks

- Parity, asserted in code:
  - Each arm alone reproduces R101's and R73's published trades and net.
  - With plain R51, the four-arm book reproduces R108's figures exactly: capped design -Rs 1,28,786 (3,456 trades),
    walk-forward Rs 25,383, final Rs 15,955 (1,384); no-cap design Rs 2,30,649 (6,520), final Rs 1,38,560 (2,538).
  - R112's two-arm view reproduces its published CUT10X: design Rs 73,255 (862), final Rs 64,269 (332).
- The final was read once (`results/FINAL_READ`); the post window once (`results/POST_READ`).
- Tests: `research/hunt/r114_donchian_close10/test_r114.py` has 12 tests on synthetic data. They cover the 09:55 cut and
  10:00 close, a look-ahead guard (data after the entry minute changes nothing), the cap freeing a place for Liquidity,
  statistics (empty data included), both twins, BH over 5,841 and the gates.

## Files

- Code: `research/hunt/r114_donchian_close10/` (`r114cfg.py`, `r114lib.py`, `r114study.py`, `r114report.py`).
- Results: `results/design.json`, `final.json`, `post.json`, `decision.json`, and `report.md` with every view: the
  no-cap books, each arm alone, every clock, and the per-arm trades and refusals inside the book.
- Log: `research/experiments/entries/r114-x10.yaml` (rejected).

## Next (not tested here)

- When sessions after 19 Oct are in the lake, `r114study.py forward` reads them once, labelled "after R114's
  pre-registration". This is the only fresh evidence possible.
- R113's "one opening bet, not two" idea (R72 and R51 take the same 09:20 break) aims at the same doubled risk more
  directly. It would need its own pre-registration.
