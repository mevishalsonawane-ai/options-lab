# All arms live: shadow, safety stop and review fixes (18 Oct 2026)

Branch `claude/all-live-1018-wip`. Final code SHA: **8cb69bca6de17bb6698001bd97ec92a03781beaa**. CI was green on it in one run:
App tests 0/4, 1/4, 2/4 and 3/4, Build APK, Engine parity with the PC, PC harness tests (run 39148842969). This file's own commit
follows it (documentation only). Not merged; no PR (the parent session merges).

The work-in-progress commit c03c5f02 was reviewed, finished and partly reworked. A separate reviewer agent then reviewed the whole
diff twice; every confirmed finding was fixed and re-tested (listed under "Second review" below).

## A) Shadow button per arm: done

- Every mirrored arm (`LiveMirror.ARMS`) has a Shadow switch, **ON by default**. In shadow nothing is sent to Zerodha, even with
  Live, AI trades and Trade live all on. Each would-be live entry and exit is recorded as a shadow trade: the price it would have
  sent (Zerodha's quote), and exits priced as the live mirror fills them (a stop at its resting SL's trigger unless the price ran
  through the SL's limit, any other exit at the market), charges included.
- The shadow record (trades, net after charges, win rate, open in shadow, and paper trades not mirrored today) is shown beside
  both switches on each arm's row in the Live-switch card.
- Off needs the PIN; on needs none. Solo (midday) cannot leave shadow while `IraSolo.provenWhy()` is not null (today it never is),
  and the reason is shown. Its "Never live" doc in IraSolo.kt is fixed.
- Each change goes into the Autopilot timeline as the new `ARM_SHADOW` kind (Boss's own, kept), with a one-tap undo. An undo that
  takes an arm out of shadow needs the PIN.
- Turning Shadow on, or Trade live off, while a live trade is open leaves its stop and exits running (tested).
- Arms that were live before this branch (ORB, ORB Fresh, Liquidity, Pine, Strategies) have no Shadow switch.
- Jarvis answers "how is <arm> doing in shadow?" with the shadow record and the live record, labelled apart. The passed arms',
  Hero ticket's, Hero day's and Solo day's answers also end with that arm's shadow and live records.
- The Shadow state survives a restart: the book file, plus a second copy of both switch sets in SecurePrefs.

## B) Safety stop: done

Night, Solo (midday), Solo 2.0 (S1's index stop), VIX BANKNIFTY, the MCX 12-month trend, and the re-armed O08 shadow variant
(its stop is on the index) rest a "safety stop" 30% below the fill when their paper trade gives no premium stop. It is an SL
order for an intraday trade and a single-leg GTT for a carried trade.

- It never moves down.
- It moves up to the arm's own level when the paper trade sets one, and is then labelled the arm's stop.
- The arm's exits take it out first, under the one-resting-exit rule.
- It is labelled "safety stop" in the log, alerts and the booked exit.

## C) Order-safety findings

1. Fixed. A fired GTT whose order has not filled no longer blocks the exit. Its order is cancelled (confirmed out), then the app
   sells at MARKET. The run-through check uses the GTT's own leg price. This also covers a part exit against a fired GTT.
2. Fixed. On every look, positions are grouped by contract (symbol and product). A resting stop is shrunk, or cancelled, down to
   what is held less the other exits working on it, newest first. A part closed outside is charged oldest first (the SharedSales
   rule).
   - Active GTTs count as resting exits (a second GTT is never placed beside one).
   - The same rule is in `Strategies.restLegStops`.
   - It covers two arms on one option (R51 and R72).
3. Fixed. A trade is "seen held" only once Zerodha's position book shows it and every older trade on the contract. A zero read for
   a trade never seen held counts only after a 60-second grace. When a stop can't rest, the user gets an alert (throttled) and the
   next look tries again.
4. Fixed. A part sale modifies the stop's quantity down. An exit that does not go, or does not fill, puts the stop back in the same
   call (re-rested, or grown back). Exits check the books and the order gates before touching the stop at all.
5. Fixed (policy). Solo (midday) keeps its live path but cannot leave shadow unless `IraSolo.provenWhy()` passes.
6. Fixed. No live entry on the opposite side of a non-zero net position in the same symbol and product.
7. Fixed. `restLegStops` uses the contract's own exchange (BFO-ready, and the exit path too), "stop_not_rested" is told at most once
   in 10 minutes per leg, and NRML legs rest a GTT. Note: Strategies' live venue is NFO-only today, so BFO is ready but not
   reachable.
8. Fixed. No live MIS entry at or after 15:15 (15:05 on its expiry day), and none for a paper trade already closed.
9. Fixed. Carried (NRML) NSE options are squared off at 15:05 on their expiry day.

## D) Rule-parity findings

1. Covered by C2.
2. Fixed. Hero and the Hero ticket (`Spec.closeStop`) are sold by their paper close-exit. Only a labelled "disaster stop" rests,
   at −80% for both.
   - Why −80% for the ticket: a ticket buys ₹5–30 options that swing 30% or more in minutes, while its 25% trail (judged on minute
     closes, and with the default 2x start no stop at all below 2x) holds them. A tick stop at −30% would sell on ordinary noise
     the paper ticket holds through.
   - −80% fires only in a collapse the paper trade would also be in, and a ticket's whole lot costs at most ₹1,000.
3. Fixed. `ShadowRules.premiumStop` gives the −40 stop of all 8 "−40" variants (O08's stop is on the index, so it gets the safety
   stop). `ShadowRules.lockLevel` gives R20's and the study shadows' lock. ShadowArms calls `ArmLive.level` whenever the peak moves.
4. Fixed. Every paper trade not mirrored is recorded "not mirrored: <reason>" (the account guard's cap included; the cap is not
   raised) and counted on the arm's row with the last reason.
5. Fixed. An unreadable book refuses every live entry and alerts Boss; open trades are still treated as held. The switches and
   shadow choices have a second copy in SecurePrefs. A damaged book is set aside and its open live trades are taken back from
   Zerodha's positions, today's `iraarm`-tagged orders and their owner labels, keeping a still-resting SL or GTT. This runs first
   at start-up and on every scheduled look. Positions that can't be matched are listed in an alert.
6. Fixed. The texts named in the review now say "paper book; its live copy follows its own Trade live and Shadow switches", or
   show the live state built from the switches (`ArmLive.stateWords` / `stateOf`). Jarvis's talk files get each arm's live and
   shadow records, labelled apart.
7. Fixed. All real trades are kept (shadow trades: the last 600 closed). Records count each paper trade once by ref (a part exit
   is not another trade). `accountStop` runs on the whole live record plus the ORB family's.
8. Done: see "Tests added".
9. Fixed.
   - `closedRefs` and `levels` drop earlier days and are capped at 2,000.
   - The arm list shows "loading" until the book is read.
   - Solo (midday) is hidden in builds without Jarvis, and the whole list is hidden in GOLD builds.

## Second review (a separate reviewer agent on the whole diff): 11 confirmed findings, all fixed

1. A damaged book's recovery never ran: the hints only woke the watch for held trades.
2. A cached GTT list could drop a fresh GTT, and a second GTT could rest beside it. Two GTTs could fire into a short.
3. A lost reply on placing a GTT could leave a duplicate GTT.
4. Recovery ignored a resting GTT.
5. Unconfirmed entries took part in the look.
6. A position book lagging a new fill closed the older trade on the same contract.
7. Stale order data within one look removed a sibling's stop.
8. Shadow trades were not looked at on a schedule; a reached stop lost to the paper exit; with no price a trade was booked at the
   price paid.
9. A part exit against a fired GTT was blocked.
10. A failed part exit left part of the position without a stop.
11. A whole exit whose stop had part filled left the rest unsold.

Also fixed from its "plausible" list:
- Shadow is re-read just before an order is sent.
- An exit refused by the order gates no longer cancels and re-rests the stop every look.
- Strategies' exit path uses the contract's exchange.

## Third pass (the reviewer re-checked the fixes): 3 more, all fixed

1. A stop that filled for less than the trade kept its id, so the rest never got a new stop. It is now cleared, and the next
   look rests a stop for the rest.
2. A fired GTT that filled for less than the trade was booked twice. It is now booked once, and the rest gets a new GTT.
3. A GTT deleted earlier in a look still counted against a sibling's stop. GTTs are now read again with the books.

Also from this pass:
- Recovery prefers a GTT whose quantity matches the position.
- Recovered trades now get their contract's expiry, so the 15:05 expiry square-off applies to them.

Left as is (low risk, noted for Boss):
- On a lost reply, ArmLive and Strategies could adopt each other's identical GTT. This needs the same symbol, trigger and
  quantity, so it is rare.
- After a damaged book, the "could not match" alert also lists Boss's own positions.

## Tests added

- **Engine (LiveMirrorTest, ShadowRulesTest):** shadow defaults and records, shadow exit pricing, safety and disaster levels and
  which arms carry them, stop shrink, the lagging-zero rule, run-through on a GTT's own limit, late and against-net refusals, the
  15:05 NRML expiry square-off, `shadowAsked`, all 8 −40 variants and their locks.
- **ira-core:**
  - AutoChangeTest: `ARM_SHADOW` (risk direction, undo PIN, Boss's own, newest flip only).
  - PassedArmsTalkTest: updated words.
- **App, ArmLiveTest (about 30 new tests):**
  - Shadow: every arm starts in shadow; nothing is sent; shadow P&L; the PIN rules; Solo stays in shadow; the timeline entry and
    its undo (Jarvis builds).
  - Open trades: a live trade keeps running when Shadow goes on or Trade live goes off.
  - Persistence: switches survive a restart and a lost file; a damaged book is recovered, SL and GTT kept, no second stop.
  - Stops: the safety GTT and SL, the disaster stop.
  - Fired GTTs: unfilled, through its leg price, part exit.
  - Quantities: hand sale shrinks the stop; a sibling keeps its stop; a lagging book; a part-filled stop.
  - Refusals: against net, 15:15, already closed.
  - Other: the 15:05 expiry square-off; not mirrored by the guard; Jarvis's answer.
- **App, other test files:**
  - StrategiesLiveTest: GTT for NRML legs, SL for MIS legs, shrink on a hand sale.
  - OneSwitchTest: the Telegram switch is off, every ARMS row renders both switches, the Shadow switch with the PIN.
  - PassedArmsHostTest: shadow end to end.
- **Host wiring tests:** one end-to-end test per host family (ORB Sweep, Hero, Hero ticket, VIX, Night, MCX, re-armed Shadow
  variants, Solo, Telegram), through a debug-only seam (`ArmLive.testHeard`) that records what each host tells the live path.
- **Jarvis's own ideas:** the source-string check is replaced with a behaviour test.

## For Boss to decide

1. **When to leave shadow.** Every arm stays in shadow until you turn it off with your PIN. Shadow trades are recorded only while
   the app is in Live with AI trades on and the arm's Trade live on, because that is when a live trade would go. In Paper,
   nothing is shadowed.
2. **The ticket's disaster stop at −80%.** The alternative is no stop at Zerodha at all. Change `LiveMirror.DISASTER_PCT` if you
   want another level.
3. **Night's carried closes outside.** A Night trade closed outside on one day drops out of Night's registered legs the next day,
   instead of showing quantity 0. The live path treats a trade it saw open and that is now missing as closed, so it still
   follows. No change was made.
4. **Not covered by a test:** a lost reply on placing a GTT (the fake Zerodha cannot simulate it). The logic adopts a matching
   active GTT; please watch for it in the first live days.

## Final review fixes (an independent review of 8cb69bc): 8 confirmed findings, all fixed

1. **HIGH: an unreadable book at start-up left open live trades unwatched.** Fixed. While the book cannot be read, `recoverHint` (and
   so `lookDue`) is on: the minute lane, the live lane, the 15-second stop check and both keep-alives (`Workers.heldQuick`,
   `SmartWorkers.holdingAny`) keep running for it, and each watch reads the book again, at most every 5 s. Once it reads, its
   open live trades are watched in the same pass. The Live-switch card now says what really happens ("reads it again every few
   seconds and watches its open live trades as soon as it reads; meanwhile their stops rest at Zerodha").
   Test: the first read fails, the trade is held, the paper trade closes; the next read succeeds and the live trade is sold.
2. **MEDIUM: Trade live, AI-live, Paper and Shadow were not re-checked inside the entry door.** Fixed. For an `iraarm` entry,
   `Broker.entryRecheck` asks `ArmLive.doorRefusal` at the last moment: the app in Live, "AI trades go live", the arm's Trade live
   and its Shadow, all from memory. The Live switch is kept in memory (`ArmLive.liveFlags`) and read again only after one of its
   keys is written (a new `SecurePrefs.liveHint` generation for `k.mode`, `k.allow` and "AI trades go live"), as the kill switch is.
   Tests: Shadow turned on while the entry waits in a held door sends nothing; each of the four switches refuses at the door.
3. **MEDIUM: the account stop rule and the security check let entries through when they failed.** Fixed. Any failure refuses the
   entry: "the account stop could not be checked" (an ORB record that cannot be read now counts as a failure, not as no losses),
   and a security check that cannot run counts as failed. The day lock and the daily loss limit fail closed the same way.
   Tests: both failures refuse; with both readable the entry goes.
4. **MEDIUM: shadow P&L was better than live would get.** Fixed.
   - A shadow MARKET buy pays Zerodha's ask and a sale gets the bid (with no usable book: the last price less the measured
     default half-spread). It is never better than the app's own measured live slippage (`Slippage.medianShare`: the arms' own
     Zerodha fills first, else all, once there are 5). The worse of the two is used, not the sum, because the measured slippage
     is taken against the last price and so already holds the spread.
   - A shadow stop is judged on the lowest price (a short's highest) traded since the last look: the stream's ticks
     (`TickPath.lowSince`), else Zerodha's minute candles that began after the last look. It fills at its trigger less the
     spread or the measured stop slippage, never past its SL limit. A price through the limit now is a market sale at the bid.
   - The card says what the records include: "priced at Zerodha's ask to buy and bid to sell, with the app's measured slippage
     and the charges; stops judged on each minute's low or high".
   - Tests: the engine rules; a wick through the stop inside a minute fills it; a minute from before the last look is never
     judged; the earlier shadow tests now expect the ask, the bid and the trigger less the spread.
5. **LOW-MEDIUM: Solo (midday) was kept in shadow only by `setShadow`.** Fixed. `enterNow` forces shadow for Solo (midday) while
   `soloWhy()` is not null, whatever the stored switch says. Test: a stored Shadow off still records a shadow trade and sends
   nothing.
6. **LOW: a part closed by hand was booked at the price now.** Fixed. It is booked at what Zerodha's trade book says it sold for
   (the newest exit-side fills on that contract that are none of the app's own orders), else its order book's average; only with
   neither, at the price now, else the price paid. An own exit or entry with no average from Zerodha reads its trade book too.
   Test: the trade book's price, then the order book's.
7. **LOW: settings reads on the caller's thread.** Fixed. `entered()` and `stateOf()` read the Live switch from memory
   (`liveFlags`); the Hero dialog works its live state out on the IO threads (`produceState`). Tests: OrderPathRound2Test (a paper
   fill and a mirrored entry's door look make no settings reads, and a later flip is seen at once); MainThreadBinderTest (the
   dialog's state is read off the main thread).
8. **LOW: nothing was watched before the day's Zerodha login.** Fixed. With a live trade open, an exit due (its paper trade closed
   or sold part, or its square-off) and no login, Boss is alerted once a day per trade: "<arm> live trade waiting for the Zerodha
   login to exit". Test: nothing said while no exit is due; one alert when it is, and nothing sold.

Also: the Live PIN prompt (`LIVE_PIN_ASK`) now says every paper strategy starts in Shadow and sends nothing to Zerodha until its
Shadow is turned off with the PIN.

A reviewer agent then checked these fixes. Its confirmed findings were fixed too:
- The shadow look's quote and candle reads no longer hold the live trades' lock. It runs after the live look, under its own
  lock, so the live lane and the 15-second check never skip a live look while it reads. It makes at most 4 candle reads a look,
  one per contract, each within 3 s.
- The live lane never re-reads an unreadable book: only the minute lane and the 15-second check do. Their wait doubles from 5 s
  to 60 s while the read keeps failing.
- The door's kept Live switch reads only its own keys on a miss (the mode and "AI trades go live"), never the whole settings,
  and the entry's own checks keep it warm.
- A shadow window whose candles could not be read is kept for the next look, never skipped. A stop that moves starts a new
  window. A wick through the SL's own limit fills at that limit.
- Several parts closed outside in one look each take the next fill (newest first), never the same fill twice.
- The go-live dialog (Root.kt) now says the same as the PIN prompt: a paper strategy trades live only once it is out of Shadow.

Left as is (noted for Boss):
- After a restart, a shadow trade's first look is judged on the price at that look, not on the minutes it missed: a stop moved up
  before the restart must not be judged on prices from before it moved.
- Without a historical-data plan the minute candles cannot be read: a shadow stop is then judged on the stream's ticks when the
  contract streams, else on the price at each look (as before). After a failed read the candles are not asked again for 2 minutes.
- The stream keeps 5 minutes of ticks: a look more than 5 minutes after the last one (a stalled phone) judges only those 5 minutes.

CI on the final review fixes: the first run turned up a test still checking the go-live dialog's old wording, and the new
hand-sale test entering 3 lots, more than the account guard allows. Both tests were fixed. One unrelated test
(`LiveSelfTestTest.staleFeedsAndAFarClockFail`) failed once at 00:04 IST: its "10 minutes ago" bar fell on the previous day. It
passed on the next run; it can fail again in the first 10 minutes after midnight.

Final green SHA (App tests 0/4, 1/4, 2/4 and 3/4, Build APK, Engine parity with the PC, PC harness tests, all green in one run):
a68f527cf11e40c0211ec099813c282ab3fbfb48
