"""R50 rules (PREREG.md tables): the 20 originals and the 8 small re-tune grids."""
from __future__ import annotations

import r50cfg as K
import r25lib as R
import r43lib as L43
import r26lib as L26
from r50lib import RuleSpec, TWIN_WINDOW


def _rows(a, b):
    return (R.close_row(*a), R.close_row(*b))


HERO_W = _rows((13, 30), (14, 45))
V4_W = _rows((13, 30), (15, 0))
R26_W = (L26.FIRST, L26.LAST)
ST_W = _rows((14, 0), (14, 45))
K1_W = (R.close_row(14, 45) + 1, R.close_row(15, 0))
T1_W = _rows((11, 0), (14, 30))
SWEEP_HERO = (38, 65)
SWEEP_ALL = (0, 65)


def _sw(j):
    return (L43.sig_row(j[0]), L43.sig_row(j[1]))


ORIGINALS = [
    RuleSpec("O-H0", "app Hero arm", "hero", "hero1_5", 5000.0, "app", unds=("NIFTY",), arg={"band": (1.0, 5.0)},
             window=HERO_W, source="index momentum + ATM straddle premium (chain context)",
             orig="R21 V0 / R25 H0 = HeroRules.kt (NIFTY only)"),
    RuleSpec("O-V4", "app Hero arm", "onesided", "hero1_15", 5000.0, "app", arg={"band": (1.0, 15.0)}, window=V4_W,
             source="OPTION'S OWN CHART (non-default) + index momentum", orig="R21 V4 / R25 V4"),
    RuleSpec("O-L1", "cheap OTM lottery", "basket", "lot3", 500.0, "hold", arg={"at": R.close_row(13, 30)},
             take="all", source="none (time only, both sides)", orig="R25 L1"),
    RuleSpec("O-L3", "cheap OTM lottery", "basket", "lot3", 500.0, "hold", arg={"at": R.close_row(14, 30)},
             take="all", source="none (time only, both sides)", orig="R25 L3"),
    RuleSpec("O-BASE", "cheap OTM lottery", "basket", "cheap", 5000.0, "s50", arg={"at": L43.sig_row(37)},
             take="all", source="none (time only, both sides)", orig="R43 base cheap hero hold (12:30)"),
    RuleSpec("O-P", "OI-wall short covering", "r26", "atm1_15_30", 2000.0, "E2", "min1", arg={"oi": True},
             window=R26_W, source="index break of the OI wall; OI as filter", orig="R26 P"),
    RuleSpec("O-A6", "OI-wall short covering", "r26", "atm1_15_30", 2000.0, "E3", "min1", arg={"oi": True},
             window=R26_W, source="index break of the OI wall; OI as filter", orig="R26 A6"),
    RuleSpec("O-B1", "Boss's 1:45-2:30 window", "r26", "atm1_15_30", 2000.0, "E2", "min1", arg={"oi": False},
             window=R26_W, source="index", orig="R26 B1"),
    RuleSpec("O-X1", "gamma / GEX", "gex", "r22", 1000.0, "E3", "min1", arg={"flag": "x1", "at": R.close_row(14, 0)},
             take="all", source="chain (GEX) day filter, no direction", orig="R36 X1"),
    RuleSpec("O-X2", "gamma / GEX", "gex", "r22", 1000.0, "E3", "min1", arg={"flag": "x2", "at": R.close_row(14, 0)},
             take="all", source="chain (GEX) day filter, no direction", orig="R36 X2"),
    RuleSpec("O-E821", "sweep signals", "sweep", "cheap", 5000.0, "s50", arg={"fam": "ema821_adx15", "win": SWEEP_HERO},
             window=_sw(SWEEP_HERO), take="first_fill", orig="R43 ema821_adx15 cheap hero hold"),
    RuleSpec("O-E921", "sweep signals", "sweep", "cheap", 5000.0, "s50", arg={"fam": "ema921_adx20", "win": SWEEP_HERO},
             window=_sw(SWEEP_HERO), take="first_fill", orig="R43 ema921_adx20 cheap hero hold"),
    RuleSpec("O-E926", "sweep signals", "sweep", "cheap", 5000.0, "s50", arg={"fam": "ema926_adx20", "win": SWEEP_HERO},
             window=_sw(SWEEP_HERO), take="first_fill", orig="R43 ema926_adx20 cheap hero hold"),
    RuleSpec("O-RSIM", "sweep signals", "sweep", "cheap", 5000.0, "s50", arg={"fam": "rsi_momo", "win": SWEEP_HERO},
             window=_sw(SWEEP_HERO), take="first_fill", orig="R43 rsi_momo cheap hero hold"),
    RuleSpec("O-RSIF", "sweep signals", "sweep", "cheap", 5000.0, "s50", arg={"fam": "rsi_fade", "win": SWEEP_ALL},
             window=_sw(SWEEP_ALL), take="first_fill", orig="R43 rsi_fade cheap all hold"),
    RuleSpec("O-DON", "sweep signals", "sweep", "otm2", 5000.0, "s50", arg={"fam": "donchian20", "win": SWEEP_ALL},
             window=_sw(SWEEP_ALL), take="first_fill", orig="R43 donchian20 otm2 all hold"),
    RuleSpec("O-S3", "volume / supertrend", "st", "atm1", 2000.0, "E3", "skip", arg={"r35": "S3"}, window=ST_W,
             source="OPTION'S OWN CHART (non-default)", orig="R35 S3"),
    RuleSpec("O-S5", "volume / supertrend", "st", "atm1", 2000.0, "E3", "skip", arg={"r35": "S5"}, window=ST_W,
             source="index (+ option volume, heavyweight stocks)", orig="R35 S5"),
    RuleSpec("O-K1", "afternoon window", "k1", "atm1", 2000.0, "hold", "min1", window=K1_W, orig="R34 K1e"),
    RuleSpec("O-T1", "afternoon window", "t1", "band1_10", 1000.0, "app", window=T1_W, take="all", orig="R25 T1"),
]
BY_ID = {r.id: r for r in ORIGINALS}


