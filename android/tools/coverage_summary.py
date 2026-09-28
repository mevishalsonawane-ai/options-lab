#!/usr/bin/env python3
"""Print a JaCoCo XML report as a Markdown summary: totals, then line coverage per package.

Usage: coverage_summary.py report.xml [junit-results-dir]   (a missing report prints a note and exits 0, so a
failed test run still shows its real error rather than this one's).
"""
import sys
import xml.etree.ElementTree as ET


def counters(node):
    return {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in node.findall("counter")}


def pct(cov_miss):
    cov, miss = cov_miss
    return f"{100.0 * cov / (cov + miss):.1f}%" if cov + miss else "n/a"


def test_counts(results_dir):
    """Totals from the JUnit XML files Gradle writes (one per test class)."""
    import glob
    import os
    n = fail = skip = 0
    failed, skipped = [], []
    for f in sorted(glob.glob(os.path.join(results_dir, "*.xml"))):
        try:
            r = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        n += int(r.get("tests", 0))
        fail += int(r.get("failures", 0)) + int(r.get("errors", 0))
        skip += int(r.get("skipped", 0))
        for case in r.findall("testcase"):
            name = case.get("classname", "").split(".")[-1] + "." + case.get("name", "")
            for bad in case.findall("failure") + case.findall("error"):
                msg = (bad.get("message") or bad.text or "").strip().replace("\n", " | ")
                failed.append(f"{name}: {msg[:400]}")
            for sk in case.findall("skipped"):
                msg = (sk.get("message") or sk.text or "").strip().split("\n")[0]
                skipped.append(f"{name}: {msg[:200]}")
    print(f"**Tests:** {n} run, {fail} failed, {skip} skipped\n")
    for line in failed[:150]:
        print(f"- FAILED {line}")
    for line in skipped[:150]:
        print(f"- SKIPPED {line}")
    if failed or skipped:
        print()


def main(path, results_dir=None):
    if results_dir:
        test_counts(results_dir)
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        print(f"No coverage report at {path} ({e.__class__.__name__}).")
        return
    total = counters(root)
    print("### App unit-test coverage (JaCoCo)\n")
    print("| Counter | Covered | Total | % |\n|---|---:|---:|---:|")
    for kind in ("LINE", "BRANCH", "INSTRUCTION", "METHOD", "CLASS"):
        if kind in total:
            cov, miss = total[kind]
            print(f"| {kind.lower()} | {cov} | {cov + miss} | {pct(total[kind])} |")
    print("\n| Package | Lines | Line % | Branch % |\n|---|---:|---:|---:|")
    rows = []
    for pkg in root.findall("package"):
        c = counters(pkg)
        line = c.get("LINE", (0, 0))
        rows.append((pkg.get("name").replace("/", "."), sum(line), pct(line), pct(c.get("BRANCH", (0, 0)))))
    for name, n, lp, bp in sorted(rows):
        print(f"| {name} | {n} | {lp} | {bp} |")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/coverage/test/debug/report.xml",
         sys.argv[2] if len(sys.argv) > 2 else None)
