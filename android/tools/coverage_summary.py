#!/usr/bin/env python3
"""Print a JaCoCo XML report as a Markdown summary: totals, then line coverage per package.

Usage: coverage_summary.py report.xml   (a missing report prints a note and exits 0, so a
failed test run still shows its real error rather than this one's).
"""
import sys
import xml.etree.ElementTree as ET


def counters(node):
    return {c.get("type"): (int(c.get("covered")), int(c.get("missed"))) for c in node.findall("counter")}


def pct(cov_miss):
    cov, miss = cov_miss
    return f"{100.0 * cov / (cov + miss):.1f}%" if cov + miss else "n/a"


def main(path):
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
    main(sys.argv[1] if len(sys.argv) > 1 else "app/build/reports/coverage/test/debug/report.xml")
