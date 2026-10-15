"""R53: one BH step over every final-test p-value of all three books, the gates (PREREG section 3), and the tables of
the write-up. Reads results/{mcx,gold,crypto}_{design,final}.json (+ MA5u's files); writes results/r53_gates.json and
results/r53_tables.md.

    python -m research.hunt.r53.aggregate
"""
from __future__ import annotations

import json

from research.hunt.r53 import common as C

R = C.RESULTS


def _load(name):
    p = R / name
    return json.loads(p.read_text()) if p.exists() else None


def rows_mcx() -> list[dict]:
    out = []
    for fin_name, des_name, note in (("mcx_final.json", "mcx_design.json", ""),
                                     ("mcx_final_ma5u.json", "mcx_design_ma5u.json", "post-lock correction")):
        fin, des = _load(fin_name), _load(des_name)
        if not fin:
            continue
        for rid, r in fin["rules"].items():
            d = des["rules"].get(rid, {}) if des else {}
            wf = r.get("design_wf") or d.get("design_wf") or {}
            for var, s in r["variants"].items():
                dv = (d.get("variants") or {}).get(var, {})
                out.append({
                    "book": "mcx", "rule": rid, "variant": var, "note": note or (
                        "MA5 as run: first signal a day only (a deviation)" if rid == "MA5" else ""),
                    "is_pick": var != "orig",
                    "design": {k: dv.get(k) for k in ("trades", "net", "p", "win_rate")},
                    "wf_net": wf.get("orig_wf_net") if var == "orig" else wf.get("retune_wf_net"),
                    "final": {k: s.get(k) for k in ("trades", "win_rate", "net", "avg_win", "avg_loss", "worst_day",
                                                    "max_dd", "top5_share")},
                    "net_per_day": s.get("net_per_trading_day"),
                    "p": s.get("p"), "key_net": None, "money": "Rs",
                })
    return out


def rows_crypto() -> list[dict]:
    fin, des = _load("crypto_final.json"), _load("crypto_design.json")
    out = []
    if not fin:
        return out
    for rid, r in fin["rules"].items():
        d = des["rules"][rid]
        wf = d.get("walk_forward_delta_after_tax") or {}
        for var, s in r["variants"].items():
            dv = d["variants"].get(var, {})
            at = s["delta_after_tax"]
            out.append({
                "book": "crypto", "rule": rid, "variant": var, "note": r.get("name", ""),
                "is_pick": var != r.get("original"),
                "design": {"trades": dv.get("trades"), "net": (dv.get("delta_pre_tax") or {}).get("net"),
                           "net_after_tax": (dv.get("delta_after_tax") or {}).get("net"), "p": dv.get("p")},
                "wf_net": wf.get("orig_wf_net") if var == r.get("original") else wf.get("retune_wf_net"),
                "final": {**{k: at.get(k) for k in ("trades", "win_rate", "avg_win", "avg_loss", "worst_day", "max_dd",
                                                    "top5_share")},
                          "net": s["delta_pre_tax"]["net"], "net_after_tax": at["net"],
                          "cs_gst": [s["coinswitch_gst_pre_tax"]["net"], s["coinswitch_gst_after_tax"]["net"]],
                          "cs_nogst": [s["coinswitch_nogst_pre_tax"]["net"], s["coinswitch_nogst_after_tax"]["net"]],
                          "tds_locked": s.get("tds_locked")},
                "net_per_day": at.get("net_per_period_day"),
                "p": s.get("p"), "key_net": at["net"], "money": "Rs",
            })
    return out


def rows_gold() -> list[dict]:
    fin, des = _load("gold_final.json"), _load("gold_design.json")
    out = []
    if not fin:
        return out
    for rid, r in fin.items():
        if rid.startswith("_"):
            continue
        d = des.get(rid, {})
        for var, s in r["variants"].items():
            dv = (d.get("variants") or {}).get(var, {})
            ds = dv.get("summary") or {}
            out.append({
                "book": "gold", "rule": rid, "variant": var, "note": r.get("name", ""),
                "is_pick": var != "orig",
                "design": {"trades": ds.get("trades"), "net": ds.get("net"), "p": dv.get("p"),
                           "win_rate": ds.get("win_rate")},
                "wf_net": r.get("design_orig_wf_net") if var == "orig" else r.get("design_retune_wf_net"),
                "final": {k: s["summary"].get(k) for k in ("trades", "win_rate", "net", "avg_win", "avg_loss",
                                                           "worst_day", "max_dd", "top5_share")},
                "net_per_day": s.get("net_per_trading_day"),
                "p": s.get("p"), "key_net": None, "money": "USD/lot",
            })
    return out


