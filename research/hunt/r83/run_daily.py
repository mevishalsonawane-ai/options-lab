"""R83 daily book runner (PREREG.md; daily.py). Design first, then ONE final read per idea.

    python -m research.hunt.r83.run_daily design
    python -m research.hunt.r83.run_daily final        # once; common.open_final raises on a second read
"""
from __future__ import annotations

import sys
from dataclasses import replace
from datetime import date

import numpy as np

from research.hunt.r83 import common as C
from research.hunt.r83 import daily as DL

D0, D1 = C.DESIGN_DAILY
F0, F1 = C.FINAL


def cut(m: DL.Mkt, hi: date) -> DL.Mkt:
    k = int(np.searchsorted(np.array(m.dates, dtype="datetime64[D]"), np.datetime64(hi, "D"), side="right"))
    return replace(m, dates=m.dates[:k], o=m.o[:k], h=m.h[:k], l=m.l[:k], c=m.c[:k], roll=m.roll[:k], atr14=m.atr14[:k])


def window(tr, a, b):
    return [t for t in tr if a <= t["entry_ts"].date() <= b]


def markets(hi: date | None):
    ms = {"CRUDE": DL.mcx_mkt("CRUDE"), "NG": DL.mcx_mkt("NG"), "COPPER": DL.copper_mkt()}
    return {k: cut(v, hi) for k, v in ms.items()} if hi else ms


# ------------------------------------------------------------------------------------------------ idea builders
def build(idea: str, ms: dict, ret=None) -> tuple[dict, dict, dict]:
    """-> ({variant: trades}, {variant: (market, twin kind, kw)}, {baseline name: trades (by variant for E8)})."""
    V, T, B = {}, {}, {}
    if idea == "E5":
        for h in (3, 5):
            V[f"hold{h}"] = DL.opec_trades(ms["CRUDE"], h)
            T[f"hold{h}"] = ("CRUDE", "hold", {})
        B["flat"] = []
    elif idea == "E8":
        other = {"CRUDE": "COPPER", "NG": "CRUDE", "COPPER": "CRUDE"}
        for k, nm in (("CRUDE", "crude"), ("NG", "ng"), ("COPPER", "cu")):
            base = DL.donchian_trades(ms[k], "none")
            for f in ("st", "breadth"):
                v = f"{nm}_{f}"
                V[v] = DL.donchian_trades(ms[k], f, ms[other[k]], strict_other=True)
                T[v] = (k, "stop2trail3", {})
                B[v] = base
    elif idea == "E9":
        V["t15"] = DL.season_trades(ms["NG"], ret, 1.5)
        V["all"] = DL.season_trades(ms["NG"], ret, None)
        T["t15"] = T["all"] = ("NG", "hold", {"init": 2.5})
        B["short"] = DL.season_trades(ms["NG"], ret, None, fixed_side=-1)
        B["flat"] = []
    elif idea == "E11":
        V["chandelier"], V["weekly"], B["app"] = [], [], []          # filled with market tags by leg_tag()
        T["chandelier"] = T["weekly"] = ("BOTH", "spell", {})
    elif idea == "E12":
        for k, nm in (("CRUDE", "crude"), ("NG", "ng")):
            for s, sn in ((1, "long"), (-1, "short")):
                V[f"{nm}_{sn}"] = DL.rollweek_trades(ms[k], s)
                T[f"{nm}_{sn}"] = (k, "hold", {})
        B["flat"] = []
    return V, T, B


PRIMARY = {"E5": "hold3", "E8": "crude_st", "E9": "t15", "E11": "chandelier", "E12": "crude_long"}


def twins(idea, ms, variant, trades, kindspec, rng, exclude=None):
    mk, kind, kw = kindspec
    if idea == "E11":
        tot = np.zeros(C.N_TWINS)
        for k in ("CRUDE", "NG"):
            sub = [t for t in trades if t.get("mkt") == k]
            tot += DL.twin_totals(ms[k], sub, "spell", rng)
        return tot
    return DL.twin_totals(ms[mk], trades, kind, rng, exclude=exclude, **kw)


def leg_tag(ms, idea, V, B):
    """E11: the two legs' spells, split into leg-month pieces with their market tagged (twins need it)."""
    if idea != "E11":
        return

    def book(fr, ch):
        out = []
        for k in ("CRUDE", "NG"):
            for t in DL.trend_trades(ms[k], fr, ch):
                out += [dict(x, mkt=k) for x in DL.month_pieces(ms[k], t)]
        return sorted(out, key=lambda x: x["entry_ts"])
    V["chandelier"], V["weekly"], B["app"] = book("M", 3.0), book("W", None), book("M", None)


