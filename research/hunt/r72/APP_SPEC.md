# App spec: "R72 Breadth Donchian" paper arm (for the app session)

16 Oct 2026. Boss: *"Okay add it"* (after R72, `research/HUNT_R72_CROSS_INDEX.md`). The research session that found it
was research-only, so it does not touch `android/**`; this file is the hand-over for the app session. **PAPER ONLY,
never live**, a new arm **beside** the R51 Donchian 20 paper arm (Boss's plan: run both on paper, compare on the same
unseen days, decide about mid-November).

## 1. What the arm does (exactly R72 C1, original setting, as gated)

Same as `DonchianArm` in every respect **except one extra entry condition**:

1. Every 5-minute bar (`PassedArms.bars5`, 09:15-anchored, continuous across sessions), for each of NIFTY, BANKNIFTY,
   FINNIFTY, MIDCPNIFTY and SENSEX, compute `DonchianArm.sideAt(series, i)` (close beyond the previous 20 bars' high /
   low).
2. Count, **for the same bar** (same date and same bar start time), how many of the five indices give +1, and how many
   give -1.
3. If **at least 4** give +1, every index giving +1 on that bar is a **call** signal; at least 4 giving -1: every index
   giving -1 is a **put** signal. Fewer than 4: no signal at all (even where plain Donchian would trade).
   - An index with no bar at that time (missing data, feed down) counts as "not breaking". The threshold stays 4
     (research: `r72rules.breadth_breakout`, M = 4, absolute, not a share).
4. Everything else is Donchian's: 1-ITM strike from the index close of the signal bar's last minute
   (`PassedArms.itmStrike(u, side, close, 1)`), nearest expiry; entries 09:20-15:10 (`DonchianArm.inWindow`); fill at the
   next bar's open with the 2-minute slack (`mayFill`); one position a side per index, at most 2 entries a side a day
   per index (`mayTake`, counted in **this arm's own book**, not Donchian's); never on that index's own expiry day;
   1 lot.
5. **Exits: exactly what the Donchian paper arm runs today**: stop -15% on the premium; R64's 30/60/100 ladder
   (breakeven + charges at +30%, +30% at +60%, +60% at +100%); **R66 L25** (+5% once +25%); no fixed target; out after 30
   minutes; square-off 15:15. (Research exit "XL", `research/hunt/r71/r71cfg.py` EXITS["XL"].)

Signal source: the indices' own bars (CLAUDE.md default); the option is only what is bought.

## 2. Where it plugs in (suggested; the app session decides the details)

- **Engine** (`android/engine/.../orb/`): a new pure `BreadthDonchianArm` object next to `DonchianArm`:
  `SOURCE = "r72_breadth_donchian"`, `LABEL = "R72 Breadth Donchian"`, `RESEARCH_ID = "R72 (C1, breadth 4 of 5)"`,
  `ARM = Arm(SOURCE, LABEL, paperOnly = true)`, `UNDERLYINGS` = Donchian's five, `MIN_BREADTH = 4`, and
  `signals(seriesByIndex: Map<String, List<Bar5>>, today: LocalDate): List<DonchianArm.Signal>` that reuses
  `DonchianArm.sideAt` / `inWindow` / `itmStrike` (do not copy the channel code). `RULES` / `RECORD` strings as below.
  Pure: no clock, no network, no storage.
- **Host** (`PassedArmsHost.kt`): add the source to `SOURCES`, `label`, `research`, `underlyings`, `apart` (report apart:
  none, `""`, or FINNIFTY like Donchian, for comparison); it reads the **same** five 5-minute series Donchian already
  builds (no extra network, no extra prefetch); its own book rows; the same lock / L25 handling as Donchian for its
  new trades.
- **Switch:** on once by the update (CLAUDE.md PAPER rule: the idea passed the gates), logged in Autopilot's change
  timeline as `PAPER_ARM_ON` with a one-tap undo, shown to Boss; never in IraGoldAlgo; never on an unreadable book (as
  the 15 Oct switch-on). After that only Boss switches it. No live path at all.
