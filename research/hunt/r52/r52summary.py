"""results/*.json -> results/summary.md: one line per rule (OLD verdict vs NOW design vs FINAL test), for the write-up."""
from __future__ import annotations

import json

import r52cfg as K


def _r(x):
    return f"{x:+,.0f}"


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    old = json.loads((K.RESULTS / "old_verdicts.json").read_text())
    rows = ["| rule | signal source | OLD verdict | NOW design: original (trades, net, walk-forward) | re-tune pick (design net, WF) "
            "| FINAL original (trades, net, p, q) | FINAL pick (trades, net, p, q) | result |", "|---" * 8 + "|"]
    for key in sorted(d, key=lambda k: (-max(f.get(f"{k}:orig", {}).get("net", -1e9),
                                             f.get(f"{k}:{d[k]['pick']}", {}).get("net", -1e9)), k)):
        v = d[key]
        o, pk = f.get(f"{key}:orig"), f.get(f"{key}:{v['pick']}") if v["pick"] else None
        so = v["scores"]["orig"]
        base = key.split("@")[0].rstrip("N") if key.endswith("1N") else key.split("@")[0]
        ol = old.get(base, {}).get("old", "")
        ol = (ol[:90] + "...") if len(ol) > 93 else ol
        verdicts = [x["gates"]["verdict"] for x in (o, pk) if x]
        res = "PASS" if "PASS" in verdicts else ("FAIL" if "FAIL" in verdicts else "too few trades")
        if res != "PASS":
            for x in (o, pk):
                g = x["gates"] if x else None
                if g and all(g[c] for c in ("final_net", "trades", "top5", "design_wf")) and x["p_flip"] < 0.05:
                    res = "FAIL (fails only the same-side twin test)"
        pick = (f"`{v['pick']}` {_r(v['scores'][v['pick']]['net'])}, WF {_r(v['wf_each'][v['pick']])}" if v["pick"]
                else "none with WF > 0")
        fo = f"{o['trades']}, {_r(o['net'])}, {o['p']:.2f}, {o['q']:.2f}" if o else "-"
        fp = f"{pk['trades']}, {_r(pk['net'])}, {pk['p']:.2f}, {pk['q']:.2f}" if pk else "-"
        rows.append(f"| {key} | {v['spec'].get('source')} | {ol} | {so['trades']}, {_r(so['net'])}, WF {_r(v['wf_orig']['net'])} "
                    f"| {pick} | {fo} | {fp} | {res} |")
    (K.RESULTS / "summary.md").write_text("\n".join(rows) + "\n")
    print(len(rows) - 2, "rules")


if __name__ == "__main__":
    main()
