# Erase everything: every store (26 Oct)

Branch `claude/erase-all-stores`, based on `claude/android-options-lab-kotlin-xgt1ym` (main did not have the "erase key race"
merge yet). CI green on **882ee3679798a21a112e9f7054ad866e5fe2c4c7** (Build APK, the four app test shards, engine parity, PC harness).

## What was wrong

`eraseEverything()` named about 20 stores by hand. Many more keep a copy of personal or trading data in memory and saved it
again (sealed with a new key) after the erase, so data survived "Erase everything". A file a store set aside during the erase
(its read refused) was renamed to `<name>.unreadable.<ms>` and survived too.

## What changed

- **One registry, `android/app/src/main/java/com/optionslab/app/data/PersonalStores.kt`.** Each store joins `ALL` with its
  wipe (memory and files, never reading the vault), its files and a memory-only check. `eraseEverything()` (Root.kt) wipes
  them all inside the existing `Vault.beginErase/endErase` gate, each wipe guarded, so one failure never stops the rest; the
  large folders stay on the slow file thread. Wipes that threw are named in `eraseFailures`.
- **Saves already under way.** Each store with a book drops a save that read the book before the erase (a wipe counter or a
  same-book check), so the old book is never written back after the gate is released. Nothing new on the order or tick path:
  only volatile reads there (OrbArms, ShadowArms); PineAuto's live save writes without its lock and undoes a write an erase
  raced.
- **`Vault.setAside` during an erase deletes the file** instead of renaming it.
- **The home-screen widgets are redrawn** after the erase; **screens drop their copies** before the slow folders go (a key
  form could save its draft again in that gap).
- `IraAlgoApp` tells the registry, LiquidityNotices and CandleAgentHost the app's folders at start.
- What's New entry "Erase everything now erases every book" (newest first; WhatsNewChangelogTest and BatteryRestTest indices
  moved by one).

## Stores found and wiped

115 store sources (117 entries; 7 on the slow file thread).

