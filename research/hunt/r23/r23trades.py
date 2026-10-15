#!/usr/bin/env python3
"""R23 trading test (PREREG.md A3 configurations, A4 selection, A5 the one lake read). Research only, 13 Oct 2026.

    python3 research/hunt/r23/r23trades.py release     # Steps 2-3 on the release (ALL design data) -> results/release.json|md
    python3 research/hunt/r23/r23trades.py freeze      # Part C: writes results/finalists.json from results/release.json
    R23_SOURCE=lake python3 research/hunt/r23/r23trades.py lake   # Step 4: the ONE read of the fresh lake holdout

A configuration's trade on one index and day: the FIRST close of its break candle beyond each range edge inside the
window (after the range ends, closing by 14:00) buys 1 lot of the call (up) / put (down) at the ORB arm's strike (ATM of
the 09:20 bar's close, held all day), nearest expiry AFTER the day, entry at the next minute's open, ORB exits; all of it
is R22's `trade()` (R17 fills 'liq', app charges). Baselines: today's ORB (R20's V0 via R22) and `r22-brk15`.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import date
from typing import Callable

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r23cfg as CFG  # noqa: E402  (sets the data switch for R22 / R17 before they are imported)
import r23data as DATA  # noqa: E402
import r23features as F  # noqa: E402
import r23ranges as R  # noqa: E402
import r22trades as T22  # noqa: E402
import r22study as S22  # noqa: E402
import arms as A  # noqa: E402
from research.obuy.costs import Fills  # noqa: E402

TWINS = 1000
FILTER_CUTS = {"twap_al": 0.21982, "brk_str": 0.03538}     # Part B: medians over the 1,242 design ORB-range breaks
F1 = ("twap_al",)
F2 = ("twap_al", "brk_str")
BASELINES = ("orb-v0", "r22-brk15")
TOP_N = 3


@dataclass(frozen=True)
class Config:
    id: str
    family: str
    mk: Callable                   # Sess -> range dict
    tf: int
    filt: tuple = field(default=())


def _t(T):
    return lambda s: R.time_range(s.ix, T)


CONFIGS = [
    *[Config(f"r23-t{T}-5m", "a", _t(T), 5) for T in (15, 30, 45, 60, 75)],
    *[Config(f"r23-t{T}-15m", "b", _t(T), 15) for T in (15, 30, 60, 75)],
    *[Config(f"r23-t{T}-30m", "b", _t(T), 30) for T in (30, 60)],
    *[Config(f"r23-vol{int(k * 100)}", "c", (lambda k: lambda s: R.vol_range(s.ix, s.atr, k))(k), 5) for k in (0.30, 0.40, 0.50)],
    Config("r23-va60-5m", "d", lambda s: R.va_range(s.ix, 60), 5),
    Config("r23-va60-15m", "d", lambda s: R.va_range(s.ix, 60), 15),
    Config("r23-pd-or45", "e", lambda s: R.pd_range(s.ix, 45, s.prev[1], s.prev[2]), 5),
    Config("r23-gap-or30", "e", lambda s: R.gap_range(s.ix, 30, s.prev[3]), 5),
    Config("r23-orb-f1", "f", _t(50), 5, F1),
    Config("r23-orb-f2", "f", _t(50), 5, F2),
    Config("r23-r22-f1", "f", _t(45), 15, F1),
    Config("r23-r22-f2", "f", _t(45), 15, F2),
]
BY_ID = {c.id: c for c in CONFIGS}
assert len(CONFIGS) == 22


# ------------------------------------------------------------------------------------------------ trades
def passes(feats: dict, filt: tuple, cuts: dict) -> bool:
    return all(np.isfinite(feats[f]) and feats[f] > cuts[f] for f in filt)


def config_day(cfg: Config, s, rg=None, cuts=None) -> list[dict]:
    """The configuration's trades on one index-day (rg: an override range, e.g. the mirror twin)."""
    rg = rg or cfg.mk(s)
    if not (rg["hi"] > rg["lo"]):
        return []
    out = []
    for side, k, row in R.first_breaks(s.ix, rg, cfg.tf):
        feats = None
        if cfg.filt:
            feats = F.features(s, rg, side, row)
            if not passes(feats, cfg.filt, cuts or FILTER_CUTS):
                continue
        t = T22.trade(s.d17, s.ctx, side, row)
        if t:
            t["k"], t["edge_px"] = k, rg["hi"] if side > 0 else rg["lo"]
            if feats is not None:
                t["feats"] = {f: feats[f] for f in F2}
            out.append(t)
    return out


