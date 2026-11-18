"""R198 (PREREG.md): far-OTM hedged strangles carried to the weekly expiry. Engine, study, gates and report in one file.

    python3 -I research/hunt/r198_far_otm_expiry/r198study.py run       # design + 2026 second look + twins + 2x slippage
    python3 -I research/hunt/r198_far_otm_expiry/r198study.py final     # gates, BH, report.md, log entries

Caches in ~/.cache/iraalgo/r198 (never in git). Marks, fills, charges and margin come from r195engine unchanged.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date
from multiprocessing import Pool
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling", HERE.parent / "r197_winrate80"):
    sys.path.insert(0, str(p))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r195study as S5  # noqa: E402

OUT = K.OUT.parent / "r198"
RESULTS = HERE / "results"
ENTRIES = HERE.parents[1] / "experiments" / "entries"
M_TOTAL = 4885
R1505, R1510, R1515 = K.SQ_ROW_EXPIRY, K.SQ_ROW, K.row(15, 15)
EX_STT_UP = date(2026, 4, 1)
CANDIDATES = {
    "F1-NIFTY": dict(a=5, w=10, exit="1505", entry="after"),
    "F2-NIFTY": dict(a=7, w=10, exit="1505", entry="after"),
    "F3-NIFTY": dict(a=5, w=10, exit="settle", entry="after"),
    "F4-NIFTY": dict(a=7, w=10, exit="settle", entry="after"),
    "F5-NIFTY": dict(a=5, w=10, exit="1505", entry="eve"),
    "F6-SENSEX": dict(a=5, w=10, exit="1505", entry="after"),
    "F7-SENSEX": dict(a=7, w=10, exit="1505", entry="after"),
    "F8-NIFTY": dict(a=5, w=10, exit="eve1510", entry="after", puts_only=True),
}


def cycles(und: str) -> list:
    """[(sessions after one expiry up to and including the next expiry)]; the first partial cycle is dropped."""
    store, Dl, _ = S5.setup(und)
    ds = [d for d in Dl.days if d in store and store[d].atm is not None and d >= K.START]
    out, cur, started = [], [], False
    for d in ds:
        if started:
            cur.append(d)
        if store[d].expiry:
            if started and len(cur) >= 2:
                out.append(cur)
            cur, started = [], True
    return out


def intrinsic(leg, s):
    return max(s - leg.k, 0.0) if leg.side == "C" else max(leg.k - s, 0.0)


def exit_fill(store, cyc, di, leg, r, expiry, q, mult):
    """A market order for minute r of session cyc[di]: a real open with 'liq' slippage; else the model mark (this
    session's last print's IV, else an earlier session's), never below intrinsic; thin-contract slippage."""
    d, D = cyc[di], store[cyc[di]]
    o = E.real_open(D, leg, r)
    if o is not None:
        return E.R103.slip(o, leg.sign < 0, E.R103.vol5_lots(D.con[(leg.side, leg.k)], r, q), mult)
    p = float(E.marks(D, leg, r, r, d, expiry)[0][0])
    j = di - 1
    while not np.isfinite(p) and j >= 0:
        p = E.prev_model(store[cyc[j]], cyc[j], leg, D, d, r, expiry)
        j -= 1
    s = D.idx[3, r] if np.isfinite(D.idx[3, r]) else RU._last(D.idx[3, : r + 1])
    p = max(p if np.isfinite(p) else 0.0, intrinsic(leg, s), E.TICK)
    return E.R103.slip(p, leg.sign < 0, E.THIN, mult)


def trade(cid, cyc, di=None, mult=1.0):
    """One trade on one expiry cycle; di = the entry session's index in the cycle (twins), else the rule's own."""
    c = CANDIDATES[cid]
    und = cid.split("-")[1]
    store, Dl, exp = S5.setup(und)
    q, bse = K.LOT[und], und in K.BSE
    if di is None:
        di = 0 if c["entry"] == "after" else len(cyc) - 2
    if di < 0 or di >= len(cyc) - 1:
        return None
    d0, xd = cyc[di], cyc[-1]
    if K.period(d0) is None or K.period(d0) != K.period(xd):
        return None
    D0 = store[d0]
    dec = R1515 if c["entry"] == "eve" else RU.R0920
    k0 = RU.atm(D0, und, dec)
    if k0 is None:
        return None
    st = K.STEP[und]
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
    xs = []
    if c["exit"] == "settle":
        s = RU._last(store[xd].idx[3])
        rate = 0.0015 if xd >= EX_STT_UP else 0.00125
        for l in legs:
            v = intrinsic(l, s)
            xs.append(v)
            if l.sign > 0 and v > 0:
                ch += rate * v * q
        exit_day = xd
    else:
        xi = len(cyc) - 1 if c["exit"] == "1505" else len(cyc) - 2
        if xi <= di:
            return None
        r = R1505 + 1 if c["exit"] == "1505" else R1510 + 1
        exit_day = cyc[xi]
        for l in legs:
            p = exit_fill(store, cyc, xi, l, r, expiry, q, mult)
            xs.append(p)
            ch += E.charge(p, q, l.sign < 0, exit_day, bse)
    gross = sum(l.sign * (x - f) for l, f, x in zip(legs, entry, xs)) * q
    credit = sum(-l.sign * f for l, f in zip(legs, entry)) * q
    carried = c["exit"] != "eve1510"
    return dict(und=und, day=d0, exit_day=exit_day, tag=cid, legs=[(l.side, l.k, l.sign) for l in legs], entry=entry,
                exitpx=xs, credit=round(credit, 2), max_loss=round((c["w"] - c["a"]) * st * q - credit, 2),
                margin=E.margin(legs, q, xd if carried else d0, carried), gross=round(gross, 2), charges=round(ch, 2),
                net=round(gross - ch, 2), why=c["exit"], stale_legs=0, model_fills=0)


def _work(args):
    cid, cys, mode, mult = args
    out = []
    for cyc in cys:
        if mode == "rule":
            out.append(trade(cid, cyc, mult=mult))
        else:
            own = 0 if CANDIDATES[cid]["entry"] == "after" else len(cyc) - 2
            tw = {i: (t["net"] if (t := trade(cid, cyc, di=i)) else None) for i in range(len(cyc) - 1)}
            out.append((cyc[own], tw))
    return out


def pmap(cid, cys, mode="rule", mult=1.0):
    S5.setup(cid.split("-")[1])
    parts = [cys[i::8] for i in range(8)]
    with Pool(4) as p:
        res = p.map(_work, [(cid, x, mode, mult) for x in parts])
    return [x for r in res for x in r if x is not None]


def save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "wb") as f:
        pickle.dump(obj, f)


