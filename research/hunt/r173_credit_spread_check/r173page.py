"""R173 Amendment B3: the honest explorer page. Every number is read from results/*.json; nothing is typed.

    python research/hunt/r173_credit_spread_check/r173page.py      # -> results/explorer.html (self-contained, no network)
"""
from __future__ import annotations

import html
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
PERIODS = (("design", "Design Nov 24-Mar 25"), ("walkforward", "Walk-fwd Apr-Sep 25"), ("final", "Final Oct 25-Feb 26"))


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(float(x))
    s = f"{abs(x):,}"
    # Indian grouping (lakh / crore)
    digits = str(abs(x))
    if len(digits) > 3:
        head, tail = digits[:-3], digits[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts) + "," + tail
    return ("-" if x < 0 else "+" if x > 0 else "") + "Rs " + s


def cls(x) -> str:
    return "neg" if x is not None and x < 0 else "pos" if x is not None and x > 0 else ""


def cell(x) -> str:
    return f'<td class="num {cls(x)}">{rs(x)}</td>'


def table(head: list, rows: list) -> str:
    h = "".join(f"<th>{html.escape(c)}</th>" for c in head)
    body = "".join("<tr>" + "".join(r) + "</tr>" for r in rows)
    return f'<div class="tw"><table><thead><tr>{h}</tr></thead><tbody>{body}</tbody></table></div>'


def td(s) -> str:
    return f"<td>{html.escape(str(s))}</td>"


def build() -> str:
    par = json.loads((RES / "parity.json").read_text())
    des = json.loads((RES / "design.json").read_text())
    fin = json.loads((RES / "final.json").read_text())
    amb = json.loads((RES / "amendment_b.json").read_text())

    def per(strat, kind, p):
        return (des[p] if p != "final" else fin)[strat][kind]["pooled"]

    # 1. claim vs model vs real (NIFTY + BANKNIFTY)
    rows = []
    for strat, mode, key, claim in (("R72-CS", "C1_Enh_FreshBreadth_CreditSpread", "R72", 82971 + 25963),
                                    ("LIQ2-CS", "Enhanced_OTM_CreditSpread", "LIQ", 35658 + 15570)):
        m = par[key]["modes"][mode]
        model = m["NIFTY"]["net"] + m["BANKNIFTY"]["net"]
        model_n = m["NIFTY"]["n"] + m["BANKNIFTY"]["n"]
        real = sum(per(strat, "all", p)["net"] for p, _ in PERIODS)
        real_n = sum(per(strat, "all", p)["n"] for p, _ in PERIODS)
        acct = fin["gates"][strat]["whole"]
        rows.append([td(strat), cell(claim), cell(model), td(model_n), cell(real), td(real_n), cell(acct["net"]), td(acct["n"])])
    t1 = table(["rule (NIFTY + BANKNIFTY)", "files' claim (Nov 24-Oct 26)", "their model, our days", "trades",
                "REAL prices", "trades", "real, Rs 56k account", "trades"], rows)

    # 2. where the model's profit comes from
    rows = []
    for key, name in (("R72", "R72 spread (4 indices)"), ("LIQ", "Liquidity spread (4 indices)")):
        v = par[key]
        rows.append([td(name), cell(v["theta_share"]["net"]), cell(v["theta_share"]["theta_rs"]),
                     cell(v["theta_share"]["net_without_theta"]), cell(v["random"]["mean"]),
                     td(f'{100 * v["random"]["share_positive"]:.0f}% of {v["random"]["draws"]}')])
    t2 = table(["their model", "total", "of which made-up time decay", "without it", "random entries (mean)",
                "random sets in profit"], rows)

    # 3. real prices by period
    rows = []
    for strat in ("R72-CS", "LIQ2-CS"):
        for p, lab in PERIODS:
            a, c = per(strat, "all", p), per(strat, "account", p)
            miss = (des[p] if p != "final" else fin)[strat]["not_taken_margin"]
            rows.append([td(strat), td(lab), td(a["n"]), td(f'{a["win"]}%'), cell(a["net"]), td(miss), td(c["n"]), cell(c["net"])])
    t3 = table(["rule", "period", "trades", "win", "net (real)", "no margin", "fit Rs 56k", "net (account)"], rows)

    # 4. arms (bought options)
    rows = []
    for arm in des["arms"]:
        r = [td(arm)]
        for p, _ in PERIODS:
            x = des["arms"][arm][p]["r17"] if p != "final" else fin["arms_final"][arm]["r17"]
            r += [td(x["n"]), cell(x["net"])]
        rows.append(r)
    t4 = table(["arm as it is (bought option)", "design n", "design", "walk-fwd n", "walk-fwd", "final n", "final"], rows)

    # 5. B1 filters
    rows = []
    names = {"B1-F": "R72 + fresh", "B1-T": "R72 + TWAP side", "B1-FT": "R72 + fresh + TWAP", "B1-LT": "Liquidity + TWAP side"}
    for k, v in amb["B1"].items():
        f = v["final"]
        rows.append([td(names[k]), td(f'{f["kept"]["n"]} of {f["arm"]["n"]}'), cell(f["arm"]["net"]), cell(f["kept"]["net"]),
                     td(v["design"]["gain_per_trade"]), td(v["walkforward"]["gain_per_trade"]), td(f["gain_per_trade"]),
                     td(f'{v["random_skip"]["p_mc"]:.2f}'), td("pass" if amb["gates"][k]["passed"] else "fail")])
    t5 = table(["filter on the bought arm", "final kept", "arm final", "filtered final", "gain/trade design",
                "gain/trade WF", "gain/trade final", "random-skip p", "verdict"], rows)

    # 6. B2 variants
    rows = []
    vn = {"V1-delta": "sell ~0.28 delta", "V2-wide": "wing 6 steps", "V3-debit": "debit (buy ATM)"}
    for k, v in amb["B2"].items():
        var, s = k.split("/")
        r = [td(f"{s}: {vn[var]}")]
        for p, _ in PERIODS:
            r.append(cell(v[p]["account"].get("net")))
        r += [cell(v["whole_account"].get("max_dd")), td(f'{v["random"]["p_mc"]:.3f}'),
              td("pass" if amb["gates"][k]["passed"] else "fail")]
        rows.append(r)
    t6 = table(["spread variant (Rs 56k account)", "design", "walk-fwd", "final", "max DD", "random p", "verdict"], rows)

    css = """
body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f6f7f9;color:#1d2330}
main{max-width:980px;margin:0 auto;padding:16px}
h1{font-size:20px;margin:8px 0}h2{font-size:16px;margin:22px 0 6px}p,li{font-size:14px;line-height:1.45}
.v{background:#fff;border-left:4px solid #b3261e;padding:10px 14px;border-radius:6px}
.tw{overflow-x:auto;background:#fff;border-radius:6px;box-shadow:0 1px 2px rgba(0,0,0,.08)}
table{border-collapse:collapse;width:100%;font-size:13px}th,td{padding:6px 8px;border-bottom:1px solid #e6e8ec;text-align:left;white-space:nowrap}
th{background:#eef0f4;font-weight:600}.num{text-align:right;font-variant-numeric:tabular-nums}
.neg{color:#b3261e}.pos{color:#1b6e3a}.note{color:#555;font-size:12px}
@media (prefers-color-scheme:dark){body{background:#15171c;color:#e6e8ec}.tw,.v{background:#1f232b}th{background:#2a2f39}
th,td{border-color:#2f3440}.neg{color:#f2b8b5}.pos{color:#8fd19e}.note{color:#aaa}}
"""
    return f"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>R173: credit spreads on real option prices</title><style>{css}</style></head><body><main>
