"""R4 report page: one HTML page for Boss (phone first), built from
  - research/reports/r4/results/backtest.json   (r4backtest.py; backtest only, in git)
  - <pools.json>                                 (r4backtest.py; the backtest trades for the percentile check, outside git)
  - <paper.json>                                 (r4paper.py; the phone's paper record, outside git)

    python research/reports/r4/r4page.py <pools.json> <paper.json> <out.html>

26 Oct 2026. The page holds paper P&L, so it is written outside the repository (refused inside) and published as a
private Artifact. Every number on it comes from the three files; the fixed text (changes, failed studies, the answer)
quotes the committed write-ups by name.
"""
from __future__ import annotations

import html
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
sys.path.insert(0, str(HERE))
import r4stats as R  # noqa: E402

ARM_NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20",
            "R52": "R52 Open outside value", "SOLO": "Solo 2.0", "PORT": "Portfolio"}
SIGNAL = {
    "LIQ": "BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5 liquidity-pool breaks on the index; buys the 1-ITM option",
    "R72": "4 of 5 indices break their Donchian-20 channel on the same 5-min index bar; buys the 1-ITM option",
    "R51": "the index's own Donchian-20 break on 5-min bars; buys the 1-ITM option",
    "R52": "the index opens outside yesterday's value area (decided 09:20); buys the 1-ITM option",
}
BEFORE_RULES = {
    "LIQ": "own exits: -15% stop, index stop, 20 min unless +5%, next-level target, 15:10",
    "R72": "-15% stop, +30% target, out after 30 min, 15:15",
    "R51": "every break; -15% stop, +30% target, 30 min, 15:15",
    "R52": "-30% stop, +10% kept once +40%, out after 60 min, 15:10",
}
AFTER_RULES = {
    "LIQ": "same exits; no-twin (21 Oct); expiry-day skip (26 Oct, already in the research); h14 limit not modelled",
    "R72": "-15% stop, L25 then the 30-60-100 ladder, no target, 30 min, 15:15 (16 Oct)",
    "R51": "only breaks Supertrend (10, 3) agrees with (16 Oct); exits as R72",
    "R52": "-30% stop, L25 then the 30-60-100 ladder, 60 min, 15:10 (16 Oct)",
}

CHANGES = [  # (date on paper, change, arms, status kind, status text)
    ("16 Oct", "30-60-100 ladder: breakeven at +30%, keep +30% at +60%, +60% at +100%; the +30% target removed",
     "R72, R51, R52, Solo", "boss", "Boss's choice. R64 called it roughly a draw and advised keeping the old exits"),
    ("16 Oct", "L25: keep +5% once the option is +25%", "R72, R51, R52, Solo", "boss",
     "Boss's choice. R66: small and safe, not proven (q 0.50)"),
    ("16 Oct", "Supertrend (10, 3) must agree with a Donchian-20 break", "R51", "pass", "Passed every gate (R73 T7)"),
    ("19 Oct", "Same-direction cap: at most 2 automatic positions betting the same way, all arms", "all", "boss",
     "Boss's rule. R108: cuts very bad days, costs about Rs 1.2 lakh in the final, picks no better than chance"),
    ("19 Oct", "One option, one arm: a second arm on the same option keeps a would-be record only", "passed arms",
     "safety", "Safety rule (portfolio)"),
    ("21 Oct", "Liquidity no-twin: two Liquidity books never hold the same option", "Liquidity", "safety",
     "Safety rule, after 19 Oct's double stop-out; not a tested edge"),
    ("22 / 25 Oct", "R72 keeps the last cap place at its bar close (narrowed 25 Oct to a break due)", "all", "boss",
     "Boss's rule"),
    ("25 Oct", "Paper daily loss stop set to Rs 10,000; when hit, no new entries and open positions are sold", "all",
     "safety", "Safety rule (the stop existed before; it fired on 19, 23 and 26 Oct)"),
    ("26 Oct", "Liquidity skips its index's own expiry day", "Liquidity", "pass",
     "Restores the research rule (every Liquidity study skipped expiry days)"),
    ("26 Oct", "h14 capped limit: buy at most +0.5% over the signal price, 3 minutes, never chase", "Liquidity", "pass",
     "Passed (h14, barely); not modelled here"),
    ("26 Oct", "Boss's risk rules: account stop copy (-Rs 40k / -Rs 13k a month), weekly stop Rs 20k, 2% risk cap, "
     "no opposite bets", "all", "boss", "Boss's rules from the 25 Oct audit; first day 26 Oct; not modelled here"),
    ("26 Oct", "Donchian fixes: locks booked at their level, quicker stop looks, 10 s entry retry, holiday expiry",
     "R72, R51", "fix", "Fixes app-vs-backtest gaps found by R105; merged 18:13 IST, after the close, so first used 27 Oct"),
]

