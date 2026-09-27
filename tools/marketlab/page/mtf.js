/* Multi-timeframe report: renders #data ({crypto: {mtf, suite, suite_mtf}, forex: {...}}). */
(function () {
  const R = JSON.parse(document.getElementById("data").textContent);
  const $ = (s, el = document) => el.querySelector(s);
  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const isNum = (x) => typeof x === "number" && isFinite(x);
  const pct = (x, d = 1) => (isNum(x) ? (x * 100).toFixed(d) + "%" : "–");
  const spct = (x, d = 1) => (isNum(x) ? (x > 0 ? "+" : "") + (x * 100).toFixed(d) + "%" : "–");
  const num = (x, d = 3) => (isNum(x) ? x.toFixed(d) : "–");
  const cls = (x) => (isNum(x) ? (x > 0 ? "pos" : x < 0 ? "neg" : "") : "");
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const table = (head, rows) => `<div class="tbl"><table><thead><tr>${head.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  const charts = [];
  const LABEL = { dir_1h: "Direction, next 1 hour", dir_4h: "Direction, next 4 hours", dir_24h: "Direction, next 24 hours",
    big_up_24h: "Big rise within 24 hours", big_down_24h: "Big drop within 24 hours", vol_24h: "Volatility, next 24 hours" };

  function metric(x) {
    if (!x) return "–";
    return isNum(x.auc) ? num(x.auc) : num(x.corr, 2);
  }

  function market(key) {
    const M = R[key], root = $(`#${key}`);
    if (!M || !M.mtf) { if (root) root.hidden = true; return; }
    const T = M.mtf.targets;
    root.querySelector(".bars").innerHTML = table(["Timeframe", ...Object.keys(M.mtf.timeframe_bars)], [["Bars in 3 years", ...Object.values(M.mtf.timeframe_bars).map((v) => v.toLocaleString())]]);
    root.querySelector(".cmp").innerHTML = table(["What the network predicts", "Score", "Hourly only · dev", "All timeframes · dev", "Hourly only · 3m", "All timeframes · 3m", "Trading it (all TF), dev / 3m"],
      Object.entries(T).map(([k, v]) => {
        const a = v["all timeframes"], h = v["hourly only"];
        const better = (x, y) => (isNum(x) && isNum(y) && x > y + 0.01 ? "pos" : "");
        const ad = metric(a.dev), hd = metric(h.dev), aw = metric(a.window), hw = metric(h.window);
        const tr = isNum(a.dev && a.dev.trade_total) ? `<span class="${cls(a.dev.trade_total)}">${spct(a.dev.trade_total, 1)}</span> / <span class="${cls(a.window.trade_total)}">${spct(a.window.trade_total, 1)}</span>` : "–";
        return [LABEL[k] || k, k === "vol_24h" ? "correlation" : "AUC (0.5 = coin)", hd, `<span class="${better(+ad, +hd)}">${ad}</span>`, hw, `<span class="${better(+aw, +hw)}">${aw}</span>`, tr];
      }));
    // importance chart: one dataset per target
    const groups = Object.keys(Object.values(T)[0].importance_dev.drop_when_shuffled);
    const palette = [css("--accent"), css("--up"), css("--down"), css("--ink-2"), css("--warn"), css("--ink-3")];
    const canvas = document.createElement("canvas");
    const box = root.querySelector(".imp");
    box.innerHTML = "";
    box.appendChild(canvas);
    const ch = new Chart(canvas, { type: "bar", data: { labels: groups.map((g) => g.replace("inside-the-hour (1-minute detail)", "1m detail")),
      datasets: Object.entries(T).map(([k, v], i) => ({ label: LABEL[k] || k, data: groups.map((g) => v.importance_dev.drop_when_shuffled[g]), backgroundColor: palette[i % palette.length] })) },
      options: { responsive: true, maintainAspectRatio: false, animation: false,
        plugins: { legend: { labels: { color: css("--ink-2"), boxWidth: 10, font: { size: 10 } } } },
        scales: { x: { ticks: { color: css("--ink-3") }, grid: { display: false } }, y: { ticks: { color: css("--ink-3") }, grid: { color: css("--line-2") },
          title: { display: true, text: "skill lost when this timeframe is shuffled", color: css("--ink-3") } } } } });
    charts.push(ch);
    // strategies with the new alarms
    const S0 = M.suite, S1 = M.suite_mtf;
    if (S0 && S1) {
      const row = (name, s) => [name, `$${s.portfolio.stacked.account.end.toFixed(2)}`, `$${s.portfolio.balanced.account.end.toFixed(2)}`,
        pct(s.portfolio.stacked.window.max_drawdown, 1), `<span class="${cls(s.portfolio.stacked.pre.total_return)}">${spct(s.portfolio.stacked.pre.total_return, 0)}</span> (S ${s.portfolio.stacked.pre.sharpe.toFixed(2)})`,
        esc(s.portfolio.parts.find((p) => p.startsWith("Straddle")) || "–")];
      root.querySelector(".strat").innerHTML = table(["Alarms from", "$100 stacked (3m)", "$100 balanced (3m)", "Worst fall, stacked", "Before the window, stacked", "Straddle rule chosen"],
        [row("Hourly network (v2)", S0), row("All-timeframe network", S1)]);
      const sk = Object.keys(S1.straddles);
      root.querySelector(".strad").innerHTML = table(["Straddle rule", "Hourly alarms: dev", "All-TF alarms: dev", "Hourly alarms: 3m", "All-TF alarms: 3m"],
        sk.map((k) => [esc(k), spct(S0.straddles[k] && S0.straddles[k].dev.total_return), spct(S1.straddles[k].dev.total_return),
          spct(S0.straddles[k] && S0.straddles[k].window.total_return), spct(S1.straddles[k].window.total_return)]));
    }
  }

  function render() {
    while (charts.length) charts.pop().destroy();
    market("crypto");
    market("forex");
  }
  render();
  let pending;
  const rerender = () => { clearTimeout(pending); pending = setTimeout(render, 50); };
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", rerender);
  new MutationObserver(rerender).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
})();
