"""Gold: 1-hour candle colours together with 30-minute candle colours (the owner's ask, 2026-10-02: "30 min then
1 hour, and find a pattern between them").

    python research/gold_mtf_patterns.py <xauusd_m1_bid.csv.gz> [out.md]

At each 30-minute close: the colours of the last 1-3 COMPLETED 1-hour candles (oldest first) and of the last 1-3
30-minute candles. BUY at the next 30-minute open (ask), sell after N = 1 / 2 / 4 / 8 thirty-minute candles (bid), $7 a
lot, one trade at a time per version. A baseline buys at every 30-minute close regardless (gold rose ~140% in these
years, so any buy rule gains from that drift): a pattern is only interesting if it beats the baseline per trade in the
fitting years AND the held-out year. Chosen on the fitting years (Oct 2023 - Sep 2025: positive, t >= 2, 150+
trades), read on the held-out year (Oct 2025 - Sep 2026). USD per standard lot.
"""
from __future__ import annotations

import itertools
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import gold_session_strats as s  # noqa: E402
import liquidity_gold as g  # noqa: E402

H, COMM = 0.15, 0.07


def tstat(x):
    return x.mean() / (x.std(ddof=1) / len(x) ** 0.5) if len(x) > 1 and x.std() > 0 else 0.0


def main():
    mid = s.load(sys.argv[1])
    m30 = s.bars(mid, 30); m30 = m30[m30.index.hour != 21]
    h1 = s.bars(mid, 60); h1 = h1[h1.index.hour != 21]
    o, c = m30.open.values, m30.close.values
    idx = m30.index; n = len(c)
    yr = np.array([g.year_of(d) for d in idx.date])
    years = sorted(set(yr), key=lambda x: x[4:8]); fit, hold = years[:-1], years[-1:]
    fitm, holdm = np.isin(yr, fit), np.isin(yr, hold)
    col30 = np.where(c > o, "G", np.where(c < o, "R", "D"))
    hcol = np.where(h1.close.values > h1.open.values, "G", np.where(h1.close.values < h1.open.values, "R", "D"))
    hend = (h1.index + pd.Timedelta(hours=1)).values
    close_t = (idx + pd.Timedelta(minutes=30)).values
    last_h = np.searchsorted(hend, close_t, side="right") - 1          # index of the last 1-hour candle closed by then
    gap_ok = np.r_[(idx[1:] - idx[:-1]) <= pd.Timedelta(minutes=60), False]
    stretch = np.cumsum(~np.r_[True, gap_ok[:-1]])
    fwd = {}
    for N in (1, 2, 4, 8):
        j = np.minimum(np.arange(n) + N, n - 1); nx = np.minimum(np.arange(n) + 1, n - 1)
        ok = (np.arange(n) + N < n) & (stretch[j] == stretch)
        f = np.full(n, np.nan); f[ok] = c[j][ok] - o[nx][ok]; fwd[N] = f

    def hkey(k):
        out = np.full(n, "", dtype="<U6")
        for back in range(k - 1, -1, -1):
            ii = last_h - back
            out = np.char.add(out, np.where(ii >= 0, hcol[np.maximum(ii, 0)], "X"))
        return out

    def mkey(k):
        out = np.full(n, "", dtype="<U6")
        for back in range(k - 1, -1, -1):
            out = np.char.add(out, np.r_[np.full(back, "X"), col30[:n - back]] if back else col30)
        return out

    HK = {k: hkey(k) for k in (1, 2, 3)}
    MK = {k: mkey(k) for k in (1, 2, 3)}

    def run(mask, N):
        f = fwd[N]
        sel = np.where(mask & gap_ok & ~np.isnan(f))[0]
        keep, busy = [], -1
        for i in sel:
            if i > busy:
                keep.append(i); busy = i + N
        keep = np.array(keep, dtype=int)
        pnl = 100 * (f[keep] - 2 * H - COMM) if len(keep) else np.array([])
        return pnl[fitm[keep]] if len(keep) else pnl, pnl[holdm[keep]] if len(keep) else pnl

    base = {N: run(np.ones(n, bool), N) for N in (1, 2, 4, 8)}
    L = ["## Gold: 1-hour and 30-minute candle colours together (research/gold_mtf_patterns.py)", "",
         f"XAUUSD {idx[0]:%Y-%m-%d} .. {idx[-1]:%Y-%m-%d}; {n:,} thirty-minute candles. Buys only; USD per standard lot after costs.", "",
         "Baseline - buy at every 30-minute close regardless of colour (gold's drift):", "",
         "| hold | per trade fit / held out | trades fit / held out |", "|---|---|---|"]
    for N, (tf, th) in base.items():
        L.append(f"| {N} x 30 min | {tf.mean():+.0f} / {th.mean():+.0f} | {len(tf)} / {len(th)} |")
    rows = []
    for hk, mk in itertools.product((1, 2, 3), (1, 2, 3)):
        for hp in ["".join(t) for t in itertools.product("GR", repeat=hk)]:
            for mp in ["".join(t) for t in itertools.product("GR", repeat=mk)]:
                mask = (HK[hk] == hp) & (MK[mk] == mp)
                for N in (1, 2, 4, 8):
                    tf, th = run(mask, N)
                    if len(tf) < 150 or len(th) < 40:
                        continue
                    bf, bh = base[N][0].mean(), base[N][1].mean()
                    rows.append(dict(name=f"1h {hp} + 30m {mp}, hold {N}", nf=len(tf), nh=len(th), pf=tf.mean(), ph=th.mean(),
                                     tf=tstat(tf), th=tstat(th), ef=tf.mean() - bf, eh=th.mean() - bh,
                                     te=tstat(tf - bf), teh=tstat(th - bh), sf=tf.sum(), sh=th.sum()))
    passed = sorted([r for r in rows if r["sf"] > 0 and r["tf"] >= 2], key=lambda r: -r["tf"])
    beat = [r for r in passed if r["ef"] > 0 and r["te"] >= 2]
    held = [r for r in beat if r["eh"] > 0]
    L += ["", f"{len(rows)} combinations x holding times tried. {len(passed)} are positive with t >= 2 in the fitting years; "
          f"{len(beat)} of those also beat the baseline there (t >= 2 on the difference); {len(held)} of those beat it again "
          "in the held-out year (by chance about half would).", "",
          "| version | trades fit / held out | per trade fit / held out | vs baseline fit / held out | fitting t | held-out t |",
          "|---|---|---|---|---|---|"]
    for r in (beat or passed)[:30]:
        L.append(f"| {r['name']} | {r['nf']} / {r['nh']} | {r['pf']:+.0f} / {r['ph']:+.0f} | {r['ef']:+.0f} / {r['eh']:+.0f} | "
                 f"{r['tf']:.2f} | {r['th']:.2f} |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
