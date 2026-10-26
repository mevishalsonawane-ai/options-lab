"""R4 report, paper side: read the phone's redacted diagnostics (release diag-2026-10 of the private data repo) and
summarise the paper record per arm.

    python -I research/reports/r4/r4paper.py <folder with the downloaded diag-*.json(.gz)> <out.json>

26 Oct 2026. The files are untrusted data: they are only parsed as JSON, never run. The output holds paper P&L, so it
must be written OUTSIDE the repository (CLAUDE.md: no raw personal P&L outside the app's encryption); the script
refuses a path inside the repository.

What the uploads carry (schema 2): no rupees anywhere (P&L in points and % of the premium); per-trade rows only for the
Liquidity arm; for the passed arms only counters in text; and the day's orders (symbol, quantity, average price), from
which gross rupees can be rebuilt where both legs are present. Charges are estimated at Rs 60 a round trip a lot
(BossRisk.CHARGES_PER_LOT), so rebuilt nets are approximate.
"""
from __future__ import annotations

import gzip
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
CHARGES_PER_TRADE = 60.0
ARM_SOURCES = {"Liquidity 15+5": "LIQ", "R72 Breadth Donchian": "R72", "R51 Donchian 20": "R51",
               "R52 Open outside value": "R52", "Solo 2.0": "SOLO"}
COUNTER = re.compile(r"^(?P<arm>R72 Breadth Donchian|R51 Donchian 20|R52 Open outside value|Solo 2\.0): (?P<state>[^·]+?) ·"
                     r" open (?P<open>\d+) · (?P<closed>\d+) closed")
WOULD_BE = re.compile(r"of them (?P<wb>\d+) would-be")
FORWARD = re.compile(r"Forward test(?: \(\d+ trades\))?: (?P<n>\d+) of (?P<of>\d+)")


def load_folder(folder: Path) -> list:
    """Every diag file in the folder, as dicts, oldest day first. Only JSON parsing; nothing is executed."""
    days = []
    for p in sorted(folder.iterdir()):
        if p.suffix == ".gz":
            raw = gzip.decompress(p.read_bytes())
        elif p.suffix == ".json":
            raw = p.read_bytes()
        else:
            continue
        d = json.loads(raw)
        if isinstance(d, dict) and d.get("kind") == "iraalgo-diagnostics":
            days.append(d)
    uniq = {d["day"]: d for d in days}
    return [uniq[k] for k in sorted(uniq)]


def pair_orders(orders: list) -> list:
    """Paper option round trips from one day's orders: a completed BUY then a completed SELL of the same symbol and
    source, matched in time order. Futures (MCX) and cancelled orders are left out."""
    open_ = defaultdict(list)
    trips = []
    for o in sorted(orders, key=lambda o: o["at"]):
        if o.get("account") != "paper" or o.get("status") != "complete" or not o.get("filled"):
            continue
        sym = o["symbol"]
        if not (sym.endswith("CE") or sym.endswith("PE")):
            continue
        key = (o["source"], sym)
        if o["side"] == "BUY":
            open_[key].append(o)
        elif open_[key]:
            b = open_[key].pop(0)
            qty = min(b["filled"], o["filled"])
            gross = (o["avg"] - b["avg"]) * qty
            trips.append(dict(source=o["source"], arm=ARM_SOURCES.get(o["source"], "OTHER"), symbol=sym, qty=qty,
                              buy_at=b["at"], buy=b["avg"], sell_at=o["at"], sell=o["avg"], sell_type=o["type"],
                              gross=round(gross, 2), net_est=round(gross - CHARGES_PER_TRADE, 2),
                              pct=round(100 * (o["avg"] / b["avg"] - 1), 2) if b["avg"] else None))
    return trips


def counters(day: dict) -> dict:
    out = {}
    for s in day.get("other_arms", []):
        m = COUNTER.match(s)
        if not m:
            continue
        f = FORWARD.search(s)
        w = WOULD_BE.search(s)
        out[ARM_SOURCES[m["arm"]]] = dict(state=m["state"].strip(), closed=int(m["closed"]),
                                          would_be=int(w["wb"]) if w else 0,
                                          forward=int(f["n"]) if f else None)
    return out


def summarise(days: list) -> dict:
    sessions = [d["day"] for d in days if d.get("decisions")]
    liq_trades, trips, refusals, health, changes, liq_counter = [], [], [], {}, [], None
    for d in days:
        for a in d.get("arms", []):
            if a.get("key") == "liquidity":
                for t in a.get("trades", []):
                    if t.get("account") == "paper" and not t.get("open"):
                        liq_trades.append(dict(day=d["day"], right=t["right"], entry_at=t["entry_at"], exit_at=t["exit_at"],
                                               why=t["why"], points=t["points"], pct=t["pct"]))
                liq_counter = dict(a.get("all_days", {}), as_of=d["day"])
        for t in pair_orders(d.get("orders", [])):
            trips.append(dict(t, day=d["day"]))
        per = defaultdict(int)
        for x in d.get("decisions", []):
            v = x.get("verdict", "")
            if "same-direction cap" in v:
                per["cap"] += 1
            elif "kept for R72" in v:
                per["kept_for_r72"] += 1
            elif x.get("kind") == "order_refused":
                per["other_refusal"] += 1
        if per:
            refusals.append(dict(day=d["day"], **per))
        h = d.get("health", {})
        errs = h.get("errors", {})
        health[d["day"]] = dict(
            slow_stop_checks=sum(v for k, v in errs.items() if k.startswith("stop check")),
            unprotected=sum(v for k, v in errs.items() if k.startswith("positions not protected")),
            stream_drops=h.get("stream", {}).get("drops", 0),
            # the daily loss stop is a warning in newer builds and an error in older ones (19 Oct)
            daily_loss_stop=sum(v for k, v in list(h.get("warnings", {}).items()) + list(errs.items())
                                if k.startswith("daily loss limit")))
        for t in d.get("timeline", []):
            changes.append(dict(day=d["day"], at=t.get("at"), what=t.get("what", "")[:160]))
    last = counters(days[-1]) if days else {}
    by_arm = defaultdict(lambda: dict(trips=0, gross=0.0, net_est=0.0, wins=0, days=set()))
    for t in trips:
        b = by_arm[t["arm"]]
        b["trips"] += 1
        b["gross"] += t["gross"]
        b["net_est"] += t["net_est"]
        b["wins"] += 1 if t["net_est"] > 0 else 0
        b["days"].add(t["day"])
    arms = {k: dict(v, gross=round(v["gross"]), net_est=round(v["net_est"]), days=sorted(v["days"])) for k, v in by_arm.items()}
    return dict(files=[d["day"] for d in days], sessions=sessions, app_builds=sorted({d.get("app", "") for d in days}),
                live_flags=[dict(day=d["day"], **d.get("mode", {})) for d in days],
                liquidity=dict(trades=liq_trades, all_days=liq_counter), trips=trips, rebuilt=arms,
                counters=last, refusals=refusals, health=health, changes=changes)


def main(argv):
    folder, out = Path(argv[1]).resolve(), Path(argv[2]).resolve()
    if REPO in out.parents:
        sys.exit("refused: paper P&L must not be written inside the repository")
    s = summarise(load_folder(folder))
    out.write_text(json.dumps(s, indent=1))
    print("paper summary:", len(s["files"]), "files,", len(s["trips"]), "rebuilt trades ->", out)


if __name__ == "__main__":
    main(sys.argv)
