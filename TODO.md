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
- [ ] R3. ORB family deep study (owner, 13 Oct): ORB, ORB Fresh, ORB Sweep and Range Fade are PAUSED, not retired.
  They were switched off on 13 Oct after losing in every backtest year. Their code, settings and history stay, and
  Boss can re-arm them at any time. Once the full Dhan download is in the data lake, study them deeply and try to
  enhance them:
  - when they win and lose (by day type, VIX, gap, range width, expiry day, time of day, index);
  - filters that skip the losing day types; better exits (trail, profit lock, time stop) and strikes;
  - the R19c consistency rules on each of them.
  Follow the research gates: pre-register, walk-forward, locked holdout read once, real costs, BH across every variant
  tried, and log every idea in research/experiments/entries/, failures included. A version that passes the gates can
  come back on paper at once, logged and undoable in one tap, and labelled. A version that fails stays a note only.
- [ ] R20. ORB opening range that adapts (owner, 13 Oct: "if the price stays in range it never trades, resolve it").
  On 13 Oct BANKNIFTY's 09:15-10:00 range was 630 points wide and price stayed inside it until 14:35, after the 14:00
  last entry, so the ORB family took no trade. Research first, on branch claude/r20-adaptive-range: how often this
  happens; then pre-registered variants (15/30/45-minute range, an adaptive range by width vs ATR, last entry 14:30,
  the inside-range rule on wide days) with walk-forward, holdout once and BH. A variant that passes goes to paper only,
  logged and undoable; none goes to live by itself.
