# R152: sell the Donchian arms at +Rs 2,000 or -Rs 2,000 (7 Nov 2026)

## For Boss, in short

**No. Selling at +Rs 2,000 / -Rs 2,000 makes both Donchian arms worse, at 1, 2 and 3 lots. Keep today's exits.**
All 18 tests failed the gates. Nothing changes in the app.

Apr - Oct 2026 (the test period, 133 sessions), whole position, after all charges and slippage:

| arm | lots | today's rules | +2k / -2k | +2k only | -2k only |
|---|---|---|---|---|---|
| R72 Breadth Donchian | 1 | **Rs 1,71,366** | -Rs 39,539 | -Rs 22,409 | Rs 1,37,432 |
| | 2 | **Rs 3,98,405** | -Rs 2,01,824 | -Rs 1,53,493 | Rs 2,30,871 |
| | 3 | **Rs 6,25,048** | -Rs 3,54,878 | -Rs 3,02,389 | Rs 1,71,399 |
| R51 Donchian 20 | 1 | **Rs 1,34,743** | -Rs 90,851 | -Rs 64,922 | Rs 91,538 |
| | 2 | **Rs 3,46,548** | -Rs 2,07,174 | -Rs 1,62,059 | Rs 1,40,376 |
| | 3 | **Rs 5,57,695** | -Rs 4,66,992 | -Rs 3,65,237 | Rs 1,44,937 |

(R72: 1,202 trades today; R51: 1,670. The earlier 16 months, Nov 2024 - Mar 2026, say the same: every rule makes less
than today's rules at every lot size.)

**Why it fails.** These arms win only about 46% of trades. They make their money on a few big moves that run far past
Rs 2,000; a +Rs 2,000 cap cuts exactly those. With +2k / -2k the wins and losses are about the same size, so after charges the arm
needs about 47% winners to break even at 1 lot and 50-51% at 2-3 lots, and it gets 44-47%. A +2k cap alone lifts the win rate to 56-64% at 2-3 lots,
but the losers stay big (the -15% stop), so it needs 58-67% to break even.

**The -Rs 2,000 stop alone** is the only one that stays in profit. It does cut the worst day (R72, 3 lots: -Rs 32,476
instead of -Rs 66,325), but it gives up much more profit than it saves (R72, 3 lots: Rs 1.71 lakh instead of Rs 6.25
lakh). It fails the "better than today" gate everywhere.

**With R151's pickers** (only the trades the app's pickers take, Apr - Oct 2026, a second look): the same answer. R72
picks at 1 lot: today Rs 1,69,674, +2k / -2k Rs 33,979; at 3 lots: Rs 5,64,570 vs -Rs 1,67,722. R51 picks at 1 lot:
Rs 1,42,884 vs -Rs 10,763.

**One side note for Boss (not part of the test).** In the app, a break the picker skips still holds its side for as long
as the trade would have lasted (the safe rule chosen on 6 Nov). On Apr - Oct 2026 that leaves R72's picker with 603 trades
and Rs 1,69,674, against R151's research figure of 652 trades and Rs 2,19,057 (R51: 653 / Rs 1,42,884 against
693 / Rs 1,59,040). The drawdown is still about half the plain arm's (R72 Rs 31,620 vs Rs 61,884). This is described, not
tested; it may be worth its own study.

## Method (honest notes)

- Pre-registered in `PREREG.md` (committed before any R152 number). R106's harness and engine; R72's and R51's trades
  reproduce their published figures exactly at 1 lot (R72 3,071 / Rs 2,16,929 design and 1,202 / Rs 1,71,366 final; R51
  with Supertrend 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743; R106's older R51 4,498 / Rs 9,998 also reproduced).
- Signal source: the index (unchanged). Only the option's exit changes: a limit sell at +Rs 2,000 net and / or a stop at
  -Rs 2,000 net on the premium, for the whole position. The arm's own -15% stop stays; the nearer stop acts (Boss's
  "whichever comes first"). At 2-3 lots the Rs 2,000 stop is the nearer one on 95-99% of trades; at 1 lot on 63-65%.
- Today's rules (B0) were re-priced at 2 and 3 lots with the same exits, real charges of the whole quantity and slippage
  from the size of the order. Every rule is compared with B0 at its own lots.
- Gates (R106's): net > 0 in design, walk-forward and final; better than today's rules; beats 500 random-exit twins
  with Benjamini-Hochberg q <= 0.10 (m = 866: R152's 18 + 848 earlier tests); >= 40 final trades. The final period was read
  once by R152, but Apr - Oct 2026 has been read by many earlier studies (weak evidence either way; here the result is
  one-sided).
- The post window (14 - 19 Oct, 4 sessions, 25-52 trades) is reported in the tables and never gated.
