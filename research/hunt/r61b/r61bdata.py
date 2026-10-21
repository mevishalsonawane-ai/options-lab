"""R61b data: R57's day stores (rebuilt in R61b's cache) plus near-ATM option volume / OI per minute, one store per
index, and the TR features of each candidate.

    R57_OUT=<R61B_OUT> python research/hunt/r57/r57data.py build     # R57's day stores (labels, A2's inputs)
    python research/hunt/r61b/r61bdata.py build [UND ...]             # agg_<UND>.pkl

agg_<UND>.pkl: {day: dict(volc, volp, oi) of (375,) float32}. The option rows are cleaned exactly as R57 / R61
(r61data.clean) first. volc / volp: summed volume of the call / put slots at offsets -2..+2 recorded at that minute
(NaN when none is recorded; volume is never carried forward). oi: call + put OI of the slots at offsets -5..+5, each
slot carried forward within the day (R61's snapshot rule; NaN before any slot has a value).
"""
from __future__ import annotations

import pickle
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np  # noqa: E402

import r61bcfg as K  # noqa: E402
import r57data  # noqa: E402
import r61chain as C  # noqa: E402
import r61data  # noqa: E402
import r61beval as EV  # noqa: E402
from r51data import _read  # noqa: E402

COLS = ["ts", "offset", "side", "strike", "spot", "close", "volume", "oi", "iv"]


def day_agg(g, nrows: int = K.NROWS) -> dict:
    """Cleaned option rows of one day -> {volc, volp, oi}: (nrows,) float."""
    snap = C.snapshot(g, nrows)
    vol = snap["raw"]["volume"]
    nv, no = np.abs(C.OFFS) <= K.VOL_OFF, np.abs(C.OFFS) <= K.OI_OFF
    out = dict(volc=C._sum(vol[0], nv), volp=C._sum(vol[1], nv))
    oc, op = C._sum(snap["snap"]["oi"][0], no), C._sum(snap["snap"]["oi"][1], no)
    both = np.vstack([oc, op])
    tot = np.nansum(both, axis=0)
    tot[~np.isfinite(both).any(axis=0)] = np.nan
    out["oi"] = tot
    return out


def build_agg(und: str, days: dict) -> dict:
    out, counts = {}, dict(rows=0, mislabelled=0, foreign=0, spikes=0)
    for y in range(K.START.year, K.END.year + 1):
        p = K.LAKE / f"opt_{und}_WEEK_{y}.parquet"
        if not p.exists():
            continue
        o, c = r61data.clean(und, _read(p, COLS), days)
        for k in counts:
            counts[k] += c[k]
        for d, g in o.groupby("day", sort=True):
            out[d] = {k: v.astype(np.float32) for k, v in day_agg(g).items()}
        del o
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / f"agg_{und}.pkl", "wb") as fh:
        pickle.dump(out, fh, protocol=pickle.HIGHEST_PROTOCOL)
    return dict(und=und, agg_days=len(out), **counts)


_AGG: dict = {}


def load_agg(und: str) -> dict:
    if und not in _AGG:
        with open(K.OUT / f"agg_{und}.pkl", "rb") as fh:
            _AGG[und] = pickle.load(fh)
    return _AGG[und]


# ------------------------------------------------------------------------------------------------ per-candidate
def _lists(D, agg: dict | None) -> dict:
    a = np.asarray(D.idx, float)
    nan = [EV.NAN] * K.NROWS
    out = dict(o=a[0].tolist(), h=a[1].tolist(), l=a[2].tolist(), c=a[3].tolist())
    for k in ("volc", "volp", "oi"):
        out[k] = np.asarray(agg[k], float).tolist() if agg is not None else list(nan)
    return out


class Context:
    """Point-in-time inputs of every session of one index: its lists, the previous session's high / low / close and
    the previous BASE_SESSIONS non-expiry sessions with option data (all strictly before the day, from START)."""

    def __init__(self, und: str, days: dict, agg: dict):
        self.und, self.days, self.agg = und, days, agg
        self.order = sorted(days)
        self.pos = {d: i for i, d in enumerate(self.order)}
        self._l: dict = {}

    def lists(self, d):
        if d not in self._l:
            self._l[d] = _lists(self.days[d], self.agg.get(d))
        return self._l[d]

    def prev(self, d):
        i = self.pos[d]
        if i == 0:
            return (EV.NAN, EV.NAN), EV.NAN
        L = self.lists(self.order[i - 1])
        return EV.day_high_low(L["h"], L["l"]), EV.last_close(L["c"])

    def hist(self, d) -> list:
        out = []
        for p in reversed(self.order[:self.pos[d]]):
            if p < K.START:
                break
            if p in self.agg and not self.days[p].expiry:
                out.append(self.lists(p))
                if len(out) == K.BASE_SESSIONS:
                    break
        return out[::-1]

    def features(self, d, frow: int, side: str) -> dict:
        ph, pc = self.prev(d)
        return EV.features(self.lists(d), frow, side, ph, pc, self.hist(d))


_CTX: dict = {}


def context(und: str) -> Context:
    if und not in _CTX:
        _CTX[und] = Context(und, r57data.load(und), load_agg(und))
    return _CTX[und]


def add(rows: list) -> list:
    """Adds the 15 TR features to R57 family-A rows in place (r['f'] gains them; read at frow = er - 1)."""
    for r in rows:
        r["f"].update(context(r["und"]).features(r["day"], r["er"] - 1, r["side"]))
    return rows


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            days = r57data.load(u)
            print(build_agg(u, days), flush=True)
            r57data._CACHE.pop(u, None)
    else:
        print(__doc__)