def items(period: str) -> list:
    return [s for u in CFG.TRADE_UNDS for s in DATA.sessions(u) if CFG.period_of(s.day) == period]


def run(cid: str, period: str, cuts=None, mirror_days: set | None = None) -> list[dict]:
    out = []
    for s in items(period):
        if cid == "orb-v0":
            out += T22.baseline_day(s.d17, s.ctx)
        elif cid == "r22-brk15":
            out += T22.rule_day(T22.RULES["r22-brk15"], s.d17, s.ctx)
        else:
            cfg = BY_ID[cid]
            rg = cfg.mk(s)
            if mirror_days and (s.und, s.day) in mirror_days:
                rg = R.mirror(rg)
            out += config_day(cfg, s, rg, cuts)
    return out


def sessions_of(period: str) -> list:
    return sorted({s.day for s in items(period)})


def report(trades: list, period: str) -> dict:
    ss = sessions_of(period)
    out = dict(all=T22.score(trades, ss))
    for u in CFG.TRADE_UNDS:
        su = sorted({s.day for s in DATA.sessions(u) if CFG.period_of(s.day) == period})
        out[u] = T22.score([t for t in trades if t["und"] == u], su)
    out["blocks"] = T22.blocks(trades, ss, CFG.N_BLOCKS)
    out["exits"] = {w: int(sum(1 for t in trades if t["why"] == w)) for w in sorted({t["why"] for t in trades})}
    out["sides"] = {str(sd): T22.score([t for t in trades if t["side"] == sd], ss)["net"] for sd in (1, -1)}
    return out


# ------------------------------------------------------------------------------------------------ selection
def rank_key(nets: list[float], total: float):
    """A4: (positive blocks, worst block, total), highest first."""
    return (sum(n > 0 for n in nets), min(nets), total)


def select(block_nets: dict, base_nets: list[float], blocks: list[int], top=TOP_N) -> list[str]:
    """The A4 rule on the given blocks: eligible = total > the ORB's total on those blocks; ranked by rank_key."""
    base = sum(base_nets[b] for b in blocks)
    elig = []
    for cid, nets in block_nets.items():
        sub = [nets[b] for b in blocks]
        if sum(sub) > base:
            elig.append((rank_key(sub, sum(sub)), cid))
    elig.sort(key=lambda x: x[0], reverse=True)
    return [cid for _, cid in elig[:top]]


def block_of(day, ss) -> int:
    cuts = np.array_split(np.arange(len(ss)), CFG.N_BLOCKS)
    for b, c in enumerate(cuts):
        if ss[c[0]] <= day <= ss[c[-1]]:
            return b
    raise ValueError(day)


def block_nets(trades, ss) -> list[float]:
    out = [0.0] * CFG.N_BLOCKS
    for t in trades:
        out[block_of(t["day"], ss)] += t["net"]
    return out


def fit_cuts(days: set) -> dict:
    """The (f) cuts refit as the median over the ORB-range breaks of `days` (Step 1's rows, all event indices)."""
    import pandas as pd
    ev = pd.read_pickle(os.path.join(CFG.OUT, "anatomy_rows.pkl"))
    e = ev[(ev.ref == "ORB") & (ev.cls != "NONE") & ev.day.isin(days)]
    return {f: float(np.nanmedian(e[f].astype(float))) for f in F2}


def filtered(trades, filt, cuts):
    return [t for t in trades if passes(t["feats"], filt, cuts)]


