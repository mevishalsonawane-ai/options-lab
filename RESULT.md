# Deep review, phase 1: RESULT (20 Oct 2026)

Branch `claude/deep-review-1020` (based on main `d656e03`). Boss, 20 Oct: "Deep analysis of the whole application, find if
there are any issues and resolve them." No features added.

**How it was done**
- **Reviewers.** Six independent reviewers, one per area: the order path and gates; the tick/stream path and positions
  first; the paper engine and arms; Jarvis, agents and the Candle agent; data, sync, security and threading; UI.
- **Verification.** Every finding was checked against the code, by me or by a separate verifier agent, before any fix.
- **Fixes.** Each confirmed finding was fixed, with tests.
- **Second pass.** Three fresh reviewers then went over the full diff. Their confirmed findings were fixed too (marked
  "2nd").
- **Live evidence.** No 20 Oct diagnostics file exists yet. The 19 Oct file (Liquidity's three 09:30 stops, −191.6 pts,
  and 7 "Zerodha requests failing" errors) was read; the 19 Oct fixes already cover its errors.

**Live-safety stance.** No fix adds a new rule that skips or resizes a LIVE trade. Every live-side change does one of
four things:
- stops a double order;
- adds a stop at the broker;
- restores an existing guard that was being bypassed (the no-quote entry);
- only adds words.

The same-direction cap's live side stays in shadow by default, as before.

## Confirmed issues fixed (most severe first)

Each row gives the severity, the cause, the fix, and the test (app Robolectric unless marked).

**Order path**

| Severity | Cause | Fix | Test |
|---|---|---|---|
| High | 15:05 expiry square-off read positions before orders. An exit that filled between the two reads was sent again, leaving a short on expiry day. | Orders are read first, then positions (as McxGuard). | `ExpirySquareOffTest.anExitFillingBetweenTheReadsIsNeverSentTwice`, `ordersAreReadBeforeThePositions`. FakeKite gained `afterNext` to fill between reads. |
| High | Strategies' live exit had the same read order and could double a protection's or the expiry's exit. | Orders first. | `StrategiesLiveTest.aRestingExitFillingBetweenTheReadsIsNotDoubled` |
| High | The paper same-direction cap only *logged* every entry in Live with AI-live on (`mirror == null` counted as "may go live"). This covered Grammar, Pine paper and Jarvis news, which can never go live. Those entries were also never counted. | Paper-only callers pass `ExposureCapHost.NO_LIVE`, so they are capped. Every automatic entry is counted, even when the cap only logs. | `ExposureSpeedTest.inLiveAPaperOnlyEntryIsStillCappedAndEveryEntryIsCounted` |
| Medium | The live cap counted an entry only after the entry door unlocked, so 4 puts queued on one signal each passed the check. | Counted inside the door. A lost reply also counts (over-counting only lowers risk). | existing cap tests |
| Medium | A strategy's live MARKET entry with no quote was guarded at price 0, so the value and exposure limits passed it. | The price is NaN, so the existing guard refuses it ("never enter blind"). | `StrategiesLiveTest.aLiveEntryWithNoQuoteIsRefusedNotPricedAtZero` |
| Medium | A carried (NRML) protection's DAY stop and target ended at the close. The protection was then marked finished and nothing watched the position. | Kept over the close and placed again at the next open, for at most what it covered, less any exit already resting. If Zerodha refuses because the price gapped through the stop, that is said plainly with the price. MIS is unchanged. | `ProtectionsLiveTest` (6 new) |
| Medium | Expiry square-off and ArmLive's own 15:05 exit could both sell one contract. | Arm-owned contracts are left to the arm until a 15:08 backstop (still counted open). At the backstop, an arm exit still working is never cancelled. | `anArmsLiveTradeIsLeftToItsOwnerUntilTheBackstop`, `atTheBackstopAnArmsWorkingExitIsLeftAlone` |
| Medium | SENSEX (BFO) options were never squared off at 15:05, and arms' SENSEX trades got a false "NOT closed" notice. | BFO list included, sent on BFO. | `aSensexOptionExpiringTodayIsClosedOnBfo` |
| Low | Any SELL exit freed a live cap place: a resting target, a partial exit, Boss's own exit, a rejected exit. | Only a positions read frees a place. | covered by cap tests |
| Low | Options carried overnight were never re-counted by the live cap on a new day. | Seeded once ArmLive's book is read, from trades bought before today. | `ExposureSpeedTest.aCarriedLiveOptionIsCountedOnTheNewDay` |
| Low | ZerodhaSync reported ArmLive's live trades as UNTRACKED. | Included. | `ZerodhaSyncTest.anArmsLiveTradeIsFollowedNotUntracked` |
| Low | A withdrawal reads as a drawdown and turns the kill switch on. | Words only: the refusal says how to restart the peak. The peak is never lowered automatically, because that would hide a real loss. | engine `AccountGuardTest.aDrawdownTodaysTradingDoesNotExplainIsToldApart` |

