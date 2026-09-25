# IraAlgo TODO

Gap analysis of D:\New Trading app (OpenAlgo fork, branch `custom`) against
D:\IraAlgo (options-lab @ bba1def), 2026-09-25. Nothing below is implemented
yet; each item waits for an explicit go-ahead.

Source paths prefixed `NTA:` are in D:\New Trading app.

## Decision needed first

- [ ] D1. Which app runs the ORB forward test from now on: laptop (NTA) or phone (IraAlgo)?
      If IraAlgo: do A1-A4, then paper-trade both side by side for a few days before turning the laptop arms off.

## A. Port missing core functionality into IraAlgo (must precede arming anything)

- [ ] A1. Port real ORB / ORB Fresh logic (09:15-10:00 range, breakout entry 10:05-14:25,
      one ATM strike all day, +40/-40 option points, resting stop, 15:10 exit, Rs 40 min premium, 1 lot).
      Source: `NTA:mobile/native/app/app/src/main/java/com/iraalgo/app/rules/OrbRules.kt`,
      `rules/Replay.kt`, `arms/ArmRunner.kt` + their parity tests; Python origin `NTA:services/ai_signals/orb_arm.py`.
- [x] A2. (done: engine `risk/AccountGuard.kt` + tests, app `data/Guard.kt`, More -> Bot -> Bot settings) Port account-wide guard: kill switch, daily loss, drawdown (vs capital and vs persisted peak),
      max concurrent positions, max trades/day, order value, symbol exposure, entry cutoff, naked short.
      Exits bypass all but the kill switch. Source: `NTA:.../rules/AccountGuard.kt` + `AccountGuardParityTest`
      (222 vectors from `NTA:mobile/native/make_vectors.py`); Python origin `NTA:services/risk/account_guard.py`.