def _grid(tid, cells):
    return tid, [(name, spec.with_(id=f"{tid}:{name}")) for name, spec in cells]


H0 = BY_ID["O-H0"]
_lot = BY_ID["O-L1"]
RETUNES = dict([
    _grid("T-HERO", [(f"{b}-{e}", H0.with_(unds=K.UNDS, pick=p, exit=e, arg={"band": band}))
                     for b, p, band in (("1-5", "hero1_5", (1.0, 5.0)), ("5-30", "hero5_30", (5.0, 30.0)))
                     for e in ("app", "tr25")]),
    _grid("T-LOT", [(f"{hh}{mm:02d}-{e}", _lot.with_(arg={"at": R.close_row(hh, mm)}, exit=e))
                    for hh, mm in ((12, 30), (13, 30), (14, 30)) for e in ("hold", "tr25")]),
    _grid("T-OI", [(e, BY_ID["O-P"].with_(exit=e)) for e in ("E2", "E3", "tr25", "hold")]),
    _grid("T-BOSS", [(f"{k}-{e}", BY_ID["O-B1"].with_(pick=p, exit=e))
                     for k, p in (("atm1", "atm1_15_30"), ("cheap", "cheap")) for e in ("E2", "tr25")]),
    _grid("T-GEX", [(e, BY_ID["O-X1"].with_(exit=e)) for e in ("E3", "tr25", "hold")]),
    _grid("T-SWEEP", [(f"{f}-{e}", BY_ID["O-E821"].with_(arg={"fam": f, "win": SWEEP_HERO}, exit=e))
                      for f in ("ema821_adx15", "ema921_adx20", "donchian20") for e in ("s50", "tr25")]),
    _grid("T-ST", [(e, BY_ID["O-S5"].with_(exit=e)) for e in ("E3", "tr25", "hold")]),
    _grid("T-AFT", [(f"{k}-{e}", BY_ID["O-K1"].with_(pick=p, exit=e))
                    for k, p in (("atm1", "atm1"), ("cheap", "cheap")) for e in ("hold", "tr25")]),
])
RETUNE_FAMILY = {"T-HERO": "app Hero arm", "T-LOT": "cheap OTM lottery", "T-OI": "OI-wall short covering",
                 "T-BOSS": "Boss's 1:45-2:30 window", "T-GEX": "gamma / GEX", "T-SWEEP": "sweep signals",
                 "T-ST": "volume / supertrend", "T-AFT": "afternoon window"}

# OLD verdicts (full old data; the inventory of R21-R43, their write-ups and results)
OLD = {
    "O-H0": "R25: design 33 tr +76,379; holdout 15 tr -24,624 (p 0.99); R21 holdout 2 tr -6,259. Rejected",
    "O-V4": "R25: design 153 tr -34,403; holdout 86 tr -30,897 (p 0.89). Rejected",
    "O-L1": "R25: design 724 tr -1,77,392; holdout 253 tr -90,750. Rejected",
    "O-L3": "R25: design 732 tr -2,12,662; holdout 257 tr -74,852. Rejected",
    "O-BASE": "R43: holdout 467 tr +33,777 (p 0.012, q 0.176). Rejected (q, top-5)",
    "O-P": "R26: 15 tr -3,851 (p 1.00). Rejected",
    "O-A6": "R26: 15 tr +10,383 (p 0.80). Rejected",
    "O-B1": "R26: 18 tr -7,572 (p 1.00). Rejected",
    "O-X1": "R36: 66 days / 86 legs -32,226 (p 0.53). Rejected",
    "O-X2": "R36: 98 days / 153 legs -42,909 (p 0.62). Rejected",
    "O-E821": "R43: design 324 tr +1,23,022 (p 0.17); holdout 119 tr +87,534 (p 0.34, q 0.78); -1,94,680 without top 5. Rejected",
    "O-E921": "R43: design 178 tr -29,677; holdout 75 tr +1,37,909 (q 0.71). Rejected",
    "O-E926": "R43: design 153 tr -68,554; holdout 62 tr +81,354; -1,25,605 without top 5. Rejected",
    "O-RSIM": "R43: design 586 tr -3,97,010; holdout 218 tr +4,546; -2,90,993 without top 5. Rejected",
    "O-RSIF": "R43: design 509 tr +52,688; holdout 186 tr -2,15,365. Rejected",
    "O-DON": "R43: design 662 tr +5,83,105 (p 0.034); holdout 245 tr -67,696 (q 0.81). Rejected",
    "O-S3": "R35: 163 tr -1,16,661 (p 1.0). Rejected",
    "O-S5": "R35: 70 tr -56,251 (p 1.0). Rejected",
    "O-K1": "R34: K1e 194 tr -91,160 (p 0.95). Rejected",
    "O-T1": "R25: design 315 tr -8,721; holdout 106 tr +6,530 (p 0.69). Rejected",
}