FAILED = [
    ("R104", "50 trading books, 227 tests", "0 pass; the daily swing lead is not an option-buying rule"),
    ("R105", "Donchian giveback exits (lock at +Rs 1,000 and 9 more)", "0 of 20; every rescue cut the winners more"),
    ("R105B", "Book direction filters (weekly / daily trend, first candle, gap side)", "0 of 40; trend filters hurt"),
    ("R105C", "A 60% win rate (sell at +5%), and a 10% stop", "61% win but -Rs 1.18 lakh on R72; the 10% stop makes less"),
    ("R105D", "Volume confirms the breakout", "No; high-volume breaks did no better (p 0.54)"),
    ("R105E", "Buy only liquid options", "0 of 6; less money, same win rate"),
    ("R105F", "Open-interest checks (PCR, writers, OI walls)", "0 of 8; each flipped between periods"),
    ("R106", "Small fixed targets (5 x Rs 1,000 a day)", "0 of 36; lost Rs 2.5-3.4 lakh in the final"),
    ("R107", "GEX and danger meters to trade bigger or add a 3rd position", "0 of 11; bigger days, not better days"),
    ("R108", "7 alternatives to the same-direction cap", "0 of 7; more trades always meant more bad days"),
    ("R110", "Every IraThinker strategy on every index", "0 of 13; alerts are information, not an edge"),
    ("R111", "IraThinker improvements", "Strangle + stop looks good (36 weeks, under the 40 needed); naked selling, needs margin"),
    ("R112", "Donchian mornings only, +Rs 1,500 / -Rs 1,000 an order", "0 of 10; Rs 4,919 vs Rs 3.06 lakh today"),
    ("R113", "When each arm makes its money (time of day)", "Most of the money is 09:15-09:30 entries; 0 of 7 time filters pass"),
    ("R114", "Donchian mornings only, closed at 10:00, in the paper book", "Rejected: beat today in every period, but random books did as well (p 0.26)"),
]

UNPROVEN = [
    "The 30-60-100 ladder and L25 (Boss's choices; R64 and R66 did not prove them).",
    "The same-direction cap: it lowers the worst days but does not pick better trades (R108).",
    "Liquidity's no-twin rule and the paper daily loss stop: safety rules, never tested as edges.",
    "Boss's 25 Oct risk rules: one paper day only.",
    "Solo 2.0 on paper: 1 real trade in the uploads; its picks do not reproduce in the backtest.",
    "R111's strangle with a stop: 36 weeks, below the 40 needed; option selling with gap risk.",
]

WATCH = [
    "Liquidity's paper record: 37 trades since 1 Oct, 12 won, sum -76% of premium, at the 2nd percentile of its "
    "backtest. Check it against the research rules trade by trade (fills, the 20-minute rule, index stops) before any live use.",
    "Stop checks while a position is open: 19 'stops looked at too far apart' and 2 'positions not protected' on 26 Oct, "
    "before the 26 Oct fixes reached the phone. Watch that this reaches 0 from 27 Oct (the open-positions-first rule).",
    "R51 with its Supertrend filter: 5th percentile on 19 paper trades. The 26 Oct parity test should close it; keep "
    "comparing its real and would-be records.",
    "Judge R72 on its real + would-be record while the cap is on (26 trades by 26 Oct, 13th percentile: normal).",
    "Boss's 25 Oct risk rules and the 10:00 idea (R114): count how often each refuses or sells, and what those trades did.",
    "The data: the lake ends 19 Oct and the Dhan token has expired, so 20-26 Oct cannot be replayed minute by minute. "
    "A new token (S6) lets the next report check paper against the backtest trade by trade.",
]


