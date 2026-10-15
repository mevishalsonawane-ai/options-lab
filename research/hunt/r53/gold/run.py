"""R53 gold book runner (PREREG.md, 15 Oct 2026).

    python -m research.hunt.r53.gold.run repro  [--data F]   old window (Oct 2023 - 25 Sep 2026): each adapter vs the
                                                             study's own function and the study's write-up
    python -m research.hunt.r53.gold.run design [--data F]   design 20 Nov 2024 - 31 Mar 2026 -> results/gold_design.json
    python -m research.hunt.r53.gold.run final  [--data F]   ONCE, after design: the original + the design pick of each
                                                             rule on 1 Apr - 13 Oct 2026 -> results/gold_final.json

F defaults to $R53_XAU or <cache>/xau/xauusd_m1_bid_to20261012.csv.gz (Dukascopy 1m BID, never in git). Indicators run
on the whole series from Oct 2023 (warm-up); only trades ENTERED on or after 20 Nov 2024 count. A trade belongs to the
period of its entry day. The final read is locked by common.open_final("gold", ...) (results/final_read_gold.json).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import warnings
from pathlib import Path

import numpy as np
import pandas as pd

from .. import common as C
from . import rules as R
from . import twins as T

warnings.filterwarnings("ignore")
TRADES_DIR = C.RESULTS / "trades"
CSV_LIMIT = 200_000          # bytes; bigger per-trade files go to the cache folder
REPRO_END = "2026-09-26"     # the old studies' data ended 25 Sep 2026


def default_data() -> str:
    return os.environ.get("R53_XAU", str(C.cache_dir() / "xau" / "xauusd_m1_bid_to20261012.csv.gz"))


def log(*a):
    print(*a, flush=True)


# ------------------------------------------------------------------------------------------------ metrics
def period_trades(trades, part):
    return [t for t in trades if C.part_of(t["entry_ts"]) == part]


def trading_days(bid: pd.DataFrame, part: str) -> int:
    d = pd.DatetimeIndex(bid.index).normalize().unique()
    d = d[d.dayofweek < 5]
    return int(T.in_part_mask(d, part).sum())


def twin_nets(ctx, minutes, rule, var, spec, trades, part):
    kind = spec["twin"][0]
    rng = T.rng_for("gold", rule, var, part)
    if kind == "random":
        cands = ctx.get(("cands", rule, var), lambda: spec["twin"][1](ctx))
        return T.random_twins(cands, len(trades), part, rng), "random entries, the rule's own exits"
    _, window, swap = spec["twin"]
    holds = [max(1, int((t["exit_ts"] - t["entry_ts"]) / pd.Timedelta(minutes=1))) for t in trades]
    return T.hold_twins(minutes, holds, part, window, swap, rng), "hold-matched random entries" + (" (swap)" if swap else "")


def metrics(ctx, minutes, rule, var, spec, trades, part, ndays):
    s = C.summary(trades)
    tw, kind = twin_nets(ctx, minutes, rule, var, spec, trades, part)
    swap_info = float(sum(100 * R.SWAP * t["nights"] for t in trades))
    charged = spec["twin"][0] == "hold" and spec["twin"][2]
    return {
        "summary": s,
        "net_per_trading_day": s["net"] / ndays if ndays else None,
        "trading_days": ndays,
        "p": C.twin_p(s["net"], tw),
        "twins": {"kind": kind, "n": int(len(tw)), "mean": float(np.mean(tw)), "p95": float(np.percentile(tw, 95))},
        "swap": {"charged": bool(charged), "usd_if_charged" if not charged else "usd_charged": swap_info},
        "exits": pd.Series([t["why"] for t in trades]).value_counts().to_dict() if trades else {},
        "avg_hold_h": float(np.mean([(t["exit_ts"] - t["entry_ts"]) / pd.Timedelta(hours=1) for t in trades])) if trades else None,
    }


def write_trades(rule, var, part, trades, cache: Path) -> str:
    if not trades:
        return ""
    df = pd.DataFrame(trades)[["signal_ts", "entry_ts", "exit_ts", "side", "entry", "exit", "net", "why", "nights"]]
    text = df.to_csv(index=False, float_format="%.4f")
    name = f"gold_{rule}_{var}_{part}.csv"
    if len(text.encode()) < CSV_LIMIT:
        TRADES_DIR.mkdir(parents=True, exist_ok=True)
        p = TRADES_DIR / name
    else:
        cache.mkdir(parents=True, exist_ok=True)
        p = cache / name
    p.write_text(text)
    return str(p.relative_to(C.HERE)) if p.is_relative_to(C.HERE) else str(p)


def entry_window_check(trades) -> int:
    """Trades entered before 20 Nov 2024 must never be counted (part_of returns None for them)."""
    return sum(1 for t in trades if C.part_of(t["entry_ts"]) is None and t["entry_ts"] >= pd.Timestamp(C.FIRST_DAY))


# ------------------------------------------------------------------------------------------------ design
def design(data: str):
    t0 = time.time()
    bid = R.load(data)
    ctx = R.Ctx(bid)
    minutes = T.Minutes(ctx.mid)
    nd = trading_days(bid, "design")
    cache = Path(data).resolve().parent / "r53_gold_trades"
    out = {"_meta": {"data": Path(data).name, "first_bar": str(bid.index.min()), "last_bar": str(bid.index.max()),
                     "design": [str(C.DESIGN[0]), str(C.DESIGN[1])], "trading_days": nd, "n_twins": C.N_TWINS,
                     "money": "USD per standard lot (100 oz) after the study's costs",
                     "fills": "next-bar; stops gapped through fill at the bar's open (R53 gap rule)"}}
    for rule, spec in R.RULES.items():
        res, vt = {}, {}
        for var, vs in spec["variants"].items():
            allt = vs["fn"](ctx)
            tr = period_trades(allt, "design")
            vt[var] = tr
            res[var] = metrics(ctx, minutes, rule, var, vs, tr, "design", nd)
            res[var]["trades_csv"] = write_trades(rule, var, "design", tr, cache)
            s = res[var]["summary"]
            log(f"{rule:8s} {var:15s} n={s['trades']:4d} net={s['net']:+10,.0f} p={res[var]['p']:.3f}  ({time.time() - t0:.0f}s)")
        wf = C.walk_forward(vt, "orig")
        out[rule] = {"name": spec["name"], "source": spec["src"], "signal_source": "U (XAUUSD itself), buys only",
                     "twin_kind": res["orig"]["twins"]["kind"], "variants": res, "walk_forward": wf,
                     "design_pick": wf["design_pick"]}
        log(f"{rule:8s} WF orig {wf['orig_wf_net']:+,.0f} retune {wf['retune_wf_net']:+,.0f} picks {wf['retune_picks']} pick {wf['design_pick']}")
    p = C.save("gold_design.json", out)
    log("wrote", p)


# ------------------------------------------------------------------------------------------------ final
def final(data: str):
    dpath = C.RESULTS / "gold_design.json"
    if not dpath.exists():
        raise SystemExit("run design first")
    des = json.loads(dpath.read_text())
    ideas = []
    for rule in R.RULES:
        ideas.append(f"{rule}:orig")
        if des[rule]["design_pick"]:
            ideas.append(f"{rule}:{des[rule]['design_pick']}")
    C.open_final("gold", ideas)                 # raises FinalReadError on a second read: never re-run
    t0 = time.time()
    bid = R.load(data)
    ctx = R.Ctx(bid)
    minutes = T.Minutes(ctx.mid)
    nd = trading_days(bid, "final")
    cache = Path(data).resolve().parent / "r53_gold_trades"
    out = {"_meta": {"data": Path(data).name, "last_bar": str(bid.index.max()), "final": [str(C.FINAL[0]), str(C.FINAL[1])],
                     "trading_days": nd, "ideas": ideas, "n_twins": C.N_TWINS,
                     "money": "USD per standard lot (100 oz) after the study's costs"}}
    for rule, spec in R.RULES.items():
        out[rule] = {"name": spec["name"], "design_pick": des[rule]["design_pick"],
                     "design_orig_wf_net": des[rule]["walk_forward"]["orig_wf_net"],
                     "design_retune_wf_net": des[rule]["walk_forward"]["retune_wf_net"], "variants": {}}
        for var in ["orig"] + ([des[rule]["design_pick"]] if des[rule]["design_pick"] else []):
            vs = spec["variants"][var]
            tr = period_trades(vs["fn"](ctx), "final")
            m = metrics(ctx, minutes, rule, var, vs, tr, "final", nd)
            m["trades_csv"] = write_trades(rule, var, "final", tr, cache)
            real = ctx.bid.index
            m["fills_on_missing_minutes"] = {
                "entries": int(sum(1 for t in tr if t["entry_ts"] not in real)),
                "exits_at_minute_prices": int(sum(1 for t in tr if t["why"] not in ("stop", "targets", "tracker", "stop/lock", "break",
                                                                               "data end", "") and t["exit_ts"] not in real))}
            m["open_at_data_end"] = sum(1 for t in tr if t["why"] in ("data end",) or t["exit_ts"] >= bid.index.max() - pd.Timedelta(minutes=1))
            out[rule]["variants"][var] = m
            s = m["summary"]
            log(f"{rule:8s} {var:15s} n={s['trades']:4d} net={s['net']:+10,.0f} p={m['p']:.3f}  ({time.time() - t0:.0f}s)")
    p = C.save("gold_final.json", out)
    log("wrote", p)


# ------------------------------------------------------------------------------------------------ reproduction
DOC = {   # the old write-ups' 3-year numbers (USD a lot, trades): research/GOLD_*.md, LIQUIDITY_GOLD*.md
    ("GB1", "orig"): (15879, 265), ("GB1", "rt_1R"): (-7470, 266), ("GB1", "rt_oppcross"): (13456, 316),
    ("GB2", "orig"): (3957, 269), ("GB2", "rt_200"): (-25253, 269),
    ("GB3", "orig"): (72557, 947), ("GB3", "rt_notargets"): (135707, 947),
    ("GB4-4h", "orig"): (154843, 59), ("GB4-30m", "orig"): (175452, 470),
    ("GB5-1h", "orig"): (87609, 445), ("GB5-4h", "orig"): (131910, 165),
    ("GB6", "orig"): (62714, 716), ("GB6", "rt_fixed2300"): (81223, 707),
    ("GB7", "orig"): (40672, 133), ("GB8", "orig"): (101195, 81), ("GB9", "orig"): (121593, 145),
    ("GB10", "orig"): (33309, 488), ("GB11", "orig"): (37730, 424), ("GB11", "rt_4candles"): (21033, 450),
    ("GB12", "orig"): (154337, 947), ("GB12", "rt_giveback1.5"): (122485, 991),
    ("GB13", "orig"): (207628, 52), ("GB13", "rt_giveback5"): (310766, 52),
}


def study_own(ctx, rule, var):
    """The same rule computed by the study's OWN function where one exists (list of USD a lot), else None."""
    gs, tas, ai, lg, p1, gc = R.gs, R.tas, R.ai, R.lg, R.p1, R.gc
    if rule == "GB1":
        mode = {"orig": "2R", "rt_1R": "1R", "rt_oppcross": "opposite cross"}[var]
        df = gs.option1(ctx.M, ctx.mid, 15, mode, True)
        return list(df[df.side > 0].usd)
    if rule == "GB2":
        df = gs.option2(ctx.M, ctx.mid, 5, {"orig": 1.0, "rt_200": 2.0}[var])
        return list(df[df.side > 0].usd)
    if rule in ("GB3", "GB4-4h", "GB4-30m"):
        tf, tps = {("GB3", "orig"): (15, tas.TPS), ("GB3", "rt_notargets"): (15, ()), ("GB4-4h", "orig"): (240, tas.TPS),
                   ("GB4-30m", "orig"): (30, ())}[(rule, var)]
        b, trk, d, pct = R._tas_frame(ctx, tf)
        return [x for _, x in tas.trades(b, trk, d, pct, 1, True, tps=tps)]
    if rule in ("GB5-1h", "GB5-4h"):
        b, sig, atr = R._ai_frame(ctx, 60 if rule == "GB5-1h" else 240)
        return [x for _, x in ai.trade(b, sig, atr, 1)]
    if rule in ("GB7", "GB10"):
        start, end, first, last, cut = lg.SESSIONS["India hours"]
        days = ctx.sessions(start, end)
        res = []
        for tf in ((60, 15) if rule == "GB7" else (15, 5)):
            b = lg.bars(days, tf)
            df = lg.simulate(days, b, R.swing_zones(b, 20, "full") + R.pool_zones(b, 2, 5, 10), first, last, cut)
            res += list(100 * df[df.sign > 0].usd)
        return res
    if rule in ("GB8", "GB9"):
        tf, conf = (120, 15) if rule == "GB8" else (60, 20)
        days = ctx.sessions(p1.START, p1.END)
        b = lg.bars(days, tf)
        return list(p1.simulate(days, b, R.swing_zones(b, 20, "full") + R.pool_zones(b, 2, 5, conf), second_target=True).usd)
    if rule == "GB11":
        b, P, lo3, hi3, ema50 = R._gc_frame(ctx, 60)
        lgs, shs = P["hammer / pin bar"]
        df = gc.run(ctx.M, b, 60, lgs, shs, lo3, hi3, ema50, False, {"orig": "2R", "rt_4candles": "4 candles"}[var])
        return list(df[df.side > 0].usd)
    return None                                  # GB6 / GB12: main()-only scripts; GB13 calls the study's run() itself


