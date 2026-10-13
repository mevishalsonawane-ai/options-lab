# R21: catching expiry-day zero-to-hero moves (the Hero arm)

Written 13 Oct 2026, after Boss's note on today's NIFTY expiry: "Deep analyse it, and find a way to catch it next time."
Pre-registered in `research/hunt/r21/PREREG.md` (commit `78e9761`, before the holdout was read). Code and tables:
`research/hunt/r21/` (`anatomy.py` step 1, `run.py` the test, `results/anatomy.md`, `results/report.md`,
`results/results.json`). Experiment entries: `research/experiments/entries/r21-*.yaml` (9 variants plus the anatomy).

## Verdict

**Nothing passes. 0 of 9 pre-registered variants clear the gates, so no app change is made.** The Hero arm keeps its
rules. The best design result, V4 (a one-sided trigger, window to 15:00, band Rs 1-15), made Rs +44,527 on 10 design
trades. On the holdout it made **0 wins in 15 trades, Rs −46,268**. Every one-sided variant had 0 holdout wins
(11-15 trades each). This is the familiar pattern for lottery tickets: a handful of design jackpots, then nothing.

To decide this properly needs the **6-year Dhan lake** (about 300 NIFTY weeklies, against 53 here). The re-run is one
switch: `R21_SOURCE=lake python3 research/hunt/r21/run.py all`, once `research/data/CATALOG.json` and the lake files
exist. On the lake, the holdout becomes the last 12 months.

## Why Hero took no trade on 13 Oct, and why that is the rare case

