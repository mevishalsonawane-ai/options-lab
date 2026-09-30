"""Writes CATALOG.md from the registry: python -m indicator.make_catalog"""
import os

from indicator import catalog

cat = catalog()
lines = ["# Indicator catalog", "",
         f"{len(cat)} indicators, each written from its author's published formula (see the source for the exact "
         "definition used where platforms differ). Import with `import indicator as ind` and call `ind.<Name>(df, ...)` "
         "on a DataFrame with open, high, low, close (and volume where marked).", ""]
for c, g in cat.groupby("category", sort=False):
    lines += [f"## {c} ({len(g)})", "", "| name | what it is | needs volume |", "|---|---|---|"]
    lines += [f"| `{r['name']}` | {r['what it is']} | {'yes' if r['needs volume'] else ''} |" for _, r in g.iterrows()]
    lines.append("")
open(os.path.join(os.path.dirname(__file__), "CATALOG.md"), "w").write("\n".join(lines))
print(len(cat), "indicators")