def base_for(idea, variant, B, lo, hi):
    if idea == "E8":
        return window(B[variant], lo, hi)
    if idea == "E9":
        s = window(B["short"], lo, hi)
        return s if sum(t["net"] for t in s) > 0 else []
    if idea == "E11":
        return window(B["app"], lo, hi)
    return []


def slim(s):
    return {k: s[k] for k in ("trades", "win_rate", "net", "max_dd", "top5_share")}


def excl(idea, ms):
    if idea == "E5":
        return set(DL.opec_signal_rows(ms["CRUDE"])) | {r + 1 for r in DL.opec_signal_rows(ms["CRUDE"])}
    return None


IDEAS = ["E5", "E8", "E9", "E11", "E12"]


def design():
    ms = markets(D1)
    ret = DL.hh_monthly()
    ret_d = ret[ret.index.year <= D1.year]
    for idea in IDEAS:
        rng = np.random.default_rng(C.SEED)
        V, T, B = build(idea, ms, ret_d)
        leg_tag(ms, idea, V, B)
        Vd = {v: window(tr, D0, D1) for v, tr in V.items()}
        wf = C.walk_forward(Vd, PRIMARY[idea], daily=True)
        out = {"idea": idea, "primary": PRIMARY[idea], "frozen": wf["frozen"], "wf_net": wf["wf_net"],
               "wf_picks": wf["wf_picks"], "variants": {}}
        # baseline walk-forward: the baseline of each fold's pick, on that fold
        bwf = 0.0
        for (a, b), p in zip(C.FOLDS, wf["wf_picks"]):
            bwf += sum(t["net"] for t in base_for(idea, p, B, a, b))
        out["base_wf_net"] = bwf
        for v, tr in Vd.items():
            s = C.summary(tr)
            tw = twins(idea, ms, v, tr, T[v], rng, excl(idea, ms))
            bd = base_for(idea, v, B, D0, D1)
            out["variants"][v] = {"design": slim(s), "fold_net": wf["fold_net"][v], "twin_p": C.twin_p(s["net"], tw),
                                  "base_design_net": sum(t["net"] for t in bd), "base_design_trades": len(bd)}
        if idea == "E9":
            out["baseline_short_design"] = slim(C.summary(window(B["short"], D0, D1)))
        C.save_json(f"{idea}_design.json", out)
        print(idea, "frozen", out["frozen"], "wf", round(out["wf_net"]), "base_wf", round(bwf))
        for v, r in out["variants"].items():
            print("  ", v, r["design"]["trades"], round(r["design"]["net"]), "p", round(r["twin_p"], 3),
                  "base", round(r["base_design_net"]), [round(x) for x in r["fold_net"]])


def final():
    ms = markets(None)
    ret = DL.hh_monthly()
    for idea in IDEAS:
        des = __import__("json").loads((C.RESULTS / f"{idea}_design.json").read_text())
        v = des["frozen"]
        C.open_final(idea)
        rng = np.random.default_rng(C.SEED + 1)
        V, T, B = build(idea, ms, ret)
        leg_tag(ms, idea, V, B)
        tr = window(V[v], F0, F1)
        s = C.summary(tr)
        tw = twins(idea, ms, v, tr, T[v], rng, excl(idea, ms))
        bf = base_for(idea, v, B, F0, F1)
        out = {"idea": idea, "frozen": v, "final": slim(s), "twin_p": C.twin_p(s["net"], tw),
               "top5_removed": C.top5_removed(tr), "base_final_net": sum(t["net"] for t in bf),
               "base_final_trades": len(bf), "wf_net": des["wf_net"], "base_wf_net": des["base_wf_net"],
               "by_year": {str(y): sum(t["net"] for t in tr if t["entry_ts"].year == y) for y in range(2023, 2027)},
               "trades": [{k: t[k] for k in ("entry_ts", "exit_ts", "side", "net", "why")} for t in tr]}
        if idea == "E9":
            out["base_short_final"] = slim(C.summary(window(B["short"], F0, F1)))
        C.save_json(f"{idea}_final.json", out)
        print(idea, v, s["trades"], round(s["net"]), "p", round(out["twin_p"], 3), "top5rest", round(out["top5_removed"]),
              "base", round(out["base_final_net"]), "wf", round(des["wf_net"]), "bwf", round(des["base_wf_net"]))


if __name__ == "__main__":
    {"design": design, "final": final}[sys.argv[1]]()