# ------------------------------------------------------------------------------------------------ formatting
def inr(x, sign: bool = False) -> str:
    """Indian grouping: 171366 -> 1,71,366."""
    if x is None:
        return "-"
    neg = x < 0
    s = str(abs(int(round(x))))
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts + [tail])
    pre = "-" if neg else ("+" if sign and x > 0 else "")
    return f"{pre}Rs {s}"


def pct(x) -> str:
    return "-" if x is None else f"{round(100 * x)}%"


def cls(x) -> str:
    if x is None:
        return ""
    return "pos" if x > 0 else ("neg" if x < 0 else "")


def esc(s) -> str:
    return html.escape(str(s))


# ------------------------------------------------------------------------------------------------ blocks
def num(x) -> str:
    """A table cell in rupees without the "Rs" (the table says it once)."""
    return "-" if x is None else inr(x)[3:] if x >= 0 else "-" + inr(x)[4:]


METRICS = [
    ("P&L", lambda s: num(s["net"]), lambda s: s["net"]),
    ("Per day", lambda s: num(s["per_day"]), lambda s: s["per_day"]),
    ("Green days", lambda s: pct(s["green_days"]), None),
    ("Win rate", lambda s: pct(s["win"]), None),
    ("Avg win", lambda s: num(s["avg_win"]), None),
    ("Avg loss", lambda s: num(s["avg_loss"]), None),
    ("Worst day", lambda s: num(s["worst_day"]), lambda s: s["worst_day"]),
    ("Worst trade", lambda s: num(s["worst_trade"]), lambda s: s["worst_trade"]),
    ("Drawdown", lambda s: num(s["max_dd"]), None),
    ("Big wins cut", lambda s: f'{s["big_cut"]["cut"]} of {s["big_cut"]["big"]}' if s.get("big_cut") else "-", None),
    ("  Rs at +30%", lambda s: num(s["big_cut"]["left"]) if s.get("big_cut") else "-", None),
    ("Trades", lambda s: f'{s["trades"]:,}', None),
    ("Days traded", lambda s: f'{s["days_traded"]}', None),
]


def metric_table(cols: list) -> str:
    """cols: [(header, stats)]; rows = the page's metrics."""
    head = "".join(f"<th>{esc(h)}</th>" for h, _ in cols)
    rows = []
    for name, fmt, val in METRICS:
        cells = []
        for _, s in cols:
            c = cls(val(s)) if val else ""
            cells.append(f'<td class="{c}">{esc(fmt(s))}</td>')
        rows.append(f"<tr><th scope=\"row\">{esc(name)}</th>{''.join(cells)}</tr>")
    return f'<p class="cap">Rupees, 1 lot, after charges.</p><div class="tw"><table class="m"><thead><tr><th></th>{head}</tr></thead><tbody>{"".join(rows)}</tbody></table></div>'


def arm_card(key: str, row: dict) -> str:
    b, a = row["before"], row["after"]
    p = row.get("paired", {})
    d = a["net"] - b["net"]
    extra = ""
    if key == "LIQ":
        n = row["after_notwin"]
        extra = (f'<p class="note">The backtest rules are unchanged for Liquidity, so Before and After are the same. '
                 f'With the no-twin rule, {b["trades"] - n["trades"]} of {b["trades"]} trades would have been twins: '
                 f'{inr(n["net"])} on {n["trades"]} trades, drawdown {inr(n["max_dd"])}.</p>')
    elif p:
        extra = (f'<p class="note">Same signals under both rules ({p["both"]:,}): the new exits let {p["ran"]} big winners run '
                 f'further ({inr(p["ran_rs"], True)}) and cut {p["cut"]} short ({inr(p["cut_rs"], True)}). '
                 + (f'{p["only_before"]} signals were taken only before ({inr(p["only_before_net"])}), '
                    f'{p["only_after"]} only after ({inr(p["only_after_net"])}).' if p["only_before"] or p["only_after"] else "")
                 + "</p>")
    verdict = f'<span class="chip {cls(d)}">After vs before: {inr(d, True)}</span>'
    return f"""
<section class="arm" id="arm-{key.lower()}">
  <h3>{esc(ARM_NAME[key])}</h3>
  <p class="sig"><b>Signal:</b> {esc(SIGNAL[key])}.</p>
  <dl class="rules"><dt>Before</dt><dd>{esc(BEFORE_RULES[key])}</dd><dt>After</dt><dd>{esc(AFTER_RULES[key])}</dd></dl>
  {metric_table([("Before", b), ("After", a)])}
  <div class="vrow">{verdict}</div>
  {extra}
</section>"""


