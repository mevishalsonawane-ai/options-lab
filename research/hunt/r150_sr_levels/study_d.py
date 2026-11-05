"""R150 Amendment D: pivot points (D1) and EMA (D2) on top of R51 + W20; VWAP (D3) and volume profile (D4) descriptive.

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_d.py design
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_d.py final
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_d.py unseen   # 2nd look
    python research/hunt/r150_sr_levels/study_d.py decide
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import study_b as B  # noqa: E402  (sets the old store for "unseen" before r51cfg is imported)

R140, K, L, S = B.R140, B.K, B.L, B.S
RESULTS = HERE / "results"
SEED, TWINS = 1504, 2000
FUT = Path(B.os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake")).expanduser() / "fut_1m_live_index.parquet"
DIST = 0.0025
EMA_N = 20


def base_keep(g, j, key, c) -> bool:
    return not B.skip("W20", key[0], key[1], int(c["er"]), c["side"])


# ------------------------------------------------------------------------------------------------ D1 pivots
def floor_pivots(h: float, l_: float, c: float) -> list:
    p = (h + l_ + c) / 3
    return [p, 2 * p - l_, 2 * p - h, p + (h - l_), p - (h - l_)]


def pivots_of(und, day) -> list:
    x = B.lv(und)
    i = x.pos[day]
    if i == 0:
        return []
    a = x.idx[x.dates[i - 1]]
    if not np.isfinite(a[3]).any():
        return []
    c = a[3][np.isfinite(a[3])][-1]
    return floor_pivots(float(np.nanmax(a[1])), float(np.nanmin(a[2])), float(c))


def wall_prices(prices: list, price: float, side: str, dist: float = DIST) -> bool:
    return any((side == "C" and price < p <= price * (1 + dist)) or (side == "P" and price * (1 - dist) <= p < price) for p in prices)


# ------------------------------------------------------------------------------------------------ D2 EMA
_EMA: dict = {}


def ema(values: np.ndarray, n: int = EMA_N) -> np.ndarray:
    a = 2.0 / (n + 1)
    out = np.empty(len(values))
    m = np.nan
    for i, v in enumerate(values):
        m = v if not np.isfinite(m) else a * v + (1 - a) * m
        out[i] = m
    return out


def ema_ok(und, day, er, side) -> bool | None:
    x = B.lv(und)
    if und not in _EMA:
        _EMA[und] = ema(x.b["c"])
    di = x.pos[day]
    closed = np.flatnonzero((x.b["day"] < di) | ((x.b["day"] == di) & (x.b["end"] < er)))
    if len(closed) < EMA_N:
        return None
    j = int(closed[-1])
    c, e = x.b["c"][j], _EMA[und][j]
    return bool(c >= e) if side == "C" else bool(c <= e)


# ------------------------------------------------------------------------------------------------ D3 / D4 futures (descriptive)
_F: dict = {}


def fut(und) -> dict:
    """{day: (375, 4) close/high/low/volume} of the near-month future (nearest expiry on or after the day)."""
    if not _F:
        df = pd.read_parquet(FUT)
        ts = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        df["d"], df["m"] = ts.dt.date, ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)
        df = df[(df.m >= 0) & (df.m < 375)]
        df["exp"] = pd.to_datetime(df.expiry).dt.date
        for (u, d), g in df.groupby(["symbol", "d"]):
            g = g[g.exp >= d]
            if g.empty:
                continue
            g = g[g.exp == g.exp.min()].drop_duplicates("m")
            a = np.full((375, 4), np.nan)
            a[g.m.values] = g[["close", "high", "low", "volume"]].values
            _F.setdefault(u, {})[d] = a
    return _F.get(und, {})


def vwap_ok(und, day, er, side) -> bool | None:
    a = fut(und).get(day)
    if a is None:
        return None
    seg = a[:er]
    ok = np.isfinite(seg[:, 0]) & np.isfinite(seg[:, 3]) & (seg[:, 3] > 0)
    if ok.sum() < 5:
        return None
    tp = (seg[ok, 0] + seg[ok, 1] + seg[ok, 2]) / 3
    vw = float((tp * seg[ok, 3]).sum() / seg[ok, 3].sum())
    px = float(seg[ok, 0][-1])
    return bool(px >= vw) if side == "C" else bool(px <= vw)


def profile_levels(a: np.ndarray, ref: float, bin_pct: float = 0.0005) -> list:
    ok = np.isfinite(a[:, 0]) & np.isfinite(a[:, 3]) & (a[:, 3] > 0)
    if ok.sum() < 30:
        return []
    w = ref * bin_pct
    bins = np.floor(a[ok, 0] / w).astype(int)
    vol = pd.Series(a[ok, 3]).groupby(bins).sum().sort_index()
    poc = int(vol.idxmax())
    tot, keep = vol.sum(), {poc}
    lo = hi = list(vol.index).index(poc)
    idx = list(vol.index)
    while vol[list(keep)].sum() < 0.7 * tot and (lo > 0 or hi < len(idx) - 1):
        up = vol[idx[hi + 1]] if hi < len(idx) - 1 else -1
        dn = vol[idx[lo - 1]] if lo > 0 else -1
        if up >= dn:
            hi += 1
            keep.add(idx[hi])
        else:
            lo -= 1
            keep.add(idx[lo])
    return [(poc + 0.5) * w, (idx[hi] + 1) * w, idx[lo] * w]


def vp_wall(und, day, er, side) -> bool | None:
    days = sorted(d for d in fut(und) if d < day)
    a_now = fut(und).get(day)
    if not days or a_now is None:
        return None
    prev = fut(und)[days[-1]]
    seg = a_now[:er, 0]
    seg = seg[np.isfinite(seg)]
    if not len(seg):
        return None
    lv = profile_levels(prev, float(seg[-1]))
    return wall_prices(lv, float(seg[-1]), side) if lv else None


# ------------------------------------------------------------------------------------------------ layers
def layer_skip(test, und, day, er, side) -> bool:
    if test == "D1":
        x = B.lv(und)
        price = x.close_at(day, er - 1)
        return bool(np.isfinite(price) and wall_prices(pivots_of(und, day), price, side))
    if test == "D2":
        ok = ema_ok(und, day, er, side)
        return ok is not None and not ok
    if test == "D3":
        ok = vwap_ok(und, day, er, side)
        return ok is not None and not ok
    if test == "D4":
        v = vp_wall(und, day, er, side)
        return v is not None and bool(v)
    raise ValueError(test)


def has_fut(und, day) -> bool:
    return day in fut(und)


def evaluate(phase: str) -> dict:
    rng = np.random.default_rng(SEED)
    if phase == "unseen":
        k = S.mods()["r51data"].K
        k.WARM_START, k.START, k.END = B.OLD_WARM, B.OLD_START, B.OLD_END
        lo, hi = B.UNSEEN
        per = {"unseen": lambda tr: [t for t in tr if B.in_(t, lo, hi)]}
    else:
        lo, hi = (K.START, K.DESIGN_END) if phase == "design" else (K.FINAL_START, K.FINAL_END)
        per = ({"design": lambda tr: S.by_period(tr, "design"), "wf": lambda tr: S.by_period(tr, "wf")} if phase == "design"
               else {"final": lambda tr: S.by_period(tr, "final")})
    us_all = S.units("R51", lo, hi)
    us = S.filtered(us_all, base_keep)                              # R51 + W20
    base = S.walk("R51", us)
    out = {"_meta": dict(phase=phase, lo=str(lo), hi=str(hi), seed=SEED, second_look_unseen=phase == "unseen"),
           "base_r51_w20": {p: R140.loss_stats(f(base)) for p, f in per.items()}}
    tests = ["D1", "D2"] + (["D3", "D4"] if phase == "final" else [])
    for t in tests:
        if t in ("D3", "D4"):                     # descriptive: only the sessions with futures minutes
            us_t = [u for u in us if has_fut(u[0][0], u[0][1])]
            b_t = S.walk("R51", us_t)
            tr = S.walk("R51", S.filtered(us_t, lambda g, j, key, c, t=t: not layer_skip(t, key[0], key[1], int(c["er"]), c["side"])))
            out[t] = {"sessions_with_futures": len({(u[0][0], u[0][1]) for u in us_t}), "base": R140.loss_stats(b_t),
                      "layer": R140.loss_stats(tr), "descriptive_only": True}
            print(phase, t, out[t]["base"]["trades"], out[t]["base"]["net"], "->", out[t]["layer"]["trades"], out[t]["layer"]["net"], flush=True)
            continue
        kf = (lambda g, j, key, c, t=t: not layer_skip(t, key[0], key[1], int(c["er"]), c["side"]))
        tr = S.walk("R51", S.filtered(us, kf))
        r = {p: R140.loss_stats(f(tr)) for p, f in per.items()}
        for p, f in per.items():
            r[p]["changed_vs_base"] = L.changed(f(tr), f(base))
        if phase != "design":
            p0 = list(per)[0]
            act = R140.loss_sum((x["day"], x["net"]) for x in per[p0](tr))
            flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
            kcount = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not kf(g, j, u[0], c))
            tw = np.zeros(TWINS)
            for i in range(TWINS):
                gone = {flat[x] for x in L.random_drop(len(flat), kcount, rng)}
                tt = S.walk("R51", S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
                tw[i] = R140.loss_sum((x["day"], x["net"]) for x in tt if lo <= x["day"] <= hi)
            r["twin"] = L.twin_p(act, tw)
        out[t] = r
        print(phase, t, {p: (r[p]["trades"], r[p]["win"], r[p]["net"], r[p]["loss_sum"], r[p]["max_dd"]) for p in per}, "base",
              {p: (out["base_r51_w20"][p]["trades"], out["base_r51_w20"][p]["win"], out["base_r51_w20"][p]["net"],
                   out["base_r51_w20"][p]["loss_sum"], out["base_r51_w20"][p]["max_dd"]) for p in per}, flush=True)
    return out


def run(phase: str) -> None:
    if phase in ("final", "unseen"):
        S.mods()["C"].open_final()
    out = evaluate(phase)
    out["_meta"]["written"] = datetime.now(timezone.utc).isoformat()
    (RESULTS / f"d_{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d, u = (json.loads((RESULTS / f"d_{p}.json").read_text()) for p in ("design", "unseen"))
    ids = ["D1", "D2"]
    ps = [u[t]["twin"]["p"] for t in ids]
    qs = L.bh(ps)
    out = {}
    for t, p, q in zip(ids, ps, qs):
        rule = {"design": d[t]["design"], "wf": d[t]["wf"], "final": u[t]["unseen"]}
        base = {"design": d["base_r51_w20"]["design"], "wf": d["base_r51_w20"]["wf"], "final": u["base_r51_w20"]["unseen"]}
        g = R140.gates(rule, base, float(q), float(p), int(u[t]["unseen"]["changed_vs_base"]))
        g["fails"] = [x.replace("final:", "unseen:") for x in g["fails"]]
        out[t] = {"p": p, "q": float(q), **g}
    (RESULTS / "d_decision.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    if B.CMD == "decide":
        print(json.dumps(decide(), indent=1, default=str))
    else:
        run(B.CMD)
