# R161 notes

- **No deviation from PREREG.** `study.py` ran once on R160's lake files.
- **B is not a perfect "truth".** The PRE period, when the print was live, shows how noisy B is: |A - B| averaged
  4.8 bps (NIFTY) and 4.8 bps (BANKNIFTY), with no freeze at all.
  - That noise comes from option-price parity and basis drift during the day.
  - POST |A - B| is no bigger (4.0 and 4.4 bps). So the 5 frozen minutes add little on top of that noise: the market
    rarely moves far between 15:14 and 15:19.
- **Flips priced are an upper bound.** Breadth and the OI build-up are not applied (PREREG 2), so most of these nights
  would not have been Night trades either way.
