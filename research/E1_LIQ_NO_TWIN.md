# E1: Liquidity no-twin rule (paper risk control, 20 Oct 2026)

Added in the 20 Oct enhancement round 1 (app). Experiment entry: `research/experiments/entries/e1-liq-no-twin.yaml`.

- On 19 Oct, 09:30, both Liquidity books (15m and 5m) held the same BANKNIFTY put and were stopped together.
- Rule: on paper, a Liquidity entry on an option another Liquidity book already holds becomes a would-be trade (kept with its
  signal bar and price, never bought), counted apart so the plain-rules record for the 26 Oct decision stays separable.
  The two books still trade side by side on different options. Live: a twin is only logged (shadow).
- On for paper, logged in the Autopilot change timeline, undoable in one tap (or the switch on the Strategy lab card).
- A risk control, not an edge claim. Not yet tested on history: the planned test replays both books 2020-2026 with and
  without twins (real charges and slippage, walk-forward, holdout read once).
