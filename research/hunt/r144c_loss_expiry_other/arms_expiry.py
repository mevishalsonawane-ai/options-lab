"""R144c (PREREG.md section 1): the trade lists of the three expiry-day arms, rebuilt with their own engines.

    IRAALGO_LAKE=<lake> python research/hunt/r144c_loss_expiry_other/arms_expiry.py   # -> $R144C_OUT/trades_<ARM>.pkl

- FT3 near-ATM morning: R117's `ft3_tickets` (R98 `ft3_app` pick and fill, R98 charges), exit "HOLD" = the frozen rule
  (out at 15:05). Parity: 478 tickets, mean Rs 1,805.25 (R117 / FORWARD_TEST.md), asserted.
- Hero Rs 1,000 ticket: R117's `hero_tickets` (R55 cell 1230 / 1505 / T0, the 25% trail from the fill, the app's
  default since 25 Oct). Parity on R55's window: design 248 / -Rs 14,507, final 107 / +Rs 1,559, asserted.
- Expiry Hero (`HeroRules`): `hero_port.py` (a line-for-line port; no research engine exists on the lake).

Signal source (CLAUDE.md): all three are OPTION-PRICE picks (labelled, reported apart): FT3 by the option's own price, IV
and straddle distance; the Hero ticket by the clock and the option's own price band; Expiry Hero by the ATM straddle's
premium and NIFTY's 15-minute move. Stops, trails and targets read the held option's premium (allowed).
"""
from __future__ import annotations

import os
import pickle
import sys
from multiprocessing import Pool
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
CACHE = Path(os.environ.get("IRAALGO_CACHE", "~/.cache/iraalgo")).expanduser()
OUT = Path(os.environ.get("R144C_OUT", CACHE / "r144c")).expanduser()
sys.path.insert(0, str(HUNT / "r117_zero_hero_lock"))
import r117cfg as K  # noqa: E402  (puts R98, R55 and their families on the path)
import r117lib as L  # noqa: E402
import r98data as R98  # noqa: E402
import r54data as DD  # noqa: E402
import r54lib as LB  # noqa: E402
import r55lib as L55  # noqa: E402

FT3_PARITY = (478, 1805.25)
HERO_PARITY = {"design": (248, -14507), "final": (107, 1559)}


def _ft3(und):
    days = [d for d in R98.load(und) if K.period(d.day) is not None]
    return L.ft3_tickets(days, exits=["HOLD"])


def _hero(und):
    LB.load_late()
    days = pickle.loads(DD.days_path(und).read_bytes())
    days = [d for d in DD._prep(days) if K.period(d.day) is not None]
    return L.hero_tickets(days, exits=["T0"])


def row_of(tk, x):
    d = tk.dec.get(x) or ()
    return int(d[-1][0]) if d else None


def to_rows(arm: str, ts: list, x: str, end_row: int) -> list:
    out = []
    for t in ts:
        xr = row_of(t, x)
        kind = "15:05" if xr is None or xr >= end_row else ("trail" if x == "T0" else "early")
        out.append(dict(arm=arm, und=t.und, day=t.day, side=t.side, strike=float(t.strike), sig_row=int(t.sig_row),
                        entry_row=int(t.fill_row), entry_px=float(t.entry), exit_row=xr if xr is not None else end_row,
                        exit_kind=kind, net=float(t.nets[x]), reach=float(t.reach)))
    return out


def build() -> dict:
    with Pool(4) as p:
        ft3 = [t for part in p.map(_ft3, R98.UNDS) for t in part]
        hero = [t for part in p.map(_hero, ("NIFTY", "SENSEX")) for t in part]
    n, m = len(ft3), float(np.mean([t.nets["HOLD"] for t in ft3]))
    assert (n, round(m, 2)) == FT3_PARITY, ("FT3 parity", n, m)
    k55 = sys.modules["r55cfg"]
    for per, (pn, pnet) in HERO_PARITY.items():
        lo, hi = (k55.START, k55.DESIGN_END) if per == "design" else (k55.FINAL_START, k55.LAKE_END)
        sel = [t for t in hero if lo <= t.day <= hi]
        assert (len(sel), round(sum(t.nets["T0"] for t in sel))) == (pn, pnet), ("Hero parity", per, len(sel))
    out = {"FT3": to_rows("FT3", ft3, "HOLD", R98.minute(15, 4)),
           "HEROTKT": to_rows("HEROTKT", hero, "T0", LB.bar_start(L.HERO_END))}
    import hero_port as HP
    out["HERO"] = HP.build()
    OUT.mkdir(parents=True, exist_ok=True)
    for a, rows in out.items():
        (OUT / f"trades_{a}.pkl").write_bytes(pickle.dumps(rows))
        print(a, len(rows), round(sum(r["net"] for r in rows)), flush=True)
    return out


if __name__ == "__main__":
    sys.path.insert(0, str(HERE))
    build()
