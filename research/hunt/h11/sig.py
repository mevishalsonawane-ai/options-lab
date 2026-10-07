"""h11 signals: the Liquidity 15+5 arm (h4's port, research/hunt/h4/comps.py, rules UNCHANGED) generalised so that the
grid of PREREG.md can be cut from ONE raw signal set:
  - every timeframe in TFS is a separate book (liq<tf>_<U>), exactly as comps.liq_ext_signals builds them;
  - NO room filter here: each row carries `level` and `dist` (= side x (next level - close), inf if no level ahead),
    so a variant applies its own index stop (idx_stop = level - side x stop) and room filter (dist >= room x stop).
With TFS=(15,5), stop = comps.IDX_STOP_EXT and room 1 this reproduces comps.liq_ext_signals row for row (checked in
build.py).
"""
from __future__ import annotations

import os
import sys
from datetime import timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from comps import L, WIN_FROM, WIN_TO, fold, swing_zones, pool_zones  # noqa: E402

TFS = (5, 15, 30)


def raw_signals(mk, unds=("NIFTY", "SENSEX"), tfs=TFS):
    rows = []
    for und in unds:
        ix = mk.index(und)
        hist = []
        for d in ix.days:
            x = ix.d[d]
            real_today = bool(x["real"])
            m = np.asarray(x["m"]).astype(int)
            sel = (m >= 555) & (m <= 929)
            today1 = [(d, int(mm), float(o), float(h), float(lo), float(c))
                      for mm, o, h, lo, c in zip(m[sel], x["o"][sel], x["h"][sel], x["l"][sel], x["c"][sel])]
            hist = [hh for hh in hist if hh[0] >= d - timedelta(days=10)]
            real = real_today and hist and all(hh[1] for hh in hist)
            if real and not x["exp"]:
                first = {}
                for b in [b for hh in hist for b in hh[2]] + today1:
                    first.setdefault((b[0], b[1]), b)
                ones = sorted(first.values(), key=lambda b: (b[0], b[1]))
                for tf in tfs:
                    book = f"liq{tf}_{und}"
                    bars = fold(ones, tf)
                    if len(bars) < 2 * L + 2:
                        continue
                    O = np.array([b[2] for b in bars]); H = np.array([b[3] for b in bars])
                    Lo = np.array([b[4] for b in bars]); Cl = np.array([b[5] for b in bars])
                    sw_, po = swing_zones(H, Lo, Cl), pool_zones(O, H, Lo, Cl)
                    zones = sw_ + po
                    n = len(bars)
                    is_today = [b[0] == d for b in bars]
                    bar_end = [b[1] + tf if is_today[k] else -1 for k, b in enumerate(bars)]
                    by_break = {}
                    for p in po:
                        if p.broken >= 0:
                            by_break.setdefault(p.broken, []).append(p)
                    for i in range(n):
                        if not is_today[i] or bars[i][1] + tf > 930 or i not in by_break:
                            continue
                        pool = None
                        for p in by_break[i]:
                            if p.known > i:
                                continue
                            if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                   and (s.broken < 0 or s.broken >= i) for s in sw_):
                                pool = p; break
                        if pool is None:
                            continue
                        side, close = pool.side, Cl[i]
                        ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i)
                                 and side * (q.edge - close) > 0]
                        target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
                        level = pool.edge
                        fb = next((bar_end[k] for k in range(i + 1, n) if side * (Cl[k] - level) < 0), None)
                        nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= i + 1]
                        nl = min(nl) if nl else None
                        done = bar_end[i]
                        if done > 930 or done < WIN_FROM or done > WIN_TO:
                            continue
                        xa = [v for v in (fb, nl) if v is not None]
                        rows.append(dict(und=und, day=d, sig_min=done - 1, gate=done, side=side, book=book, tf=tf,
                                         ref_spot=float(close), level=float(level),
                                         dist=np.inf if target is None else float(side * (target - close)),
                                         idx_target=np.nan if target is None else float(target),
                                         exit_at=float(min(xa)) if xa else np.nan, tag=f"lvl={level:.2f}"))
            hist.append((d, real_today, today1))
    return pd.DataFrame(rows)
