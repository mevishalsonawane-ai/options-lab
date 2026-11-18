"""R199 (PREREG.md): expiry condors with loss caps, filters and an intraday version. Engine, study, gates, report.

    python3 -I research/hunt/r199_expiry_condor_hunt/r199study.py run      # trades, twins, 2x slippage
    python3 -I research/hunt/r199_expiry_condor_hunt/r199study.py final    # gates, BH, report.md, log entries

Caches in ~/.cache/iraalgo/r199 (never in git). Marks, fills, charges and margin: r195engine; cycles and exit fills: R198.
"""
from __future__ import annotations

import json
import pickle
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling", HERE.parent / "r197_winrate80", HERE.parent / "r198_far_otm_expiry"):
    sys.path.insert(0, str(p))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r195study as S5  # noqa: E402
import r198study as S8  # noqa: E402

OUT = K.OUT.parent / "r199"
RESULTS = HERE / "results"
ENTRIES = HERE.parents[1] / "experiments" / "entries"
M_TOTAL = 4897
R0920, R1200, R1515 = RU.R0920, K.row(12, 0), K.row(15, 15)
X1505, X1510 = K.SQ_ROW_EXPIRY + 1, K.SQ_ROW + 1          # the fill minutes of the 15:05 / 15:10 exits
LAST = K.NROWS - 1
EVE = dict(entry="eve", dec=R1515, a=5, w=10)
CANDIDATES = {
    "H1-NIFTY": dict(EVE, istop=5),
    "H2-NIFTY": dict(EVE, istop=3),
    "H3-NIFTY": dict(EVE, a=7),
    "H4-NIFTY": dict(EVE, filt="vix"),
    "H5-NIFTY": dict(EVE, filt="calm"),
    "H6-NIFTY": dict(entry="xday", dec=R0920, a=5, w=10),
    "H7-NIFTY": dict(entry="xday", dec=R0920, a=5, w=10, istop=5),
    "H8-NIFTY": dict(entry="xday", dec=R1200, a=4, w=10, istop=4),
    "H9-BANKNIFTY": dict(EVE, istop=5),
    "H10-SENSEX": dict(EVE, istop=5),
    "H11-NIFTY": dict(EVE, pstop=1.5),
    "H12-NIFTY": dict(entry="eve2", dec=R1515, a=5, w=10, istop=5, puts_only=True),
}
TWIN_GRID = {"H6-NIFTY": range(R0920, K.row(13, 0) + 1, 5), "H7-NIFTY": range(R0920, K.row(13, 0) + 1, 5),
             "H8-NIFTY": range(K.row(11, 0), K.row(14, 0) + 1, 5)}


def und_of(cid):
    return cid.split("-")[1]


def vix_at(d, r):
    v = S5._G["vix"].get(d)
    return RU._last(v[3, : r + 1]) if v is not None else float("nan")


def vix_ok(Dl, d, r):
    i = Dl.pos[d]
    hist = np.array([vix_at(x, r) for x in Dl.days[max(0, i - 250): i]])
    hist = hist[np.isfinite(hist)]
    v = vix_at(d, r)
    return bool(len(hist) >= 120 and np.isfinite(v) and v >= np.median(hist))


def calm_ok(D, r):
    o, c = D.idx[0, 0], D.idx[3, r]
    return bool(np.isfinite(o) and np.isfinite(c) and abs(c / o - 1) <= 0.005)


def entry_index(c, cyc):
    return {"eve": len(cyc) - 2, "eve2": len(cyc) - 3, "xday": len(cyc) - 1, "after": 0}[c["entry"]]   # after: R200


