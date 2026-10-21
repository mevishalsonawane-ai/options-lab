# RESULT: commodities' own paper day lock, plus the 21 Oct diagnostics fixes

Branch `claude/mcx-own-daylock`. Boss's request, 21 Oct: "Yes, give commodities their own day lock". PAPER only; LIVE unchanged.

## 1. Commodities' own PAPER day lock and daily loss limit

- **Pure rules:** `ira-core/.../SegmentLock.kt`.
- **Split:** the paper day after charges is split by exchange.
  - MCX = the MCX position rows' day P&L less the MCX trades' charges.
  - NSE = everything else.
- **NSE side:** the NSE paper lock and loss limit read the NSE figure only.
- **MCX side:** the MCX paper arms have their own lock (`breaker.daylock.mcx`) and their own loss stop (`breaker.mcx.day`).
- **Effect:** MCX results never stop NSE arms, and NSE results never stop MCX arms.
- **What still stops both:**
  - Boss's stop for today and the quick-settings tile. Boss's stop is now also kept apart (`breaker.bossstop`), so it still stops MCX when an NSE loss stop was made first.
  - The kill switch.
  - Zerodha's loss limit. A later Zerodha loss after the NSE paper stop upgrades the stop to "live" and stops MCX too.
  - Every live gate.
- **Account stop rule:** it guards live entries at the entry door. It never applied to paper, and it still covers both segments' live entries.
- **No segment can lift another's stop:**
  - An MCX loss stop holds for the day.
  - "Start all" does not lift it.
  - Switching the split off does not lift it, nor a commodities day lock reached today.
  - A stop made on the shared figure (`Trip.SHARED`) still stops MCX after the split is switched back on.
  - A loss stop whose account is unknown stops both.
  - The stop's kind and day are written before the bots are stopped, so the MCX arms never mistake an NSE paper stop for their own.
  - After an NSE paper stop, Boss's own stop then "start" resumes the MCX arms only.
- **While anything can trade live** (the app in Live, or a live strategy running), the loss limits keep the one shared paper figure, exactly as before: an MCX loss then stops everything and sells what the bots hold (`SegmentLock.lossSplit`). The day locks only stop paper entries, so they stay split.
- **LIVE is never weaker.** Zerodha's day lock and loss limit stay one account. The live entry door (`Broker.entryRecheck`, `ArmLive.gate`) and automatic live strategy starts refuse on any of:
  - the NSE side's stop;
  - the MCX paper stop;
  - the whole paper day past the paper limit (`breaker.paper.whole`, exactly the old single-figure rule).
- **Settings** (Bot settings → Account guard):
  - "Commodities keep their own paper limits" (on by default).
  - "Commodities day lock (paper)" and "Commodities daily loss limit (paper)". When unset, they follow the current paper amounts; 0 means off.
  - Loosening any of them asks for the PIN.
- **Autopilot timeline:** the split is logged once as Boss's choice (21 Oct, `AutoChange.Kind.SEGMENT_SPLIT`). One tap undoes it, back to the shared lock (paper, no PIN). It never carries over to live.
- **Status lines** on Home, Bot settings, the MCX card and the diagnostics (`DayLockGuard.statusLine/mcxLine/diagLine`):
  - "Day lock reached (paper NSE) +Rs 8,000 at 11:00: no new NSE entries today"
  - "Commodities day lock reached +Rs 8,000 at 18:05: no new MCX entries today"
  - The commodities loss stop's own line.
- **Order path:** the entry door reads memory only. The new keys are in `SecurePrefs.GUARD`, so the kept copies refresh. `OrderPathRound2Test` passes.

## 2. US-night silver could never trade

- **Cause:** Yahoo's 5-minute USD/INR (INR=X) is almost empty from about 15:30 to 08:30 IST, so there was no bar within 2 hours of the 23:30 close.
- **Fix:**
  - USD/INR is now also fetched as 1-hour bars, the research's own data (`research/hunt/r2/fetch_yahoo.sh`, PREREG "inr_on (Yahoo 1h)").
  - The 5-minute price is used first, then the hourly one. Both are point-in-time, before the 09:05 entry.
