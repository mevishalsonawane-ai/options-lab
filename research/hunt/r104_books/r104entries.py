"""R104 bookkeeping: every book-*.yaml experiment entry gets its R104 outcome (tested / covered / lacks data), new
entries for the Amendment 1 strategies, and a "Tested (R104)" line per rule in each book's write-up.

    python research/hunt/r104_books/r104entries.py      # after r104final.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402
import r104rules as RR  # noqa: E402

ROOT = HERE.parents[2]
ENT = ROOT / "research" / "experiments" / "entries"
BOOKS = ROOT / "research" / "books"
DOC = "research/HUNT_R104_BOOKS.md"
PREREG = "research/hunt/r104_books/PREREG.md"
SIGNAL = {"IDX": "index spot, bought option", "IDXF": "index spot, futures proxy", "DLY": "index daily, futures proxy",
          "MCXI": "MCX mini future (own bars)", "MCXD": "MCX future daily", "CRY": "BTC/ETH perp",
          "STK": "stock daily, futures proxy", "ARM": "the arm's index signal (overlay / filter)",
          "MM": "the arms' trades (sizing)", "VOL": "option structure (chain as context)"}


def rs(x):
    if x is None:
        return "-"
    s = f"{abs(x):,.0f}"
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x):
    return "-" if x is None else f"{x:.0%}"


def line(r):
    mk = r["market"]
    h = r.get("holdout") or {}
    if mk == "ARM":
        d = (f"design {r['n']} trades, win {pct(r.get('win'))} (B0 {pct(r.get('b0_win'))}), net {rs(r['net'])} vs B0 "
             f"{rs(r['b0_net'])} (delta {rs(r['delta'])}), quarters {', '.join(rs(x) for x in r['folds'])}")
        hh = f"; final delta {rs(h.get('delta'))}" if h else ""
    elif mk == "MM":
        d = (f"design net {rs(r['net'])} vs {rs(r['b0_net'])}, max drawdown {rs(r['maxdd'])} vs {rs(r['b0_maxdd'])} "
             f"(cut {r['dd_cut']:.0%}, net cut {r['net_cut']:.0%}){' - adds risk' if r.get('adds_risk') else ''}")
        hh = (f"; final DD cut {h['dd_cut']:.0%}, net cut {h['net_cut']:.0%}" if h else "")
    else:
        d = (f"design {r['n']} trades, win {pct(r.get('win'))}, net {rs(r['net'])}, folds "
             f"{', '.join(rs(x) for x in r['folds'])}")
        if r.get("cmp"):
            d += f", vs host {rs(r['cmp']['host_net'])} (delta {rs(r['cmp']['delta'])})"
        hh = f"; holdout {h.get('n')} trades, win {pct(h.get('win'))}, net {rs(h.get('net'))}" if h else ""
    tw = f"; twin p {r['twin']['p']:.3f}" if r.get("twin") else ""
    verdict = "PASS" if r["final_pass"] else "fail"
    return (f"{mk} ({SIGNAL[mk]}), variant {r.get('variant')}: {d}; p {r['p']:.3f}, BH q {r['q']:.3f}{tw}{hh}. "
            f"R104 verdict: {verdict}.")


def update_entries(final):
    by_rule = {}
    for r in final:
        by_rule.setdefault(r["rule"], []).append(r)
    n_tests = len(final)
    for rid in RR.R:
        st, fam, mk, why = RR.R[rid]
        p = ENT / f"{rid}.yaml"
        if rid.startswith("new-"):
            e = dict(id=f"r104-{rid.lower()}", title=f"R104 new strategy {rid[4:]}: {why}", family="patterns-indicators"
                     if "Donchian" in why else ("sizing-execution" if rid == "new-N4" else
                                                ("option-structure" if rid == "new-N6" else "daily-filters")),
                     date="2026-10-25", doc=DOC, hypothesis="see PREREG.md Amendment 1, row " + rid[4:],
                     data_version="Drive lake copied 25 Oct 2026 (R101 arm stores, R103 option stores)",
                     period=dict(design="R101 design 20 Nov 2024 - 31 Mar 2026" if "VOL" not in mk else
                                 "2 Aug 2021 - 31 Mar 2026", holdout="read once (R104)"),
                     instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], features=[why],
                     model_or_rule=why, settings="research/hunt/r104_books/f_new.py", prereg=PREREG, variants_tried=1)
            p = ENT / f"r104-{rid.lower()}.yaml"
        else:
            e = yaml.safe_load(p.read_text())
        rows = by_rule.get(rid, [])
        if st == "test" and rows:
            e["doc"] = DOC
            e["prereg"] = PREREG
            e["data_version"] = "Drive lake copied 25 Oct 2026 (index_1m, options_rolling_1m, index_daily, fut_*, crypto)"
            design = "; ".join(f"{r['market']} {K.PERIODS[r['market']]['design'][0]} - "
                                              f"{K.PERIODS[r['market']]['design'][1]}" for r in rows)
            e["period"] = {"design": design,
                           "holdout": "; ".join(f"{r['market']} {K.PERIODS[r['market']]['holdout'][0]} - "
                                                f"{K.PERIODS[r['market']]['holdout'][1]} (read once)" for r in rows)}
            e["variants_tried"] = int(sum(_n_variants(r) for r in rows))
            e["results"] = dict(summary=" | ".join(line(r) for r in rows),
                                costs="dated charges and liquidity slippage (PREREG.md section 5): options R51 liq fills "
                                      "and dated charges; futures proxies dated futures charges, half-spreads, carry; "
                                      "MCX R94 charges; crypto fees + GST")
            e["holdout_read"] = True
            # the books' write-up titled each rule "(..., book rule, proposed)"; once run it is a tested rule
            e["title"] = str(e.get("title", "")).replace("book rule, proposed)", "book rule, tested in R104)")
            e["verdict"] = "paper-only" if any(r["final_pass"] for r in rows) else "rejected"
            prior = "" if rid.startswith("new-") else _prior_notes(e.get("notes", ""), why)
            e["notes"] = (f"R104 ({n_tests} tests, BH across all). " + (why or "") + (" " + prior if prior else ""))[:3000]
        elif st == "covered":
            e["verdict"] = "not-a-test"
            e["title"] = str(e.get("title", "")).replace("book rule, proposed)", "book rule, covered by an earlier study)")
            e["results"] = dict(summary=f"Not re-run in R104: covered by an earlier study. {why}",
                                costs=e.get("results", {}).get("costs", "n/a"))
            e["notes"] = f"R104: covered (no BH budget spent). {why}"
        elif st == "lack":
            e["results"] = dict(summary=f"Not testable in R104: {why}", costs=e.get("results", {}).get("costs", "n/a"))
            e["notes"] = f"R104: needs data we lack ({why})."
        elif st == "desc":
            e["verdict"] = "not-a-test"
            e["title"] = str(e.get("title", "")).replace("book rule, proposed)", "book question, answered in R104)")
            e["results"] = dict(summary="R104: descriptive question only (no trade); see HUNT_R104_BOOKS.md",
                                costs="n/a")
        p.write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


def _prior_notes(notes, why):
    """The entry's notes from before R104: re-runs strip the "R104 (...)" text an earlier run put in front."""
    notes = str(notes or "")
    head = re.compile(r"^R104 \(\d+ tests, BH across all\)\. " + re.escape(why or "") + r"\s*")
    while head.match(notes):
        notes = head.sub("", notes, count=1)
    return notes.strip()