def paper_rows(paper: dict, pools: dict) -> tuple:
    """Per arm: what the uploads show, the rebuilt net, and the percentile against the backtest."""
    reb = paper["rebuilt"]
    cnt = paper["counters"]
    liq = paper["liquidity"]["all_days"] or {}
    pl, pr = pools["pools"], pools["pools_ret"]
    out = []
    # Liquidity: % of premium (its rows have no rupees)
    if liq:
        r = R.percentile_of(liq["sum_pct"], [x * 100 for x in pr["LIQ"]], int(liq["trades"]))
        out.append(dict(arm="LIQ", what=f'{liq["trades"]} trades since {liq["since"][8:10]} Oct · {liq["wins"]} won',
                        result=f'{liq["sum_pct"]:+.0f}% of premium in all (avg {liq["avg_pct"]:+.2f}% a trade)',
                        pct=r["pct"], range=f'{r["p5"]:+.0f}% to {r["p95"]:+.0f}%', n=r["n"]))
    # passed arms: the uploads' counters, the rebuilt trades, and Boss's in-app figures quoted in R105 (26 Oct 12:20)
    quoted = {"R72": [("11 real (Boss's view, 26 Oct 12:20)", 11, -3583), ("26 real + would-be (same view)", 26, -7896)],
              "R51": [("19 real, with Supertrend (same view)", 19, -11997)]}
    for k in ("R72", "R51", "R52", "SOLO"):
        c = cnt.get(k, {})
        rb = reb.get(k)
        base = (f'{c.get("closed", 0)} closed since 17 Oct, {c.get("would_be", 0)} of them would-be' if c else "")
        if rb:
            r = R.percentile_of(rb["net_est"], pl[k], rb["trips"]) if k in pl else dict(pct=None, p5=None, p95=None, n=rb["trips"])
            out.append(dict(arm=k, what=f'{base}; rebuilt: {rb["trips"]} with prices ({", ".join(x[8:10] for x in rb["days"])} Oct)',
                            result=f'{inr(rb["net_est"])} ({rb["wins"]} won)', pct=r["pct"],
                            range=f'{inr(r["p5"])} to {inr(r["p95"])}' if r.get("p5") is not None else "no backtest here",
                            n=r["n"]))
        for label, n, v in quoted.get(k, []):
            r = R.percentile_of(v, pl[k], n)
            out.append(dict(arm=k, what=label, result=inr(v), pct=r["pct"], range=f'{inr(r["p5"])} to {inr(r["p95"])}', n=n))
    return out


def short(arm: str, n: int, v: float) -> str:
    return f"{ARM_NAME[arm].split(' ')[0]} {n}: {inr(v)}"


