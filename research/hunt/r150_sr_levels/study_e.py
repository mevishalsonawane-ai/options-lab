"""R150 Amendment E: EMA / VWAP cross on top of R51 + W20.

    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_e.py design
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_e.py final
    IRAALGO_LAKE=~/.cache/iraalgo/lake R51_CLEAN=1 python research/hunt/r150_sr_levels/study_e.py unseen   # 2nd look
    python research/hunt/r150_sr_levels/study_e.py decide
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import study_d as Dm  # noqa: E402

B, R140, K, L, S = Dm.B, Dm.R140, Dm.K, Dm.L, Dm.S
RESULTS = HERE / "results"
SEED, TWINS = 1505, 2000
FRESH = 6


# ------------------------------------------------------------------------------------------------ pure
def twap(idx: np.ndarray) -> np.ndarray:
    """Per-minute session TWAP of the typical price (H + L + C) / 3 from 09:15 ((4, 375) O H L C in; NaN-safe)."""
    tp = (idx[1] + idx[2] + idx[3]) / 3
    ok = np.isfinite(tp)
    cs, cn = np.cumsum(np.where(ok, tp, 0.0)), np.cumsum(ok)
    return np.where(cn > 0, cs / np.maximum(cn, 1), np.nan)


def vwap(close, high, low, vol) -> np.ndarray:
    tp = (close + high + low) / 3
    ok = np.isfinite(tp) & np.isfinite(vol) & (vol > 0)
    num, den = np.cumsum(np.where(ok, tp * vol, 0.0)), np.cumsum(np.where(ok, vol, 0.0))
    return np.where(den > 0, num / np.where(den > 0, den, 1), np.nan)


def side_of(ema_v: float, vw: float, side: str) -> bool | None:
    if not (np.isfinite(ema_v) and np.isfinite(vw)):
        return None
    return bool(ema_v >= vw) if side == "C" else bool(ema_v <= vw)      # plain bool: identity checks below


def cross_decision(states: list, fresh: bool) -> bool | None:
    """states: today's closed 5-min bars' 'EMA on the trade's side of VWAP' (oldest first; None unknown). E1: the last
    is True. E2: the last is True and it became True within the last FRESH bars (a False just before). None: unknown."""
    states = [None if v is None else bool(v) for v in states]
    if not states or states[-1] is None:
        return None
    if not fresh:
        return states[-1]
    if not states[-1]:
        return False
    tail = states[-(FRESH + 1):]
    for k in range(len(tail) - 1, 0, -1):
        if tail[k] and tail[k - 1] is False:
            return True
    return False


# ------------------------------------------------------------------------------------------------ index (E1 / E2)
def states_index(und, day, er, side) -> list:
    x = B.lv(und)
    if und not in Dm._EMA:
        Dm._EMA[und] = Dm.ema(x.b["c"])
    di = x.pos[day]
    tw = twap(x.idx[day])
    js = np.flatnonzero((x.b["day"] == di) & (x.b["end"] < er))
    return [side_of(Dm._EMA[und][j], tw[x.b["end"][j]], side) for j in js]


# ------------------------------------------------------------------------------------------------ futures (E3 / E4)
_FB: dict = {}


def fut_bars(und):
    """The near future's continuous 5-min closes, their EMA, and per-bar session VWAP, over the sessions it has."""
    if und not in _FB:
        F = Dm.fut(und)
        days = sorted(F)
        c, day, end, vw = [], [], [], []
        for d in days:
            a = F[d]
            v = vwap(a[:, 0], a[:, 1], a[:, 2], a[:, 3])
            for s in range(0, 375, 5):
                seg = a[s:s + 5, 0]
                ok = np.flatnonzero(np.isfinite(seg))
                if not ok.size:
                    continue
                c.append(seg[ok[-1]])
                day.append(d)
                end.append(s + 4)
                vw.append(v[s + 4] if np.isfinite(v[s + 4]) else v[s + ok[-1]])
        c = np.asarray(c)
        _FB[und] = dict(c=c, ema=Dm.ema(c), day=np.asarray(day, dtype=object), end=np.asarray(end), vw=np.asarray(vw))
    return _FB[und]


def states_fut(und, day, er, side) -> list:
    f = fut_bars(und)
    js = np.flatnonzero((f["day"] == day) & (f["end"] < er))
    return [side_of(f["ema"][j], f["vw"][j], side) for j in js]


def layer_skip(test, und, day, er, side) -> bool:
    if test in ("E1", "E2"):
        dec = cross_decision(states_index(und, day, er, side), test == "E2")
    else:
        dec = cross_decision(states_fut(und, day, er, side), test == "E4")
    return dec is False


# ------------------------------------------------------------------------------------------------ run (as study_d)
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
    us = S.filtered(S.units("R51", lo, hi), Dm.base_keep)
    base = S.walk("R51", us)
    out = {"_meta": dict(phase=phase, lo=str(lo), hi=str(hi), seed=SEED, second_look_unseen=phase == "unseen"),
           "base_r51_w20": {p: R140.loss_stats(f(base)) for p, f in per.items()}}
    for t in ["E1", "E2"] + (["E3", "E4"] if phase == "final" else []):
        kf = (lambda g, j, key, c, t=t: not layer_skip(t, key[0], key[1], int(c["er"]), c["side"]))
        if t in ("E3", "E4"):
            us_t = [u for u in us if Dm.has_fut(u[0][0], u[0][1])]
            out[t] = {"sessions_with_futures": len({(u[0][0], u[0][1]) for u in us_t}),
                      "base": R140.loss_stats(S.walk("R51", us_t)), "layer": R140.loss_stats(S.walk("R51", S.filtered(us_t, kf))),
                      "descriptive_only": True}
            print(phase, t, out[t]["base"]["trades"], out[t]["base"]["net"], "->", out[t]["layer"]["trades"], out[t]["layer"]["net"], flush=True)
            continue
        tr = S.walk("R51", S.filtered(us, kf))
        r = {p: R140.loss_stats(f(tr)) for p, f in per.items()}
        for p, f in per.items():
            r[p]["changed_vs_base"] = L.changed(f(tr), f(base))
        if phase != "design":
            p0 = list(per)[0]
            act = R140.loss_sum((x["day"], x["net"]) for x in per[p0](tr))
            flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
            kc = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not kf(g, j, u[0], c))
            tw = np.zeros(TWINS)
            for i in range(TWINS):
                gone = {flat[x] for x in L.random_drop(len(flat), kc, rng)}
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
    (RESULTS / f"e_{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d, u = (json.loads((RESULTS / f"e_{p}.json").read_text()) for p in ("design", "unseen"))
    ids = ["E1", "E2"]
    ps = [u[t]["twin"]["p"] for t in ids]
    qs = L.bh(ps)
    out = {}
    for t, p, q in zip(ids, ps, qs):
        rule = {"design": d[t]["design"], "wf": d[t]["wf"], "final": u[t]["unseen"]}
        base = {"design": d["base_r51_w20"]["design"], "wf": d["base_r51_w20"]["wf"], "final": u["base_r51_w20"]["unseen"]}
        g = R140.gates(rule, base, float(q), float(p), int(u[t]["unseen"]["changed_vs_base"]))
        g["fails"] = [x.replace("final:", "unseen:") for x in g["fails"]]
        out[t] = {"p": p, "q": float(q), **g}
    (RESULTS / "e_decision.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    if B.CMD == "decide":
        print(json.dumps(decide(), indent=1, default=str))
    else:
        run(B.CMD)
