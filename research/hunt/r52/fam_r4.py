"""R52 family R4 + R5-1: Gann / Fibonacci / "magic number" / harmonic rules (HUNT_R4_GANN_FIB_MAGIC.md, code
research/hunt/r4/) and Boss's uploaded Gann Sq9 + Sq144 + Fib + Harmonic Pine (HUNT_R5_GUIDE_PINE.md, code
research/hunt/r5/), re-tested on 20 Nov 2024 onwards (PREREG.md). Written 15 Oct 2026.

Signal source: index (NIFTY / BANKNIFTY / FINNIFTY index minutes), every rule. The option is only what is bought.

REUSE: the signals come from the original studies' own functions, imported unchanged:
    research/hunt/r4/signals4.py   prevday, magic (sq9_rule), gann_intraday, seasonal, gann_swing, fib_swings,
                                   harmonics, bars5_day, mk_bars, piv (h37 sigs.bars / atr / zigzag underneath)
    research/hunt/r5/pine5.py      simulate (bar-by-bar replica of the uploaded Pine script)
The original functions write trade requests into an R4 request object (lib4.SReq: add / _P_nat). Here a small
recorder (_Rec) takes their place and keeps what they asked for; the adapter turns one variant's requests into the
obuy signal columns:
    side 0 (CE) -> +1, 1 (PE) -> -1; signal column s -> sig_min = 09:15 + s; fill = next minute's open (as R4).
    "...|OPT"            premium -25% / +50% (Exits stop_pct / tgt_pct), else 15:10              (R4 PREREG "OPT")
    "...|NAT" (levels)   the rule's own index stop / target -> idx_stop / idx_target, else 15:10 (R4 PREREG "NAT")
    "...|NAT" (a column) a structural exit column (G5 close back across the line) -> exit_at
    "...|T30"            out at the open of s + 31 -> exit_at                                       (R4 PREREG "T30")
    "...|EOD"            nothing but the 15:10 square-off
The P panel is lib.load_index (research/hunt/r1/lib.py) rebuilt from the harness's `mk` (same fields, same day filter:
real index feed and < 20% missing minutes before 15:00; this filter reads the day's minute COUNT, not its prices).

R4 frame kept (r4/PREREG.md): 1-ITM strike of the nearest expiry, entry window 09:20-14:45 (signal minute), square-off
15:10, expiry days INCLUDED ("as r1"), one position at a time per index and at most 3 trades a day.

Per-index frozen picks: R4 froze one variant per rule family PER INDEX (r4/results/frozen.json), and some picks differ
in their exit (OPT premium exits vs index-level / time exits). obuy applies one Exits per rule, so a key's 'orig' is
the frozen pick of the index the catalogue's "close" verdict names, together with every other index whose frozen pick
uses the same exit kind (each index keeps its own frozen variant). The frozen picks of the remaining indices are the
first grid entries (on their own index), followed by the study's own variants of that family on the orig indices.

R5-1 'orig' = the script exactly as uploaded (AS_WRITTEN = Sq144 time filter on), 15-minute, the script default
(pivot 10/10, minSwing 1.0 ATR, cooldown 15), NAT exit (the script's own stop X -/+ 0.25 ATR and TP1 1.8R on the
index, or its reversal). R5 pre-registered this default as PRIMARY on 15m / 30m / 1h x AS_WRITTEN / PRAG / CONFL,
NAT and OPT; the other 17 primary runs are the grid. Note: in R5 the AS_WRITTEN script made 0 option trades in 5
years (design), so this key may well have 0-3 trades here.

LEFT: none of the assigned keys. Notes:
  * R4-14 includes R4-15 (M2 45-degree calculator, step 0.25) in its grid (catalogue merge).
  * R4-18 includes R4-19 / R4-20 / R4-21 (Cypher, 5-0, Shark picks) in its grid; Bat / Alt Bat / Crab had < 30
    design trades and no frozen pick in R4, so they are not added (no original setting to test).
  * R4-13 (8 seasonal dates a year) gives about 8 events a year per index: expect "too few trades to judge".
"""
from __future__ import annotations

