# R144c part B: a hindsight sweep for skip rules (Boss, 1 Nov: "Can we find any way to skip this trades")

Written 1 Nov 2026, after R144c part A and before any part-B number. Research only; nothing in the app changes.

**Honest frame, said first:** part A already read the final months and the unseen two years. Every period is now seen,
so **nothing found here can pass as a tested rule.** Part B only asks which simple skip rules would have helped in EVERY
period, and whether the best of them beats what chance alone produces when this many rules are tried. Anything that
survives is a lead for a forward (shadow) log on new days, never a switch.

## Arms and candidates

FT3, Hero Rs 1,000 ticket, Night, VIX divergence (Expiry Hero and Flow stay too small). Periods: unseen, design, final.
For each arm, every one of these single skip rules (cuts from the design quintiles, as part A):
- each numeric feature of part A: skip the lowest fifth, the lowest two fifths, the highest fifth, the highest two fifths;
- each OI-price quadrant;
- each side (calls / puts) and each index;
- VIX divergence: expiry days; Night: its own close location, day move and OI build-up z (each by fifths as above);
- FT3 and the Hero ticket: monthly-expiry tickets (an index whose nearest expiry is its monthly one).

## "Held in every period"

In each of unseen, design and final: the skipped trades' net is below 0, the sum of losing days falls, the worst month is
no worse, and the rule skips 5-50% of the trades (>= 5 trades) in that period.

## Chance check (the whole sweep at once)

For each holding rule, its saving = how much the sum of losing days improves in its weakest period (as a share of that
period's losing-day sum). The max-statistic check: 1,000 times, give every candidate rule random skips of the same size
in each period and record the best weakest-period saving among the random rules that "held". A rule's family-wise p =
the share of those 1,000 random sweeps whose best beats it. Leads: family-wise p <= 0.10. Every raw value goes to
`results/sweep.json`.
