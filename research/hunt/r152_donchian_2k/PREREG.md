# R152 pre-registration: sell the Donchian arms at +Rs 2,000 or -Rs 2,000 for the whole position

Written 7 Nov 2026, **before any R152 number was computed** (committed on `claude/r152-donchian-2k` before the code's first
run). Research only; nothing in `android/**` changes.

Boss, 7 Nov: *"Make 1 more change to donchian and provide me results, sell at max target i.e. 2k and max loss at 2k"*.

Signal source for every rule: **the index** (CLAUDE.md default). The arms' entry signals are unchanged; only the exit of
the bought option changes (a rupee target and / or a rupee stop on the option premium, which CLAUDE.md allows).

## 0. What is already known

R106 (26 Oct) ran +Rs 500 / 1,000 / 1,500 targets, with and without a 1:1 rupee stop, at 1 lot, on the same arms. Every
rule failed: small targets cut the few big winners the Donchian arms live on (R72 final: B0 Rs 1,71,366; T1500 lower).
R105C found the same for percentage targets. R152 asks the same question at Boss's new amount, Rs 2,000, and, new, at
2 and 3 lots, where Rs 2,000 is a smaller move per lot.

## 1. The arms (B0 = the rule on paper today, reproduced first)

Engine, stores, fills, charges and taking rules exactly as R106 (`research/hunt/r106_small_targets/`): R51's Amendment A
clean stores (`R51_CLEAN=1`), R64's held-contract simulator with R106's rupee levels, R17 liquidity fills, dated Zerodha
charges, next-minute fills, stops on the minute low; one open position a side, at most 2 fresh fills a side a day.

| arm | units | today's exits (XL) | B0 must reproduce at 1 lot (design / final) |
|---|---|---|---|
| R72 Breadth Donchian (4 of 5) | R101's units (as R106) | -15% stop, 30-60-100 ladder + L25, 30-min time stop, 15:15 | 3,071 / Rs 2,16,929 ; 1,202 / Rs 1,71,366 |
| R51 Donchian 20 | R101's Donchian-20 signals kept by Supertrend (10, 3) (R112 / R115: the app's R51 today) | XL as above | 4,208 / Rs 50,425 ; 1,670 / Rs 1,34,743 |

**Deviation from R106, stated now:** R106's R51 B0 (4,498 / Rs 9,998) is R51 *before* the Supertrend filter. The app's
R51 has run with it since R112 / R115, and R151 (whose pickers are live) used it too, so "today's rules" for R51 is the
Supertrend version. R106's plain R51 B0 is still reproduced first, as an engine check (asserted), then not used.

## 2. The rules (fixed now)

Every amount is **for the whole position, net of the trade's own dated charges and slippage** (the engine's real fills).

