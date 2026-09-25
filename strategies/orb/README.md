# ORB handoff

Everything another agent needs to build the ORB and ORB Fresh arms into this app,
taken from the desktop app (`D:\New Trading app`, branch `custom`) on 2026-09-26.

| File | What |
|---|---|
| `ORB_STRATEGY.md` | The rules, execution, exits, restart state, operator controls, pass rule and known traps. Start here. |
| `orb_config.json` | Every strategy parameter, both arms. |
| `risk_config.json` | The account guard limits: live defaults, and what the paper test runs with now. |
| `execution_config.json` | Charges, slippage, fill rules, data sources, phone timing. |
| `reference/` | Source copies: desktop Python arm (`orb_arm.py`), evening replay (`orb_shadow.py`), account guard (`account_guard.py`), charges and slippage, the pre-registration, and the phone Kotlin port (`OrbRules.kt`, `Replay.kt`, `ArmRunner.kt`, `Costs.kt`, `AccountGuard.kt`) with its parity vector generator (`make_vectors.py`). Reference only: not compiled, not imported. |

## Why this is needed

This app's "Import from desktop" brings ORB and ORB Fresh in as time-scheduled
basket strategies (`android/app/.../data/Strategies.kt`). They have **no opening
range and no breakout check**: armed, they would buy at the start time whatever the
market did. Do not arm them. `TODO.md` items A1-A4 track the port.

## What to build (TODO.md A1-A4)

1. **A1** ORB rules and the arm runner in `android/engine/` (plain Kotlin, no Android),
   from `reference/OrbRules.kt`, `Replay.kt`, `ArmRunner.kt`. Keep the pure rules
   separate from I/O, as the reference does.
2. **A2** The account guard, from `reference/AccountGuard.kt` (a line-for-line port of
   `account_guard.py`), applied to every entry across all strategies.
3. **A3** Account-level Stop for today / Start again, and clearing the kill switch.
4. **A4** Replace or block the imported time-scheduled ORB strategies.

Tests: regenerate vectors with `reference/make_vectors.py` from the desktop repo
(it imports the desktop Python, so run it there) and assert the Kotlin rules, the
day replay and the guard equal the Python, as the desktop phone port does.

## Where it shows on the phone

Home, second card ("Strategies", `ui/screens/StrategyArmCard.kt`): one row per arm
with its arm switch and state (waiting for range / range H-L / waiting for breakout /
position with live P&L), plus an account-level "Stop arms today". Tapping a row opens
Trade -> Strategies for the day's detail. Positions and orders also appear in Home's
"Live orders" card.

## Rules that are not negotiable

- Paper (sandbox) pipe, decided in the arm, never from the app's live/paper switch.
- Exactly the pre-registered rule; any change starts a new forward test.
- Revert the paper-only risk raises in `risk_config.json` before anything goes live.
- No API keys, secrets or tokens in this folder.