_DESIGN = None


def _n_variants(r):
    """Variants tried for one rule-by-market test, from the design results (walk-forward picked among them)."""
    global _DESIGN
    if _DESIGN is None:
        _DESIGN = {}
        for f in sorted(K.RESULTS.glob("design_*.json")):
            _DESIGN.update(json.loads(f.read_text()))
    d = _DESIGN.get(f"{r['rule']}|{r['market']}") or {}
    v = d.get("variants")
    return max(1, len(v)) if isinstance(v, (dict, list)) else 1


def book_lines(final):
    """{rule: one short 'tested: result' line}."""
    by_rule = {}
    for r in final:
        by_rule.setdefault(r["rule"], []).append(r)
    out = {}
    for rid, (st, fam, mk, why) in RR.R.items():
        if not rid.startswith("book-"):
            continue
        if st == "test":
            parts = []
            for r in by_rule.get(rid, []):
                h = r.get("holdout") or {}
                if r["market"] == "ARM":
                    parts.append(f"{r['market']}: delta vs the arm {rs(r['delta'])} design")
                elif r["market"] == "MM":
                    parts.append(f"MM: drawdown cut {r['dd_cut']:.0%}, net cut {r['net_cut']:.0%} design")
                else:
                    parts.append(f"{r['market']}: {r['n']} trades, win {pct(r.get('win'))}, net {rs(r['net'])} design, "
                                 f"holdout {rs(h.get('net'))}")
                parts[-1] += " (PASS)" if r["final_pass"] else " (fail)"
            out[rid] = "tested (R104): " + "; ".join(parts)
        elif st == "covered":
            out[rid] = f"not re-run (R104): covered: {why}"
        elif st == "lack":
            out[rid] = f"not testable (R104): {why}"
        else:
            out[rid] = "descriptive only (R104)"
    return out


def update_books(final):
    lines = book_lines(final)
    # rule -> its book's write-up file, from the "([write-up](x.md))" section headings of TESTABLE_RULES.md
    book_file, cur = {}, None
    for ln in (BOOKS / "TESTABLE_RULES.md").read_text().splitlines():
        m = re.search(r"\(\[write-up\]\(([^)]+\.md)\)\)", ln)
        if ln.startswith("## ") and m:
            cur = m.group(1)
        m2 = re.match(r"### `([^`]+)`", ln)
        if m2:
            book_file[m2.group(1)] = cur
    for f in sorted(BOOKS.glob("*.md")):
        if f.name in ("README.md", "TESTABLE_RULES.md"):
            continue
        t = f.read_text()
        ids = [rid for rid in lines if f"`{rid}`" in t or book_file.get(rid) == f.name]
        if not ids:
            continue
        t = re.sub(r"\n## R104 results \(25 Oct 2026\).*\Z", "", t, flags=re.S)
        add = ["", "## R104 results (25 Oct 2026)", "",
               "Every testable rule of this book was run in R104 (`research/HUNT_R104_BOOKS.md`; pre-registered in "
               "`research/hunt/r104_books/PREREG.md`). Net after real charges and slippage, 1 lot / 1 unit:", ""]
        add += [f"- `{rid}`: {lines[rid]}" for rid in ids]
        f.write_text(t.rstrip("\n") + "\n" + "\n".join(add) + "\n")
    tr = BOOKS / "TESTABLE_RULES.md"
    t = tr.read_text()
    for rid, ln in lines.items():
        pat = re.compile(r"(### `" + re.escape(rid) + r"`[^\n]*\n(?:(?!### ).*\n)*?)(- R104: [^\n]*\n)?(?=### |\n## |\Z)")
        t = pat.sub(lambda m: m.group(1).rstrip("\n") + "\n" + f"- R104: {ln}\n", t, count=1)
    tr.write_text(t)


if __name__ == "__main__":
    final = json.loads((K.RESULTS / "final.json").read_text())
    update_entries(final)
    update_books(final)
    print("entries and book notes updated")