def page(bt: dict, pools: dict, paper: dict) -> str:
    rows = bt["rows"]
    P = rows["PORT"]
    per = bt["period"]
    arms_html = "".join(arm_card(k, rows[k]) for k in ("R72", "R51", "R52", "LIQ"))
    port = metric_table([("Before", P["before"]), ("Paper book", P["after"]), ("No cap", P["after_nocap"])])
    cr = P["after"]["cap_refused_big"]
    changes = "".join(
        f'<li><span class="when">{esc(w)}</span><div><p class="what">{esc(c)}</p><p class="meta">{esc(a)} · '
        f'<span class="tag {k}">{esc(s)}</span></p></div></li>' for w, c, a, k, s in CHANGES)
    prow = paper_rows(paper, pools)
    def tag(p):
        if p is None:
            return "bad"
        return "ok" if 10 <= p <= 97.5 else ("warn" if p >= 2.5 else "bad")
    ptab = "".join(
        f'<li><p class="ph"><b>{esc(ARM_NAME[r["arm"]])}</b> <span class="tag {tag(r["pct"])}">'
        f'{esc(R.reading(r["pct"]) + (" · small sample" if r["n"] < R.SMALL_N else ""))}</span></p>'
        f'<p class="small">{esc(r["what"])}</p>'
        f'<p class="pr"><span class="{"neg" if r["result"].startswith("-") else "pos"}">{esc(r["result"])}</span> · '
        f'percentile {"-" if r["pct"] is None else esc(r["pct"])} · backtest middle 90%: {esc(r["range"])}</p></li>'
        for r in prow)
    # paper by day (rebuilt, the four index arms + Solo)
    days = {}
    for t in paper["trips"]:
        if t["arm"] in ("OTHER",):
            continue
        days.setdefault(t["day"], {}).setdefault(t["arm"], [0, 0.0])
        days[t["day"]][t["arm"]][0] += 1
        days[t["day"]][t["arm"]][1] += t["net_est"]
    dtab = "".join(
        f'<tr><th scope="row">{d[8:10]} Oct</th><td>{" · ".join(short(a, n, v) for a, (n, v) in sorted(x.items()))}</td>'
        f'<td class="{cls(sum(v for _, v in x.values()))}">{inr(sum(v for _, v in x.values()))}</td></tr>'
        for d, x in sorted(days.items()))
    tot_n = sum(n for x in days.values() for n, _ in x.values())
    tot_v = sum(v for x in days.values() for _, v in x.values())
    h = paper["health"]
    htab = "".join(f'<tr><th scope="row">{d[8:10]} Oct</th><td>{v["slow_stop_checks"]}</td><td>{v["unprotected"]}</td>'
                   f'<td>{v["stream_drops"]}</td><td>{"yes" if v["daily_loss_stop"] else "-"}</td></tr>'
                   for d, v in sorted(h.items()) if d in paper["sessions"])
    failed = "".join(f'<li><b>{esc(a)}</b> {esc(b)}: <span>{esc(c)}</span></li>' for a, b, c in FAILED)
    unp = "".join(f"<li>{esc(x)}</li>" for x in UNPROVEN)
    watch = "".join(f"<li>{esc(x)}</li>" for x in WATCH)
    liq_all = paper["liquidity"]["all_days"] or {}
    return TEMPLATE.format(
        css=CSS, gen=esc(bt["generated"]), sessions=per["sessions"], months=per["months"],
        port=port, arms=arms_html, changes=changes, ptab=ptab, dtab=dtab, htab=htab, failed=failed, unp=unp, watch=watch,
        p_net=inr(P["after"]["net"]), p_dd=inr(P["after"]["max_dd"]), p_n=f'{P["after"]["trades"]:,}',
        p_bad=P["after"]["bad_months"], p_perday=inr(P["after"]["per_day"]),
        b_net=inr(P["before"]["net"]), b_dd=inr(P["before"]["max_dd"]), b_bad=P["before"]["bad_months"],
        b_wd=inr(P["before"]["worst_day"]), a_wd=inr(P["after"]["worst_day"]), nc_wd=inr(P["after_nocap"]["worst_day"]),
        nc_net=inr(P["after_nocap"]["net"]), nc_dd=inr(P["after_nocap"]["max_dd"]),
        cr_ref=cr["refused"], cr_big=cr["big"], cr_rs=inr(cr["rupees"]),
        r72_dd=inr(rows["R72"]["after"]["max_dd"]), r72_net=inr(rows["R72"]["after"]["net"]),
        liq_dd=inr(rows["LIQ"]["after"]["max_dd"]), r52_dd=inr(rows["R52"]["after"]["max_dd"]),
        r51_dd=inr(rows["R51"]["after"]["max_dd"]),
        tot_n=tot_n, tot_v=inr(tot_v), files=", ".join(f"{d[8:10]}" for d in paper["files"]),
        sess=", ".join(d[8:10] for d in paper["sessions"]), liq_n=liq_all.get("trades", 0),
    )


