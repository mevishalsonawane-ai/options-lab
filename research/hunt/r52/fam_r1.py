"""R52 family R1: the R1 internet ideas (research/HUNT_R1_INTERNET.md, code research/hunt/r1/) re-tested on Nov 2024+.

Written 15 Oct 2026 for R52 (PREREG.md, FAMILY_BRIEF.md). Signal source: the index itself (R1-9 adds yesterday's India
VIX close). Nothing here reads an option's own price chart.

How the original code is reused
    The idea functions are research/hunt/r1/signals.py itself (n1_noise, n2_wvb, n4_rod, n6_fvg, n7_turtle, n8_ibs,
    n9_vixspk, n14_lunchrev), imported unchanged. They take R1's index "panel" P; `panel()` below is a port of
    research/hunt/r1/lib.py `load_index` (+ the VIX close part of `vix_minutes`) that reads the `mk` handed in instead of
    obuy's global market, so the synthetic markets of the tests work too. R1's request rows (var, di, s, side, x, liq)
    are turned into obuy signal rows: sig_min = 09:15 + s (R1 decided on the close of column s and bought the open of
    s+1, as obuy does), side 0 (CE) -> +1, 1 (PE) -> -1.
    Index stops / targets: R1 computed them with signals.index_exit(stop=..., tgt=...). While an idea runs, that
    function is swapped for a recorder that keeps the (stop, target) LEVELS, which become idx_stop / idx_target (obuy
    checks them on the index bar's low/high and leaves at the next open, as R1 did; obuy's stop is strictly below the
    level where R1 used <=). The N1 trailing exits (trail30 / trail1: back inside max(band, TWAP)) are not levels, so
    R1's own exit column becomes exit_at (09:15 + x). R1's other fixed time exits: 60m = exit_at 10:16, 1330 = 13:30.

R1's frame kept: 1-ITM strike of the nearest expiry (StrikeRule(1, 'near')), one trade a day per variant and index,
expiry days NOT skipped (R1 traded every session; N4's 'expiry' variant trades only expiry days), square-off 15:10,
EOD = hold to 15:10, LIQ = the app's Liquidity exit (-15% premium stop, out after 20 minutes unless the premium closes
>= +5%, else 15:10) = Exits(stop_pct=0.15, time_stop=20, time_gain=0.05). Random-twin windows = R1's own lo..hi
entry columns.

ORIGINAL settings = R1's frozen holdout picks (research/HUNT_R1_INTERNET.md results table; picked per idea PER INDEX
by research/hunt/r1/design.py, `frozen.json`). Where the two indices' picks differ only in index-side settings, one
rule carries both (per-index params). Where they need different PREMIUM exits (R1-2, R1-6, R1-8: one index EOD, the
other LIQ or 60m), one obuy Rule cannot hold both, so those keys are split by index: "R1-2@NIFTY", "R1-2@BANKNIFTY",
etc. (each its own original + R1's grid for that index).
GRID = R1's own pre-registered variants of the idea (research/hunt/r1/PREREG.md), the same setting on both indices.

LEFT
    none of the eight keys asked for. R1-4's merged twin OBUY_GA-6 (obuy ga_td02_gao, TD-02 Gao) is an obuy strategy
    with its own 96-setting grid; it is not part of R1's code and is not re-tested here.
Caveat (as R1): a session is eligible only if its index data is real and < 20% of 09:15-15:00 minutes are missing
(lib.load_index `ok`). That data filter reads the whole day; it can drop a broken day, never choose a side or minute.
"""
from __future__ import annotations

import importlib.util
import sys
import warnings
from contextlib import contextmanager
from pathlib import Path

import numpy as np
import pandas as pd

OPEN_M = 9 * 60 + 15
W = 375
NB = ("NIFTY", "BANKNIFTY")
R1_DIR = Path(__file__).resolve().parents[1] / "r1"
COLS = ["und", "day", "sig_min", "side", "book", "idx_stop", "idx_target", "exit_at"]


