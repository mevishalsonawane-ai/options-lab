# R84 pre-registration: Breadth Donchian, one-signal risk (how many correlated positions per signal)

Written 19 Oct 2026, committed BEFORE any variant is priced. Research only: nothing in `android/**` or `.github/**`.

Boss, 19 Oct: *"Deep analysis and find what happened today with breadth donchian, should not happen again like 9k loss,
so find a best solution for it."*

**What happened (19 Oct, paper).** At 09:30 NIFTY had fallen about 0.42% in 10 minutes, about 11 times its usual
10-minute move, on a NIFTY expiry day with an event. R72 Breadth Donchian fired (4+ of 5 indices broke down on the same
5-minute bar) and, by design, bought a 1-ITM put on every index that broke: BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (NIFTY
skipped, its own expiry day), entries 09:30:33-09:31:54. The market reversed; three hit the -15% stop within 2-3 minutes
and the fourth was closed by the paper daily loss limit: BANKNIFTY -Rs 2,440, SENSEX -Rs 1,765, MIDCPNIFTY -Rs 2,908,
FINNIFTY -Rs 2,609, total -Rs 9,721.

**The question.** Which rule best limits Breadth Donchian's loss on ONE signal, with the least loss of edge? This is
**risk shaping** (how many correlated positions to hold per signal), not new signal tuning. The signal itself (R72 C1,
4 of 5) is unchanged in every variant.

## 0. Signal source

The indices' own 5-minute bars (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), read together (R72 C1). Every variant
below decides from the same index bars (break size in the index's own 5-minute ATR; NIFTY's own 10-minute move) or, for
RB only, the chosen option's last price at the signal minute as a SIZE input (never a signal). No rule reads an option's
chart for its signal. The option bought is the 1-ITM strike, as now.

## 1. Baseline K0 = R75's C exactly

R75's harness, unchanged and imported read-only (`research/hunt/r75/r75study.py`): R64's engine on R51's Amendment A
clean stores (R51_CLEAN=1), R72's `rule_signals(und, "C1", 4)`, R73/R75 candidates (1-ITM, entries 09:20-15:10), exits P
(-15%, 30/60/100 ladder + L25, out after 30 minutes, square-off 15:15), one open position a side per index, at most 2
entries a side a day per index, never on the index's own expiry day, next-minute fills, R17 liquidity fills, dated
charges, 1 lot in force. The study refuses to run unless K0 reproduces R75's published C to the rupee: design
Rs 2,16,929, final Rs 1,71,366.

Periods (R51-R75's): design 20 Nov 2024 - 31 Mar 2026; walk-forward quarters Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025,
Jan-Mar 2026 (inside design, as R75); final 1 Apr - 13 Oct 2026.

## 2. Definitions (fixed now)

- **Signal** = one (day, entry row, side) of C: every index that the C1 rule fires on the same 5-minute bar the same way.
- **Eligible** at a signal = its candidates that K0's own per-index rules would take at that moment: the engine has a
  fill, that index has no open position on that side, and fewer than 2 entries that side that day. A variant then
  chooses a SUBSET of the eligible set. A candidate not chosen is not taken, so its slot stays free for that index's
  later signals (as the app would behave). With the full set chosen, the walk is identical to K0 (a test proves it).
- **Break size** of index u at its signal bar j (R72's Panel, u's own 5-minute series): put `(clo_j - c_j) / atr_j`,
  call `(c_j - chi_j) / atr_j`, where chi/clo = the previous 20 bars' high/low (the Donchian channel) and atr = Wilder
  ATR 14 of u's 5-minute bars. Missing ATR = size 0. Ties: the fixed index order.
- **Fixed index order** ("first to the door"): NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX. This is the order in which
  the app's Breadth arm emits one bar's signals (`BreadthDonchianArm.signals`, `DonchianArm.UNDERLYINGS`), so it is the
  closest fixed stand-in for "the first two booked". Stated: the real app's cap counts ALL automatic arms (Donchian,
  Liquidity, ...), so in the app Breadth may get fewer than 2 slots; R84 models Breadth alone.
- **10-minute move** of NIFTY at signal bar j: `|c_j / o_{j-1} - 1|` (the open of the previous 5-minute bar of the same
  day; at the day's first bar its own open). **Usual move** = the median of the same quantity over every 5-minute bar of
  NIFTY's previous 20 sessions (not the signal day). Shock ratio = move / usual. Fewer than 10 earlier sessions or a
  missing NIFTY bar: ratio unknown = no skip. NIFTY is read even on its own expiry day (it still counts for breadth).
- **Premium risk** of a candidate (RB only) = the chosen option's close at the signal minute (row er - 1, known at the
  decision) x the lot in force x 0.15. Missing price: the candidate's risk is unknown and it is not added (unless first).