import importlib
import os
import sys
import warnings

import numpy as np
import pandas as pd

_HUNT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_RESEARCH = os.path.dirname(_HUNT)


def _load_originals():
    """Import r4/signals4 (+ lib4, r1/lib, h37/sigs) and r5/pine5 under their own names without clashing with any other
    module called 'lib' / 'sigs' another family may have loaded: the generic names are swapped out of sys.modules for
    the import and put back afterwards (the imported modules keep their own references)."""
    names = ("lib", "lib4", "sigs", "signals4", "pine5")
    saved = {n: sys.modules.pop(n) for n in names if n in sys.modules}
    paths = [os.path.join(_HUNT, "r4"), os.path.join(_HUNT, "r1"), os.path.join(_HUNT, "h37"),
             os.path.join(_HUNT, "r5"), _RESEARCH]
    old_path = list(sys.path)
    sys.path[:0] = paths
    try:
        s4 = importlib.import_module("signals4")
        p5 = importlib.import_module("pine5")
        l4 = sys.modules["lib4"]
    finally:
        sys.path[:] = old_path
        for n in names:
            sys.modules.pop(n, None)
        sys.modules.update(saved)
    return s4, l4, p5


S4, L4, P5 = _load_originals()

NAN = float("nan")
OPEN_M = 9 * 60 + 15
SQ10 = 15 * 60 + 10                       # R4 square-off 15:10 (lib.SQ = column 355)
WIN = (OPEN_M + L4.W0, OPEN_M + L4.W1)    # 09:20 .. 14:45 signal minutes
POS = dict(one_at_a_time=True, max_per_day=3)
OPT_X = dict(stop_pct=0.25, tgt_pct=0.50, sq_off=SQ10)
NAT_X = dict(sq_off=SQ10)
NB = ("NIFTY", "BANKNIFTY")
NBF = ("NIFTY", "BANKNIFTY", "FINNIFTY")


# ------------------------------------------------------------------------------------------------ data adapter
def panel(mk, u):
    """r1 lib.load_index(u), built from the harness's mk instead of the global obuy market."""
    ix = mk.index(u)
    M = ix.mat()
    days = list(ix.days)
    nd = len(days)
    if nd == 0:
        return None
    dl = ix.daily()
    real = np.array([bool(ix.d[d].get("real", True)) for d in days])
    with np.errstate(invalid="ignore"):
        ok = real & (np.isnan(M["c"][:, :345]).mean(1) < 0.2)
    c = pd.DataFrame(M["c"]).T.ffill().T.values          # forward-filled closes (within the day)
    P = dict(u=u, days=days, nd=nd, o=M["o"], h=M["h"], l=M["l"], c=c, ok=ok,
             exp=np.array([bool(ix.d[d].get("exp", False)) for d in days]),
             dopen=dl["open"].values.astype(float), dhigh=dl["high"].values.astype(float),
             dlow=dl["low"].values.astype(float), dclose=dl["close"].values.astype(float))
    P["pclose"] = np.r_[np.nan, P["dclose"][:-1]]
    P["phigh"] = np.r_[np.nan, P["dhigh"][:-1]]
    P["plow"] = np.r_[np.nan, P["dlow"][:-1]]
    return P


class _Rec:
    """Stands in for lib4.SReq: records (var, di, s, side, payload) instead of pricing anything."""

    def __init__(self, P):
        self.P = P
        self.rows = []

    def add(self, var, di, s, side, x=-1, sp=NAN, tp=NAN, idx_exit=False, lo=None, hi=None):
        self.rows.append((var, int(di), int(s), int(side), int(x)))

    def _P_nat(self, var, di, s, side, v):
        self.rows.append((var, int(di), int(s), int(side), v if not isinstance(v, (int, np.integer)) else int(v)))