- **Already wiped before (21):** BiometricGate, Broker, Diag, FillSlippage, History, Ledger (and Alarms), LiveRecorder, Market,
  MarketRecorder, OiWallShadowHost, OrbArms, Paper, PineAuto, PineScripts, Protections, SecurePrefs, ShadowArms, ShortAnswer,
  Store, Strategies, TradeBook (and Journal). Several of their wipes missed memory (OrbArms' armed/stop hints and paper legs,
  ShadowArms' legs, Paper's noted holdings, PineScripts' compiled scripts...): now cleared.
- **New (94):** AccountStopHost, AgentLessons, AppHealth, ArmLive, Autopilot, BossRiskHost, BossTradeLog, BotReplayStore,
  CandleAgentHost, DayLockGuard, DayOco, EventDays, ExitCoordinator, ExpirySquareOff, ExposureCapHost, FlowGate, Ft3Arm,
  GammaLive, GitHubSync, GlanceSource, GoldDipPaper, GoldPaper, GoldTasPaper, GoldTrendPaper, GrammarArm, HealthReport,
  HeroTicketArm, HiddenArms, IraAccount, IraAlgoApp (the crash report), IraBots, IraCoach, IraDayJournal, IraExpert, IraHub,
  IraJournal, IraModel, IraNewsTrades, IraNotes, IraSolo, IraStudy, IraTax (the cache's exports folder: tax CSV, health
  reports), IraThinking, IraTools, IraWeekly, IraWidget, JarvisLearning, JarvisSpeaker, JarvisVoice, Jobs (ticket notices),
  KiteStream, LearningFreeze, LiquidityNotices, LiquidityTest, LiveSelfTest, LossBreaker, MainActivity, ManagerSpecialists,
  MarketContext, McxMarket, McxNr7Record, McxPaperArms, MoveRecorder, NightArm, NtfyArm, NtfyReader, OpenChange (every arm's
  open-trade exits), OpenWidget, OrderReviewHost, OrderTiming, PaperOnly, PassedArmsHost, PayoutWatch, PineScreen (the unsaved
  draft), PortfolioHost, PreTradeShadow (the danger meter), ProblemsHost, RegimeHost, Relay, Rx (ira-core's caches of Boss's
  words, via a new `WordCaches`), SecureBlobs, SelfGrowth, SelfJobs, SharedKnowledgeHost, SmartWorkers, StaticIp, Sweeper,
  TimeMachineHost, TradeManagerHost, TrendWatchHost, VariantRunner, VixDivArm, VoiceGuard, WatchlistHost.
- **Settings only, no copy in memory (34, `PREFS_ONLY`):** wiped by `SecurePrefs.wipe()`; each with its reason in the file.
- **Nothing personal (34, `NOT_PERSONAL`):** pure logic, screens that save through a store above, public market data; each
  with its reason.

## Files that may remain after an erase (`KEPT_FILES`)

Public market data only (the erase always kept it), and nothing else:

| File | Why it holds nothing personal |
|---|---|
| `orderflow/*.csv.gz`, `orderflow/events/**` | public order-flow bars, footprints and big-move recordings (FlowGate's shadow logs in the same folder are deleted) |
| `history/**` | public 1-minute bars and end-of-day chains |
| `holidays.json`, `mcx_holidays.json` | public trading calendars |
| `mcx_contracts.json(.tmp)`, `contracts_bfo.json`, `contracts_stocks.txt(.tmp)` | public contract lists |
| `*.gguf`, `*.gguf.part` | the public on-phone language model (up to 2 GB to download again) |
| `night_marks.xml` | alarm times, CPU budget, install stamp, ntfy's armed flag |

The test refuses a kept entry that is a vault file, a set-aside copy or a settings file.

## Tests

- `android/app/src/test/java/com/optionslab/app/data/PersonalStoresTest.kt` (Robolectric):
  - `everySourceThatSavesIsListed`: every main source file (app, ira-core, engine) that touches the vault, the settings,
    SecureBlobs, the app's folders or writes a file is in `ALL`, `PREFS_ONLY` or `NOT_PERSONAL`. A new store fails here.
  - `theListsNameRealFilesOnceEach`, `everyStoreHasASeed` (a store without a seed fails), `theGlobsMatchAsWritten`.
  - `eraseLeavesNoStoreHoldingDataAndNoFile`: seeds every store through its own API (memory and disk), checks each took it,
    erases, then checks no wipe threw, no store holds data in memory, none of their files exist, and no file is left under
    files/, no_backup/, cache/, shared_prefs/ or databases/ outside `KEPT_FILES`.
  - `aWipeThatThrowsNeverStopsTheRest`, `aFileSetAsideDuringAnEraseIsDeleted`.
- `android/ira-core/src/test/kotlin/com/optionslab/ira/WordCachesTest.kt`: the word caches are forgotten.
- Existing erase tests in RootNavigationTest (the key race) still pass.

## Review

Four separate reviewer agents read the whole diff (compile errors first, then races, locks, the order path, test
expectations). Fixed from their findings: Ft3Arm, NtfyArm, VixDivArm, PassedArmsHost, ArmLive, HeroTicketArm and McxMarket keep
a memory-only `wipe()` that tests use as a restart, with a separate erase that deletes the file; old books read before the
erase are no longer cached (Night, Hero, Grammar, FT3, gold books, trade manager); a kept-files pattern that would have hidden
FlowGate's shadow logs was narrowed; PineAuto's live save no longer holds a lock during its write; MarketContext's background
switch save is dropped after an erase.

## Things Boss should know

- After an erase the phone no longer watches open positions (the books, stops and broker login are all gone); only stops
  resting at the broker (SL/GTT) protect a live position. That cannot be otherwise once the login is erased.
- The exit registry (exits already sent, in memory only, a few minutes) is kept, so a turn under way can never send a second
  exit for the same position.
- After an erase the switches go back to a fresh phone's defaults: Liquidity BANKNIFTY is frozen again, FT3 and Hero switch
  themselves on once as on a new install, the exposure cap goes back to its defaults (live in shadow).
- Small windows remain where a background pass stalled across the whole erase could still save one value (listed in the
  helpers' notes; e.g. IraSolo or OrderTiming writing one setting just after the wipe). None keeps a book.
