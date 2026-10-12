# Why the agents missed 12 Oct's problems, and what catches them now

Written 12 Oct 2026 for Boss ("find out why all my agents were not able to find these issues"). The problems were fixed in
commits 24ed8c0..5bc2d67. This note covers the step after that: for each problem, which agent should have caught it, why it
did not, and what catches it now.

## The pattern behind most misses

1. **A blind spot by design.** Most agents watch trades and markets. None watched the app's own words: its diary, its money
   labels, or its "should the watch run?" rule.
2. **Measured, but only written to Diagnostics.** The stalls, the app start time, the budget, the paper order speed and
   "ij1" were all recorded. Each was one line in a report Boss never reads.
3. **Surfaced, but buried or soft.** "Tested: ORB lost in both years" was a bullet among twenty in the morning check. The
   order path's paper warnings were INFO-level flaws marked "live".
4. **The detector leaned on the faulty rule.** The stall alarm and the stream health monitor both asked the same
   watch-should-run rule that was wrong at 16:30. The health monitor also runs inside the watch that had just ended.
5. **Needed more days.** The order review only counts a pattern after 3 cases on 2 or more days. Self-review needs 15
   trades and does not review ORB at all. Lessons need 10 trades to be "building".

## Per problem

| # | What happened | Who should have caught it | Why it did not | What catches it now |
|---|---|---|---|---|
| A | Home's "Capital" and the 15:45 report mixed PAPER money with Zerodha's | Day report, Jarvis funds answer, morning check | Blind spot: no check that a money figure names its account | Source-scan test `MoneyLabels` (ira-core `MissedChecksTest`) over Home, the day report, the widget and Jarvis funds. It flags the old widget, report title and Capital lines. |
| B | ORB re-entered the same side ~9 times a day. Each trade made about Rs 0 before charges | Order review, self-review (DriftReview), Jarvis's lessons, trade check | No detector existed for charges versus gross or same-side re-entries. The order review also needs 3 cases on 2 days. DriftReview reviews Liquidity, Hero and Solo, not ORB ("Liquidity: too few trades"). The trade check said "lost in both years" only as a bullet. | Order review `CHARGES_EAT` and `REENTRY` flaws on the day itself, kept in sight on the card and listed under Problems found. Jarvis gets a coach line, words only. When the flaw recurs it becomes a proposal: cap entries a side, or switch it off. |
| C | A SILVERM target set below entry filled at once (-Rs 1,795). An empty "Protected…:" cancelled the old stop. | Order review, Protections validation | The code guard was missing (fixed in b654dfb). The order review never read protections. | Order review `WRONG_SIDE` and `EMPTY_PROTECT` (serious) from each day's protections. |
| D | Jarvis's "Guard my positions" put 15% stops on MCX arm positions (-Rs 3,530) | Order review, trade manager | The guard's list of arms was incomplete (fixed). No agent compared a protection with who owned the position. | Order review `ARM_PROTECT`: a protection on a position a bot opened. |
| E | The watch and stream ended at 16:30 while MCX was open with 3 arms on | Stall alarm (Heartbeat), self-healing stream health | Both asked the same faulty "watch due" rule. The stream monitor runs inside the watch and only expects the stream during NSE hours. | `SessionCoverage` in the stall alarm, outside the watch, built from plain facts: MCX open plus arms armed or positions held, with no watch → serious problem, notification, watch restarted. |
| F | "Missing required field: quantity" x3 | Any agent reading the diary | Blind spot: no agent read the diary's errors | `DiagErrors` counts the day's errors by kind. They appear in the order review as `ERRORS` flaws (serious at 3+). |
| G | Jarvis auto-traded SENSEX (BSE) and FINNIFTY (barred by X1) on paper | Morning check, Autopilot | No policy check: what is armed was never compared with standing decisions | `PolicyCheck` (venue rules plus research "lost in both years"). It shows as a ✗ line in the morning check, as Problems found, and gets a notification if serious. |
| H | 3.9 s main-thread binder stalls; app start 2 s | HealthWatchdog / HealthTuner, "is the app healthy?" | The data was there but only written to Diagnostics. The tuner acts only on 3 tunable cadences. "Healthy" only failed at 3+ stalls a day. | `HealthChecks.stalls` (≥1 s, with the frame) and `appStart` (>1.5 s, with the slowest step) as problems |
| I | The background budget ran out 24 minutes into the night; the history vault was starved | BackgroundBudget / BudgetRule | Only logged ("waits for tomorrow"). The real cause was a wrong clock, now fixed. | `HealthChecks.budget`: more than half spent before 15:30 (day) or 20:00 (night) |
| J | Paper orders took seconds; a profit lock was missed | Order speed, order review | Measured, but paper and live were mixed in one figure. The review recorded the warnings as INFO and called them live. | `HealthChecks.paperOrders` (paper median >1 s) as a problem. Paper speed warnings are now marked paper in the review. |
| K | "ij1" failure could not be read; "Night study not working" was a false alarm | Watch diary, morning self-check | Shrunk class names (fixed with -keepnames). The study check asked for the wrong time window (fixed). | `DiagErrors.unreadable` plus `HealthChecks.unreadable`: any failure still named only by a shrunk class is a problem |

Other findings from the same diagnostics:

- **Trade manager ACT on Solo and Pine paper hurt (-Rs 1,635, -Rs 1,324).** Autopilot's probation and Jarvis's "mgr:"
  lessons need 10 to 30 settled cases. They were not there yet.
- **11 stream drops.** These were the phone's network. Self-healing counted them, and the reconnect wait is shorter now.
- **Relay unreachable.** Self-healing's relay incident covered it. This needs Boss's server.
- **Clock 1.8 s off.** Order speed shows it.
- **Zerodha login not on the static IP.** The morning check's ✗ line covers it.

## Jarvis's "ORB makes money (36 trades, +Rs 3,428)"

There were two reasons this lesson was wrong:

- Paper fills before 08 Oct did not pay the bid/ask spread.
- No lesson was ever compared with the research.

What changed:

- Lessons now take off the spread estimate those trips never paid (`Insights.unbookedSpread`).
- A lesson that a strategy makes money is now capped at "weak" when the research found that strategy lost in both tested
  years. It reads "contradicts research - not trusted" and is never proposed.

## Where Boss sees it

Home → Automation card → **Problems found** shows two things:

- the count by severity, with the most serious problem always in sight;
- the rest under "Show all problems".

The same list appears in Diagnostics. Problems stay open for a day and a half after the last time they are seen.

The new **Paper-only period** (Settings → Bot settings) holds an end date and a reason. Until the end date:

- switching to Live shows the reason and the end date, then asks for the PIN;
- exits and square-offs are never blocked;
- voice, backups and automation can never change the period.
