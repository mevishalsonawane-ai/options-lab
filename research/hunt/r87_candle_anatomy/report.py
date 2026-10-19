"""Builds results/atlas_report.md (tables for the write-up) and the candle knowledge pack
(knowledge/candle_pack.json + candle_pack.md) from the results files. No data is read here, only results/.

    python -m research.hunt.r87_candle_anatomy.report
"""
from __future__ import annotations

import json
import math

import numpy as np
import pandas as pd

from . import cfg
from .meaning import COND, MEANING

KDIR = cfg.HERE / "knowledge"


def _r(x, d=2):
    return None if x is None or (isinstance(x, float) and not math.isfinite(x)) else round(float(x), d)


def load(tag: str) -> dict:
    out = {}
    for fam in ("P1", "P2", "P3"):
        p = cfg.RESULTS / f"atlas_{tag}_{fam}.csv.gz"
        if p.exists():
            t = pd.read_csv(p)
            t["cond"] = t["cond"].fillna("")
            out[fam] = t
    return out


def oos() -> pd.DataFrame | None:
    p = cfg.RESULTS / "oos_findings.csv"
    if not p.exists():
        return None
    t = pd.read_csv(p)
    t["cond"] = t["cond"].fillna("")
    return t


def fold_same(t: pd.DataFrame) -> pd.Series:
    sg = np.sign(t.eff)
    f = t.fold_eff.apply(json.loads)
    return pd.Series([int(sum(1 for x in v if x is not None and np.sign(x) == s)) for v, s in zip(f, sg)], index=t.index)


def family_cards(d: dict, o: pd.DataFrame | None, paths: list) -> list[dict]:
    """One card per candle family: per instrument x timeframe numbers, where it works / fails, mechanism, verdict."""
    p1, p2 = d["P1"], d.get("P2", pd.DataFrame())
    p1 = p1.assign(fs=fold_same(p1))
    if len(p2):
        p2 = p2.assign(fs=fold_same(p2))
    held = set()
    if o is not None and len(o):
        held = {(r.und, int(r.tf), r.family, r.cond) for r in o[o.holds].itertuples()}
    tested_oos = set()
    if o is not None and len(o):
        tested_oos = {(r.und, int(r.tf), r.family, r.cond) for r in o.itertuples()}
    pmap = {(x["und"], x["tf"], x["family"]): x for x in paths}
    cards = []
    for fam, (name, defin, why) in MEANING.items():
        rows = p1[p1.family == fam]
        if rows.empty:
            continue
        stats = []
        for r in rows.itertuples():
            key = (r.und, int(r.tf), fam, "")
            stats.append(dict(
                und=r.und, tf=int(r.tf), n=int(r.n), days=int(r.days),
                up_rate_5=_r(r.up_rate, 3), base_up_rate_5=_r(r.base_up_rate, 3),
                eff_atr_5=_r(r.eff, 3), move_pts_5=_r(r.move_pts, 1), q=_r(r.q, 4),
                folds_same_sign=int(r.fs), finding=bool(r.tested and r.q < cfg.Q and r.fs >= 3),
                low_broken_10=_r(r.f_lobrk10_raw, 3), low_broken_10_base=_r(r.f_lobrk10_base, 3),
                high_broken_10=_r(r.f_hibrk10_raw, 3), high_broken_10_base=_r(r.f_hibrk10_base, 3),
                up_first_20=_r(r.f_upfirst20_raw, 3), up_first_20_base=_r(r.f_upfirst20_base, 3),
                mfe10=_r(r.f_mfe10_raw), mae10=_r(r.f_mae10_raw),
                holds_oos=(key in held) if key in tested_oos else None,
                path=pmap.get((r.und, int(r.tf), fam))))
        ctx = p2[(p2.family == fam) & p2.tested] if len(p2) else pd.DataFrame()
        works, fails = [], []
        if len(ctx):
            sig = ctx[(ctx.q < cfg.Q) & (ctx.fs >= 3)].sort_values("z", key=abs, ascending=False)
            for r in sig.head(8).itertuples():
                key = (r.und, int(r.tf), fam, r.cond)
                item = dict(und=r.und, tf=int(r.tf), when=COND.get(r.cond, r.cond), cond=r.cond, n=int(r.n),
                            eff_atr_5=_r(r.eff, 3), up_rate_5=_r(r.up_rate, 3), base_up_rate_5=_r(r.base_up_rate, 3),
                            move_pts_5=_r(r.move_pts, 1), q=_r(r.q, 4),
                            holds_oos=(key in held) if key in tested_oos else None)
                (works if r.eff > 0 else fails).append(item)
        cards.append(dict(family=fam, name=name, definition=defin, mechanism_hypothesis=why,
                          signal_source="the index candle (NIFTY / BANKNIFTY / SENSEX spot)", stats=stats,
                          up_more_than_matched=works, down_more_than_matched=fails))
    return cards


