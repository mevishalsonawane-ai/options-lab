"""R70 study (PREREG.md): Boss's selected lock (R66 L25: stop to +5% once the option has traded +25%) on Liquidity 15+5
and Solo midday, each against its own exits (B0). Research only.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R51's clean stores (Liquidity)
    python research/hunt/r57/r57data.py build                  # R57's stores (Solo midday)
    python research/hunt/r70/r70study.py design
    python research/hunt/r70/r70study.py final                 # the ONE final read (refuses a second)
    python research/hunt/r70/r70study.py report

Each arm's trades are made once with its own exits (fam_liq / plain_solo); the same trades (entry row, index-exit row)
are priced again with L25. Each arm runs in its own process (r57cfg re-points the shared R51 engine). Trades go to
$R70_OUT (never git); results/ holds small JSON / markdown.
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
sys.path.insert(0, str(HERE.parent / "r64"))
sys.path.insert(0, str(HERE.parent / "r65"))
import r64cfg as R64  # noqa: E402
import r64study as P  # noqa: E402  (stats, periods, split, bh, paired_boot)
import r65study as Q  # noqa: E402  (exit_twin_totals, p_of, forced_rows)

OUT = Path(os.environ.get("R70_OUT", R64.CACHE_ROOT / "r70")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
ARMS = ("Liquidity", "SoloMidday")
VARIANTS = ("B0", "L25")
L25_STEP = ((0.25, 0.05),)
LIQ_BOOKS = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5), "MIDCPNIFTY": (15, 5)}   # as R63 (r63run.LIQ_BOOKS)
LIQ_IDX_STOP_MIDCP = 8.0
TWINS, SEED, BOOT = 500, 70, 2000
GATE_Q = 0.10


# ================================================================================================ pure
def with_l25(ex_fields: dict) -> dict:
    """An arm's Exits fields plus the L25 step (kept with any steps it already has). Pure."""
    return dict(ex_fields, steps=tuple(ex_fields.get("steps", ())) + L25_STEP)


def account(b0: list, l25: list) -> dict:
    """Trade by trade, by position (the same trades, priced twice, in the same order; Liquidity's two books can share a
    side and a fill minute, so keys would collide): how many L25 changed, Rs given up and saved. Pure."""
    assert len(b0) == len(l25)
    d = [(y["net"] - x["net"], x, y) for x, y in zip(b0, l25) if abs(y["net"] - x["net"]) > 1e-6]
    worse = [z for z in d if z[0] < 0]
    better = [z for z in d if z[0] > 0]

    def ex(z):
        if z is None:
            return None
        _, x, y = z
        return dict(und=x["und"], day=str(x["day"]), side=x["side"], l25_net=round(y["net"]), b0_net=round(x["net"]),
                    l25_ret=round(y["ret"], 3), b0_ret=round(x["ret"], 3), b0_why=x["why"])
    return dict(changed=len(d), n_worse=len(worse), given_up=round(-sum(z[0] for z in worse)), n_better=len(better),
                saved=round(sum(z[0] for z in better)), net_effect=round(sum(z[0] for z in d)),
                worst_example=ex(min(worse, key=lambda z: z[0]) if worse else None),
                best_example=ex(max(better, key=lambda z: z[0]) if better else None))


def decide(arm: dict) -> dict:
    """PREREG section 3: L25 recommended only if its net >= B0's in design, walk-forward and final and exit-twin q <=
    0.10; 'harmless' if it never made less than B0 in any of the three."""
    b, l = arm["B0"], arm["L25"]
    never_less = all(l[ph]["net"] >= b[ph]["net"] for ph in ("design", "wf", "final"))
    rec = never_less and l.get("exit_q") is not None and l["exit_q"] <= GATE_Q
    return dict(recommended="L25" if rec else None, harmless=never_less)


# ================================================================================================ helpers
def _save(name, obj):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"{name}.pkl").write_bytes(pickle.dumps(obj, protocol=pickle.HIGHEST_PROTOCOL))


def _load(name):
    p = OUT / f"{name}.pkl"
    return pickle.loads(p.read_bytes()) if p.exists() else []


def _exits(E, fields: dict):
    return E.Exits(**fields)


