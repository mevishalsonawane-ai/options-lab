# Research sources checked — 14 November 2026

This is a targeted primary-source review, not a claim to have searched the whole
internet or replicated each paper. Published findings in other assets do not
establish a65% NIFTY options win rate. Abstract-only and blocked sources are
identified. No external paper's performance is reported as our own backtest.

| Source | What it contributes | Scope and access limit |
|---|---|---|
| [Osler, Support for Resistance](https://www.newyorkfed.org/research/epr/00v06n2/0007osle.html) | Tests whether quoted support/resistance predicts trend interruptions; effectiveness varies by firm/currency | Foreign exchange; landing-page findings checked, not evidence of Indian option profitability |
| [Cont, Kukanov, Stoikov, Price Impact of Order Book Events](https://arxiv.org/abs/1011.6402) | Distinguishes observed best-quote order-flow imbalance from noisier volume measures | US equities; full retrieved text reviewed. Contemporaneous impact is not automatically a tradable forward edge |
| [Bailey and Lopez de Prado, Deflated Sharpe Ratio](https://davidhbailey.com/dhbpapers/deflated-sharpe.pdf) | Selection and non-normality can inflate reported performance when many strategies are tried | Retrieved author PDF. We apply block uncertainty/BH, not claim a complete DSR or correction for unknown historical search |
| [Lo, Mamaysky, Wang, Foundations of Technical Analysis](https://www.nber.org/papers/w7613) | Formal pattern definitions and statistical testing replace discretionary chart stories | Research abstract; historical US daily stocks, not intraday NIFTY options |
| [Chung and Bellotti, Support/Resistance Levels](https://arxiv.org/abs/2101.07410) | Bounce frequency and decay motivate explicit causal levels | Abstract reviewed; new detector is a simpler hypothesis, not a reproduction of their algorithm |
| [Pan and Poteshman, Option Volume Information](https://www.nber.org/papers/w10925) | Some option trading information can be predictive | Search-indexed abstract; direct landing-page access403. Our aggregate volume/OI lacks their detailed trade classifications; not a replication |
| [Gao et al., Market Intraday Momentum](https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2440866) | Time-of-day patterns warrant asset-specific testing | Abstract/search evidence only; not implemented as a new late-session strategy in this registered batch |
| [Deep Learning for Options Trading](https://arxiv.org/abs/2407.21791) | End-to-end portfolio learning is a separate possible approach | Abstract page checked; no claim to reproduce training, market or reported results here |
| [NSE Option Chain](https://www.nseindia.com/option-chain) | Distinct OI, volume, IV and bid/ask fields; IV is a derived measure | Documentation only, no live quotes scraped |
| [NSE Greek display](https://www.nseindia.com/market-data/option-greek) | Delta exposure is different from trade-direction volume | Documentation checked. Our Greeks use reported IV, scheduled expiry and a fixed6.5% rate assumption, not observed exchange Greeks |
| [NSE STT schedule](https://www.nseindia.com/static/products-services/equity-derivatives-securities-transaction-tax) and [FA73061](https://nsearchives.nseindia.com/content/circulars/FA73061.pdf) | Dated charges and inclusive exchange/IPFT costs | Reuse the previously checked dated research cost engine; no silent current-rate substitution |

Search also surfaced recent NIFTY PPO and Greeks-aware labeling papers.
Their indexed abstracts are discovery leads, not verified winning strategies;
full text/source/data were not reproduced. One Greeks-aware abstract explicitly
reports negative absolute Sharpe after costs. Neither was adopted as a validated
65% strategy. Paper discovery does not establish executable quotes or data access.

## Existing repository evidence used

- R105D: actual futures-volume confirmation failed on tested Donchian rules;
  only57 sessions of the recorded contract were available, often a far month.
- R133: CISD V2 + neural filter + index stop/lock showed71% on35 later-period
  trades, too few to pass its research gate and on previously seen history.
- R165:78 delta/strike/Greek/proxy experiments did not meet improvement gates.
- R169/R170:207/576 ORB enhancement tests failed. They include support/resistance,
  false-break and volume ideas; this batch is a new specified combination, not
  evidence that those old failures can be ignored.
- R171:72 liquidity gates failed; measured spread assumptions materially change
  apparent profitability. OHLC-derived spreads remain estimates.
- audit_v3: trained MLP/CNN-GRU underlying-direction forecasts stayed near chance;
  all-data weights cannot create an unseen historical prediction.

None of these earlier results is newly independently reproduced here. The new
batch tests only its declared40 unique NIFTY rule/settings combinations, not
all mathematical strategies, all instruments or all internet claims.
