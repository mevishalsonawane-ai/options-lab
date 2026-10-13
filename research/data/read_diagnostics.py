"""Read the phone's daily diagnostics (TODO G3, 13 Oct 2026).

Each evening, after the MCX close, the phone uploads one small file per day to the private data repository
mevishalsonawane-ai/IraAlgo: release ``diag-YYYY-MM``, asset ``diag-YYYY-MM-DD.json.gz`` (gzip-compressed JSON; a re-run
replaces that day's file). The phone builds it in android/ira-core (DiagDay.kt) and redacts it before upload: no tokens,
keys, PINs, account or client ids, names, phone or e-mail, order ids, or rupee balances, margins or P&L. Arm P&L is in
points and percent of the premium. It is not sealed (nothing personal is in it), so no passphrase is needed.

Schema 1 (see MAINTENANCE.md, "Daily diagnostics"):

- ``schema``, ``kind`` ("iraalgo-diagnostics"), ``day``, ``generated_at``, ``app``, ``privacy``, ``mode`` (switches).
- ``arms``: one row per arm: ``key``, ``label``, ``state`` (armed / off / parked / awaiting_approval), ``automatic``,
  ``live_cleared``, ``parked_indices``, ``status`` and ``status_kind``; ``today`` (decisions, signals, taken, skipped,
  skips by reason kind, entries, exits, paper, live, wins, losses, points, avg_pct, sum_pct) and ``all_days`` (trades,
  since, wins, losses, points, avg_pct, sum_pct); ``trades`` (account paper/live, source, right, entry_at, entry,
  exit_at, exit, why, points, pct, lots, open, unconfirmed, trail).
- ``other_arms``: the other paper arms' one-line states.
- ``decisions_since`` and ``decisions``: at, arm, verdict, kind (the reason: guard, lock, day_lock, kill_switch, flow,
  liquidity, pretrade, event, cap, exposure, lapsed, ...), outcome (taken / skipped / waiting / holding / error), bar.
- ``orders`` (at, account, source, symbol, side, qty, filled, status, type, avg, message) and ``orders_by_source``.
- ``health``: errors by kind, unreadable failures, stream drops, named summary lines (order_speed, slippage, ...),
  workers, sync (failed, skipped, lines), diary_lines.
- ``timeline``: the Autopilot change timeline (at, source, agent, what, why, undo, scope, own: Boss's own switch).
- ``truncated``: how many items each long list lost to the size cap (the newest are kept).

Usage::

    python research/data/read_diagnostics.py diag-2026-10-13.json.gz          # a short summary
    python research/data/read_diagnostics.py --json diag-2026-10-13.json.gz   # the whole file, pretty
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
import sys
from collections import Counter
from pathlib import Path
from typing import Any

SCHEMA = 1
KIND = "iraalgo-diagnostics"
NAME = re.compile(r"^diag-(\d{4}-\d{2}-\d{2})\.json\.gz$")
TOP_KEYS = ("schema", "kind", "day", "generated_at", "app", "privacy", "mode", "arms", "other_arms", "decisions_since",
            "decisions", "orders", "orders_by_source", "health", "timeline", "truncated")
SKIP_OUTCOME = "skipped"

# What must never be in a file (the phone's scrub is tested; this re-checks every file research reads).
_LEAKS = [
    re.compile(r"github_pat_|gh[pousr]_[A-Za-z0-9]{8,}"),
    re.compile(r"eyJ[A-Za-z0-9_\-]{8,}"),
    re.compile(r"\d{9,}"),
    re.compile(r"(₹|\bRs\.?)\s*[-+]?\d"),
    re.compile(r"[\w.+-]+@[\w-]+\.[\w.-]+"),
]


class DiagError(ValueError):
    """The file is not a diagnostics file of a schema this reader knows."""


def day_of(name: str) -> str | None:
    """The day in an asset name (``diag-2026-10-13.json.gz`` -> ``2026-10-13``), or None."""
    m = NAME.match(Path(name).name)
    return m.group(1) if m else None


def loads(raw: bytes) -> dict[str, Any]:
    """A diagnostics file's bytes (gzip or plain JSON) as a dict, checked (:func:`check`)."""
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    data = json.loads(raw.decode("utf-8"))
    check(data)
    return data