def main():
    rows = rows_mcx() + rows_gold() + rows_crypto()
    key = lambda x: f"{x['book']}:{x['rule']}:{x['variant']}"  # noqa: E731
    q = C.bh({key(x): x["p"] for x in rows})
    for x in rows:
        x["q"] = q.get(key(x))
        g = C.gates(x["final"], x["p"], x["q"], x["wf_net"], key_net=x["key_net"])
        x.update(g)
        if x["note"] == "post-lock correction" and x["verdict"] == "PASS":
            x["verdict"] = "pass on numbers, but read after the lock: not a clean pass"
    C.save("r53_gates.json", {"n_final_tests": len(rows), "rows": rows})
    (R / "r53_tables.md").write_text(tables(rows))
    for x in rows:
        f = x["final"]
        print(f"{key(x):34} n={f['trades']:>4} net={f['net']:>12.0f} p={x['p']:.3f} q={x['q']:.3f} wf={x['wf_net']}"
              f" -> {x['verdict']}")


def _f(v, nd=0):
    if v is None:
        return "–"
    if isinstance(v, float):
        return f"{v:,.{nd}f}"
    return f"{v:,}" if isinstance(v, int) else str(v)


def tables(rows) -> str:
    L = []
    for book in ("mcx", "gold", "crypto"):
        rs = [x for x in rows if x["book"] == book]
        if not rs:
            continue
        L += [f"### {book}", ""]
        if book == "crypto":
            L += ["| rule:version | design tr / net before / after tax / p | WF net | final tr | win % | Delta before / after tax"
                  " | CoinSwitch GST before / after | CoinSwitch no GST before / after | after-tax net/day | avg win / loss"
                  " | worst day | max DD | p | q | verdict |", "|" + "---|" * 15]
            for x in rs:
                d, f = x["design"], x["final"]
                L.append(f"| {x['rule']}:{x['variant']} | {_f(d['trades'])} / {_f(d['net'])} / {_f(d['net_after_tax'])} /"
                         f" {_f(d['p'], 3)} | {_f(x['wf_net'])} | {_f(f['trades'])} | {_f(100 * (f['win_rate'] or 0), 0)} |"
                         f" {_f(f['net'])} / {_f(f['net_after_tax'])} | {_f(f['cs_gst'][0])} / {_f(f['cs_gst'][1])} |"
                         f" {_f(f['cs_nogst'][0])} / {_f(f['cs_nogst'][1])} | {_f(x['net_per_day'])} |"
                         f" {_f(f['avg_win'])} / {_f(f['avg_loss'])} | {_f(f['worst_day'])} | {_f(f['max_dd'])} |"
                         f" {_f(x['p'], 3)} | {_f(x['q'], 3)} | {x['verdict']} |")
        else:
            L += [f"| rule:version | design tr / net / p | WF net | final tr | win % | net ({rs[0]['money']}) | net/day |"
                  " avg win / loss | worst day | max DD | top-5 share | p | q | verdict |", "|" + "---|" * 14]
            for x in rs:
                d, f = x["design"], x["final"]
                L.append(f"| {x['rule']}:{x['variant']} | {_f(d['trades'])} / {_f(d['net'])} / {_f(d['p'], 3)} |"
                         f" {_f(x['wf_net'])} | {_f(f['trades'])} | {_f(100 * (f['win_rate'] or 0), 0)} | {_f(f['net'])} |"
                         f" {_f(x['net_per_day'])} | {_f(f['avg_win'])} / {_f(f['avg_loss'])} | {_f(f['worst_day'])} |"
                         f" {_f(f['max_dd'])} | {_f(f['top5_share'], 2)} | {_f(x['p'], 3)} | {_f(x['q'], 3)} |"
                         f" {x['verdict']} |")
        L.append("")
    return "\n".join(L)


if __name__ == "__main__":
    main()