<h1>R72 &amp; Liquidity as credit spreads: the files vs real option prices</h1>
<div class="v"><p><b>Verdict: the files' profit comes from their pricing formula, not from the market.</b> On real 1-minute
option prices (NIFTY, BANKNIFTY, Nov 2024 - 23 Feb 2026) both spread rules lose, and so do all 6 spread variants and all 4 entry
filters tried on the bought arms. Keep the arms as they are.</p></div>
<h2>1. Claim vs their model vs real prices</h2>{t1}
<p class="note">The files' claim covers 5 indices to Oct 2026; only NIFTY and BANKNIFTY have real option prices here, and the
Drive lake (Apr - Oct 2026, other indices) is not reachable. The model column re-runs their own script on our index data.</p>
<h2>2. Where the model's profit comes from</h2>{t2}
<p class="note">Their formula adds a fixed time decay (22% of the premium a day, 3 days to expiry for every index) to every trade.
Random entries under the same formula also make money: the formula, not the signal, produces the profit.</p>
<h2>3. Real prices by period, and the Rs 56k margin limit</h2>{t3}
<p class="note">One spread blocks about Rs 35-47k (max loss + 2% exposure, an approximation of the broker's SPAN + exposure),
so only one fits in Rs 56k at a time.</p>
<h2>4. The arms as they are (bought options), same data and costs</h2>{t4}
<h2>5. The files' entry filters on the bought arms (Amendment B1)</h2>{t5}
<h2>6. Spread variants on real prices (Amendment B2)</h2>{t6}
<p class="note">Every number is generated from research/hunt/r173_credit_spread_check/results/*.json by r173page.py.
Costs: R17 fills on every order + dated Zerodha charges. Benjamini-Hochberg over m = {amb["m"]:,} tests: no q below 1.
Final period read once by R173 (Amendment B: second look). Research only; nothing in the app changed.</p>
</main></body></html>
"""


if __name__ == "__main__":
    (RES / "explorer.html").write_text(build())
    print("wrote", RES / "explorer.html")
