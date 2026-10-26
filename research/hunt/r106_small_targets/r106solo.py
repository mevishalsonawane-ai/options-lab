"""R106 on Solo 2.0 (R57 A2; PREREG section 1): A2's own picks, regenerated with R57's frozen pipeline (its stores,
features and monthly refits), must reproduce R57's published figures (design walk-forward 166 trades / Rs 55,910;
final 128 trades / Rs 99,172). The picks are then kept as they are and only their exits change (R106's 9 rules).

    python research/hunt/r57/r57data.py build                          # R57's stores (to 14 Oct)
    python research/hunt/r106_small_targets/r106solo.py design
    python research/hunt/r106_small_targets/r106solo.py final          # inside R106's one final read

Runs in its own process: R57 re-points R51's settings at R57's cache. Solo's "design" is A2's out-of-fold months only
(Jul 2025 - Mar 2026: the model needs earlier months to train), so its design and walk-forward window are the same.
R57's stores end on 14 Oct 2026: Solo's final is 1 Apr - 13 Oct and its post window is 14 Oct only.
"""
from __future__ import annotations

import json
import sys
from datetime import date
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r57"))
import r57cfg as K57  # noqa: E402  (first: points r51cfg at R57's cache)
import numpy as np  # noqa: E402

import r106cfg as K  # noqa: E402

PUBLISHED = {"design": (166, 55910), "final": (128, 99172)}
SOLO_LAST_ENTRY = K.row(14, 0)


def er_signal(fill_row: int) -> int:
    """A2's signal rows are all on the 5-minute grid (S1 12:00, S3 09:20, C4 10:15, S2 / S4 bar ends) and the fill is at
    most 2 minutes later, so the signal row is the fill row rounded down to the grid."""
    return int(fill_row - fill_row % 5)


def picks(phase: str) -> list:
    import r57data
    import r57setups as S
    import r57study as R
    for u in K57.UNDS:
        r57data.load(u)
    cal = R.calendar()
    a0, _ = R.load_or_build("design_data", K57.START, K57.DESIGN_END)
    v = R.VARIANTS["A2"]
    if phase == "design":
        tr, _, _ = R.run_a(v, a0, K57.WF_MONTHS, [d for d in cal if d <= K57.DESIGN_END])
    else:
        S.open_final()
        a1, _ = R.load_or_build("final_data", K57.FINAL_START, K57.END)
        tr, _, _ = R.run_a(v, a0 + a1, K57.FINAL_MONTHS, cal)
    n, net = len(tr), round(sum(t["net"] for t in tr))
    pub = PUBLISHED[phase]
    assert (n, net) == pub, ("Solo 2.0 A2 does not reproduce R57", phase, n, net, pub)
    print("Solo A2", phase, n, net, "reproduced", flush=True)
    return tr


