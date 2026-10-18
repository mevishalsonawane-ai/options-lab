"""R76 study (PREREG.md): Boss's "25% - 35% of target" lock on Liquidity 15+5. Once the INDEX has covered X% of the way
from the entry index price to the trade's own index target, the option's stop rises to +5% over the fill (T25, T30,
T35, T50), plus the premium reading "+5% once the option is +35%" (L35), each against Liquidity's own exits (B0).
Research only. 18 Oct 2026.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build BANKNIFTY FINNIFTY MIDCPNIFTY   # R51's clean stores
    python research/hunt/r76/r76study.py design
    python research/hunt/r76/r76study.py final                 # the ONE final read (refuses a second)
    python research/hunt/r76/r76study.py summary

The trades are made once with B0 (fam_liq, exactly as R63 / R70); the same trades (entry row, index-exit row) are priced
again under each variant. Trades go to $R76_OUT (never git); results/ holds small JSON only.
"""
from __future__ import annotations

import json
import os
import pickle
import subprocess
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
for _d in ("r64", "r65", "r70"):
    sys.path.insert(0, str(HERE.parent / _d))
import r64cfg as R64  # noqa: E402
import r64study as P  # noqa: E402  (stats, periods, bh, paired_boot)
import r65study as Q  # noqa: E402  (exit_twin_totals, p_of, forced_rows)
import r70study as R70  # noqa: E402  (LIQ_BOOKS, LIQ_IDX_STOP_MIDCP, _fields, _price)

OUT = Path(os.environ.get("R76_OUT", R64.CACHE_ROOT / "r76")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
KEEP = 0.05                                       # the stop rises to +5% over the fill
# id -> ("idx", share of the way to the index target) or ("prem", option gain over the fill)
VARIANTS = {"T25": ("idx", 0.25), "T30": ("idx", 0.30), "T35": ("idx", 0.35), "T50": ("idx", 0.50),
            "L35": ("prem", 0.35)}
TESTS = tuple(VARIANTS)
ALL = ("B0",) + TESTS
TWINS, SEED, BOOT = 500, 76, 2000
GATE_Q = 0.10
PHASES = ("design", "wf", "final")


# ================================================================================================ pure
def with_step(ex_fields: dict, gain: float, keep: float = KEEP) -> dict:
    """The arm's Exits fields plus one premium step (kept with any steps it already has). Pure."""
    return dict(ex_fields, steps=tuple(ex_fields.get("steps", ())) + ((gain, keep),))


def changed(b0: list, v: list) -> list:
    """Positions (same trades, same order) where the variant's net differs from B0's. Pure."""
    assert len(b0) == len(v)
    return [i for i, (x, y) in enumerate(zip(b0, v)) if abs(y["net"] - x["net"]) > 1e-6]


def account(b0: list, v: list) -> dict:
    """Trade by trade against B0: how many changed, Rs given up and saved, best / worst example. Pure."""
    idx = changed(b0, v)
    d = [(v[i]["net"] - b0[i]["net"], b0[i], v[i]) for i in idx]
    worse = [z for z in d if z[0] < 0]
    better = [z for z in d if z[0] > 0]

    def ex(z):
        if z is None:
            return None
        _, x, y = z
        return dict(und=x["und"], day=str(x["day"]), side=x["side"], strike=x["strike"], v_net=round(y["net"]),
                    b0_net=round(x["net"]), v_ret=round(y["ret"], 3), b0_ret=round(x["ret"], 3), v_why=y["why"],
                    b0_why=x["why"], b0_peak=round(x.get("peak", float("nan")), 3))
    return dict(changed=len(d), n_worse=len(worse), given_up=round(-sum(z[0] for z in worse)), n_better=len(better),
                saved=round(sum(z[0] for z in better)), net_effect=round(sum(z[0] for z in d)),
                worst_example=ex(min(worse, key=lambda z: z[0]) if worse else None),
                best_example=ex(max(better, key=lambda z: z[0]) if better else None))


def lock_split(b0: list, v: list) -> dict:
    """The changed trades split by how the +5% lock sold them: 'below' = the lock was armed while the option was still
    under +5%, so the stop sat above the market and sold at the next open below +5% (a real SL above the price fires at
    once); 'at5' = sold at +5% after the option had been above it; 'other' = changed another way. Count and Rs effect
    against B0 for each. Descriptive only (18 Oct 2026). Pure."""
    out = {k: [0, 0.0] for k in ("below", "at5", "other")}
    for i in changed(b0, v):
        y = v[i]
        k = "other" if y["why"] != "lock" else ("below" if y["X"] < y["E"] * (1 + KEEP) - 1e-9 else "at5")
        out[k][0] += 1
        out[k][1] += y["net"] - b0[i]["net"]
    return {k: dict(n=n, rs=round(r)) for k, (n, r) in out.items()}


def decide(res: dict, v: str) -> dict:
    """PREREG section 3: recommended only if net >= B0's in design, walk-forward and final and exit-twin q <= 0.10;
    'harmless' if it never made less than B0 in any of the three."""
    b, r = res["B0"], res[v]
    never_less = all(r[ph]["net"] >= b[ph]["net"] for ph in PHASES)
    rec = never_less and r.get("exit_q") is not None and r["exit_q"] <= GATE_Q
    return dict(recommended=bool(rec), harmless=never_less)


# ================================================================================================ helpers
def _save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name):
    p = OUT / f"{name}.pkl"
    return pickle.loads(p.read_bytes()) if p.exists() else []


