"""R200 (PREREG.md): the iron fly versions not yet tested. Intraday fly with a stop inside max loss and a target; a
carried weekly fly with an index stop (R199's run_carry). Study, gates, report and log entries in one file.

    python3 -I research/hunt/r200_iron_fly/r200study.py run      # trades, twins, 2x slippage
    python3 -I research/hunt/r200_iron_fly/r200study.py final    # gates, BH, report.md, log entries

Caches in ~/.cache/iraalgo/r200 (never in git).
"""
from __future__ import annotations

import json
import math
import pickle
import sys
from collections import Counter
from multiprocessing import Pool
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling", HERE.parent / "r197_winrate80", HERE.parent / "r198_far_otm_expiry",
          HERE.parent / "r199_expiry_condor_hunt"):
    sys.path.insert(0, str(p))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r195study as S5  # noqa: E402
import r198study as S8  # noqa: E402
import r199study as S9  # noqa: E402

OUT = K.OUT.parent / "r200"
RESULTS = HERE / "results"
ENTRIES = HERE.parents[1] / "experiments" / "entries"
M_TOTAL = 4905
DAY = {"IF1-NIFTY": dict(xday=False, target=None), "IF2-NIFTY": dict(xday=False, target=0.25),
       "IF3-NIFTY": dict(xday=True, target=None), "IF4-NIFTY": dict(xday=True, target=0.25),
       "IF5-BANKNIFTY": dict(xday=False, target=0.25), "IF6-SENSEX": dict(xday=False, target=0.25)}
CARRY = {"IF7-NIFTY": dict(entry="after", dec=RU.R0920, a=0, w=8, istop=4),
         "IF8-SENSEX": dict(entry="after", dec=RU.R0920, a=0, w=8, istop=4)}
S9.CANDIDATES.update(CARRY)                    # run through R199's walker; R199's own candidates are unchanged
CANDIDATES = list(DAY) + list(CARRY)
GRID = range(RU.R0920, K.row(13, 0) + 1, 5)


def und_of(cid):
    return cid.split("-")[1]


def fly_day(cid, d, decide=None, mult=1.0):
    """The intraday fly: wings = the 09:20 straddle in steps (2-10); stop at 50% of max loss, optional target."""
    c, und = DAY[cid], und_of(cid)
    store, Dl, exp = S5.setup(und)
    D = store.get(d)
    if D is None or D.atm is None or bool(D.expiry) != c["xday"]:
        return None
    r = RU.R0920 if decide is None else decide
    k0, s = RU.straddle(D, und, r)
    if k0 is None or not np.isfinite(s):
        return None
    st, q, bse = K.STEP[und], K.LOT[und], und in K.BSE
    w = int(min(10, max(2, math.floor(s / st + 0.5))))
    legs = [E.Leg("C", k0, -1), E.Leg("P", k0, -1), E.Leg("C", k0 + w * st, 1), E.Leg("P", k0 - w * st, 1)]
    er = r + 1
    sq = K.SQ_ROW_EXPIRY if c["xday"] else K.SQ_ROW
    if er >= sq:
        return None
    for l in legs:
        if not E.fresh_at(D, l, r) or E.real_open(D, l, er) is None:
            return None
    ex = exp[d]
    entry, ch = [], 0.0
    for l in legs:
        p, _ = E.fill(D, l, er, l.sign > 0, d, ex, q, mult)
        entry.append(p)
        ch += E.charge(p, q, l.sign > 0, d, bse)
    credit = sum(-l.sign * f for l, f in zip(legs, entry)) * q
    max_loss = w * st * q - credit
    if credit <= 0 or max_loss <= 0:
        return None
    M = np.vstack([E.marks(D, l, er, sq, d, ex)[0] for l in legs])
    pnl = ((M - np.array(entry)[:, None]) * np.array([l.sign for l in legs])[:, None]).sum(axis=0) * q
    xr, why = sq, "time"
    for j in range(len(pnl) - 1):
        if pnl[j] <= -0.5 * max_loss:
            xr, why = er + j + 1, "stop"
            break
        if c["target"] is not None and pnl[j] >= c["target"] * credit:
            xr, why = er + j + 1, "target"
            break
    xs = []
    for l in legs:
        p, _ = E.fill(D, l, xr, l.sign < 0, d, ex, q, mult * (2.0 if why == "stop" else 1.0))
        xs.append(p)
        ch += E.charge(p, q, l.sign < 0, d, bse)
    gross = sum(l.sign * (x - f) for l, f, x in zip(legs, entry, xs)) * q
    return dict(und=und, day=d, exit_day=d, tag=cid, decide=r, legs=[(l.side, l.k, l.sign) for l in legs], entry=entry,
                exitpx=xs, credit=round(credit, 2), max_loss=round(max_loss, 2), why=why,
                margin=E.margin(legs, q, d, c["xday"]), gross=round(gross, 2), charges=round(ch, 2),
                net=round(gross - ch, 2), stale_legs=0, model_fills=0)