- [x] A3. (done: one bot button on Home's Strategies card - Stop for today / Start / Clear kill switch, each confirmed) Account-level Stop/Start arms for the day + kill-switch clear (confirm dialogs).
      Source: `NTA:blueprints/ai_signals_activity.py`, `NTA:frontend/src/components/trading/ArmsControl.tsx`.
- [ ] A4. (interim block shipped: any strategy named ORB cannot be armed or started and shows 'BLOCKED - needs breakout rules'; replace with the real logic once A1 lands) Block or replace the imported ORB / ORB Fresh JSON strategies (`android/.../data/Strategies.kt:187-216`):
      they currently run as time-scheduled baskets with NO breakout check. DO NOT ARM until A1 lands.
- [x] A5. (done: `data/ExpirySquareOff.kt` from the market watch, paper + live, keeps the Expiry Put to settlement by default; toggles in Bot settings) Expiry-day square-off at 15:05 for all products (NTA: `services/expiry_squareoff.py`);
      IraAlgo currently settles at expiry / squares MIS at 15:15.
- [x] A6. (done: one set of limits in Bot settings; the Zerodha checks use them, the separate Zerodha caps are gone) Decide order limits: IraAlgo 4 orders/day + Rs 5L/order vs NTA guard limits.
- [x] A7. (skipped by the owner's decision, 2026-09-25) Minute market snapshots (BANKNIFTY/NIFTY/SENSEX/VIX + near-ATM options, 09:15-15:30).
      Source: `NTA:services/market_data_collector.py` -> `db/market_snapshots.duckdb`.
- [ ] A8. Evening ORB replay (15:35-15:40) beside paper results. Source: `NTA:services/ai_signals/orb_shadow.py`
      (off on NTA; native app Task 8A never started).
- [ ] A9. Verify sandbox charges/slippage parity with NTA (`NTA:sandbox/charges.py`, `sandbox/slippage.py`,
      stop slippage 10 bps, spread fallback 5 bps).
- [ ] A10. Heartbeat / dead-man alert when the engine stops during market hours
      (incomplete on NTA too: `NTA:services/heartbeat_service.py`).
- [ ] A11. Optional: Telegram alerts (NTA has them; IraAlgo uses phone notifications only).
- [ ] A12. Optional: pre-market routine (symbol refresh, daily report). AI Signals: skip unless revived (research refused it).

## B. IraAlgo housekeeping

- [ ] B1. Re-point `tools/harvest_nightly.ps1` (hard-coded to `C:\Users\mevis\Downloads\files\options-lab`, Python310)
      and re-register in Task Scheduler; harvested bars stop at 2026-09-10 (missed sessions are lost for good).
- [ ] B2. Release signing: run `android/tools/make-release-key.sh`, add the 4 GitHub secrets
      (otherwise every update needs uninstall, which wipes the vault).
- [ ] B3. Static IP for live orders (SEBI): VPS + `android/tools/wg-relay-setup.sh`.
- [ ] B4. Research milestones in `docs/design.md`: M1 in progress, M4-M9 open, M9 forward holdout ~Dec 2026;
      missing tests `test_theta_units`, `test_long_short_mirror`, `test_exits_fire`, `test_no_engine_import`; pricer/IV module;
      confirm M3 Kaggle splice reconciliation.
- [ ] B5. Hedged variant undecided (`docs/hedged-variant.md`: wing helped 0/170).
- [ ] B6. Docs drift: `android/README.md` tab names; root README test count (206 -> ~332).
- [ ] B7. App module has no unit/UI tests (engine only).
- [ ] B8. Decide fate of the untracked `options_lab/data/banknifty_expiry_cache/` and modified bars in the old
      `D:\files\options-lab` clone (not present in D:\IraAlgo).

## C. D:\New Trading app (still running the ORB paper forward test)

- [ ] C1. Revert before live, `.env`: ACCOUNT_MAX_DAILY_LOSS 6000->2000, ACCOUNT_MAX_TRADES_TODAY 60->10,
      ACCOUNT_MAX_DRAWDOWN_PCT 30->10, re-base `db/account_peak.json`.
- [ ] C2. Revert before live, `services/ai_signals/config.py`: MIN_CONFIDENCE_FOR_SIGNAL 0.65->0.55,
      MIN_RISK_REWARD_RATIO 1.2->1.5, RISK_PER_TRADE_PCT 2.0->0.7.
- [ ] C3. `ACCOUNT_STARTING_CAPITAL=0` leaves the capital-based drawdown leg inert.
- [ ] C4. Heartbeat not written by the ORB arms or the collector.
- [ ] C5. ORB forward test needs ~57 trades before it means anything.
- [ ] C6. Commit the uncommitted native-app work (Engine, AccountGuard, SqliteStore, service, UI, fake build,
      PaperAccount `checkStop(orderId)` fix, make_vectors.py, test_fake_kite.py). Exclude `tools/__pycache__/`.
      Never share that APK: `EngineConfig.kt` embeds the Kite secret.
- [ ] C7. Native plan (`docs/superpowers/plans/2026-09-25-native-android-app.md`):
      Task 8A not started; battery-exemption request missing (Task 9);
      Task 10 E2E timed out waiting for ORB signals (suspect: EngineService stops itself with no alarm when the first
      fake-clock read is outside 09:10-15:30; `KiteClient.options()` caches on real date, not engine clock);
      Task 10 steps 3-4 not done; tick the plan checkboxes.
- [ ] C8. Orphaned code: `services/timestone/`, `strategies/core/`, `strategies/directional/`,
      `strategies/execution/order_router.py:486` TODO. Wire in or delete.
- [ ] C9. `upgrade/seed_ai_signals_model.py` not registered in `upgrade/migrate_all.py`.
- [ ] C10. Junk at repo root: file named `--force`, empty `cit.json`, debug `.txt` files, loose scratch scripts.
- [ ] C11. All `docs/superpowers/plans/*` checkboxes unticked though work shipped; ADR-0005 still "Draft".
- [ ] C12. `git pull` of branch `custom` from FinalProductTradingApp hung (likely credential prompt) and was stopped.