def run_carry(cid, cyc, di=None, dec=None, mult=1.0):
    """One trade on one expiry cycle. di / dec override the entry session / minute (twins)."""
    c = CANDIDATES[cid]
    und = und_of(cid)
    store, Dl, exp = S5.setup(und)
    q, bse, st = K.LOT[und], und in K.BSE, K.STEP[und]
    di = entry_index(c, cyc) if di is None else di
    dec = c["dec"] if dec is None else dec
    xi, xrow = (len(cyc) - 2, X1510) if c["entry"] == "eve2" else (len(cyc) - 1, X1505)
    if di < 0 or di > xi or (di == xi and dec + 1 >= xrow):
        return None
    d0 = cyc[di]
    if K.period(d0) is None or K.period(d0) != K.period(cyc[xi]):
        return None
    D0 = store[d0]
    if c.get("filt") == "vix" and not vix_ok(Dl, d0, dec):
        return None
    if c.get("filt") == "calm" and not calm_ok(D0, dec):
        return None
    k0 = RU.atm(D0, und, dec)
    if k0 is None:
        return None
    legs = [E.Leg("P", k0 - c["a"] * st, -1), E.Leg("P", k0 - c["w"] * st, 1)]
    if not c.get("puts_only"):
        legs += [E.Leg("C", k0 + c["a"] * st, -1), E.Leg("C", k0 + c["w"] * st, 1)]
    for l in legs:
        if not E.fresh_at(D0, l, dec) or E.real_open(D0, l, dec + 1) is None:
            return None
    expiry = exp[d0]
    entry, ch = [], 0.0
    for l in legs:
        p, _ = E.fill(D0, l, dec + 1, l.sign > 0, d0, expiry, q, mult)
        entry.append(p)
        ch += E.charge(p, q, l.sign > 0, d0, bse)
    credit = sum(-l.sign * f for l, f in zip(legs, entry)) * q
    lo = k0 - c["istop"] * st if c.get("istop") else -np.inf
    hi = k0 + c["istop"] * st if c.get("istop") and not c.get("puts_only") else np.inf
    # walk every decision minute from the entry fill to the minute before the exit fill
    xk, xr, why = xi, xrow, "time"
    for k in range(di, xi + 1):
        D, d = store[cyc[k]], cyc[k]
        r0 = dec + 1 if k == di else 0
        r1 = xrow - 1 if k == xi else LAST
        if r1 < r0:
            continue
        ic = D.idx[3, r0: r1 + 1]
        hit = np.isfinite(ic) & ((ic < lo) | (ic > hi))
        if c.get("pstop"):
            M = np.vstack([E.marks(D, l, r0, r1, d, expiry)[0] for l in legs])
            for i, l in enumerate(legs):
                bad = ~np.isfinite(M[i])
                if bad.any():
                    s = np.where(np.isfinite(ic), ic, np.nanmean(ic))
                    M[i, bad] = [S8.intrinsic(l, x) for x in s[bad]]
            loss = -((M - np.array(entry)[:, None]) * np.array([l.sign for l in legs])[:, None]).sum(axis=0) * q
            hit |= loss >= c["pstop"] * credit
        j = np.flatnonzero(hit)
        if len(j):
            r = r0 + int(j[0])
            why = "stop"
            if r < LAST:
                xk, xr = k, r + 1
            elif k < xi:
                xk, xr = k + 1, 0
            else:
                why = "time"
            break
    xs = []
    xd = cyc[xk]
    for l in legs:
        p = S8.exit_fill(store, cyc, xk, l, xr, expiry, q, mult * (2.0 if why == "stop" else 1.0))
        xs.append(p)
        ch += E.charge(p, q, l.sign < 0, xd, bse)
    gross = sum(l.sign * (x - f) for l, f, x in zip(legs, entry, xs)) * q
    planned_expiry = xi == len(cyc) - 1                    # held into the expiry day: ELM margin at its peak
    return dict(und=und, day=d0, exit_day=xd, tag=cid, decide=dec, legs=[(l.side, l.k, l.sign) for l in legs],
                entry=entry, exitpx=xs, credit=round(credit, 2), why=why,
                margin=E.margin(legs, q, cyc[xi], True) if planned_expiry else E.margin(legs, q, d0, False),
                gross=round(gross, 2), charges=round(ch, 2), net=round(gross - ch, 2), stale_legs=0, model_fills=0)


