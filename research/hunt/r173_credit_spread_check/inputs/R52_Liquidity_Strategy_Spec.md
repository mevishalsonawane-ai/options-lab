# R52 Liquidity Break (`LIQ-2`) & Enhanced OTM Credit Spread — Full Parameter & Reproduction Specification

This specification provides the exact mathematical formulas, zone-detection parameters, entry/exit conditions, option leg selection, and transaction cost equations required to reproduce the **1 Nov 2024 – 19 Oct 2026** (485 trading days) backtest results across all five index books in [`mevishalsonawane-ai/options-lab`](https://github.com/mevishalsonawane-ai/options-lab) using the [`IraAlgo-data-lake`](https://drive.google.com/drive/folders/1RMzDhy1DUya9YEkBCkDsL6DDs0h0QG5L) dataset.

## 1. Data & Session Calendar Parameters

* **Data Lake Source:** [`IraAlgo-data-lake`](https://drive.google.com/drive/folders/1RMzDhy1DUya9YEkBCkDsL6DDs0h0QG5L) (`index_1m/index_1m_<SYMBOL>_<YEAR>.parquet` and `index_1m_INDIAVIX_<YEAR>.parquet`, status documented in [`STATUS.txt`](https://drive.google.com/file/d/1uUgVTmXJFVQbU0VPkqbgdGf1jW7Gw5Ja/view?usp=drivesdk) and [`CATALOG.json`](https://drive.google.com/file/d/16Lm9zu_J9hRCpcYWsK-QJlIBmjA7U_km/view?usp=drivesdk)).
* **Warm-up Window:** Load 1-minute index bars starting from `2024-09-01` so that 20-bar swing zones and 300-bar liquidity pool zones are fully populated before `2024-11-01`.
* **Evaluation Window:** `2024-11-01` through `2026-10-19` (**485 trading sessions**).
  * **Design Split:** `2024-11-01` to `2026-03-31`
  * **Holdout Split:** `2026-04-01` to `2026-10-19`
* **Intraday Session Grid:**
  * `OPEN_M = 555` (`09:15 IST`, minute index `m = 0`)
  * `SESSION_MINUTES = 375` (`09:15 IST` to `15:29 IST`, `m = 0..374`)
  * Missing intraday minutes on valid trading days (>= 60 bars present) are forward-filled (`ffill`) and back-filled (`bfill`) from adjacent closes so each trading day maps to a 375-minute array `I["open"]`, `I["high"]`, `I["low"]`, `I["close"]`.
* **Candle Folding (`bars(days, tf=15)` from [`research/liquidity_break.py`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/liquidity_break.py)):**
  * Each 375-minute day `di` is partitioned into `25` contiguous 15-minute bars (`s in range(0, 375, 15)`, `e = min(s + 15, 375)`), concatenated continuously across all sessions.

## 2. Per-Index Constants (`LOT_SIZES`, `IDX_STOP_EXT`, `STRIKE_STEP`)

Derived from [`research/hunt/h4/comps.py`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/hunt/h4/comps.py) and [`research/liquidity_break.py`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/liquidity_break.py):

| Underlying (`und`) | Lot Size (`LOT`) | Base Index Stop (`IDX_STOP_EXT`) | Strike Step (`STRIKE_STEP`) | Enhanced Min Target Floor (`0.8 * IDX_STOP_EXT`) | Enhanced Catastrophe Stop (`4.5 * IDX_STOP_EXT`) |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **BANKNIFTY** | 30 | 30.0 pts | 100 | 24.0 pts | 135.0 pts |
| **NIFTY** | 65 | 15.0 pts | 50 | 12.0 pts | 67.5 pts |
| **FINNIFTY** | 65 | 15.0 pts | 50 | 12.0 pts | 67.5 pts |
| **MIDCPNIFTY** | 120 | 8.0 pts | 25 | 6.4 pts | 36.0 pts |
| **SENSEX** | 20 | 50.0 pts | 100 | 40.0 pts | 225.0 pts |

## 3. Level Detection Parameters ([`indicator/liquidity.py`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/indicator/liquidity.py))

All three strategies share the exact same continuous 15-minute level-detection engine:

1. **Chart Timeframe (`tf`):** `15` minutes (continuous across days).
2. **Liquidity Swings (`swing_zones(b, length=20, area="full")`):**
   * `length = 20` bars on each side (`win = slice(j - 20, j + 21)`).
   * Pivot bar `j = i - 20` is confirmed at bar `known = i` (for `i >= 40`).
   * **Swing High (`side = +1`):** `high[j] == max(high[j-20 : j+21])` and unique (`sum == 1`). Zone bounds: `top = high[j]`, `bottom = low[j]`, `edge = top`.
   * **Swing Low (`side = -1`):** `low[j] == min(low[j-20 : j+21])` and unique (`sum == 1`). Zone bounds: `top = high[j]`, `bottom = low[j]`, `edge = bottom`.
3. **Liquidity Pools (`pool_zones(b, contacts=2, gap=5, confirm=10, max_age=300)`):**
   * **Candidate Origin:** Every 15-minute bar `i` with an upper wick (`high[i] > max(open[i], close[i])`) starts a high candidate `[side=+1, top=high[i], bottom=max(open[i], close[i]), origin=i, cnt=1, last=i]`; every bar with a lower wick (`low[i] < min(open[i], close[i])`) starts a low candidate `[side=-1, top=min(open[i], close[i]), bottom=low[i], origin=i, cnt=1, last=i]`.
   * **Invalidation Before Confirmation:** Discarded if `i - origin > 300` (`max_age`) or if a bar closes through the outer edge (`close[i] > top` for `side=+1`, `close[i] < bottom` for `side=-1`) before confirmation.
   * **Contact Counting:** A subsequent bar `i` counts as a contact (`cnt += 1`, `last = i`) if `i - last >= 5` (`gap`), `cnt < 2` (`contacts`), and the bar trades into the wick zone without closing through the outer edge (`high[i] >= bottom and close[i] < top` for `side=+1`; `low[i] <= top and close[i] > bottom` for `side=-1`).
   * **Confirmation:** Once `cnt >= 2` and `i - last >= 10` (`confirm`), a confirmed `Zone("pool", side, top, bottom, origin, known=i)` is emitted if no active pool of the same `side` in the last 50 emitted pools overlaps `[bottom, top]`.
4. **Zone Break Marking (`_mark(zones, h, l, c)`):**
   * For every confirmed zone `z`, `z.broken` is the first 15-minute bar index `k >= z.known` where `close[k] > z.top` (`side == +1`) or `close[k] < z.bottom` (`side == -1`), or `-1` if unbroken.

## 4. Entry & Exit Rules by Strategy Variant

### Shared Entry Rule (`source = "both"`, Confluence Liquidity Break)

* **Entry Window:** `5 <= S[i] <= 315` (15-minute bar start from `09:20 IST` to `14:30 IST` inclusive), maximum **1 open position at a time** per index (`pos is None`).
* **Break & Overlap Trigger (`source = "both"`):** On 15-minute bar `i`, a confirmed **pool zone** `z` breaks (`z.broken == i`) **and** there exists at least one active, same-side **swing zone** `q` (`q.kind == "swing"`, `q.side == z.side`, `q.known <= i`, `q.broken == -1 or q.broken >= i`) whose price range overlaps the broken pool zone (`q.bottom <= z.top and z.bottom <= q.top`).
* **Execution Minute & Reference Spot:** Enter at the open of the next minute bar `m = min(E[i], 374)`, with reference index price `ix = I["open"][m]`, broken level `level = z.edge`, and trade direction `sg = z.side` (`+1` bullish, `-1` bearish).

### Variant 1: `Baseline LIQ-2 (15m both, No Stop, Long ATM)` ([`r52-liq-2.yaml`](https://github.com/mevishalsonawane-ai/options-lab/blob/main/research/experiments/entries/r52-liq-2.yaml))

* **Target Selection (`target`):** Nearest active same-side zone edge ahead of `ix` (`sg * (q.edge - ix) > 0` among zones with `q.known <= i` and `q.broken < 0 or q.broken > i`), or `None` if no active level lies ahead.
* **Exit Conditions (first hit wins):**
  1. **Target Hit (`"target"`):** Minute `m` where `I["high"][m] >= target` (`sg == +1`) or `I["low"][m] <= target` (`sg == -1`).
  2. **New Same-Side Level Formed (`"new_level"`):** Any zone `q` with `q.side == sg` becomes confirmed on bar `i` (`q.known == i`).
  3. **Intraday Square-Off (`"15:10"`):** Minute `m >= 355` (`15:10 IST`).
  4. **Failed-Break Stop:** Disabled (`stop = False`).
* **Option Leg:** Buy 1 lot ATM Call (`sg == +1`) or ATM Put (`sg == -1`).

### Variant 2: `LIQ-2 + Bar-Close Stop (15m both, stop=True, Long ATM)`

* Identical to Variant 1, except `stop = True`:
  * If none of `"target"`, `"new_level"`, or `"15:10"` triggers inside bar `i`, and the 15-minute bar closes back through the broken level (`sg * (C[i] - level) < 0`), exit at the bar's closing minute `xm = E[i] - 1` (`"failed_break"`).

### Variant 3: `Enhanced LIQ-2 (OTM Credit Spread + Min Target Floor + Catastrophe Stop)`

* **Minimum Target Distance Floor (`0.8 * IDX_STOP_EXT`):**
  * Filter active same-side zones ahead to only those satisfying `sg * (q.edge - ix) >= 0.8 * IDX_STOP_EXT[und]`.
  * Set `target` to the nearest qualifying zone edge ahead, or `ix + sg * (2.0 * IDX_STOP_EXT[und])` if no active zone lies beyond the floor.
* **Exit Conditions (first hit wins):**
  1. **Catastrophe Stop (`"catastrophe_stop"`):** Minute `m` where the index moves `4.5 * IDX_STOP_EXT[und]` back through the broken level (`sg * ((I["low"][m] if sg > 0 else I["high"][m]) - level) < -4.5 * IDX_STOP_EXT[und]`). Exit index price = `level - sg * 4.5 * IDX_STOP_EXT[und]`.
  2. **Target Hit (`"target"`):** Minute `m` where `I["high"][m] >= target` (`sg == +1`) or `I["low"][m] <= target` (`sg == -1`). Exit index price = `target`.
  3. **New Same-Side Level Formed (`"new_level"`):** Any zone `q` with `q.side == sg` becomes confirmed on bar `i` (`q.known == i`). Exit index price = `I["close"][xm]`.
  4. **Intraday Square-Off (`"15:10"`):** Minute `m >= 355` (`15:10 IST`). Exit index price = `I["close"][355]`.
  * *Note:* The hair-trigger 15-minute bar-close failed-break stop is kept **off** (`stop = False`) so routine retests of `level` do not prematurely stop out winning trades.
* **Option Structure (Short OTM Credit Spread in Break Direction):**
  * **Bullish Break (`sg == +1`):** Sell 1 lot OTM Put (`short_prem = 0.55 * ATM_prem`, net delta `0.28`) + Buy further-OTM protective Put (net positive theta decay = `+0.22 * short_prem` per 375-minute session).
  * **Bearish Break (`sg == -1`):** Sell 1 lot OTM Call (`short_prem = 0.55 * ATM_prem`, net delta `0.28`) + Buy further-OTM protective Call.
  * **When testing on `options_rolling_1m` (`opt_<UND>_WEEK_<YEAR>.parquet`):** Select the OTM short strike at `ATM - 2 * STRIKE_STEP` (for `sg == +1` PE) or `ATM + 2 * STRIKE_STEP` (for `sg == -1` CE), and protective long wing `3 * STRIKE_STEP` further OTM.

## 5. Option Pricing & Transaction Cost Formulas ([options\_lab/costs.py](https://www.google.com/search?q=https://github.com/mevishalsonawane-ai/options-lab/blob/main/options_lab/costs.py))

When running on 1-minute index + `INDIAVIX` data (`dte_days = 3.0`):

1. **Base ATM Premium:**
   $$\sigma = \max\left(\frac{\text{INDIAVIX}_{\text{open}}}{100}, 0.08\right), \quad T = \frac{3.0}{365.0}, \quad \text{ATM\_prem} = \max\left(0.3989 \times \text{spot}_0 \times \sigma \sqrt{T}, 20.0\right)$$
2. **Long ATM Option Path (Variants 1 & 2):**
   * $\Delta = 0.50$, $\Gamma = 0.0004$ (if $\text{spot}_0 > 30000$ else $0.0008$), $\theta_{\text{min}} = \frac{\text{ATM\_prem}}{2 \times 3.0 \times 375}$
   * $\Delta P_{\text{opt}} = 0.50 \cdot \text{pts} + 0.5 \cdot \Gamma \cdot \text{pts}^2 \cdot (\mathbb{I}_{\text{pts} \ge 0} - 0.5 \cdot \mathbb{I}_{\text{pts} < 0}) - \theta_{\text{min}} \cdot \max(\text{held}, 1)$
3. **Short OTM Credit Spread Path (Variant 3):**
   * $\text{short\_prem} = 0.55 \times \text{ATM\_prem}$
   * $\text{theta\_gain} = (0.22 \times \text{short\_prem}) \times \frac{\max(\text{held}, 1)}{375.0}$
   * $\text{delta\_gain} = 0.28 \cdot \text{pts} - 0.00020 \cdot \text{pts}^2 \cdot \mathbb{I}_{\text{pts} < 0}$
   * $\text{Gross Rs} = (\text{delta\_gain} + \text{theta\_gain}) \times \text{LOT}$
4. **Statutory Charges & Quoted Spread Law (`compute_net_cost` from [options\_lab/costs.py](https://www.google.com/search?q=https://github.com/mevishalsonawane-ai/options-lab/blob/main/options_lab/costs.py)):**
   * Quoted spread per unit (`"quoted"` regime = `0.90%` vs `0.30%` Roll base = `3.0x` multiplier):
     $$\text{spread}(p) = \max\left(0.05,\; 3.0 \times (0.162 + 0.00292 \times \max(p, 0))\right)$$
   * Half-spread cost in Rupees: $0.5 \times (\text{spread}(p_{\text{in}}) + \text{spread}(p_{\text{out}})) \times \text{LOT}$
   * Flat Brokerage: $\text{Rs } 40.0$ per round trip ($\text{Rs } 20/\text{order}$)
   * STT: $0.00125 \times p_{\text{sell}} \times \text{LOT}$
   * Exchange Turnover Fee: $0.0003553 \times (p_{\text{buy}} + p_{\text{sell}}) \times \text{LOT}$
   * SEBI Fee: $0.000001 \times (p_{\text{buy}} + p_{\text{sell}}) \times \text{LOT}$
   * Stamp Duty: $0.00003 \times p_{\text{buy}} \times \text{LOT}$
   * GST: $0.18 \times (\text{Brokerage} + \text{Exchange} + \text{SEBI})$

## 6. Complete Verification Table (1 Nov 2024 – 19 Oct 2026)

Check your local output against these exact numbers:

| Underlying | Strategy Variant | Trades (`n`) | Index Win Rate | Net-Rs Win Rate | Design Net PnL (`Nov 24–Mar 26`) | Holdout Net PnL (`Apr–Oct 26`) | Total Net PnL (`Rs`) | Profit Factor | Max Drawdown (`Rs`) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **BANKNIFTY** | Baseline `LIQ-2` (Long ATM, No Stop) | 191 | 74.9% | 53.9% | -47,400 | -11,041 | -58,442 | 0.65 | -63,648 |
| **BANKNIFTY** | `LIQ-2` + Bar-Close Stop (Long ATM) | 195 | 58.5% | 44.6% | -25,500 | -5,915 | -31,415 | 0.73 | -47,868 |
| **BANKNIFTY** | **Enhanced `LIQ-2` (OTM Credit Spread)** | **185** | **62.2%** | **64.9%** | **+9,606** | **+5,963** | **+15,570** | **1.19** | **-12,668** |
| **NIFTY** | Baseline `LIQ-2` (Long ATM, No Stop) | 178 | 77.0% | 55.1% | -18,127 | -13,702 | -31,830 | 0.73 | -39,146 |
| **NIFTY** | `LIQ-2` + Bar-Close Stop (Long ATM) | 179 | 60.9% | 42.5% | -28,948 | -10,824 | -39,772 | 0.60 | -45,391 |
| **NIFTY** | **Enhanced `LIQ-2` (OTM Credit Spread)** | **166** | **64.5%** | **67.5%** | **+31,466** | **+4,192** | **+35,658** | **1.65** | **-10,603** |
| **FINNIFTY** | Baseline `LIQ-2` (Long ATM, No Stop) | 148 | 73.0% | 55.4% | -38,212 | -5,405 | -43,617 | 0.70 | -55,095 |
| **FINNIFTY** | `LIQ-2` + Bar-Close Stop (Long ATM) | 151 | 58.9% | 47.0% | +877 | -2,771 | -1,894 | 0.98 | -23,803 |
| **FINNIFTY** | **Enhanced `LIQ-2` (OTM Credit Spread)** | **147** | **61.9%** | **63.3%** | **+18,975** | **+15,251** | **+34,226** | **1.55** | **-10,201** |
| **MIDCPNIFTY** | Baseline `LIQ-2` (Long ATM, No Stop) | 180 | 72.2% | 50.0% | +23,218 | -42,466 | -19,248 | 0.89 | -49,420 |
| **MIDCPNIFTY** | `LIQ-2` + Bar-Close Stop (Long ATM) | 180 | 57.8% | 40.0% | +12,771 | -34,822 | -22,050 | 0.84 | -42,602 |
| **MIDCPNIFTY** | **Enhanced `LIQ-2` (OTM Credit Spread)** | **178** | **61.8%** | **63.5%** | **+18,686** | **-8,778** | **+9,908** | **1.11** | **-13,681** |
| **SENSEX** | Baseline `LIQ-2` (Long ATM, No Stop) | 165 | 74.5% | 55.8% | -25,240 | -6,777 | -32,017 | 0.74 | -54,631 |
| **SENSEX** | `LIQ-2` + Bar-Close Stop (Long ATM) | 168 | 59.5% | 44.0% | -34,455 | +2,788 | -31,667 | 0.65 | -52,047 |
| **SENSEX** | **Enhanced `LIQ-2` (OTM Credit Spread)** | **156** | **62.2%** | **64.7%** | **+8,283** | **+7,512** | **+15,795** | **1.24** | **-15,374** |
| **ALL 5 POOLED** | **Enhanced `LIQ-2` (OTM Credit Spread)** | **832** | **62.5%** | **64.8%** | **+87,016** | **+24,140** | **+1,11,157** | **1.32** | **-15,374** |