# which original builder produces each R4 family
_DAY = {"F1_FIBPIV": "prevday", "F2_PDRFIB": "prevday", "F7_CONF": "prevday", "G4_MID50": "prevday",
        "M1_SQ9I": "magic", "M2_SQ9H": "magic", "M3_RMAG": "magic", "M4_RFADE": "magic", "M9_ORFIB": "magic",
        "G5_ANG": "gann_intraday", "G6_TOPEN": "gann_intraday", "G6_TEXT": "gann_intraday"}

_CACHE = {}


def _bars(P, tf):
    """signals4.mk_bars, quiet about all-NaN bars (missing-minute gaps; h37 fills them from the close)."""
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        return S4.mk_bars(P, tf)


def _builder(fam, var):
    if fam in _DAY:
        return _DAY[fam], 0
    if fam == "G7_SEAS":
        return "seasonal", 0
    tf = int(var.split("|")[1][2:])
    if fam == "G3_SWING":
        return "gann_swing", tf
    if fam in ("F3_GP", "F4_UPX61"):
        return "fib_swings", tf
    if fam.startswith("H_"):
        return "harmonics", tf
    raise ValueError(f"family not wired: {fam}")


def _requests(mk, u, P, bname, tf):
    key = (id(mk), u, bname, tf)
    hit = _CACHE.get(key)
    if hit is not None and hit[0] is mk:
        return hit[1]
    R = _Rec(P)
    days_ok = np.nonzero(P["ok"])[0]
    okd = P["ok"].copy()
    if bname in ("prevday", "magic", "gann_intraday"):
        fn = getattr(S4, bname)
        for di in days_ok:
            fn(R, P, di, S4.bars5_day(P, di))
    elif bname == "seasonal":
        S4.seasonal(R, P, days_ok)
    else:
        B = _bars(P, tf)
        if bname == "gann_swing":
            S4.gann_swing(R, P, B, tf, okd)
        else:
            Z = S4.piv(B, 2)
            getattr(S4, bname)(R, P, B, tf, okd, Z)
    _CACHE[key] = (mk, R.rows)
    return R.rows


def _rows_to_frame(u, P, rows, var):
    out = []
    for v, di, s, side, pay in rows:
        if v != var:
            continue
        r = dict(und=u, day=P["days"][di], sig_min=OPEN_M + s, side=1 if side == 0 else -1, book=f"r4_{u}",
                 idx_stop=NAN, idx_target=NAN, exit_at=NAN)
        kind = var.split("|")[-1]
        if kind not in ("OPT", "EOD"):
            if isinstance(pay, tuple):
                r["idx_stop"], r["idx_target"] = float(pay[0]), float(pay[1])
            elif pay >= 0:
                r["exit_at"] = OPEN_M + int(pay)
        out.append(r)
    return out


def _var_for(var, u):
    if isinstance(var, str):
        return var
    return dict(var).get(u)


def r4_rule(mk, var, unds=NBF):
    """One R4 variant, e.g. var='F1_FIBPIV|bnc|OPT' on every index of unds, or var={index: variant} (per-index frozen
    picks, all with the same exit kind). Columns: und, day, sig_min, side, book, idx_stop, idx_target, exit_at."""
    out = []
    for u in unds:
        v = _var_for(var, u)
        if not v:
            continue
        P = panel(mk, u)
        if P is None:
            continue
        fam = v.split("|")[0]
        bname, tf = _builder(fam, v)
        out += _rows_to_frame(u, P, _requests(mk, u, P, bname, tf), v)
    cols = ["und", "day", "sig_min", "side", "book", "idx_stop", "idx_target", "exit_at"]
    return pd.DataFrame(out, columns=cols)


