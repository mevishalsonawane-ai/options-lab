# R164: does watching buyers and sellers help the Donchian arms? (12 Nov 2026)

Boss (12 Nov): "I want you to also check continuously the buyers and sellers while trading, may be that will help."
Arms: R72 Breadth Donchian and R51 Donchian 20 (+Supertrend), live with R151's pickers.

## For Boss, in short

- **Not tested yet, so no answer.** The phone's buyer/seller recordings are locked with your backup passphrase. I must not
  have that passphrase. This session was also not allowed to open the private IraAlgo data repository. So no recording was
  read.
- **Even unlocked, the data is too small to decide.**
  - The phone records buyers and sellers only for **NIFTY and BANKNIFTY**. The Donchian arms trade five indices.
  - In the 21 recorded sessions, that is about **76 R72 trades and 106 R51 trades** (an estimate; with your pickers on,
    about 41 and 44).
  - Proving a 5-point higher win rate honestly needs about **650 trades per arm**. A loose check needs about 245.
- **Earlier research is not encouraging.** 131 earlier order-flow and OI tests found that buyers and sellers explain the
  price in the same seconds but say little about the next minutes. None of those rules paid an option buyer.
- **What I did instead:**
  - pre-registered 6 entry checks and 3 "get out when the flow turns" exits, with the code ready and tested;
  - wrote a plan for a **shadow log** in the app. It only records; it never skips or exits a trade. You decide after a
    fixed **650 trades per arm**, which is about 3-4 months.
- **Nothing in the app changed.**

## 1. What data exists

