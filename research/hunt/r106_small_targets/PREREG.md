# R106 pre-registration: fixed small rupee targets on the paper arms

Written 26 Oct 2026, **before any R106 number was computed** (committed and pushed on `claude/r106-small-targets` ahead
of the code's first run). Research only; nothing in `android/**` changes.

Boss, 26 Oct: *"I would like to check if small orders could also work: like 5 orders each give me Rs 1,000. That will
also work for me. I'm not expecting Rs 20,000-30,000 per day. Will some changes like this work?"*

Signal source for every rule: **the index** (CLAUDE.md default). The arms' entry signals are unchanged; only the exit
of the bought option changes (a fixed rupee target and, in half the rules, a rupee stop, both on the option premium,
which CLAUDE.md allows). Re-entries re-use the arm's own index signal while it is still alive (section 2).

Not duplicated: R105 (branch `claude/r105-donchian-giveback`) studies Donchian's give-back with new lock variants.
R106 adds no lock or trail; it only caps the win at a fixed rupee amount.

## 1. The arms (B0 = the rule on paper today, reproduced first)

Engine, stores, fills, charges and taking rules exactly as R101 (`research/hunt/r101_arms_enhance/`): R51's Amendment A
clean stores (`R51_CLEAN=1`), R64's held-contract simulator, R17 liquidity fills, dated Zerodha charges, 1 lot, next
minute fills, stops on the minute low. The candidates are R101's own (`r101arms.UNITS`), so each B0 must reproduce
R101's published figure before any rule runs (asserted in code):

| arm | today's exits | B0 must reproduce (design / final) |
|---|---|---|
| Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5) | -15% premium stop, 20-min time stop unless +5%, index exits, 15:10; one position a book; expiry days skipped | 881 / Rs 2,57,249 ; 357 / Rs 11,155 |
| R72 Breadth Donchian (4 of 5) | XL: -15% stop, ladder + L25, 30-min time stop, 15:15; one open a side, 2 fills a side a day | 3,071 / Rs 2,16,929 ; 1,202 / Rs 1,71,366 |
| R51 Donchian 20 | XL as above | 4,498 / Rs 9,998 ; 1,781 / Rs 81,845 |
| R52 Open outside value | R64 A4 + L25: -30% stop, ladder + L25, 60-min time stop, 15:10; one a day an index | Rs 2,90,573 ; Rs 48,612 |

**Solo 2.0 (R57 A2)** is included only if its published final B0 (128 trades, Rs 99,172) reproduces with R57's own
pipeline in this session (its own stores, brain features and monthly refits); its picks are then kept as they are and
only their exits change. If it does not reproduce, it is reported as not run, with the reason.

Not in R106, with the reason: the Hero Rs 1,000 ticket (a lottery-style expiry ticket whose whole design is the rare
multi-x winner; R21-R98 exhausted); FT3 (a frozen forward test: any change starts a new test); MCX paper arms (another
lake, other charges; keeps the family small); Night, VIX divergence, Solo midday (hidden).

## 2. The rules (fixed now; 9 rules, each run on every arm = 36 tests, 45 with Solo 2.0)

Every amount is **per lot, net of the trade's own dated charges and slippage** (the engine's real fills).

- **Target T** (rupees): a resting limit sell at the premium X where `(X - real entry) x qty - charges(entry, X) = T`
  (solved per trade from its real entry fill; a limit fills at its price, as every R51-family target). The arm's
  other exits (stop, ladder locks, time stop, index exits, square-off) stay; whichever comes first exits.
- **Stop "own"**: the arm's own premium stop, unchanged.
- **Stop "1:1"**: the arm's premium stop is replaced by a resting stop at the premium S where the stop sale (R17 stop
  fill: -10 bps and twice the half-spread, the spread read from the 5 minutes before the entry) nets exactly `-T`
  after charges. A gap through S still fills at the minute's open (so a loss can exceed T; reported). If the whole
  premium is worth less than T, there is no premium stop.
