"""R193 study (PREREG.md): R72, R51 and Liquidity 15+5 from 1 Nov 2024 to 13 Nov 2026 - the arms' own exits (A) against
R151's picker, V4 (picker + breakeven at +10%), V5 (V4 + R186's step lock; Liquidity: breakeven at +Rs 1,000) and
V5 + FB-R72-A10.

    python3 -I research/hunt/r193_arms_nov24/r193study.py repro     # R186's year and R190's era C first -> results/repro.json
    python3 -I research/hunt/r193_arms_nov24/r193study.py span      # read once -> results/span.json
    python3 -I research/hunt/r193_arms_nov24/r193study.py decide    # the gate -> results/decision.json

Reuses R186's engine (r186engine.price, forced_nets), R179's pricing of the arms' own exits, R176's units / walk and R152's
picker module (R151's frozen app numbers), unchanged. Pure parts in r193lib.py. Plain-English notes, 17 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r193cfg as K  # noqa: E402

K.set_env()
sys.path.insert(0, str(K.HUNT / "r186_fixed_profit"))
import numpy as np  # noqa: E402

import r186study as Z6  # noqa: E402  (R179 -> R177 -> R176 -> R152 -> R112 -> R106 -> R101 -> R51)
import r193lib as G  # noqa: E402

R9, R7, S6 = Z6.R9, Z6.R7, Z6.S6
LIQ_UNDS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
_A10: dict = {}


def boot() -> dict:
    """The stack's modules, with R51's window set to R190's stores (warm-up 2020, last day 13 Nov 2026)."""
    M = R7.mods()
    k = M["r51data"].K
    k.WARM_START, k.START, k.END = K.STORE
    M["C"].open_final()
    return M


# ================================================================================================ pieces
def key_c(und, day, c) -> tuple:
    return (und, S6._d(day), int(c["er"]), c["side"])


def key_t(t) -> tuple:
    return (t["und"], S6._d(t["day"]), int(t["er_sig"]), t["side"])


def units(arm: str, lo: date, hi: date) -> tuple:
    """(raw units, units with R177's A rule applied (adds 'fx_arm'))."""
    us0 = S6.units(arm, lo, hi)
    usA, _ = R7.apply_rule(arm, us0, None)
    return us0, usA


def price(arm: str, us: list, be: bool, lock: str, lots: int) -> list:
    """A (no breakeven, no lock) = R179's pricing of the arm's own exits; otherwise R186's price (breakeven at +10%,
    lock 'L500' / 'LBE' / '')."""
    if not be and not lock:
        return R9.priced(arm, us, "A", False, lots)
    assert be, "every R193 lock sits on top of the breakeven"
    return Z6.priced(arm, us, lots, -1.0, lock)


def walk(arm: str, us: list, pr: list, lo: date, hi: date) -> list:
    tr = [t for t in S6.walk(arm, us, pr) if lo <= t["day"] <= hi]
    if arm == "LIQ":
        tr = [t for t in tr if t["und"] in LIQ_UNDS]
    return tr


def lookup(us: list, pr: list) -> dict:
    return {key_c(key[0], key[1], c): t for (key, cands, _, _), row in zip(us, pr) for c, t in zip(cands, row)
            if t is not None}


def a10_map(und: str) -> dict:
    """{day: A10} for one index from the store's index minutes (r193lib.a10_series = R140a's feature)."""
    if und not in _A10:
        days = R7.mods()["r51data"].load(und)
        ks = sorted(days)
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            hi = np.array([np.nanmax(days[d].idx[1]) if np.isfinite(days[d].idx[1]).any() else np.nan for d in ks])
            lo = np.array([np.nanmin(days[d].idx[2]) if np.isfinite(days[d].idx[2]).any() else np.nan for d in ks])
        _A10[und] = dict(zip([S6._d(d) for d in ks], G.a10_series(hi, lo)))
    return _A10[und]


def a10_keep(und: str, day) -> bool:
    return not G.a10_skip(a10_map(und).get(S6._d(day), np.nan))


def prefiltered(us: list, keep) -> list:
    """Candidates removed BEFORE the walk (R115's `filtered`, as R140a / R190 applied A10)."""
    out = []
    for key, cands, res, how in us:
        idx = [j for j, c in enumerate(cands) if keep(key, c)]
        if idx:
            out.append((key, [cands[j] for j in idx], [res[j] for j in idx], how))
    return out


def summ(tr: list) -> dict:
    return dict(trades=len(tr), net=round(sum(t["net"] for t in tr)))


