"""R201 (PREREG.md): option-selling ideas from the literature. Overnight sales with cheap far wings out at the open
(Muravyev & Ni 2020; Bhat, Pandey & Rao 2024), selling after VIX spikes (Bakshi & Kapadia 2003), a cross-index IV / RV
rank (Goyal & Saretto 2009), and event days. Study, gates, report and log entries in one file.

    python3 -I research/hunt/r201_literature/r201study.py run      # trades, twins, 2x slippage
    python3 -I research/hunt/r201_literature/r201study.py final    # gates, BH, report.md, log entries

Caches in ~/.cache/iraalgo/r201 (never in git). R195's S1 trade caches are read for VX1 and XS1, not changed.
"""
from __future__ import annotations

import json
import math
import pickle
import sys
from collections import Counter
from datetime import date
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

OUT = K.OUT.parent / "r201"
RESULTS = HERE / "results"
ENTRIES = HERE.parents[1] / "experiments" / "entries"
M_TOTAL = 4912
R1515, R1301 = K.row(15, 15), K.row(13, 1)
CANDIDATES = ["ON1-NIFTY", "ON2-BANKNIFTY", "ON3-SENSEX", "VX1-NIFTY", "VX2-NIFTY", "XS1-ALL", "EV1-NIFTY"]
RBI = ["2021-08-06", "2021-10-08", "2021-12-08", "2022-02-10", "2022-04-08", "2022-05-04", "2022-06-08", "2022-08-05",
       "2022-09-30", "2022-12-07", "2023-02-08", "2023-04-06", "2023-06-08", "2023-08-10", "2023-10-06", "2023-12-08",
       "2024-02-08", "2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06", "2025-02-07", "2025-04-09",
       "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05", "2026-02-06", "2026-04-08", "2026-06-05", "2026-08-05",
       "2026-10-07"]
OTHER = ["2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01", "2024-06-04"]
EVENTS = {date.fromisoformat(x) for x in RBI + OTHER}
ON_GRID = range(K.row(13, 0), R1515 + 1, 5)


def und_of(cid):
    return cid.split("-")[1]


def vix_close(d):
    v = S5._G["vix"].get(d)
    return RU._last(v[3]) if v is not None else float("nan")


def vix_spike_eve(Dl, i):
    """VX1: yesterday's VIX close >= 1.15 x the close five sessions before it (sessions before i only)."""
    if i < 7:
        return False
    a, b = vix_close(Dl.days[i - 1]), vix_close(Dl.days[i - 6])
    return bool(np.isfinite(a) and np.isfinite(b) and a >= 1.15 * b)


def vix_spike_1515(Dl, i):
    """VX2: VIX at 15:15 today >= 1.15 x the close five sessions before."""
    if i < 6:
        return False
    v = S5._G["vix"].get(Dl.days[i])
    a = RU._last(v[3, : R1515 + 1]) if v is not None else float("nan")
    b = vix_close(Dl.days[i - 5])
    return bool(np.isfinite(a) and np.isfinite(b) and a >= 1.15 * b)


def overnight(und, d, decide=R1515, exit_row=0, mult=1.0, need_next_not_expiry=True):
    """The ON structure: straddle sold at decide + 1 with wings 10 steps out, closed on the next session at exit_row."""
    store, Dl, exp = S5.setup(und)
    D, i = store.get(d), Dl.pos.get(d)
    if D is None or D.atm is None or i is None or D.expiry or i + 1 >= len(Dl.days):
        return None
    dn = Dl.days[i + 1]
    Dn = store.get(dn)
    if Dn is None or Dn.atm is None or (need_next_not_expiry and Dn.expiry) or K.period(dn) != K.period(d):
        return None
    sp = RU.spec_straddle(D, und, decide, wings=10)
    if sp is None:
        return None
    return E.run(D, und, d, sp, exp[d], False, mult, D_next=Dn, d_next=dn, exit_next=exit_row)