# ------------------------------------------------------------------------------------------------ R5-1 Pine
def r5_pine(mk, cfg="AS_WRITTEN", tf=15, piv=10, ms=1.0, cd=15, exit="NAT", unds=NB):
    """R5 run_period(B) adapter: pine5.simulate on continuous tf-minute bars (h37 bars via signals4.mk_bars); every
    script ENTRY at a bar close with signal minute 09:20-14:45 on an ok day -> long = CE, short = PE. NAT: the script's
    stop / TP1 at entry as index levels, plus its same-day reversal close (exit at the next minute's open)."""
    out = []
    for u in unds:
        P = panel(mk, u)
        if P is None:
            continue
        B0 = _bars(P, tf)
        B = {k: v for k, v in B0.items() if isinstance(v, np.ndarray)}
        if len(B.get("c", [])) == 0:
            continue
        T, _ = P5.simulate(B, piv, ms, cd, cfg)
        for r in T.itertuples() if len(T) else []:
            s, di = int(r.ecol), int(r.edpos)
            if not (L4.W0 <= s <= L4.W1) or not P["ok"][di]:
                continue
            row = dict(und=u, day=P["days"][di], sig_min=OPEN_M + s, side=1 if r.side > 0 else -1, book=f"r5_{u}",
                       idx_stop=NAN, idx_target=NAN, exit_at=NAN)
            if exit == "NAT":
                row["idx_stop"], row["idx_target"] = float(r.sl), float(r.tp)
                if r.why == "REV" and int(r.xdpos) == di:
                    row["exit_at"] = OPEN_M + int(r.xcol) + 1
            out.append(row)
    cols = ["und", "day", "sig_min", "side", "book", "idx_stop", "idx_target", "exit_at"]
    return pd.DataFrame(out, columns=cols)


# ------------------------------------------------------------------------------------------------ specs
def _ex(var):
    """Exits for a variant (all variants of one setting share the exit kind)."""
    v = var if isinstance(var, str) else next(iter(dict(var).values()))
    return OPT_X if v.endswith("|OPT") else NAT_X


def _o(var, unds, window=WIN):
    return dict(fn=r4_rule, params=dict(var=var, unds=tuple(unds)), money=1, series="near", unds=tuple(unds),
                exits=_ex(var), expiry="allow", pos=POS, window=window)


def _g(var, unds):
    return dict(params=dict(var=var, unds=tuple(unds)), unds=tuple(unds), exits=_ex(var))


def _grid(fam, variants, orig_unds, others=(), skip=None):
    """others: [(var, unds)] = the frozen picks of the other indices; then the family's own variants on orig_unds."""
    g = [_g(v, us) for v, us in others]
    g += [_g(f"{fam}|{v}", orig_unds) for v in variants if f"{fam}|{v}" != skip]
    return g[:23]


def _pairs(d):
    return tuple(sorted(d.items()))


F12 = ("bnc|NAT", "bnc|OPT", "bnc|T30", "brk|NAT", "brk|OPT", "brk|T30")
F3V = tuple(f"tf{t}|{x}" for t in (3, 5, 15) for x in ("NAT", "NAT127", "OPT", "T30"))
G3V = tuple(f"tf{t}|{x}" for t in (15, 60) for x in ("NAT", "OPT", "T30"))
G5V = tuple(f"{a}|{x}" for a in ("1x1", "2x1") for x in ("NAT", "OPT", "T30"))
M1V = tuple(f"{r}|{x}" for r in ("r0925", "rOpen", "rPC") for x in ("NATk1", "NATk3", "OPT", "T30"))
M2V = tuple(f"M2_SQ9H|{r}|{x}" for r in ("r0925", "rPC") for x in ("NATk1", "OPT", "T30"))
M3V = tuple(f"{g}|{x}" for g in ("major", "minor") for x in ("NAT", "OPT", "T30"))

BN, FIN, NIF = ("BANKNIFTY",), ("FINNIFTY",), ("NIFTY",)
BF = ("BANKNIFTY", "FINNIFTY")
NF = ("NIFTY", "FINNIFTY")

_R418_OTHERS = [  # R4 frozen picks named in the catalogue / shortlist (incl. R4-19 Cypher / 5-0, R4-20 Shark)
    ("H_ALL|tf60|NAT1", BN), ("H_CYPHER|tf3|NAT1", BN), ("H_FIVE0|tf3|NAT1", BN), ("H_GARTLEY|tf3|NAT1", BN),
    ("H_SHARK|tf15|OPT", BN), ("H_ABCD|tf60|NAT1", NIF), ("H_ALL|tf60|NAT1", NIF), ("H_FIVE0|tf3|OPT", NIF),
    ("H_SHARK|tf15|NAT1", NIF), ("H_SHARK|tf5|NAT1", FIN), ("H_FIVE0|tf3|NAT1", FIN)]

