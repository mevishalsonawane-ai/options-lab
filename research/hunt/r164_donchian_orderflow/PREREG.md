# R164 pre-registration: does watching buyers and sellers raise the Donchian arms' win rate?

Written 12 Nov 2026, before any outcome number was computed or any recording was opened. It is committed before the study
code exists, and its sha256 goes into every result file.

**Numbering:** R160 is on main and `claude/r161-night-frozen-index` is on the remote. R162 and R163 are siblings running
today. This study is **R164**, on branch `claude/r164-donchian-orderflow`.

## 0. Boss's question (12 Nov 2026)

"I want you to also check continuously the buyers and sellers while trading, may be that will help."

The arms are R72 Breadth Donchian and R51 Donchian 20 (+Supertrend). Both are live through R151's pickers. Today's exits
are a −15% stop, the ladder/locks and a 30-minute time stop.

## 1. What was looked at before writing this (disclosed; no outcome)

- **Earlier order-flow work.** R7, R8, R10, OF1, R61b, R77 and R125, plus the flow-oi family (131 logged tests before
  today). They found that order flow explains the price in the same seconds but forecasts very little beyond a minute or
  two. None of the order-flow rules tried so far paid an option buyer after costs. TODO S7 asks for a re-test on the
  phone's own recordings once there are 2-4 weeks of NSE sessions (about 150-200 recorded trades per strategy family).
- **What the phone records** (code read: `TickZone.kt`, `TickZoneRecorder.kt`, `OrderFlowLive.kt`, `FlowGate.kt`,
  `FlowShadow.kt`, `LiveSeal.kt`, `DiagDay.kt`):
  - The tick zone records **one row a second** for **BANKNIFTY and NIFTY near-month futures**, the **ATM ±3 calls and puts
    of each of those two indices' nearest expiry**, and India VIX (about 31 instruments).
    - Each row: LTP, cumulative volume, ATP, OI, total buy/sell quantity, and 5 bid and 5 ask levels (price, qty, orders).
    - Zerodha's full-mode tick has **no aggressor flag**. "Buyers vs sellers" can only be estimated with the tick rule
      (price up = buy). The app's minute file does exactly that.
  - FINNIFTY, MIDCPNIFTY and SENSEX are **not** in the tick zone. Their Donchian trades have no depth or flow record.
  - Raw seconds are kept 10 days on the phone. Each evening the day's folder (`ticks.jsonl.gz`, `orderflow_1m.jsonl.gz`,
    `market.jsonl.gz`) is zipped and **sealed with Boss's backup passphrase** (AES-256-GCM) as `live-YYYY-MM-DD.irlive`
    in the private IraAlgo repo. This study must not have the passphrase and will not try to open the files any other
    way.
  - The daily diagnostics (`diag-YYYY-MM-DD.json.gz`) are not sealed. They hold the arms' trades (entry/exit time,
    points, %) but no depth.
  - `FlowGate`'s shadow log (the order flow at each strategy signal) **does not cover R51 or R72**. They are not in
    `FlowShadow.STRATEGIES`.
- **Access in this session:** attaching the private IraAlgo repository was refused. No recording, diagnostic or catalogue
  has been seen.

## 2. Population

- Every R72 and R51 entry (paper and live), each arm on its own, whose index is **NIFTY or BANKNIFTY** and whose bought
  option was in that day's tick-zone watch-list at the signal second.
- Trades come from the diagnostics (`arms[].trades`, sources `r72_breadth_donchian` and `r51_donchian20`). If those are
  missing for a day, the trades are rebuilt from the recorded index minutes with the arms' rules (`DonchianArm`,
  `BreadthDonchianArm`).
  - R72's 4-of-5 breadth needs all five indices' 5-minute bars. `market.jsonl.gz` has the minute rows of all of them.
- Picker labels (R151) are carried. The main test is on every break the arm would have taken without the picker, because
  the picker went live only on 6 Nov. A second view is restricted to picker-taken breaks.
- Only seconds the tick file recorded count. A trade with more than 20% of its seconds in gap runs (`"t":"G"`), or none in
  the 60 s before the signal, is dropped and counted.

## 3. Entry filters (fixed now; each is SKIP-only, read at the signal second t0)