def load(path: str | Path) -> dict[str, Any]:
    return loads(Path(path).read_bytes())


def check(data: Any) -> None:
    """Raises DiagError unless ``data`` is a schema-1 diagnostics report with nothing that looks secret."""
    if not isinstance(data, dict) or data.get("kind") != KIND:
        raise DiagError("not an IraAlgo diagnostics file")
    if data.get("schema") != SCHEMA:
        raise DiagError(f"schema {data.get('schema')!r}: this reader knows schema {SCHEMA}")
    missing = [k for k in TOP_KEYS if k not in data]
    if missing:
        raise DiagError(f"missing keys: {', '.join(missing)}")
    text = json.dumps(data, ensure_ascii=False)
    for rx in _LEAKS:
        if rx.search(text):
            raise DiagError(f"looks like a secret or a rupee figure ({rx.pattern}): not redacted")


def skips(data: dict[str, Any]) -> Counter:
    """Signals not taken, counted by (arm, reason kind) over the day's decisions."""
    return Counter((d["arm"], d["kind"]) for d in data["decisions"] if d["outcome"] == SKIP_OUTCOME)


def arm_rows(data: dict[str, Any]) -> list[dict[str, Any]]:
    """One flat row per arm: state, today's and all days' counts (points and percent only)."""
    rows = []
    for a in data["arms"]:
        t, al = a["today"], a["all_days"]
        rows.append({
            "day": data["day"], "arm": a["label"], "state": a["state"],
            "decisions": t["decisions"], "signals": t["signals"], "taken": t["taken"], "skipped": t["skipped"],
            "entries": t["entries"], "exits": t["exits"], "points_today": t["points"], "avg_pct_today": t["avg_pct"],
            "trades_all_days": al["trades"], "points_all_days": al["points"], "avg_pct_all_days": al["avg_pct"],
        })
    return rows


def summary(data: dict[str, Any]) -> str:
    """A few lines a research session can read at a glance."""
    out = [f"{data['day']} · app {data['app']} · generated {data['generated_at']}"]
    for r in arm_rows(data):
        pct = "-" if r["avg_pct_today"] is None else f"{r['avg_pct_today']:+.1f}%"
        out.append(f"  {r['arm']}: {r['state']} · today {r['signals']} signals, {r['taken']} taken, {r['skipped']} skipped, "
                   f"{r['exits']} closed, {r['points_today']:+.1f} pts ({pct}) · all days {r['trades_all_days']} trades")
    sk = skips(data)
    if sk:
        out.append("  skips: " + ", ".join(f"{arm} {kind} x{n}" for (arm, kind), n in sk.most_common(8)))
    h = data["health"]
    errors = sum(h["errors"].values())
    out.append(f"  health: {errors} errors in {len(h['errors'])} kinds · stream drops {h['stream']['drops']} · "
               f"sync failed {h['sync']['failed']}, skipped {h['sync']['skipped']}")
    out.append(f"  changes: {len(data['timeline'])} ({sum(1 for c in data['timeline'] if c['own'])} by Boss)")
    if data["truncated"]:
        out.append("  cut for size: " + ", ".join(f"{k} {v}" for k, v in sorted(data["truncated"].items())))
    return "\n".join(out)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("files", nargs="+")
    p.add_argument("--json", action="store_true", help="print the whole file, pretty")
    a = p.parse_args(argv)
    for f in a.files:
        try:
            data = load(f)
        except (DiagError, ValueError, OSError) as e:
            print(f"{f}: {e}", file=sys.stderr)
            return 1
        print(json.dumps(data, indent=2, ensure_ascii=False) if a.json else summary(data))
    return 0


if __name__ == "__main__":
    sys.exit(main())
