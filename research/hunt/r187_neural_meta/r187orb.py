"""R187 ORB family (PREREG sections 1b, 3): ORB, ORB Fresh and ORB Sweep on BANKNIFTY through R169's engine (R3 walk, R169's
B0: dated Zerodha charges, R17 fills), on R3's session store rebuilt to 13 Nov 2026 (r187orbstore.py).

- `rows(arm, lo, hi)`: every B0 trade with its decision row, R169's 11 features and its net under each exit of the ORB menu
  (B0, X1 .. X6), each priced on the same strike and entry fill (r169lib.simulate; the walk is not re-run for labels).
- `walk(arm, sessions, chooser)`: r169lib.run_session line for line (no filters), except that each trade's exit set comes
  from `chooser(s, side, row, feats, k)` (an ORB exit id). With chooser = always "B0" it is R169's B0 (test_r187.py).

Signal source: the index (BANKNIFTY 5-minute bars). Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
os.environ.setdefault("R169_OUT", str(Path.home() / ".cache" / "iraalgo" / "r187" / "r3"))
sys.path.insert(0, str(HERE.parent / "r169_orb_enhance"))
import numpy as np  # noqa: E402

import r169cfg as K9  # noqa: E402
import r169lib as X  # noqa: E402
import r3engine as E  # noqa: E402

import r187cfg as K  # noqa: E402

_S: list = []


def sessions() -> list:
    if not _S:
        import r169study as S9
        _S.extend(S9.sessions())
    return _S


def contexts() -> dict:
    import r169study as S9
    return S9.contexts(sessions())


def cfg_for(arm: str, e: str) -> X.Cfg:
    return X.Cfg(arm, 1, K.orb_changes(e))


def walk(arm: str, ss: list, chooser=None) -> list:
    """The arm's taken trades over sessions `ss`; chooser(s, side, row, feats, k) -> 'B0' | 'X1' .. 'X6' (None = B0)."""
    base = X.Cfg(arm)
    v = base.base
    P = K9.K3.PTS[K9.UND]
    out = []
    for s in ss:
        b5 = X.bars5(s.ix)
        hi, lo = E.opening_range(b5)
        if not (np.isfinite(hi) and np.isfinite(lo)) or not np.isfinite(s.atm):
            continue
        first, last = v.win
        per_side, n, free_bar = {1: 0, -1: 0}, 0, -1
        b = first
        while b <= last:
            if b <= free_bar:
                b += 1
                continue
            if n >= v.max_entries:
                break
            side = E.signal(v.arm, b5, b, hi, lo)
            if not side or (v.side_cap and per_side[side] >= v.side_cap):
                b += 1
                continue
            row = 5 * b + 4
            k = X.choose_strike(s, base, side, row, None)
            f = X.features(s, side, row, hi, lo, b5, k)
            e = chooser(s, side, row, f, k) if chooser is not None else "B0"
            t = X.simulate(s, cfg_for(arm, e or "B0"), side, row, k, P, hi, lo)
            if t is None:
                b += 1
                continue
            t.update(feats=f, bar=b, exit_id=f"O:{e or 'B0'}", hi=hi, lo=lo)
            out.append(t)
            per_side[side] += 1
            n += 1
            free_bar = t["exit_row"] // 5
            b = b + 1
    return out


def rows(arm: str, lo, hi) -> list:
    """B0 trades on [lo, hi] with the net under every ORB exit (same strike, same entry fill)."""
    ss = [s for s in sessions() if lo <= s.day <= hi]
    by_day = {s.day: s for s in ss}
    ctx = contexts()
    P = K9.K3.PTS[K9.UND]
    out = []
    for t in walk(arm, ss):
        s = by_day[t["day"]]
        nets = {}
        for e in K.ORB_EXITS:
            u = t if e == "B0" else X.simulate(s, cfg_for(arm, e), t["side"], t["sig_row"], t["k"], P, t["hi"], t["lo"])
            nets[f"O:{e}"] = float(u["net"]) if u is not None else np.nan
        ex = ctx[s.day]["expiry_date"]
        out.append(dict(arm=arm, und=K9.UND, day=s.day, side="C" if t["side"] > 0 else "P", r=int(t["sig_row"]),
                        key=(K9.UND, s.day, int(t["sig_row"]), "C" if t["side"] > 0 else "P"),
                        dte=float((ex - s.day).days) if ex is not None else np.nan, is_exp=bool(s.expiry),
                        study={f"o_{k}": float(t["feats"].get(k, np.nan)) for k in X.PICK_FEATS}, nets=nets,
                        net=float(t["net"])))
    return out