def trade(cid, d, decide=None, mult=1.0):
    f, und = cid.split("-")[0], und_of(cid)
    if f in ("ON1", "ON2", "ON3"):
        return overnight(und, d, R1515 if decide is None else decide, mult=mult)
    if f == "VX2":
        _, Dl, _ = S5.setup(und)
        i = Dl.pos.get(d)
        return overnight(und, d, mult=mult) if i is not None and vix_spike_1515(Dl, i) else None
    if f == "EV1":            # d is the eve; the caller passes event eves (the rule) or non-event eves (its twins)
        return overnight(und, d, exit_row=R1301, mult=mult, need_next_not_expiry=False)
    raise KeyError(cid)


def ev_eves(part):
    _, Dl, _ = S5.setup("NIFTY")
    return [Dl.days[i] for i in range(len(Dl.days) - 1) if Dl.days[i + 1] in EVENTS and K.period(Dl.days[i]) == part]


def xs1(part, mult=1.0):
    """XS1 from R195's S1 caches (wf / ho; 2x slippage for wf): the index with the highest IV / RV20 at 09:20."""
    pre = {"wf": "wf2x" if mult == 2.0 else "wf", "ho": "ho"}[part]
    by_day = {}
    for u in K.UNDS:
        store, Dl, exp = S5.setup(u)
        for t in S5._load(f"{pre}_S1-{u}.pkl"):
            d = t["day"]
            D, i = store[d], Dl.pos[d]
            k0, s = RU.straddle(D, u, RU.R0920)
            spot, tt, r20 = D.idx[3, RU.R0920], float(E.years_left(d, exp[d], RU.R0920)), RU.rv(Dl, i, 20)
            if k0 is None or not (np.isfinite(s) and np.isfinite(spot) and np.isfinite(r20)) or tt <= 0 or r20 <= 0:
                continue
            by_day.setdefault(d, []).append((s / (0.7979 * spot * math.sqrt(tt)) / r20, u, t))
    picks = {d: max(v, key=lambda x: x[0])[2] for d, v in by_day.items()}
    twins = {d: [x[2]["net"] for x in v] for d, v in by_day.items()}
    return [dict(t, tag="XS1-ALL") for d, t in sorted(picks.items())], twins


def _work(args):
    cid, ds, mode, mult = args
    out = []
    for d in ds:
        if mode == "rule":
            out.append(trade(cid, d, mult=mult))
        else:
            out.append((d, {r: (t["net"] if (t := overnight(und_of(cid), d, r)) else None) for r in ON_GRID}))
    return [x for x in out if x is not None]


def pmap(cid, ds, mode="rule", mult=1.0):
    S5.setup(und_of(cid) if cid != "XS1-ALL" else "NIFTY")
    parts = [ds[i::16] for i in range(16)]
    with Pool(4) as p:
        res = p.map(_work, [(cid, x, mode, mult) for x in parts])
    return sorted([x for r in res for x in r], key=lambda t: t["day"] if isinstance(t, dict) else t[0])


def save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    with open(OUT / name, "wb") as f:
        pickle.dump(obj, f)


def load(name):
    with open(OUT / name, "rb") as f:
        return pickle.load(f)


def days(und, part):
    store, _, _ = S5.setup(und)
    return [d for d in sorted(store) if K.period(d) == part and store[d].atm is not None]


def run():
    for cid in CANDIDATES:
        f, und = cid.split("-")[0], und_of(cid)
        if f == "XS1":
            wf, tw = xs1("wf")
            ho, _ = xs1("ho")
            wf2, _ = xs1("wf", 2.0)
            save(f"twin_{cid}.pkl", {d: v for d, v in tw.items() if K.period(d) == "wf"})
        elif f == "VX1":
            store, Dl, _ = S5.setup(und)
            keep = lambda tr: [t for t in tr if vix_spike_eve(Dl, Dl.pos[t["day"]])]  # noqa: E731
            wf, ho, wf2 = (keep(S5._load(f"{p}_S1-NIFTY.pkl")) for p in ("wf", "ho", "wf2x"))
        elif f == "EV1":
            wf, ho = pmap(cid, ev_eves("wf")), pmap(cid, ev_eves("ho"))
            wf2 = pmap(cid, ev_eves("wf"), mult=2.0)
            ev = set(ev_eves("wf"))
            others = [d for d in days(und, "wf") if d not in ev]
            save(f"twin_{cid}.pkl", [t for t in pmap(cid, others)])           # non-event eves, same entry and exit
        else:
            wf, ho = pmap(cid, days(und, "wf")), pmap(cid, days(und, "ho"))
            wf2 = pmap(cid, days(und, "wf"), mult=2.0)
            if f != "VX2":
                save(f"twin_{cid}.pkl", dict(pmap(cid, [t["day"] for t in wf], "twin")))
        save(f"wf_{cid}.pkl", wf)
        save(f"ho_{cid}.pkl", ho)
        save(f"wf2x_{cid}.pkl", wf2)
        print(cid, len(wf), round(sum(t["net"] for t in wf)), "| 2026", len(ho), round(sum(t["net"] for t in ho)), flush=True)