**Paper records**

| Severity | Cause | Fix | Test |
|---|---|---|---|
| Medium | PassedArms booked a STOP or LOCK that the walk found on a closed minute (a wick) at the later, better market fill. This flattered R51/R72's forward bar. | Booked at the lower of the fill and the walk's price. The fill is kept in `paperFill`. | engine `PassedArmsTest.aStopOrLockIsBookedNoBetterThanTheWalk` |
| Low | Liquidity's saved state (Boss's 13 Oct unfreeze, the trail) was thrown away if anything changed before the background read. | Early edits are replayed over the saved state, and a save never writes defaults over it. | `LiquidityPaperTestAppTest.aChangeBeforeTheSavedStateIsReadNeverLosesIt` |

**Tick path and open positions first**

| Severity | Cause | Fix | Test |
|---|---|---|---|
| High | ORB's pass replaced the stream's "index" set: NIFTY, BANKNIFTY and VIX were unsubscribed whenever no ORB arm was armed. That hit the move recorder, local candles, the widget and the fast index signal. | The set always keeps `INDEX_TOKENS` plus the armed indices. | `OrbArmsDayTest.thePassNeverDropsTheStreamsIndices` |
| Medium | Low battery stretched the 15-s stop check to 18–24 s. | A wait never passes the window end. | ira-core `StopWindowTest` |
| Medium | SENSEX live exits had no stream token, so they fell back to a REST read every 2 s. | Token from today's BFO list in memory. | `TickPathReviewTest.aSensexOptionHasItsStreamTokenFromTodaysBfoList` |
| Medium | The instruments file was parsed on the fast-path thread after a restart. | Memory-only lookup, warmed on IO. MCX was kept working after a 2nd-pass regression. | `afterARestartTheLiveTokensNeverParseTheInstrumentsFileOnTheTickPath`, `anMcxLiveTradesTokenReachesTheLiveExitsFromMemory` |
| Low | Paper-book positions did not count as "held" for the 15-s check. | Counted. | `thePaperBookAndTheMinuteArmsCountAsHeldForTheFifteenSecondCheck` |
| Low | At start-up the analysis workers began before FastPath. | FastPath starts first, with no owner leak. The brain worker waits for positions. | JobsTest |

**Candle agent (landed today)**

| Severity | Cause | Fix | Test |
|---|---|---|---|
| High | 5m/15m candles were judged closed by the clock. Partial candles (MCX minutes cached 10 min, stale index minutes) were read, counted and posted. | Closed by the data's own time. The newest minute of a running session is not trusted. "Session over" only when the data reaches the close (2nd). | ira-core `CandleReviewFixesTest` |
| Medium | Chain, gamma, OI and VWAP context read at batch time was counted, which is look-ahead. | Shown and explained, but stripped before counting. Old cells are dropped on load (2nd). | `theChainGammaAndAnOutsideVwapReadAtTheBatchAreExplainedButNeverCounted`, `oldLookAheadCellsAreDroppedOnLoad` |
| Medium | Thousands of cells were tested at \|z\|≥2 with no correction, producing false "differs" notes and lessons. | Benjamini–Hochberg at q<0.10. A research edge not yet seen in its own count reads "too few", not "differs". The per-candle wording says it is uncorrected (2nd). | `thousandsOfCoinFlipCellsWriteNoNoteAndARealOneStillDoes` |
| Medium | "Latest candles" and Jarvis's default answer were in series order, not time order. NIFTY's newest could be pushed out. | Sorted newest first, then trimmed. | `theLatestReadsAreNewestFirstWhateverSeriesTheyCameFrom` |
| Medium | The last ~10 candles of every MCX session were never counted. | One settle batch after the close, once per session, never on a failed open read. | `theMcxSettleRunsOnceASession` |
| Low | An open explanation dialog switched candles. A failed batch was never shown. The status promised an hourly batch. The stats file had two writers. Findings were heard twice. | Rows keyed. Publish on failure. Honest wording. A save lock with an atomic move. A heard watermark. | CandleAgentPageTest, CandleAgentHostTest, ira-core |