def _plain(phase):
    """Liquidity's B0 trades, exactly as R70 made them (fam_liq per book, keeping e0 / fx0)."""
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51cfg as K
    import common as C
    import engine as E
    import fam_liq

    assert K.CLEAN
    if phase == "final":
        C.open_final()
        lo, hi = R64.FINAL_START, K.END
    else:
        lo, hi = R64.START, R64.DESIGN_END
    fam_liq.IDX_STOP.setdefault("MIDCPNIFTY", R70.LIQ_IDX_STOP_MIDCP)
    orig = E.run_day

    def rec(und, D, sides, strikes, entries, ex, forced=None):          # as r70study.run_liq: keep e0 / fx0
        res = orig(und, D, sides, strikes, entries, ex, forced)
        fx = [-1] * len(entries) if forced is None else list(forced)
        for i, o in enumerate(res):
            if o is not None:
                o["e0"], o["fx0"] = int(entries[i]), int(fx[i])
        return res
    E.run_day = rec
    plain = []
    try:
        for und, books in R70.LIQ_BOOKS.items():
            T = fam_liq.make(und, books).run(lo, hi)
            plain += T.to_dict("records") if len(T) else []
    finally:
        E.run_day = orig
    for t in plain:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    plain.sort(key=lambda t: (t["day"], t["und"], t["er"]))
    return plain, fam_liq.EX


def run_phase(phase: str) -> dict:
    plain, ex_b0 = _plain(phase)
    import engine as E
    import r51data
    import r64engine as G
    import r76engine as X

    load = r51data.load
    tr = {"B0": R70._price(G, load, plain, ex_b0)}
    mine = []                                       # B0 through R76's engine must equal R64's, trade by trade
    for t in plain:
        r = X.price_trade(t["und"], load(t["und"])[t["day"]], t, ex_b0, None)
        if r is not None:
            mine.append(r)
    assert len(mine) == len(tr["B0"]) and all(abs(a["net"] - b["net"]) < 1e-6 for a, b in zip(mine, tr["B0"]))
    keep_meta = ("sgn", "close", "target", "book", "e0", "fx0")
    for v, (kind, x) in VARIANTS.items():
        if kind == "prem":
            tr[v] = R70._price(G, load, plain, E.Exits(**with_step(R70._fields(ex_b0), x)))
        else:
            tr[v] = []
            for t in plain:
                r = X.price_trade(t["und"], load(t["und"])[t["day"]], t, ex_b0, x, KEEP)
                if r is not None:
                    r.update({k: t[k] for k in keep_meta if k in t})
                    tr[v].append(r)
        assert len(tr[v]) == len(tr["B0"])
    out = {"plain_net": round(sum(t["net"] for t in plain)), "plain_trades": len(plain),
           "no_target": sum(1 for t in plain if not np.isfinite(t.get("target", np.nan))), "res": {}}
    for v in ALL:
        _save(f"{v}_{phase}", tr[v])
        out["res"][v] = P.periods(tr[v], "Liquidity", phase)
        if v != "B0":
            out["res"][v]["changed"] = len(changed(tr["B0"], tr[v]))
            out["res"][v]["triggered"] = (sum(1 for t in tr[v] if t.get("trig", -1) >= 0)
                                          if VARIANTS[v][0] == "idx" else None)
    if phase == "final":
        for v in TESTS:
            k = out["res"][v]["changed"]

            def nf(t, rows):
                D = load(t["und"])[t["day"]]
                m = len(rows)
                return G.twin_nets(t["und"], D, [t["side"]] * m, [t["strike"]] * m, [t["er"]] * m, ex_b0,
                                   Q.forced_rows(rows, t["fx0"]))
            tw = Q.exit_twin_totals(tr["B0"], k, TWINS, np.random.default_rng(SEED), nf)
            out["res"][v]["exit_p"] = Q.p_of(tw, sum(t["net"] for t in tr[v]))
            out["res"][v]["exit_k"] = k
    key = "final" if phase == "final" else "design"
    print(phase, {v: out["res"][v][key]["net"] for v in ALL}, flush=True)
    return out


