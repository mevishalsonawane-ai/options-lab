# ntfy alerts: an external signal, traded on the option (25 Oct 2026)

**Signal source: an external message** posted to Boss's ntfy topic (Boss's own IraThinker sender, or anyone who knows the
topic). The app does not compute the signal; it reads it and buys the option the message names, or the index option the
strike rule picks. **This is not a researched or proven strategy**: no walk-forward test, no holdout, no Benjamini-Hochberg
correction has been run on it, and none can be until the messages themselves are recorded for a while. Jarvis and every
screen say so. It replaces the removed Telegram calls arm (whose signal was also an external message).

## What the app does with a message (the rules, not evidence)

- Read once: each ntfy message id is saved (encrypted) before anything is decided.
- Not traded: older than 5 minutes (the default), outside 09:15 to the square-off, a holiday or a weekend, ambiguous text,
  futures, MCX, crypto, BANKEX, test signals.
- A buy: paper, at most 1% over the price; 1 lot unless the message asks for more, never over Boss's cap (5 at most).
- The stop always rests: the message's, else 20% under the fill. Target: the message's, else 40% over the fill.
  A JSON signal's stop and target1 are the underlying's levels and close the trade when the underlying crosses them.
- At most 3 trades a day by default; square-off 15:15, or 15:05 on the option's expiry day.

## How it could become evidence

Its Alerts list keeps each message's time, words and decision, and its paper book keeps every trade after charges. After
enough trades (40 or more) its paper record can be compared with random entries at the same times, as the Telegram tester
did. Until then it is Boss's choice to run it, on paper first.