**Data, sync and security**

| Severity | Cause | Fix | Test |
|---|---|---|---|
| Medium-High | A backup restore raced the live stores' saves. A shared `<name>.tmp` plus a delete-then-rename fallback could **delete** a vault file. | A unique temp file per write, with no delete fallback. The restore freezes its files; the freeze is re-checked under a lock before the rename (2nd) and never thawed in production (2nd). Leftover temp files are swept. | `VaultAtomicTest` (4) |
| Medium | GitHub sync accepted any repository, including a public one. | Only `CloudSync.OWNER`'s private repositories, checked at save and before each run. A transient error skips that one run. | `GitHubSyncTest` (3), `CloudSyncTest` |
| Medium | "Erase everything" could stop half-way and leave the vault readable. It ran on the main thread. | Keys destroyed first and again at the end. Every wipe is guarded. Big deletes run off the main thread. | `RootNavigationTest.aWipeThatThrowsStillDestroysTheKeys` |
| Low-Med | "Try again" on an unreadable vault ran on the main thread (ANR). The crash report read and deleted its file on main and showed over the PIN. | Both on IO. The crash report shows only when unlocked. | `RootNavigationTest` |
| Low | A large-value write failed silently and `flush()` said true. Jarvis's safety-setting retry did nothing. Keystore work ran under a value lock the screen takes. "Forget" could be undone by a concurrent remember. | All fixed. | `SecureBlobsTest`, `JarvisVoiceKeepTest` |
| Low | A Keystore that returned null for an existing key would replace the key and lock every file for good. | Throws instead. | `VaultAtomicTest.aKeyPresentButNotReturnedIsNeverReplaced` |
| Low | Third-party actions with write access and the signing secrets were unpinned. | `gradle/actions/setup-gradle` and `android-emulator-runner` pinned to commit SHAs (checked against their tags). gold-emulator is read-only at workflow level. | YAML parses |

**UI**

| Severity | Cause | Fix | Test |
|---|---|---|---|
| Medium | A bracket on a Market order with no LTP skipped validation, so a live order could go out with a wrong-side stop. | Disabled: "needs a price". | `OptionOrderSheetTest.aBracketWithNoPriceToCheckItAgainstHoldsTheOrder` |
| Low-Med | The lots steppers were 36 dp and unnamed, and the Bracket toggle was 28 dp. | 48 dp, a button role, named, with expanded state. | `theSteppersAndTheBracketToggleAreButtonsTalkBackCanName` |
| Low | The Jarvis widget showed trade replies (contract, lots, stop) on the home screen. | Trade words and CE/PE hidden. | `JarvisWidgetSecretTest` |
| Low | `phoneLocked()` failed open. | Fails closed. | `PhoneLockedFailsClosedTest` |

**What's New.** One entry, `2026-10-20-deep-review-fixes`, with 7 sentences. The hard-coded indices in
WhatsNewChangelogTest and BatteryRestTest are shifted by +1.

## False positives and partial findings dropped

- **TradingBusy counter race.** Its only caller pairs `begin`/`end` in try/finally, so the count never goes negative.
- **Night/VixDiv/MCX exits "once a minute".** Their stops rest in the paper book, and Night has no stop. Only the
  "held" predicate gap was real, and it is fixed.
- **Candle agent start-up "before positions".** `batch()` already waits for PositionsFirst. Only the FastPath order was
  changed.