def _fields(ex) -> dict:
    return dict(stop=ex.stop, target=ex.target, lock_trig=ex.lock_trig, lock_keep=ex.lock_keep,
                lock_trail=ex.lock_trail, steps=tuple(ex.steps), tmax=ex.tmax, need=ex.need,
                close_stop=ex.close_stop, sq_row=ex.sq_row)


def _price(G, load, plain: list, ex) -> list:
    """The same trades priced with `ex`: each from its signal row `e0`, with its own index-exit row `fx0`."""
    out = []
    for t in plain:
        D = load(t["und"])[t["day"]]
        r = G.run_day(t["und"], D, [t["side"]], [t["strike"]], [t["e0"]], ex, [t["fx0"]])[0]
        if r is not None:
            r.update(e0=t["e0"], fx0=t["fx0"])
            out.append(r)
    return out


def _evaluate(arm, phase, plain, ex_b0, ex_l25, G, load, out):
    tr = {"B0": _price(G, load, plain, ex_b0)}
    tr["L25"] = _price(G, load, plain, ex_l25)
    out[arm] = {}
    for v in VARIANTS:
        _save(f"{arm}_{v}_{phase}", tr[v])
        out[arm][v] = P.periods(tr[v], arm, phase)
    out[arm]["L25"]["changed"] = sum(1 for a, b in zip(tr["B0"], tr["L25"]) if abs(a["net"] - b["net"]) > 1e-6)
    if phase == "final":
        k = out[arm]["L25"]["changed"]

        def nf(t, rows):
            D = load(t["und"])[t["day"]]
            m = len(rows)
            return G.twin_nets(t["und"], D, [t["side"]] * m, [t["strike"]] * m, [t["er"]] * m, ex_b0,
                               Q.forced_rows(rows, t["fx0"]))
        tw = Q.exit_twin_totals(tr["B0"], k, TWINS, np.random.default_rng(SEED), nf)
        out[arm]["L25"]["exit_p"] = Q.p_of(tw, sum(t["net"] for t in tr["L25"]))
        out[arm]["L25"]["exit_k"] = k
    print(arm, phase, {v: out[arm][v][phase if phase == "final" else "design"]["net"] for v in VARIANTS}, flush=True)


# ================================================================================================ arms
def run_liq(phase: str) -> dict:
    os.environ["R51_CLEAN"] = "1"
    sys.path.insert(0, str(HERE.parent / "r51"))
    import r51cfg as K
    import common as C
    import engine as E
    import fam_liq
    import r51data
    import r64engine as G

    assert K.CLEAN
    if phase == "final":
        C.open_final()
        lo, hi = R64.FINAL_START, K.END
    else:
        lo, hi = R64.START, R64.DESIGN_END
    fam_liq.IDX_STOP.setdefault("MIDCPNIFTY", LIQ_IDX_STOP_MIDCP)
    orig = E.run_day

    def rec(und, D, sides, strikes, entries, ex, forced=None):          # as r63core.record: keep e0 / fx0
        res = orig(und, D, sides, strikes, entries, ex, forced)
        fx = [-1] * len(entries) if forced is None else list(forced)
        for i, o in enumerate(res):
            if o is not None:
                o["e0"], o["fx0"] = int(entries[i]), int(fx[i])
        return res
    E.run_day = rec
    plain = []
    for und, books in LIQ_BOOKS.items():
        T = fam_liq.make(und, books).run(lo, hi)
        plain += T.to_dict("records") if len(T) else []
    E.run_day = orig
    for t in plain:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    plain.sort(key=lambda t: (t["day"], t["und"], t["er"]))
    ex_b0 = fam_liq.EX
    out = {"plain_net": round(sum(t["net"] for t in plain)), "plain_trades": len(plain)}
    _evaluate("Liquidity", phase, plain, ex_b0, _exits(E, with_l25(_fields(ex_b0))), G, r51data.load, out)
    return out


