# Research loop scoreboard (Boss, 27 Oct 2026: "find the best strategy you can with max winrate")

Every round of the loop, one line. A strategy counts only if it passes every pre-registered gate, after real costs,
inside Boss's limits (-Rs 13k a month, -Rs 40k drawdown). Win rate is shown, but it never decides alone: a high win
rate with a losing mean is a loss. Earlier, related rounds (R117-R120) are listed for context.

| round | idea | trades (final) | win rate (all) | mean / trade (design / wf / final) | verdict |
|---|---|---|---|---|---|
| R117 | profit locks on FT3 / Hero | 478 / 847 | 16-35% | FT3 hold best (+Rs 1,805) | 0 of 12 |
| R119 | R118's six patterns | 860-888 | 3-59% | best: iron fly +47 a trade | 0 of 6 |
| R120 | size, futures, skew, twins, morning after | 176-1,450 | 18-59% | iron fly at 2 lots +52 / +504 / +171 | 0 of 5 (tail risk) |
| R121 | overnight short iron fly (15:15 -> 09:20); T2 = night before expiry only | 708 / 100 | 36% / 56% | T1 -129 / -498 / -539; T2 -17 / -96 / +344 | 0 of 2 (premium sits in the morning; T2 positive before costs only) |
| R122 | sell the opening premium: iron fly 09:20 -> 10:30; U1 normal days, U2 expiry days | 577 / 104 | 34% / 51% | U1 -230 / -260 / -355; U2 -238 / -69 / +127 | 0 of 2 (no morning premium on normal days; expiry-day one only in 2026) |
| R123 | expiry-day naked ATM straddle 09:30 with an INDEX stop (V1 1.0 / V2 1.5 straddles) | 108 / 108 | 44% / 59% | V1 +323 / +353 / +211; V2 +277 / +668 / +273 | 0 of 2 - BEST SO FAR: positive in all periods after costs; fails best-days, twins, limits (pooled 5 indices), BH |
| R124 | R123's frozen rule on NORMAL days (out-of-sample check) | 796 / 796 | 63% / 64% | W1 +34 / +201 / -159; W2 +11 / +219 / -106 | 0 of 2 - high win rate, fat tail; R123's edge is expiry-only |