def run(phase: str) -> dict:
    import r57data
    import r57setups as S
    import engine as E
    import r106engine as X
    import r106study as ST
    import r106walk as W
    tr = picks(phase)
    units, base = [], []
    for t in tr:
        D = r57data.load(t["und"])[t["day"]]
        ex = S.EXITS[t["setup"]]
        er = er_signal(t["er"])
        c = dict(er=er, side=t["side"], strike=t["strike"], und=t["und"], setup=t["setup"],
                 fx=S.forced_row(D, er, S.sgn(t["side"]), S.IDX_EXIT.get(t["setup"]), E.sq_row(D, ex)))
        c["fx_idx"] = c["fx"]
        c["ref_close"] = float(D.idx[3, er - 1])
        p = X.price(t["und"], D, [c], ex, None, "own")[0]
        assert p is not None and abs(p["net"] - t["net"]) < 1e-6 and p["xr"] == t["xr"], ("Solo engine parity", t)
        units.append(((t["und"], t["day"], None), [c], "one"))
        base.append(t)

    def make_re(unit, c, r):
        er = r["xr"] + 1
        D = r57data.load(c["und"])[unit[0][1]]
        now = float(D.idx[3, r["xr"]])
        sgn = 1 if c["side"] == "C" else -1
        if er > SOLO_LAST_ENTRY or not (np.isfinite(now) and sgn * (now - c["ref_close"]) > 0):
            return None
        if c["fx"] is not None and 0 <= c["fx"] <= er:
            return None
        k = E.strike_for(D, c["und"], c["side"], er - 1, S.MONEY[c["setup"]])
        if k is None:
            return None
        nc = dict(c)
        nc.update(er=er, strike=k)
        return nc

    out = {}
    trades = {"B0": []}
    for name in ("B0",) + K.RULE_IDS:
        spec = K.RULES.get(name, dict(target=None, stop="own", re=False, goal=False))
        def price_re(unit, nc, spec=spec):
            D = r57data.load(nc["und"])[unit[0][1]]
            return X.price(nc["und"], D, [nc], S.EXITS[nc["setup"]], spec["target"], spec["stop"])[0]

        by = {}
        for key, cands, how in units:
            D = r57data.load(key[0])[key[1]]
            pr = X.price(key[0], D, cands, S.EXITS[cands[0]["setup"]], spec["target"], spec["stop"])
            by.setdefault(key[1], []).append(((key, cands, how), pr))
        tt = []
        for d, us in sorted(by.items()):
            tt += W.walk_day([u for u, _ in us], [p for _, p in us], re=spec["re"], make_re=make_re, price_re=price_re,
                             max_fills=K.MAX_FILLS_PER_INDEX_DAY, goal=K.DAY_GOAL if spec["goal"] else None,
                             day_max=K.DAY_MAX_TRADES if spec["goal"] else None)
        trades[name] = tt
    # Solo trades at most one index a day; sessions = the R57 calendar's days in the phase
    import r57study as R
    cal = R.calendar()
    if phase == "design":
        lo, hi = date(2025, 7, 1), K57.DESIGN_END
    else:
        lo, hi = K57.FINAL_START, K57.END
    days = [d for d in cal if lo <= d <= hi]
    split = {"design": [("all", lo, hi)], "final": [("all", K.FINAL_START, K.FINAL_END), ("post", K.POST_START, hi)]}[phase]
    for name, tt in trades.items():
        r = {}
        for lab, a, b in split:
            sub = [t for t in tt if a <= t["day"] <= b]
            ss = [d for d in days if a <= d <= b]
            r[lab] = ST.stats(sub, len(ss))
            r[lab + "_profile"] = ST.day_goal_profile(sub, ss)
        if phase == "design":
            r["wf"] = r["all"]
        out[name] = r
    if phase == "final":
        rng = np.random.default_rng(K.SEED)
        for name in K.RULE_IDS:
            sub = [t for t in trades[name] if t["day"] <= K.FINAL_END]
            tw, ch = solo_twins(sub, rng)
            act = sum(t["net"] for t in sub)
            out[name]["twin"] = dict(p=round(ST.twin_p(act, tw), 4), mean=round(float(tw.mean())), changed=ch)
            print("SOLO", name, out[name]["all"]["net"], out[name]["twin"], flush=True)
    out["parity"] = dict(published=list(PUBLISHED[phase]), reproduced=True)
    (K.RESULTS / f"{phase}_SOLO.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def solo_twins(trades: list, rng) -> tuple:
    import r57data
    import r57setups as S
    import r106engine as X
    import r106study as ST
    tot = np.zeros(K.TWINS)
    changed = 0
    for t in trades:
        D = r57data.load(t["und"])[t["day"]]
        ex = S.EXITS[t["setup"]]
        c = dict(side=t["side"], strike=t["strike"], er=t["er_sig"], fx=t.get("fx_idx", -1))
        p = X.price(t["und"], D, [c], ex, None, "own")[0]
        if p is None or t["xr"] >= p["xr"] or p["xr"] - t["er"] < 2:
            tot += t["net"]
            continue
        changed += 1
        rows = ST.twin_rows(t["er"], p["xr"], K.TWINS, rng)
        tot += X.forced_exit_nets(t["und"], D, t, ex, rows, t.get("fx_idx", -1))
    return tot, changed


if __name__ == "__main__":
    ph = sys.argv[1] if len(sys.argv) > 1 else ""
    if ph == "final":
        assert K.FINAL_FLAG.exists(), "Solo's final is read inside R106's one final read"
        if (K.RESULTS / "final_SOLO.json").exists():
            raise SystemExit("Solo final already read once")
    if ph in ("design", "final"):
        run(ph)
    else:
        raise SystemExit(__doc__)