## 3. Variants (7 tests, all in BH)

| id | rule at each signal |
|---|---|
| K0 | (baseline) every eligible index |
| K2-first | the first 2 eligible in the fixed index order |
| K2-strong | the 2 eligible with the biggest break size |
| K1-strong | the 1 eligible with the biggest break size |
| K1-BN | BANKNIFTY if eligible, else the biggest break size |
| X-skip | skip the whole signal when NIFTY's shock ratio > k = 5; else every eligible index (as K0) |
| X-K2 | X-skip (k = 5), then K2-strong |
| RB | strongest first: the first always; add the next while the sum of premium risk stays <= Rs 5,000 |

## 4. Report per variant (design, each walk-forward quarter, and the final when read)

Net; trades; win rate; worst day; **worst single signal** (sum of the nets of one signal's trades); max drawdown (daily
closed equity); months worse than -Rs 13,000; drawdown episodes deeper than Rs 40,000 (account stop rule); % green days;
net with the 5 best trades removed; per quarter whether it beat K0 on net AND on worst day (count of 4); random-subset
twin p; BH q; positions dropped vs K0 (losers avoided / winners missed, matched on index, day, side, signal row).

## 5. Random-subset twins (design; seed 84; 500 twins)

Each twin walks the same days with the same eligibility rules, but at each signal it keeps the SAME NUMBER of eligible
indices as the variant's rule would keep from the twin's own eligible set, chosen uniformly at random:
- K2-*: random 2 (all if fewer); K1-*: random 1; RB: the count RB's rule would keep from that set, chosen at random;
- X-skip: skips the same number of signals as X-skip did, drawn at random from K0's design signals; otherwise all;
- X-K2: that random signal skip, then random 2.

p = (1 + #twins with design net >= the variant's design net) / 501. BH over the 7 variants' p.

## 6. Pass rule (decided on design + walk-forward only; ALL must hold)

1. **Risk cut:** design worst day AND design max drawdown are each at least 30% smaller than K0's.
2. **Edge kept:** design net >= 70% of K0's design net, AND the four walk-forward quarters' pooled net >= 70% of K0's
   pooled quarter net.
3. **Not luck of fewer trades:** twin p < 0.10 AND BH q < 0.10.
4. **Not carried by the top 5:** design net without its 5 best trades > 0 and >= 70% of K0's design net without its 5
   best trades.

Reported, not gated: the quarter count beating K0 on net AND worst day, account-stop breaches, green days.

## 7. The final window (weak evidence)

1 Apr - 13 Oct 2026 was already read by R72, R73 and R75. `r84study.py final` reads it ONCE (guarded by
`results/FINAL_READ`), and only for K0 and the variants that passed section 6. It is reported, not used to decide. If
no variant passes, the final is not read at all.

## 8. 19 Oct (the plain fact)

The lake ends 14 Oct and the Dhan token is expired, so 19 Oct's option minutes cannot be rebuilt. The study rebuilds 19
Oct's INDEX side from Yahoo 1-minute bars (8-19 Oct, with the lake's index minutes before them for warm-up): whether C1
fires at 09:30, each breaker's break size, and NIFTY's shock ratio. Each variant's 19 Oct cost is then the sum of the
app's own paper losses (above) of the positions that variant keeps. RB uses each position's own loss as its premium risk
(the loss at a -15% stop is about premium x lot x 15%, plus slippage and charges), stated as an approximation. Yahoo's
index prints may differ slightly from NSE's; disclosed.

## 9. Decision

- A passing variant is a **paper-only** recommendation for Boss (it can only lower risk: fewer positions per signal).
  Paper changes are logged in the Autopilot timeline, undoable in one tap, never carried to live. If several pass, the
  one with the higher design net (ties: smaller design max DD) is recommended. An APP_SPEC (paper only, not built) is
  written only if a variant passes.
- Nothing passes: say so plainly; Breadth Donchian's plain rules stay, the app's same-direction cap (2) stays as built,
  and R84 says what the numbers do show.

## 10. Logging and tests

One `research/experiments/entries/r84-*.yaml` per variant (failures too), one FINDINGS line.
`research/hunt/r84/test_r84.py`: the walker with the full set reproduces per-index trade-taking; each selection rule on
hand-made signals; break size and shock ratio on hand-made bars with a look-ahead guard (changing bars after the signal
bar never changes the decision); the twin keeps the same count; the pass rule and BH maths; K0 reproducing R75 when the
stores are present.
