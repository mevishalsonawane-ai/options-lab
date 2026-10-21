"""R87B Part B4: at most 3 rules from the R87B design findings (B1 30m / 60m, B2 sequences and clusters), selected
mechanically as in R87 (PREREG section 8.1) and priced on REAL option minutes where they exist (Market 'hybrid');
plus the R87 rules re-priced the same way (descriptive: they already failed).

    python -m research.hunt.r87_candle_anatomy.brules select
    python -m research.hunt.r87_candle_anatomy.brules design
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np
import pandas as pd

from . import cfg
from . import rules as R
from . import sim as S
from . import stats as ST
from .batlas import PRIMARY, RES

HOLD = {5: 25, 15: 75, 30: 90, 60: 180}           # the primary horizon in minutes (5, 5, 3, 3 candles)
SEL = RES / "brules_selected.json"


def cost_points_hybrid(und: str, frm: date, to: date) -> dict:
    """As rules.cost_points, on the hybrid (real where printed) 1-ITM call at 10:30."""
    mk = R.Market(und, "hybrid")
    ps, real = [], 0
    for d in mk.days(frm, to):
        c, atm, con, _ = mk.day(d)
        if not np.isfinite(c[R.COST_ROW]):
            continue
        k = S.strike_1itm(und, atm[R.COST_ROW], c[R.COST_ROW], True)
        A = con.get(("C", k))
        if A is not None and np.isfinite(A[3, R.COST_ROW]):
            ps.append(A[3, R.COST_ROW])
            real += bool(con.is_real("C", k, R.COST_ROW))
    P = float(np.median(ps))
    qty = cfg.lot(und, to)
    unit = 2 * S.BPS_MKT * P + 2 * S.TICK + S.charges(und, P, P, qty, to) / qty
    return dict(und=und, premium=P, days=int(len(ps)), real_days=int(real), lot=qty, unit_cost=unit,
                points=unit / R.COST_DELTA, pricing="hybrid (real where printed)")


def select() -> None:
    parts = []
    for fam in ("B1", "B2seq", "B2cl"):
        t = pd.read_csv(RES / f"batlas_design_{fam}.csv.gz")
        t = t[t.tf.isin([5, 15, 30, 60])]
        parts.append(t.assign(src=fam))
    t = pd.concat(parts, ignore_index=True)
    t["cond"] = ""
    cost = {u: cost_points_hybrid(u, cfg.RULE_START, cfg.DESIGN_END) for u in cfg.UNDS}
    picked = R.select(t, t.iloc[:0], cost)
    for p in picked:
        p["hold"] = HOLD[p["tf"]]
        p["id"] = p["id"].replace("R87-", "R87B-")
    SEL.write_text(json.dumps(dict(cost=cost, rules=picked), indent=1))
    print(json.dumps(dict(cost=cost, rules=picked), indent=1))


def design() -> None:
    sel = json.loads(SEL.read_text())["rules"]
    old = json.loads((cfg.RESULTS / "rules_selected.json").read_text())["rules"]
    out = []
    for r in sel + old:
        mk = R.Market(r["und"], "hybrid")
        sig = R.signals(r, cfg.RULE_START, cfg.DESIGN_END)
        res, tr = R.walk_forward(r, mk, sig)
        days = mk.days(cfg.RULE_START, cfg.DESIGN_END)
        tw = R.twins(r, mk, tr, days, res["frozen_stop"])
        net = res[f"design_{int(res['frozen_stop'] * 100)}"]["net"]
        res.update(rule=r, signals=int(len(sig)), twin_mean=float(tw.mean()),
                   twin_p=float((1 + (tw >= net).sum()) / (len(tw) + 1)), pricing="hybrid",
                   real_entry_share=float(tr.real_entry.mean()) if len(tr) and "real_entry" in tr else None,
                   real_exit_share=float(tr.real_exit.mean()) if len(tr) and "real_exit" in tr else None,
                   r87_repriced=r["id"].startswith("R87-"))
        tr.to_csv(RES / f"brules_design_trades_{r['id']}.csv", index=False, float_format="%.4f")
        out.append(res)
        print(r["id"], round(res["wf_net"]), round(net), res["twin_p"], flush=True)
    new = [o for o in out if not o["r87_repriced"]]
    q = ST.bh([o["twin_p"] for o in new])
    for o, qq in zip(new, q):
        d = o[f"design_{int(o['frozen_stop'] * 100)}"]
        o["bh_q"] = float(qq)
        why = []
        if not o["wf_net"] > 0:
            why.append("walk-forward net <= 0")
        if not (o["twin_p"] < R.GATE_P and qq < R.GATE_P):
            why.append("not better than random entries (twin p or BH q >= 0.10)")
        if not d["top5_removed"] > 0:
            why.append("carried by its 5 best trades")
        if d["trades"] < R.MIN_TRADES:
            why.append(f"fewer than {R.MIN_TRADES} trades")
        o["design_pass"], o["fail_reasons"] = not why, why
    (RES / "brules_design.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    {"select": select, "design": design}[sys.argv[1]]()