def _load_r1():
    """Import research/hunt/r1/lib.py and signals.py under private names (their own names 'lib' / 'signals' are too
    generic to leave in sys.modules next to other families)."""
    if "r52_r1_signals" in sys.modules:
        return sys.modules["r52_r1_signals"]
    research = str(R1_DIR.parents[1])
    if research not in sys.path:
        sys.path.insert(0, research)
    saved = sys.modules.get("lib")
    spec = importlib.util.spec_from_file_location("r52_r1_lib", R1_DIR / "lib.py")
    lib = importlib.util.module_from_spec(spec)
    sys.modules["r52_r1_lib"] = lib
    spec.loader.exec_module(lib)
    sys.modules["lib"] = lib                        # signals.py does `import lib`
    try:
        spec = importlib.util.spec_from_file_location("r52_r1_signals", R1_DIR / "signals.py")
        sig = importlib.util.module_from_spec(spec)
        sys.modules["r52_r1_signals"] = sig
        spec.loader.exec_module(sig)
    finally:
        if saved is None:
            sys.modules.pop("lib", None)
        else:
            sys.modules["lib"] = saved
    return sig


S = _load_r1()
L = S.L


# ------------------------------------------------------------------------------------------------ R1 panel
def panel(mk, u):
    """Port of research/hunt/r1/lib.py load_index (15 Oct 2026): same keys, built from mk.index(u)."""
    ix = mk.index(u)
    days = list(ix.days)
    nd = len(days)
    if nd == 0:
        return None
    M = ix.mat()
    dl = ix.daily()
    real = np.array([bool(ix.d[d].get("real", True)) for d in days])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        ok = real & (np.isnan(M["c"][:, :345]).mean(1) < 0.2)
    c = pd.DataFrame(M["c"]).T.ffill().T.values          # forward-filled closes within the day
    P = dict(u=u, days=days, nd=nd, o=M["o"], h=M["h"], l=M["l"], c=c, ok=ok,
             exp=np.array([bool(ix.d[d]["exp"]) for d in days]),
             dopen=dl["open"].values.astype(float), dhigh=dl["high"].values.astype(float),
             dlow=dl["low"].values.astype(float), dclose=dl["close"].values.astype(float))
    P["pclose"] = np.r_[np.nan, P["dclose"][:-1]]
    P["phigh"] = np.r_[np.nan, P["dhigh"][:-1]]
    P["plow"] = np.r_[np.nan, P["dlow"][:-1]]
    return P


def vix_close(mk, days):
    """lib.vix_minutes' daily part: India VIX close per index day (NaN when missing)."""
    dc = mk.vix.daily.close
    return np.array([float(dc.get(d, np.nan)) for d in days])


@contextmanager
def _levels():
    """Swap signals.index_exit for a recorder: returns a code (<= -1000) that maps to the (stop, target) levels."""
    store = []
    orig = S.index_exit

    def rec(P, di, s, stop=None, tgt=None, side=0, until=None):
        store.append((np.nan if stop is None else float(stop), np.nan if tgt is None else float(tgt)))
        return -1000 - (len(store) - 1)

    S.index_exit = rec
    try:
        yield store
    finally:
        S.index_exit = orig


def _per(v, u):
    return v[u] if isinstance(v, dict) else v


def _requests(mk, u, idea):
    """Run one R1 idea on index u -> (P, R1 request frame, levels)."""
    P = panel(mk, u)
    if P is None:
        return None, pd.DataFrame(), []
    days_ok = np.nonzero(P["ok"])[0]
    R = S.Req()
    with warnings.catch_warnings(), _levels() as lv:
        warnings.simplefilter("ignore", RuntimeWarning)
        fn = getattr(S, idea)
        if idea == "n6_fvg":
            fn(P, R, days_ok, L.bars5(P))
        elif idea == "n9_vixspk":
            fn(P, R, days_ok, vix_close(mk, P["days"]))
        else:
            fn(P, R, days_ok)
    return P, R.frame(), list(lv)


