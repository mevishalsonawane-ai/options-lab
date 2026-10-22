"""R104 study (PREREG.md): 16 tests, design first, the holdout once.

    R103_LAKE=<lake> R103_OUT=<cache> R102_LAKE=<lake> python research/hunt/r104_follow/r104study.py design
    ... r104study.py holdout      # ONE read for design passers (results/HOLDOUT_READ)
    ... r104study.py report       # results/report.md

Trades go to <R103_OUT>/r104_trades_<part>.pkl (never in git); results/ holds small JSON / markdown only.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np
from scipy.stats import binomtest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r103_sell"))
import r103data as RD  # noqa: E402
import r103study as S3  # noqa: E402
import r104engine as F  # noqa: E402

RESULTS = HERE / "results"
HOLDOUT_FLAG = RESULTS / "HOLDOUT_READ"
SEED = 104
TWIN_PER_DAY, TWIN_DRAWS = 20, 200
TESTS = [(u, t, k, x) for u in RD.UNDS for t in ("E", "N") for k in F.KS for x in F.EXITS]


def test_id(u, t, k, x) -> str:
    return f"{u}-{t}-k{k:g}-{x}"


def expiry_maps(und: str, days: dict) -> tuple[set, dict]:
    os.environ.setdefault("R102_LAKE", str(RD.LAKE))
    sys.path.insert(0, str(HERE.parent / "r102_deep"))
    import r102data as B
    B.LAKE = RD.LAKE
    e = B.expiries(und)
    return S3.expiry_days(und, days), dict(zip(e.day, e.expiry.dt.date))


def trades_for(part: str, mult: float = 1.0, only: set | None = None) -> tuple[dict, dict, dict]:
    """({test id: [trade]}, {test id: [fade twin]}, {test id: skipped entries})."""
    if part == "holdout" and not HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked: only `r104study.py holdout` may read it (PREREG.md)")
    out, fade, skipped = defaultdict(list), defaultdict(list), defaultdict(int)
    for u in RD.UNDS:
        days = RD.load(u)
        exp, exp_of = expiry_maps(u, days)
        for d in sorted(days):
            if d < RD.START or S3.period_of(d) != part or days[d].atm is None:
                continue
            D = days[d]
            t = "E" if d in exp else "N"
            for (uu, tt, k, x) in TESTS:
                tid = test_id(uu, tt, k, x)
                if uu != u or tt != t or (only is not None and tid not in only):
                    continue
                a = F.anchor(D, u)
                if a is None or F.trigger(D, a[0], a[1], k) is None:
                    continue
                tr = F.run(D, u, d, k, x, t == "E", exp_of.get(d), mult)
                if tr is None:
                    skipped[tid] += 1
                    continue
                out[tid].append(tr)
                if mult == 1.0:
                    fz = F.run(D, u, d, k, x, t == "E", exp_of.get(d), 1.0, fade=True, exit_at=tr["xr"])
                    fade[tid].append(fz)
    return out, fade, skipped


def fade_gate(tr: list, fz: list, rng) -> dict:
    pairs = [(a["net"], b["net"]) for a, b in zip(tr, fz) if b is not None]
    if len(pairs) < 2:
        return dict(n=len(pairs), mean_diff=None, p=1.0, fade_net=None)
    diff = np.array([a - b for a, b in pairs])
    means = diff[rng.integers(0, len(diff), size=(S3.BOOT, len(diff)))].mean(axis=1)
    return dict(n=len(pairs), mean_diff=round(float(diff.mean()), 1), p=float((means <= 0).mean()),
                fade_net=round(float(sum(b for _, b in pairs))), fade_win=round(float(np.mean([b > 0 for _, b in pairs])), 3))


def continuation(tr: list) -> dict:
    g = [t["geo"] for t in tr if t.get("geo") is not None]
    if not g:
        return dict(n=0)
    hits = int(sum(g))
    return dict(n=len(g), rate=round(hits / len(g), 4), p_vs_third=round(binomtest(hits, len(g), 1 / 3, alternative="greater").pvalue, 4),
                undecided=int(sum(t.get("geo") is None for t in tr)))


def twin_totals(tid: str, real: list, rng) -> np.ndarray:
    u, t, kk, x = tid.split("-")
    k = float(kk[1:])
    days = RD.load(u)
    _, exp_of = expiry_maps(u, days)
    tot = np.zeros(TWIN_DRAWS)
    for tr in real:
        D = days[tr["day"]]
        i0 = tr["i0"]
        nets = []
        for r in rng.integers(F.FIRST_ROW, F.LAST_TRIG_ROW + 1, size=TWIN_PER_DAY):
            v = D.idx[3, int(r)]
            if not np.isfinite(v) or v == i0:
                continue
            z = F.run(D, u, tr["day"], k, x, t == "E", exp_of.get(tr["day"]), force=(int(r), 1 if v > i0 else -1))
            if z is not None:
                nets.append(z["net"])
        nets = nets or [0.0]
        tot += np.asarray(nets)[rng.integers(0, len(nets), size=TWIN_DRAWS)]
    return tot


def gates(s: dict, s2x: dict, q: float, fg: dict) -> dict:
    g = S3.gates(s, s2x, q)
    g.pop("pass")
    g["8_beats_fade_twin"] = fg.get("mean_diff") is not None and fg["mean_diff"] > 0 and fg["p"] < S3.BOOT_P
    g["pass"] = all(g.values())
    return g


def _save(name, obj):
    RD.OUT.mkdir(parents=True, exist_ok=True)
    with open(RD.OUT / f"r104_{name}.pkl", "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def _json(obj, path):
    path.write_text(json.dumps(obj, indent=1, default=str) + "\n")


def design() -> dict:
    rng = np.random.default_rng(SEED)
    tr, fz, sk = trades_for("design")
    tr2, _, _ = trades_for("design", mult=2.0)
    _save("trades_design", dict(tr=dict(tr), fade=dict(fz)))
    rows = []
    for tid in [test_id(*t) for t in TESTS]:
        s = S3.summary(tr.get(tid, []), rng)
        rows.append(dict(id=tid, design=s, slip2x_net=S3.summary(tr2.get(tid, [])).get("net", 0), skipped=sk.get(tid, 0),
                         fade=fade_gate(tr.get(tid, []), fz.get(tid, []), rng), continuation=continuation(tr.get(tid, []))))
    for r, q in zip(rows, S3.bh([r["design"].get("boot_p", 1.0) for r in rows])):
        r["q_bh"] = round(q, 4)
        r["gates"] = gates(r["design"], dict(net=r["slip2x_net"]), q, r["fade"]) if r["design"].get("n") else {"4_trades": False, "pass": False}
    for r in rows:
        if r["design"].get("n"):
            tw = twin_totals(r["id"], tr[r["id"]], rng)
            r["timing_twin"] = dict(mean_total=round(float(tw.mean())), p_twin_ge_real=round(float((tw >= r["design"]["net"]).mean()), 3))
    res = dict(part="design", design_end=str(S3.DESIGN_END), n_tests=len(rows), passed=[r["id"] for r in rows if r["gates"]["pass"]], tests=rows)
    RESULTS.mkdir(parents=True, exist_ok=True)
    _json(res, RESULTS / "design.json")
    print(f"design: {len(rows)} tests; passed: {res['passed']}")
    return res


def holdout() -> dict:
    if HOLDOUT_FLAG.exists():
        print("the holdout was already read once (results/HOLDOUT_READ); PREREG.md allows no second read", file=sys.stderr)
        raise SystemExit(2)
    dz = json.loads((RESULTS / "design.json").read_text())
    passed = set(dz["passed"])
    HOLDOUT_FLAG.write_text(f"read on {date.today().isoformat()} by r104study.py holdout; design passers: {sorted(passed)}\n")
    if not passed:
        res = dict(part="holdout", read=False, note="no test passed design; nothing read", tests=[])
        _json(res, RESULTS / "holdout.json")
        print("holdout: no design passer, nothing read")
        return res
    rng = np.random.default_rng(SEED + 1)
    tr, fz, _ = trades_for("holdout", only=passed)
    _save("trades_holdout", dict(tr=dict(tr), fade=dict(fz)))
    rows = []
    for tid in sorted(passed):
        s = S3.summary(tr.get(tid, []))
        fg = fade_gate(tr.get(tid, []), fz.get(tid, []), rng)
        dsg = next(r["design"] for r in dz["tests"] if r["id"] == tid)
        ok = (s.get("n", 0) > 0 and s["net"] > 0 and s["worst_month"] >= S3.MONTH_STOP and (fg.get("mean_diff") or 0) > 0
              and abs(s["win"] - dsg["win"]) <= 0.15)
        rows.append(dict(id=tid, holdout=s, fade=fg, continuation=continuation(tr.get(tid, [])), confirmed=bool(ok)))
    res = dict(part="holdout", read=True, confirmed=[r["id"] for r in rows if r["confirmed"]], tests=rows)
    _json(res, RESULTS / "holdout.json")
    print(f"holdout: confirmed {res['confirmed']}")
    return res


def rs(x):
    return "" if x is None else (f"Rs {x:,.0f}" if x >= 0 else f"-Rs {-x:,.0f}")


def report() -> str:
    d = json.loads((RESULTS / "design.json").read_text())
    h = json.loads((RESULTS / "holdout.json").read_text()) if (RESULTS / "holdout.json").exists() else None
    out = ["# R104 report: buy in the direction of the move once the 09:20 sellers are losing", "",
           f"Design to {d['design_end']}; 1 lot; net after slippage and dated charges; stale minutes marked by Black-Scholes. "
           f"Passed design: {d['passed'] or 'none'}.", "",
           "| test | trades | win | net | per trade | avg win / loss | worst day | worst month | max DD | 2022 / 2023 / 2024 / 2025 | after 20 Nov 2024 | ex best 5 | 2x slip | boot p | BH q | fade twin: net / win | follow minus fade, a trade (p) | continuation (n; p vs 1/3) | timing twin p | gates failed |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in d["tests"]:
        s = r["design"]
        if not s.get("n"):
            out.append(f"| {r['id']} | 0 | | | | | | | | | | | | | | | | | | no trades |")
            continue
        yrs = " / ".join(rs(s["by_year"].get(str(y), 0)) for y in (2022, 2023, 2024, 2025))
        f = r["fade"]; c = r["continuation"]
        failed = ", ".join(k for k, v in r["gates"].items() if k != "pass" and not v) or "none"
        out.append(f"| {r['id']} | {s['n']:,} | {s['win'] * 100:.0f}% | {rs(s['net'])} | {rs(s['mean'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
                   f"{rs(s['worst_day'])} | {rs(s['worst_month'])} | {rs(-s['max_dd'])} | {yrs} | {rs(s['post_regime_net'])} ({s['post_regime_n']}) | "
                   f"{rs(s['ex_best5'])} | {rs(r['slip2x_net'])} | {s.get('boot_p', 1):.3f} | {r['q_bh']:.3f} | "
                   f"{rs(f.get('fade_net'))} / {100 * (f.get('fade_win') or 0):.0f}% | {rs(f.get('mean_diff'))} ({f.get('p', 1):.3f}) | "
                   f"{100 * c.get('rate', 0):.1f}% ({c.get('n', 0)}; {c.get('p_vs_third', 1)}) | {r.get('timing_twin', {}).get('p_twin_ge_real', '')} | {failed} |")
    if h is not None:
        out += ["", "## Holdout (15 Jul - 19 Oct 2026, read once)", ""]
        if not h.get("read"):
            out.append(h.get("note", "not read"))
        else:
            out += ["| test | trades | win | net | worst day | worst month | follow minus fade | continuation | confirmed |", "|---|---|---|---|---|---|---|---|---|"]
            for r in h["tests"]:
                s = r["holdout"]; f = r["fade"]; c = r["continuation"]
                out.append(f"| {r['id']} | {s.get('n', 0)} | {100 * s.get('win', 0):.0f}% | {rs(s.get('net'))} | {rs(s.get('worst_day'))} | "
                           f"{rs(s.get('worst_month'))} | {rs(f.get('mean_diff'))} ({f.get('p', 1):.3f}) | {100 * c.get('rate', 0):.1f}% ({c.get('n', 0)}) | "
                           f"{'yes' if r['confirmed'] else 'no'} |")
    text = "\n".join(out) + "\n"
    (RESULTS / "report.md").write_text(text)
    return text


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design()
    elif cmd == "holdout":
        holdout()
    elif cmd == "report":
        print(report())
    else:
        raise SystemExit(__doc__)
