# R114 pre-registration: the Donchian arms in the morning only, closed at 10:00, in the app's full paper book

Written 26 Oct 2026, **before any R114 number was computed** (committed and pushed alone on
`claude/r112-donchian-morning-1500` ahead of the code's first run). Research only; nothing in `android/**` changes.

> **Renumbered (26 Oct 2026, still before any number).** This pre-registration was first committed as "R113" in
> `749734f` by a session that stopped before running anything. R113 is taken (the time-of-day map,
> `research/HUNT_R113_TIME_OF_DAY.md`), so the study is R114. Only the name changed: "R113" became "R114" (and the
> file names `r114study.py`, `r114-x10.yaml`); every rule, period, gate and the twins' seed (113) are exactly as first
> written. One amendment is in section 4a below, also written before any number; it can only make the gate harder.

## 0. Why, and what is already known

R112 (`research/HUNT_R112_DONCHIAN_MORNING.md`) left a lead, not a pass. "CUT10X" = R72 Breadth Donchian and R51
Donchian 20 + Supertrend take only signals whose 5-minute bar closes by 09:55 (bought ~09:55), and everything still open
is sold at the open of the 10:00 minute; otherwise today's exits. In R112's descriptive view of the app's book with **only
those two arms** and the same-direction cap of 2, it beat today's rules in design (Rs 73,255 vs -Rs 1,66,185),
walk-forward (Rs 42,461 vs -Rs 15,177) and final (Rs 64,269 vs Rs 62,060). That view was not a gated test.

Said plainly:
- **The lead is data-snooped.** It was spotted in R112's tables after its final read, and every period here (design,
  walk-forward, final, post 14-19 Oct) has already been read. Re-running R112's two-arm view would only repeat known
  numbers, so it is not the test.
- **What is not yet computed is the book the app really runs:** all four paper arms (Liquidity 15+5, R72, R51 +
  Supertrend, R52 Open outside value) sharing the cap of 2. Closing the Donchian arms at 10:00 frees places for
  Liquidity and R52 later in the day; whether that helps or hurts is unknown. That is R114's one gated test.
- Only sessions after 19 Oct would be fresh, and they are not in the lake (Dhan's token expired on 26 Oct). Section 6
  pre-registers how they are read once they are.

**Signal source of every rule: the index** (CLAUDE.md default). Every arm keeps its own index signals, 1-ITM option and
exits; R114 changes only when the two Donchian arms may enter and that they are out by 10:00.

## 1. Data, engine, arms (identical to R101 / R106 / R108 / R112)

- Drive lake, R51 Amendment A clean stores (`R51_CLEAN=1`), R106's engine, R17 fills, dated Zerodha charges, 1 lot.
- Arms: Liquidity 15+5 and R52 as R101 priced them (R108's candidates); R72 as R101; R51 with R73's Supertrend (10, 3)
  filter as on paper (R112's `units_of("R51")`).
- The book: R108's model of the app (`r108book.walk_day`): the cap of 2 open positions a way across all indices and arms,
  the app's order (Liquidity's books first, then R72, R51, R52), R72's kept place, a refused Donchian / R52 entry booked as
  a would-be trade, "one option, one arm".
- **Parity first (asserted):** with plain R51 (no Supertrend), the four-arm CAP2 and NOCAP books must reproduce R108's
  published figures: CAP2 design -Rs 1,28,786 (3,456 trades), walk-forward Rs 25,383, final Rs 15,955 (1,384 trades);
  NOCAP design Rs 2,30,649 (6,520), final Rs 1,38,560 (2,538). Then the R51 + Supertrend book is "today's book" (BOOK0).

## 2. The test (one)

| id | R72 and R51 | Liquidity, R52 |
|---|---|---|
| BOOK0 (base, not a test) | today's rules: entries 09:20-15:10, today's exits | today's |
| **X10** | entries only from signal bars closing by 09:55 (entry row <= 40), every open position sold at the open of 10:00 (row 45) if no other exit came first; today's exits otherwise | today's (unchanged) |

Both books carry the cap of 2 as the app runs it. Nothing is fitted (10:00 was Boss's number).

## 3. Periods

Design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (inside design); final 1 Apr - 13 Oct 2026
read once by this study (`results/FINAL_READ`), not fresh (R51-R112 read it); post 14-19 Oct (4 sessions, R106's post
stores), reported, never gated.

## 4. Gates (all must hold; never relaxed)

1. **Net** of the whole four-arm book above BOOK0's in design AND walk-forward AND final.
2. **Boss's account rule at least as well as BOOK0** in each of the three periods: months at or below -Rs 13,000 <=
   BOOK0's; separate Rs 40,000 drawdown breaches <= BOOK0's; days at or below -Rs 13,000 <= BOOK0's (R108 gate 2).
3. **Twins**, final period, 100,000 each (seed 113); p = the larger of:
   - *refusal twin* (R108's): each session, from BOOK0's real Donchian trades remove at random as many as X10 took fewer
     that day (k_d = max(0, BOOK0's Donchian trades - X10's)), keeping BOOK0's Liquidity and R52 trades; statistic = the
     book's total net;
   - *exit twin* (R106 / R112's): each X10 Donchian trade that the 10:00 close sold earlier than today's exits would have
     is sold instead at the open of a uniformly random minute strictly after its fill and before that exit row; the rest of
     the X10 book is kept as it is (the cap's knock-on effects are not re-walked; said as a limit).
   BH q <= 0.10 with m = 5,819 earlier tests (as R112) + R112's 10 + R114's 1 = 5,830; the smallest possible q is
   about 0.058, so only a rule no twin matches can pass.
4. At least 30 final trades differ between X10's and BOOK0's books.

### 4a. Amendment (26 Oct 2026, before any number): count R113's tests too

R113 (time of day, finished the same day) ran 11 pre-registered follow-up tests (none passed;
`research/experiments/entries/r113-*.yaml`). They are counted as p = 1, so m = 5,830 + 11 = **5,841**. The smallest
possible q becomes about 0.058 (1 / 100,001 x 5,841), still under 0.10. Nothing else in the gates changes.

## 5. Reported, never gated (fixed now, so nothing is chosen after seeing it)

- Both books also with NO cap (the same book rules without the cap), and each arm alone.
- **Neighbouring clocks**, in the four-arm capped book, to show whether 10:00 is a knife-edge: last entry / close at
  09:40 / 09:45, 10:10 / 10:15, 10:25 / 10:30, 10:55 / 11:00 (entry rows <= 25 / 55 / 70 / 100, close rows 30 / 60 / 75 / 105).
- Per arm inside the book: trades, net, refused entries; Liquidity's and R52's extra trades from the freed places.
- Trades (n), win rate, net, average win / loss, % green days, worst day, max drawdown, months at or below -Rs 13,000,
  separate Rs 40,000 breaches, days at or below -Rs 13,000, for every view and period.

## 6. The forward read (the only fresh evidence)

When the lake holds sessions after 19 Oct (a renewed Dhan token and the usual append), `r114study.py forward` runs
BOOK0 and X10 on every session from 20 Oct to the lake's end, once, and reports them beside the app's own paper record.
It is reported, never gated, and stays labelled "after R114's pre-registration".

## 7. What a pass means (CLAUDE.md)

A pass may act on PAPER for R72 and R51 straight away: a labelled switch, logged in the Autopilot change timeline,
undoable in one tap, shown to Boss, with the arms' plain-rules record kept rebuildable. R114 itself changes no app code;
the switch would be a separate change. Nothing goes live from R114. A failure stays a note here and in
`research/experiments/entries/r114-x10.yaml`.