# ================================================================================================ reproduction (1a)
def repro() -> dict:
    boot()
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(),
                         stores="R190's R51 clean stores (3 Aug 2020 - 13 Nov 2026), R190's lake copy")}
    lo, hi = K.R186_YEAR
    for arm in K.ARMS:
        us0, usA = units(arm, lo, hi)
        unds = sorted({k[0] for k, _, _, _ in usA})
        P = sc = None
        if arm in K.PICK_THR:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
            assert abs(thr - K.PICK_THR[arm]) < 1e-12
        keep = (lambda tr: tr) if P is None else (lambda tr: [t for t in tr if P.kept(t, sc, thr)])
        # R186's own method: no index filter on Liquidity (its units' indices are listed)
        wk = lambda pr: [t for t in S6.walk(arm, usA, pr) if lo <= t["day"] <= hi]  # noqa: E731
        rows = {"A": wk(price(arm, usA, False, "", 1)), "BASE": keep(wk(price(arm, usA, True, "", 1)))}
        if arm == "LIQ":
            rows["LBE1000"] = wk(price(arm, usA, True, "LBE", 1))
        else:
            rows["L500"] = keep(wk(price(arm, usA, True, "L500", 1)))
        for v, tr in rows.items():
            s = G.stats(tr, lo, hi)
            pub = K.R186_PUB[(v, arm)]
            r = dict(trades=s["trades"], net=s["net"], win=s["win"], published=list(pub),
                     gap_trades=s["trades"] - pub[0], gap_net=s["net"] - pub[1])
            r["reproduced"] = r["gap_trades"] == 0 and abs(r["gap_net"]) <= 1
            out[f"R186 {v} {arm}"] = r
            print("R186", v, arm, r, flush=True)
        out[f"units {arm}"] = unds
    # R190 era C: R72 own exits (D1), A10 skipped before the walk (D3), Liquidity (L1)
    lo, hi = K.SPAN
    us0, usA = units("R72", lo, hi)
    prA = price("R72", usA, False, "", 1)
    d1 = walk("R72", usA, prA, lo, hi)
    lk = lookup(usA, prA)
    usF = prefiltered(usA, lambda key, c: a10_keep(key[0], key[1]))
    prF = [[lk.get(key_c(key[0], key[1], c)) for c in cands] for key, cands, _, _ in usF]
    d3 = walk("R72", usF, prF, lo, hi)
    _, uL = units("LIQ", lo, hi)
    l1 = walk("LIQ", uL, price("LIQ", uL, False, "", 1), lo, hi)
    for name, tr in (("D1", d1), ("D3", d3), ("L1", l1)):
        s = summ(tr)
        pub = K.R190_PUB[name]
        r = dict(s, published=list(pub), gap_trades=s["trades"] - pub[0], gap_net=s["net"] - pub[1])
        r["reproduced"] = r["gap_trades"] == 0 and abs(r["gap_net"]) <= 1
        out[f"R190 {name}"] = r
        print("R190", name, r, flush=True)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "repro.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ the span (read once)
def twins_for(arm: str, walked: list, keep: np.ndarray, plainA: dict, exit_changed: bool, rng) -> tuple:
    """PREREG 6: twin totals (K.TWINS,) on the variant's own walk. Exit part: a trade the variant closes >= 2 minutes
    before A would is sold at a random minute between its fill and A's exit (A's exits active before it). Filter part:
    as many trades as the filters removed are removed at random."""
    import r186engine as Z
    M = R7.mods()
    ex = S6.exits(arm)
    n = len(walked)
    nets = np.repeat(np.array([t["net"] for t in walked], float)[:, None], K.TWINS, axis=1)
    changed = 0
    if exit_changed:
        for i, t in enumerate(walked):
            p = plainA.get(key_t(t))
            if p is None or not (t["xr"] < p["xr"] and p["xr"] - t["er"] >= 2):
                continue
            changed += 1
            rows = M["S"].twin_rows(t["er"], p["xr"], K.TWINS, rng)
            D = M["r51data"].load(t["und"])[t["day"]]
            nets[i] = Z.forced_nets(t["und"], D, t, ex, rows, 1, int(t.get("fx_arm", -1)), be_x=-1.0, lock="")
    k = int(n - keep.sum())
    tot = np.empty(K.TWINS)
    for j in range(K.TWINS):
        m = G.random_skip_mask(n, k, rng)
        tot[j] = float(nets[m, j].sum())
    return tot, changed, k


def funded(trades: list, by_lots: dict) -> list:
    """PREREG 4: each trade at the lots Rs 56,000 pays for (cap 3; 0 = skipped), priced at that many lots."""
    out = []
    for t in trades:
        n = G.funded_lots(float(t["E"]), float(t["qty"]) / float(t.get("lots", 1) or 1))
        if n == 0:
            continue
        x = by_lots[n].get(key_t(t))
        if x is not None:
            out.append(dict(x, day=S6._d(x["day"]), funded_lots=n))
    return out