def verdict(card: dict) -> str:
    f = [s for s in card["stats"] if s["finding"]]
    held = [s for s in f if s.get("holds_oos")]
    if not f:
        ctx = card["up_more_than_matched"] + card["down_more_than_matched"]
        if ctx:
            return (f"no edge on its own (no instrument/timeframe passed); {len(ctx)} context cell(s) did "
                    f"(see 'when'), {sum(1 for c in ctx if c.get('holds_oos'))} held out of sample")
        return "no edge: after it, price moves like any matched candle (same time, trend, volatility, colour)"
    dirs = {("up" if s["eff_atr_5"] > 0 else "down") for s in f}
    return (f"{len(f)} instrument/timeframe finding(s), more {'/'.join(sorted(dirs))} than matched candles; "
            f"{len(held)} held out of sample")


def write_pack(cards: list, meta: dict) -> None:
    KDIR.mkdir(exist_ok=True)
    for c in cards:
        c["verdict"] = verdict(c)
    pack = dict(schema=1, study="R87 candle anatomy", date="2026-10-19", doc="research/HUNT_R87_CANDLE_ANATOMY.md",
                units="moves in ATR of the candle's own timeframe (ATR14 through the previous candle); pts = index "
                      "points; 'matched' = all candles of the same instrument, timeframe, time of day, trend, "
                      "volatility regime and colour",
                **meta, families=cards)
    (KDIR / "candle_pack.json").write_text(json.dumps(pack, indent=1, default=str))
    L = ["# Candle knowledge pack (R87)", "",
         "*For Jarvis and the agents to quote. Every number: n = candles, effect in ATR over the next 5 candles vs "
         "matched candles (same instrument, timeframe, time of day, trend, volatility regime, colour), q = BH. "
         "The 'reason' is the textbook hypothesis; the numbers say whether the follow-through happens. Signal "
         "source: the index candle. Source: research/HUNT_R87_CANDLE_ANATOMY.md.*", ""]
    for c in cards:
        L += [f"## {c['name']}", "", f"- What: {c['definition']}.", f"- Reason (hypothesis): {c['mechanism_hypothesis']}.",
              f"- Verdict: {c['verdict']}."]
        for s in c["stats"]:
            if s["tf"] == 1:
                continue
            L.append(f"- {s['und']} {s['tf']}m: n {s['n']:,}; up after 5 candles {s['up_rate_5']:.0%} vs "
                     f"{s['base_up_rate_5']:.0%} matched; effect {s['eff_atr_5']:+.3f} ATR ({s['move_pts_5']:+.1f} pts "
                     f"raw), q {s['q']}; low broken within 10: {s['low_broken_10']:.0%} vs {s['low_broken_10_base']:.0%}; "
                     f"high broken: {s['high_broken_10']:.0%} vs {s['high_broken_10_base']:.0%}"
                     + (" — FINDING" + (", held out of sample" if s.get("holds_oos") else
                                         (", did NOT hold out of sample" if s.get("holds_oos") is False else ""))
                        if s["finding"] else ""))
        for lab, key in (("Up more than matched when", "up_more_than_matched"),
                         ("Down more than matched when", "down_more_than_matched")):
            for w in c[key][:4]:
                L.append(f"- {lab}: {w['und']} {w['tf']}m {w['when']} (n {w['n']}, {w['eff_atr_5']:+.3f} ATR, q {w['q']}"
                         + (", held out of sample" if w.get("holds_oos") else
                            (", did NOT hold" if w.get("holds_oos") is False else "")) + ")")
        L.append("")
    (KDIR / "candle_pack.md").write_text("\n".join(L) + "\n")


def main() -> None:
    d = load("design")
    o = oos()
    pth = json.loads((cfg.RESULTS / "paths_design.json").read_text()) if (cfg.RESULTS / "paths_design.json").exists() else []
    summ = json.loads((cfg.RESULTS / "atlas_design_summary.json").read_text())
    meta = dict(design=f"{cfg.START} - {cfg.DESIGN_END}", holdout=f"{cfg.FINAL_START} - {cfg.END}",
                design_tests={k: v for k, v in summ.items() if k in ("P1", "P2", "P3")})
    if (cfg.RESULTS / "final_summary.json").exists():
        fs = json.loads((cfg.RESULTS / "final_summary.json").read_text())
        meta["holdout_check"] = dict(design_findings=fs["design_findings"], held=fs["holds"])
    write_pack(family_cards(d, o, pth), meta)


if __name__ == "__main__":
    main()
