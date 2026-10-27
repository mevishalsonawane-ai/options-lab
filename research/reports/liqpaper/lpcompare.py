"""Liquidity paper check (27 Oct 2026), comparison side: every research trade of the days the phone's diagnostics cover,
matched to what the app did, and each difference given a class.

    python -I research/reports/liqpaper/lpcompare.py <folder with diag-*.json.gz> <research.json> <out.json>

The diagnostics are untrusted data (only parsed as JSON, never run). The output carries paper results, so it is written
OUTSIDE the repository (refused inside); the write-up keeps only per-trade % of premium and small summaries.

Classes (fixed before reading the result, from what the evidence can show):
  app_bug      the app did something no rule asks for (a paper fill above every price the exchange printed in that
               minute);
  data_feed    the feed failed the app ("no price to fill at"), or the research priced an exit on a stale print of a
               contract that had stopped trading (the research's "missing" exit), i.e. the backtest is too kind;
  rule         a deliberate app rule the research does not have: the same-direction cap (and the place kept for R72),
               the paper daily loss limit, the no-twin rule;
  execution    the same trade, entered and left within seconds of the research, priced on the live book instead of the
               minute's open / close (spread, seconds of delay);
  unexplained  a research trade with no app decision at all.
"Bad luck" is what is left: the research rule's own result on the same days (the research trade's %).
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE.parent / "r4"))

import r4paper  # noqa: E402

BOOKS = {"Liquidity 15m": ("BANKNIFTY", 15), "Liquidity 5m": ("BANKNIFTY", 5),
         "Liquidity 30m FINNIFTY": ("FINNIFTY", 30), "Liquidity 5m FINNIFTY": ("FINNIFTY", 5),
         "Liquidity 15m MIDCPNIFTY": ("MIDCPNIFTY", 15), "Liquidity 5m MIDCPNIFTY": ("MIDCPNIFTY", 5)}
NO_TWIN_FROM = "2026-10-21"          # the no-twin rule's first paper session (b4bff48, 20 Oct evening)


def minutes(hhmm: str) -> int:
    h, m = hhmm[:5].split(":")
    return int(h) * 60 + int(m)


def paper_of(day: dict) -> dict:
    """{'trades': [...], 'decisions': [...], 'lock_at': minute or None, 'covered': bool} for one diagnostics day."""
    taken_book = {}
    decisions = []
    lock = None
    for x in day.get("decisions", []):
        book = BOOKS.get(x.get("arm", ""))
        if book is None:
            continue
        at = minutes(x["at"])
        if x.get("kind") == "taken":
            taken_book[x["at"]] = book
        if x.get("kind") == "day_lock":
            lock = at if lock is None else min(lock, at)
        decisions.append(dict(at=at, book=book, kind=x.get("kind", ""), verdict=x.get("verdict", "")))
    trades = []
    for a in day.get("arms", []):
        if a.get("key") != "liquidity":
            continue
        for t in a.get("trades", []):
            if t.get("account") != "paper" or t.get("open"):
                continue
            book = taken_book.get(t["entry_at"])
            trades.append(dict(book=book, side=t["right"][0], at=minutes(t["entry_at"]), entry=t["entry"],
                               exit=t["exit"], exit_at=t["exit_at"], why=t["why"], pct=t["pct"],
                               entry_at=t["entry_at"]))
    covered = any(d["kind"] != "outside_hours" for d in decisions) or bool(trades)
    return dict(trades=trades, decisions=decisions, lock_at=lock, covered=covered)


def twins(rows: list) -> set:
    """Indices of research trades the no-twin rule would not take (paper sessions from NO_TWIN_FROM): an entry on an
    option another Liquidity book already holds; inside a minute the longer book decides first (r4backtest.no_twin)."""
    order = sorted(range(len(rows)), key=lambda i: (rows[i]["day"], minutes(rows[i]["entry"]), -rows[i]["book"]))
    kept, out = [], set()
    for i in order:
        t = rows[i]
        if t["day"] < NO_TWIN_FROM:
            kept.append(i)
            continue
        held = any(rows[k]["day"] == t["day"] and rows[k]["und"] == t["und"] and rows[k]["side"] == t["side"]
                   and rows[k]["strike"] == t["strike"]
                   and minutes(rows[k]["entry"]) <= minutes(t["entry"]) <= minutes(rows[k]["exit"]) for k in kept)
        (out.add(i) if held else kept.append(i))
    return out


def refusal_class(verdict: str) -> tuple:
    v = verdict.lower()
    if "kept for r72" in v:
        return "rule", "same-direction cap (place kept for R72)"
    if "same-direction cap" in v:
        return "rule", "same-direction cap"
    if "no price to fill" in v:
        return "data_feed", "no price to fill at (feed)"
    return "unexplained", verdict[:60]


def taken_class(p: dict, r: dict) -> tuple:
    if p["entry"] > r["entry_high"] + 1e-9 and p["at"] == minutes(r["entry"]):
        return "app_bug", "paper fill above the minute's high (capped limit filled on a stale last price)"
    if r["why"] == "missing":
        return "data_feed", "research exit on a stale print (contract stopped trading)"
    return "execution", "same trade; live book and seconds of delay"


def compare(days: list, research: list) -> dict:
    by_day = {d["day"]: paper_of(d) for d in days}
    covered = sorted(k for k, v in by_day.items() if v["covered"])
    rows = [r for r in research if r["day"] in covered]
    tw = twins(rows)
    used = set()
    out = []
    for i, r in enumerate(rows):
        P = by_day[r["day"]]
        sig = minutes(r["signal_end"])
        book = (r["und"], r["book"])
        row = dict(day=r["day"], und=r["und"], book=r["book"], side=r["side"], strike=r["strike"],
                   signal=r["signal_end"], research_entry=r["E"], research_exit=r["X"], research_why=r["why"],
                   research_pct=r["pct"], research_pct_net=r["pct_net"], qty=r["qty"])
        hit = next((j for j, p in enumerate(P["trades"]) if (r["day"], j) not in used and p["book"] == book
                    and p["side"] == r["side"] and sig <= p["at"] <= sig + 1), None)
        if hit is not None:
            used.add((r["day"], hit))
            p = P["trades"][hit]
            cls, why = taken_class(p, r)
            row.update(app="taken", paper_entry=p["entry"], paper_exit=p["exit"], paper_why=p["why"],
                       paper_pct=p["pct"], diff_pct=round(p["pct"] - r["pct"], 2), cls=cls, why=why)
            out.append(row)
            continue
        dec = [x for x in P["decisions"] if x["book"] == book and sig <= x["at"] <= sig + 1
               and x["kind"] in ("order_refused", "liquidity", "taken")]
        if dec and dec[0]["kind"] == "order_refused":
            cls, why = refusal_class(dec[0]["verdict"])
        elif i in tw:
            cls, why = "rule", "no-twin (another book held this option)"
        elif P["lock_at"] is not None and P["lock_at"] <= sig:
            cls, why = "rule", "paper daily loss limit (all arms) had stopped the day"
        elif dec:
            cls, why = "unexplained", dec[0]["verdict"][:60]
        else:
            cls, why = "unexplained", "no app decision found"
        row.update(app="missed", paper_pct=None, diff_pct=round(-r["pct"], 2), cls=cls, why=why)
        out.append(row)
    extra = [dict(day=k, **p) for k, P in by_day.items() for j, p in enumerate(P["trades"]) if (k, j) not in used]
    return dict(covered=covered, rows=out, paper_only=extra, summary=summary(out, extra))


def summary(rows: list, extra: list) -> dict:
    """Per class (and per reason): trades, the sum of the paper-minus-research % of premium (a missed research trade
    counts as minus its %) and the same in rupees on the research trade's premium (1 lot; an estimate, not a paper
    P&L)."""
    cls = defaultdict(lambda: dict(n=0, diff_pct=0.0, diff_rs=0.0))
    why = defaultdict(lambda: dict(n=0, diff_pct=0.0, diff_rs=0.0))
    for r in rows:
        rs = r["diff_pct"] / 100 * r["research_entry"] * r["qty"]       # on the research trade's premium, 1 lot
        for c in (cls[r["cls"]], why[r["cls"] + ": " + r["why"]]):
            c["n"] += 1
            c["diff_pct"] = round(c["diff_pct"] + r["diff_pct"], 2)
            c["diff_rs"] = round(c["diff_rs"] + rs)
    taken = [r for r in rows if r["app"] == "taken"]
    return dict(research_trades=len(rows), research_sum_pct=round(sum(r["research_pct"] for r in rows), 2),
                paper_trades=len(taken) + len(extra),
                paper_sum_pct=round(sum(r["paper_pct"] for r in taken) + sum(p["pct"] for p in extra), 2),
                by_class=dict(cls), by_reason=dict(why), paper_only=len(extra))


def main(argv):
    folder, research, out = Path(argv[1]), Path(argv[2]), Path(argv[3]).resolve()
    if REPO in out.parents:
        sys.exit("refused: paper results must not be written inside the repository")
    days = r4paper.load_folder(folder)
    res = compare(days, json.loads(research.read_text())["trades"])
    out.write_text(json.dumps(res, indent=1))
    print(json.dumps(res["summary"], indent=1))


if __name__ == "__main__":
    main(sys.argv)