_WHY = "original = R4's frozen pick (research/hunt/r4/results/frozen.json, HUNT_R4_GANN_FIB_MAGIC.md) "

SPECS = [
    dict(key="R4-1", title="Fibonacci floor pivots (F1)", source="index",
         why=_WHY + "BANKNIFTY + FINNIFTY F1_FIBPIV|bnc|OPT (both frozen); NIFTY's frozen bnc|T30 is grid 1",
         orig=_o("F1_FIBPIV|bnc|OPT", BF),
         grid=_grid("F1_FIBPIV", F12, BF, [("F1_FIBPIV|bnc|T30", NIF)], "F1_FIBPIV|bnc|OPT")),
    dict(key="R4-2", title="Previous-day-range Fibonacci levels (F2)", source="index",
         why=_WHY + "BANKNIFTY F2_PDRFIB|brk|T30; NIFTY bnc|OPT + FINNIFTY brk|OPT (frozen) are grid 1",
         orig=_o("F2_PDRFIB|brk|T30", BN),
         grid=_grid("F2_PDRFIB", F12, BN,
                    [(_pairs({"NIFTY": "F2_PDRFIB|bnc|OPT", "FINNIFTY": "F2_PDRFIB|brk|OPT"}), NF)],
                    "F2_PDRFIB|brk|T30")),
    dict(key="R4-3", title="Fibonacci golden pocket (F3)", source="index",
         why=_WHY + "NIFTY F3_GP|tf15|NAT + FINNIFTY F3_GP|tf15|NAT127 (index-level exits); BANKNIFTY tf5|OPT is grid 1",
         orig=_o(_pairs({"NIFTY": "F3_GP|tf15|NAT", "FINNIFTY": "F3_GP|tf15|NAT127"}), NF),
         grid=_grid("F3_GP", F3V, NF, [("F3_GP|tf5|OPT", BN)])),
    dict(key="R4-4", title="Upstox 3-min 61.8% touch + reversal candle (F4)", source="index",
         why=_WHY + "F4_UPX61|tf3|OPT on all three indices",
         orig=_o("F4_UPX61|tf3|OPT", NBF),
         grid=_grid("F4_UPX61", ("tf3|NAT", "tf3|OPT", "tf3|T30"), NBF, (), "F4_UPX61|tf3|OPT")),
    dict(key="R4-6", title="Fibonacci level + round-100 confluence (F7)", source="index",
         why=_WHY + "NIFTY F7_CONF|rnd100|NAT; BANKNIFTY + FINNIFTY rnd100|OPT (frozen) are grid 1",
         orig=_o("F7_CONF|rnd100|NAT", NIF),
         grid=_grid("F7_CONF", ("rnd100|NAT", "rnd100|OPT", "rnd100|T30"), NIF, [("F7_CONF|rnd100|OPT", BF)],
                    "F7_CONF|rnd100|NAT")),
    dict(key="R4-8", title="Gann 2-bar swing chart break (G3)", source="index",
         why=_WHY + "G3_SWING NAT: NIFTY + BANKNIFTY tf15, FINNIFTY tf60",
         orig=_o(_pairs({"NIFTY": "G3_SWING|tf15|NAT", "BANKNIFTY": "G3_SWING|tf15|NAT",
                         "FINNIFTY": "G3_SWING|tf60|NAT"}), NBF),
         grid=_grid("G3_SWING", G3V, NBF)),
    dict(key="R4-10", title="Gann 1x1 / 2x1 intraday angle (G5)", source="index",
         why=_WHY + "G5_ANG|2x1: NIFTY + BANKNIFTY NAT (exit on the close back across the line), FINNIFTY T30",
         orig=_o(_pairs({"NIFTY": "G5_ANG|2x1|NAT", "BANKNIFTY": "G5_ANG|2x1|NAT", "FINNIFTY": "G5_ANG|2x1|T30"}),
                 NBF),
         grid=_grid("G5_ANG", G5V, NBF)),
    dict(key="R4-13", title="Gann seasonal dates: fade the 5-day move (G7)", source="index",
         why=_WHY + "G7_SEAS|fade5d|OPT on NIFTY + BANKNIFTY (signal 09:15 close, twins at the same minute as R4)",
         orig=_o("G7_SEAS|fade5d|OPT", NB, window=(OPEN_M, OPEN_M)),
         grid=[_g("G7_SEAS|fade5d|EOD", NB)]),
    dict(key="R4-14", title="Square-of-9 intraday calculator (M1; grid incl. R4-15 M2)", source="index",
         why=_WHY + "M1_SQ9I|r0925|NATk3 on all three indices; R4-15 (M2_SQ9H, frozen r0925|T30) in the grid",
         orig=_o("M1_SQ9I|r0925|NATk3", NBF),
         grid=[_g(v, NBF) for v in ("M2_SQ9H|r0925|T30",) + tuple(x for x in M2V if x != "M2_SQ9H|r0925|T30")]
         + _grid("M1_SQ9I", M1V, NBF, (), "M1_SQ9I|r0925|NATk3")),
    dict(key="R4-16", title="Round number as a magnet (M3)", source="index",
         why=_WHY + "NIFTY M3_RMAG|major|OPT; BANKNIFTY minor|T30 + FINNIFTY major|T30 (frozen) are grid 1",
         orig=_o("M3_RMAG|major|OPT", NIF),
         grid=_grid("M3_RMAG", M3V, NIF,
                    [(_pairs({"BANKNIFTY": "M3_RMAG|minor|T30", "FINNIFTY": "M3_RMAG|major|T30"}), BF)],
                    "M3_RMAG|major|OPT")),
    dict(key="R4-18", title="Harmonic patterns, R4 engine (incl. R4-19/20/21)", source="index",
         why=_WHY + "BANKNIFTY H_ABCD|tf15|OPT (the catalogue's headline pick); the other frozen picks it names "
                    "(BN all-60m / Cypher / 5-0 / Gartley / Shark, NIFTY ABCD / all / 5-0 / Shark, FIN Shark / 5-0) "
                    "are the grid, then ABCD's own exits / timeframes on BANKNIFTY",
         orig=_o("H_ABCD|tf15|OPT", BN),
         grid=_grid("H_ABCD", ("tf15|NAT1", "tf15|NAT2", "tf3|OPT", "tf5|OPT", "tf60|OPT"), BN, _R418_OTHERS)),
]