- **The Candle agent's look-ahead tags reaching the lessons.** Narrower than reported: lessons use only the "all" cells.
  The tags are fixed anyway.
- **A9 (`phoneLocked`) in practice.** The app always sets the context first, so this was theoretical. It now fails
  closed anyway.
- **Liquidity's three 09:30 stops on 19 Oct.** No code defect. Two books held the same BANKNIFTY put, and FINNIFTY was
  correlated, after a 0.42% drop in 10 minutes. The no-twin rule for Liquidity is idea 3 below.
- **Not fixed, recorded:**
  - **Candle A5:** the overnight study's older and newer days join with a gap. It needs a "count only these days"
    field and triggers only after missed days.
  - **Liquidity's early edits:** an early `setTrail`'s Undo value, and a stop told twice after a restart. Both behave
    as before this change.
  - **Remaining `actions/*@v4` tags:** GitHub's own actions are still on version tags, not commit SHAs.
  - **Five JobsTest failures in this sandbox:** they also fail on clean main here, pass in CI, and passed in a later
    local run.

## Checks

- **Engine and ira-core:** `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification`
  is green (exit 0, coverage included).
- **App tests:** run locally with CI's flags (`-PtestsWithoutJarvis=true :app:testIraDebugUnitTest`, an SDK installed in
  the sandbox). Every touched or related class was run: 43 classes and 623 tests, including OrderPathRound2Test (21/21),
  JobsTest and the order, cap, protection, security and UI classes.
- **Failures that run caught, all fixed:**
  - a SENSEX position whose list was still loading let the day be marked done;
  - three tests blocked a vault write through the old fixed temp name. They now use `Vault.testFailWrites`.
- **Full suite:** CI runs all four app shards, Build APK, engine parity and the PC harness.
- **Note for future sessions:** parallel Gradle builds in one checkout corrupt each other's caches. Run one at a time,
  with `-Pkotlin.compiler.execution.strategy=in-process`.

## Top 15 enhancement ideas (for the enhancement loop)

Ranked by value to Boss against risk.

1. **One exit coordinator per contract.** Every exit sender (expiry, ArmLive, ORB, Strategies, Protections) checks one
   registry of exits in flight. This ends every double-sell class at its root. *High value; medium risk (every exit
   path).*
2. **GTT for every carried live position** (Protections, Jarvis, Strategies), as ArmLive already does, so a dead phone
   misses nothing. *High; low.*
3. **A no-twin rule for Liquidity's 15m and 5m books on one contract:** the second becomes a would-be trade. Paper
   only. This is the 19 Oct triple stop. *High; low.*
4. **The account stop rule (−₹40k / −₹13k a month) across all live trades,** not only ArmLive and ORB. It only blocks.
   *High; low.*
5. **Encrypt the daily diagnostics** with the live-file key before upload; `read_diagnostics.py` decrypts with the
   passphrase. *High; low.*
6. **A drift guard on the 15-s stop check:** log or alert when the real gap goes over 16 s while holding. *Medium;
   none.*
7. **One shared "holding anything" predicate** in ira-core. There are four today, which is how the T3 gap happened.
   *Medium; low.*
8. **Paper positions as an explicit stream owner** instead of 3-minute "touches." *Medium; low.*
9. **A daily diagnostics count of entries the cap "only logged"**, so a silent bypass like today's shows up. *Medium;
   none.*
10. **A Candle page "as of HH:mm" line, instrument filter chips, and a What's New tap that opens Research → Candles.**
    *Medium; low.*
11. **Count only 5m and 15m candle reads into lessons** (1m explained only). This cuts overlap noise. *Medium; low.*
12. **Payout-aware drawdown:** read Kite's `utilised.payout` so a withdrawal never trips the kill switch, with Boss
    confirming. *Medium; low.*
13. **Mark diagnostics copied to the clipboard as sensitive** (Android 13+ preview). *Medium; none.*
14. **Keep the Liquidity live stop rule's state in its saved state** rather than recomputing it from the last 2000
    positions. *Medium; low.*
15. **Weekly Jarvis review (TODO I15):** a 2-minute spoken Sunday summary of what worked and what cost money. *Medium;
    low.*
