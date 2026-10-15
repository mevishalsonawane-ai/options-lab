# R19 pre-registration: a profit lock for Liquidity 15+5

Written 13 Oct 2026, BEFORE any R19 result was computed. This file is committed on its own before the first run, and the
write-up (`research/HUNT_R19_LIQUIDITY_LOCK.md`) quotes that commit's hash. Nothing below may change after that commit.
A change needs a new entry, a new pre-registration and a fresh holdout.

## Why

Boss, 13 Oct: the Liquidity 5m MIDCPNIFTY PE bought at 13:20 for 195.45 rose about +21 a unit (about +Rs 2.5k), then
fell back and left at 13:29 at 188.25 on the index stop (-Rs 864). Liquidity has no profit lock:
`ProfitLock.targetOf` returns null for it, because it has no fixed premium target and sells at the next liquidity level.
ORB's ladder cut ORB's losses a lot (research/PROFIT_LOCK.md).

## What was already seen (disclosed before the run)

- `research/LIQUIDITY_LOCK.md` (a premium-target ladder, 5 reference sizes) and `research/LIQUIDITY_TRAIL.md` (trailing
  stops, 10 versions) ran on the SAME two BANKNIFTY years used here, in-sample, with older arm rules (ATM strike, no room
  filter, and in LIQUIDITY_LOCK no index or time stop). Every ladder and every early trail was worse than no lock; a late
  trail (from +25%) was mixed. The author of this file has read those results. So the idea family is not new and the
  data is not virgin for it; the holdout below has never been read AS A HOLDOUT, but it was part of those in-sample runs.
- Those 15 earlier versions are counted in the family total in `r19.yaml` notes; the BH step below covers R19's own
  tests only (as R15 and R17 did).

## Data (no Dhan API call)

- The `research-data` release: `banknifty_prev_year_wide.parquet` + `banknifty_year_wide.parquet`, BANKNIFTY index and
  option minute bars (OHLC, volume), 13 Feb 2024 - 23 Feb 2026, 498 sessions. The option files hold the nearest expiry
  AFTER each day at strikes within 1,000 points of the 09:20 index, which is the contract the app's Liquidity buys
  (`OrbRules.expiryAfter`: always the next expiry, expiry days included). Read through `research/brain/adapters/release.py`.
- **Books tested: BANKNIFTY 15m and BANKNIFTY 5m.**
- **MIDCPNIFTY 15m / 5m: NOT testable.** The release has no MIDCPNIFTY index or option data, the 6-year Dhan lake that
  h4 used is not published (it is not in this container) and the Dhan API may not be called. These books are reported as
  "not tested". A variant that passes on BANKNIFTY does NOT carry over to MIDCPNIFTY: MIDCPNIFTY stays on the plain rules
  until the same test runs on its own data. FINNIFTY is parked and out.

## The arm (control, variant d): engine `LiquidityRules`, 13 Oct 2026, unchanged

- Levels: swing pivots (20 bars each side, full candle range) and pools (2 contacts 5 bars apart, 10 confirmation bars,
  max age 300), on 15-minute and 5-minute bars folded from 09:15, continuous over the last 10 calendar days of minutes
  (the port in `research/hunt/h4/comps.py`, signal-for-signal equal to the validated port on BANKNIFTY).
