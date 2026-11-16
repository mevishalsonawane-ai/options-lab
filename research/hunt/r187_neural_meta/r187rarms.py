"""R187 R72 / R51 / Liquidity (PREREG sections 1b, 3): R179 / R186's units, walk and pricing on R51's clean stores, with the
eight exits of R187's menu (r187cfg.R_EXITS) priced by R186's engine (copied unchanged: r186engine.py).

- `Arm(arm, lo, hi)`: the arm's units (R177 apply_rule with no rule, as R179 / R186), R151's app picker scores, R151's
  28 features, and every candidate priced under every exit (one entry fill per candidate; an exit only changes the exit).
- `Arm.walk(choice)`: R176's walk with each candidate priced under its chosen exit (choice: {cand key: exit id}; missing
  = V5). A different exit can free or block later candidates, as in the app.
- `Arm.v5(trades)`: R151's post-walk keep on R72 / R51 (a skipped break still held its side), as the app's V5.

R176_POST=1 (the stores built to 19 Oct) is set before anything is imported; the new month runs in its own process on
R186's extended copy (IRAALGO_LAKE / IRAALGO_CACHE, r106cfg.POST_END = 13 Nov). Signal source: the index. Plain-English
notes, 16 Nov 2026.
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

os.environ.setdefault("R176_POST", "1")
HERE = Path(__file__).resolve().parent
for p in (str(HERE.parent / "r179_winrate_55"), str(HERE.parent / "r177_vp_trap"), str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)
import numpy as np  # noqa: E402

import r179study as R9  # noqa: E402
import r187cfg as K  # noqa: E402

R7, S6, K6 = R9.R7, R9.S6, R9.K6


def extend_to(hi: date):
    """The new month: point the store window at `hi`, only on R186's extended copy (never the real 19 Oct stores)."""
    cache = Path(os.environ.get("IRAALGO_CACHE", "")).expanduser()
    lake = Path(os.environ.get("IRAALGO_LAKE", "")).expanduser()
    assert cache.name and cache != Path.home() / ".cache" / "iraalgo" and "r186ext" in str(lake), \
        "set IRAALGO_LAKE / IRAALGO_CACHE to R186's extended copy (outside git)"
    import r106cfg
    r106cfg.POST_END = hi


def ckey(und: str, day, er: int, side: str) -> tuple:
    return (und, S6._d(day), int(er), side)


class Arm:
    def __init__(self, arm: str, lo: date, hi: date):
        import r186engine as Z
        self.arm, self.lo, self.hi = arm, lo, hi
        M = R7.mods()
        us0 = S6.units(arm, lo, hi)
        self.us, _ = R7.apply_rule(arm, us0, None)
        self.P = self.sc = self.thr = None
        if arm in K.PICKER_ARMS:
            self.P = S6.picker()
            self.sc, self.thr = self.P.scores(arm, us0)
        ex = S6.exits(arm)
        self.pr = {}
        for e in K.R_EXITS:
            kw = K.r_exit_kw(arm, e)
            self.pr[f"R:{e}"] = [Z.price(key[0], M["r51data"].load(key[0])[key[1]], cands, ex, 1, **kw)
                                 for key, cands, _, _ in self.us]
        self.exp_days = {}                             # und -> sorted expiry days of the store (Day.expiry is a flag)
        for key, _, _, _ in self.us:
            if key[0] not in self.exp_days:
                st = M["r51data"].load(key[0])
                self.exp_days[key[0]] = sorted(d for d, D in st.items() if getattr(D, "expiry", False))
        self.cands = {}                                # cand key -> (unit index, cand index, cand, Day)
        for ui, (key, cands, _, _) in enumerate(self.us):
            D = M["r51data"].load(key[0])[key[1]]
            for ci, c in enumerate(cands):
                self.cands[ckey(key[0], key[1], c["er"], c["side"])] = (ui, ci, c, D)

    # ------------------------------------------------------------------------------------------ walks
    def priced(self, choice: dict | None = None) -> list:
        base = self.pr["R:V5"]
        if not choice:
            return base
        out = []
        for ui, (key, cands, _, _) in enumerate(self.us):
            row = []
            for ci, c in enumerate(cands):
                e = choice.get(ckey(key[0], key[1], c["er"], c["side"]), "R:V5")
                row.append(self.pr[e][ui][ci])
            out.append(row)
        return out

    def walk(self, choice: dict | None = None) -> list:
        """Walked trades (before R151's keep), each with 'key' and 'exit_id'."""
        tr = [t for t in S6.walk(self.arm, self.us, self.priced(choice)) if self.lo <= t["day"] <= self.hi]
        for t in tr:
            t["key"] = ckey(t["und"], t["day"], t["er_sig"], t["side"])
            t["exit_id"] = (choice or {}).get(t["key"], "R:V5")
        return tr

    def kept(self, t: dict) -> bool:
        return self.P is None or self.P.kept(t, self.sc, self.thr)

    def v5(self, trades: list) -> list:
        return [t for t in trades if self.kept(t)]

    def nets(self, key: tuple) -> dict:
        ui, ci, _, _ = self.cands[key]
        return {e: (float(self.pr[e][ui][ci]["net"]) if self.pr[e][ui][ci] is not None else np.nan) for e in self.pr}

    # ------------------------------------------------------------------------------------------ rows
    def study_feats(self) -> dict:
        """R151's 28 features + the app picker score (R72 / R51) for every candidate key."""
        import study as T                             # R151 (imported by the picker module chain)
        if self.P is None:
            import r152picker  # noqa: F401  (puts R151's study on sys.modules)
        fx = T.feats_of_units(self.us)
        out = {}
        for k, f in fx.items():
            key = ckey(k[0], k[1], k[2], k[3])
            d = {f"p_{n}": float(f.get(n, np.nan)) for n in T.FEATS}
            d["p_score"] = float(self.sc[k]) if self.sc is not None and k in self.sc else np.nan
            out[key] = d
        return out

    def meta(self, key: tuple) -> dict:
        from bisect import bisect_left
        _, _, c, D = self.cands[key]
        ds = self.exp_days.get(key[0], [])
        i = bisect_left(ds, key[1])
        dte = float((ds[i] - key[1]).days) if i < len(ds) else np.nan
        return dict(arm=self.arm, und=key[0], day=key[1], side=key[3], r=int(key[2]) - 1, key=key, dte=dte,
                    is_exp=bool(getattr(D, "expiry", False)))