- **USD/INR still missing:** it counts as no rupee move, as the research's `cand.py` does. The arm's log and status say so plainly: "USD/INR not read (...): counted as no move, as the research did".
- **Silver price missing:** the day is still skipped, with a plain reason.
- **Tests:** engine `McxUsSilverRulesTest` (hourly fallback, research signal) and app `McxPaperArmsTest`:
  - trades on the hourly close;
  - trades with no USD/INR and says so;
  - skips when silver is missing.

## 3. From Boss's 21 Oct 11:09 diagnostics

- **(a) Candle agent "stale" with the stream LIVE.**
  - **Cause:** the 1-minute series was judged stale after about 5 minutes. But the batch runs every 5 minutes on MCX data that is cached for 10, and the newest minute is never trusted.
  - **Fix:** `CandleAgent.stale()` now waits at least 15 minutes. The 5- and 15-minute limits are unchanged, and candle closing (`closedByData`) is untouched, so no partial candles come back.
  - **Not done:** the agent's index data still refreshes only when the Ira page or Jarvis refreshes it. That is a possible follow-up: call `IraHub.refreshIfDue` in the batch.
- **(b) Words worker 6 min 44 s.**
  - **Slow step:** `IraHub.refresh()` → `autoLab()`. It backtests two years for each new pattern, and the cancellation was swallowed by `runCatching`.
  - **Fix:**
    - From the words round, refresh no longer runs the lab inline (`refresh(labInline = false)`). The lab runs on its own single background job with a 3-minute budget and stops between backtests.
    - Refresh now passes a cancel through.
    - The pattern check and the candle expert are each bounded at 90 s in `Jobs.wordsSteps`.
  - The position watch never waited on this worker; it has its own executors.
- **(c) Yesterday's notes on the arms' rows.** Each arm's status map now records the day each line is written (`StatusMap` in `StatusDays.kt`, pure `ArmStatus`); the arms' "write only on a change" guards re-write a line on its first use of a new day. A line from an earlier day shows as "from 20 Oct: 13:45 put break missed ...". This covers PassedArmsHost, McxPaperArms, HeroTicketArm, VixDivArm, NightArm and SignalPaperArm. The MCX "parked" line and the R73 count, which carries its own day, are shown as they are.
- **(d) Plain day-lock lines per segment,** with the amount and the time. See section 1.

## Review

A separate reviewer agent read the full diff. Fixed from its findings:

- live strategy runs not stopped by an MCX or whole-day paper loss (now the shared figure while anything trades live);
- the kind/day write order;
- the shared stop being lifted by the switch;
- a reached MCX lock being lifted by the switch;
- Boss's stop not liftable for MCX after an NSE stop;
- status days going by text instead of the write;
- a timeout showing a data error;
- a main-thread vault read in Bot settings;
- the kept copy now warmed after each write.

Left as is (minor):

- The MCX amounts stop following the paper amounts once a full settings save writes them.
- The 90 s bound cannot cut a blocking step, but the slow lab no longer runs inside it.

Local flakes outside this change, both passing alone or in CI's shards:

- `ControlScreensLayoutTest.crashReport[...]`: a screenshot capture error, a different variant each run.
- `LiquidityPaperTestAppTest.otherTradersYield...`: fails only in a single-JVM full run.

## Tests and checks run

- **Local** (an Android SDK was installed in the session):
  - `:engine:test`, `:ira-core:cleanTest :ira-core:test`, `:engine:jacocoTestCoverageVerification`: green.
  - App unit tests for everything touched: 327 tests green. A full local app run (2,786 tests) had only the two flakes listed above.
- **New tests:**
  - `SegmentLockTest`;
  - `SegmentSplitChangeTest`;
  - `ArmStatusTest`;
  - `CandleStaleTest`;
  - app `SegmentLimitsTest`, 13 cases: NSE lock with MCX trading and the reverse, a loss in one with the other trading, Boss's stop on both, Boss's stop after an NSE loss, Zerodha's limit on both, a restart, a day change, an NSE holiday with MCX open, an expiry day, the shared rule, and Live untouched;
  - `CommoditiesLimitsTest` (Compose);
  - `IraHubTest.theWordsRoundsRefreshLeavesThePatternLabToItsOwnJob`.
- **What's New:** the new top entry `2026-10-21-commodities-own-lock`. The hard-coded indices in `WhatsNewChangelogTest` were shifted by +1. `BatteryRestTest` holds no What's New indices any more.
