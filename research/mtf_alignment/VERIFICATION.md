# Verification — 14 November 2026

- Candle study plus shared option strategy tests: 15 passed. Includes causal
  prefix tests, independent manual closed-bar oracle, missing bars, dojis,
  timestamp validation, callback restoration, costs, fixed-strike holding,
  cash/risk limits and complete report generation.
- Existing research brain suite: 209 passed, one existing skip.
- Feature catalog: FEATURES.md up to date.
- Experiment registry: all 4,635 entries valid, including both rejected rules.
- All four saved executed ledgers independently reconcile net=gross-fees-friction,
  net-positive wins and account ending cash. Entry times and non-expiry entries
  checked. No source raw bars or private recording metadata staged.
- Self-review and staged whitespace/credential-pattern checks completed.
  Secret scan is scoped to changed text; no claim of auditing all historical
  binary data or every repository secret.

Each rule has one full normal-friction replay, one independent double-friction
replay and six chronological-window diagnostics. Source account engine is
unchanged. Window restarts must not be summed into a full account result.

Exact 5m+1m+10s: blocked, not tested, win rate/net remain null. Encrypted
recordings cannot be assumed to contain complete usable index and option ticks
until decrypted and validated. No synthetic ten-second candles, no claim of
fresh test data, no production or workflow change, no real orders.

The parent branch's synthetic R18 CI passed; its unrelated external BANKNIFTY
training/data workflow failed. Current-commit CI must be assessed separately;
local test success is not a claim that all repository CI is green.