def nested_wf(all_trades: dict, ss: list) -> dict:
    """A4: for k = 2, 3, 4 select on blocks 1 .. k-1 (the (f) cuts refit there) and score block k."""
    cuts_all = np.array_split(np.arange(len(ss)), CFG.N_BLOCKS)
    base = block_nets(all_trades["orb-v0"], ss)
    r22 = block_nets(all_trades["r22-brk15"], ss)
    out = []
    for k in range(1, CFG.N_BLOCKS):
        train = list(range(k))
        tdays = {ss[i] for b in train for i in cuts_all[b]}
        cuts = fit_cuts(tdays)
        nets = {}
        for c in CONFIGS:
            tr = all_trades[c.id + "#raw"] if c.filt else all_trades[c.id]
            if c.filt:
                tr = filtered(tr, c.filt, cuts)
            nets[c.id] = block_nets(tr, ss)
        picks = select(nets, base, train)
        test = {cid: nets[cid][k] for cid in nets}
        out.append(dict(test_block=k + 1, train_blocks=[b + 1 for b in train], cuts=cuts, picks=picks,
                        top1=test[picks[0]] if picks else None,
                        top3_mean=float(np.mean([test[p] for p in picks])) if picks else None,
                        orb=base[k], r22=r22[k], all_mean=float(np.mean(list(test.values()))),
                        all_positive=int(sum(v > 0 for v in test.values()))))
    return dict(folds=out, top1_total=float(sum(f["top1"] or 0.0 for f in out)),
                orb_total=float(sum(f["orb"] for f in out)), r22_total=float(sum(f["r22"] for f in out)),
                all_mean_total=float(sum(f["all_mean"] for f in out)))


# ------------------------------------------------------------------------------------------------ twins
def twin_level(cid, period, actual, n=TWINS, seed=CFG.SEED):
    """Per index-day: the configuration's net with the real range and with the range mirrored around p_end; each twin
    takes the mirror on a random half of the days."""
    cfg = BY_ID.get(cid)
    rng = np.random.default_rng([seed, 1])
    real, mir = [], []
    for s in items(period):
        if cfg is None:                                 # a baseline: R22's levels and its own mirror
            if cid == "r22-brk15":
                real.append(sum(t["net"] for t in T22.rule_day(T22.RULES[cid], s.d17, s.ctx)))
                mir.append(sum(t["net"] for t in T22.rule_day(T22.RULES[cid], s.d17, s.ctx, T22.LV.mirror(s.ctx.lv))))
            else:
                return None, None, None
            continue
        rg = cfg.mk(s)
        real.append(sum(t["net"] for t in config_day(cfg, s, rg)))
        mir.append(sum(t["net"] for t in config_day(cfg, s, R.mirror(rg))))
    real, mir = np.array(real), np.array(mir)
    pick = rng.random((n, len(real))) < 0.5
    nets = np.where(pick, mir[None, :], real[None, :]).sum(axis=1)
    return float((1 + (nets >= actual - 1e-9).sum()) / (n + 1)), float(nets.mean()), float(mir.sum())


def twin_time(cid, period, trades, n=TWINS, seed=CFG.SEED):
    """Each trade re-entered at a random decision candle of the configuration's own window that day."""
    rng = np.random.default_rng([seed, 2])
    by = {(s.und, s.day): s for s in items(period)}
    if cid == "orb-v0":
        tf, end = 5, 49
    elif cid == "r22-brk15":
        tf, end = 15, 44
    else:
        tf, end = BY_ID[cid].tf, None
    tables = []
    for t in trades:
        s = by[(t["und"], t["day"])]
        e = end if end is not None else BY_ID[cid].mk(s)["end"]
        k0, k1 = R.window(e, tf)
        nets = []
        for k in range(k0, k1 + 1):
            x = T22.trade(s.d17, s.ctx, t["side"], R.row_of(k, tf))
            nets.append(x["net"] if x else 0.0)
        tables.append(np.array(nets))
    actual = sum(t["net"] for t in trades)
    tot = np.zeros(n)
    for tb in tables:
        tot += tb[rng.integers(0, len(tb), n)]
    return float((1 + (tot >= actual - 1e-9).sum()) / (n + 1)), float(tot.mean())


def net_2x(cid, period):
    A.FILLS = T22.Fills2x(mode="liq")
    try:
        return float(sum(t["net"] for t in run(cid, period)))
    finally:
        A.FILLS = Fills(mode="liq")


def prior_pvalues() -> dict:
    """The 58 holdout p-values of R20 / R20b / R20c / R22 (R22 results/holdout.json's BH table)."""
    p = os.path.join(CFG.HERE, "..", "r22", "results", "holdout.json")
    return {k: v["p"] for k, v in json.load(open(p))["_bh"]["table"].items()}


def bh(pvals: dict, q: float):
    return T22.bh(pvals, q)