def _work(args):
    cid, items, mode, mult = args
    out = []
    for it in items:
        if cid in DAY:
            if mode == "rule":
                out.append(fly_day(cid, it, mult=mult))
            else:
                out.append((it, {r: (t["net"] if (t := fly_day(cid, it, decide=r)) else None) for r in GRID}))
        else:
            if mode == "rule":
                out.append(S9.run_carry(cid, it, mult=mult))
            else:
                tw = {i: (t["net"] if (t := S9.run_carry(cid, it, di=i)) else None) for i in range(len(it) - 1)}
                out.append((it[0], tw))
    return [x for x in out if x is not None]


def pmap(cid, items, mode="rule", mult=1.0):
    S5.setup(und_of(cid))
    parts = [items[i::16] for i in range(16)]
    with Pool(4) as p:
        res = p.map(_work, [(cid, x, mode, mult) for x in parts])
    return [x for r in res for x in r]


def save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "wb") as f:
        pickle.dump(obj, f)


def load(name):
    with open(OUT / name, "rb") as f:
        return pickle.load(f)


def run():
    for cid in CANDIDATES:
        if cid in DAY:
            store, _, _ = S5.setup(und_of(cid))
            items = [d for d in sorted(store) if K.period(d) is not None and store[d].atm is not None]
            wf_items = [d for d in items if K.period(d) == "wf"]
        else:
            items = S8.cycles(und_of(cid))
            wf_items = [cy for cy in items if K.period(cy[-1]) == "wf"]
        tr = sorted(pmap(cid, items), key=lambda t: t["day"])
        save(f"all_{cid}.pkl", tr)
        wf_days = {t["day"] for t in tr if K.period(t["day"]) == "wf"}
        save(f"wf2x_{cid}.pkl", pmap(cid, wf_items, mult=2.0))
        tw_items = [x for x in wf_items if (x if cid in DAY else x[0]) in wf_days]
        save(f"twin_{cid}.pkl", dict(pmap(cid, tw_items, "twin")))
        print(cid, len(wf_days), round(sum(t["net"] for t in tr if t["day"] in wf_days)), flush=True)


