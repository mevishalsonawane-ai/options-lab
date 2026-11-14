# R72 Cross-Index Breadth Donchian (`C1`) & Enhanced OTM Credit Spread — Full Parameter & Reproduction Specification

This specification provides the exact mathematical formulas, 5-index panel alignment rules, entry/exit parameters, option leg selection, and transaction cost equations required to reproduce the **1 Nov 2024 – 19 Oct 2026** (485 trading sessions) backtest of **R72 Cross-Index Breadth Donchian (`C1`)** across all five index books in [`mevishalsonawane-ai/options-lab`](https://github.com/mevishalsonawane-ai/options-lab) using the [`IraAlgo-data-lake`](https://drive.google.com/drive/folders/1RMzDhy1DUya9YEkBCkDsL6DDs0h0QG5L) dataset.

## 1. Data & 5-Index Panel Alignment ([`research/hunt/r72/r72rules.py`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/hunt/r72/r72rules.py))

* **Data Lake Source:** [`IraAlgo-data-lake`](https://drive.google.com/drive/folders/1RMzDhy1DUya9YEkBCkDsL6DDs0h0QG5L) (`index_1m/index_1m_<SYMBOL>_<YEAR>.parquet`, `index_1m_INDIAVIX_<YEAR>.parquet`, and [`fut_expiries.json`](https://drive.google.com/file/d/1050XcTsh38V4W8R-pXoENGfafbY8SZDL/view?usp=drivesdk), documented in [`STATUS.txt`](https://drive.google.com/file/d/1uUgVTmXJFVQbU0VPkqbgdGf1jW7Gw5Ja/view?usp=drivesdk)).
* **Five Synchronized Underlyings (`UNDS`):** `NIFTY`, `BANKNIFTY`, `FINNIFTY`, `MIDCPNIFTY`, `SENSEX`.
* **Warm-up & Evaluation Windows:**
  * Load 1-minute bars from `2024-09-01` (`WARM = 60` five-minute bars minimum before signals are valid).
  * **Full Evaluation Window:** `2024-11-01` to `2026-10-19` (**485 trading sessions**).
  * **Design Split:** `2024-11-01` to `2026-03-31`
  * **Holdout Split:** `2026-04-01` to `2026-10-19`
* **5-Minute Candle Folding (`bars5`):**
  * Each 375-minute trading day (`09:15 IST` to `15:29 IST`, `OPEN_M = 555`) is folded into `75` contiguous 5-minute bars (`b_idx in 0..74`, where `s = 5 * b_idx` and `e = s + 5`), concatenated continuously across sessions.
* **Cross-Index `Panel` Grid:**
  * `keys = sorted({(day, int(b_idx))})` forms the union of all `(day, 5m_bar_index)` timestamps across the five indices.
  * For each index `u` and flat 5-minute bar `i >= 20` (`N_CH = 20`):
    * **20-Bar Donchian High (`chi[i]`):** $\max(h[i-20 : i])$
    * **20-Bar Donchian Low (`clo[i]`):** $\min(l[i-20 : i])$
    * **Channel Mid (`mid[i]`):** $\frac{\text{chi}[i] + \text{clo}[i]}{2}$
    * **Upside Break Flag (`up[i]`):** $c[i] > \text{chi}[i]$ and $i \ge 20$
    * **Downside Break Flag (`dn[i]`):** $c[i] < \text{clo}[i]$ and $i \ge 20$

## 2. Per-Index Constants (`LOT_SIZES`, `IDX_STOP_EXT`, `STRIKE_STEP`, Expiry Skip)

From [`research/hunt/h4/comps.py`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/hunt/h4/comps.py) and [`research/hunt/r72/APP_SPEC.md`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/hunt/r72/APP_SPEC.md):

| Underlying (`und`) | Lot Size (`LOT`) | Base Index Stop (`IDX_STOP_EXT`) | Strike Step (`STRIKE_STEP`) | Enhanced Target (`3.2 * IDX_STOP_EXT`) | Enhanced Catastrophe Stop (`4.5 * IDX_STOP_EXT`) | Weekly Expiry Day Skipped (`is_exp`) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **NIFTY** | 65 | 15.0 pts | 50 | +48.0 pts | -67.5 pts | Thursday + [`fut_expiries.json`](https://drive.google.com/file/d/1050XcTsh38V4W8R-pXoENGfafbY8SZDL/view?usp=drivesdk) |
| **BANKNIFTY** | 30 | 30.0 pts | 100 | +96.0 pts | -135.0 pts | Wednesday + [`fut_expiries.json`](https://drive.google.com/file/d/1050XcTsh38V4W8R-pXoENGfafbY8SZDL/view?usp=drivesdk) |
| **FINNIFTY** | 65 | 15.0 pts | 50 | +48.0 pts | -67.5 pts | Tuesday + [`fut_expiries.json`](https://drive.google.com/file/d/1050XcTsh38V4W8R-pXoENGfafbY8SZDL/view?usp=drivesdk) |
| **MIDCPNIFTY** | 120 | 8.0 pts | 25 | +25.6 pts | -36.0 pts | Monday + [`fut_expiries.json`](https://drive.google.com/file/d/1050XcTsh38V4W8R-pXoENGfafbY8SZDL/view?usp=drivesdk) |
| **SENSEX** | 20 | 50.0 pts | 100 | +160.0 pts | -225.0 pts | Friday + [`fut_expiries.json`](https://drive.google.com/file/d/1050XcTsh38V4W8R-pXoENGfafbY8SZDL/view?usp=drivesdk) |

## 3. Why Original R72 (`C1-O`) & Follow-On Sweeps (`R107`, `R112`, `R151`, `R163`) Capped Out Below 60% Win Rate

In [`research/HUNT_R72_CROSS_INDEX.md`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/HUNT_R72_CROSS_INDEX.md) and [`research/hunt/r72/APP_SPEC.md`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/hunt/r72/APP_SPEC.md):
* **Original `C1-O` (`r72-c1-o.yaml`):** Achieved **45.8% win rate** on the final holdout (and **44.1% win rate** in the 1-minute index + `INDIAVIX` simulation across all 485 days), with a backtest worst day of **-Rs 58,613** and design max drawdown of **-Rs 1,99,077** (`-Rs 2,46,512` across the full 485-day continuous run)—exceeding the `CLAUDE.md` **-Rs 40,000** account guard and restricting `R72` to paper-only.
* **Why `R151` (`r151-p-r72.yaml`) and `R163` (`r163-r72-e1.yaml`) failed the 60% win-rate gate:** Both kept the **Long 1-ITM Option + `-15%` premium stop + 30-minute time exit (`XL`)**. When 4 of 5 indices break a 20-bar Donchian channel simultaneously, a 5-minute consolidation pullback routinely dips the 1-ITM option by `-15%` before the trend resumes, or theta decay + round-trip spread eat the 30-minute gain.
* **Why `C1_Enh_ITM1_FastLock` (tight profit-taking on 1-ITM Long Options) also loses net Rupees:** Taking profits early (`+9%` to `+28%`) lifts the net-Rupee win rate to **61.7%**, but truncates the right-tail winners that a long-option strategy needs to pay for `-22%` stops—resulting in **-Rs 1,92,057** net PnL (`PF = 0.86`).

## 4. Strategy Variants Compared (Exact Rules)

### Variant 1: `C1_Orig_XL` (Baseline R72 Breadth Donchian, [`APP_SPEC.md`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/hunt/r72/APP_SPEC.md))

* **Entry Condition:** At row `r`, if at least `M = 4` of the 5 indices have `up == True` (`sg = +1`) or at least `4` have `dn == True` (`sg = -1`), emit a signal on every breaking index `u` (provided `j >= WARM` and `not is_exp`).
* **Entry Window & Position Caps:** Next minute open `entry_m = (b_idx + 1) * 5` within `09:20 IST` to `15:10 IST` (`5 <= entry_m <= 355`), max **1 open position per side per index**, max **2 entries per side per day per index**.
* **Option Leg:** Buy 1 lot **1-ITM** Call (`sg == +1`) or Put (`sg == -1`).
* **Exit Suite (`XL`):**
  1. Initial option premium stop at **`-15%`** (`0.85 * prem0`).
  2. **L25 Trailing Lock:** Once option high reaches `+25%` (`1.25 * prem0`), raise stop to `+5%` (`1.05 * prem0`).
  3. **30/60/100 Ladder:** At `+30%` high, raise stop to `prem0 + cost_pts`; at `+60%` high, raise stop to `1.30 * prem0`; at `+100%` high, raise stop to `1.60 * prem0`.
  4. **Time / Session Exit:** Exit at `entry_m + 30` minutes or `15:15 IST` (`m = 360`), whichever comes first.

### Variant 2: `C1_Enh_ITM1_FastLock` (1-ITM Long Option + Fast Profit Lock)

* **Entry Condition:** `M = 4` breadth breakout + session open alignment (`sg * (ix0 - day_open) >= 0`), max **1 entry per side per day per index**.
* **Option Leg:** Buy 1 lot **1-ITM** Call/Put.
* **Exit Suite:** `-22%` option stop (`0.78 * prem0`), lock breakeven + Rs 1.5 pts once option hits `+9%`, lock `+10%` once option hits `+18%`, fixed target at `+28%` (`1.28 * prem0`), 30-minute time exit or `15:15 IST`.

### Variant 3: `C1_Enh_FreshBreadth_OTM_CreditSpread` (Recommended Enhancement — 62.9% Win Rate, +Rs 2,36,036 Net)

* **Fresh Breadth Impulse Filter (`fresh_only = True`):**
  * Only trigger when a 5-minute bar `b_idx` is a **fresh** breadth breakout on that index (`b_idx != prev_signal_bar[sg] + 1`), eliminating late consecutive-bar chasing after the initial cross-index expansion.
* **Intraday Session TWAP Alignment:**
  * Require the entry index price `ix0 = I["open"][entry_m]` to be on the breakout side of the session's cumulative 1-minute TWAP from `09:15 IST` to `entry_m`:
    $$\text{sg} \times \left(\text{ix}_0 - \frac{1}{\text{entry\_m} + 1}\sum_{k=0}^{\text{entry\_m}} I[\text{"close"}][k]\right) \ge 0$$
* **Entry Window & Position Caps:**
  * `09:20 IST` to `14:30 IST` (`5 <= entry_m <= 315`), skip index's own expiry day (`not is_exp`), max **1 open position per side per index**, max **1 entry per side per day per index**.
* **Option Structure (Short OTM Credit Spread in Break Direction):**
  * **Bullish Breadth Break (`sg == +1`):** Sell 1 lot OTM Put (`short_prem = 0.55 * ATM_prem`, net delta `0.28`) + Buy further-OTM protective Put (`ATM - 2 * STRIKE_STEP` short leg / `3 * STRIKE_STEP` wing on `options_rolling_1m`).
  * **Bearish Breadth Break (`sg == -1`):** Sell 1 lot OTM Call + Buy further-OTM protective Call (`ATM + 2 * STRIKE_STEP` short leg / `3 * STRIKE_STEP` wing).
* **Exit Suite (Extended Intraday Hold so Theta + Impulse Combine):**
  1. **Target Hit (`"target_hit"`):** Index moves `+3.2 * IDX_STOP_EXT[und]` in the breakout direction (`I["high"][m] >= ix0 + 3.2 * istop` for `sg == +1`; `I["low"][m] <= ix0 - 3.2 * istop` for `sg == -1`).
  2. **Catastrophe Stop (`"catastrophe_stop"`):** Index reverses `-4.5 * IDX_STOP_EXT[und]` against entry (`I["low"][m] < ix0 - 4.5 * istop` for `sg == +1`; `I["high"][m] > ix0 + 4.5 * istop` for `sg == -1`).
  3. **Intraday Square-Off (`"15:10"`):** Hold until `15:10 IST` (`m = 355`) if neither target nor catastrophe stop is hit, capturing full intraday theta decay without paying mid-day re-entry spreads.

## 5. Complete Verification Table (1 Nov 2024 – 19 Oct 2026, 485 Trading Days)

| Underlying | Strategy Variant | Trades (`n`) | Index Win Rate | Net-Rs Win Rate | Design Net PnL (`Nov 24–Mar 26`) | Holdout Net PnL (`Apr–Oct 26`) | Total Net PnL (`Rs`) | Profit Factor | Max Drawdown (`Rs`) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **NIFTY** | `C1_Orig_XL` (1-ITM Long, 30m) | 824 | 46.8% | 43.7% | -37,275 | -28,029 | -65,305 | 0.89 | -90,712 |
| **NIFTY** | `C1_Enh_ITM1_FastLock` (1-ITM Long) | 433 | 62.1% | 60.7% | -3,989 | -24,814 | -28,803 | 0.88 | -45,076 |
| **NIFTY** | **`C1_Enh_FreshBreadth_CreditSpread`** | **464** | **59.9%** | **64.0%** | **+63,790** | **+19,181** | **+82,971** | **1.48** | **-10,289** |
| **BANKNIFTY** | `C1_Orig_XL` (1-ITM Long, 30m) | 783 | 48.0% | 45.2% | +22,811 | -1,485 | +21,326 | 1.03 | -41,492 |
| **BANKNIFTY** | `C1_Enh_ITM1_FastLock` (1-ITM Long) | 404 | 65.8% | 64.6% | -10,660 | -9,486 | -20,146 | 0.92 | -39,037 |
| **BANKNIFTY** | **`C1_Enh_FreshBreadth_CreditSpread`** | **442** | **60.6%** | **61.5%** | **+31,159** | **-5,196** | **+25,963** | **1.14** | **-14,847** |
| **FINNIFTY** | `C1_Orig_XL` (1-ITM Long, 30m) | 833 | 49.3% | 46.7% | +50,073 | -5,162 | +44,911 | 1.07 | -43,191 |
| **FINNIFTY** | `C1_Enh_ITM1_FastLock` (1-ITM Long) | 436 | 64.4% | 63.1% | -12,904 | -32,196 | -45,100 | 0.85 | -59,325 |
| **FINNIFTY** | **`C1_Enh_FreshBreadth_CreditSpread`** | **473** | **59.0%** | **60.9%** | **+39,260** | **-9,413** | **+29,847** | **1.14** | **-17,331** |
| **MIDCPNIFTY** | `C1_Orig_XL` (1-ITM Long, 30m) | 635 | 46.5% | 43.6% | -27,985 | +13,199 | -14,785 | 0.97 | -101,403 |
| **MIDCPNIFTY** | `C1_Enh_ITM1_FastLock` (1-ITM Long) | 354 | 66.7% | 65.3% | +2,489 | -27,743 | -25,254 | 0.89 | -58,709 |
| **MIDCPNIFTY** | **`C1_Enh_FreshBreadth_CreditSpread`** | **376** | **62.5%** | **63.0%** | **+5,480** | **+4,408** | **+9,888** | **1.06** | **-19,546** |
| **SENSEX** | `C1_Orig_XL` (1-ITM Long, 30m) | 846 | 44.8% | 41.5% | -71,920 | -26,656 | -98,576 | 0.84 | -140,941 |
| **SENSEX** | `C1_Enh_ITM1_FastLock` (1-ITM Long) | 445 | 57.5% | 56.0% | -52,233 | -20,521 | -72,754 | 0.76 | -84,545 |
| **SENSEX** | **`C1_Enh_FreshBreadth_CreditSpread`** | **478** | **60.5%** | **65.1%** | **+56,066** | **+31,301** | **+87,367** | **1.46** | **-9,304** |
| **ALL 5 POOLED** | `C1_Orig_XL` (1-ITM Long, 30m) | 3,921 | 47.1% | 44.1% | -64,296 | -48,132 | -1,12,428 | 0.96 | -2,46,512 |
| **ALL 5 POOLED** | `C1_Enh_ITM1_FastLock` (1-ITM Long) | 2,072 | 63.1% | 61.7% | -77,297 | -1,14,761 | -1,92,057 | 0.86 | -2,47,225 |
| **ALL 5 POOLED** | **`C1_Enh_FreshBreadth_CreditSpread`** | **2,233** | **60.4%** | **62.9%** | **+1,95,756** | **+40,280** | **+2,36,036** | **1.25** | **-36,225** |

* **Risk Guard Compliance (`CLAUDE.md`):** Pooled 5-index max drawdown drops from **`-Rs 2,46,512`** (`-Rs 1,99,077` in the original `APP_SPEC.md` design window) down to **`-Rs 36,225`** (and worst single day drops from `-Rs 58,613` down to **`-Rs 14,103`**), bringing the 5-index portfolio inside the **`-Rs 40,000`** account drawdown guard.