def _rows(P, req, lv, var, book):
    """R1 requests of one variant -> obuy signal rows."""
    g = req[req["var"] == var]
    out = []
    for r in g.itertuples(index=False):
        st = tg = xa = np.nan
        x = int(r.x)
        if x <= -1000:
            st, tg = lv[-1000 - x]
        elif x >= 0:
            xa = OPEN_M + x
        out.append(dict(und=P["u"], day=P["days"][int(r.di)], sig_min=OPEN_M + int(r.s), side=1 if r.side == 0 else -1,
                        book=book, idx_stop=st, idx_target=tg, exit_at=xa))
    return out


def _frame(rows):
    if not rows:
        return pd.DataFrame(columns=COLS)
    df = pd.DataFrame(rows, columns=COLS)
    return df.sort_values(["day", "und", "sig_min"], kind="stable").reset_index(drop=True)


def _run(mk, idea, unds, varf, book):
    rows = []
    for u in unds:
        P, req, lv = _requests(mk, u, idea)
        if P is None or not len(req):
            continue
        rows += _rows(P, req, lv, varf(u), f"{book}_{u}")
    return _frame(rows)


# ------------------------------------------------------------------------------------------------ adapters
def r1_noise(mk, m=1.0, exit="EOD", unds=NB):
    """R1-1 N1 noise area. m in {1.0, 1.5}; exit in {trail30, trail1, EOD, LIQ} (LIQ also needs Exits LIQ)."""
    return _run(mk, "n1_noise", unds, lambda u: f"N1_NOISE|m{float(_per(m, u))}|{_per(exit, u)}", "r1n1")


def r1_wvb(mk, k=0.75, exit="EOD", unds=NB):
    """R1-2 N2 Williams breakout. k in {0.25, 0.5, 0.75}; exit native (index back to the open) / EOD / LIQ."""
    return _run(mk, "n2_wvb", unds, lambda u: f"N2_WVB|k{_per(k, u)}|{_per(exit, u)}", "r1n2")


def r1_rod(mk, T="1440", base="pc", thr=0.0, expiry_only=True, unds=NB):
    """R1-4 N4 rest-of-day -> 15:10. expiry_only: R1's 'N4_ROD|1440|pc|expiry' (14:40, from prev close, thr 0)."""
    if expiry_only:
        return _run(mk, "n4_rod", unds, lambda u: "N4_ROD|1440|pc|expiry", "r1n4")
    return _run(mk, "n4_rod", unds, lambda u: f"N4_ROD|{T}|{base}|thr{thr}", "r1n4")


def r1_fvg(mk, T0="0930", g=0.0005, exit="EOD", unds=NB):
    """R1-6 N6 first fair value gap. T0 0930/1000, g 0/0.0005; exit native (stop bar 1, 2R) / EOD (stop) / LIQ."""
    return _run(mk, "n6_fvg", unds, lambda u: f"N6_FVG|{_per(T0, u)}|g{_per(g, u)}|{_per(exit, u)}", "r1n6")


def r1_turtle(mk, B=5, exit="EOD", unds=NB):
    """R1-7 N7 turtle soup on PDL/PDH. B 5/15 min; exit native (session-extreme stop, 2R) / EOD (stop) / LIQ."""
    return _run(mk, "n7_turtle", unds, lambda u: f"N7_TURTLE|B{_per(B, u)}|{_per(exit, u)}", "r1n7")


def r1_ibs(mk, lo=0.2, exit="60m", unds=NB):
    """R1-8 N8 IBS at 09:16. lo 0.2 (hi 0.8) / 0.1 (hi 0.9); exit EOD / 60m / LIQ."""
    return _run(mk, "n8_ibs", unds, lambda u: f"N8_IBS|{_per(lo, u)}|{_per(exit, u)}", "r1n8")


def r1_vixspk(mk, trig="chg15", T="0916", exit="EOD", unds=NB):
    """R1-9 N9 India VIX spike -> CE. trig chg10 / chg15 / p90; T 0916 / 0945; exit EOD / LIQ."""
    return _run(mk, "n9_vixspk", unds, lambda u: f"N9_VIXSPK|{trig}|{T}|{exit}", "r1n9")