CSS = r"""
/* Layout: one reading column; each arm is a section with a 3-column metric table (metric, before, after) that fits a phone. */
:root{
  --bg:#f5f6f4; --surface:#ffffff; --ink:#1d2622; --muted:#5b6762; --line:#d9ded9;
  --accent:#1f5c4f; --pos:#1b7a3c; --neg:#b3261e; --warn:#8a5a00; --chip:#e8eeea;
  --display:"Instrument Sans","Segoe UI",system-ui,sans-serif;
  --body:"Atkinson Hyperlegible","Segoe UI",system-ui,sans-serif;
  --num:"IBM Plex Mono",ui-monospace,Menlo,Consolas,monospace;
  color-scheme:light;
}
@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){
  --bg:#121715; --surface:#1a211e; --ink:#e4ebe7; --muted:#9aa8a1; --line:#2c3632;
  --accent:#7cc4ae; --pos:#6fd08f; --neg:#ff8a80; --warn:#e6b450; --chip:#24302b; color-scheme:dark}}
:root[data-theme="dark"]{
  --bg:#121715; --surface:#1a211e; --ink:#e4ebe7; --muted:#9aa8a1; --line:#2c3632;
  --accent:#7cc4ae; --pos:#6fd08f; --neg:#ff8a80; --warn:#e6b450; --chip:#24302b; color-scheme:dark}
body{background:var(--bg);color:var(--ink);font-family:var(--body);font-size:15px;line-height:1.5}
.wrap{max-width:760px;margin:0 auto;padding-block:20px 48px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px));display:grid;gap:28px}
h1,h2,h3{font-family:var(--display);line-height:1.2;text-wrap:balance;margin:0}
h1{font-size:1.6rem} h2{font-size:1.2rem;color:var(--accent)} h3{font-size:1.05rem}
p{margin:0} ul,ol{margin:0;padding-left:1.1em}
.eyebrow{font-size:.75rem;letter-spacing:.08em;text-transform:uppercase;color:var(--muted)}
header{display:grid;gap:6px}
.answer{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:16px;display:grid;gap:10px}
.answer ol{display:grid;gap:8px}
.answer li::marker{font-family:var(--num);color:var(--accent)}
.boss{font-size:.85rem;color:var(--muted);border-top:1px solid var(--line);padding-top:8px}
section{display:grid;gap:10px;min-width:0}
.arm{border-top:1px solid var(--line);padding-top:14px}
.sig,.note,.meta,.small{font-size:.85rem;color:var(--muted)}
.rules{display:grid;grid-template-columns:auto 1fr;gap:2px 10px;margin:0;font-size:.85rem}
.rules dt{font-weight:700} .rules dd{margin:0;color:var(--muted)}
.tw{overflow-x:auto;min-width:0;border:1px solid var(--line);border-radius:8px;background:var(--surface)}
table{border-collapse:collapse;width:100%;font-size:.85rem}
th,td{padding:6px 7px;border-bottom:1px solid var(--line);text-align:right;vertical-align:top}
thead th{font-family:var(--display);font-size:.78rem;color:var(--muted);font-weight:600}
tbody th{text-align:left;font-weight:600;white-space:nowrap}
td{white-space:nowrap;font-family:var(--num);font-variant-numeric:tabular-nums;font-size:.8rem}
table.txt td{font-family:var(--body);text-align:left;font-size:.82rem;white-space:normal}
.pcheck{list-style:none;padding:0;display:grid;gap:0;border:1px solid var(--line);border-radius:8px;background:var(--surface)}
.pcheck li{padding:10px 12px;border-bottom:1px solid var(--line);display:grid;gap:3px}
.pcheck li:last-child{border-bottom:0}
.pr{font-family:var(--num);font-size:.78rem;font-variant-numeric:tabular-nums}
tr:last-child th,tr:last-child td{border-bottom:0}
.cap{font-size:.75rem;color:var(--muted);margin-bottom:-6px}
.pos{color:var(--pos)} .neg{color:var(--neg)}
.chip{display:inline-block;font-family:var(--num);font-size:.8rem;padding:2px 8px;border-radius:99px;background:var(--chip)}
.vrow{display:flex;flex-wrap:wrap;gap:8px}
.tag{display:inline-block;font-size:.74rem;padding:1px 7px;border-radius:99px;background:var(--chip);color:var(--ink)}
.tag.pass,.tag.ok{color:var(--pos)} .tag.boss,.tag.warn{color:var(--warn)} .tag.bad{color:var(--neg)} .tag.safety,.tag.fix{color:var(--accent)}
.changes{list-style:none;padding:0;display:grid;gap:10px}
.changes li{display:grid;grid-template-columns:5.2em 1fr;gap:10px}
.when{font-family:var(--num);font-size:.78rem;color:var(--accent);padding-top:2px}
.what{font-size:.9rem}
.list{display:grid;gap:6px;font-size:.9rem}
.list span{color:var(--muted)}
footer{font-size:.78rem;color:var(--muted);border-top:1px solid var(--line);padding-top:12px;display:grid;gap:6px}
a{color:var(--accent)}
:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
"""

