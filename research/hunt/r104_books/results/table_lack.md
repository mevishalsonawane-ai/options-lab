| rule | book | what is missing |
|---|---|---|
| `book-wyckoff-01` | Wyckoff | index-futures tick prints exist only in the phone's recorder since 12 Oct 2026 (private repo); ~3 months needed |
| `book-wyckoff-03` | Wyckoff | index only; needs index-futures minute volume, which exists only from 29 Jul 2026 (57 sessions, too few for design + holdout) |
| `book-ozenbas-03` | Ozenbas, Pagano, Schwartz and Weber | no historical bid/ask for index options (one snapshot, 6 Oct 2026) |
| `book-ozenbas-06` | Ozenbas, Pagano, Schwartz and Weber | no option book-depth history |
| `book-ozenbas-07` | Ozenbas, Pagano, Schwartz and Weber | needs tick-by-tick book and queue position |
| `book-bennett-06` | Colin Bennett | next-expiry ATM chain is mostly missing from the rolling lake |
| `book-bennett-07` | Colin Bennett | needs two expiries' chains on event days |
| `book-bennett-08` | Colin Bennett | matched-maturity IVs need a second expiry; with the nearest only, a 10-session vol position has to roll weekly, which the book's trade is not |
| `book-smith-05` | David Graeme Smith | no results / AGM calendar for F&O stocks |
| `book-atm-01` | The Advanced Trading Methods book | multi-lot exit fills need book depth |
| `book-atm-02` | The Advanced Trading Methods book | index-futures minutes only from 29 Jul 2026 (57 sessions) |
| `book-porwal-10` | Nikhil Porwal | index rule needs futures minute volume (57 sessions only); the price part (fade the day-high break) is covered by R86 (6 of 6 lost) and R91 |
| `book-porwal-11` | Nikhil Porwal | index rule needs futures minute volume (57 sessions only); the price part (day-low reclaim) is R87's held fact, below option cost |
| `book-fmjavaheri-01` | Alireza Javaheri | 10% OTM monthly strikes are outside the lake's ATM +-10; bhavcopy in the lake is a weekly sample, closes only |
| `book-fmjavaheri-02` | Alireza Javaheri | needs the next-monthly chain and far legs (see javaheri-01) |
| `book-fmaqf-01` | Härdle, Kleinow and Stahl | implied density needs every strike on the entry day; the lake has ATM +-10 intraday and a weekly bhavcopy sample |
| `book-fmaqf-02` | Härdle, Kleinow and Stahl | as fmaqf-01 |
| `book-fmlyuu-01` | Yuh-Dauh Lyuu | no minute ETF/basket prices, dividend schedule or futures quotes |
| `book-fmbjork-03` | Tomas Björk | needs synchronous bid/ask on three legs |
| `book-fmcapinski-01` | Capinski and Zastawniak | needs executable bid/ask; last prints in the lake are often stale (R103) |
| `book-fmcapinski-02` | Capinski and Zastawniak | needs executable bid/ask |
| `book-fmbinomial-01` | van der Hoek and Elliott | needs simultaneous bid/ask |
| `book-fmbinomial-02` | van der Hoek and Elliott | needs simultaneous bid/ask |
| `book-fmhughston-01` | Hughston and Hunter | needs executable bid/ask |
| `book-fmmusiela-01` | Musiela and Rutkowski | needs synchronous bid/ask for all legs |
| `book-fmmusiela-04` | Musiela and Rutkowski | no intraday USDINR or dated import-duty history |