- **13 Oct (from Boss's message; no minute data of that day is in any file here).** The straddle peaked at +13.7% at
  14:36. The 0.25% momentum came at 14:40 and 14:43-14:44. The two never met in the same minute.
  - In a one-way rally the call's gain is offset by the put's collapse (22600 PE 76 → 5), so the ATM straddle hardly
    grows.
  - That diagnosis is right for that day.
- **In the data it is the exception, not the rule.** On the 33 design expiries:
  - the straddle condition (≥ 15% over its low since 12:00) was met at some minute on **32 of 33**;
  - **momentum** is what binds: a 0.25% 15-minute move happened on only **9 of 33** days;
  - on all 9 of those days, momentum and the straddle condition held in the same minute.
- **One-way afternoons** (the index moved ≥ 0.4% from 13:30 to 14:45) were 4 of 33 design days. The straddle still
  expanded 18-36% on them.

## Step 1: anatomy (design days only: 27 NIFTY weeklies + 6 BANKNIFTY monthlies, Apr-Oct 2025)

Hindsight figures: the best of about 80 strikes over 90 entry minutes.

| some Rs 1-10 option at 13:00-14:30 reached | before 15:05 | before the close |
|---|---|---|
| 5× | 24 / 33 | 26 / 33 |
| 10× | 11 / 33 | 14 / 33 |
| 20× | 7 / 33 | 10 / 33 |

- **Big moves are common in hindsight.** On a typical expiry, some cheap strike runs 5× (24 of 33 days). The hard part
  is picking it in advance, not the move being rare.
- **When it starts.** The best option's run started anywhere from 13:02 to 14:30 (median 14:04). Its peak came
  8-110 minutes later (median 36.5).
- **How long the spike lasts.** It traded at or above half its peak for a median of 28 minutes. On 2 of the 24 days
  it never closed a minute there.
- **Give-back by 15:05.** The median give-back from the peak was 68%. On 11 of the 24 big days the option was back
  under 2× of its start by 15:05; on 9 it was worth less than its start.
- **Direction.** The winning side went with the index's trend since 12:00 on 12 of 24 days and against it on 12. A
  coin flip.
- **What separates big days from the rest.** Nothing measured at 13:00 did (medians: big days n = 24, other days
  n = 9):

| at 13:00 | big | other |
|---|---|---|
| index move since the open (abs) | 0.30% | 0.34% |
| index move since 12:00 (abs) | 0.08% | 0.12% |
| gap (abs) | 0.14% | 0.18% |
| distance to max pain | 0.08% | 0.05% |
| call OI wall above / put wall below | 0.26% / 0.18% | 0.18% / 0.24% |
| VIX | 13.3 | 12.3 |
| ATM call change since 12:00 | −8% | +11% |
| ATM put change since 12:00 | −33% | −34% |
| cheap-strike volume, last 15 min vs earlier | 0.94× | 0.84× |

At the move's start (big days only), cheap-strike volume ran 1.34× its earlier pace, and the ATM call and put had each
lost about 34-39% since 12:00. Those are traits of the move itself, not warnings before it.

## Step 2: the 9 pre-registered variants

k for the one-sided trigger was chosen on design days only, by a rule stated before the scan (best design net; ties →
the larger k). Both bands gave k = 2.5.

| id | idea | trigger | window | band Rs | exits |
|---|---|---|---|---|---|
| V0 | baseline: the app's Hero | straddle ≥ 15% this minute + momentum | 13:30-14:45 | 1-5 | app (half at 5×, rest at 20× / 15:05, −60% close stop) |
| V1 | (a) one-sided | the option's own close ≥ 2.5 × its low since 12:00 + momentum agreeing | 13:30-14:45 | 1-5 | app |
| V2 | (b) recent expansion | straddle ≥ 10% in any of the last 15 minutes + momentum | 13:30-14:45 | 1-5 | app |
| V3 | (a)+(c) | as V1 | 13:30-15:00 | 1-5 | app |
| V4 | (a)+(c)+(d) | as V1 | 13:30-15:00 | 1-15 | app |
| V5 | V4 + lock | as V1 | 13:30-15:00 | 1-15 | app + once 4×, a close ≤ 2× sells |
| V6 | V4 + give-back trail | as V1 | 13:30-15:00 | 1-15 | app + once a close ≥ 3×, a close ≤ 50% of the best sells |
| V7 | V4, plain exit | as V1 | 13:30-15:00 | 1-15 | 15:05 only |
| V8 | (f) blind control | buy at 13:30 on the side of the move since 12:00 | 13:30 | 1-5 | app |

## Step 3: results

How the test was run:

- Rs 5,000 ticket; the NSE lot that day; the app's charges.
- Fills at the ask:
  - the next bar's open + 5 bps + a half-spread of 1-4 ticks, by the strike's 5-minute volume;
  - the entry limit is capped at 1.2 × the signal price + 0.10 and worked for 3 minutes;
  - sells at the open minus the same half-spread;
  - the stress test doubles the spread.
- Design = 33 expiries. Holdout = 31 expiries (NIFTY from 13 Oct 2025: 26; BANKNIFTY from 25 Aug 2025: 5), read once.
- "Big days" are hindsight (as in step 1): 24 in the design period, 23 in the holdout. A big day is "caught" when the
  variant traded that day and its option reached 5× its fill before 15:05.

| id | design trades / wins | design net Rs | holdout trades / wins | holdout net Rs | per holdout expiry | worst expiry | holdout max DD | big days caught (holdout) | 2× spread Rs | twin p / BH q |
|---|---|---|---|---|---|---|---|---|---|---|
| V0 | 9 / 1 | −17,054 | 2 / 0 | −6,259 | −202 | −3,203 | −6,259 | 0 / 23 | −6,507 | 0.70 / 1.00 |
| V1 | 7 / 3 | +10,338 | 11 / 0 | −35,188 | −1,135 | −3,387 | −35,188 | 0 / 23 | −35,750 | 0.91 / 1.00 |
| V2 | 9 / 3 | +3,870 | 13 / 1 | −18,837 | −608 | −3,899 | −22,721 | 1 / 23 | −19,624 | 0.92 / 1.00 |
| V3 | 8 / 3 | +7,398 | 11 / 0 | −35,188 | −1,135 | −3,387 | −35,188 | 0 / 23 | −35,750 | 0.85 / 1.00 |
| V4 | 10 / 5 | +44,527 | 15 / 0 | −46,268 | −1,493 | −3,267 | −46,268 | 0 / 23 | −47,099 | 1.00 / 1.00 |
| V5 | 10 / 5 | +45,227 | 15 / 0 | −46,268 | −1,493 | −3,267 | −46,268 | 0 / 23 | −47,099 | 1.00 / 1.00 |
| V6 | 10 / 5 | +46,170 | 15 / 0 | −46,268 | −1,493 | −3,267 | −46,268 | 0 / 23 | −47,099 | 1.00 / 1.00 |
| V7 | 10 / 3 | +9,342 | 15 / 0 | −61,863 | −1,996 | −4,850 | −61,863 | 0 / 23 | −62,651 | 1.00 / 1.00 |
| V8 | 31 / 4 | −46,285 | 30 / 2 | −66,764 | −2,154 | −4,013 | −66,764 | 1 / 23 | −69,081 | 0.006 / 0.054 |

Gates (all must hold): design net > 0; holdout net > 0 on ≥ 5 trades; random-time twin BH q < 0.05; holdout net > 0 at
2× spread; holdout net > V0's. Every variant fails G2. No variant has a q below 0.054.

- **Random-time twins.** Each trade was re-entered at a random minute of the same window, same day, same side, same
  strike rule. The twins did better than the triggers for V0-V7: twin means of Rs +1.0k to +26.2k, against triggered nets
  of Rs −6k to −62k. So the triggers add nothing over random timing.
- **V8 (blind at 13:30) loses heavily**, as expected: −Rs 66.8k on 30 trades. Its twin p of 0.006 only says that
  13:30 was less bad than a random minute up to 13:45 (twins −Rs 85.7k). It is not an edge.
- **The exits don't matter here.** V5 and V6 equal V4 in the holdout because no holdout trade ever reached the 3-4×
  that arms them. The plain 15:05 exit (V7) loses more than the app's −60% stop (−Rs 61.9k vs −Rs 46.3k). In the
  design period, the lock and trail added only Rs 0.7k-1.6k over V4.
- **Walk-forward, not a gate.** V4 with k re-chosen each month from earlier expiries made 22 trades, 3 wins,
  **Rs −24,812** (Jun 2025 - Apr 2026). All 3 wins came by Sep 2025; every month from Oct 2025 lost.
- **The stop cuts some tickets before the late run.** For example, V4 on 2 Mar 2026: stopped out, then the option reached
  4.9× its fill. A wider stop is not the fix, though: with no stop (V7), the holdout loss is larger.

### Would it have caught 13 Oct? (forward example only; prices from Boss's message, no minute file)

- 13 Oct is after the release's last day (13 Apr 2026), so it chose nothing.
- The first momentum minute was 14:40, with the index near 22,670-22,680 (the 22650 CE was at 31.2). So every
  momentum-gated variant could only fire from 14:40.
- V0 does not fire, as happened.
- V1 and V3 (the cheapest OTM call ≥ 2.5× its low since 12:00, Rs 1-5) would **probably** have fired at 14:40 on the
  22750 CE (0.75 at 13:30; peak 22.6 at 14:56; 6.25 at 15:05) if it was then Rs 1.9-5. A fill near Rs 4-5 reaches
  about 4.5-5.5× at the peak. Whether the half-at-5× target filled depends on the exact fill, and the rest goes out
  at 15:05 at about 1.3-1.6×.
- V2 (straddle ≥ 10% within 15 minutes; it was +13.7% at 14:36) would probably have fired at 14:40 on the same strike.
- V4-V7 (Rs 1-15) would probably have taken the 22700 CE: low 1.45, 4.15 at 14:30, peak 59.4, 21.8 at 15:05. A
  Rs 10-15 fill gives about 4-6× at the peak and about 1.5-2× at 15:05.
- **So the new triggers would likely have caught a piece of 13 Oct.** But the same triggers took 11-15 holdout trades
  without one win. One forward day cannot outweigh that.
- To check this exactly, rebuild the day from the phone's recording with `data.to_day` and run `rules.run_day` per
  variant.

## Why the idea fails (honest reading)

1. **Momentum is a lagging, noisy filter on expiry afternoons.** A 0.25% 15-minute move appears on about 1 day in 4.
   When it does, the cheap strike in that direction is as likely to die (the move stalls; theta is near-total) as to
   run.
2. **The one-sided trigger buys after the option has already run 2.5×.** In the holdout, those strikes usually mean-reverted
   within minutes into the −60% stop. Most one-sided holdout trades never reached 2× their fill: 9 of 11 (V1) and 11 of 15 (V4).
3. **Hindsight makes it look easy.** "Some strike ran 5×" happened on 74% of days. A real-time rule picks one strike and
   one minute, and the spread (1-4 ticks, 1-10% of the price) and the charges come off every ticket.
4. **Earlier work agrees.**
   - OBUY_FINAL/OBUY_GC: the catalog hero-zero lost Rs 15 lakh at Rs 5k tickets over 5 years.
   - OBUY_VALIDATION: Hero's own profit was one day (12 Sep 2024); random tickets did as well.
   - R17: the Hero holdout had 0 wins in 13 trades.

## Data, and what would decide it

- **Used:** the `research-data` release. That is the expiring contract's minutes on expiry days, NIFTY (53 weeklies,
  9 Apr 2025 - 13 Apr 2026) and BANKNIFTY (11 monthlies, Feb 2025 - Jan 2026), strikes within about ±1,000 points, plus
  index minutes and VIX.
- **Not available:**
  - SENSEX, FINNIFTY and MIDCPNIFTY option chains (the release has only their index).
  - The Dhan lake: no `research/data/CATALOG.json` and no lake release yet.
- **Needed:** the 6-year lake, about 300 NIFTY weeklies plus BANKNIFTY weeklies to Nov 2024. At about one trade per
  3 expiries, that gives about 100 trades: enough to tell a 10-20× tail from luck. Re-run with `R21_SOURCE=lake`. The
  pre-registration and its gates stay as written; the holdout becomes the last 12 months, read once.

## Checks

- `python -m pytest research/hunt/r21/test_r21.py -q`: 20 tests. They cover:
  - the look-ahead guard: every variant at every window minute gives the same decision on a day with the future
    blanked, and with the future set to absurd values;
  - exits, the entry cap, lots, a stale leg, an index gap, a market-closed or empty day, BH, twins, the lot schedule,
    and the one-read holdout flag.
- The guard on the real design days: 7,722 decisions, 0 mismatches.
- 13 Oct is not in the data, and no parameter was chosen from it (test `test_thirteen_october_is_not_in_any_choice`).
