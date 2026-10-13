#!/usr/bin/env python3
"""Imports the phone's nightly "lessons bundle" into research/app_lessons/ as a summary (rates, counts and effects only).

    python research/knowledge/import_app_lessons.py EXPORT.zip          # the PIN-confirmed "Export recordings" zip
    python research/knowledge/import_app_lessons.py lessons_bundle.json # or one bundle file taken out of it
    python research/knowledge/import_app_lessons.py EXPORT.zip --no-experiments   # summaries only, no experiment-log entries

13 Oct 2026 (Boss: "the findings from the app should go back to the research"). The phone writes one bundle a night
(recordings/YYYY-MM-DD/lessons_bundle.json, ira-core LessonsBundle): Jarvis's lessons, a summary of the findings log, the
strategy lab's ideas and variants, the order review's recurring flaws and the trade manager's vote outcomes. Boss's money
figures travel in it only encrypted with the app's Vault ("money_enc"), and decrypted ("money") only in his PIN-confirmed
export.

The rule this script keeps, checked by test_import_app_lessons.py: NOTHING of Boss's money is written to the repo. Only
these survive - counts (n, hits, tried, passed, days), rates (hits / n) and unitless effects (the rate minus a half, the
sign) - plus names, tags and dates. The "money" / "money_enc" sections, every rupee field (rs, net, pnl, rupees, charges,
slippage, money), and any text that quotes rupees ("Rs 1,234", "₹") are dropped. A bundle is matched by its day: importing
the same day again replaces that day's summary.

Output (one pair a bundle day):
  research/app_lessons/YYYY-MM-DD.json  the summary build_pack.py reads (kind "app_lesson" facts and shared-knowledge items)
  research/app_lessons/YYYY-MM-DD.md    a short readable page (the experiment entries' `doc`)
and, unless --no-experiments, research/experiments/entries/app-lab-*.yaml: one experiment-log entry per strategy-lab idea
family the phone tested (the lab's "lab:" lessons), so the multiple-testing count includes the phone's own tries.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "research/app_lessons"
ENTRIES = ROOT / "research/experiments/entries"
SCHEMA = 1
BUNDLE_KIND = "iraalgo-lessons-bundle"
SUMMARY_KIND = "iraalgo-app-lessons"
BUNDLE_NAME = "lessons_bundle.json"

# Keys that hold Boss's money, anywhere in the bundle: never copied.
MONEY_KEYS = {"money", "money_enc", "rs", "net", "pnl", "p&l", "rupees", "charges", "slippage", "gross", "capital", "equity",
              "balance"}
# Text that quotes rupees: never copied.
MONEY_TEXT = re.compile(r"(?i)(\brs\.?\s*[-+]?\d|₹|\binr\b|\blakh\b|\bcrore\b)")

# The strategy lab's block or filter -> the experiment log's family (research/experiments/validate.py FAMILIES).
LAB_FAMILY = {
    "orb_break": "orb", "orb_sweep": "orb", "range_fade": "orb", "level_sweep": "liquidity", "oi_buildup": "flow-oi",
    "vix_divergence": "patterns-indicators", "vwap_reclaim": "patterns-indicators", "gap_fade": "patterns-indicators",
    "time_of_day": "patterns-indicators", "prev_day_break": "patterns-indicators", "squeeze_break": "patterns-indicators",
    "expiry_only": "calendar-events", "skip_expiry": "calendar-events", "vix_low": "daily-filters", "vix_high": "daily-filters",
    "busy_only": "daily-filters", "quiet_only": "daily-filters",
}


# ---- reading --------------------------------------------------------------------------------------------------------

def read_bundles(path: Path) -> list[dict]:
    """Every bundle in [path]: a zip (each day's lessons_bundle.json) or one JSON file."""
    if path.suffix.lower() == ".zip":
        out = []
        with zipfile.ZipFile(path) as z:
            for name in sorted(z.namelist()):
                if name.rsplit("/", 1)[-1] == BUNDLE_NAME:
                    out.append(json.load(io.TextIOWrapper(z.open(name), encoding="utf-8")))
        return out
    return [json.loads(path.read_text(encoding="utf-8"))]


# ---- the rule: rates, counts and effects only ------------------------------------------------------------------------

def clean_text(s) -> str:
    """A name or tag as kept: plain, short, and never one that quotes rupees (dropped to "")."""
    if not isinstance(s, str):
        return ""
    s = " ".join(s.split())[:160]
    return "" if MONEY_TEXT.search(s) else s


def count(x) -> int:
    try:
        v = int(x)
    except (TypeError, ValueError):
        return 0
    return max(v, 0)


def rate(hits: int, n: int) -> float | None:
    return round(hits / n, 4) if n > 0 else None


def effect(hits: int, n: int) -> float | None:
    """The unitless effect: the hit rate minus a half (positive: the thing happened / won more often than not)."""
    r = rate(hits, n)
    return None if r is None else round(r - 0.5, 4)


def summarize(b: dict) -> dict:
    """The summary of one bundle: only the allowed fields, money dropped."""
    if b.get("kind") != BUNDLE_KIND:
        raise ValueError(f"not a lessons bundle (kind {b.get('kind')!r})")
    if int(b.get("schema", 0)) != SCHEMA:
        raise ValueError(f"bundle schema {b.get('schema')}, this importer reads {SCHEMA}")
    day = str(b.get("day", ""))
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", day):
        raise ValueError(f"bundle day {day!r} is not a date")
    lessons = []
    for l in b.get("lessons", []):
        n, hits = count(l.get("n")), count(l.get("hits"))
        name = clean_text(l.get("name"))
        if not name or n == 0:
            continue
        lessons.append({
            "id": clean_text(l.get("id")), "topic": clean_text(l.get("topic")), "kind": clean_text(l.get("kind")), "name": name,
            "tags": [t for t in (clean_text(x) for x in l.get("tags", [])) if t][:12],
            "agents": [t for t in (clean_text(x) for x in l.get("agents", [])) if t][:8],
            "n": n, "hits": min(hits, n), "rate": rate(min(hits, n), n), "effect": effect(min(hits, n), n),
            "sign": max(-1, min(1, int(l.get("sign", 0) or 0))), "confidence": clean_text(l.get("confidence")),
            "status": clean_text(l.get("status")), "q": l.get("q") if isinstance(l.get("q"), (int, float)) else None,
            "first": clean_text(l.get("first")), "last": clean_text(l.get("last")),
        })
    findings = [{"kind": clean_text(f.get("kind")), "who": clean_text(f.get("who")), "count": count(f.get("count")),
                 "days": count(f.get("days"))} for f in b.get("findings", []) if count(f.get("count")) > 0]
    lab = b.get("lab", {}) or {}
    forge = {k: count(v) for k, v in (lab.get("forge", {}) or {}).items() if k in
             ("tried", "passed", "forward", "proposed", "retired", "rejected", "paper", "skipped")}
    ideas = []
    for i in lab.get("ideas", []):
        ideas.append({"id": clean_text(i.get("id")), "entry": clean_text(i.get("entry")),
                      "filters": [clean_text(x) for x in i.get("filters", [])], "exit": clean_text(i.get("exit")),
                      "underlying": clean_text(i.get("underlying")), "stage": clean_text(i.get("stage")),
                      "gate_failed": clean_text(i.get("gate_failed")), "trades": count(i.get("trades")),
                      "win_rate": i.get("win_rate") if isinstance(i.get("win_rate"), (int, float)) else None,
                      "twin_p": i.get("twin_p") if isinstance(i.get("twin_p"), (int, float)) else None})
    skipped = [{"idea": clean_text(s.get("idea")), "why": clean_text(s.get("why"))} for s in lab.get("skipped", [])]
    variants = [{"strategy": clean_text(v.get("strategy")), "status": clean_text(v.get("status")), "n": count(v.get("n")),
                 "better": v.get("better") if isinstance(v.get("better"), bool) else None} for v in lab.get("variants", [])]
    orders = [{"strategy": clean_text(o.get("strategy")), "venue": clean_text(o.get("venue")), "category": clean_text(o.get("category")),
               "count": count(o.get("count")), "days": count(o.get("days"))} for o in (b.get("orders", {}) or {}).get("patterns", [])]
    votes = []
    for v in b.get("votes", []):
        settled, helped = count(v.get("settled")), count(v.get("helped"))
        if settled == 0:
            continue
        votes.append({"family": clean_text(v.get("family")), "specialist": clean_text(v.get("specialist")), "settled": settled,
                      "helped": min(helped, settled), "rate": rate(min(helped, settled), settled)})
    shadow = [{"rule": clean_text(s.get("rule")), "arm": clean_text(s.get("arm")), "would_skip": count(s.get("would_skip")),
               "trades": count(s.get("trades"))} for s in b.get("shadow", [])]
    return {
        "schema": SCHEMA, "kind": SUMMARY_KIND, "day": day, "app": clean_text(b.get("app")),
        "pack_version": clean_text(b.get("pack_version")),
        "rule": "rates, counts and effects only - no money (import_app_lessons.py)",
        "lessons": lessons, "findings": findings, "forge": forge, "ideas": ideas, "skipped": skipped, "variants": variants,
        "orders": orders, "votes": votes, "shadow": shadow,
    }


def money_free(o, path: str = "") -> list[str]:
    """Where [o] still holds money (a money key or rupee text); empty when clean. The tests and main() both check it."""
    bad = []
    if isinstance(o, dict):
        for k, v in o.items():
            if str(k).lower() in MONEY_KEYS:
                bad.append(f"{path}/{k}")
            bad += money_free(v, f"{path}/{k}")
    elif isinstance(o, list):
        for i, v in enumerate(o):
            bad += money_free(v, f"{path}[{i}]")
    elif isinstance(o, str) and MONEY_TEXT.search(o):
        bad.append(f"{path}: {o[:40]!r}")
    return bad


# ---- writing --------------------------------------------------------------------------------------------------------

def page(s: dict) -> str:
    lines = [f"# App lessons, {s['day']}", "",
             f"Imported from the phone's nightly lessons bundle (app {s['app'] or '?'}, knowledge pack {s['pack_version'] or '?'}).",
             "Rates, counts and effects only: Boss's money figures never leave the phone's encrypted bundle.", ""]
    if s["lessons"]:
        lines += ["## Jarvis's lessons (strong and building)", ""]
        for l in s["lessons"]:
            if l["confidence"] in ("strong", "building"):
                lines.append(f"- {l['name']}: {l['hits']} of {l['n']} ({l['confidence']}, {l['topic']})")
        lines.append("")
    f = s["forge"]
    if f:
        lines += ["## Strategy lab", "", "- Forge: " + ", ".join(f"{k} {v}" for k, v in sorted(f.items())), ""]
    if s["orders"]:
        lines += ["## Order review: recurring flaws", ""]
        lines += [f"- {o['strategy']} ({o['venue']}): {o['category']} {o['count']} times on {o['days']} days" for o in s["orders"]]
        lines.append("")
    if s["votes"]:
        lines += ["## Trade manager: specialists' settled votes", ""]
        lines += [f"- {v['specialist']} on {v['family']}: helped {v['helped']} of {v['settled']}" for v in s["votes"]]
        lines.append("")
    return "\n".join(lines) + "\n"


def slug(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")[:60]


def experiment_entries(s: dict) -> list[dict]:
    """One experiment-log entry per strategy-lab idea family tested on the phone ("lab:" lessons): its tries count."""
    out = []
    for l in s["lessons"]:
        lid = l["id"]
        if not lid.startswith("lab:"):
            continue
        key = lid.split(":")[-1]
        family = LAB_FAMILY.get(key.lower(), "patterns-indicators")
        strong = l["confidence"] == "strong"
        verdict = ("shadow" if l["sign"] > 0 else "rejected") if strong else "inconclusive"
        out.append({
            "id": f"app-lab-{slug(lid)}", "title": f"Phone strategy lab: {l['name']}", "family": family, "date": s["day"],
            "doc": f"research/app_lessons/{s['day']}.md",
            "hypothesis": f"{l['name']} passes the forge's gates (walk-forward, random twin, false-discovery check) more often than not.",
            "data_version": f"the phone's stored days (lessons bundle {s['day']}, app {s['app'] or '?'})",
            "period": {"design": f"{l['first'] or '?'} .. {l['last'] or s['day']}"},
            "instruments": ["BANKNIFTY"], "features": [t for t in l["tags"] if t not in ("lab", "forge")][:6] or ["strategy-lab block"],
            "model_or_rule": "StrategyForge / VariantLab on the phone", "settings": "the phone's strategy lab (ira-core StrategyForge)",
            "prereg": None, "variants_tried": l["n"],
            "results": {"summary": f"passed {l['hits']} of {l['n']} lab tests ({l['confidence']}).",
                        "costs": "paper costs plus half a spread (the forge's backtest)"},
            "holdout_read": None, "verdict": verdict, "backfilled": False,
            "notes": "Written by research/knowledge/import_app_lessons.py from the phone's lessons; refreshed on each import.",
        })
    return out


def write(s: dict, *, experiments: bool = True, out: Path = OUT, entries: Path = ENTRIES) -> list[Path]:
    bad = money_free(s)
    if bad:
        raise ValueError("money found in the summary (refusing to write): " + "; ".join(bad[:5]))
    out.mkdir(parents=True, exist_ok=True)
    j = out / f"{s['day']}.json"
    j.write_text(json.dumps(s, indent=1, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
    m = out / f"{s['day']}.md"
    m.write_text(page(s), encoding="utf-8")
    written = [j, m]
    if experiments:
        entries.mkdir(parents=True, exist_ok=True)
        for e in experiment_entries(s):
            if money_free({k: v for k, v in e.items() if k != "results"}) or money_free(e["results"]["summary"]):
                continue
            p = entries / f"{e['id']}.yaml"
            p.write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True), encoding="utf-8")
            written.append(p)
    return written


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("path", type=Path, help="the decrypted export zip, or one lessons_bundle.json")
    ap.add_argument("--no-experiments", action="store_true", help="write the summaries only")
    ap.add_argument("--out", type=Path, default=OUT, help=argparse.SUPPRESS)
    ap.add_argument("--entries", type=Path, default=ENTRIES, help=argparse.SUPPRESS)
    args = ap.parse_args(argv)
    bundles = read_bundles(args.path)
    if not bundles:
        print(f"no {BUNDLE_NAME} in {args.path}", file=sys.stderr)
        return 1
    for b in bundles:
        s = summarize(b)
        for p in write(s, experiments=not args.no_experiments, out=args.out, entries=args.entries):
            print(f"wrote {p.relative_to(ROOT) if p.is_relative_to(ROOT) else p}")
    print("next: python research/experiments/validate.py && python research/knowledge/build_pack.py")
    return 0


if __name__ == "__main__":
    sys.exit(main())
