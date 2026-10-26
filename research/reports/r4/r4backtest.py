"""R4 report, backtest side: every arm BEFORE (its plain research rules) and AFTER (the changes on paper today), final
period 1 Apr - 13 Oct 2026, 1 lot, after dated charges and slippage.

    R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R101's clean stores (lake to 13 Oct), outside git
    python research/reports/r4/r4backtest.py                    # -> research/reports/r4/results/backtest.json

26 Oct 2026. Reuses the arms' own research code; nothing is re-tuned and no new rule is tested here:
- candidates and pricing: R101 (`r101arms`), R64's held-contract engine, R17 slippage, dated Zerodha charges;
- R51's Supertrend (10, 3) filter: R112's `r51_st_signals` (R73 T7);
- the paper book with the same-direction cap: R108's `r108book` (CAP2 = the app's rule, NOCAP = none).
Signal source of every rule: the index (Donchian / breadth / value area / liquidity pools on the index's bars); the
option is only bought. Locks, stops and the ladder sit on the option premium.

BEFORE / AFTER per arm (dates the change reached paper; see the page for the full list):
- R72 Breadth Donchian: -15% stop, +30% target, 30 min (R71 "XB")  ->  -15%, L25 + 30-60-100 ladder, no target (16 Oct, "XL").
- R51 Donchian 20: plain breaks + XB  ->  Supertrend-agreeing breaks + XL (16 Oct).
- R52 Open outside value: -30% stop, +10% once +40%, 60 min (launch rules)  ->  -30%, L25 + ladder, 60 min (16 Oct).
- Liquidity 15+5: own exits, both sides  ->  the same exits, plus the no-twin rule (21 Oct) and the expiry-day skip
  (26 Oct; the research already skipped expiry days). h14's capped limit (26 Oct) is not modelled.
- Portfolio: the four arms' BEFORE trades summed  ->  R108's paper book with the AFTER arms, no cap and with the cap.
Boss's 25 Oct rules (opposite bets, weekly stop, 2% cap, account stop copy) started 26 Oct and are not modelled.

Only summary numbers are written (results/backtest.json). Trade lists stay outside git ($R4_OUT, default
~/.cache/iraalgo/r4).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from datetime import date, datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
HUNT = HERE.parents[1] / "hunt"
os.environ["R51_CLEAN"] = "1"
for _d in ("r112_donchian_morning", "r108_same_direction_cap", "r106_small_targets", "r101_arms_enhance", "r73"):
    if str(HUNT / _d) not in sys.path:
        sys.path.insert(0, str(HUNT / _d))
sys.path.insert(0, str(HERE))

import numpy as np  # noqa: E402

import r4stats as R  # noqa: E402

OUT = Path(os.environ.get("R4_OUT", Path.home() / ".cache" / "iraalgo" / "r4")).expanduser()
RESULTS = HERE / "results"
FINAL_START, FINAL_END = date(2026, 4, 1), date(2026, 10, 13)
ARMS = ("LIQ", "R72", "R51", "R52")
NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20", "R52": "R52 Open outside value"}
# published final figures the AFTER walk must reproduce (R101 / R73 / R112 / R108), trades and net
PUBLISHED = {"LIQ": (357, 11_155), "R72": (1_202, 171_366), "R51": (1_670, 134_743), "R52": (293, 48_612),
             "R51plain": (1_781, 81_845), "CAP2_R108": (1_384, 15_955), "NOCAP_R108": (2_538, 138_560)}

_M: dict = {}


def mods():
    if not _M:
        import r112study as S112
        M = S112.mods()
        A = M["A"]
        AM = A.mods()
        import r101study as W101
        _M.update(S112=S112, A=A, AM=AM, W101=W101, B=M["B"], S106=M["S"])
    return _M


# ------------------------------------------------------------------------------------------------ exits
def exits(name: str):
    """The Exits object of each rule set (all from the arms' own configs)."""
    M = mods()
    AM = M["AM"]
    E, R51K, R64 = AM["E"], AM["R51"], AM["R64"]
    if name == "XB":                     # R71 XB = R64 OWN["Donchian"]: -15% / +30% / 30 min / 15:15
        return AM["S71"].exits_of("XB")
    if name == "XL":
        return M["A"]._xl()
    if name == "OOV_OWN":                # the launch rules: -30%, +10% once +40% (Solo layer), 60 min, 15:10
        o = R64.OWN["OOV"]
        return E.Exits(stop=o["stop"], steps=tuple(o["steps"]), tmax=o["tmax"], sq_row=R51K.row(*o["sq"]))
    if name == "OOV_L25":
        return M["A"]._oov_l25()
    raise ValueError(name)


def reprice(units: list, ex) -> list:
    """The same candidates priced with other exits (R64's engine), keeping each unit's key, candidates and walk."""
    M = mods()
    G, r51data = M["AM"]["G"], M["AM"]["r51data"]
    out = []
    for key, cands, _res, how in units:
        D = r51data.load(key[0])[key[1]]
        res = G.run_day(key[0], D, [c["side"] for c in cands], [c["strike"] for c in cands], [c["er"] for c in cands], ex)
        out.append((key, cands, res, how))
    return out


def units_of(arm: str, rules: str, lo: date, hi: date) -> list:
    M = mods()
    A, S112 = M["A"], M["S112"]
    if arm == "LIQ":
        return A.liq_units(lo, hi)
    if arm == "R72":
        u = A.r72_units(lo, hi)                       # priced with XL
        return u if rules == "after" else reprice(u, exits("XB"))
    if arm == "R51":
        if rules == "after":
            return A._side2_units(S112.r51_st_signals(), lo, hi, exits("XL"))
        return reprice(A.r51_units(lo, hi), exits("XB"))
    if arm == "R51plain":
        return A.r51_units(lo, hi)
    if arm == "R52":
        u = A.r52_units(lo, hi)                       # priced with OOV + L25 + ladder
        return u if rules == "after" else reprice(u, exits("OOV_OWN"))
    raise ValueError(arm)


# ------------------------------------------------------------------------------------------------ walks
def walk_units(units: list) -> list:
    """Each arm's own taking rule per unit (R101): one book at a time, 2 a side a day, or one a day."""
    W = mods()["W101"]
    fn = {"book": W.walk_book, "side2": W.walk_side2, "one": W.walk_one}
    out = []
    for key, cands, res, how in units:
        tr = fn[how](cands, res)
        if how == "one":
            tr = [tr] if isinstance(tr, dict) else (tr or [])
        for t in tr:
            t = dict(t)
            t["day"] = R.as_date(t["day"])
            t["und"] = key[0]
            if how == "book":
                t["book"] = key[2]
            out.append(t)
    out.sort(key=lambda t: (t["day"], t["er"], t["und"]))
    return out


def add_peak(trades: list) -> list:
    """Liquidity's engine (R51's) returns no 'peak' / 'ret'; add them as R64 does: the held contract's highest high from
    the fill minute to 15:15 over the raw fill, - 1 (the big-mover test), and the raw exit over the raw fill, - 1."""
    M = mods()
    r51data, R51K = M["AM"]["r51data"], M["AM"]["R51"]
    last = R51K.row(15, 15)
    for t in trades:
        if "peak" in t and "ret" in t:
            continue
        t["ret"] = t["X"] / t["E"] - 1.0
        C = r51data.load(t["und"])[t["day"]].contract(t["side"], t["strike"])
        hi = C[1, t["er"]:last + 1] if C is not None else np.zeros(0)
        hi = hi[np.isfinite(hi)]
        t["peak"] = max(float(hi.max()), t["E"]) / t["E"] - 1.0 if len(hi) else float("nan")
    return trades


def no_twin(trades: list) -> tuple:
    """Liquidity's no-twin rule (paper since 21 Oct): an entry on an option another Liquidity book already holds open is
    not taken (it becomes a would-be trade). Trades in entry order; returns (kept, twins)."""
    kept, twins = [], []
    # inside a minute the longer-timeframe book decides first (the app's order, r108book.LIQ_BOOK_ORDER)
    for t in sorted(trades, key=lambda t: (t["day"], t["er"], -int(t.get("book") or 0))):
        held = any(k["day"] == t["day"] and k["und"] == t["und"] and k["side"] == t["side"] and k["strike"] == t["strike"]
                   and k["er"] <= t["er"] <= k["xr"] for k in kept)
        (twins if held else kept).append(t)
    return kept, twins


# ------------------------------------------------------------------------------------------------ the book (R108)
def book(units_by_arm: dict, sessions: list, rule: str) -> list:
    M = mods()
    B = M["B"]
    import r108study as S108
    dc = S108.candidates({a: units_by_arm[a] for a in S108.K.ARMS})
    w = B.walk_period(sessions, dc, B.make_rule(rule))
    tr = B.trades_of(w)
    for t in tr:
        t["day"] = R.as_date(t["day"])
    return tr


def sessions_final() -> list:
    return [d for d in mods()["S106"].sessions_in(FINAL_START, FINAL_END)]


# ------------------------------------------------------------------------------------------------ run
def check(name: str, trades: list):
    n, net = len(trades), round(sum(t["net"] for t in trades))
    pub = PUBLISHED[name]
    ok = n == pub[0] and abs(net - pub[1]) <= 1
    print(f"parity {name}: {n} / {net} (published {pub[0]} / {pub[1]})", "OK" if ok else "MISMATCH", flush=True)
    assert ok, (name, n, net, pub)


def run() -> dict:
    lo, hi = FINAL_START, FINAL_END
    # R51's guard keeps the final window shut outside a final read. R4 chooses nothing and tests no rule: it only
    # describes the window that R51-R112 already read, so it opens the guard as R101 / R108 do in their final runs.
    mods()["AM"]["C"].open_final()
    sess = sessions_final()
    units = {"before": {}, "after": {}}
    trades = {"before": {}, "after": {}}
    for arm in ARMS:
        for rules in ("before", "after"):
            u = units_of(arm, rules, lo, hi)
            units[rules][arm] = u
            trades[rules][arm] = add_peak(walk_units(u))
        print(arm, "before", len(trades["before"][arm]), "after", len(trades["after"][arm]), flush=True)
    for arm in ARMS:
        check(arm, trades["after"][arm])
    # Liquidity: expiry-day skip (a research rule already) and the no-twin rule
    liq_exp = [t for t in trades["after"]["LIQ"] if mods()["AM"]["r51data"].load(t["und"])[t["day"]].expiry]
    liq_kept, liq_twins = no_twin(trades["after"]["LIQ"])
    # the book: R108 parity first (plain R51 with XL), then today's arms
    r51plain = units_of("R51plain", "after", lo, hi)
    check("R51plain", walk_units(r51plain))
    # R108's book reads every arm's units from the design window too (STOPS / EDGE history); CAP2 / NOCAP only need
    # the final days, so the final units alone are walked here.
    u108 = dict(units["after"]); u108["R51"] = r51plain
    check("CAP2_R108", book(u108, sess, "CAP2"))
    check("NOCAP_R108", book(u108, sess, "NOCAP"))
    after_nocap = book(units["after"], sess, "NOCAP")
    after_cap = book(units["after"], sess, "CAP2")
    before_all = [t for a in ARMS for t in trades["before"][a]]
    after_alone = [t for a in ARMS for t in trades["after"][a]]
    # Liquidity's trades in the book carry no peak: take it from the same priced trade of the arm alone
    ref = {(t["day"], t["und"], t["side"], float(t["strike"]), int(t["er"])): t for t in after_alone}
    for tr in (after_nocap, after_cap):
        for t in tr:
            src = ref.get((t["day"], t["und"], t["side"], float(t["strike"]), int(t["er"])))
            if src is not None:
                t.update(peak=src["peak"], ret=src["ret"], E=src["E"], qty=src["qty"])

    rows = {}
    for arm in ARMS:
        rows[arm] = {"before": R.stats(trades["before"][arm], sess), "after": R.stats(trades["after"][arm], sess)}
    for arm in ARMS:
        rows[arm]["paired"] = R.paired(trades["before"][arm], trades["after"][arm])
    rows["LIQ"]["after_notwin"] = R.stats(liq_kept, sess)
    rows["PORT"] = {
        "before": R.stats(before_all, sess),
        "after_alone": R.stats(after_alone, sess),
        "after_nocap": R.stats(after_nocap, sess),
        "after": R.stats(after_cap, sess),
    }
    for k, tr in (("before", before_all), ("after_nocap", after_nocap), ("after", after_cap)):
        rows["PORT"][k]["bad_months"] = R.month_breaches(tr, sess)
        rows["PORT"][k]["months"] = len({(d.year, d.month) for d in sess})
    # the cap's refusals of big winners: AFTER-alone big winners that the capped book did not take
    taken = {(t["day"], t["und"], t["side"], float(t["strike"]), int(t["er"])) for t in after_cap}
    big = [t for t in after_alone if t["net"] >= 3_000]
    refused = [t for t in big if (t["day"], t["und"], t["side"], float(t["strike"]), int(t["er"])) not in taken]
    rows["PORT"]["after"]["cap_refused_big"] = dict(big=len(big), refused=len(refused),
                                                    rupees=round(sum(t["net"] for t in refused)))
    # paired change per arm: the same signals, before vs after exits (both walks re-run, so a slot freed early may
    # take a later signal: that is counted as it happens)
    out = dict(
        generated=datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC"),
        period=dict(start=str(lo), end=str(hi), sessions=len(sess), months=len({(d.year, d.month) for d in sess})),
        rows=rows,
        liquidity=dict(expiry_trades_in_research=len(liq_exp), twins=len(liq_twins),
                       twins_net=round(sum(t["net"] for t in liq_twins))),
        pools=dict({a: [round(float(t["net"]), 2) for t in trades["after"][a]] for a in ARMS},
                   PORT=[round(float(t["net"]), 2) for t in after_cap]),
        pools_ret={a: [round(float(t["ret"]), 5) for t in trades["after"][a]] for a in ARMS},
        parity={k: list(v) for k, v in PUBLISHED.items()},
    )
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "trades.pkl").write_bytes(pickle.dumps(dict(trades=trades, after_cap=after_cap, after_nocap=after_nocap)))
    return out


def main():
    out = run()
    pools = {k: out.pop(k) for k in ("pools", "pools_ret")}
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "backtest.json").write_text(json.dumps(out, indent=1, default=str) + "\n")
    # the bootstrap pools (backtest trades' nets and returns, no paper data) stay outside git
    (OUT / "pools.json").write_text(json.dumps(pools))
    print("wrote", RESULTS / "backtest.json")


if __name__ == "__main__":
    main()
