"""h43 part A: reproduce the outside report on ITS window (2026-08-31 .. data end 2026-10-05) with the obuy engine.

    flock <scratch>/obuy.lock python3 -I research/hunt/h43/partA.py

Modes: their costs (fills at the raw option open / stop / target / next open, Rs 95 per lot round trip) and ours
(app fills +-5/10 bps, app charges, + real half-spread 0.16% on entry and exit); exits on the option's 1-min HIGH/LOW
(stop first on ties) and the close-based variant (stop/target decided on 1-min closes, filled at the next open).
"""
from __future__ import annotations

import os
import sys
from dataclasses import replace
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, Exits, StrikeRule, positions, prepare_many  # noqa: E402
import sigs  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h43")
SQ = 15 * 60 + 15
HS = 0.0016
EXITS = {
    "T40": Exits(stop_pct=0.30, tgt_pct=0.60, time_stop=40, time_gain=None, sq_off=SQ),
    "T15": Exits(stop_pct=0.30, tgt_pct=0.60, time_stop=15, time_gain=None, sq_off=SQ),
    "T30": Exits(stop_pct=0.30, tgt_pct=0.60, time_stop=30, time_gain=None, sq_off=SQ),
    "EOD60": Exits(stop_pct=0.30, tgt_pct=0.60, sq_off=SQ),
    "EOD120": Exits(stop_pct=0.30, tgt_pct=1.20, sq_off=SQ),
    "T60": Exits(stop_pct=0.30, tgt_pct=0.60, time_stop=60, time_gain=None, sq_off=SQ),
    "T45": Exits(stop_pct=0.30, tgt_pct=0.60, time_stop=45, time_gain=None, sq_off=SQ),
    "T20": Exits(stop_pct=0.30, tgt_pct=0.60, time_stop=20, time_gain=None, sq_off=SQ),
    # the parameter document's CHAMPION: 25% stop, 50% target, lock at +25/50/75/100% once the peak passes it, 60 min
    "CHAMP": Exits(stop_pct=0.25, tgt_pct=0.50, ladder=((1, 1), (2, 2), (3, 3), (4, 4)), ladder_ref_pct=0.25,
                   time_stop=60, time_gain=None, sq_off=SQ),
}
EXE_APP = Execution(expiry="skip", lot_mode="today")
EXE_THEIRS = Execution(fills=Fills("flat", pts=0.0), costs=Costs("flat", per_order=47.5), expiry="skip", lot_mode="today")
W0, W1 = date(2026, 8, 31), date(2026, 10, 7)
REPORT = {("ema", 5): 7656, ("ema", 15): 6662, ("bb", 1): 10852, ("bb", 5): -222, ("bb", 15): -16760,
          ("orb15", 5): 4573, ("orb15", 15): -6372, ("pe", 5): 3638, ("pe", 15): -5710, ("orb30", 5): 912, ("champ", 5): 13809, ("champM", 5): 13809}
REPORT_N = {("ema", 5): 22, ("bb", 1): 46, ("orb15", 5): 22, ("pe", 5): 32, ("orb30", 5): 21, ("champ", 5): 33, ("champM", 5): 33}


def main():
    mk = market()
    ix = mk.index("BANKNIFTY")
    wdays = [d for d in ix.days if W0 <= d <= W1]
    print("window sessions in our data:", len(wdays), wdays[0], wdays[-1], "expiry:",
          [d for d in wdays if ix.d[d]["exp"]], "lot:", ix.lot(wdays[-1], "today"))
    allsig = {}
    for s, tf, ek in sigs.variants():
        if (s, tf) not in allsig:
            x = sigs.make(ix, s, tf)
            allsig[(s, tf)] = x[(x.day >= W0) & (x.day <= W1)].reset_index(drop=True)
    keys = list(allsig)
    rule = lambda k: StrikeRule(0, series="month" if k[0] == "champM" else "near")  # noqa: E731
    jobs = [(allsig[k], rule(k), EXE_APP, 0, None, False) for k in keys]
    jobs += [(allsig[k], rule(k), EXE_THEIRS, 0, None, False) for k in keys]
    packs = prepare_many(jobs)
    rows, trades = [], []
    for s, tf, ek in sigs.variants():
        j = keys.index((s, tf))
        for cost, pk in (("ours", packs[j][0]), ("theirs", packs[len(keys) + j][0])):
            exe = EXE_APP if cost == "ours" else EXE_THEIRS
            for mode in ("hilo", "close"):
                ex = EXITS[ek] if mode == "hilo" else replace(EXITS[ek], intrabar=False)
                tr = pk.run(ex, exe)
                tr = positions(tr, one_at_a_time=True, max_per_day=sigs.STRATS[s][0])
                if cost == "ours":
                    tr["net"] = tr.net - HS * (tr.entry + tr.exit) * tr.qty
                    tr["gross_raw"] = tr.gross
                n = len(tr)
                h = sorted(tr.day.unique())
                half = h[len(h) // 2] if h else None
                e1 = tr[tr.day < half].net.sum() if n else 0
                rows.append(dict(strat=s, tf=tf, exit=ek, cost=cost, mode=mode, trades=n, gross=tr.gross.sum(),
                                 net=tr.net.sum(), win=(tr.net > 0).mean() if n else np.nan,
                                 pf=tr.net[tr.net > 0].sum() / max(-tr.net[tr.net < 0].sum(), 1e-9) if n else np.nan,
                                 early=e1, late=tr.net.sum() - e1,
                                 why=" ".join(f"{k}:{v}" for k, v in tr.why.value_counts().items()),
                                 report=REPORT.get((s, tf) if ek != "EOD120" else ("x", 0), np.nan),
                                 report_n=REPORT_N.get((s, tf) if ek != "EOD120" else ("x", 0), np.nan)))
                tr = tr.assign(strat=s, tf=tf, exit_k=ek, cost=cost, mode=mode)
                trades.append(tr)
    res = pd.DataFrame(rows)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 30)
    print(res.round(0).to_string())
    res.to_csv(os.path.join(OUT, "partA.csv"), index=False)
    pd.concat(trades).to_csv(os.path.join(OUT, "partA_trades.csv.gz"), index=False)


if __name__ == "__main__":
    main()