# ------------------------------------------------------------------------------------------------ Steps 2-3 (release)
def strip(trs):
    return [dict(und=t["und"], day=str(t["day"]), side=t["side"], row=t["row"], net=round(t["net"], 2), why=t["why"])
            for t in trs]


def release(n_twins=TWINS):
    if CFG.SOURCE != "release":
        sys.exit("Steps 2-3 run on the release only (R23_SOURCE=release)")
    ss = sessions_of("design")
    res, all_tr = {}, {}
    for cid in list(BASELINES) + [c.id for c in CONFIGS]:
        cfg = BY_ID.get(cid)
        if cfg is not None and cfg.filt:
            raw = [t for s in items("design") for t in config_day(Config(cid, "f", cfg.mk, cfg.tf, ()), s)]
            for t in raw:                                              # features of every unfiltered first signal
                s = next(x for x in DATA.sessions(t["und"]) if x.day == t["day"])
                t["feats"] = {f: F.features(s, cfg.mk(s), t["side"], t["row"])[f] for f in F2}
            all_tr[cid + "#raw"] = raw
            tr = filtered(raw, cfg.filt, FILTER_CUTS)
        else:
            tr = run(cid, "design")
        all_tr[cid] = tr
        r = report(tr, "design")
        r["family"] = cfg.family if cfg else "baseline"
        r["block_nets"] = [b["net"] for b in r["blocks"]]
        res[cid] = r
        print(cid, r["all"]["trades"], round(r["all"]["net"]), [round(x) for x in r["block_nets"]], flush=True)
    # the ORB range under the common R23 rule, unfiltered (reference for the (f) filters; not a configuration)
    res["ref-orb-first"] = report(all_tr["r23-orb-f1#raw"], "design")
    res["ref-r22-first"] = report(all_tr["r23-r22-f1#raw"], "design")
    base_nets = res["orb-v0"]["block_nets"]
    nets = {c.id: res[c.id]["block_nets"] for c in CONFIGS}
    finalists = select(nets, base_nets, list(range(CFG.N_BLOCKS)))
    wf = nested_wf(all_tr, ss)
    pv = {f"R23 {c.id} release-t": res[c.id]["all"]["p"] for c in CONFIGS}
    B = bh(dict(prior_pvalues(), **pv), 0.10)
    for c in CONFIGS:
        res[c.id]["q_desc"] = B[f"R23 {c.id} release-t"][1]
    for cid in list(BASELINES) + finalists:
        r = res[cid]
        net = r["all"]["net"]
        r["p_level"], r["twin_level_mean"], r["mirror_all_net"] = twin_level(cid, "design", net, n_twins)
        r["p_time"], r["twin_time_mean"] = twin_time(cid, "design", all_tr[cid], n_twins)
        r["net_2x_ticks"] = net_2x(cid, "design")
        print("twins", cid, r["p_level"], r["p_time"], round(r["net_2x_ticks"]), flush=True)
    for cid in finalists:
        r = res[cid]
        r["flag"] = "promising" if (r["all"]["net"] > 0 and sum(x > 0 for x in r["block_nets"]) >= 3) else \
            "carried, not promising"
    out = dict(source=CFG.SOURCE, sessions=len(ss), first=str(ss[0]), last=str(ss[-1]), results=res, finalists=finalists,
               nested_wf=wf, bh_desc=dict(m=len(B), q=0.10, rejected=[k for k, v in B.items() if v[2]],
                                          smallest=sorted(((k, v[1]) for k, v in B.items()), key=lambda x: x[1])[:8]),
               trades={cid: strip(all_tr[cid]) for cid in list(BASELINES) + finalists})
    os.makedirs(CFG.RESULTS, exist_ok=True)
    json.dump(S22.jsonable(out), open(os.path.join(CFG.RESULTS, "release.json"), "w"), indent=1)
    print("finalists", finalists, "nested", {k: v for k, v in wf.items() if k != "folds"})
    tables()
    return out


