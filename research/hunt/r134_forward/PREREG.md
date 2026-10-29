# R134 pre-registration: the forward test of CISD + R123's network (frozen 29 Oct 2026)

Written 29 Oct 2026, after R133 and before any session after 19 Oct 2026 was seen. Research only: nothing in the app
changes and nothing trades. These are simulated trades on new data, logged, so the R133 lead can be judged on data no
study has seen. Boss, 29 Oct: *"yes set up the forward test"*.

## 1. What is frozen (nothing may change after this commit)

- **Rules:** R133's six design passers, exactly as R131 / R132 / R133 define them (code at this commit):
  - **PRIMARY: S3-V2-E0**: CISD (ICT's reading) with R131's exits and R123's network EV filter;
  - secondary, reported only: S3-V2-E3, S7-V2-E4, S3-V1-E3, S9-W-E4, S3-W-E1.
- **The network:** R123's N1 as it scored R123's holdout (weights `net_N1_holdout.pt`, scaling refitted from R123's
  design rows). `nnlive.check()` reproduces R123's saved holdout predictions (46,580 rows, max P(win) difference
  2e-7: `results/nn_check.json`). It is never retrained during the test.
- **The pipeline:** `forward.py`. The replay of 1 Apr - 19 Oct 2026 reproduces R133's holdout-period numbers for all
  six rules to the paisa (`results/replay_log.json`).
- **Signal source:** the index spot (R131's entries). The network's option-chain inputs are context only. ATM option,
  1 lot, dated charges, liquidity-aware slippage.

## 2. The period

From **20 Oct 2026** (the first session after the lake's end) until the primary rule has **50 trades**. At R133's
holdout rate (32 trades in 137 sessions) that is about 210 sessions, roughly **ten months (around Aug-Sep 2027)**.

## 3. The decision (once, at 50 primary trades; no earlier decision)

The primary rule **passes** only if, over its first 50 forward trades:
- the average net per trade is > 0 at **2x slippage**;
- the max drawdown is above -Rs 40,000 on 1 lot;
- no calendar month is below -Rs 13,000.

The weekly updates are status only: no rule is dropped, changed or added on what they show. If it passes, it becomes
a candidate for a **paper** arm by Boss's choice (CLAUDE.md: paper only, logged and undoable; never live by itself).
If it fails, CISD + network is rejected.

## 4. Data

- Each run syncs the Drive lake (`gdrive:IraAlgo-data-lake`, read only), then tops up any sessions after the lake's
  last day from the Dhan API (`research/data/lake_fetch.py --only-missing --since`, `lake_build.py`), into the local
  copy only. The shared Drive lake is never written.
- Dhan's rolling option data covers **expired** contracts, so a week's option minutes arrive after that expiry. Each
  run recomputes the whole forward period, so late data is picked up and nothing is decided on partial weeks.
- A Dhan access token that is expired stops the top-up. The run then says so and logs only what the lake has.

## 5. Outputs

- `results/forward_log.json`: the sessions covered, each rule's trades and summary, and the primary's decision status.
- `research/HUNT_R134_FORWARD.md`: how to read it. TODO.md has the reminder.
