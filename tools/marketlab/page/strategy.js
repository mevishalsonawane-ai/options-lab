/* Renders the strategy report from the JSON in #data ({crypto: {...}, forex: {...}}). */
(function () {
  const R = JSON.parse(document.getElementById("data").textContent);
  const $ = (s, el = document) => el.querySelector(s);
  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const isNum = (x) => typeof x === "number" && isFinite(x);
  const pct = (x, d = 1) => (isNum(x) ? (x * 100).toFixed(d) + "%" : "–");
  const spct = (x, d = 1) => (isNum(x) ? (x > 0 ? "+" : "") + (x * 100).toFixed(d) + "%" : "–");
  const usd = (x, d = 2) => (isNum(x) ? (x < 0 ? "−$" : "$") + Math.abs(x).toFixed(d) : "–");
  const susd = (x, d = 2) => (isNum(x) ? (x > 0 ? "+" : x < 0 ? "−" : "") + "$" + Math.abs(x).toFixed(d) : "–");
  const num = (x, d = 2) => (isNum(x) ? x.toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d }) : "–");
  const cls = (x) => (isNum(x) ? (x > 0 ? "pos" : x < 0 ? "neg" : "") : "");
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const secs = (t) => Math.floor(Date.parse(String(t).replace(" ", "T")) / 1000);
  const table = (head, rows, klass = "") => `<div class="tbl"><table class="${klass}"><thead><tr>${head.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  const fig = (k, v, n, c = "") => `<div class="fig"><span class="k">${k}</span><span class="v ${c}">${v}</span><span class="n">${n}</span></div>`;
  const charts = [];
  const when = (t) => (t ? String(t).slice(5, 16).replace("T", " ") : "open");

  function market(key) {
    const M = R[key], s = M.summary, root = $(`#${key}`);
    const diff = s.end_capital - s.benchmark_end_usd;
    root.querySelector(".figs").innerHTML = [
      fig("$100 became", usd(s.end_capital), `${susd(s.pnl_usd)} over 3 months`, cls(s.pnl_usd)),
      fig("Return", spct(s.total_return, 2), `annualised ${spct(s.annualised, 0)}`, cls(s.total_return)),
      fig("Just holding", usd(s.benchmark_end_usd), `strategy ${diff >= 0 ? "ahead" : "behind"} by ${usd(Math.abs(diff))}`),
      fig("Worst fall", pct(s.max_drawdown, 1), `holding: ${pct(s.benchmark_max_drawdown, 1)}`),
      fig("Sharpe", num(s.sharpe, 2), `volatility ${pct(s.ann_vol, 0)} a year`),
      fig("Trades", String(s.trades), `win rate ${pct(s.win_rate, 0)}, profit factor ${num(s.profit_factor, 2)}`),
      fig("Best / worst day", `${spct(s.best_day, 1)} / ${spct(s.worst_day, 1)}`, `${s.up_days} up, ${s.down_days} down, ${s.flat_days} flat days`),
    ].join("");
    // equity
    const el = root.querySelector(".eq");
    const c = { ink3: css("--ink-3"), line: css("--line"), line2: css("--line-2"), panel: css("--panel"), accent: css("--accent") };
    const ch = LightweightCharts.createChart(el, { autoSize: true,
      layout: { background: { type: "solid", color: c.panel }, textColor: c.ink3, fontFamily: css("--mono"), fontSize: 11 },
      grid: { vertLines: { color: c.line2 }, horzLines: { color: c.line2 } }, rightPriceScale: { borderColor: c.line },
      timeScale: { borderColor: c.line, timeVisible: true }, handleScroll: { vertTouchDrag: false } });
    charts.push(ch);
    const t = M.curve.t.map(secs);
    const a = ch.addLineSeries({ color: c.accent, lineWidth: 2, priceLineVisible: false, priceFormat: { type: "custom", formatter: (v) => "$" + v.toFixed(2) } });
    a.setData(t.map((x, i) => ({ time: x, value: M.curve.eq[i] })));
    const b = ch.addLineSeries({ color: c.ink3, lineWidth: 1.5, priceLineVisible: false, lastValueVisible: false });
    b.setData(t.map((x, i) => ({ time: x, value: M.curve.bench[i] })).filter((p) => isNum(p.value)));
    ch.timeScale().fitContent();
    root.querySelector(".eq-leg").innerHTML = `<div class="legend"><span><i style="background:${c.accent}"></i>Strategy account</span><span><i style="background:${c.ink3}"></i>$100 simply held</span></div>`;
    // months and parts
    root.querySelector(".months").innerHTML = table(["Month", "Start", "End", "Return", "Holding", "New trades"],
      M.months.map((m) => [m.month, usd(m.start_usd), usd(m.end_usd), `<span class="${cls(m.ret)}">${spct(m.ret, 2)}</span>`, `<span class="${cls(m.benchmark)}">${spct(m.benchmark, 2)}</span>`, m.trades]));
    root.querySelector(".parts").innerHTML = table(["Rule", "P&L", "Time long", "Time short"],
      Object.entries(s.by_component_usd).map(([k, v]) => {
        const ex = Object.entries(s.exposure).find(([n]) => n === k || n.split(" ")[0] === k.split(" ")[0]);
        return [esc(k), `<span class="${cls(v)}">${susd(v)}</span>`, ex ? pct(ex[1].long_share, 0) : "–", ex ? pct(ex[1].short_share, 0) : "–"];
      }));
    root.querySelector(".rules").textContent = M.rules.trim();
    root.querySelector(".capnote").textContent = M.capital_note;
    root.querySelector(".thr").textContent = `Alarm thresholds were set from ${M.thresholds.calibrated_on[0].slice(0, 10)} to ${M.thresholds.calibrated_on[1].slice(0, 10)}, before the test window, and not changed afterwards.`;
    // trades
    root.querySelector(".trades").innerHTML = table(["Rule", "Side", "Entry (UTC)", "Entry price", "Exit (UTC)", "Exit price", "Hours", "Return", "P&L"],
      M.trades.map((x) => [esc(x.strategy) + (x.carried_in ? ' <span class="caveat">(carried in)</span>' : "") + (x.iv ? ` <span class="caveat">IV ${x.iv}%, premium ${pct(x.premium_pct, 2)}</span>` : ""),
        x.side, when(x.entry_time), num(x.entry, 2), when(x.exit_time), num(x.exit, 2), num(x.hours, 0),
        `<span class="${cls(x.ret)}">${spct(x.ret, 2)}</span>`, `<span class="${cls(x.usd)}">${susd(x.usd)}</span>`]));
    const sk = root.querySelector(".skipped");
    if (sk) sk.innerHTML = M.straddles && M.straddles.skipped.length ? table(["Friday", "Why the straddle was not sold"],
      M.straddles.skipped.map((x) => [x.date, esc(x.reasons.join(", "))])) : "";
    // daily
    const parts = Object.keys(s.by_component_usd);
    root.querySelector(".daily").innerHTML = table(["Date (UTC)", "Day", "Start", "End", "P&L", "Return", "Market close", "Market", ...parts.map((p) => p + " P&L"), "Positions held", "Trades", "Alarms up / down", "Note"],
      M.days.map((d) => [d.date, d.weekday, usd(d.start_usd), usd(d.end_usd), `<span class="${cls(d.pnl_usd)}">${susd(d.pnl_usd)}</span>`,
        `<span class="${cls(d.ret)}">${spct(d.ret, 2)}</span>`, num(d.market_close, 2), `<span class="${cls(d.market_ret)}">${spct(d.market_ret, 2)}</span>`,
        ...parts.map((p) => `<span class="${cls(d.by_component_usd[p])}">${susd(d.by_component_usd[p])}</span>`),
        Object.entries(d.positions).map(([k, v]) => `${esc(k)}: ${esc(v)}`).join("<br>"),
        [d.opened.length ? `opened ${d.opened.map((x) => x.side).join(", ")}` : "", d.closed.length ? `closed ${d.closed.map((x) => `${x.side} ${susd(x.usd)}`).join(", ")}` : ""].filter(Boolean).join("<br>") || "–",
        `${pct(d.max_p_up, 0)} / ${pct(d.max_p_dn, 0)}`, `<span class="note-cell">${esc(d.note)}</span>`]), "daily-t");
  }

  function render() {
    while (charts.length) charts.pop().remove();
    ["crypto", "forex"].forEach(market);
  }
  render();
  let pending;
  const rerender = () => { clearTimeout(pending); pending = setTimeout(render, 50); };
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", rerender);
  new MutationObserver(rerender).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
})();