# ------------------------------------------------------------------------------------------------ Part C and Step 4
def freeze():
    rel = json.load(open(os.path.join(CFG.RESULTS, "release.json")))
    fz = dict(written=str(date.today()), finalists=rel["finalists"], filter_cuts=FILTER_CUTS, baselines=list(BASELINES),
              release_p={c.id: rel["results"][c.id]["all"]["p"] for c in CONFIGS},
              lake=dict(design_last=str(CFG.LAST_EXAMINED), holdout_start=str(CFG.LAKE_HOLDOUT_START),
                        min_holdout_sessions=CFG.MIN_HOLD_SESSIONS, twins=TWINS, seed=CFG.SEED, bh_q=0.10))
    json.dump(fz, open(os.path.join(CFG.HERE, "results", "finalists.json"), "w"), indent=1)
    print(json.dumps(fz, indent=1))


PART_C = "a9a21ed9"        # the commit that froze results/finalists.json (PREREG.md part C)


def committed(path: str) -> bool:
    """finalists.json exists in the Part C commit and is byte-identical to it in HEAD and the working tree."""
    rel = os.path.relpath(path, CFG.HERE)
    a = subprocess.run(["git", "diff", "--quiet", PART_C, "--", rel], cwd=CFG.HERE)
    b = subprocess.run(["git", "cat-file", "-e", f"{PART_C}:research/hunt/r23/results/finalists.json"], cwd=CFG.HERE)
    return a.returncode == 0 and b.returncode == 0


def lake(n_twins=TWINS):
    if CFG.SOURCE != "lake":
        sys.exit("the lake read needs R23_SOURCE=lake")
    fz_path = os.path.join(CFG.HERE, "results", "finalists.json")
    if not os.path.exists(fz_path) or not committed(fz_path):
        sys.exit("Part C (results/finalists.json) must be committed, unchanged, before the lake read")
    if os.path.exists(CFG.LAKE_FLAG):
        sys.exit("the R23 lake holdout has already been read once (flag exists)")
    fz = json.load(open(fz_path))
    for u in CFG.TRADE_UNDS:
        n = sum(1 for s in DATA.sessions(u) if CFG.period_of(s.day) == "holdout")
        if n < CFG.MIN_HOLD_SESSIONS:
            sys.exit(f"{u}: {n} lake holdout sessions < {CFG.MIN_HOLD_SESSIONS}; the read waits")
    os.makedirs(CFG.OUT, exist_ok=True)
    open(CFG.LAKE_FLAG, "w").write(str(date.today()))
    FILTER_CUTS.update(fz["filter_cuts"])
    ids = list(BASELINES) + fz["finalists"]
    res, pv = {}, {}
    for cid in ids:
        des, hold = run(cid, "design"), run(cid, "holdout")
        r = dict(design=report(des, "design"), holdout=report(hold, "holdout"))
        r["design_block_nets"] = [b["net"] for b in r["design"]["blocks"]]
        r["trades_list"] = strip(hold)
        res[cid] = r
        print(cid, round(r["design"]["all"]["net"]), round(r["holdout"]["all"]["net"]), flush=True)
    base = res["orb-v0"]["holdout"]["all"]["net"]
    for cid in fz["finalists"]:
        r = res[cid]
        hold = run(cid, "holdout")
        net = r["holdout"]["all"]["net"]
        r["p_t"] = r["holdout"]["all"]["p"]
        r["p_level"], r["twin_level_mean"], r["mirror_all_net"] = twin_level(cid, "holdout", net, n_twins)
        r["p_time"], r["twin_time_mean"] = twin_time(cid, "holdout", hold, n_twins)
        r["net_2x_ticks"] = net_2x(cid, "holdout")
        r["L1"], r["L2"] = net > 0, net > base
        r["L6"] = sum(x > 0 for x in r["design_block_nets"]) >= 3
        pv[f"R23 {cid} lake G3"], pv[f"R23 {cid} lake L3"], pv[f"R23 {cid} lake L4"] = r["p_t"], r["p_level"], r["p_time"]
    fam = dict(prior_pvalues(), **{f"R23 {k} release-t": v for k, v in fz["release_p"].items()}, **pv)
    B = bh(fam, 0.10)
    for cid in fz["finalists"]:
        r = res[cid]
        r["q"] = {g: B[f"R23 {cid} lake {g}"][1] for g in ("G3", "L3", "L4")}
        r["L5"] = all(B[f"R23 {cid} lake {g}"][2] for g in ("G3", "L3", "L4"))
        r["PASS"] = bool(r["L1"] and r["L2"] and r["L5"] and r["L6"])
    res["_bh"] = dict(m=len(fam), q=0.10, table={k: dict(p=v[0], q=v[1]) for k, v in B.items()})
    os.makedirs(CFG.RESULTS, exist_ok=True)
    json.dump(S22.jsonable(res), open(os.path.join(CFG.RESULTS, "lake_holdout.json"), "w"), indent=1)
    return res


