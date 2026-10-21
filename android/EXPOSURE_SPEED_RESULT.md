# Paper order speed, same-direction cap, no duplicate Donchian contracts (19 Oct 2026)

Boss, 19 Oct: "Resolve the issues". Branch `claude/exposure-speed-1019`, base `main` (776c56e5).

## What was slow, and why

The paper order path waited on encrypted disk writes, one after another.

Each paper vault write is a Keystore encryption plus two disk syncs: a second or more on a Pixel's StrongBox. Before this
change, every automatic paper entry of the passed-research arms waited on these, in this order:

1. `Paper.snapshot()` for the account guard: it saved the re-marked book, and `locked {}` then **flushed it to disk on the
   caller's thread**.
2. `Paper.place()` itself: the same synchronous flush after the order.
3. `PassedArmsHost.record()` → `save()`: the arms' own book written to its vault file **under the arms' lock**.
4. `Strategies.tagOwner()`: the whole strategies book (up to 3,000 order labels) written to its vault file **under the
   strategies lock**. Every arm's next entry calls `Strategies.stoppedToday()`, which takes that same lock.

The bar-close entry check then took the indices one after another (R51 on five, then R72 on five). So the fifth index's order
waited for about 4 × 3–4 writes. The arm never marked its own decision either, so "decision → sent" ran from the start of the
check. That explains the 19 Oct figures: decision → sent 5.7 s typical and 35 s worst, and the SENSEX put bought at 09:31:52
for the 09:30 bar. The same writes held the 15-second stop check's exits: `manageOnly()` waits for the arms' lock.

Each option's price was also read inside its own entry, one index at a time. When the stream did not have the option yet, that
was a network read per index.

## The fix (root cause, not a workaround)

- **Write-behind** (`ira-core` `WriteBehind`): the paper book, the passed arms' book and `tagOwner`'s labels are now written on
  each store's own background thread. The caller is answered from memory. Writes coalesce, run one at a time, and the newest
  book is always written last. The strategies book orders its synchronous `save` and its background `saveSoon` with a
  sequence number, so a late background write can never overwrite a newer book. A failed write is kept and retried with the
  next change. The window where a killed process could lose the last change is the write itself (well under the second it
  used to block for); the next look writes it again, and a restart restores what is on disk first, as before.
- **Each order times its own decision** (`OrderTiming.ownDecision`): "decision → sent" now measures that order's own checks.
  "Signal → sent" is unchanged and still covers the whole wait from the bar close.
- **Prices side by side**: before the entries, the bar's due Donchian options are priced in parallel (capped at 3 s); each
  entry then finds its price in memory. The fill itself is still priced fresh in `Paper.place` (2 s at most), as before.
- Nothing new touches disk, SecurePrefs or the network on the order or tick path. The cap reads only switches kept in memory.
  The stops, targets and exits of open positions run as before, and no longer wait behind an entry's writes.

## Before and after (tests with a 1.5 s vault write, as a StrongBox)

On the phone, 19 Oct (diagnostics, 18 paper orders): decision → sent 5.7 s typical, 35 s worst; signal → sent 6.1 s typical.

In the tests, every vault write is made 1.5 s slow (`Vault.testWriteDelayMs`). CI passes them on 7a94802:

| Test (slow vault, 1.5 s a write) | Before (write on the caller) | After (write-behind) |
|---|---|---|
| One paper order (`ExposureSpeedTest`, 5 indices in a row) | ≥ 1,500 ms each | < 500 ms each |
| Decision → sent, one run deciding once then 5 orders | median ≥ 2,500 ms | median < 1,000 ms |
| Whole R51 entry pass (`PassedArmsHostTest`) | 3–4 writes in a row (≥ 4.5 s) | < 1,500 ms, decision → sent < 1,000 ms |
| An exit on a slow vault | ≥ 1,500 ms | < 500 ms |
| 9 refused entries in a row | n/a | < 1,000 ms total, 0 settings reads |

These bounds are asserted, not measured: CI prints no times for tests that pass. The phone's own order-speed card shows the
real figures from the next session on.

## The same-direction cap

- At most **2** automatic open option positions betting the same way on the index options (NIFTY, BANKNIFTY, FINNIFTY,
  MIDCPNIFTY, SENSEX), across all arms. A long call bets up and a long put bets down.
- **Where**: inside the paper book's own lock in `Paper.place`, for every automatic entry (`auto = arm`). **First to reach the
  door wins.** When several arms fire on one bar, the first two booked go and the rest are refused. A memory-only pre-check
  refuses earlier, before any price read.
- **Counting**: each automatic entry counts once, from the paper book's own fills (first in, first out), trimmed to what it
  holds, plus working automatic BUY orders. Liquidity's 15m and 5m books on one put count 2.
- **Refused** with a plain reason, for example "same-direction cap: 2 puts already open (… by R51 Donchian 20; …)". Each refusal
  is recorded once as a would-be entry, with its arm, option, way and price. The diagnostics and Bot settings show today's
  refusals by arm, and **Liquidity's are counted apart** from its plain-rules record.
- **Never capped**: exits (only opening BUYs are asked), Boss's own orders (manual tickets, baskets, Jarvis acting on Boss's
  words), saved strategies' baskets (a refused leg could leave a hedge open), MCX and stock options.