- **Target +Rs 2,000**: a resting limit sell at the premium X where `(X - real entry) x qty - charges(entry, X) = 2,000`,
  solved per trade from its real entry fill (R106's `target_price`; a limit fills at its price).
- **Stop -Rs 2,000**: a resting stop at the premium S whose stop sale (R17 stop fill: -10 bps and twice the half-spread)
  nets exactly -2,000 after charges (R106's `stop_price`). A gap through S fills at the minute's open, so a loss can
  exceed Rs 2,000 (reported). **The arm's own -15% stop stays (Boss: "its own stops ... stay, whichever comes first"):
  the nearer of the two acts.** This equals R106's "1:1" whenever the rupee stop is the nearer one (nearly always at 2-3
  lots); at 1 lot, where -15% is often nearer, R106's 1:1 would have *widened* the stop, which Boss's words rule out.
  If the whole position is worth less than Rs 2,000, only the -15% stop acts. The share of trades where each stop was the
  nearer is reported.
- The arm's other exits (ladder locks, 30-min time stop, 15:15 square-off) stay; whichever comes first exits.

| id | target | stop |
|---|---|---|
| T2000 | +Rs 2,000 | the arm's own (-15%) |
| T2000S | +Rs 2,000 | -Rs 2,000 or -15%, the nearer |
| S2000 | none | -Rs 2,000 or -15%, the nearer |

**Lots 1, 2 and 3.** qty = lots x the index's lot size on that date. The Rs 2,000 levels stay Rs 2,000 for the position
(Rs 1,000 a lot at 2 lots, Rs 667 at 3). Charges are the real dated charges of the whole quantity (brokerage per order,
the rest per rupee); the slippage ticks come from the 5-minute volume per position (R17), so bigger orders pay more where
the book is thin. **B0 is re-priced at the same lots** (its exits are all percentages, so only quantity, charges and
slippage change) and every rule is judged against B0 at its own lots.

3 rules x 2 arms x 3 lot sizes = **18 tests**. No threshold is fitted to data: Boss named Rs 2,000; lots 1-3 follow Boss's
lots setting (CLAUDE.md, 4 Nov: "if I select 3 lots ...").

## 3. Periods

As R106: design 20 Nov 2024 - 31 Mar 2026 with the walk-forward window Apr 2025 - Mar 2026 (nothing is fitted, so it is
reported as its own period); final 1 Apr - 13 Oct 2026, read ONCE by R152 (`results/FINAL_READ`; already read by R51-R151:
weak evidence); post 14 - 19 Oct 2026 on stores built to 19 Oct in their own folder (`results/POST_READ`; tiny, never
gated).

## 4. What is reported (every rule, B0, lots, period)

Trades, win rate, break-even win rate (average net loss / (average net win + average net loss)), net, per session, worst
day, max drawdown (daily), net without the 5 best trades, the exit mix (target / stop / other; the stop split into the
rupee stop and the -15% stop), the largest single loss, exit-twin p and BH q, and the verdict.

**Second look (descriptive, never gated): R151's pickers.** The pickers now act in the app (Boss's 6 Nov exception). On
Apr - Oct 2026 only, each arm's candidates are scored with the app's exported model numbers
(`research/hunt/r151_donchian_winrate60/results/r72_picker_model.json` / `r51_picker_model.json`; thresholds 0.4656 /
0.4469). As the app does, a skipped break still holds its side and counts in the day's caps (it is walked as a trade and
then removed from the record); a break that cannot be scored is taken. Each rule and B0 is shown at 1-3 lots on those
picks. Parity first: R151's own walk (skipped breaks removed before the walk) must reproduce R151's published picks
(R72 652 trades / Rs 2,19,057; R51 693 / Rs 1,59,040); if it does not, the second look is reported with the difference.
Labelled "second look": Apr - Oct 2026 was read many times, and the pickers themselves failed their gates.

## 5. Gates (all must hold; R106's, never relaxed)

1. Net > 0 in design, in the walk-forward window and in the final.
2. Better than B0 at the same lots: net above B0 in the walk-forward window AND the final; OR net within 5% of B0's in
   both AND worst day and max drawdown each at least 20% smaller than B0's in both.
3. Beats its exit twin: random-exit twins, final period, 500 sets (seed 152), as R106: for every trade the rule's rupee
   target or rupee stop closed earlier than the arm's own exits would have, the twin sells it instead at the open of a
   uniformly random minute strictly after its fill and before that own-exit row; p = (1 + #twins with total net >= the
   rule's) / 501.
4. Benjamini-Hochberg q <= 0.10 on those p, counted against R106's 549 (its 36 + its 513 earlier) and the 299 later
   entries in `research/experiments/entries/` dated 26 Oct - 6 Nov 2026 in the arm exit / filter families
   (trade-management 156, daily-filters 54, sizing-execution 24, flow-oi 12) plus R115's 10 pattern filters and R150's
   23 / R151's 20 Donchian filters: **848 earlier tests, taken as p = 1**, plus R152's 18 = m 866. q within R152 alone is
   also shown.
5. At least 40 final trades.

## 6. What a pass means (CLAUDE.md)

A passing rule may act on PAPER for that arm straight away: a labelled switch, logged in the Autopilot change timeline,
undoable in one tap, shown to Boss. Live: a target or stop that only exits earlier lowers risk, but anything new on live
trades starts in shadow behind a switch that is off by default. Nothing goes live from R152. A failed rule stays a note
here and in `research/experiments/entries/r152-*.yaml`.
