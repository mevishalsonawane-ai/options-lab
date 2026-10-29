# R134: the forward test of CISD + R123's network (running from 20 Oct 2026)

29 Oct 2026. Research only: these are simulated trades on new data, logged; nothing trades and nothing in the app
changes. Boss: *"yes set up the forward test"*.

## What it is

R133's best lead was CISD (ICT's reading) with R131's exits, taking a trade only when R123's network expects that
option to pay. It passed every design gate, but the data left to check it (Apr - Oct 2026) had already been seen and
gave only 32 trades. The only fair test is on sessions **no study has seen**: from 20 Oct 2026 on.

- **Primary rule:** S3-V2-E0. Five other R133 passers are logged next to it for information only.
- **Decision, once, at 50 primary trades:** pass only if the average net per trade is positive at 2x slippage, the
  drawdown stays above -Rs 40k, and no month is below -Rs 13k (`hunt/r134_forward/PREREG.md`). At R133's rate that is
  about ten months: **around Aug-Sep 2027.**
- **A pass means a paper arm becomes possible**, by Boss's choice. It never goes live by itself.

## How it runs

- **Weekly**, Saturday 10:17 IST, as a scheduled cloud task. Each run:
  1. syncs the Drive lake;
  2. tops up new sessions from Dhan into a local copy (never written back to Drive);
  3. scores each session with R123's frozen network;
  4. updates `research/hunt/r134_forward/results/forward_log.json` on branch `claude/r131-ict-ten`.
- **Every run recomputes the whole forward period.** Option minutes arrive from Dhan only after each weekly expiry, so
  late data is picked up.

## Checks done before starting

- **The rebuilt network matches R123's.** It reproduces R123's saved holdout predictions: 46,580 rows, largest P(win)
  difference 0.0000002 (`results/nn_check.json`).
- **The pipeline reproduces R133.** A replay of 1 Apr - 19 Oct 2026 gives R133's holdout-period numbers for all six
  rules exactly (`results/replay_log.json`). For the primary: 32 trades, +Rs 302 each.
- **Tests:** `test_r134.py` covers the frozen rules, the decision rule and the filter.

## What Boss needs to do

**Refresh the Dhan access token** (`DHAN_ACCESS_TOKEN`) in the cloud environment's settings. On 29 Oct it was expired
(Dhan: "access token is invalid or expired"). Until then the weekly run can only log what the Drive lake has, and that
ends on 19 Oct, so it will report "no new sessions" each week.