def load(name):
    with open(OUT / name, "rb") as f:
        return pickle.load(f)


def run():
    for cid in CANDIDATES:
        cys = cycles(cid.split("-")[1])
        tr = sorted(pmap(cid, cys), key=lambda t: t["day"])
        save(f"all_{cid}.pkl", tr)
        wf = [t for t in tr if K.period(t["day"]) == "wf"]
        wcy = [cy for cy in cys if K.period(cy[0]) == "wf"]
        save(f"wf2x_{cid}.pkl", [t for t in pmap(cid, wcy, mult=2.0)])
        save(f"twin_{cid}.pkl", dict(pmap(cid, [cy for cy in wcy if any(t["day"] in cy for t in wf)], "twin")))
        print(cid, len(wf), round(sum(t["net"] for t in wf)), flush=True)


def twin_p(cid, wf):
    rng = np.random.default_rng(K.SEED)
    sums = np.zeros(K.N_TWINS)
    for row in load(f"twin_{cid}.pkl").values():
        v = np.array([x for x in row.values() if x is not None])
        if len(v):
            sums += v[rng.integers(0, len(v), K.N_TWINS)]
    real = sum(t["net"] for t in wf)
    from scipy.stats import norm
    sd = float(sums.std(ddof=1))
    return dict(p_emp=round((1 + int((sums >= real).sum())) / (K.N_TWINS + 1), 4),
                p_norm=float(norm.sf((real - sums.mean()) / sd)) if sd > 0 else 1.0, twin_mean=round(float(sums.mean())))


def rs(x):
    return "-" if x is None else (f"-Rs {abs(round(x)):,}" if x < 0 else f"Rs {round(x):,}")