- **Forward bar:** the same `PassedArms.ForwardBar` as Donchian (at 40 paper trades it switches itself off if the net is
  below 0 or below the cost of twice the spread; risk-lowering, logged).
- **Comparison for Boss** (the point of running both): on the arms screen / Jarvis, Breadth Donchian's paper net next
  to Donchian's on the same days since the switch-on, and the net of the Donchian trades that breadth did NOT confirm
  (research: they lost Rs 55,790 in the final; confirmed ones made Rs 1,37,635).

Texts:
- RULES: "On each 5-minute bar: when at least 4 of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX close above the
  highest high of their previous 20 bars, buy the 1-ITM call of each index that did (below the lowest low: the put);
  fewer than 4: no trade. Next bar's open, 09:20-15:10; stop −15%, +5% once +25%, the 30/60/100 ladder, out after 30
  minutes, square-off 15:15; one position a side, at most 2 entries a side a day per index; never on the index's own
  expiry day; 1 lot; paper only"
- RECORD: "Research R72 final test: 1,202 trades, +₹1,71,366 (Donchian on the same days +₹55,439 / +₹81,845 with L25),
  p 0.008, q 0.032; +₹1,37,648 at twice the slippage. Same trades as Donchian 77% of the time: a filter, not a new edge"
- Risk line shown with it: "Backtest worst day −₹58,613, drawdown ₹1.99 lakh at 1 lot on five indices: beyond the
  −₹40k account stop; paper only"

## 3. Safety rules this must keep (CLAUDE.md)

- Paper only; no live path; Jarvis's/Boss's live switches untouched; no bypass of PIN, lock, Live, AI-live, guard, kill
  switch, day lock. New entries obey the kill switch and the bot's stop like the other passed arms.
- Order / tick path: the signal is computed where Donchian's is, off the tick path; nothing on the order or tick path
  touches disk, SecurePrefs or the network, or blocks (`OrderPathRound2Test` stays 0); no main-thread I/O.
- Open positions first: its open positions are watched at full cadence like every paper position; restored first after
  a restart; released last under memory pressure.
- Logged and labelled so its record stays apart from the plain-rules record for the 26 Oct decision.

## 4. Tests the app change needs

Engine unit tests (`BreadthDonchianArmTest`):
- 4 of 5 break up on the same bar -> 4 call signals (the 5th none); 5 of 5 -> 5; 3 of 5 -> none.
- the put mirror; mixed (3 up, 2 down) -> none.
- breaks on different bars (one index a bar later) do not add up.
- an index with no bar at that time counts as not breaking (4 of the other 4 -> signals; 3 of 4 -> none).
- the entry window (09:15 bar never; 15:10 last), the 1-ITM strike, the 2-minute fill slack, caps per index and side,
  expiry day skipped: as `DonchianArmTest`, through the shared helpers.
- research parity: a fixture of a few real days' bars (or synthetic bars) giving the same signals as
  `research/hunt/r72/r72rules.py breadth_breakout(M=4)` (the research tests' `test_breadth_breakout_needs_m_indices` is a
  ready template).

Host (Robolectric): switched on once (`PAPER_ARM_ON` in the change timeline, one-tap undo turns it off); not in
IraGoldAlgo; never on an unreadable book; its trades in its own book with Donchian's exits plus L25; the forward bar
switches it off by itself; the background-work rule (positions watched first after a restart) for its positions.
Plus `:engine:test :ira-core:test :engine:jacocoTestCoverageVerification`, the `code-review` skill or a separate
reviewer on the diff, and CI green including the app tests and Build APK.

## 5. Research references

- Rule: `research/hunt/r72/r72rules.py` (`Panel`, `breadth_breakout`), Donchian channel `research/hunt/r44/ind.py`.
- Exits: `research/hunt/r71/r71cfg.py` (XL), engine `research/hunt/r64/r64engine.py`.
- Results: `research/hunt/r72/results/final.json` (C1-O), `research/HUNT_R72_CROSS_INDEX.md`.
- Pre-registration: `research/hunt/r72/PREREG.md`.