def _work(args):
    cid, cys, mode, mult = args
    out = []
    for cyc in cys:
        if mode == "rule":
            out.append(run_carry(cid, cyc, mult=mult))
            continue
        c = CANDIDATES[cid]
        own = entry_index(c, cyc)
        if own < 0:
            continue
        if cid in TWIN_GRID:
            tw = {r: (t["net"] if (t := run_carry(cid, cyc, dec=r)) else None) for r in TWIN_GRID[cid]}
        else:
            top = len(cyc) - 2 if c["entry"] == "eve2" else len(cyc) - 1
            tw = {i: (t["net"] if (t := run_carry(cid, cyc, di=i)) else None) for i in range(top)}
        out.append((cyc[own], tw))
    return [x for x in out if x is not None]


def pmap(cid, cys, mode="rule", mult=1.0):
    S5.setup(und_of(cid))
    parts = [cys[i::8] for i in range(8)]
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


def run(cids=None):
    for cid in cids or CANDIDATES:
        cys = S8.cycles(und_of(cid))
        tr = sorted(pmap(cid, cys), key=lambda t: t["day"])
        save(f"all_{cid}.pkl", tr)
        wf_days = {t["day"] for t in tr if K.period(t["day"]) == "wf"}
        wcy = [cy for cy in cys if K.period(cy[-1]) == "wf"]
        save(f"wf2x_{cid}.pkl", pmap(cid, wcy, mult=2.0))
        tw = dict(pmap(cid, wcy, "twin"))
        save(f"twin_{cid}.pkl", {d: v for d, v in tw.items() if d in wf_days})
        print(cid, len(wf_days), round(sum(t["net"] for t in tr if t["day"] in wf_days)), flush=True)


