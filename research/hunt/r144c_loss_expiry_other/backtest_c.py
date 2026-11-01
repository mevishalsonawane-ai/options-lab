"""R144c part C (PREREG_C.md): before / after backtests of the five changes proposed in part B. Hindsight: every period
was read before. Research only.

    IRAALGO_LAKE=<lake> OBUY_SCRATCH=<obuy layout> python -I research/hunt/r144c_loss_expiry_other/backtest_c.py
        -> results/before_after.json
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import lake as LK  # noqa: E402
import study as ST  # noqa: E402

PERIODS = ("unseen", "design", "final")
TWINS, SEED = 2000, 1445
MONTHLY_FROM = date(2024, 11, 20)
MONTHLY = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
NIGHT_VIX_CUT = -0.0313                   # part B: Night's vix_chg design lowest-fifth cut


def table(tr: list) -> dict:
    out = {p: ST.stats([t for t in tr if t["period"] == p]) for p in PERIODS}
    out["all three"] = ST.stats([t for t in tr if t["period"] in PERIODS])
    return out


def skip_twin(before: list, after: list, rng) -> float:
    """Random skips of as many trades in each period; p on the summed losing days of the three periods."""
    pers = {p: [t for t in before if t["period"] == p] for p in PERIODS}
    k = {p: len(pers[p]) - sum(1 for t in after if t["period"] == p) for p in PERIODS}
    act = sum(ST.loss_sum([t for t in after if t["period"] == p]) for p in PERIODS)
    tw = np.empty(TWINS)
    for i in range(TWINS):
        s = 0.0
        for p in PERIODS:
            idx = set(rng.choice(len(pers[p]), k[p], replace=False).tolist()) if k[p] else set()
            s += ST.loss_sum([t for j, t in enumerate(pers[p]) if j not in idx])
        tw[i] = s
    return round(float((1 + (tw >= act).sum()) / (1 + TWINS)), 4)


def vixdiv_nifty_eod() -> list:
    """C2: R1's own engine with NIFTY on the EOD variant (held to 15:10); BANKNIFTY as today."""
    sys.path.insert(0, str(HERE.parent / "r1"))
    import lib as L
    import design as Dz
    import signals as S
    rows = []
    for u, var in (("NIFTY", "N13_VIXDIV|a0.002|EOD"), ("BANKNIFTY", "N13_VIXDIV|a0.002|EOD")):
        P = L.load_index(u)
        ok = np.nonzero(P["ok"])[0]
        vmin, _ = L.vix_minutes(P["days"])
        Rq = S.Req()
        S.n13_vixdiv(P, Rq, ok, vmin)
        req = Rq.frame()
        req = req[req.di.isin(ok) & (req["var"] == var)].reset_index(drop=True)
        tr = Dz.engine_dedup(P, req)
        tr = tr[np.isfinite(tr.net)]
        for r in tr.itertuples():
            d = P["days"][int(r.di)]
            rows.append(dict(und=u, day=d, net=float(r.net), entry_row=int(r.ent), period=LK.period(d)))
        L.D.market().release()
    return rows


def run() -> dict:
    rng = np.random.default_rng(SEED)
    res = {"_note": "hindsight before / after (PREREG_C.md): every period was read in parts A and B"}
    ft3, hero, night, vix, flow = (ST.load(a) for a in ("FT3", "HEROTKT", "NIGHT", "VIXDIV", "FLOW"))
    flt = json.loads((HERE / "results" / "filters.json").read_text())
    lu = next(c for c in flt["FT3"]["chosen"] if c["feature"] == "quadrant")

    def add(cid, arm, change, before, after, skip=False):
        r = dict(arm=arm, change=change, before=table(before), after=table(after))
        if skip:
            r["twin_p_hindsight"] = skip_twin(before, after, rng)
        res[cid] = r
        a, b = r["after"]["all three"], r["before"]["all three"]
        print(cid, arm, b["trades"], "->", a["trades"], "net", b["net"], "->", a["net"], "win", b["win"], "->", a["win"],
              r.get("twin_p_hindsight", ""), flush=True)

    add("C1", "Hero Rs 1,000 ticket", "switched off", hero, [])
    vx = vixdiv_nifty_eod()
    add("C2", "VIX divergence", "NIFTY held to 15:10 (no -15% stop, no 20-minute rule)", vix, vx)
    add("C2-NIFTY", "VIX divergence, NIFTY only", "held to 15:10", [t for t in vix if t["und"] == "NIFTY"],
        [t for t in vx if t["und"] == "NIFTY"])
    c3 = [t for t in ft3 if not (t["und"] in MONTHLY and t["day"] >= MONTHLY_FROM)]
    add("C3", "FT3", "no tickets on BANKNIFTY / FINNIFTY / MIDCPNIFTY expiries from 20 Nov 2024", ft3, c3, skip=True)
    c5 = [t for t in ft3 if not ST.drops(lu, t)]
    add("C5", "FT3", "skip long unwinding (option price and OI both fell, 15 min)", ft3, c5, skip=True)
    c35 = [t for t in c3 if not ST.drops(lu, t)]
    add("C3+C5", "FT3", "both FT3 changes", ft3, c35, skip=True)
    c6 = [t for t in night if not (t["f"].get("vix_chg") == t["f"].get("vix_chg") and t["f"].get("vix_chg") is not None
                                   and t["f"]["vix_chg"] < NIGHT_VIX_CUT)]
    add("C6", "Night (R3)", "skip when India VIX is down more than 3.13% today", night, c6, skip=True)
    import flow_port as F
    by, _ = F.build(stop_fracs=(0.20, 0.30, 0.40, None))
    for t in by[0.20]:
        assert any(abs(t["net"] - u["net"]) < 1e-6 and t["day"] == u["day"] for u in flow), "Flow parity"
    for cid, sf in (("C4a", 0.30), ("C4b", 0.40), ("C4c", None)):
        tr = [dict(t, period=LK.period(t["day"])) for t in by[sf]]
        add(cid, "IraThinker Flow", f"premium stop {'none' if sf is None else f'{int(sf * 100)}%'} (was 20%)", flow, tr)
    (HERE / "results" / "before_after.json").write_text(json.dumps(res, indent=1, default=str))
    return res


if __name__ == "__main__":
    run()
