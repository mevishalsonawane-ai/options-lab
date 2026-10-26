# R112 pre-registration: the two Donchian arms, mornings only, +Rs 1,500 / -Rs 1,000 an order

Written 26 Oct 2026, **before any R112 number was computed** (committed and pushed alone on
`claude/r112-donchian-morning-1500` ahead of the code's first run). Research only; nothing in `android/**` changes.

Boss, 26 Oct: *"Can you analyse one more thing: keep both Donchian strategies open only till 10 am, and exit if any
order gets Rs 1.5k per order, and Rs 1k as loss. What will be the results?"*

**Signal source of every rule: the index** (CLAUDE.md default). The entry signals are the arms' own Donchian breaks on
the index's 5-minute bars; the option bought is the 1-ITM weekly, as today. The +Rs 1,500 target and -Rs 1,000 stop sit
on the option premium (CLAUDE.md allows stops and targets on the premium).

## 1. The arms ("today's rules", B0)

Engine, stores, fills, charges and taking rules exactly as R101 / R106 / R108: R51's Amendment A clean stores
(`R51_CLEAN=1`), R64's held-contract simulator through R106's engine (which adds per-trade rupee levels), R17 liquidity
fills, dated Zerodha charges, 1 lot, next-minute fills, stops on the minute low, a gap through a stop filled at the
minute's open. Entries 09:20-15:10, one open position a side per index, at most 2 fresh fills a side a day, never on the
index's expiry day, NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX.

| arm | signal (index, 5-minute bars) | today's exits (XL) |
|---|---|---|
| R72 Breadth Donchian | 4 of 5 indices close beyond their Donchian-20 channel on the same bar (R72 C1, `r101arms.r72_units`) | -15% premium stop, A4 ladder (30/60/100) + L25, out 30 min after the fill, 15:15 square-off |
| R51 Donchian 20 + Supertrend | the index's own Donchian-20 break, taken only when Supertrend (10, 3) on the same 5-minute bars points the same way at the signal bar (R73 T7, as on paper since 16 Oct; `r73rules.trend_ok("T7")` on `r73rules.features`) | as R72 |

Parity first (asserted in code): R72 and plain R51 (no Supertrend) must reproduce R101's published design / final trades
and net (R72 3,071 / Rs 2,16,929 and 1,202 / Rs 1,71,366; R51 4,498 / Rs 9,998 and 1,781 / Rs 81,845). R51 with
Supertrend is then the R51 baseline (it has no R101 figure; plain R51 is shown beside it once, as information).

## 2. The rules (fixed now; 5 per arm = 10 gated tests)

- **10:00 entry cut ("CUT")**: only signals whose 5-minute signal bar closes by 10:00 (entry row <= 10:00; the last is the
  09:55 bar, bought at ~10:00). Positions may run past 10:00 until their own exits.
- **+Rs 1,500 / -Rs 1,000 ("TS")**: every order gets a resting limit target at the premium X where
  `(X - real entry) x qty - charges = +1,500` and a resting stop at the premium S where the stop sale (R17 stop fill:
  -10 bps and twice the half-spread from the 5 minutes before the fill) nets `-1,000` after charges; both solved per order
  from its own real fill and its lot (R106 `target_price` / `stop_price`, rounded to the tick on the safe side). The
  -Rs 1,000 stop REPLACES the -15% premium stop. A gap through either level fills at the next price (the minute's open),
  so a loss can exceed Rs 1,000 (reported). The arm's other exits stay: the A4 ladder + L25 locks, the 30-minute time
  exit, 15:15 square-off; whichever comes first exits. If the whole premium is worth less than Rs 1,000 there is no
  premium stop (as R106).
- **Close by 10:00 ("X")**, the second reading of "open only till 10 am": the last entry is the 09:50 signal bar (bought
  at ~09:55) and every position still open is sold at the open of the 10:00 minute (R17 fill), if no other exit came first.

| id | entries | exits | what it answers |
|---|---|---|---|
| B0 | all day (09:20-15:10) | today's | today's rules (not a test) |
| CUT10 | signal bar closes by 10:00 | today's | the 10:00 cut alone |
| TS | all day | +1,500 / -1,000, other exits as today | the 1,500 / 1,000 exits alone |
| M10 | signal bar closes by 10:00 | +1,500 / -1,000 | **Boss's rule, reading A** (entries until 10:00) |
| CUT10X | signal bar closes by 09:50 | today's, all out at 10:00 | the 10:00 close alone |
| M10X | signal bar closes by 09:50 | +1,500 / -1,000, all out at 10:00 | **Boss's rule, reading B** (nothing open after 10:00) |

Nothing is fitted: Boss named 10:00, Rs 1,500 and Rs 1,000. Reading A is pre-registered as the primary reading (a
trader saying "open only till 10 am" most often means taking new trades only until 10); both are gated and reported.

## 3. Periods

- Design 20 Nov 2024 - 31 Mar 2026; walk-forward window 1 Apr 2025 - 31 Mar 2026 (four quarters, inside design; nothing
  is fitted, so it is reported as its own period, as R106).
- Final 1 Apr - 13 Oct 2026, read ONCE (`results/FINAL_READ`). Already read by R51-R111: weak evidence, said so.
- Post: 14 Oct to the lake's last session (19 Oct when written), stores built in their own folder; read once after the
  final (`results/POST_READ`). If 20-26 Oct minutes can be fetched from Dhan into a separate lake folder (never mixed with
  the Drive lake), those sessions are reported too as "the paper days" (R51's Supertrend filter has been on paper since
  16 Oct, the same-direction cap since 19 Oct). Tiny; reported, never gated. If the fetch fails, that is said.

## 4. What is reported (every rule and B0, each arm and both arms together, every period)

Trades (n), win rate, net, average win / average loss, % green days (of days traded) and sessions, worst day, max
drawdown (daily), months at or below -Rs 13,000, the exit mix, the largest single loss, and the **break-even win rate**:
in theory for +1,500 / -1,000 net (1,000 / 2,500 = 40%), and from each rule's own trades (average loss / (average win +
average loss)), against the win rate achieved. A short table of the premium move each level needs per index (typical
premium, lot, charges, as R106's table).

**Same-direction cap.** Each rule is also run through R108's model of the app's book (`r108book.walk_day`, `CAP2`: at most
2 open automatic positions betting the same way across indices, R72 first, then R51, R72's kept place, a refused entry
booked as a would-be trade, "one option, one arm") with **only these two arms** in the book, and with no cap (`NOCAP`,
the same book without the cap). Liquidity and R52 are left out (they are not part of the question); said as a limit.
The cap views are descriptive, never gated.

## 5. Gates (all must hold; never relaxed), per arm and rule, on the arm's own record (no cap)

1. Net > 0 in design, in the walk-forward window and in the final.
2. Better than B0 in design AND the walk-forward window AND the final: net above B0's, or net within 5% of B0's with the
   worst day and max drawdown each at least 20% smaller (R106 gate 2, now in all three periods).
3. Beats a random twin, final period, 100,000 twins (seed 112), at Benjamini-Hochberg q <= 0.10:
   - **entry twin** (rules with an entry cut): per session, the same number of trades as the rule took, drawn at random
     from the all-day rule with the same exits (CUT10, CUT10X: B0; M10, M10X: TS); p = (1 + #twins with net >= the
     rule's) / 100,001;
   - **exit twin** (rules with changed exits: TS, M10, CUT10X, M10X): R106's random-exit twin; for every trade the rule
     closed earlier than today's exits would have (from the same entry), sell it instead at the open of a uniformly random
     minute strictly after its fill and before that exit row;
   - a rule with both takes p = the larger of the two (it must beat both).
   BH counts every earlier Donchian / exit / filter family as p = 1: R64-R107 as R108 counted them (5,777), R108 7,
   R110 25, R111 10 = 5,819 earlier tests, + R112's 10 = 5,829. With 100,000 twins the smallest possible q is about 0.058,
   so only a rule that no twin matches can pass (as R108); q within R112 alone is also shown. An exit twin's sale minute
   is drawn per trade from the rows between its fill and its own exit; each row's net is computed once and reused.
4. At least 40 final trades.

## 6. What a pass means (CLAUDE.md)

A passing rule may act on PAPER for that arm straight away: a labelled switch, logged in the Autopilot change timeline,
undoable in one tap, shown to Boss, with the arm's plain-rules record kept rebuildable. Nothing goes live from R112. A
failed rule stays a note here and in `research/experiments/entries/r112-*.yaml`.
