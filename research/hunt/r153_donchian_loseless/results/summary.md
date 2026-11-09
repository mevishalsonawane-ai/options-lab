# R153: combinations to make the Donchian arms lose less (7 Nov 2026)

## For Boss, in short

**No combination passed (0 of 24). Most do cut the losses, but they cut the profit by more, and random choices of the
same size cut the losses just as much.** Nothing changes in the app.

What was tried, on R72 and R51, at 1, 2 and 3 lots (Rs amounts for the whole position, after charges):
- **C1** -Rs 2,000 stop + once a trade is +Rs 2,000, lock +Rs 1,000 (the winner keeps running);
- **C2** stop new trades for the day once the day's closed trades are -Rs 4,000;
- **C3** C1 + C2;
- **C4** new trades only until 10:00 + C1.

Apr - Oct 2026, 133 sessions. "Losing days" = the total of all red days (smaller is better):

| | lots | today: net / losing days / worst day | best "lose less" combination | its net / losing days / worst day |
|---|---|---|---|---|
| R72 | 1 | Rs 1,71,366 / -Rs 3,89,644 / -Rs 22,571 | C4 (10:00 + stop + lock) | Rs 77,323 / -Rs 1,57,539 / -Rs 10,585 |
| R72 | 3 | Rs 6,25,048 / -Rs 11,20,275 / -Rs 66,325 | C4 | Rs 80,827 / -Rs 2,67,686 / -Rs 13,024 |
| R51 | 1 | Rs 1,34,743 / -Rs 3,95,932 / -Rs 21,578 | C4 | Rs 82,673 / -Rs 2,22,622 / -Rs 9,077 |
| R51 | 3 | Rs 5,57,695 / -Rs 11,07,670 / -Rs 63,776 | C3 (stop + lock + day cap) | Rs 92,304 / -Rs 5,59,297 / -Rs 11,412 |

- **So the losses do get much smaller** (often halved or better, worst day -Rs 9k to -13k instead of -Rs 21k to -66k),
  **but the profit falls 40-90%**, and in R51 at 3 lots C4 even ends slightly negative (-Rs 5,533).
- **Why it fails the "better than chance" test:** C4 takes about a third of the trades (369 instead of 1,202 on R72).
  Throwing away the same number of trades at random cuts the losses about as much. The stop and the lock never beat
  random exit times (best p 0.19): selling a falling trade at a random earlier minute loses less than waiting for -2k.
- **The day cap (C2) alone** keeps most of the profit at 1 lot (R72 Rs 1,66,394 vs Rs 1,71,366) with a smaller worst day
  (-Rs 14,064), but the trades it blocks are on average the afternoon recoveries, so random blocks do better; on R72 in the
  walk-forward year it made the losing days bigger.
- **One lead (not a pass):** the 10:00 cut on its own beat random skips at 3 lots (p 0.025 R72, 0.033 R51). It was only
  tested together with the stop and lock, so it cannot be adopted from here; R112 found the 10:00 cut alone made less
  money than today. It could be its own pre-registered test if Boss wants.

**What already lowers losses in the app:** R151's picker (live since 6 Nov). On Apr - Oct 2026 (second look), R72's
picks at 1 lot made Rs 1,69,674 with losing days -Rs 2,33,631 and drawdown Rs 31,620, against the plain arm's
Rs 1,71,366 / -Rs 3,89,644 / Rs 61,884: about the same profit with 40% less on red days. On the picks, the day cap at 1 lot looked a little
better (R72 Rs 1,89,335, losing days -Rs 1,98,933), but that is a second look and the day cap failed its test on the
full arm; the other combinations cut the picks' profit (all in the report's second-look table).

## Method (honest notes)

- Pre-registered in `PREREG.md` (committed before any R153 number), the same engine and parity as R152 (R72 3,071 /
  Rs 2,16,929 and 1,202 / Rs 1,71,366; R51 + Supertrend 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743, reproduced).
- Signal source: the index (unchanged). Only the option's exit (stop, lock on the premium) and the day's entries change.
- Gates (R140's "lose less", Boss 31 Oct): in design, walk-forward and final, smaller losing days, drawdown, worst day and
  worst month than today at the same lots, net still > 0; beats 2,000 random twins on the losing days (p < 0.05; a
  combination must beat both its exit twin and its skip twin); BH q <= 0.10 with m = 890; >= 40 final trades.
- The final period was read once by R153 but has been read by many earlier studies. The post window (14 - 19 Oct,
  4 sessions) is in the tables and never gated.
