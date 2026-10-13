# IraAlgo TODO

Gap analysis of D:\New Trading app (desktop app, branch `custom`) against
D:\IraAlgo (options-lab @ bba1def), 2026-09-25. Nothing below is implemented
yet; each item waits for an explicit go-ahead.

Source paths prefixed `NTA:` are in D:\New Trading app.

## Decision needed first

- [x] D1. (decided 2026-09-25: phone) Which app runs the ORB forward test from now on: laptop (NTA) or phone (IraAlgo)?
      If IraAlgo: do A1-A4, then paper-trade both side by side for a few days before turning the laptop arms off.

## A. Port missing core functionality into IraAlgo (must precede arming anything)

- [x] A1. (done 2026-09-26 from `strategies/orb/`: engine `orb/OrbRules.kt`, `orb/Replay.kt` with PassRule + tests; app `data/OrbArms.kt` on the paper account, Home -> Strategies arm switches, approvals, day detail and forward-test tally; 15 s stop/target checks while in a trade) Port real ORB / ORB Fresh logic (09:15-10:00 range, breakout entry 10:05-14:25,
      one ATM strike all day, +40/-40 option points, resting stop, 15:10 exit, Rs 40 min premium, 1 lot).
      Source: `NTA:mobile/native/app/app/src/main/java/com/iraalgo/app/rules/OrbRules.kt`,
      `rules/Replay.kt`, `arms/ArmRunner.kt` + their parity tests; Python origin `NTA:services/ai_signals/orb_arm.py`.
- [x] A2. (aligned with the desktop 2026-09-26: rupee exposure per instrument Rs 2L, Rs 2L per order, cutoff 14:55, every order counts, a drawdown hit engages the kill switch, paper account limits 60 orders / Rs 6,000 / 30% as the desktop paper test) (done: engine `risk/AccountGuard.kt` + tests, app `data/Guard.kt`, More -> Bot -> Bot settings) Port account-wide guard: kill switch, daily loss, drawdown (vs capital and vs persisted peak),
      max concurrent positions, max trades/day, order value, symbol exposure, entry cutoff, naked short.
      Exits bypass all but the kill switch. Source: `NTA:.../rules/AccountGuard.kt` + `AccountGuardParityTest`
      (222 vectors from `NTA:mobile/native/make_vectors.py`); Python origin `NTA:services/risk/account_guard.py`.