def final():
    import r197study as S7
    import yaml
    from scipy.stats import norm
    rows, ps = {}, {}
    for cid in CANDIDATES:
        tr = load(f"all_{cid}.pkl")
        wf = [t for t in tr if K.period(t["day"]) == "wf"]
        ho = [t for t in tr if K.period(t["day"]) == "ho"]
        rng = np.random.default_rng(K.SEED)
        sums = np.zeros(K.N_TWINS)
        for row in load(f"twin_{cid}.pkl").values():
            v = np.array([x for x in row.values() if x is not None])
            if len(v):
                sums += v[rng.integers(0, len(v), K.N_TWINS)]
        real = sum(t["net"] for t in wf)
        sd = float(sums.std(ddof=1))
        tw = dict(p_emp=round((1 + int((sums >= real).sum())) / (K.N_TWINS + 1), 4),
                  p_norm=float(norm.sf((real - sums.mean()) / sd)) if sd > 0 else 1.0)
        bp = S5.boot_p(wf)
        ps[cid] = max(bp, tw["p_norm"])
        rows[cid] = dict(design=S5.stats(wf), post_regime=S5.stats(wf, lo=K.REGIME), second_look_2026=S5.stats(ho),
                         all=S5.stats(tr), design_ratio=S7.ratio(wf), twin=tw, boot_p=bp,
                         net_2x_slip=round(sum(t["net"] for t in load(f"wf2x_{cid}.pkl"))),
                         exits=dict(Counter(t["why"] for t in tr)), years=S5.by_year(wf))
    q = S5.bh(ps, M_TOTAL)
    for cid, r in rows.items():
        d, h, al = r["design"], r["second_look_2026"], r["all"]
        f = []
        if not (d.get("net", 0) > 0 and r["post_regime"].get("net", 0) > 0):
            f.append("1 design")
        if not h.get("net", 0) > 0:
            f.append("2 2026")
        if d.get("n", 0) < 40 or h.get("n", 0) < 40:
            f.append("3 trades")
        if not (d.get("net_wo_top5", 0) > 0 and h.get("net_wo_top5", 0) > 0):
            f.append("4 without top 5")
        if r["twin"]["p_emp"] >= 0.05:
            f.append("5 twin")
        if q[cid] > 0.10:
            f.append("6 BH")
        if al.get("worst_month", 0) < K.MONTH_STOP or al.get("max_dd", 0) > K.DD_STOP:
            f.append("7 account stop")
        if not r["net_2x_slip"] > 0:
            f.append("8 2x slippage")
        r.update(bh_q=q[cid], fails=f, verdict="provisional (needs the forward read)" if not f else "fail",
                 possible_at_56k=bool(h.get("n", 0) and h.get("affordable_share", 0) >= 0.95))
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "final.json").write_text(json.dumps(dict(m=M_TOTAL, tests=rows), indent=1, default=str) + "\n")
    rs = S8.rs
    L = ["# R200 results (generated by r200study.py final)", "", "1 lot at today's lot, after slippage and dated charges. "
         "2026 is a second look.", "",
         "| test | design trades / win / net / max DD / worst trade | avg win / avg loss | post-Nov-2024 trades / win / net | "
         "2026 trades / win / net | worst month (all) | exits | margin p95 | twin p | BH q | 2x slip | verdict: failed |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for cid, r in rows.items():
        d, h, ra, po, a = r["design"], r["second_look_2026"], r["design_ratio"], r["post_regime"], r["all"]
        L.append(f"| {cid} | {d['n']} / {d['win']:.1%} / {rs(d['net'])} / {rs(d['max_dd'])} / {rs(d['worst_day'])} | "
                 f"{rs(ra['avg_win'])} / {rs(ra['avg_loss'])} | {po.get('n', 0)} / {po.get('win', 0):.1%} / {rs(po.get('net'))} | "
                 f"{h.get('n', 0)} / {h.get('win', 0):.1%} / {rs(h.get('net'))} | {rs(a.get('worst_month'))} | {r['exits']} | "
                 f"{rs(h.get('margin_p95'))} | {r['twin']['p_emp']} | {r['bh_q']:.2f} | {rs(r['net_2x_slip'])} | "
                 f"{r['verdict']}: {', '.join(r['fails']) or 'none'} |")
    L += ["", "By year (design):", ""] + [f"- {k}: {r['years']}" for k, r in rows.items()]
    (RESULTS / "report.md").write_text("\n".join(L) + "\n")
    for cid, r in rows.items():
        rule = (f"{und_of(cid)} carried weekly iron fly, wings 8 steps, 09:20 after an expiry to 15:05 on expiry day, "
                f"index stop at 4 steps" if cid in CARRY else
                f"{und_of(cid)} {'expiry-day' if DAY[cid]['xday'] else 'non-expiry'} 09:20 iron fly (wings = the straddle), "
                f"stop at 50% of max loss" + (f", target {int(DAY[cid]['target'] * 100)}% of credit" if DAY[cid]['target'] else ""))
        d, h = r["design"], r["second_look_2026"]
        e = dict(id=f"r200-{cid.lower()}", title=f"R200 iron fly (side study): {cid} - {rule}", family="option-structure",
                 date="2026-11-18", doc="research/HUNT_R200_IRON_FLY.md",
                 hypothesis="this iron fly makes money after costs in design and 2026, inside the account stop",
                 data_version="R195's stores (Drive lake copy of 18 Nov 2026; R51 clean held contracts 2 Aug 2021 - 13 Nov 2026)",
                 period=dict(design="2021-08-02..2025-12-31", holdout="2026 second look (R195 read it); forward from 2026-11-16 unread"),
                 instruments=[und_of(cid)],
                 features=["signal source: none directional (strikes and wings from the index and the 09:20 straddle); "
                           "the carried fly's stop reads the index"],
                 model_or_rule=rule, settings="research/hunt/r200_iron_fly/r200study.py; PREREG.md section 3",
                 prereg="research/hunt/r200_iron_fly/PREREG.md", variants_tried=1,
                 results=dict(summary=(f"design {d['n']} tr, win {d['win']:.1%}, net {rs(d['net'])}, DD {rs(d['max_dd'])}; "
                                       f"2026 {h.get('n', 0)} tr, win {h.get('win', 0):.1%}, net {rs(h.get('net'))}; twin p "
                                       f"{r['twin']['p_emp']}, BH q {r['bh_q']:.2f} (m = {M_TOTAL:,}). {r['verdict']}: "
                                       f"{', '.join(r['fails']) or 'none'}"),
                              costs="dated charges, 'liq' slippage (stops doubled), today's lot"),
                 holdout_read=False, verdict="rejected" if r["verdict"] == "fail" else "inconclusive",
                 notes="Research only (Boss, 18 Nov 2026: 'Check iron fly strategy'). The app stays buy-only.")
        (ENTRIES / f"r200-{cid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    return rows


if __name__ == "__main__":
    a = (sys.argv[1:] or [""])[0]
    if a == "run":
        run()
    elif a == "final":
        for k, v in final().items():
            d, h = v["design"], v["second_look_2026"]
            print(k, d["n"], d["win"], d["net"], d["max_dd"], "| 2026", h.get("n"), h.get("win"), h.get("net"), "|",
                  v["verdict"], v["fails"])
    else:
        raise SystemExit(__doc__)
