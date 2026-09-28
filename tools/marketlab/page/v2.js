/* Strategy v2 report: renders #data ({crypto, forex, crypto_2vol, old}). */
(function () {
  const R = JSON.parse(document.getElementById("data").textContent);
  const $ = (s, el = document) => el.querySelector(s);
  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const isNum = (x) => typeof x === "number" && isFinite(x);
  const pct = (x, d = 1) => (isNum(x) ? (x * 100).toFixed(d) + "%" : "–");
  const spct = (x, d = 1) => (isNum(x) ? (x > 0 ? "+" : "") + (x * 100).toFixed(d) + "%" : "–");
  const usd = (x) => (isNum(x) ? "$" + x.toFixed(2) : "–");
  const susd = (x) => (isNum(x) ? (x > 0 ? "+" : x < 0 ? "−" : "") + "$" + Math.abs(x).toFixed(2) : "–");
  const num = (x, d = 2) => (isNum(x) ? x.toFixed(d) : "–");
  const cls = (x) => (isNum(x) ? (x > 0 ? "pos" : x < 0 ? "neg" : "") : "");
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const table = (head, rows, k = "") => `<div class="tbl"><table class="${k}"><thead><tr>${head.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  const charts = [];
  const seg = (s) => s ? `<span class="${cls(s.total_return)}">${spct(s.total_return, 1)}</span> <span class="caveat">S ${num(s.sharpe, 2)} · ${pct(s.max_drawdown, 0)}</span>` : "–";

  function famTable(fam, chosen) {
    return table(["Variant", "Before the window (≈2.75 y)", "Development year", "Last 3 months", "All 3 years"],
      Object.entries(fam).map(([k, v]) => [(chosen.some((c) => c.endsWith(k)) ? "<b>✓ " : "") + esc(k) + (chosen.some((c) => c.endsWith(k)) ? "</b>" : ""),
        seg(v.pre), seg(v.dev), seg(v.window), seg(v.full)]));
  }

  function market(key) {
    const M = R[key], root = $(`#${key}`), P = M.portfolio;
    root.querySelector(".pf").innerHTML = table(["Account", "$100 after 3 months", "Worst fall (3m)", "Before the window", "All 3 years"],
      [["Balanced (a third each)", `<b>${usd(P.balanced.account.end)}</b>`, pct(P.balanced.window.max_drawdown, 1), seg(P.balanced.pre), seg(P.balanced.full)],
       ["Stacked (each at full size)", `<b>${usd(P.stacked.account.end)}</b>`, pct(P.stacked.window.max_drawdown, 1), seg(P.stacked.pre), seg(P.stacked.full)],
       ["Just holding", usd(P.stacked.account.hold_end), "", "", ""]]);
    root.querySelector(".parts").innerHTML = table(["Part (chosen before the window)", "Before the window", "Development year", "Last 3 months", "P&L in 3m, stacked"],
      Object.entries(P.parts_stats).map(([k, v]) => [esc(k), seg(v.pre), seg(v.dev), seg(v.window), `<span class="${cls(P.stacked.account.by_part_usd[k])}">${susd(P.stacked.account.by_part_usd[k])}</span>`]));
    root.querySelector(".fam-trend").innerHTML = famTable(M.trend, P.parts);
    root.querySelector(".fam-strad").innerHTML = famTable(M.straddles, P.parts);
    const fc = root.querySelector(".fam-carry");
    if (fc) fc.innerHTML = M.carry && Object.keys(M.carry).length ? famTable(M.carry, P.parts) : "";
    const fo = root.querySelector(".fam-orig");
    if (fo && M.original) fo.innerHTML = table(["Rule", "Development year", "Last 3 months"], [["Blow-off fade + buy the big drop", seg(M.original.dev), seg(M.original.window)]]);
    // NN
    const nn = M.nn, vk = Object.keys(nn["price only"]).find((k) => k.startsWith("vol") && k.endsWith("dev")).replace("_dev", "");
    root.querySelector(".nn").innerHTML = table(["Inputs", "Direction AUC, dev", "Direction AUC, 3m", "Volatility forecast corr., dev", "Volatility forecast corr., 3m"],
      Object.entries(nn).map(([k, v]) => [esc(k), num(v.direction_dev.auc, 3), num(v.direction_window.auc, 3), num(v[vk + "_dev"].corr, 2), num(v[vk + "_window"].corr, 2)]));
    root.querySelector(".feat").textContent = "Extra inputs: " + M.extra_features.join(", ");
    // curve
    const el = root.querySelector(".eq");
    const cc = { ink3: css("--ink-3"), line: css("--line"), line2: css("--line-2"), panel: css("--panel"), accent: css("--accent"), up: css("--up") };
    const ch = LightweightCharts.createChart(el, { autoSize: true, layout: { background: { type: "solid", color: cc.panel }, textColor: cc.ink3, fontFamily: css("--mono"), fontSize: 11 },
      grid: { vertLines: { color: cc.line2 }, horzLines: { color: cc.line2 } }, rightPriceScale: { borderColor: cc.line }, timeScale: { borderColor: cc.line }, handleScroll: { vertTouchDrag: false } });
    charts.push(ch);
    const add = (acc, color, w, field) => { const s = ch.addLineSeries({ color, lineWidth: w, priceLineVisible: false, lastValueVisible: field === "eq" && color === cc.accent });
      s.setData(acc.curve.t.map((t, i) => ({ time: t, value: acc.curve[field][i] }))); };
    add(P.stacked.account, cc.accent, 2, "eq");
    add(P.balanced.account, cc.up, 2, "eq");
    add(P.stacked.account, cc.ink3, 1.5, "hold");
    ch.timeScale().fitContent();
    root.querySelector(".eq-leg").innerHTML = `<div class="legend"><span><i style="background:${cc.accent}"></i>Stacked</span><span><i style="background:${cc.up}"></i>Balanced</span><span><i style="background:${cc.ink3}"></i>$100 simply held</span></div>`;
    // straddle weeks
    const sw = M.straddle_weeks_window || [];
    root.querySelector(".weeks").innerHTML = sw.length ? table(["Week from (UTC)", "Action", "Implied vol", "Move over the week", "P&L (% of price)"],
      sw.map((w) => [w.start.slice(0, 16), w.side > 0 ? "bought straddle" : w.side < 0 ? "sold straddle" : "no trade", isNum(w.iv) ? num(w.iv, 1) + "%" : "–",
        isNum(w.move) ? `<span class="${cls(w.move)}">${spct(w.move, 1)}</span>` : "–", `<span class="${cls(w.pnl)}">${w.side ? spct(w.pnl, 2) : "–"}</span>`])) : "";
    // daily
    const D = P.stacked.account.days, B = Object.fromEntries(P.balanced.account.days.map((d) => [d.date, d]));
    const parts = Object.keys(D[0].parts);
    root.querySelector(".daily").innerHTML = table(["Date", "Stacked start", "Stacked end", "P&L", ...parts.map((p) => p.split(":")[0] + " P&L"), "Balanced end", "Market"],
      D.map((d) => [d.date, usd(d.start), usd(d.end), `<span class="${cls(d.pnl)}">${susd(d.pnl)}</span>`,
        ...parts.map((p) => `<span class="${cls(d.parts[p])}">${susd(d.parts[p])}</span>`), usd(B[d.date] && B[d.date].end),
        `<span class="${cls(d.market_ret)}">${spct(d.market_ret, 2)}</span>`]));
  }

  function render() {
    while (charts.length) charts.pop().remove();
    market("crypto");
    market("forex");
  }
  render();
  let pending;
  const rerender = () => { clearTimeout(pending); pending = setTimeout(render, 50); };
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", rerender);
  new MutationObserver(rerender).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
})();