- Entry: a completed bar closes through a pool that overlaps a still-active swing zone of the same side; bar end 09:20 -
  14:00; room filter (next level ahead at least 1 index stop = 30 points from the bar's close; no level ahead = room);
  buy 1 strike in the money (CE below ATM for a break up, PE above ATM for a break down) at the next minute's open; one
  position per book; expiry days traded (next expiry), as the app.
- Exits, the first of:
  1. the resting premium stop at floor_tick(fill x 0.85) (fills at the trigger, or the bar's open if it gapped through);
  2. the index stop: a 1-minute bar trades 30 points back beyond the broken level;
  3. the time stop: 20 minutes after the entry, out unless the option's last price is at least +5% over the fill;
  4. the target: the index touches the next liquidity level beyond the entry;
  5. failed break: a completed chart bar closes back through the broken level;
  6. new liquidity: a new level of the trade's side becomes known;
  7. 15:10 square-off.
  Exits 2-7 are decided on a minute's close and filled at the next minute's open (the obuy engine, `run_exits`).
- Costs: the app's paper fills plus a liquidity half-spread (`research/obuy/costs.py Fills("liq")`: +-5 bps market,
  -10 bps stops, plus 1 / 2 / 4 ticks from the contract's last-5-minute volume, x2 on stops) and the app's charges at
  today's rates (`Costs("app")`). Size: 1 lot of the NSE lot in force that day (BANKNIFTY 15 / 30 / 35).

## The lock (variants a-c): it only ADDS an earlier exit

Each lock is a resting stop that only moves UP: the resting trigger is max(premium stop, lock level). The lock level is
computed from what was known BEFORE the bar (the best option high and the best index excursion of the earlier minutes
since the entry; a rung counts from the next minute, as `ProfitLock.raise`). A lock level counts only when it sits below
the best option price seen (else it would sell at once) and is never below the breakeven after charges
(`ProfitLock.roundTripPerUnit`). A lock fill is a resting-stop fill: at min(lock, the bar's open), with stop slippage.
Nothing else in the arm changes: same trades, same other exits; a lock never widens or removes a stop.

| id | variant | rule (fixed in advance) |
|---|---|---|
| d | control | today's rules, no lock |
| a1 | index ladder, keep 25% | progress = best index move our way since the entry / (target - the deciding bar's close). At 50% of the way: stop to breakeven after charges. At 75%: lock 25% of the premium gained so far (fill + 0.25 x (best option price - fill)), never below breakeven. No target (no level ahead): no ladder. |
| a2 | index ladder, keep 50% | as a1, but at 75% lock 50% of the premium gained so far |
| b1 | breakeven at +10% | once the option's best is 10% over the fill: stop to breakeven after charges |
| b2 | breakeven at +20% | the same at 20% |
| c1 | give-back 50% after +10% | once the best gain is 10% of the fill: exit when the option gives back 50% of its best gain (lock = fill + 0.5 x (best - fill)) |
| c2 | give-back 50% after +20% | the same after a 20% gain |

6 lock variants x 2 books = **12 tests**. Nothing else is tried; a variant not listed here is not run.

## Splits

- **Holdout (locked, read ONCE): BANKNIFTY sessions 1 Aug 2024 - 14 Feb 2025 (136 sessions).**
  Why these dates: every BANKNIFTY session from 23 Feb 2025 on has already been read as a holdout (i6 event shield:
  23 Feb 2025 - 23 Feb 2026; R16 / R17: 25 Aug 2025 - 23 Feb 2026; R15: 1 Oct 2025 - 23 Feb 2026; h4 and the locked
  year: 1 Oct 2025 - 6 Oct 2026). The only release sessions never read as a holdout are 13 Feb 2024 - 21 Feb 2025, and
  the holdout is the second half of that span. It is not the latest data; that is the cost of not re-reading a holdout.
- **Design: the other 362 sessions** (113 sessions 13 Feb - 31 Jul 2024 and 249 sessions 17 Feb 2025 - 23 Feb 2026).
- Signals and levels use the whole continuous minute series (a level is known only from the bars before it), so the
  holdout's first trades use levels formed in July 2024. No parameter is fitted, so no embargo is needed.
- The holdout flag file (`<out>/HOLDOUT_READ.flag`) is written before the holdout is computed; a second run refuses.

## Measures (every number with its n)

Per variant x book and period: trades; net P&L (Rs, 1 lot); the change against control (delta); net per session;
worst day; maximum drawdown of the daily equity curve; % of MFE captured (sum of (exit - fill) over the sum of the
control trade's MFE, the best option high between the fill and the control exit, trades with MFE > 0); winners cut
short (trades whose control net > 0 and whose variant net is lower); lock exits; and the delta at 1.5x the spread.

## Gates (a variant x book PASSES only if ALL hold)

- **G1 design:** design delta > 0 and the delta is positive in at least half of the 8 design folds
  (calendar quarters: 2024 Q1 (from 13 Feb), Q2, Jul 2024; 2025 Q1 (from 17 Feb), Q2, Q3, Q4; 2026 Q1 (to 23 Feb)).
- **G2 walk-forward (per book):** anchored, fold by fold: from fold 2 on, the variant (control included) with the best
  design delta over the earlier folds is used on the next fold (ties: control). The sum of these out-of-fold deltas must
  be > 0, else no variant on that book passes.
- **G3 holdout:** holdout delta > 0, and the paired one-sided t-test on daily deltas (variant - control, every holdout
  session, 0 on days without a trade) has a Benjamini-Hochberg q <= 0.10 across the 12 tests.
- **G4 random twin (holdout):** the lock's delta beats 1,000 random twins at p < 0.05. A twin takes the same number of
  trades as the lock cut (k), picks k of the book's holdout trades at random, and exits each early at one of the lock's
  own exit delays (minutes after the entry, shuffled) when the trade is still open then, at that minute's next open
  (market fill). It asks whether the lock picks WHEN to leave better than leaving at the same delays at random.
- **G5 risk (holdout):** worst day and maximum drawdown no worse than control's.
- **G6 costs (holdout):** delta > 0 at 1.5x the modelled spread.

## What happens next

- PASS (all gates): per CLAUDE.md the variant may act on PAPER at once, for the BANKNIFTY book(s) where it passed only,
  logged in the Autopilot change timeline, undoable in one tap, each trade labelled "plain rules" / "with lock". It is a
  resting-stop raise like `ProfitLock.raise`. Live stays unchanged (Boss's PIN decision). MIDCPNIFTY unchanged.
- No pass: the app changes nothing; the write-up gives the honest result and Jarvis gets a one-line answer to
  "why no profit lock on Liquidity?" through the knowledge pack.
- Today's MIDCPNIFTY trade is shown under each variant as an illustration only (one trade is not evidence).
