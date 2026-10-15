"""R52 rule registry: each catalogue rule's ORIGINAL setting and its re-tune grid (PREREG.md).

A rule is either an obuy-registered strategy (research/obuy/strategies, run with its own signal function, strike rules
and exits; only fills, charges, lot and twins are R52's) or a family module r52/fam_*.py exposing SPECS (see
spec_rules). The re-tune grid is the original study's own grid; above GRID_CAP settings a fixed random subset
(seed r52cfg.SEED) is used, always with the original first.
"""
from __future__ import annotations

import importlib

import numpy as np

import r52cfg as K
from r52lab import Rule

GRID_CAP = 24

# key -> (obuy strategy name, original vid 's<i>|r<j>|x<k>', why that one, signal source)
OBUY = {
    "OBUY_GA-1": ("ga_or05_ohol", "s0|r0|x0", "no frozen variant named (OBUY_GA.md): the catalog's first setting", "index"),
    "OBUY_GA-11": ("ga_lv04_nr7", "s12|r0|x0", "NR7 inside day, touch, levels only, ITM1 (the catalog's NR7 base)", "index"),
    "OBUY_GA-12": ("ga_lv05_donchian", "s6|r1|x0", "the walk-forward pick every year (OBUY_GA.md)", "index (+ VIX filter)"),
    "OBUY_GB-1": ("gb_mr04_camarilla", "s0|r0|x0", "in-sample best, the paper control (OBUY_GB.md / OBUY_FINAL.md)", "index"),
    "OBUY_GB-4": ("gb_ti04_ema9_twap", "s2|r0|x11", "in-sample best and WF pick 2023-26 (OBUY_GB.md)", "index"),
    "OBUY_GC-3": ("gc_exp05_scalp1", "s0|r0|x0", "the catalog's EXP-05 setting (follow last 5 min, -3.3%/+10%, 1 a day)", "index"),
    "OBUY_GC-14": ("gc_pos01_btst", "s10|r1|x1", "in-sample best (strong close, mirrored, out 09:30, ITM1, -30%; skip-Friday off)", "index"),
    "OBUY_GC-15": ("gc_pos02_trend", "s0|r0|x0", "in-sample best signal (Supertrend, Varsity strike, hold 5); exit not named: none", "index"),
}

FAMILIES = ["fam_r1", "fam_h42h28", "fam_r4", "fam_h25h37", "fam_misc", "fam_swingvol"]          # fam_* module names, appended as they are written
TITLES = {}


def _obuy_strategy(name):
    from obuy.strategies.registry import all_strategies
    return all_strategies()[name]


def _parse(vid):
    s, r, x = vid.split("|")
    return int(s[1:]), int(r[1:]), int(x[1:])


def _obuy_rules(key):
    name, orig, _, src = OBUY[key]
    st = _obuy_strategy(name)
    allv = [v.vid.split("|", 1)[1] for v in st.variants()]
    rest = [v for v in allv if v != orig]
    if len(rest) > GRID_CAP - 1:
        rng = np.random.default_rng(K.SEED)
        rest = [rest[i] for i in sorted(rng.choice(len(rest), GRID_CAP - 1, replace=False))]
    out = []
    for i, vid in enumerate([orig] + rest):
        si, ri, xi = _parse(vid)
        out.append(Rule(key=key, vid="orig" if i == 0 else vid.replace("|", "."), name=name, signal_fn=st.signal_fn,
                        params=dict(st.sig_grid[si]), rule=st.rules[ri], exits=st.exits[xi], expiry=st.exe.expiry,
                        pos=dict(st.pos), window=st.window, source=src))
    return out


def _families():
    out = {}
    for m in FAMILIES:
        mod = importlib.import_module(m)
        for spec in mod.SPECS:
            out[spec["key"]] = spec
    return out


def spec_rules(spec) -> list:
    """A family SPEC: dict(key, title, source, orig=dict(fn, params, money, series, exits, expiry, pos, window, unds),
    grid=[dict of overrides of the same fields], why) -> Rules ('orig' first, then 'g01' ...)."""
    from obuy.engine import Exits, StrikeRule
    o = spec["orig"]

    def make(vid, over):
        x = {**o, **over}
        if "params" in over:
            x["params"] = {**o.get("params", {}), **over["params"]}
        return Rule(key=spec["key"], vid=vid, name=spec["title"], signal_fn=x["fn"], params=x.get("params", {}),
                    rule=StrikeRule(x.get("money", 0), x.get("series", "near")), exits=Exits(**x.get("exits", {})),
                    expiry=x.get("expiry", "skip"), pos=x.get("pos", dict(one_at_a_time=True)), window=x.get("window"),
                    source=spec["source"], unds=tuple(x.get("unds", ())))
    grid = spec.get("grid", [])[:GRID_CAP - 1]
    return [make("orig", {})] + [make(f"g{i + 1:02d}", g) for i, g in enumerate(grid)]


def variants(key) -> list:
    if key in OBUY:
        return _obuy_rules(key)
    return spec_rules(_families()[key])


def describe(key) -> dict:
    if key in OBUY:
        name, orig, why, src = OBUY[key]
        r = _obuy_rules(key)[0]
        return dict(kind="obuy", strategy=name, orig_vid=orig, why=why, source=src, params=r.params,
                    rule=r.rule.label(), exits=r.exits.label(), expiry=r.expiry, pos=r.pos)
    s = _families()[key]
    r = spec_rules(s)[0]
    return dict(kind="family", title=s["title"], why=s.get("why", ""), source=s["source"], params=r.params,
                rule=r.rule.label(), exits=r.exits.label(), expiry=r.expiry, pos=r.pos, unds=list(r.unds),
                fn=f"{r.signal_fn.__module__}.{r.signal_fn.__name__}")


class _Rules(dict):
    """Lazy key list: every OBUY key + every family SPEC key."""

    def _fill(self):
        if not dict.__len__(self):
            for k in OBUY:
                dict.__setitem__(self, k, "obuy")
            for k in _families():
                dict.__setitem__(self, k, "family")

    def items(self):
        self._fill()
        return dict.items(self)

    def __contains__(self, k):
        self._fill()
        return dict.__contains__(self, k)

    def __iter__(self):
        self._fill()
        return dict.__iter__(self)


RULES = _Rules()