- [ ] R20b. ORB range from support and resistance (owner's idea, 13 Oct): previous-day high/low/pivots, liquidity
  zones and option OI walls (previous-day bhavcopy OI; intraday Dhan OI if in the lake) used as or to clip the range.
  Point-in-time only, with a random-level twin, and BH counted together with R20. Branch claude/r20b-sr-range.
- [ ] R20c. What happens after a support/resistance break (owner, 13 Oct: "if 2 candles are above resistance and 2
  candles are below support"). Event study first (follow-through vs fake-out at 15/30/60 min, 1 vs 2 vs 3 closes, by
  level, timeframe, time, VIX, expiry, index), then at most 4 pre-registered rules with random-level and random-time
  twins, BH counted with R20 and R20b. Branch claude/r20c-sr-break.
- [x] OF1. (done 14 Oct: research/HUNT_OF1_ORDERFLOW_INDIA.md; nothing to follow, no app change) Order-flow forensics
  (owner, 13 Oct: "reverse-engineer the order flow: what was placed, why, who made good profit"): NSE participant
  OI/volume 2012-2026, who profited by type, 36 follow-the-group signals (15 passed design, 0 of 15 held on the holdout),
  build-up and walls/magnets (no wall, no pin), R0-R3 option-buying rules (all fail). Open follow-ups:
  - run `research/hunt/of1/footprints.py` on the phone's tick-zone days once 2-4 weeks are uploaded (descriptive first,
    with S7);
  - DII cash flows need a source that does not block cloud machines (NSE's API returns 403); and
  - rerun `python3 -I research/hunt/of1/fetch.py --from <last day>` then build/analyze `summary` to refresh the
    who-profited tables (the holdout of OF1 is spent: any new test needs a new pre-registration and a new holdout).
- [ ] R4. Before/after report for Boss (owner, 13 Oct: "show me before and after results after everything is done").
  When R3, R19c and the 13–26 Oct paper run are finished, show one page with every arm and the whole portfolio on the
  same days, BEFORE (today's plain rules) and AFTER (every change that passed):
  - total P&L, P&L per day, % green days, win rate, average win and average loss;
  - worst day, worst trade, max drawdown, the biggest winners cut short;
  - backtest holdout and live paper shown separately, each with its sample size (trades and days);
  - changes that failed listed too, with what they would have done.

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

- [x] G3. Nightly diagnostics to GitHub, with a daily fix loop (owner, 2026-10-13).
      - Done 13 Oct (phone side): each evening run (and "Upload now") uploads ONE redacted file for its day,
        `diag-YYYY-MM-DD.json.gz` in IraAlgo's release `diag-YYYY-MM` (a release asset like G1/G2, not a commit; a re-run
        replaces the day's file). Built by ira-core's `DiagDay` (pure, tested), gathered by the app's `DiagDayHost` on the
        worker's thread. Per arm: armed/off/parked, every verdict with its reason kind (guard, lock, day lock, flow,
        liquidity, pre-trade, event, cap...), trades with times, premiums, points and percent, paper/live and source labels,
        counts labelled `today` and `all_days`; the day's orders by source (no ids); health (errors by kind, order speed,
        slippage in points, stream drops, workers, failed/skipped syncs); the change timeline with Boss's own switches.
        Privacy: Diag.redact, then DiagDay.scrub (CloudSync.redact, Secrets.redact, JWTs, keys, client ids, rupees and money
        figures, the holder's name/user id/login), proven by DiagDayTest's fake secrets. Not sealed (nothing personal in
        it; research has no passphrase). Card: "Diagnostics: last day uploaded". Reader: `research/data/read_diagnostics.py`;
        schema in MAINTENANCE.md ("Daily diagnostics"). Left: the daily fix loop (a Claude routine reading these files).
      - Phone (after G1/G2 merge, same uploader and token): every night AFTER THE MCX CLOSE (23:30 IST, 23:55 when US DST
        is on; from the app's MCX calendar; Wi-Fi + charging; Boss, 13 Oct) it commits
        that day's diagnostics to the private data repo `mevishalsonawane-ai/IraAlgo` at
        `diagnostics/YYYY/MM/YYYY-MM-DD.json`. It is the same content as Settings → Diagnostics → Copy diagnostics:
        Problems found, health, stalls/ANRs, crashes, worker runs, stream gaps, Order Review flaws, self-healing
        incidents, app version.
      - Add each arm's decisions today to Copy diagnostics too (Boss, 13 Oct: "why no other trades?"): per arm, the last N
        decisions with their reason (waiting_for_opening_range, no break, guard_refused, stale price, sits out...), and
        counts per reason. Today's trade-manager lines must say "today" vs "all days" plainly.
      - Redaction before upload: no keys or tokens (as Copy diagnostics today), and no account id, order ids, names or
        rupee amounts. Money appears only as signs and percentages. The file is plain JSON so Claude can read it; the
        repo stays private.
      - A test proves the redaction against a sample with every secret and money pattern. A failed upload shows in
        Problems found.
      - Daily fix loop (Claude routine, after G3 ships): each morning before 09:00 IST it reads the latest
        diagnostics, groups new or repeating problems, and drafts fixes on a branch. Every fix follows CLAUDE.md:
        tests, review, CI green. Then it merges and sends the APK, and reports to Boss in a few lines: "Yesterday:
        N problems, fixed X, need you: Y". Problems that need Boss (token, static IP, login) are only reported,
        never worked around.

- [x] G4. Upload time (Boss, 13 Oct: "after the commodities close"): G1 recordings and G2 backups also wait until after
      the MCX evening close (23:30/23:55 IST from the app's MCX calendar), not 15:45, so the MCX session is included and
      nothing uploads while any market the app trades is open. On days with no MCX session (MCX holiday), 15:45 after the
      NSE close is fine. The manual "Upload now" is unchanged.
      - Done 13 Oct: `CloudSync.eveningFrom` picks the gate from today's MCX window (its close, never before 15:45; 15:45
        when MCX is shut; 23:55 when the calendar cannot be read), and `plan`, `eveningDue` and `backupDelayMin` take it.
        The app reads the MCX calendar on the worker's thread (`GitHubSync.eveningFrom`). A missed evening runs the next
        morning until 08:00 (13 Oct review: an hour before the open) and is kept as its own evening (`eveningDueDay`), so a run that ends after midnight neither
        skips the next day nor runs twice. The 09:00-15:45 rule for a day's big file is unchanged; so is "Upload now".
        The run reads the clock again before each day's file and before the backup (`CloudSync.sendNow`): an automatic
        run sends nothing new after 08:00, a tap starts no day's file in a trading day's 09:00-15:45, and a backup job held
        past midnight still runs until 08:00.

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

- [x] I0. **India Data Brain v1** (14 Oct, research/BRAIN_V1.md): cube + features + labels for NIFTY, BANKNIFTY,
      FINNIFTY, MIDCPNIFTY, SENSEX, BANKEX from the Drive lake + release; first models. The size of the next 30/60 min
      is predictable on all six (design); day type and regime at 09:45 are not.
      - [ ] Read the locked holdout (from 27 Apr 2026) once, for the 12 range tests, in its own step.
      - [ ] When the lake is complete: `python -m research.brain.refresh --source lake --upload --pack`.
      - [ ] v2 (new pre-registration): range60 from 09:45 (B-last fallback), previous-session ER in the linear model,
        rest-of-day labels that skip a missing 15:29 / t+1 bar (BRAIN_V1.md, honest note 4).

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
- [x] I3. **Charges check before each trade**: expected move (the R16 range model) against charges plus expected
      slippage. A trade whose costs take more than X% of the expected move is flagged, and skipped on paper. ORB's
      lesson (R14).
      - 13 Oct (R17, research/HUNT_R17_PRETRADE.md): costs are a median 3-6% of the expected 30-minute move on every
        arm. The 10% line (design pick) skipped 3 of 499 holdout trades and FAILED (p 0.31, q 0.46).
      - Built as SHADOW only: a note at each paper entry, in Order Review and the arm's day; nothing is skipped.

Protect capital:
- [x] I4. Daily danger meter: each morning, rate today against the days that hurt the strategies most (gap, VIX, event,
      expiry, global cues), with a suggested size (normal, half, or paper only). Shown in the Good morning check.
      - 13 Oct (R17): FAILED walk-forward. On 122 holdout days the careful days lost less than normal ones (p 0.70).
        No global cues are in the data.
      - Shown as advice with its sample size (347 sessions). The half-size switch (paper arms only) is off; it changes
        only Hero, since a 1-lot arm cannot be halved.
- [x] I5. **Smart stop-loss**: base the stop distance on the R16 expected 30–60 min range instead of fixed points.
      Backtest each arm first, then paper.
      - 13 Oct (R17): k x expected range x delta, k 0.5-1.5, two forms, 6 arms. No arm passed: only VIX divergence NIFTY
        qualified in design, and it failed the holdout (q 0.46). Fixed stops stay.
      - Shadow line per paper trade: "smart stop would be N points; fixed stop is M" (k = 1).
- [x] I6. (done 13 Oct: research/EVENT_SHIELD.md - BANKNIFTY moved 1.48x a normal day on RBI/Budget days, 15 sessions, holdout confirmed; US CPI/Fed: bigger gaps, same range. Home card and the 09:00 / 15:45 reports warn the evening before and that morning; Bot settings → "Pause paper arms on event days": OFF by default, Shadow first, paper only, never Liquidity BANKNIFTY) Event shield: how BankNifty and NIFTY behaved around RBI, Budget, US CPI/Fed and heavyweight results; warn,
      or pause the arms before the event. Builds on the existing event calendar and S11.

Find and improve edges:
- [ ] I7. Strategy Factory: see F1.
- [ ] I8. Improve the existing arms with the new fields (value area, OI build-up, VWAP, order flow). Pre-registered,
      gated. Liquidity BANKNIFTY only after its freeze ends on 26 Oct.
- [ ] I9. Better exits: test exits by VWAP band, the next support/resistance level, an order-flow turn, and the
      expected range. Measure how much of the profit each one keeps.
      (13 Oct, R18 `research/HUNT_R18_EXITS.md`: on the release data none of 5 exit overlays beat today's exits on any
      arm, 0 of 60 tests, best BH q 0.48; no shadow line added; Liquidity: keep its exits after 26 Oct. Still open:
      re-run on the 6-year lake with `R18_SOURCE=lake` (a new pre-registration, "next S/R" pooled, judged on the fresh
      lake holdout from 27 Apr 2026: the release holdout was R16's, reused), and order-flow
      exits once 60 days are recorded.)
- [x] I10. (done 13 Oct on the release's ~1 year of expiries: research/EXPIRY_PLAYBOOK.md, in the knowledge pack; expiry-day card on Home. Re-run on the 6-year lake: IRAALGO_CONTEXT_SOURCE=lake) Expiry-day playbook: 6 years of expiry days (theta, straddle decay, max-pain behaviour, pin risk), giving a
      separate rulebook and checks for the Hero arm and expiry exits.

Help Boss decide faster:
- [x] I11. (done 13 Oct: research/DAYS_LIKE_TODAY.md - no forecast skill, as R16; Home card and Jarvis's morning brief from 09:21, labelled context, with sample sizes and each arm on those days) "Days like today" morning brief: the nearest past days and what happened next, with how each arm did on
      them. Labelled as context, not a forecast (R16).
- [x] I12. (done 13 Oct: chart indicator "Ira: level map" (Market context group), counted point in time on the phone; research/LEVEL_MAP.md - no level type beat random levels, 0 of 24; POC/VAH/VAL stay the order-flow indicator) Live level map on the chart: support/resistance, POC/VAH/VAL, VWAP bands and max-OI strikes, each with
      "held N of M times".
- [x] I13. (done 13 Oct: chart long-press menu → "Explain this candle (Jarvis)"; recordings, VIX, flow and news, each missing piece said) Explain any candle: long-press a candle and Jarvis explains it from OI, IV, VIX, volume, order flow and news
      at that minute.

Learn from Boss's own trading:
- [ ] I14. **Personal mistakes report**: from Boss's own trades, the hours, setups and habits that lose (for example,
      trades after 14:00, or trades right after a loss), with numbers. Extends the existing mistakes list (Batch K).
- [ ] I15. Weekly review with Jarvis: a 2-minute spoken summary every Sunday covering what worked, what cost money,
      and one change to make.

Later (when profitable):
- [ ] I16. Stocks and MCX strategies, on the downloaded data, once the index arms are proven.
- [ ] I17. Package the tested signals as a product. Far future; check SEBI research-analyst and algo rules first.

## C. Crypto (owner, 13 Oct 2026: "import crypto data", "the same brain for crypto", "update all the agents and Jarvis
##    with the knowledge of crypto"). Knowledge only: nothing here trades crypto.

- [ ] C1. Crypto data lake on Google Drive from Binance's bulk archive (BTC/ETH full history, then SOL/BNB/XRP; spot and
      USD-M futures 1m, funding, mark/index, OI, long/short; catalogue in research/data/crypto/). Branch claude/crypto-lake.
- [ ] C2. Crypto Data Brain: the Indian brain's layers reused (cube, point-in-time features, labels/splits, studies,
      knowledge pack with a crypto tag). Branch claude/crypto-brain.
- [ ] C4. Boss's crypto rule (13 Oct): ALWAYS a stop-loss and a profit lock on every crypto trade. Re-check the RC1
      small-account study (branch claude/crypto-small-account): every active idea must be judged WITH a stop and a
      profit lock (pre-registered variants: fixed % / ATR stop; step lock and trailing lock), never without; if RC1
      finished without them, run that as RC1b before any verdict reaches Boss.
- [ ] C5. Crypto options study (Boss, 13 Oct: "is there anything like options in crypto"; no holding, take profit and
      sell): BTC/ETH options on Delta Exchange India (INR-settled, daily/weekly/monthly expiries) with Deribit's history
      for depth; option-buying arms like the Indian ones, always with a stop-loss and a profit lock. Branch
      claude/crypto-options.
      CoinSwitch (Boss's exchange) also lists BTC/ETH options in its app (INR and USDT, option chain, Greeks); its
      options API is private beta, on request. Once Boss's read-only CoinSwitch key is in the environment (and options
      API access is granted), compare CoinSwitch's option contracts, fees and spreads with Delta India's and re-run any
      passing RC2 rule on CoinSwitch's costs.
- [ ] C3. Every agent and Jarvis know crypto (after C2 has findings). App work, one session:
      - the shared knowledge pack carries the crypto facts and findings (tagged), which every agent already reads;
      - Jarvis answers crypto questions (what is known, what failed, latest BTC/ETH context), with numbers checked like
        the Indian answers, and never mixes crypto with the Indian arms' records;
      - each agent (trade manager, order review, study, strategy lab, autopilot) uses crypto only where it applies:
        explain and rank, never trade, size or arm anything; no crypto broker or orders;
      - a read-only crypto context card (BTC/ETH price, funding, basis, DVOL) only if a live source is reachable from
        the phone (Binance's API is geo-blocked from the cloud; check from India);
      - tests for each, one WhatsNew entry, the usual review, CI and one APK.

## W. Data watchdog (owner, 13–14 Oct)

- W1 India brain v1 started 14 Oct on the data available then (branch claude/india-brain-v1). When the Dhan lake
  completes, the watchdog runs that branch's REFRESH command (not a new build). The crypto brain is on claude/crypto-brain.
  The Strategy Factory (F1) starts after the refresh.
- F1 India started 14 Oct 06:03 UTC on Boss's request (branch claude/f1-india-factory: EMA, VWAP, OI, PCR, IV, GEX...),
  with sister studies R3 (ORB family), R26 (OI wall), R27 (Night exit), R28 (gamma exposure), R29 (multi-EMA patterns).
- F1 crypto started 14 Oct 06:35 UTC: the crypto options lake finished (Deribit trades, Delta India 1-minute candles
  Jan 2024 to now, DVOL; branch claude/crypto-options-lake, merged). One session refreshes the crypto brain on the full
  crypto lake, then runs the crypto part of the factory (branch claude/f1-crypto-factory). Still filling: Dhan stock options
  (paced under Dhan's rate limit) and 13-14 Oct index data, on the renewed token (claude/data-lake-gdrive).

## C. Crypto Data Brain (owner, 2026-10-13: "the same model ... for crypto")
Built 13 Oct: `research/brain/crypto` (README there), study `research/CRYPTO_C1_PREDICTABILITY.md`. Research only;
nothing trades crypto.
- [x] C1. Market-aware core, L2 clean, L3 24x7 cube, 62 point-in-time features, labels, locked holdout (1 Apr 2026),
      costs, guard; the C1 predictability study on BTC/ETH design data (size predictable, direction not).
- [x] C1b. Boss's window (13 Oct: "only from 12am to 9am"): 00:00-09:00 IST (`config.WINDOW`); study C2
      `research/CRYPTO_C2_WINDOW.md` (range predictable; no direction edge after costs).
- [ ] C2. When the Drive crypto lake is complete: `fetch drive` month by month, `cube build`, features, labels; add
      SOL/BNB/XRP; if the lake's layout differs from the archive's file names, one rule in `crypto/adapters.py`.
- [ ] C3. One pre-registered holdout read (from 1 Apr 2026) for the expected-range forecast (C1 A1/A2, C2 W1/W3), the
      weekend size effect and C2 W4 on ETH (the whole-window reversal, margin inside the cost uncertainty); nothing
      else from C1 or C2 earns a holdout read.
- [ ] C4. Wire in what exists but is not in the cube: ETH DVOL (no file yet), Deribit option surfaces, on-chain and
      ETF flows (`crypto/data_more`), liquidation prints if a source appears.