def r1_lunchrev(mk, thr=0.006, exit="LIQ", unds=NB):
    """R1-14 N14 midday reversal at 12:00. thr 0.003 / 0.006; exit 1330 / EOD / LIQ."""
    return _run(mk, "n14_lunchrev", unds, lambda u: f"N14_LUNCHREV|{thr}|{exit}", "r1n14")


# ------------------------------------------------------------------------------------------------ specs
EOD = dict()                                                 # square-off 15:10 (Exits default)
LIQ = dict(stop_pct=0.15, time_stop=20, time_gain=0.05)
POS = dict(one_at_a_time=True, max_per_day=1)
ITM1 = 1


def _win(lo, hi):
    """R1's random-baseline entry columns lo..hi -> clock minutes."""
    return (OPEN_M + lo, OPEN_M + hi)


def _ex(name):
    return LIQ if name == "LIQ" else EOD


def _orig(fn, params, exits, window, unds, expiry="allow"):
    return dict(fn=fn, params={**params, "unds": tuple(unds)}, money=ITM1, series="near", unds=tuple(unds),
                exits=exits, expiry=expiry, pos=POS, window=window)


def _grid(orig_key, combos):
    """combos: list of (params, exits name, extra overrides); the original combination is left out."""
    out = []
    for params, ex, extra in combos:
        if (tuple(sorted(params.items())), ex) == orig_key:
            continue
        out.append(dict(params=params, exits=_ex(ex), **extra))
    return out


WHY = "original = R1's frozen holdout pick per index (HUNT_R1_INTERNET.md results table; hunt/r1/design.py frozen.json)"
SRC = "index"

# R1-1 noise area: NIFTY m1.0 EOD, BANKNIFTY m1.5 trail1 (index-side settings only -> one rule)
_g1 = [(dict(m=m, exit=e), "LIQ" if e == "LIQ" else "EOD", {}) for m in (1.0, 1.5) for e in ("trail30", "trail1", "EOD", "LIQ")]
SPEC_R1_1 = dict(
    key="R1-1", title="Noise-area intraday momentum (R1 N1)", source=SRC, why=WHY + ": NIFTY m1.0 EOD, BN m1.5 trail1",
    orig=_orig(r1_noise, dict(m={"NIFTY": 1.0, "BANKNIFTY": 1.5}, exit={"NIFTY": "EOD", "BANKNIFTY": "trail1"}), EOD,
               _win(44, 314), NB),
    grid=_grid(None, _g1))


def _wvb(u, k, ex):
    combos = [(dict(k=kk, exit=e), e, {}) for kk in (0.25, 0.5, 0.75) for e in ("native", "EOD", "LIQ")]
    return dict(key=f"R1-2@{u}", title=f"Larry Williams volatility breakout (R1 N2, {u})", source=SRC,
                why=WHY + f": {u} k{k} {ex} (the two indices' picks need different premium exits, so split by index)",
                orig=_orig(r1_wvb, dict(k=k, exit=ex), _ex(ex), _win(0, 314), (u,)),
                grid=_grid((tuple(sorted(dict(k=k, exit=ex).items())), ex), combos))


# R1-4: both indices N4_ROD|1440|pc|expiry (expiry days only); grid = R1's 8 every-day variants
_S4 = {"1430": 14 * 60 + 30 - OPEN_M - 1, "1440": 14 * 60 + 40 - OPEN_M - 1}
_g4 = [(dict(T=T, base=b, thr=t, expiry_only=False), "EOD", dict(expiry="allow", window=_win(_S4[T], _S4[T])))
       for T in ("1430", "1440") for b in ("pc", "op") for t in (0.0, 0.005)]
SPEC_R1_4 = dict(
    key="R1-4", title="Rest-of-day -> last 30 min momentum (R1 N4)", source=SRC,
    why=WHY + ": both N4_ROD|1440|pc|expiry (14:40 from prev close, thr 0, expiry days only)",
    orig=_orig(r1_rod, dict(T="1440", base="pc", thr=0.0, expiry_only=True), EOD, _win(_S4["1440"], _S4["1440"]), NB,
               expiry="only"),
    grid=_grid(None, _g4))