`dir` = +1 for a call bought, −1 for a put. Every read uses rows with `s ≤ t0` only (the look-ahead guard). "Fut" = that
index's near future. The tick rule is the app's: up = buy, down = sell, unchanged = previous side.

| id | read | skip when |
|---|---|---|
| E1 | bought option's top-5 depth imbalance (Σbid qty − Σask qty)/(Σbid + Σask), mean over (t0−60 s, t0] | ≤ −0.20 |
| E2 | fut top-5 depth imbalance × dir, mean over (t0−60 s, t0] | ≤ −0.20 |
| E3 | fut tick-rule delta / volume × dir over (t0−60 s, t0] | ≤ −0.20 |
| E4 | fut tick-rule delta / volume × dir over (t0−300 s, t0] | ≤ −0.10 |
| E5 | fut large prints (a second's volume > the day's p99 of earlier seconds, ≥ 200 seconds seen), signed by tick rule, net count × dir over (t0−300 s, t0] | ≤ −2 |
| E6 | fut OI change over (t0−900 s, t0] | ≤ −0.10% (the break runs on positions closing, not new ones) |

The thresholds are round numbers chosen before any data. They are not tuned. A filter that cannot be read (no data in the
window) keeps the trade, as the app's CONFIRM mode does.

## 4. In-trade exits (fixed now; checked every recorded second from entry + 60 s to the trade's own exit)

An early exit sells at the **best bid** of the next recorded second (no mid fill). It only ever exits earlier than today's
exits (stop, ladder/locks, 30-minute cap, square-off), never later.

| id | flow flips against the trade when | for |
|---|---|---|
| X1 | fut tick-rule delta / volume × dir over the last 60 s ≤ −0.30 | 30 consecutive seconds |
| X2 | bought option's top-5 depth imbalance ≤ −0.30 | 30 consecutive seconds |
| X3 | X1's and X2's conditions both hold | 15 consecutive seconds |

## 5. Pricing

- Entry at the arm's own fill (diagnostics) or, when rebuilt, the best ask at the signal bar's next minute open.
  Today's exits are priced as the arm priced them.
- Charges: `research.obuy.costs.Costs('dated')` (brokerage, STT, exchange, SEBI, stamp, GST), 1 lot at the lot size of
  the day. A stress test uses 1.5× the observed spread.
- The diagnostics hide live rupees (null qty and avg since 18 Oct). Live trades are priced from their points and % at 1 lot,
  like paper trades.

## 6. Success, per arm and per rule (all must hold)

1. Win rate (net of charges) of the trades kept (entry filter), or of all trades with the exit (exit rule), is **≥ 5
   points above** the arm's on the same trades.
2. Net ₹ after charges is **not lower** than the arm's, and also not lower at 1.5× spread.
3. It **beats random of the same size**: 2,000 random skips of the same number of trades (entry), or 2,000 random early
   exits at the same number of trades and the same times-in-trade drawn from the rule's own exits (exit). p = the share of
   random draws whose win gain AND net gain are both ≥ the rule's. Need p ≤ 0.05.
4. **BH q ≤ 0.10** across the flow-oi family: 131 earlier tests + R164's 18 (9 rules × 2 arms) + any sibling tests
   (R161-R163) in one joint step. R164 reports raw p-values for that.
5. **Minimum sample:** ≥ 150 eligible trades per arm, ≥ 30 trades skipped (entry) or changed (exit). Below that, the
   verdict is **inconclusive (too small)**, whatever the numbers. The numbers are shown, but nothing is decided from them.
6. Reported apart: NIFTY vs BANKNIFTY, and paper vs live.

## 7. If the data cannot be read or is too small

The result is then: **inconclusive**, plus

- (a) a pre-registered **forward SHADOW log** in the app: what to log at each Donchian entry and every 5 s in the trade,
  with a fixed trade count before any decision;
- (b) the recording changes needed (instruments, retention, a non-sealed research extract).

Nothing in the app changes in R164.

## 8. Signal source

Every rule's signal stays on the underlying (the index's 5-minute Donchian break). E2-E6 and X1 read the index future's
flow, which is underlying data. E1 and X2 read the **bought option's own order book**: they are option-chart rules and are
labelled so (CLAUDE.md). They are reported separately and could only be switched on by Boss.