def span() -> dict:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    if K.SPAN_FLAG.exists() and (K.RESULTS / "span.json").exists():
        raise SystemExit("R193 span already read once: " + K.SPAN_FLAG.read_text())
    if not (K.RESULTS / "repro.json").exists():
        raise SystemExit("reproduction first (PREREG 1a)")
    if not K.SPAN_FLAG.exists():
        K.SPAN_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    boot()
    lo, hi = K.SPAN
    rng = np.random.default_rng(K.SEED)
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), span=f"{lo} .. {hi}", twins=K.TWINS,
                         seed=K.SEED, m_total=K.M_TOTAL)}
    trades = {}
    for arm in K.ARMS:
        us0, usA = units(arm, lo, hi)
        sc = thr = P = None
        if arm in K.PICK_THR:
            P = S6.picker()
            sc, thr = P.scores(arm, us0)
            assert abs(thr - K.PICK_THR[arm]) < 1e-12
        pricings = {("A", ""): False}
        for v, (a, be, lock, _, _) in K.VARIANTS.items():
            if a == arm and be:
                pricings[("BE", lock)] = True
        priced = {}
        for (tag, lock), be in pricings.items():
            for lots in (1, 2, 3):
                priced[(tag, lock, lots)] = price(arm, usA, be, lock, lots)
                print(arm, tag, lock, lots, "priced", flush=True)
        plainA = lookup(usA, priced[("A", "", 1)])
        base = walk(arm, usA, priced[("A", "", 1)], lo, hi)
        lkA = {n: lookup(usA, priced[("A", "", n)]) for n in (1, 2, 3)}
        trades["A:" + arm] = base
        out["A:" + arm] = dict(windows=G.by_windows(base), funded=G.by_windows(funded(base, lkA)))
        print(arm, "A", out["A:" + arm]["windows"]["total"]["net"], flush=True)
        for v, (a, be, lock, pick, a10) in K.VARIANTS.items():
            if a != arm:
                continue
            tag = "BE" if be else "A"
            w = walk(arm, usA, priced[(tag, lock, 1)], lo, hi)
            kp = np.array([P.kept(t, sc, thr) if pick else True for t in w], bool)
            ka = np.array([a10_keep(t["und"], t["day"]) if a10 else True for t in w], bool)
            keep = kp & ka
            tr = [t for t, k_ in zip(w, keep) if k_]
            trades[v] = tr
            lk = {n: lookup(usA, priced[(tag, lock, n)]) for n in (1, 2, 3)}
            tw, ch, k = twins_for(arm, w, keep, plainA, be, rng)
            act = float(sum(t["net"] for t in tr))
            rem = {wn: dict(picker=int(sum(1 for t, x in zip(w, kp) if not x and lo_ <= t["day"] <= hi_)),
                            a10=int(sum(1 for t, x, y in zip(w, kp, ka) if x and not y and lo_ <= t["day"] <= hi_)))
                   for wn, (lo_, hi_) in K.WINDOWS.items()}
            out[v] = dict(arm=arm, windows=G.by_windows(tr), funded=G.by_windows(funded(tr, lk)), removed=rem,
                          twin=dict(p=round(G.twin_p(act, tw), 5), p_normal=G.normal_p(act, tw), mean=round(float(tw.mean())),
                                    sd=round(float(tw.std(ddof=1))), changed=ch, removed=k, actual=round(act)))
            print(v, out[v]["windows"]["total"]["net"], out[v]["twin"], flush=True)
    (K.OUT / "r193_trades.pkl").write_bytes(pickle.dumps(trades, protocol=pickle.HIGHEST_PROTOCOL))
    (K.RESULTS / "span.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    r = json.loads((K.RESULTS / "span.json").read_text())
    ps = [r[v]["twin"]["p_normal"] for v in K.TESTS]
    qs = G.bh_total(ps, K.M_TOTAL)
    out = {"m_total": K.M_TOTAL, "variants": {}}
    for v, q in zip(K.TESTS, qs):
        arm = K.VARIANTS[v][0]
        g = G.gate(r[v]["windows"], r["A:" + arm]["windows"], r[v]["twin"]["p"], q)
        g["p_normal"] = r[v]["twin"]["p_normal"]
        g["account_stop"] = {w: r[v]["windows"][w]["account_stop_ok"] for w in K.WINDOWS}
        out["variants"][v] = g
    out["helps"] = {a: [v for v in K.TESTS if K.VARIANTS[v][0] == a and out["variants"][v]["helps"]] for a in K.ARMS}
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    {"repro": repro, "span": span, "decide": decide}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