# ------------------------------------------------------------------------------------------------ tables
def _q(r):
    return "-" if r.get("q_desc") is None else f"{r['q_desc']:.2f}"


def _r(v):
    return "n/a" if v is None else f"{v:,.0f}"


def tables():
    """results/release.md from results/release.json."""
    j = json.load(open(os.path.join(CFG.RESULTS, "release.json")))
    res = j["results"]
    L = [f"# R23 Steps 2-3 on the release ({j['sessions']} calendar days, {j['first']} .. {j['last']}; ALL design data)", "",
         "Rs after costs, BANKNIFTY + NIFTY, 1 lot. Blocks = 4 consecutive equal blocks of the calendar days. p = one-sided "
         "t-test of the daily net (in-sample: descriptive only). q = BH over the 58 earlier holdout p-values + these 22.", "",
         "| id | family | trades | net | per day | green trade days | worst day | max DD | blocks 1-4 | + blocks | BANKNIFTY / NIFTY | p | q |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for cid in list(BASELINES) + [c.id for c in CONFIGS]:
        r = res[cid]
        a = r["all"]
        b = r["block_nets"]
        L.append(f"| `{cid}` | {r['family']} | {a['trades']:,} | {_r(a['net'])} | {a['per_session']:+.0f} | "
                 f"{100 * a['green_trade_days']:.0f}% | {_r(a['worst_day'])} | {_r(a['max_dd'])} | "
                 f"{' / '.join(_r(x) for x in b)} | {sum(x > 0 for x in b)} | {_r(r['BANKNIFTY']['net'])} / "
                 f"{_r(r['NIFTY']['net'])} | {a['p']:.2f} | {_q(r)} |")
    L += ["", "## Finalists (A4 rule) and the release twins (descriptive)", "",
          "| id | flag | twin-level mean / p | mirror on every day | twin-time mean / p | net at 2x ticks | big winners / cut short / Rs left |",
          "|---|---|---|---|---|---|---|"]
    for cid in list(BASELINES) + j["finalists"]:
        r = res[cid]
        a = r["all"]
        pl = "n/a" if r.get("p_level") is None else f"{_r(r['twin_level_mean'])} / {r['p_level']:.2f}"
        L.append(f"| `{cid}` | {r.get('flag', 'baseline')} | {pl} | {_r(r.get('mirror_all_net'))} | "
                 f"{_r(r['twin_time_mean'])} / {r['p_time']:.2f} | {_r(r['net_2x_ticks'])} | "
                 f"{a['big']} / {a['cut']} / {a['left'] / 1e5:.1f} lakh |")
    L += ["", "## Nested walk-forward of the selection rule (select on blocks before k, score block k)", "",
          "| test block | picks (top 3) | top-1 | top-3 mean | today's ORB | r22-brk15 | mean of all 22 | configs > 0 |",
          "|---|---|---|---|---|---|---|---|"]
    for f in j["nested_wf"]["folds"]:
        L.append(f"| {f['test_block']} | {', '.join(f['picks'])} | {_r(f['top1'])} | {_r(f['top3_mean'])} | {_r(f['orb'])} | "
                 f"{_r(f['r22'])} | {_r(f['all_mean'])} | {f['all_positive']} of 22 |")
    w = j["nested_wf"]
    L += [f"| total 2-4 | | {_r(w['top1_total'])} | | {_r(w['orb_total'])} | {_r(w['r22_total'])} | {_r(w['all_mean_total'])} | |",
          "", f"Finalists: {', '.join(j['finalists'])}. BH (m = {j['bh_desc']['m']}, q 0.10): rejected "
          f"{len(j['bh_desc']['rejected'])}; smallest q {j['bh_desc']['smallest'][0][1]:.2f} ({j['bh_desc']['smallest'][0][0]})."]
    open(os.path.join(CFG.RESULTS, "release.md"), "w").write("\n".join(L) + "\n")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    {"release": release, "tables": tables, "freeze": freeze, "lake": lake}.get(cmd, lambda: sys.exit(__doc__))()