# ================================================================================================ steps
def run(phase: str):
    path = RESULTS / f"{phase}.json"
    if phase == "final":
        if not FINAL_FLAG.exists():
            raise SystemExit("R76: the final read is opened only by `r76study.py final`")
        if path.exists():
            raise SystemExit(f"R76: {path.name} exists - the final test is read once")
    res = run_phase(phase)
    RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(res, indent=1, default=str))


def _sub(phase):
    subprocess.run([sys.executable, str(Path(__file__).resolve()), "run", phase], check=True)


def design():
    _sub("design")


def final():
    if FINAL_FLAG.exists():
        raise SystemExit("R76: the final test was already read (results/FINAL_READ). One read only.")
    RESULTS.mkdir(parents=True, exist_ok=True)
    FINAL_FLAG.write_text(f"final test read on {datetime.now(timezone.utc).isoformat()}\n")
    _sub("final")
    summarise()


def summarise() -> dict:
    d, f = (json.loads((RESULTS / f"{ph}.json").read_text()) for ph in ("design", "final"))
    out = {"plain_check": {ph: dict(plain=x["plain_net"], trades=x["plain_trades"], b0=x["res"]["B0"][k]["net"])
                           for ph, x, k in (("design", d, "design"), ("final", f, "final"))},
           "no_target": {"design": d["no_target"], "final": f["no_target"]}, "res": {}}
    T = {v: {ph: _load(f"{v}_{ph}") for ph in ("design", "final")} for v in ALL}
    for v in ALL:
        a, b = d["res"][v], f["res"][v]
        A = out["res"][v] = dict(design=a["design"], wf=a["wf"], wf_quarters=a["wf_quarters"], final=b["final"])
        if v != "B0":
            A.update(changed=dict(design=a["changed"], final=b["changed"]),
                     triggered=dict(design=a["triggered"], final=b["triggered"]), exit_p=b["exit_p"],
                     exit_k=b["exit_k"])
            A["trail"] = {ph: account(T["B0"][ph], T[v][ph]) for ph in ("design", "final")}
            A["lock_split"] = {ph: lock_split(T["B0"][ph], T[v][ph]) for ph in ("design", "final")}
            A["paired"] = {ph: dict(zip(("p", "diff"), P.paired_boot(T["B0"][ph], T[v][ph], BOOT, SEED)))
                           for ph in ("design", "final")}
    qs = P.bh([out["res"][v]["exit_p"] for v in TESTS])
    for v, q in zip(TESTS, qs):
        out["res"][v]["exit_q"] = round(float(q), 4)
        out["res"][v]["decision"] = decide(out["res"], v)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "summary.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run" and len(sys.argv) == 3 and sys.argv[2] in ("design", "final"):
        run(sys.argv[2])
    elif cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "summary":
        summarise()
    else:
        print(__doc__)