- **Re-entry "RE5"**: after a trade exits AT ITS TARGET, the arm buys again in the same direction at the open of the
  next minute (1-ITM from the minute before, same exits), if (i) the entry row is within the arm's entry hours
  (Liquidity <= 14:00, Donchian / R72 <= 15:10, OOV <= 15:10), (ii) the signal is still alive: Liquidity: its index
  exits (index stop, next-level target, failed break, new level) have not fired by that row; the others: the index
  close at the target minute is still beyond the signal bar's close in the signal's direction, and (iii) the arm has
  fewer than 5 fills on that index that day. A re-entry occupies the book / side like any position; it does not use
  up Donchian's "2 fills a side" for fresh signals. A re-entry that exits at its target may re-enter again.
- **Day goal "DAY5K"**: RE5 plus, per arm and day (all its indices together, in time order): no new entry once 5
  trades were taken that day, or once the trades already closed that day net >= +Rs 5,000.

| id | target | stop | more entries |
|---|---|---|---|
| T500 | Rs 500 | own | - |
| T1000 | Rs 1,000 | own | - |
| T1500 | Rs 1,500 | own | - |
| T500S | Rs 500 | 1:1 | - |
| T1000S | Rs 1,000 | 1:1 | - |
| T1500S | Rs 1,500 | 1:1 | - |
| T1000R | Rs 1,000 | own | RE5 |
| T1000SR | Rs 1,000 | 1:1 | RE5 |
| DAY5K | Rs 1,000 | 1:1 | RE5 + day goal (5 trades or +Rs 5,000) |

No threshold is fitted to data: Boss named the amounts (Rs 500 / 1,000 / 1,500, 5 trades, Rs 5,000).

## 3. Periods

- Design 20 Nov 2024 - 31 Mar 2026, with the walk-forward window Apr 2025 - Mar 2026 (four quarters; nothing is fitted,
  so the walk-forward window is reported as its own period).
- Final 1 Apr - 13 Oct 2026, read ONCE (`results/FINAL_READ`). Already read by R51-R104: weak evidence.
- Post: 14 - 19 Oct 2026 (the lake's last 4 sessions; stores built to 19 Oct in their own folder so the earlier
  stores stay exactly R101's), read once after the final (`results/POST_READ`). Tiny; reported, never gated.

## 4. What is reported (every rule and B0, every period)

Trades, win rate, net, P&L per trading day, % green days, worst day, max drawdown (daily), net without the 5 best
trades, the exit mix (target / stop / other), the largest single loss; and the **break-even win rate**: the average
net loss / (average net win + average net loss) of the rule's own trades, against the win rate achieved. A short table
of the exact round-trip charges for a typical lot (Zerodha dated charges, R17 fills) gives the premium move each
target needs.

"5 trades x Rs 1,000": DAY5K's day distribution per arm and for all arms together (the four arms' T1000SR trades merged,
the day goal applied across them): median day, % of days reaching +Rs 5,000, % green days, worst day.

## 5. Gates (all must hold; never relaxed)

1. Net > 0 in design, in the walk-forward window and in the final.
2. Better than B0: net above B0 in the walk-forward window AND the final; OR net within 5% of B0's in both AND worst
   day and max drawdown each at least 20% smaller than B0's in both.
3. Beats its exit twin: **random-exit twins**, final period, 500 sets (seed 106). The twin keeps the rule's own entries
   (re-entries included) and, for every trade the rule's target or 1:1 stop closed earlier than the arm's own exits
   would have (the trade priced again with the arm's own exits from the same entry), sells it instead at the open of a
   uniformly random minute strictly after its fill and before that own-exit row; p = (1 + #twins with total net >=
   the rule's) / 501.
4. Benjamini-Hochberg q <= 0.10 on those p, counted against **every test of the earlier target / lock / arm-filter
   families** as well as R106's own (R64 15, R65 30, R66 15, R67 15, R68 21, R69 15, R70 4, R71 16, R72 12, R73 72,
   R74 11, R75 40, R101 20, R104 227 = 513 earlier tests, taken as p = 1 since none passed); q within R106 alone is also
   shown.
5. At least 40 final trades.

## 6. What a pass means (CLAUDE.md)

A passing rule may act on PAPER for that arm straight away: a labelled switch, logged in the Autopilot change timeline,
undoable in one tap, shown to Boss, with the arm's plain-rules record kept rebuildable. Nothing goes live from R106.
A failed rule stays a note here and in `research/experiments/entries/r106-*.yaml`.