| source | where | what is in it | can research read it? |
|---|---|---|---|
| Tick zone, `ticks.jsonl.gz` | phone, `recordings/YYYY-MM-DD/`; uploaded nightly inside `live-YYYY-MM-DD.irlive` (IraAlgo releases `live-2026-10`, `live-2026-11`) | one row **a second** per instrument: LTP, cumulative volume, ATP, OI, total buy/sell qty, **5 bid + 5 ask levels** (price, qty, orders). Instruments: **NIFTY and BANKNIFTY near futures, ATM ±3 calls and puts of their nearest expiry, India VIX** (about 31). Since 12 Oct 2026. | **No.** `.irlive` is sealed (PBKDF2-SHA256 + AES-256-GCM, `LiveSeal.kt`) with Boss's backup passphrase. I stopped on this path; nothing was tried against the encryption. |
| `orderflow_1m.jsonl.gz` | same sealed bundle | per minute per instrument: OHLC, volume, **tick-rule** buy/sell/delta, CVD, VWAP, OI, book imbalance (totals and top 5), spread, large prints (> day's p99), absorption flag | No (sealed) |
| `market.jsonl.gz` | same sealed bundle | minute rows of every index (all five) and snapshots at each signal and fill | No (sealed) |
| `trades.jsonl.gz.vault` | same bundle | Boss's own trades, sealed a second time with the phone's key | No (phone only) |
| Daily diagnostics `diag-YYYY-MM-DD.json.gz` | IraAlgo releases `diag-2026-10/11` | not sealed: arms' trades (entry/exit time and premium, points, %; live lots hidden since 18 Oct), orders with **symbols** (live qty/price hidden), decisions | Yes in principle, but the repository could not be attached here, so **not read** |
| `FlowGate` shadow log (`orderflow/shadow.log`) | phone only (not uploaded) | order flow at each strategy signal | **Does not include R51 or R72**: they are not in `FlowShadow.STRATEGIES` |

Facts that limit any test:

- **No aggressor flag.** Zerodha's full-mode ticks do not say whether a trade hit the bid or the ask. "Buyers vs sellers"
  is the tick rule's estimate: price up = buy.
- **Coverage is 2 of the arms' 5 indices.** FINNIFTY, MIDCPNIFTY and SENSEX trades have no depth record at all. FINNIFTY
  carried R51's research profit (356 of 1,787 final trades).
- **Raw seconds are kept 10 days on the phone.** The sealed nightly upload is the only lasting copy. A day the phone
  missed, or a day over the 150 MB tick budget, has gaps.

## 2. Trades inside coverage

I could not list them: the diagnostics and recordings were not readable here. `study.py` builds the list on Boss's PC
from the unsealed diagnostics, using each trade's BUY order for the symbol. It keeps only trades whose option was in that
day's watch-list and that have recorded seconds just before the signal.

The expected size, from committed research rates (`coverage.py`, `results/coverage.json`). It assumes NIFTY and BANKNIFTY
are 2/5 of trades, because the per-index split is not in the committed results:

| arm | trades a session (all 5 indices) | covered a session (2 of 5) | covered in 21 sessions (12 Oct - 11 Nov) |
|---|---|---|---|
| R72 (all breaks) | 9.0 (1,202 / 133 sessions) | 3.6 | **≈ 76** |
| R51 + Supertrend (all breaks) | 12.6 (1,670 / 133) | 5.0 | **≈ 106** |
| R72 picker-taken | 4.9 (652 / 133) | 2.0 | ≈ 41 |
| R51 picker-taken | 5.2 (693 / 133) | 2.1 | ≈ 44 |

This is an upper bound: any day the phone did not record, or recorded with gaps, removes trades.

## 3. The pre-registered rules (PREREG.md, committed `d44411f` before any code or number)

Entry filters, read at the signal second (skip only; a read that is missing keeps the trade):

| id | read | skip when | signal source |
|---|---|---|---|
| E1 | bought option's top-5 depth imbalance, mean 60 s | ≤ −0.20 | **option's own book (option-chart rule)** |
| E2 | index future's top-5 depth imbalance × direction, mean 60 s | ≤ −0.20 | underlying |
| E3 | future's tick-rule delta / volume × direction, 1 min | ≤ −0.20 | underlying |
| E4 | the same over 5 min | ≤ −0.10 | underlying |
| E5 | future's net large prints × direction, 5 min | ≤ −2 | underlying |
| E6 | future's OI change, 15 min | ≤ −0.10% | underlying context |

In-trade exits, checked every recorded second from entry + 60 s. Each sells at the next second's **best bid** and only
ever exits earlier than today's exits:

| id | flow turns against the trade when | for |
|---|---|---|
| X1 | future's 60-s tick-rule delta / volume × direction ≤ −0.30 | 30 s |
| X2 | bought option's top-5 depth imbalance ≤ −0.30 | 30 s (**option-chart rule**) |
| X3 | both | 15 s (**option-chart rule**) |

Success, per arm and rule (all must hold):

- win rate up ≥ 5 points;
- net (dated charges) not lower, also at 1.5× spread;
- beats 2,000 random skips or exits of the same size (p ≤ 0.05);
- BH q ≤ 0.10 across the flow-oi family;
- at least 150 trades per arm and at least 30 changed, else **inconclusive**.

**Status: not run.** Every rule is "inconclusive: data unreadable here, and too small even if read".

## 4. How many trades a decision needs

`coverage.py`, a two-proportion test of kept vs skipped trades at 80% power. The win rate gain is measured on the kept
trades against all trades:

| arm (win rate) | 20% skipped | 30% skipped | 50% skipped |
|---|---|---|---|
| R72 (45.8%) at α 0.05 | 126 | 247 | 608 |
| R72 at the family's first BH rank (α 0.10 / 149) | 334 | 654 | 1,612 |
| R51 (44.9%) at α 0.05 | 124 | 244 | 606 |
| R51 at the first BH rank | 327 | 647 | 1,606 |

- The recordings hold about 76 and 106 covered trades. That is below the 150 minimum and below every row above.
- **The recorded sample is too small to decide, by a factor of 2-6.** That holds even before the sealing problem.

## 5. Proposal (a): a forward SHADOW log for the Donchian arms

A logger only. It never skips, resizes or exits anything, paper or live, and it is off for orders by construction. Every
read happens off the order and tick path, from memory the order-flow thread already holds. It is written by that thread's
existing 30-second batch. Nothing new touches disk, SecurePrefs or the network on the order path.

**At each Donchian break**, R72 and R51, all five indices. This includes breaks the R151 picker or a guard skips, logged as
would-be trades with the option they would have bought:

- arm, index, side, signal bar time, the fill (or would-be fill) second and premium;
- picker score and label, Supertrend and breadth count;
- the option's token/symbol, and its best bid/ask, spread, top-5 bid and ask quantities;
- E1-E6's **raw values** (not just pass/fail) for the option and the index future, with the seconds of data each read had;
- the feed's age and any gap in the last 5 minutes.

**Every 5 seconds while the trade (or would-be trade) is open:**

- the option's LTP, best bid/ask and top-5 imbalance;
- the future's 60-s tick-rule delta / volume and top-5 imbalance;
- X1/X2/X3's condition and run counts, and the second each would have fired, with that best bid.

**At the exit:** the arm's own exit reason, second and price.

**Fixed count before any decision:** **650 closed trades per arm**, all five indices, all breaks.

- That is about 72 sessions for R72 and 52 for R51 (roughly 3½ months).
- The picker-taken subset is reported apart.
- It is read **once**, with this PREREG's rules unchanged.
- If a rule passes, it can only become a **shadow → Boss's switch** change. Live: skip or exit-earlier only, behind a
  switch that is off by default (CLAUDE.md).

## 6. Proposal (b): recording changes needed

1. **Record what the Donchian arms trade.** Add FINNIFTY, MIDCPNIFTY and SENSEX near futures. Also add the option each
   Donchian break buys or would buy, on all five indices, from its signal second to its exit.
   - That is about 45 more instruments. `TickZone.MAX_INSTRUMENTS` (40) and the 150 MB daily tick budget would need
     raising. Kite allows 3,000 instruments per connection.
   - The position watch keeps first priority under memory pressure, as CLAUDE.md requires.
2. **Add R51 and R72 to `FlowShadow.STRATEGIES`** in SHADOW mode, so `FlowGate` logs them like every other directional
   strategy.
3. **An unsealed research extract.** A small per-break summary (section 5's numbers, market data only, no rupees, no
   account fields) in the daily diagnostics. Research can then judge it without the passphrase.
   - The raw ticks stay sealed.
4. **A better buyer/seller estimate.** Today it is the tick rule (formula v1). The per-second rows already hold LTP and the
   five levels, so a quote rule (trade at or above the previous second's ask = buy, at or below the bid = sell) could be
   added as formula v2 beside v1. It would be logged, not swapped.

## 7. Raw p-values for the joint BH with R161-R163

**None.** R164 ran no test, so it adds **0** p-values now. Its 9 entries are logged as `proposed`, which does not count.
When run, R164 adds 18 tests (9 rules × 2 arms) to the flow-oi family's 131 earlier tests.

## 8. Files

- `PREREG.md`: the pre-registration (sha256 `4722c1c2…0efdf2`).
- `flow.py`: frozen reads and exits from the tick zone's per-second rows.
- `study.py`: pricing (dated charges), gates, random twins, BH, the minimum-sample verdict, and the runner for Boss's PC
  (opened days + diagnostics; it writes aggregates only).
- `coverage.py` → `results/coverage.json`: the expected covered trades and the trades needed.
- `make_entries.py` → `research/experiments/entries/r164-*.yaml` (9, `proposed`).
- `tests/test_r164.py`: 19 tests, synthetic data only, including the look-ahead guard. Reads ignore rows after the signal
  second; an exit decided at second t sells at a later recorded second; gaps restart the count.

How Boss runs it later, on their own PC. The passphrase is never shared, and the raw days stay outside the repo:

    python research/data/decrypt_live.py live-2026-1*-*.irlive --extract days/
    python -m research.hunt.r164_donchian_orderflow.study --days days/ --diag diag/