def twin_p(cid, wf):
    from scipy.stats import norm
    rng = np.random.default_rng(K.SEED)
    f = cid.split("-")[0]
    real = sum(t["net"] for t in wf)
    sums = np.zeros(K.N_TWINS)
    if f in ("ON1", "ON2", "ON3", "XS1"):
        g = load(f"twin_{cid}.pkl")
        for d, row in g.items():
            v = np.array([x for x in (row.values() if isinstance(row, dict) else row) if x is not None])
            if len(v):
                sums += v[rng.integers(0, len(v), K.N_TWINS)]
    else:
        base = {"VX1": lambda: S5._load("wf_S1-NIFTY.pkl"), "VX2": lambda: load("wf_ON1-NIFTY.pkl"),
                "EV1": lambda: load("twin_EV1-NIFTY.pkl")}[f]()
        bn = np.array([t["net"] for t in base])
        for j in range(K.N_TWINS):
            sums[j] = bn[rng.choice(len(bn), size=min(len(wf), len(bn)), replace=False)].sum()
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
        wf, ho, wf2 = load(f"wf_{cid}.pkl"), load(f"ho_{cid}.pkl"), load(f"wf2x_{cid}.pkl")
        tw, bp = twin_p(cid, wf), S5.boot_p(wf)
        ps[cid] = max(bp, tw["p_norm"])
        g = np.array([t["gross"] for t in wf + ho])
        rows[cid] = dict(design=S5.stats(wf), post_regime=S5.stats(wf, lo=K.REGIME), second_look_2026=S5.stats(ho),
                         all=S5.stats(wf + ho), design_ratio=S7.ratio(wf), twin=tw, boot_p=bp,
                         net_2x_slip=round(sum(t["net"] for t in wf2)), gross_per_trade=round(float(g.mean())) if len(g) else None,
                         cost_per_trade=round(float(np.mean([t["charges"] for t in wf + ho]))) if wf + ho else None,
                         years=S5.by_year(wf), by_index=dict(Counter(t["und"] for t in wf + ho)))
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
    L = ["# R201 results (generated by r201study.py final)", "", "1 lot at today's lot, after slippage and dated charges. "
         "2026 is a second look.", "",
         "| test | design trades / win / net / max DD / worst trade | avg win / avg loss | gross / costs per trade | "
         "post-Nov-2024 trades / win / net | 2026 trades / win / net | worst month (all) | margin p95 | twin p | BH q | "
         "2x slip | verdict: failed |", "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for cid, r in rows.items():
        d, h, ra, po, a = r["design"], r["second_look_2026"], r["design_ratio"], r["post_regime"], r["all"]
        L.append(f"| {cid} | {d['n']} / {d['win']:.1%} / {rs(d['net'])} / {rs(d['max_dd'])} / {rs(d['worst_day'])} | "
                 f"{rs(ra['avg_win'])} / {rs(ra['avg_loss'])} | {rs(r['gross_per_trade'])} / {rs(r['cost_per_trade'])} | "
                 f"{po.get('n', 0)} / {po.get('win', 0):.1%} / {rs(po.get('net'))} | {h.get('n', 0)} / {h.get('win', 0):.1%} / "
                 f"{rs(h.get('net'))} | {rs(a.get('worst_month'))} | {rs(h.get('margin_p95'))} | {r['twin']['p_emp']} | "
                 f"{r['bh_q']:.2f} | {rs(r['net_2x_slip'])} | {r['verdict']}: {', '.join(r['fails']) or 'none'} |")
    L += ["", "By year (design):", ""] + [f"- {k}: {r['years']}" for k, r in rows.items()]
    L += ["", "XS1 trades by index:", "", f"- {rows['XS1-ALL']['by_index']}"]
    (RESULTS / "report.md").write_text("\n".join(L) + "\n")
    RULE = {"ON1": "overnight hedged straddle (wings 10 steps) sold 15:16, out at the next 09:15 open",
            "ON2": "ON1 on BANKNIFTY", "ON3": "ON1 on SENSEX",
            "VX1": "09:20 straddle with 25% stops per leg after a VIX spike (yesterday >= 1.15x five sessions earlier)",
            "VX2": "ON1 on days VIX at 15:15 >= 1.15x five sessions earlier",
            "XS1": "09:20 straddle with 25% stops per leg on the index with the highest IV / RV20 that day",
            "EV1": "hedged straddle sold 15:16 on the eve of RBI policy / Budget / election days, out at 13:01"}
    for cid, r in rows.items():
        f = cid.split("-")[0]
        d, h = r["design"], r["second_look_2026"]
        e = dict(id=f"r201-{cid.lower()}", title=f"R201 literature ideas (side study): {cid} - {RULE[f]}",
                 family="option-structure", date="2026-11-18", doc="research/HUNT_R201_LITERATURE.md",
                 hypothesis=f"{RULE[f]} makes money after costs in design and 2026, inside the account stop (the published "
                            f"finding behind it: PREREG.md section 1)",
                 data_version="R195's stores (Drive lake copy of 18 Nov 2026; R51 clean held contracts 2 Aug 2021 - 13 Nov 2026)",
                 period=dict(design="2021-08-02..2025-12-31", holdout="2026 second look (R195 read it); forward from 2026-11-16 unread"),
                 instruments=list(K.UNDS) if f == "XS1" else [und_of(cid)],
                 features=["signal source: none directional for the structure; VIX (VX1 / VX2), IV / RV (XS1) or a calendar "
                           "(EV1) as filters"],
                 model_or_rule=RULE[f], settings="research/hunt/r201_literature/r201study.py; PREREG.md section 3",
                 prereg="research/hunt/r201_literature/PREREG.md", variants_tried=1,
                 results=dict(summary=(f"design {d['n']} tr, win {d['win']:.1%}, net {rs(d['net'])}, DD {rs(d['max_dd'])}; "
                                       f"2026 {h.get('n', 0)} tr, win {h.get('win', 0):.1%}, net {rs(h.get('net'))}; gross "
                                       f"{rs(r['gross_per_trade'])} vs costs {rs(r['cost_per_trade'])} a trade; twin p "
                                       f"{r['twin']['p_emp']}, BH q {r['bh_q']:.2f} (m = {M_TOTAL:,}). {r['verdict']}: "
                                       f"{', '.join(r['fails']) or 'none'}"),
                              costs="dated charges, 'liq' slippage (stops doubled), today's lot"),
                 holdout_read=False, verdict="rejected" if r["verdict"] == "fail" else "inconclusive",
                 notes="Research only (Boss, 18 Nov 2026: 'find all the papers published for it'). The app stays buy-only.")
        (ENTRIES / f"r201-{cid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    return rows


if __name__ == "__main__":
    a = (sys.argv[1:] or [""])[0]
    if a == "run":
        run()
    elif a == "final":
        for k, v in final().items():
            d, h = v["design"], v["second_look_2026"]
            print(k, d["n"], d["win"], d["net"], "| 2026", h.get("n"), h.get("win"), h.get("net"), "| gross",
                  v["gross_per_trade"], "cost", v["cost_per_trade"], "|", v["verdict"], v["fails"])
    else:
        raise SystemExit(__doc__)
