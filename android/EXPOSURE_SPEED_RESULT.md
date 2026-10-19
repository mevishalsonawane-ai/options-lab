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

FILLED IN FROM CI

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
- R51 and R72 refusals become **would-be trades** in their own book (`shadow = cap`), run by their own exits at the prices
  seen, with estimated charges. Their records stay comparable.

## No duplicate Donchian contracts

R72 Breadth Donchian no longer buys an option R51 Donchian 20 holds, and the other way round. R51 decides first on a bar both
break. The second arm's trade is a **would-be trade** (`shadow = twin`): no paper order, its own exits at the prices seen,
estimated charges, never mirrored live and no notice. It is labelled in its row and in Jarvis's words. The R72-vs-R51
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

## Final green SHA

FILLED IN FROM CI

## Left undone

FILLED IN
