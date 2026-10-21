# R96 round 2 pre-registration: refine the NR7 lead, real-print exits (21 Oct 2026)

Boss, 21 Oct: "go ahead with round 2". Written and committed **before any round-2 statistic**. Everything in
PREREG_ROUND1.md s2-s5 and s7 still holds unless changed here: the same design (5 Aug 2025 - 14 Jul 2026), the same
four walk-forward folds, the **same LOCKED holdout (15 Jul - 19 Oct 2026, never read so far)**, the same costs, twins
(500, seed 96, same book and calendar month), the same hard stop / lock ladder, and BH over the **cumulative ledger**
(round 1's 9 ideas + these 8 = 17).

Signal source of every rule: the MCX future (as round 1). Every trade buys options (no selling); every trade has a hard
stop and a profit lock.

## 1. What round 1 taught (HUNT_R96_ROUND1.md)

- Only N1 (NR7 breakout -> 1-ITM, 3 sessions) showed an edge: design +Rs 1.84 lakh on 58, twin p 0.006, and positive
  against twins on 2012-25 daily futures (1,436). But 7 deep-ITM silver exits priced by Black-76 (strike outside the
  stored ATM+/-3) carried the profit; real-print exits lost on every book.
- Data check (21 Oct, no statistic): the lake holds ATM+/-3 only, on every MCX book; the Dhan token in this environment is
  expired (HTTP 401 DH-901), so wider strikes cannot be fetched this round.

## 2. The change common to all round-2 rules: the EDGE take-profit (real fills only)

At every minute, the held contract is "at the edge" when its strike is the deepest in-the-money strike stored at that
minute or beyond it (call: strike <= the lowest stored strike; put: strike >= the highest). The position is **sold at
the close of the first printed minute at the edge** (exit reason "edge"). If the strike jumps past the edge with no
print there, the position is sold at the contract's next real print; only if none comes before the time exit is a
model price used (flagged). For straddles the rule applies to either leg (both legs are sold). The stop, lock ladder,
underlying stop and time exit are unchanged.

New design gate 8 (round 2 onward): **model-priced exits <= 10% of trades, and the net of the trades with a real-print
exit > 0.**

## 3. Books

The four mini books of round 1, plus for R2-4 and R2-6 the big energy books **CRUDEOIL** (x100) and **NATURALGAS**
(x1250), whose ATM option prints in 96-97% of minutes. Same cost model (Zerodha MCX charges + 0.30% / 0.60%
half-spread, natural gas 0.45% / 0.90%). The big books' 1-lot stop risk is reported (capital for 2%).

## 4. The eight rules (each one BH test, pooled over its books)

Entry next session at the first print at or after 09:15 unless said; 1-ITM unless said; hard stop -40% premium
(straddles -30%); round-1 lock ladder; the edge take-profit (s2) on all.

| id | idea | signal on the future | strike | underlying stop | time exit | books | twin |
|---|---|---|---|---|---|---|---|
| **R2A** | N1 with edge exits | N1 exactly (NR7 day; next session's first minute close beyond its high / low, 09:15-22:00) | 1-ITM | NR7 opposite extreme | 3 sessions | 4 minis | T-tod |
| **R2B** | N1, 2-ITM | as R2A | **2-ITM** | as R2A | 3 sessions | 4 minis | T-tod |
| **R2C** | Crabel ID/NR7 | NR7 day that is also an **inside day** (high < previous high and low > previous low); break as R2A | 1-ITM | as R2A | 3 sessions | 4 minis | T-tod |
| **R2D** | N1 on the big energy books | as R2A | 1-ITM | as R2A | 3 sessions | CRUDEOIL, NATURALGAS | T-tod |
| **R2E** | N1 with a premium cap | as R2A, but skip a trade whose 1-lot stop risk (40% of the premium + Rs 100) > **Rs 6,500** (2% of Rs 3.25 lakh) | 1-ITM | as R2A | 3 sessions | 4 minis | T-tod (same cap) |
| **R2F** | squeeze straddle, relaxed | squeeze on (Bollinger 20,2 inside Keltner EMA20 +/- 1.5 ATR20) on **>= 3 of the previous 5 sessions** and on the previous one; today it is not | ATM straddle | none | 5 sessions | 4 minis + CRUDEOIL, NATURALGAS | T-day |
| **R2G** | NR7 -> straddle | an NR7 day (no break needed) | ATM straddle, next session 09:15 | none | 3 sessions | 4 minis | T-day |
| **R2H** | quiet-base breakout | the close breaks the previous 20 sessions' highest / lowest close (back-adjusted) and an NR7 day occurred in the previous 5 sessions | 1-ITM | 1.0 x ATR14 | 5 sessions | 4 minis | T-day |

Long-history futures check (gate 7) applies to R2A-R2E (the N1 / ID-NR7 signal, as round 1's history.py) and R2H
(next-open entry, 1.0 ATR stop on closes, 5 sessions, 0.10% cost).

## 5. Gates

Round 1's gates 1-7, plus gate 8 (s2). Holdout rules unchanged: only a rule passing all design gates is read on the
locked holdout, once. Lead = design net > 0 with >= 2 positive folds, or twin p < 0.10.

## 6. Known in advance (stated so it can't be explained away later)

- R2A-R2E reuse round 1's N1 signal, so they are not independent of N1 (BH stays valid under positive dependence).
  R2A is the honest re-test of N1; if R2A fails while N1 passed on model prices, the NR7 lead is not tradeable on
  this lake.
- On GOLDM (strikes 100 apart, ATM+/-3 = +/-0.2%) the edge take-profit will close most winners within hours; on
  SILVERM (1,000 apart) within a ~1% move. Energy books keep winners longer. This is a data limit, reported per book.
- The holdout has never been read. A round-2 pass is read once; no rule is changed after that.