def final():
    import r197study as S7
    import yaml
    rows, ps = {}, {}
    for cid in CANDIDATES:
        tr = load(f"all_{cid}.pkl")
        wf = [t for t in tr if K.period(t["day"]) == "wf"]
        ho = [t for t in tr if K.period(t["day"]) == "ho"]
        d, h, al = S5.stats(wf), S5.stats(ho), S5.stats(tr)
        tw, bp = twin_p(cid, wf), S5.boot_p(wf)
        ps[cid] = max(bp, tw["p_norm"])
        worst = sorted(tr, key=lambda t: t["net"])[:3]
        rows[cid] = dict(design=d, post_regime=S5.stats(wf, lo=K.REGIME), second_look_2026=h, all=al,
                         design_ratio=S7.ratio(wf), ratio_2026=S7.ratio(ho) if ho else None, twin=tw, boot_p=bp,
                         net_2x_slip=round(sum(t["net"] for t in load(f"wf2x_{cid}.pkl"))),
                         worst_trades=[(str(t["day"]), str(t["exit_day"]), round(t["net"])) for t in worst],
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
    L = ["# R198 results (generated by r198study.py final)", "",
         "1 lot at today's lot, after slippage, dated charges and (settle) exercise STT. 2026 is a second look.", "",
         "| test | design trades / win (Wilson 95%) / net / DD / worst trade | avg win / avg loss / break-even win | "
         "post-Nov-2024 net | 2026 trades / win / net / worst trade | margin p95 (2026) | twin p | BH q | 2x slip | "
         "verdict: failed |", "|---|---|---|---|---|---|---|---|---|---|"]
    for cid, r in rows.items():
        d, h, ra = r["design"], r["second_look_2026"], r["design_ratio"]
        L.append(f"| {cid} | {d['n']} / {d['win']:.1%} ({ra['wilson'][0]:.1%}-{ra['wilson'][1]:.1%}) / {rs(d['net'])} / "
                 f"{rs(d['max_dd'])} / {rs(d['worst_day'])} | {rs(ra['avg_win'])} / {rs(ra['avg_loss'])} / "
                 f"{ra['breakeven_win']:.1%} | {rs(r['post_regime'].get('net'))} | {h.get('n', 0)} / {h.get('win', 0):.1%} / "
                 f"{rs(h.get('net'))} / {rs(h.get('worst_day'))} | {rs(h.get('margin_p95'))} | {r['twin']['p_emp']} | "
                 f"{r['bh_q']:.2f} | {rs(r['net_2x_slip'])} | {r['verdict']}: {', '.join(r['fails']) or 'none'} |")
    L += ["", "Worst three trades (entry, expiry, net):", ""] + [f"- {k}: {r['worst_trades']}" for k, r in rows.items()]
    L += ["", "By year (design):", ""] + [f"- {k}: {r['years']}" for k, r in rows.items()]
    (RESULTS / "report.md").write_text("\n".join(L) + "\n")
    for cid, r in rows.items():
        c = CANDIDATES[cid]
        rule = (f"{cid.split('-')[1]} {'put spread' if c.get('puts_only') else 'iron condor'}: shorts {c['a']} steps out, "
                f"hedges {c['w']} steps out, entry {'09:20 the session after an expiry' if c['entry'] == 'after' else '15:15 the session before expiry'}, "
                f"exit {dict(settle='at settlement', eve1510='15:10 the session before expiry')[c['exit']] if c['exit'] != '1505' else '15:05 on expiry day'}")
        d, h, ra = r["design"], r["second_look_2026"], r["design_ratio"]
        e = dict(id=f"r198-{cid.lower()}", title=f"R198 far-OTM hedged strangle to expiry (side study): {cid} - {rule}",
                 family="option-structure", date="2026-11-18", doc="research/HUNT_R198_FAR_OTM_EXPIRY.md",
                 hypothesis="a far-OTM hedged short strangle carried to the weekly expiry wins >= 80% of cycles and makes "
                            "money after costs",
                 data_version="R195's stores (Drive lake copy of 18 Nov 2026; R51 clean held contracts 2 Aug 2021 - 13 Nov 2026)",
                 period=dict(design="2021-08-02..2025-12-31", holdout="2026 second look (R195 read it); forward from 2026-11-16 unread"),
                 instruments=[cid.split("-")[1]],
                 features=["signal source: none directional (strikes from the index at entry)", "no stop; the hedge is the loss cap"],
                 model_or_rule=rule, settings="research/hunt/r198_far_otm_expiry/r198study.py; PREREG.md section 3",
                 prereg="research/hunt/r198_far_otm_expiry/PREREG.md", variants_tried=1,
                 results=dict(summary=(f"design {d['n']} cycles, win {d['win']:.1%} (Wilson {ra['wilson'][0]:.1%}-"
                                       f"{ra['wilson'][1]:.1%}), net {rs(d['net'])}, avg win {rs(ra['avg_win'])}, avg loss "
                                       f"{rs(ra['avg_loss'])}, worst {rs(d['worst_day'])}; 2026 {h.get('n', 0)} cycles, win "
                                       f"{h.get('win', 0):.1%}, net {rs(h.get('net'))}; twin p {r['twin']['p_emp']}, BH q "
                                       f"{r['bh_q']:.2f} (m = {M_TOTAL:,}). {r['verdict']}: {', '.join(r['fails']) or 'none'}"),
                              costs="dated charges, 'liq' slippage, exercise STT on settled ITM long legs, today's lot"),
                 holdout_read=False, verdict="rejected" if r["verdict"] == "fail" else "inconclusive",
                 notes="Research only (Boss, 18 Nov 2026: 'try far OTM strangle hold to expiry with hedge for 80%'). "
                       "The app stays buy-only.")
        (ENTRIES / f"r198-{cid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    return rows


if __name__ == "__main__":
    a = (sys.argv[1:] or [""])[0]
    if a == "run":
        run()
    elif a == "final":
        for k, v in final().items():
            print(k, v["design"]["n"], v["design"]["win"], v["design"]["net"], "| 2026", v["second_look_2026"].get("n"),
                  v["second_look_2026"].get("win"), v["second_look_2026"].get("net"), "|", v["verdict"], v["fails"])
    else:
        raise SystemExit(__doc__)
