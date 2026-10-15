# R61-N pre-registration: does positive / negative news before the trade help Solo pick its trades?

Written 15 Oct 2026, after R61's main study (`PREREG.md`, results in `HUNT_R61_SOLO_3.md`) and **before any news data
was scored or joined to a trade** (one June 2025 sitemap was opened only to see its format). Boss, 15 Oct: "Also check
if there is any positive/negative news which could help solo." Research only; Solo stays paper only, forever.

What we already know: h33 (`HUNT_H33.md`, 3,072 variants, 59,189 timed headlines 2020-2026) found that trading
INTRADAY headlines loses (walk-forward -Rs 1.02 lakh): the price moves 1-4 minutes BEFORE the headline. R61-N asks a
different question: is the tone of the news that is already out before Solo's signal (overnight and pre-signal) useful
CONTEXT for take / skip among A2's candidates? It never creates a trade or picks a side.

## 1. Data

- Moneycontrol monthly post sitemaps (`https://www.moneycontrol.com/news/sitemap/sitemap-post-YYYY-MM.xml`),
  Oct 2024 - Oct 2026, fetched politely (one request every 3 s, plain research user agent, no cookies), as h33 did.
  Each row: the article URL and its `<lastmod>` time (IST). Raw files stay outside git and are deleted at the end.
  (h33's Economic Times sitemap index no longer answers, so ET is not used.)
- Only URLs under `/news/business/` (markets, economy, stocks, companies, IPO, earnings) are kept.
- Headline text = the URL slug: the last path part without `.html`, its trailing article id removed, hyphens read as
  spaces.
- Time = `lastmod`. It can be LATER than first publication (an edit), never earlier, so using it can only make a
  headline look later than it was: no look-ahead, at the cost of dropping some early headlines from a window.

## 2. Tone and tags: the phone's own reader

`News.tone` and `News.tag` (`android/ira-core/.../News.kt`) ported to Python unchanged (same word lists, weights,
phrase-first matching, negation one or two words back, score / 2 clipped to -1..+1), with one stated adaptation: slugs
have no hyphens inside words, so the hyphenated list entries ("all-time high", "sell-off") are also matched with a
space ("all time high", "sell off"). A parity test checks the port against the Kotlin word lists.

A headline counts for an index when `News.tag` gives that index; NIFTY-tagged ("market", "stocks", "sensex", "fii"...)
headlines count for all four indices (MIDCPNIFTY has no tag of its own).

## 3. Features (known at the signal minute)

For a candidate on index U with signal minute t on day d, the window is (previous NSE session 15:30, d at t]:
- `news_tone` = mean tone of the window's headlines that count for U, signed by the candidate's side (x +1 call, x -1
  put); 0 when there are none;
- `news_count` = log(1 + number of those headlines).

## 4. Variants (one BH family of 2), everything else exactly as R61 section 5

| id | model | inputs |
|---|---|---|
| N1 | logistic C 0.1 | A2's 29 + `news_tone` + `news_count` |
| N2 | logistic C 0.1 | A2's 29 + `news_tone` |

Comparator: V1 (A2 refitted; 128 final trades, +Rs 99,172). Same candidates S1-S4, held-contract labels, purged
walk-forward with monthly refits and 1-session embargo, take rule, 500 random-entry twins (seed 61), gates.

## 5. Reports

- Walk-forward AUC change vs V1 (design Jul 2025 - Mar 2026, and final).
- Trades, net, p, q, max DD, top-5, 2x costs, per index, without MIDCPNIFTY.
- Descriptive (not gated): correlation of the pre-open tone (window to 09:15) with the index's open-to-close return,
  design days only, with its sample size.

## 6. Verdict rule

A variant "helps Solo" only if it passes the R47-R60 gates AND beats V1 on the same final days on all four checks of
`PREREG.md` section 8 (net, AUC, paired-day bootstrap p < 0.10, net without MIDCPNIFTY). Otherwise news stays
context for Jarvis's explanations only.

## 7. Honest warning before any result

The final window (1 Apr - 14 Oct 2026) has now been read by R57 and R61 for these same candidates: this is its third
use, so a final "win" would be weak evidence. The design walk-forward is the cleaner read. Live paper from 15 Oct 2026
is the only unseen data.