def final():
    import r197study as S7
    import yaml
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
        from scipy.stats import norm
        sd = float(sums.std(ddof=1))
        tw = dict(p_emp=round((1 + int((sums >= real).sum())) / (K.N_TWINS + 1), 4),
                  p_norm=float(norm.sf((real - sums.mean()) / sd)) if sd > 0 else 1.0, twin_mean=round(float(sums.mean())))
        bp = S5.boot_p(wf)
        ps[cid] = max(bp, tw["p_norm"])
        from collections import Counter
        rows[cid] = dict(design=S5.stats(wf), post_regime=S5.stats(wf, lo=K.REGIME), second_look_2026=S5.stats(ho),
                         all=S5.stats(tr), design_ratio=S7.ratio(wf), ratio_2026=S7.ratio(ho) if ho else None, twin=tw,
                         boot_p=bp, net_2x_slip=round(sum(t["net"] for t in load(f"wf2x_{cid}.pkl"))),
                         exits=dict(Counter(t["why"] for t in tr)),
                         worst_trades=[(str(t["day"]), str(t["exit_day"]), round(t["net"])) for t in sorted(tr, key=lambda t: t["net"])[:3]],
                         years=S5.by_year(wf))
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
        if not (d.get("win", 0) >= 0.80 and h.get("win", 0) >= 0.80):
            f.append("9 win rate 80%")
        r.update(bh_q=q[cid], fails=f, verdict="provisional (needs the forward read)" if not f else "fail",
                 possible_at_56k=bool(h.get("n", 0) and h.get("affordable_share", 0) >= 0.95))
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "final.json").write_text(json.dumps(dict(m=M_TOTAL, tests=rows), indent=1, default=str) + "\n")
    rs = S8.rs
    L = ["# R199 results (generated by r199study.py final)", "", "1 lot at today's lot, after slippage and dated charges. "
         "2026 is a second look.", "",
         "| test | design trades / win (Wilson 95%) / net / DD / worst trade | avg win / avg loss / break-even win | "
         "post-Nov-2024 trades / win / net | 2026 trades / win / net / worst trade | exits | margin p95 (2026) | twin p | BH q "
         "| 2x slip | verdict: failed |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for cid, r in rows.items():
        d, h, ra, po = r["design"], r["second_look_2026"], r["design_ratio"], r["post_regime"]
        L.append(f"| {cid} | {d['n']} / {d['win']:.1%} ({ra['wilson'][0]:.1%}-{ra['wilson'][1]:.1%}) / {rs(d['net'])} / "
                 f"{rs(d['max_dd'])} / {rs(d['worst_day'])} | {rs(ra['avg_win'])} / {rs(ra['avg_loss'])} / "
                 f"{ra['breakeven_win']:.1%} | {po.get('n', 0)} / {po.get('win', 0):.1%} / {rs(po.get('net'))} | "
                 f"{h.get('n', 0)} / {h.get('win', 0):.1%} / {rs(h.get('net'))} / {rs(h.get('worst_day'))} | {r['exits']} | "
                 f"{rs(h.get('margin_p95'))} | {r['twin']['p_emp']} | {r['bh_q']:.2f} | {rs(r['net_2x_slip'])} | "
                 f"{r['verdict']}: {', '.join(r['fails']) or 'none'} |")
    L += ["", "Worst three trades (entry, exit day, net):", ""] + [f"- {k}: {r['worst_trades']}" for k, r in rows.items()]
    L += ["", "By year (design):", ""] + [f"- {k}: {r['years']}" for k, r in rows.items()]
    (RESULTS / "report.md").write_text("\n".join(L) + "\n")
    for cid, r in rows.items():
        c = CANDIDATES[cid]
        rule = (f"{und_of(cid)} {'put spread' if c.get('puts_only') else 'condor'} {c['a']} / {c['w']} steps, entry "
                f"{c['entry']} at row {c['dec']}" + (f", index stop {c['istop']} steps" if c.get("istop") else "")
                + (f", premium stop {c['pstop']}x credit" if c.get("pstop") else "") + (f", filter {c['filt']}" if c.get("filt") else ""))
        d, h, ra = r["design"], r["second_look_2026"], r["design_ratio"]
        e = dict(id=f"r199-{cid.lower()}", title=f"R199 expiry condor hunt (side study): {cid} - {rule}",
                 family="option-structure", date="2026-11-18", doc="research/HUNT_R199_EXPIRY_CONDOR_HUNT.md",
                 hypothesis="this expiry condor wins >= 80% and keeps its losses small enough to make money after costs",
                 data_version="R195's stores (Drive lake copy of 18 Nov 2026; R51 clean held contracts 2 Aug 2021 - 13 Nov 2026)",
                 period=dict(design="2021-08-02..2025-12-31", holdout="2026 second look (R195 read it); forward from 2026-11-16 unread"),
                 instruments=[und_of(cid)],
                 features=["signal source: none directional (strikes from the index); index stops and the calm filter read "
                           "the index; the VIX filter reads India VIX"],
                 model_or_rule=rule, settings="research/hunt/r199_expiry_condor_hunt/r199study.py; PREREG.md section 3",
                 prereg="research/hunt/r199_expiry_condor_hunt/PREREG.md", variants_tried=1,
                 results=dict(summary=(f"design {d['n']} tr, win {d['win']:.1%} (Wilson {ra['wilson'][0]:.1%}-{ra['wilson'][1]:.1%}), "
                                       f"net {rs(d['net'])}, avg win {rs(ra['avg_win'])}, avg loss {rs(ra['avg_loss'])}; 2026 "
                                       f"{h.get('n', 0)} tr, win {h.get('win', 0):.1%}, net {rs(h.get('net'))}; twin p "
                                       f"{r['twin']['p_emp']}, BH q {r['bh_q']:.2f} (m = {M_TOTAL:,}). {r['verdict']}: "
                                       f"{', '.join(r['fails']) or 'none'}"),
                              costs="dated charges, 'liq' slippage (stops doubled), today's lot"),
                 holdout_read=False, verdict="rejected" if r["verdict"] == "fail" else "inconclusive",
                 notes="Research only (Boss, 18 Nov 2026: 'Hunt. More'). The app stays buy-only.")
        (ENTRIES / f"r199-{cid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    return rows


if __name__ == "__main__":
    a = (sys.argv[1:] or [""])[0]
    if a == "run":
        run(sys.argv[2:] or None)
    elif a == "final":
        for k, v in final().items():
            d, h = v["design"], v["second_look_2026"]
            print(k, d["n"], d["win"], d["net"], "| 2026", h.get("n"), h.get("win"), h.get("net"), "|", v["verdict"], v["fails"])
    else:
        raise SystemExit(__doc__)
