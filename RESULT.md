# ntfy messages read late: result (26 Oct 2026)

Boss: "I think ntfy messages are getting read late, please check." Branch `claude/ntfy-latency`, no PR.

## What was late, and by how much (from the code and the tests)

1. **The trade waited for the next minute (the main delay).**
   - The reader handed each message to `NtfyArm.receive`, which only marked it PENDING.
   - The trade was decided in `NtfyArm.tick`, and that ran only:
     - in the watch's bar-close wake (the next minute boundary plus a few seconds), or
     - in the safety-net and full passes.
   - So a message arriving mid-minute waited **up to about 60–65 s**, and longer when a pass was slow or the battery saver doubled the pace.
   - The index price that tick needed was always a network candle read.
2. **The reader started late, and restarted late after a stop.**
   - Only Jarvis's words lane started it (`Jobs.wordsRound → NtfyReader.ensure`). That lane runs from 09:15 and every 60 s (120 s with the battery saver), and only in builds with Jarvis.
   - The reader's hours start at 09:00, but it started at **09:15 at the earliest**, and the market watch (which keeps the phone awake) started at 09:14.
   - After a memory release or a stop, it waited for the next words round: **60–120 s with no reader**.
3. **A failing line was retried slowly.** Reconnect waits grew to **60 s** (1, 2, 4 … 60 s).
   - The stream itself was fine:
     - each NDJSON line is handed over as it arrives;
     - a 90 s read timeout against ntfy's 45 s keepalive;
     - the catch-up poll starts from the last id read, so nothing is missed or read twice.
   - The watch is a foreground service, so it keeps network access while the phone is in Doze.

## What changed

- **Decided within seconds.**
  - When `receive` keeps a fresh PENDING message (a buy or an exit), it starts a quick pass at once: `NtfyArm.kick`, its own coroutine on the IO pool, started after the read's lock is released.
  - Kicks that arrive while a pass runs are merged into one more pass.
  - The quick pass is the same `NtfyArm.tick`, so every guard applies unchanged: kill switch, day lock, loss limits, account guard, Boss's 25 Oct risk rules, paper/live, read-once and stale checks.
  - Passes never overlap (a new `passLock`). The 15-second stop check (`manageOnly`) never takes it, so a slow quick pass cannot delay a stop (tested).
  - A waiting buy's strike uses the live stream's index price when it is fresh (at most 5 s old). A held trade's index stop and target still use minute closes, so its exit rule is unchanged.
- **The reader has its own supervisor.** `NtfyReader.supervise` is started with the app, after the positions-first step.
  - While ntfy is armed (switched on with a topic set), it looks every 3 s:
    - it starts the reader at 09:00;
    - it restarts it within seconds after a memory release or a stop;
    - it never runs it outside its hours.
  - With ntfy armed, the market watch's alarm moves to 09:00. A plain marker on the phone keeps this across reboots, and the first look after a restart re-arms the alarm.
  - The watch keeps the processor awake while the reader is reading. The hung-watch diary line still fires.
  - The stock options list is requested at most every 5 minutes.
  - A settings read that fails changes nothing.
- **Reconnect** waits are capped at 15 s, or 60 s when the server answers 429.
- **Timing record.** Each message stores three times: when it was read, when a pass first decided on it, and when it was settled. They are kept in the book and measured from the server's time (`e.at`).
  - **The ntfy page** shows, for each message, "read 1.0s · decided 1.0s" (plus "· done …" when a buy waited for its price), and today's line.
  - **The diagnostics** have that line under the ntfy line: "ntfy: N messages, read in median Xs / max Ys, acted in median Xs / max Ys".
  - **The diary** has each message's line with its seconds.
  - Only message ids, counts and seconds are recorded: no topic and no new message text.
- **What's New** has a new entry, newest of all.

## Tests

- **Engine:** `NtfyRulesTest` covers the backoff cap and 429, and the timing, median and day line.
- **App, `NtfyArmTest`:**
  - a message arriving mid-minute is decided in under 5 s with no watch pass, with its timing, and the timing is kept across a restart;
  - two messages in quick succession are each acted on once, and their duplicate is read once;
  - an exit right after a buy leaves nothing open and never sells short;
  - stale and duplicate messages are still refused and need no pass;
  - a slow quick pass never delays the stop check;
  - the "done" time of a buy that waited for its price.
- **App, `NtfyReaderTest`:**
  - a message from the stream is decided within seconds;
  - the supervisor starts the reader at 09:00 and not at 08:59:58;
  - it restarts within seconds after a memory release;
  - it stays stopped outside hours and when ntfy is off.
- **App, other:**
  - `JobsTest`: the watch's 09:00 start when armed, 09:14 otherwise.
  - `NtfyPageTest`: each message's timing and today's line.
- **ira-core:** `WhatsNewChangelogTest` and `BatteryRestTest`, with the entry indices moved down one.
- **Locally:** `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification` passes. `:app` runs only in CI.
- **Also fixed, a test bug that was already there:** `PaperExitReduceTest.aShortsRestingBuyStopFillsOnAHighBetweenLooks` failed whenever CI ran before 12:00 IST, on any branch. Its stop used the tests' noon clock but its price spike used the real time. The test now uses one clock.

## Review

- A separate reviewer agent read the full diff. It found no compile errors, deadlocks or double orders, and found six things to fix, all fixed:
  - the alarm could stay at 09:14 after a reboot;
  - the stock list was retried every 3 s while it kept failing;
  - the hung-watch check was masked;
  - the words lane lost its battery rest;
  - the index stop and target had changed to live ticks;
  - quick passes could carry over between tests.
- Smaller items also fixed:
  - a failing settings read changing the armed state;
  - the "done" time;
  - an old run overwriting a new run's status;
  - a note on the jobs page that never showed.
- CI then found two more, also fixed:
  - the arm must not name `Broker` (the index token is now read through the stream);
  - a timing race in an older reader test.

## CI

Green on `12ab86bfa85c28b310d67524cadf9a2970bd80cc`: PC harness tests, Engine parity with the PC, App tests 0/4, 1/4, 2/4 and 3/4, and Build APK all passed (Publish screenshots skipped, as on a branch). This RESULT.md commit changes no code.

## Not done, and why

- **A reader restarted under memory pressure.** As asked, it is back within seconds after a memory release (it is one connection and a line buffer). If memory is still short, it will be released again at the next trim.
- **A buy waiting for a price level** ("@ 100", "above X") is still checked each minute, as before. That wait is the market's, not the reader's.
- **The model name in early commit trailers.** The first four pushed commits on this branch name the model in their co-author line ("Claude Opus 5.5"). Changing them needs a force-push of the branch, which was refused. Later commits say "Claude" only.
