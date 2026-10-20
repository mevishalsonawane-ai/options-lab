# RESULT: Candle agent (branch `claude/candle-agent`, 20 Oct 2026)

Boss, 20 Oct: an agent that studies only candles, explains each one (why green or red, where its support or resistance came from), uses OI, gamma and the rest, learns, and shares and talks with the other agents. Later the same day Boss added three asks:
- find the history behind each candle: the earlier level it took support from, and the old resistance a rally ran into;
- keep it learning 24x7;
- show the big players' hand in each candle.

All of these are done. The agent is knowledge only: **it never trades, sizes, stops or orders.**

## What it is
- **ira-core (pure, unit-tested)**
  - `CandleRead`: each closed candle's shape, using R87's exact thresholds (43 families, the most specific named first). It also reads:
    - context: trend, day and previous-day high/low, opening range, VWAP (own volume or the future's), round numbers, the previous day's value area, OI walls, PCR, OI shift, gamma, time of day, expiry;
    - the nearest support and resistance, each with its source.
  - **History behind a candle** (Boss's later ask): swing highs and lows and earlier days' highs and lows, up to 3,000 candles back.
    - For each candle it finds the earlier level it took support from, or the old high a rally ran into.
    - It reports when that level was first made, how many times it was tested, and when last.
    - It reports the rally or fall over the last 5 candles.
    - These levels only become known after they formed, so there is no look-ahead (tested).
  - **Big players' hand**:
    - footprints in the candle itself: stop runs, heavy volume with a small range (absorption), heavy volume with a big range (climax);
    - option writers adding OI at a strike;
    - what the liquidity, order-flow, trap and move agents posted during that candle.
    - It always adds R86/R87's verdict that these did not predict the next move by themselves.
  - `CandleBook`:
    - outcomes over the next 1 to 50 candles of the same session (move, best and worst over 10, whether the wick end held); a gap, a holiday or the session's end stops the count;
    - its own statistics per shape × context, including "at a level from history" and "big players"; at most 2,000 cells, saved as text;
    - the research seed (R87, R87B, R85);
    - its own count compared with the research;
    - lessons, notes for Boss, and a growth score.
  - `CandleAgent`, the agent:
    - reads in batches;
    - posts its reads to the findings bus with **no direction**, so they never count in any consensus or entry reaction;
    - hears the other agents (regime, OI, gamma, news, sweeps, stop and target hits);
    - answers other agents through a typed `ask` (latest read, levels, odds);
    - answers Jarvis;
    - reports its status, health and its log of what it shared and heard.
  - Shared framework, extended rather than duplicated:
    - `Findings.Kind.CANDLE`;
    - Jarvis's lessons topic "Candle agent": building at most, never a proposal;
    - a SharedKnowledge "candles" topic;
    - `MemoryPlan.CANDLE_AGENT`, let go second (right after the language model, even at the mildest trim);
    - a `BudgetRule.Job.CANDLE_STUDY`.
- **App**
  - `CandleAgentHost`, a supervised worker in the watch's analysis group:
    - every 5 minutes in session (10 with the battery saver), then hourly around the clock while it is loaded (re-checked every minute, so it never sleeps through the open);
    - it waits for open positions to be restored first and skips while memory is short;
    - its counts are written on IO.
  - Its inputs come from memory: IraHub's 1-minute index history, `MemoryChain`, `GammaLive`, and the future's VWAP. MCX near futures are read via `Net.intraday` at most every 10 minutes while MCX is open.
  - 24x7 study: once a day while NSE and MCX are both shut, from the hourly study worker and within the background battery budget (metered on the thread doing the work).
    - It reads only the 5-minute history the market study already holds in memory (nothing fetched for it), also folded to 15 minutes.
    - Each night it goes up to 60 sessions further back than the oldest candle counted so far.
    - Each candle is counted once only: the counted span (first and last) is kept with the counts, and an older counts file is read safely.
  - Page: Research → **Candles**. It shows:
    - status and growth;
    - the latest candles (tap one for its full explanation);
    - the shapes against the research;
    - its lessons and notes;
    - what it shared and heard.
  - Jarvis answers "why was that candle green", "what does this candle mean" and "where is support for this candle". It sits right before the big-candle record in IraHub; the routing audits (CollisionTest/CoverageTest) were updated. On a locked phone the bots' posts are left out.
  - Jarvis's nightly pass takes its lessons. It is listed on the "What I learned" sheet and has a diagnostics line.
  - What's new entry (8 sentences). The indices in WhatsNewChangelogTest (+1) and BatteryRestTest (22 → 23) were shifted.
- **Research:** `research/hunt/r87_candle_anatomy/app_seed.py` turns the 500 KB candle pack into a 41 KB app asset (`assets/knowledge/candle_seed.json`), with pytest. Signal source: the index or future candle.

## Honest notes
- The research says no candle shape predicts direction by itself (R87: 2 of 17 findings held, both below an option's cost; R85: none for MCX). The agent says that beside every read. When its own count differs from the research, it writes a note for Boss and nothing changes.
- **"Where is support" said plainly still goes to the existing levels answer.** The Candle agent takes it only when a candle is named ("where is support for this candle"), so the existing answer was not taken over.
- No general agent-to-agent ask/answer channel existed. The agents shared only the findings bus and the SharedKnowledge snapshot. The agent uses both, and adds a typed `CandleAgent.ask` that other agents can call. No existing agent calls it yet.
- MCX outcomes: the last few candles of an MCX session are not counted, because the worker sleeps after MCX closes and the next day's fetch holds only that day.

## Also fixed (not the Candle agent's)
- Today (20 Oct, Dussehra) every branch's app tests failed `ControlScreensLayoutTest.framePaper[phone-font2.0]`: the masthead's holiday name ("· Dussehra") was cut off beside "Market closed" at 2x fonts. It is now its own item of the masthead's flowing row, so it wraps to its own line (`ui/Root.kt`).

## Checks
- `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification`: pass locally.
- Locally:
  - ira-core passes in full, including 47 new candle tests: classifier, look-ahead guard, history levels, footprints, outcomes (gaps, holidays, session end), stats bounds and encoding, research seed, lessons, sharing with no direction, hearing, locked phone, stale data, no data, restart without double counting, 24x7 study, and speed.
  - The R87 research tests (58) and the features catalog pass.
  - The `research/brain` suite fails in this sandbox for an environment reason: numpy/pandas versions. It fails the same way on the untouched base, and nothing there changed.
- `:app` compiles only in CI. App tests added:
  - `CandleAgentHostTest`: let go first while a held instrument's candles stay; skipped under memory pressure; 24x7 pace; daily study once, within the battery budget, never in session; bus posts with no direction; MCX fold;
  - `CandleAgentPageTest`: Compose page;
  - `LabScreensTest`: the Candles tab;
  - `MemoryTrimTest`.
- Reviews: self-review of the full diff, plus two separate reviewer agents (one on the first commit, one on the second). No compile errors were found by hand. Fixed:
  - a first batch of days of 1-minute candles dropped history before counting it;
  - the 24x7 study's counted span was moved by its own first candle;
  - today's chain, gamma and VWAP were given to older candles of the day (look-ahead); now only to the candle that just closed, with gamma older than 15 minutes left out;
  - design findings that failed out of sample were counted as edges;
  - an hour-long off-hours wait could miss the open; the daily study would re-download two years of candles and re-read the same sessions every night;
  - the trade manager was woken by candle reads; MCX read failures were cached; a finished batch could refill memory after a release; the cell cap could squeeze out new context cells;
  - smaller fixes: posts capped at one per series per batch (5m and longer), no stale flag while a market is shut, page-test robustness, What's new wording.