def run_solo(phase: str) -> dict:
    sys.path.insert(0, str(HERE.parent / "r57"))
    import r57cfg as K57
    import r57data
    import r57setups as S
    import r57study as ST
    import engine as E
    import r64engine as G

    for u in K57.UNDS:
        r57data.load(u)
    if phase == "final":
        S.open_final()
        rows, _ = ST.load_or_build("final_data", K57.FINAL_START, K57.END)
        lo, hi = K57.FINAL_START, K57.END
    else:
        rows, _ = ST.load_or_build("design_data", K57.START, K57.DESIGN_END)
        lo, hi = max(K57.START, R64.START), K57.DESIGN_END
    picks = ST.plain_solo(rows, lo, hi)
    ex_b0 = S.EXITS["S1"]
    plain = []
    for p in picks:
        D = r57data.load(p["und"])[p["day"]]
        fx = S.forced_row(D, p["er"], S.sgn(p["side"]), S.IDX_EXIT.get("S1"), E.sq_row(D, ex_b0))
        plain.append(dict(und=p["und"], day=p["day"], side=p["side"], strike=p["strike"], e0=int(p["er"]), fx0=int(fx),
                          net=p["net"]))
    out = {"plain_net": round(sum(p["net"] for p in picks)), "plain_trades": len(picks)}
    _evaluate("SoloMidday", phase, plain, ex_b0, _exits(E, with_l25(_fields(ex_b0))), G, r57data.load, out)
    return out


# ================================================================================================ steps
GROUPS = {"liq": run_liq, "solo": run_solo}


def run(group: str, phase: str):
    path = RESULTS / f"{phase}_{group}.json"
    if phase == "final":
        if not FINAL_FLAG.exists():
            raise SystemExit("R70: the final read is opened only by `r70study.py final`")
        if path.exists():
            raise SystemExit(f"R70: {path.name} exists - the final test is read once")
    res = GROUPS[group](phase)
    RESULTS.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(res, indent=1, default=str))


def _sub(group, phase):
    subprocess.run([sys.executable, str(Path(__file__).resolve()), "run", group, phase], check=True)


def design():
    for g in GROUPS:
        _sub(g, "design")


def final():
    if FINAL_FLAG.exists():
        raise SystemExit("R70: the final test was already read (results/FINAL_READ). One read only.")
    RESULTS.mkdir(parents=True, exist_ok=True)
    FINAL_FLAG.write_text(f"final test read on {datetime.now(timezone.utc).isoformat()}\n")
    for g in GROUPS:
        _sub(g, "final")
    summarise()


def summarise() -> dict:
    des, fin, plain = {}, {}, {}
    for g, arm in zip(GROUPS, ARMS):
        d, f = (json.loads((RESULTS / f"{ph}_{g}.json").read_text()) for ph in ("design", "final"))
        des[arm], fin[arm] = d[arm], f[arm]
        plain[arm] = {"design": d["plain_net"], "final": f["plain_net"]}
    out = {"arms": {}}
    for arm in ARMS:
        A = out["arms"][arm] = {}
        for v in VARIANTS:
            d, f = des[arm][v], fin[arm][v]
            A[v] = dict(design=d["design"], wf=d["wf"], wf_quarters=d["wf_quarters"], final=f["final"],
                        changed=dict(design=d.get("changed"), final=f.get("changed")), exit_p=f.get("exit_p"),
                        exit_k=f.get("exit_k"))
        T = {v: {ph: _load(f"{arm}_{v}_{ph}") for ph in ("design", "final")} for v in VARIANTS}
        A["L25"]["trail"] = {ph: account(T["B0"][ph], T["L25"][ph]) for ph in ("design", "final")}
        A["L25"]["paired"] = {ph: dict(zip(("p", "diff"), P.paired_boot(T["B0"][ph], T["L25"][ph], BOOT, SEED)))
                              for ph in ("design", "final")}
        A["plain_check"] = {ph: dict(plain=plain[arm][ph], b0=A["B0"][ph]["net"]) for ph in ("design", "final")}
    qs = P.bh([out["arms"][a]["L25"]["exit_p"] or 1.0 for a in ARMS])
    for a, q in zip(ARMS, qs):
        out["arms"][a]["L25"]["exit_q"] = round(float(q), 4)
        out["arms"][a]["decision"] = decide(out["arms"][a])
    (RESULTS / "summary.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "run" and len(sys.argv) == 4 and sys.argv[2] in GROUPS and sys.argv[3] in ("design", "final"):
        run(sys.argv[2], sys.argv[3])
    elif cmd == "design":
        design()
    elif cmd == "final":
        final()
    elif cmd == "summary":
        summarise()
    else:
        print(__doc__)