- [x] A3. (done: one bot button on Home's Strategies card - Stop for today / Start / Clear kill switch, each confirmed) Account-level Stop/Start arms for the day + kill-switch clear (confirm dialogs).
      Source: `NTA:blueprints/ai_signals_activity.py`, `NTA:frontend/src/components/trading/ArmsControl.tsx`.
- [x] A4. (done: imported ORB strategies stay blocked and are hidden from Home, replaced by the built-in arms of A1) Block or replace the imported ORB / ORB Fresh JSON strategies (`android/.../data/Strategies.kt:187-216`):
      they currently run as time-scheduled baskets with NO breakout check. DO NOT ARM until A1 lands.
- [x] A5. (done: `data/ExpirySquareOff.kt` from the market watch, paper + live, keeps the Expiry Put to settlement by default; toggles in Bot settings) Expiry-day square-off at 15:05 for all products (NTA: `services/expiry_squareoff.py`);
      IraAlgo currently settles at expiry / squares MIS at 15:15.
- [x] A6. (done: one set of limits in Bot settings; the Zerodha checks use them, the separate Zerodha caps are gone) Decide order limits: IraAlgo 4 orders/day + Rs 5L/order vs NTA guard limits.
- [x] A7. (skipped by the owner's decision, 2026-09-25) Minute market snapshots (BANKNIFTY/NIFTY/SENSEX/VIX + near-ATM options, 09:15-15:30).
      Source: `NTA:services/market_data_collector.py` -> `db/market_snapshots.duckdb`.
- [x] A8. Evening ORB replay (15:35-15:40) beside paper results. Source: `NTA:services/ai_signals/orb_shadow.py`
      (off on NTA; native app Task 8A never started). Done 2026-09-26: `OrbArms.replayIfDue` after 15:35 (the 15:45
      job and on opening the app), both arms on the day's 5-minute bars, shown in the ORB day detail; records up/down day for the pass rule.
- [x] A9. Verify sandbox charges/slippage parity with NTA (`NTA:sandbox/charges.py`, `sandbox/slippage.py`,
      stop slippage 10 bps, spread fallback 5 bps). Done 2026-09-26: `engine/sandbox/SandboxCosts.kt` (+ tests),
      on for the phone's paper account; each paper trade keeps its charges.
- [x] A10. Heartbeat / dead-man alert when the engine stops during market hours
      (incomplete on NTA too: `NTA:services/heartbeat_service.py`). Done on the phone: the market watch stamps
      a heartbeat each pass; an alarm checks it every 5 min from 09:17 to 15:30, restarts a watch silent for
      over 3 min and posts one "Market watch stopped" notice per stall (approval channel, so it always shows).
- [x] A11. (dropped by the owner, 2026-09-28: no Telegram; phone notifications only) Telegram alerts.
- [x] A12. (done 2026-09-28: the 09:00 morning check loads today's contract list and, when logged in, Zerodha's instrument list; 15:45 day report; AI Signals skipped) Optional: pre-market routine (symbol refresh, daily report). AI Signals: skip unless revived (research refused it).

## B. IraAlgo housekeeping

- [x] B1. (not applicable, owner 2026-09-28: only the Android app is used from now on; no laptop harvest) Re-point `tools/harvest_nightly.ps1` (hard-coded to `C:\Users\mevis\Downloads\files\options-lab`, Python310)
      and re-register in Task Scheduler; harvested bars stop at 2026-09-10 (missed sessions are lost for good).
- [x] B2. (done 2026-09-28: CI signs with the owner's key; the build log prints the public fingerprint) Release signing: run `android/tools/make-release-key.sh`, add the 4 GitHub secrets
      (otherwise every update needs uninstall, which wipes the vault).
- [ ] B3. Static IP for live orders (SEBI): VPS (Oracle Always Free works) or a home static IP + `android/tools/wg-relay-setup.sh`; then enter the IP in the app (More → Zerodha → Static IP) and check it shows ✓. The app now refuses new live positions from any other IP.
- [x] B4. (dropped by the owner, 2026-09-28: Android app only; the PC research track is not pursued) Research milestones in `docs/design.md`: M1 in progress, M4-M9 open, M9 forward holdout ~Dec 2026;
      missing tests `test_theta_units`, `test_long_short_mirror`, `test_exits_fire`, `test_no_engine_import`; pricer/IV module;
      confirm M3 Kaggle splice reconciliation.
- [x] B5. (skipped by the owner, 2026-10-01: stays unhedged) Hedged variant undecided (`docs/hedged-variant.md`: wing helped 0/170).
- [x] B6. (done in D20) Docs drift: `android/README.md` tab names; root README test count (206 -> ~332).
- [x] B7. (done in D4) App module has no unit/UI tests (engine only).
- [ ] B8. Decide fate of the untracked `options_lab/data/banknifty_expiry_cache/` and modified bars in the old
      `D:\files\options-lab` clone (not present in D:\IraAlgo).
- [x] B9. (skipped by the owner, 2026-10-01: the chart terminal stays) Later (owner, 2026-09-28): replace the chart terminal in `android/app/src/main/assets/chart/` (Apache-2.0 code
      that must keep its NOTICE/LICENSE while it ships) with the app's own native chart, then delete the folder and its notice.
- [x] B10. (done 2026-10-01: a commit message starting "[quick]" builds and publishes the APKs after the engine tests only, skipping the app test shards, PC tests, screenshots and the gold emulator run; see android/README.md) (owner, 2026-09-28) Faster app updates: take the tests out of the APK build path so a fix reaches the
      phone in minutes. Note: the "Build APK" job already runs alongside the tests and uploads the signed APK even when
      a test fails (about 9 minutes); the remaining work is to skip or trim the test shards on a quick-fix push
      (e.g. a "[quick]" commit tag or a separate build-only workflow) while keeping the full tests on main.

## D. Enhancements (owner go-ahead 2026-09-26: all at once; answers: real-order changes need the PIN once;
##    no Telegram; morning check and end-of-day report always show)

- [x] D1. (ready: CI signs with the owner's key once the 4 secrets from `android/tools/make-release-key.sh` are added; README has the steps) Release signing ready (B2): CI signs with the owner's key once the 4 secrets exist; steps in `android/README.md`.
- [x] D2. (done: stream ticks pushed into the chart page, `window.__iraTick`; tested in a browser harness) Live chart from the Zerodha stream: in Live mode the last candle moves with every tick.
- [x] D3. (done: 5 s re-pricing from the stream in Live mode; OI change card from the day's first reading) Live option chain: refreshes from the stream while open; OI change since the day's first reading; PCR and max pain live.
- [x] D4. (done: `app/src/test` JVM tests, run by CI before the APK is built) App unit tests (B7): JVM tests for the app's pure logic, run by CI.
- [x] D5. (done: `engine/risk/Protection.kt` + tests, `data/Protections.kt`; position popup → Protect; paper at once, Zerodha after one PIN) Trailing stop-loss on any position (paper; Zerodha after one PIN): the stop only ever tightens.
- [x] D6. (done: order sheet bracket - stop/trail/target set when the entry fills; chart ALERT button, priced from the chart feed) From the chart: bracket order (entry + stop + target) and price alerts drawn at a level.
- [x] D7. (done: Options → Straddle: any call/put pair, combined premium, pair P&L) Straddle / strangle tracker: combined premium through the day and the pair's live P&L.
- [x] D8. (done: Trade → Strategies → Presets: backtest over harvested sessions, then Add to Strategies, paper and not armed) Strategy presets ready to arm (short straddle, short strangle, iron fly, iron condor), each backtested on harvested sessions first.
- [x] D9. (done: Lab → Replay: harvested day, index or ATM CE/PE, step/play, buy/sell at the close, live P&L) Replay mode: step through a past day candle by candle and paper-trade it.
- [x] D10. (done: trade popup → Journal; P&L tab → Journal card with P&L by tag) Trade journal: note and tags per trade; P&L by tag.
- [x] D11. (done: strategy chips, Month/Year switch with 12 month tiles, Export CSV) P&L calendar: filter by strategy (ORB, manual, Expiry Put, …), year view, CSV export.
- [x] D12. (done: P&L tab → Strategies compared: trips, win %, profit factor, net, avg, best/worst, drawdown, charges) Strategy comparison: each strategy's forward stats side by side.
- [x] D13. (done: P&L tab → Charges by month, line by line; Zerodha estimated from recorded trades) Charges report: brokerage, STT, exchange, SEBI, stamp and GST by month.
- [x] D14. (done: `work/DailyReports.kt`, 09:00 check + 09:10 login reminder, approval channel) Morning check 09:00 (Zerodha login, holidays, contracts, armed strategies) and a login reminder before the open.
- [x] D15. (done: 15:45 day report, approval channel; opens the P&L tab) End-of-day report at 15:45: the day's P&L, trades, strategies and any problems.
- [x] D16. (done: More → Security → Backup and restore) Encrypted backup and restore (PIN-sealed file; Zerodha credentials never included).
- [x] D17. (done: widget from ticks every 5 s while streaming) Home-screen widget live from the stream.
- [x] D18. (done: sideways chart hides the header and tab bar; wide screens add OI columns to the chain) Landscape / tablet: full-screen chart and a wider option chain.
- [x] D19. (done: haptic on every alert; swipe actions already had one) Haptics on order confirm, fills and swipe actions.
- [x] D20. (done: harvest script finds its repo and Python itself; READMEs updated) Docs (B6) and the nightly harvest script path (B1: script fixed; re-registering the task stays with the owner).

## L. Before going live (real money)

- [x] L1. (done 2026-09-28: blocked by default under a new setting key, so every phone starts blocked; allow it again only with the PIN) Turn OFF "Allow screenshots and screen recording" (More -> Security), and set its default
  back to off (`security/Capture.kt`, `DEFAULT_ALLOWED = false`). It is on only while testing.
- [ ] L2. First live ORB trade with the app open: confirm the entry and the SL stop order appear in Kite,
  and that the +40 / 15:10 exit goes out.
- [x] L3. (checked 2026-09-28: Zerodha stops and trailing stops already go out as SL with a limit, like the ORB stop; only paper uses SL-M; the notes on screen now say so) Protections (Zerodha stop / trailing stop) still send SL-M: confirm Zerodha accepts SL-M on
  index options, or switch them to SL with a limit like the ORB stop.
- [x] L4. (done 2026-09-28) Switch the Zerodha setup gate back on: `SKIP_ZERODHA_GATE = false` in `ui/Root.kt` (off while testing other features).
- [ ] L5. Pine auto-trade: run a script on Paper for a few sessions first (Research -> Pine scripts -> Auto-trade),
  then one live trade with the app open: confirm the ATM option BUY, the switch on the next signal, and the 15:15 sell in Kite.

## E. Entry cut-off (owner go-ahead 2026-10-01)

- [x] E1. Last new entry at 14:00 (was 14:30) for ORB, ORB Fresh and Liquidity 15+5 (research/ENTRY_CUTOFF.md: better in
  both years for ORB and Liquidity, about even for ORB Fresh). ORB Sweep keeps 14:25 (14:00 made it worse); Range Fade
  already stops at 13:55. The 15:10 exit is unchanged.

## R. Research queue (owner, 2026-09-30)

- [ ] R1. Liquidity 15+5 on NIFTY / FINNIFTY / SENSEX (research/liquidity_indices.py, Actions job indices.yml):
  report the results first; only then add the Liquidity 15+5 arm to the app (engine rules in `orb/LiquidityRules.kt`).
- [ ] R2. AFTER the Liquidity 15+5 arm is tested and pushed: run the same liquidity rules on XAUUSD (gold vs US dollar,
  forex) - the `forex/` folder has the fetch scripts. Note: forex is not tradable from Zerodha / the app; research only.

## P. Position screen (owner, 2026-10-08)

- [ ] P1. Show every stop-loss and target that is active on a position, in the position popup (Trade -> Account ->
  tap a position) and on the position row. Today the popup only offers "Protect: stop · trail · target" and does not
  say what is already set. List, with the rupee level and the option price for each: the hard stop, the target, the
  trailing stop (current level), the profit-lock level reached so far, the time stop (clock time), any index-level
  stop, and the 15:10 square-off. Say who set each one (the arm or Jarvis solo, or Boss by hand) and whether it rests at
  Zerodha as an order (live) or is watched by the app (paper). Example from 8 Oct 13:01: NIFTY13OCT2622550PE, opened by
  Jarvis solo, avg 261.33, LTP 318.95, +Rs 3,745 - none of its exits were visible.

## S. Self-improvement roadmap (owner go-ahead 2026-10-11: "implement all; what is not possible now goes here")

Being built in round 2 (agents): S1 variants drive the real arms (paper first), S2 a paper arm for approved forge ideas,
S3 the lab replays the real arm code, S4 one shared stats engine, S5 more history kept on the phone, S8 regime settings,
S9 Liquidity exit-only tuning, S10 learning from manual trades, S11 event-day exclusion, S12 portfolio risk across arms,
S13 daily change timeline + undo today, S14 learning freeze on drawdown, S15 monthly autopilot scorecard,
S16 emulator smoke flows in CI, S17 exportable crash/stall report, S18 battery/heat budget, S19 study pages,
S20 daily lesson card, S21 bot trade replay with commentary.

Not possible yet:
- [ ] S6. Restore a deep-history data source. Needs a fresh Dhan access token (the last one expired 2026-10-10) or another
      provider. Then: refill years of index/option 1-min history, re-run the forge and the variants' pre-checks on it.
- [ ] S7. Re-test order flow, delta, absorption and the trap guard on the phone's own recordings once there are
      2-4 weeks of NSE sessions (about 150-200 recorded trades per strategy family); re-tune TrapGuard on week 1.
- [ ] S16b. Smoke test on Boss's real phone (login, Home, Chart picker, a paper order): needs the device; CI covers the
      emulator only.
- [ ] S22. Merge the branch to main after Boss checks Monday's session (Order speed card, diagnostics incl. Workers,
      Brain, Health, Autopilot).

## G. Live data to GitHub (owner go-ahead 2026-10-12: option A)

- [x] G1. The phone uploads each day's live recording to GitHub on its own (design: `research/data/ARCHITECTURE.md`,
      tick zone and L8).
      - Built 13 Oct (phone side): `GitHubSync` + `GitHubSyncWorker` in the app, the rules in ira-core (`CloudSync`,
        `CloudRun`, `GitHubReleases`, `LiveSeal`), the card in Settings → Data & Harvest → GitHub sync, and
        `research/data/decrypt_live.py` for the PC. Each day's folder goes up whole (the trades file still encrypted
        inside), sealed with the backup passphrase (PBKDF2-SHA256 600k, AES-256-GCM chunks), as `live-YYYY-MM-DD.irlive`.
        A day whose folder grew (a late fill) goes again; a day only uploads after 15:45. Left: Boss's token
        (MAINTENANCE.md, "GitHub token").
      - Review fixes 13 Oct (Boss: the uploads go to the private data-only repository `mevishalsonawane-ai/IraAlgo`, never
        options-lab, so a stolen phone token can never push code CI signs or replace `latest-apk`):
        - The data repository is a constant and the card's "Data repository" field; options-lab is refused everywhere,
          and a token that can reach options-lab is refused when pasted.
        - The token's only PIN-less copy is the upload worker's, bound to the data repository. Restore from GitHub
          needs the PIN.
        - `backup.last` is set only after an upload worked. A 401/403 pauses the automatic runs, and Problems found
          says "GitHub token invalid".
        - A re-upload goes up as `<name>.new` first, then the old copy is deleted and the new one renamed.
        - The backup after a strategy or Pine change waits for 15:45 and charging, as the evening run does.
      - [ ] Nightly job: moved to the IraAlgo repository (13 Oct review). The files to commit there are staged in
        `tools/iraalgo-data-repo/` (the workflow, `verify_uploads.py`, its tests, a README and an empty
        `CATALOG.json`). Left: this session had no push access to IraAlgo; copy them to IraAlgo's default branch, then
        delete the staging folder here. The job:
        - loads its last `CATALOG.json` first and downloads only new or replaced files;
        - checks each file's structure without the passphrase (header, chunk layout, size, sha256, name and month) and
          each backup's header;
        - warns on gaps and an old backup;
        - turns the run red on a bad file (ntfy).

        There is no passphrase secret, and nothing is decrypted. Feeding the Data Brain from that catalogue is the
        brain's own step (P1/P6).
      - When: after 15:45, only on Wi-Fi and while charging. If it misses a day it retries the next evening.
      - Never on the order or tick path, and never on the main thread.
      - What: the day's file (per-second snapshots, per-minute order-flow aggregates, Boss's fills), encrypted on the
        phone before upload. It goes to a GitHub Release `live-YYYY-MM` as an asset, never as a git commit.
      - Key: one fine-grained GitHub token, for the IraAlgo data repository only (never options-lab), Contents read and
        write, 90-day expiry.
        - Boss pastes it into the app (Settings → Data & Harvest), never into chat. It is stored wrapped by the PIN, like
          the Zerodha API secret, and never appears in diagnostics or backups.
        - The app reminds Boss 7 days before the token expires.
      - Card: "Last upload: date · size · ✓/✗", "N days waiting". A failure goes to Problems found.
      - Nightly GitHub job (in IraAlgo): verifies each upload, adds it to IraAlgo's `CATALOG.json` and feeds the Data
        Brain.
      - Docs: MAINTENANCE.md gets the steps to create and renew the token, and tells Boss to turn on "protect main"
        (changes only after CI passes).
      - Until G1 ships: export the recorded data by hand every Friday (Data & Harvest → Export) and upload it to the
        release.
- [x] G2. Automatic phone backup to GitHub, so a new phone restores everything (owner, 2026-10-12). Built 13 Oct with
      G1's uploader; restore: Data & Harvest → GitHub sync → Restore from GitHub (MAINTENANCE.md, "Restore on a new
      phone": the app needs Zerodha linked before its settings open, so link first, then restore).
      - Upload: every evening, using the same uploader and token as G1, the phone uploads the existing encrypted backup
        (`data/Backup.kt`). It is sealed with Boss's backup password and goes to a GitHub Release `phone-backup` as an
        asset, never as a commit. The last 30 days are kept, plus one backup per month for older ones.
      - What it holds: settings, strategies, Pine scripts, the ORB arms and forward test, the paper account, alarms,
        protections, the journal, live trade records and what Jarvis has learned. It also uploads straight away when a
        strategy or Pine script is added or changed.
      - New phone:
        1. Install the APK and enter the GitHub token.
        2. Use "Restore from GitHub": pick a date, then enter the backup password.
        3. Log in to Zerodha.
      - Safety rules stay as today. Strategies and Pine scripts come back switched off, with their stop and profit lock.
        The PIN, Live/AI-live, tokens and the paper-only period are never in a backup, so they are set again on the new
        phone.
      - Card: "Last backup to GitHub: date ✓". A failure, or no backup for 2 days, goes to Problems found.

## F. Strategy Factory (owner go-ahead 2026-10-13)

- [ ] F1. Automatic strategy discovery from the Data Brain. Nothing goes live without Boss's PIN.
      - Ideas (nightly): candidate rules built from the Data Brain features (`research/brain`: VWAP, profile, levels,
        OI, IV, indicators, regime, expected range) and from research findings (FINDINGS.md, the HUNT docs). Every idea is
        logged in the experiment log, failures included.
      - Gates (`research/data/ARCHITECTURE.md` L6), all required:
        - walk-forward, purged;
        - the locked final-year holdout, read once per idea;
        - real charges and slippage;
        - Benjamini–Hochberg over every idea tried;
        - beats a random twin;
        - a model card.
        Expected pass rate: under 1%.
      - Paper: each idea that passes is armed automatically on PAPER only.
        - At most 5 factory strategies at a time.
        - Normal lot size, with every existing guard and the account stop rule (−₹40k drawdown / −₹13k a month).
        - Nightly GitHub job, with the result shipped to the phone in the knowledge or model pack (G1/P6).
      - Scorecard: after at least 20 paper trading days, a card per strategy: trades, P&L after charges, worst day, and
        live vs backtest. "Make it live?" needs Boss's PIN, every time. Bigger size also needs the PIN.
      - Retirement: a factory strategy is switched off automatically (this only lowers risk) when its paper or live
        results fall outside what its tests predicted, or on drift. Boss is notified.
      - Order: after the data lake merges and the first specialists exist (Data Brain roadmap P3). Liquidity BANKNIFTY
        stays frozen until 26 Oct and is not touched by the factory.

## I. Using the Data Brain (owner go-ahead 2026-10-13: "add all"). Priority: I3, I5, I14 first.

Rules for all of these:
- New behaviour runs in shadow or paper first.
- Anything that changes live orders needs Boss's PIN.
- Automatic changes may only lower risk.
- Every claim shown to Boss carries its sample size and source.

Save money on every trade:
- [ ] I1. Best time to enter: learn the spread and slippage for each minute from the recordings (Order Review and
      FillSlippage), then suggest or apply a wait ("09:15–09:20 spreads cost ₹20 a lot; wait to 09:21"). Paper first.
- [ ] I2. Smart order type: choose a limit at mid price or a market order from the live book (depth, imbalance, spread).
      Paper first; live needs the PIN.
- [ ] I3. **Charges check before each trade**: expected move (the R16 range model) against charges plus expected
      slippage. A trade whose costs take more than X% of the expected move is flagged, and skipped on paper. ORB's
      lesson (R14).

Protect capital:
- [ ] I4. Daily danger meter: each morning, rate today against the days that hurt the strategies most (gap, VIX, event,
      expiry, global cues), with a suggested size (normal, half, or paper only). Shown in the Good morning check.
- [ ] I5. **Smart stop-loss**: base the stop distance on the R16 expected 30–60 min range instead of fixed points.
      Backtest each arm first, then paper.
- [ ] I6. Event shield: how BankNifty and NIFTY behaved around RBI, Budget, US CPI/Fed and heavyweight results; warn,
      or pause the arms before the event. Builds on the existing event calendar and S11.

Find and improve edges:
- [ ] I7. Strategy Factory: see F1.
- [ ] I8. Improve the existing arms with the new fields (value area, OI build-up, VWAP, order flow). Pre-registered,
      gated. Liquidity BANKNIFTY only after its freeze ends on 26 Oct.
- [ ] I9. Better exits: test exits by VWAP band, the next support/resistance level, an order-flow turn, and the
      expected range. Measure how much of the profit each one keeps.
- [ ] I10. Expiry-day playbook: 6 years of expiry days (theta, straddle decay, max-pain behaviour, pin risk), giving a
      separate rulebook and checks for the Hero arm and expiry exits.

Help Boss decide faster:
- [ ] I11. "Days like today" morning brief: the nearest past days and what happened next, with how each arm did on
      them. Labelled as context, not a forecast (R16).
- [ ] I12. Live level map on the chart: support/resistance, POC/VAH/VAL, VWAP bands and max-OI strikes, each with
      "held N of M times".
- [ ] I13. Explain any candle: long-press a candle and Jarvis explains it from OI, IV, VIX, volume, order flow and news
      at that minute.

Learn from Boss's own trading:
- [ ] I14. **Personal mistakes report**: from Boss's own trades, the hours, setups and habits that lose (for example,
      trades after 14:00, or trades right after a loss), with numbers. Extends the existing mistakes list (Batch K).
- [ ] I15. Weekly review with Jarvis: a 2-minute spoken summary every Sunday covering what worked, what cost money,
      and one change to make.

Later (when profitable):
- [ ] I16. Stocks and MCX strategies, on the downloaded data, once the index arms are proven.
- [ ] I17. Package the tested signals as a product. Far future; check SEBI research-analyst and algo rules first.
