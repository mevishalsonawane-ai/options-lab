"""R105C Amendment 2: stop x target grid on R72 (C) and R51 + Supertrend (B), design / walk-forward / final.

    python research/hunt/r105c_winrate60/r105cgrid.py      # design then the one final read -> results/grid.json, grid.md
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r105c as Cc  # noqa: E402

STOPS = (5.0, 7.5, 10.0, 12.5, 15.0)
TARGETS = (10, 15, 20, 25, 30, None)
FLAG = Cc.RESULTS / "FINAL_READ_GRID"


def cell_spec(t, off, p_steps):
    """Exits P plus a +t% limit target (None = no target). Pure."""
    return dict(off, steps=p_steps, target=(t / 100.0 if t else -1.0))


def breakeven_win(s: dict):
    """Average loss / (average win + average loss); None if undefined. Pure."""
    w, l = s.get("avg_win"), s.get("avg_loss")
    if not w or l is None:
        return None
    return round(abs(l) / (w + abs(l)), 4)


def run(arm, stop, t, lo, hi):
    T, L = Cc._study()
    old = L.P_STOP
    L.P_STOP = stop / 100.0
    try:
        spec = cell_spec(t, L.OFF, L.P_STEPS)
        tr = []
        for und, D, cs in T.day_cands(arm, lo, hi):
            tr += T.walk(cs, T.price(und, D, cs, spec))
    finally:
        L.P_STOP = old
    return tr


def main():
    T, L = Cc._study()
    if FLAG.exists():
        raise SystemExit("the grid's final period was already read once")
    out = {}
    for arm in Cc.ARMS:
        for s in STOPS:
            for t in TARGETS:
                tr = run(arm, s, t, L.START, L.DESIGN_END)
                k = f"{arm}|{s:g}|{t or 'none'}"
                out[k] = dict(design=Cc.summ(tr, L), wf=Cc.summ(L.split(tr, L.WF_START, L.WF_END), L))
                print(k, out[k]["design"]["win"], out[k]["design"]["net"], flush=True)
    (Cc.RESULTS / "grid_design.json").write_text(json.dumps(out, indent=1) + "\n")
    FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    T.mods()["C"].open_final()
    for arm in Cc.ARMS:
        for s in STOPS:
            for t in TARGETS:
                k = f"{arm}|{s:g}|{t or 'none'}"
                out[k]["final"] = Cc.summ(run(arm, s, t, L.FINAL_START, L.FINAL_END), L)
                for ph in ("design", "wf", "final"):
                    out[k][ph]["breakeven_win"] = breakeven_win(out[k][ph])
                print(k, "final", out[k]["final"]["win"], out[k]["final"]["net"], flush=True)
    for arm in Cc.ARMS:
        base = out[f"{arm}|15|none"]
        assert (base["final"]["trades"], base["final"]["net"]) == L.EXPECT[(arm, "final")], "baseline final"
        assert (base["design"]["trades"], base["design"]["net"]) == L.EXPECT[(arm, "design")], "baseline design"
        for s in STOPS:
            for t in TARGETS:
                c = out[f"{arm}|{s:g}|{t or 'none'}"]
                c["better_everywhere"] = all(c[p]["net"] > base[p]["net"] for p in ("design", "wf", "final"))
    (Cc.RESULTS / "grid.json").write_text(json.dumps(out, indent=1) + "\n")


if __name__ == "__main__":
    main()