TEMPLATE = """<title>IraAlgo Before and After</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Atkinson+Hyperlegible:wght@400;700&family=IBM+Plex+Mono:wght@400;500&family=Instrument+Sans:wght@500;600;700&display=swap">
<style>{css}</style>
<main class="wrap">
<header>
  <p class="eyebrow">R4 · for Boss · 26 Oct 2026 · paper only 13-26 Oct</p>
  <h1>Before and after: the paper run and the live decision</h1>
  <p class="small">Backtest: 1 Apr - 13 Oct 2026, {sessions} sessions, 1 lot, after charges and slippage. Paper: the phone's uploads. Shown separately; they are never added together.</p>
</header>

<section class="answer" aria-labelledby="ans">
  <h2 id="ans">The answer for the live decision</h2>
  <ol>
    <li><b>The evidence does not support going live with the book as it runs on paper.</b> All four arms with the new exits and the cap of 2 made {p_net} in the final period ({p_n} trades, {p_perday} a day), with a {p_dd} drawdown and {p_bad} of {months} months at or below -Rs 13,000. It lost money in design (R108, R114).</li>
    <li><b>Boss's account rule (-Rs 40k drawdown, -Rs 13k a month) is broken at the smallest size.</b> R108: no 1-lot set of the four arms stays inside it in both periods, with or without the cap. Alone, each arm's final drawdown is above Rs 40k too (R72 {r72_dd}, Liquidity {liq_dd}, R52 {r52_dd}, R51 {r51_dd}).</li>
    <li><b>The strongest arm is R72 Breadth Donchian</b> ({r72_net} in the final alone; its paper record is inside its backtest range). R108's closest fit to the rule is Liquidity alone with the cap: inside it in design, just outside in the final (Rs 35,911 drawdown, 1 bad month). R72 + R52 and Liquidity + R72 + R52 with the cap fit the final only.</li>
    <li><b>Of the changes, only the Supertrend filter (R51) and h14's capped limit (Liquidity) passed research. In the backtest the Supertrend filter and the new exits added money on R51 and R72 and changed nothing on R52.</b> The cap takes the book's worst day from {nc_wd} to {a_wd}, but its profit from {nc_net} to {p_net}. Liquidity's paper record is worse than its backtest and needs a check.</li>
    <li><b>If Boss still wants a live step: 1 lot, R72 only, stops resting at the broker, the account stop on</b>, and a limit agreed in advance for stopping. Otherwise keep paper running and re-read this page after 30 more sessions.</li>
  </ol>
  <p class="boss">A recommendation, not an order. Boss decides. Nothing live changes by itself: real orders need Boss's PIN and "AI trades go live", and every upload (files {files}) shows Live off, AI-live off and real orders not allowed. (R72's own "Trade live" switch was turned back on on 19 Oct; it can do nothing while Live and AI-live are off.)</p>
</section>

<section aria-labelledby="chg">
  <h2 id="chg">What changed on paper, with dates</h2>
  <ul class="changes">{changes}</ul>
</section>

<section aria-labelledby="port">
  <h2 id="port">The portfolio: backtest final period</h2>
  <p class="small">Before = the four arms' plain-rule trades added up ({b_net}, drawdown {b_dd}, {b_bad} bad months). After = the same four arms with today's exits and filters in the app's paper book: with no cap ({nc_net}, drawdown {nc_dd}) and with the cap of 2, as paper runs today ("Paper book"). Big wins = trades whose option reached +60% after the fill; "cut" = sold below +30%; "Rs at +30%" = what selling those at +30% would have added.</p>
  {port}
  <p class="note">The cap left out {cr_ref} of the {cr_big} trades that made Rs 3,000 or more alone ({cr_rs} between them). Boss's 25 Oct risk rules, h14's limit and Solo 2.0 are not in these numbers.</p>
</section>

<section aria-labelledby="arms">
  <h2 id="arms">Every arm: plain rules vs today's rules (backtest final period)</h2>
  {arms}
</section>

<section aria-labelledby="pap">
  <h2 id="pap">Paper 13-26 Oct: what the phone record shows</h2>
  <p class="small">The uploads exist for {files} Oct (no files for 13-18 Oct in any release); trading sessions with a record: {sess} Oct. They carry no rupees. Rupees below are rebuilt from the paper orders' prices (both legs present: {tot_n} trades of the four arms and Solo 2.0; 4 other paper trades of the NG evening breakout and the Hero ticket are left out) less about Rs 60 charges a trade, so they are estimates. Small samples throughout.</p>
  <div class="tw"><table class="txt"><thead><tr><th>Day</th><th>Rebuilt trades by arm</th><th>Net (est.)</th></tr></thead><tbody>{dtab}<tr><th scope="row">All</th><td>{tot_n} trades</td><td>{tot_v}</td></tr></tbody></table></div>
</section>

<section aria-labelledby="chk">
  <h2 id="chk">Is paper inside the backtest's range?</h2>
  <p class="small">R105's method: 20,000 random draws of the same number of trades from the arm's own backtest trades (final period, today's rules). The percentile is the share of draws that did as badly or worse. The range is the middle 90% of draws. Liquidity is compared in % of premium because its rows have no rupees ({liq_n} trades since 1 Oct; 1-13 Oct also sits inside the backtest's final period, so this sample is not fully separate from it).</p>
  <ul class="pcheck">{ptab}</ul>
  <h3>Known app-vs-backtest differences (found by R105 / R108, fixed 21-26 Oct)</h3>
  <ul class="list">
    <li>Locks booked below their level (21 Oct BANKNIFTY sold Rs 216 under its +5% lock) <span>fixed 26 Oct: booked at the level</span></li>
    <li>Stops checked seconds apart while a position was open <span>fixed 25-26 Oct: 5 s looks, bounded reads; still 19 slow looks on 26 Oct, before the fix reached the phone</span></li>
    <li>Missed fills ("the fill window passed", 4 in two days) <span>fixed 26 Oct: 10 s retry inside the window, paper only</span></li>
    <li>R51 treated Wednesday 21 Oct as NIFTY's expiry day <span>fixed 26 Oct: holiday-aware expiry rule</span></li>
    <li>R51's Supertrend on the phone vs R73 <span>26 Oct: parity test to 1e-6 added</span></li>
    <li>Liquidity traded on its own expiry day <span>fixed 26 Oct: expiry-day skip</span></li>
    <li>Two Liquidity books bought the same put on 19 Oct <span>fixed 21 Oct: no-twin rule</span></li>
    <li>The cap makes paper a different subset of R72's signals <span>not a bug: judge R72 on real + would-be</span></li>
  </ul>
  <h3>Stop checks while positions were open (from the uploads)</h3>
  <div class="tw"><table><thead><tr><th>Day</th><th>Slow stop looks</th><th>Not protected</th><th>Stream drops</th><th>Day loss stop</th></tr></thead><tbody>{htab}</tbody></table></div>
</section>

<section aria-labelledby="fail">
  <h2 id="fail">What failed (R104-R114)</h2>
  <ul class="list">{failed}</ul>
  <h3>Still unproven</h3>
  <ul class="list">{unp}</ul>
</section>

<section aria-labelledby="next">
  <h2 id="next">What to watch next</h2>
  <ul class="list">{watch}</ul>
</section>

<footer>
  <p>Signal source for every arm: the index (or its breadth); the option is only bought (1-ITM). Stops, locks and the ladder sit on the option premium. No rule here reads an option's own chart.</p>
  <p>Backtest: each arm's own research code (R101 candidates, R64 engine, R112's Supertrend, R108's paper book), R51 clean stores, dated Zerodha charges, R17 slippage, 1 lot. Each "After" row reproduces its published figure to the rupee (R72 1,202 / Rs 1,71,366; R51 + Supertrend 1,670 / Rs 1,34,743; R52 293 / Rs 48,612; Liquidity 357 / Rs 11,155; R108's capped book 1,384 / Rs 15,955). The final period has been read by many studies, so it is weak evidence; no rule was chosen on it here.</p>
  <p>R113 and R114 are finished on their branches (not merged yet). Generator: research/reports/r4/ · built {gen}.</p>
</footer>
</main>
"""


def main(argv):
    pools_p, paper_p, out = Path(argv[1]), Path(argv[2]), Path(argv[3]).resolve()
    if REPO in out.parents:
        sys.exit("refused: the page holds paper P&L and must not be written inside the repository")
    bt = json.loads((HERE / "results" / "backtest.json").read_text())
    out.write_text(page(bt, json.loads(pools_p.read_text()), json.loads(paper_p.read_text())))
    print("page ->", out)


if __name__ == "__main__":
    main(sys.argv)