- **Paper: on by default**. Logged once in the Autopilot change timeline as Boss's ask and undone in one tap. Settings → Bot
  settings → Same-direction cap has the switch and the number (1–5); every flip is logged.
- **Live: shadow, off by default**. The entry door records what it would have refused and changes nothing. Its own switch
  ("Also refuse the bots' live entries") turns live refusals on; it only ever refuses. It counts the bots' live entries sent
  through the door, minus exits and whatever a later positions read shows closed.
- **Live mirror (review fix)**: while an arm's paper entry could also be mirrored to Zerodha (Live, AI trades on, that arm's
  "Trade live" on and out of Shadow, no paper-only period), the paper cap and the no-twin rule only log. Refusing the paper
  entry would also skip its live trade. During the paper-only period (Boss, 13–26 Oct) both apply in full.
- R51 and R72 refusals become **would-be trades** in their own book (`shadow = cap`), run by their own exits at the prices
  seen, with estimated charges. Their records stay comparable.

## No duplicate Donchian contracts

R72 Breadth Donchian no longer buys an option R51 Donchian 20 holds, and the other way round. R51 decides first on a bar both
break. The second arm's trade is a **would-be trade** (`shadow = twin`): no paper order, entered at the twin's real fill, its
own exits (selling at a fresh bid when there is one), estimated real charges, never mirrored live and no notice. It is labelled in its row and in Jarvis's words. The R72-vs-R51
comparison and each arm's forward bar still count it. Liquidity's own 15m/5m behaviour is untouched (its plain rules are the
26 Oct live candidate), but the cap counts both books.

Not changed: the daily loss limit and the day lock.

## Tests

- `ira-core`: `SameDirectionCapTest` covers 11 same-bar puts (2 enter, 9 refused and recorded, Liquidity's 3 counted apart),
  exits free a place, manual orders are never counted, two books on one option count twice, the FIFO trims, the live ledger,
  and the Autopilot entry and its undo. `WriteBehindTest` covers a 1.5 s writer never holding the caller, newest last, no
  older-over-newer, failures counted, and a dead writer. `WhatsNewChangelogTest` and `BatteryRestTest` indices moved by +1.
- `app`: `ExposureSpeedTest` covers a slow vault (before ≥ 1.5 s per order and decision → sent ≥ 2.5 s; after < 0.5 s and
  < 1 s; durable after a restart), an exit answered at once on a slow vault, 11 puts → 2 + 9 refused, exits and manual orders
  never capped, the switch and the number, no settings read while refusing, live shadow-only until switched on (through
  `Broker.entryRecheck`), and the Autopilot undo. In `PassedArmsHostTest`, a whole entry pass on a slow vault takes less than
  one write and decision → sent stays under 1 s; a capped R51 break becomes a would-be trade, decided once and closed by its
  own stop; R72 beside R51 is a would-be trade with one lot on the account; the old shared-lot tests run on a carried-over book.
  `SameDirectionCapCardTest` covers the Bot settings card.

## Review

A separate reviewer agent found no compile errors and no test failures. It confirmed three logic bugs, all fixed in b96caec:

1. Resting live stops wrongly freed cap places.
2. The paper cap and the twin rule could skip an arm's live mirror outside the shadow rule.
3. The strategies label write still held its ordering lock through the write.

The same commit also fixed four minor points:

- prices of would-be trades;
- a prewarm timeout that did not bound the wait;
- the position popup showing would-be trades;
- the cap's kept record not merged with refusals made before it was read.

CI on the first push saw one test reach the internet (a background options-list download). 7a94802 makes the prewarm use only
lists already saved.

## Final green SHA

`7a94802ab45ab004fd85116c31039831f4c7ae7e`: all 4 app test shards, Build APK, Engine parity with the PC and the PC harness
are green (b96caec was green too). Locally: `:engine:test :ira-core:cleanTest :ira-core:test :engine:jacocoTestCoverageVerification`
passes. `git grep -nE "eyJ0eXAi|1114010050"` shows only CLAUDE.md's line.

## Left undone (on purpose or for later)

- The real phone figures are not known until the next session. Check Diagnostics → Order speed after 09:30.
- Another arm's capped entry (Liquidity, ORB, Pine and the rest) is a would-be record in the cap's log with its price, not a
  trade with its own exits. Only R51 / R72 / R52 / Solo 2.0 (PassedArmsHost) run would-be trades to an exit.
- Counting is first in, first out per option. When Boss holds the same option an arm bought, an arm's sell can be matched to
  Boss's older lot, so the arm's entry stays counted. That errs on the safe side (one refusal too many).
- The live ledger counts the bots' live entries sent by this process today. A position carried overnight is not counted, and
  Boss's own sell of the same option frees one of the bots' places.
- Would-be trades count in their arm's forward-test bar, as the arm's rule record would. A capped arm could still switch
  itself off on would-be results.
- "MCX paper arms 10.5 s" and "watch pass max 30.1 s" get the same write-behind gain (MCX paper orders go through the same
  paper book). They were not studied separately.
- The paper loss limit, the day lock and Liquidity's own rules are unchanged.