def _fvg(u, T0, g, ex):
    combos = [(dict(T0=t, g=gg, exit=e), e, {}) for t in ("0930", "1000") for gg in (0.0, 0.0005)
              for e in ("native", "EOD", "LIQ")]
    p = dict(T0=T0, g=g, exit=ex)
    return dict(key=f"R1-6@{u}", title=f"ICT first fair value gap (R1 N6, {u})", source=SRC,
                why=WHY + f": {u} {T0} g{g} {ex} (the two indices' picks need different premium exits, so split)",
                orig=_orig(r1_fvg, p, _ex(ex), _win(15, 314), (u,)),
                grid=_grid((tuple(sorted(p.items())), ex), combos))


# R1-7 turtle soup: NIFTY B5 EOD (index stop), BANKNIFTY B5 native (stop + 2R) -> one rule
_g7 = [(dict(B=b, exit=e), e, {}) for b in (5, 15) for e in ("native", "EOD", "LIQ")]
SPEC_R1_7 = dict(
    key="R1-7", title="Turtle soup on PDL/PDH (R1 N7)", source=SRC, why=WHY + ": NIFTY B5 EOD, BN B5 native",
    orig=_orig(r1_turtle, dict(B=5, exit={"NIFTY": "EOD", "BANKNIFTY": "native"}), EOD, _win(14, 314), NB),
    grid=_grid(None, _g7))


def _ibs(u, lo, ex):
    combos = [(dict(lo=l_, exit=e), e, {}) for l_ in (0.2, 0.1) for e in ("EOD", "60m", "LIQ")]
    p = dict(lo=lo, exit=ex)
    return dict(key=f"R1-8@{u}", title=f"Internal bar strength (R1 N8, {u})", source=SRC,
                why=WHY + f": {u} {lo} {ex} (the two indices' picks need different exits, so split)",
                orig=_orig(r1_ibs, p, _ex(ex), _win(0, 0), (u,)),
                grid=_grid((tuple(sorted(p.items())), ex), combos))


# R1-9 VIX spike: both chg15 0916 EOD
_S9 = {"0916": 0, "0945": 9 * 60 + 45 - OPEN_M - 1}
_p9 = dict(trig="chg15", T="0916", exit="EOD")
_g9 = [(dict(trig=t, T=T, exit=e), e, dict(window=_win(_S9[T], _S9[T])))
       for t in ("chg10", "chg15", "p90") for T in ("0916", "0945") for e in ("EOD", "LIQ")]
SPEC_R1_9 = dict(
    key="R1-9", title="India VIX spike -> CE (R1 N9)", source="India VIX + index",
    why=WHY + ": both chg15 (yesterday's VIX close +15%) CE at 09:16, EOD",
    orig=_orig(r1_vixspk, _p9, EOD, _win(0, 0), NB), grid=_grid((tuple(sorted(_p9.items())), "EOD"), _g9))

# R1-14 midday reversal: both 0.006 LIQ
_S14 = 12 * 60 - OPEN_M - 1
_p14 = dict(thr=0.006, exit="LIQ")
_g14 = [(dict(thr=t, exit=e), e, {}) for t in (0.003, 0.006) for e in ("1330", "EOD", "LIQ")]
SPEC_R1_14 = dict(
    key="R1-14", title="Midday reversal (R1 N14)", source=SRC, why=WHY + ": both 0.6% at 12:00, LIQ",
    orig=_orig(r1_lunchrev, _p14, LIQ, _win(_S14, _S14), NB), grid=_grid((tuple(sorted(_p14.items())), "LIQ"), _g14))

SPECS = [
    SPEC_R1_1,
    _wvb("NIFTY", 0.75, "EOD"), _wvb("BANKNIFTY", 0.75, "LIQ"),
    SPEC_R1_4,
    _fvg("NIFTY", "0930", 0.0005, "EOD"), _fvg("BANKNIFTY", "0930", 0.0005, "LIQ"),
    SPEC_R1_7,
    _ibs("NIFTY", 0.1, "LIQ"), _ibs("BANKNIFTY", 0.2, "60m"),
    SPEC_R1_9,
    SPEC_R1_14,
]