# R5-1: 3 configs x 3 timeframes x NAT / OPT at the script default = R5's 18 pre-registered primary runs
_R5_ALL = [(c, t, x) for c in ("AS_WRITTEN", "PRAG", "CONFL") for t in (15, 30, 60) for x in ("NAT", "OPT")]
SPECS.append(dict(
    key="R5-1", title="Boss's uploaded Gann Sq9 + Sq144 + Fib + Harmonic Pine", source="index",
    why="original = the script as uploaded (AS_WRITTEN), 15m, script default pivot 10 / minSwing 1.0 / cooldown 15, NAT "
        "exit = R5's PRIMARY setting (research/hunt/r5/PREREG.md, design.py DEFAULT); grid = the other 17 primary runs",
    orig=dict(fn=r5_pine, params=dict(cfg="AS_WRITTEN", tf=15, piv=10, ms=1.0, cd=15, exit="NAT", unds=NB), money=1,
              series="near", unds=NB, exits=NAT_X, expiry="allow", pos=POS, window=WIN),
    grid=[dict(params=dict(cfg=c, tf=t, exit=x), exits=OPT_X if x == "OPT" else NAT_X)
          for c, t, x in _R5_ALL if (c, t, x) != ("AS_WRITTEN", 15, "NAT")],
))