def study_fn_for_repro(ctx, rule, var):
    """The adapter with the study's exact fills (no R53 gap rule) for the reproduction."""
    v = {
        ("GB1", "orig"): lambda: R.gb1(ctx, "2R", gap=False), ("GB1", "rt_1R"): lambda: R.gb1(ctx, "1R", gap=False),
        ("GB1", "rt_oppcross"): lambda: R.gb1(ctx, "opposite cross", gap=False),
        ("GB2", "orig"): lambda: R.gb2(ctx, 1.0, gap=False), ("GB2", "rt_200"): lambda: R.gb2(ctx, 2.0, gap=False),
        ("GB5-1h", "orig"): lambda: R.gb5(ctx, 60, gap=False), ("GB5-4h", "orig"): lambda: R.gb5(ctx, 240, gap=False),
        ("GB11", "orig"): lambda: R.gb11(ctx, "2R", gap=False), ("GB11", "rt_4candles"): lambda: R.gb11(ctx, "4 candles", gap=False),
    }.get((rule, var))
    return v() if v else R.RULES[rule]["variants"][var]["fn"](ctx)


def repro(data: str):
    bid = R.load(data, end=REPRO_END)
    ctx = R.Ctx(bid)
    out = {"_meta": {"window": [str(bid.index.min()), str(bid.index.max())]}}
    for rule, spec in R.RULES.items():
        for var, vs in spec["variants"].items():
            mine = study_fn_for_repro(ctx, rule, var)
            gapped = vs["fn"](ctx)
            own = study_own(ctx, rule, var)
            net = float(sum(t["net"] for t in mine))
            doc_net, doc_n = DOC[(rule, var)]
            row = {"adapter_n": len(mine), "adapter_net": round(net), "doc_n": doc_n, "doc_net": doc_net,
                   "r53_fills_net": round(sum(t["net"] for t in gapped)), "r53_fills_n": len(gapped)}
            if own is not None:
                a = np.sort(np.array([t["net"] for t in mine]))
                b = np.sort(np.array(own, dtype=float))
                row["study_fn_n"], row["study_fn_net"] = len(own), round(float(b.sum()))
                row["identical_to_study_fn"] = bool(len(a) == len(b) and np.allclose(a, b, atol=1e-6))
            out[f"{rule}:{var}"] = row
            log(rule, var, row)
    C.save("gold_repro.json", out)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["repro", "design", "final"])
    ap.add_argument("--data", default=default_data())
    a = ap.parse_args(argv)
    {"repro": repro, "design": design, "final": final}[a.cmd](a.data)


if __name__ == "__main__":
    sys.exit(main())
