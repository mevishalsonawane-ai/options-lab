/* Renders the "why no profit" diagnosis from #data ({crypto: {...}, forex: {...}}). */
(function () {
  const R = JSON.parse(document.getElementById("data").textContent);
  const $ = (s, el = document) => el.querySelector(s);
  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const isNum = (x) => typeof x === "number" && isFinite(x);
  const pct = (x, d = 1) => (isNum(x) ? (x * 100).toFixed(d) + "%" : "–");
  const spct = (x, d = 1) => (isNum(x) ? (x > 0 ? "+" : "") + (x * 100).toFixed(d) + "%" : "–");
  const num = (x, d = 2) => (isNum(x) ? x.toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d }) : "–");
  const cls = (x) => (isNum(x) ? (x > 0 ? "pos" : x < 0 ? "neg" : "") : "");
  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const table = (head, rows) => `<div class="tbl"><table><thead><tr>${head.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead>
    <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  const fig = (k, v, n, c = "") => `<div class="fig"><span class="k">${k}</span><span class="v ${c}">${v}</span><span class="n">${n}</span></div>`;
  const charts = [];

  function cj(el, type, labels, datasets, opts = {}) {
    const canvas = document.createElement("canvas");
    el.innerHTML = "";
    el.appendChild(canvas);
    const ink3 = css("--ink-3"), line2 = css("--line-2");
    const ch = new Chart(canvas, { type, data: { labels, datasets }, options: Object.assign({
      responsive: true, maintainAspectRatio: false, animation: false,
      plugins: { legend: { labels: { color: css("--ink-2"), boxWidth: 12, font: { size: 11 } } } },
      scales: { x: { ticks: { color: ink3, font: { size: 10 } }, grid: { display: false } }, y: { ticks: { color: ink3 }, grid: { color: line2 } } },
    }, opts) });
    charts.push(ch);
  }

  function market(key, unit) {
    const M = R[key], root = $(`#${key}`), S = M.sessions;
    const over = Object.entries(S.days_over || {}).map(([p, n]) => `${n} of ${S.days} over ${unit}${Number(p).toLocaleString()}`).join(" · ");
    root.querySelector(".sess-figs").innerHTML = [
      fig("Average session range", `${unit}${num(S.avg_range_pts, 0)}`, `${pct(S.avg_range_pct, 2)} of price, median ${unit}${num(S.median_range_pts, 0)}`),
      fig("Open to close, average", pct(S.avg_abs_oc_pct, 2), `only ${pct(S.share_oc_of_range, 0)} of the day's range`),
      fig("Big sessions", "", over),
    ].join("");
    const rows = M.session_rows;
    cj(root.querySelector(".sess-chart"), "bar", rows.map((r) => r.date.slice(5)), [
      { label: `High–low range (${unit})`, data: rows.map((r) => r.range_pts), backgroundColor: css("--accent") },
      { label: `Open→close move (${unit})`, data: rows.map((r) => Math.abs(r.oc_pct) * (r.range_pts / r.range_pct)), backgroundColor: css("--ink-3") },
    ], { scales: { x: { ticks: { color: css("--ink-3"), font: { size: 9 }, maxRotation: 0, autoSkipPadding: 8 }, grid: { display: false } },
      y: { ticks: { color: css("--ink-3") }, grid: { color: css("--line-2") } } } });
    // scan
    const sc = M.scan;
    const segName = { all_test: "All tested bars", dev: "Development year", window: "Last 3 months" };
    root.querySelector(".scan").innerHTML = table(["Next", "Avg move", "Right direction", "Needed to pay costs", "Gap", "Size forecast (corr.)", "Trade every signal"],
      sc.map((x) => { const a = x.all_test; const gap = a.dir_accuracy - a.breakeven_accuracy;
        return [`${x.horizon} bar${x.horizon > 1 ? "s" : ""}`, pct(a.avg_abs_move, 2), pct(a.dir_accuracy, 1), pct(a.breakeven_accuracy, 1),
          `<span class="${cls(gap)}">${gap >= 0 ? "+" : ""}${(gap * 100).toFixed(1)} pts</span>`, num(a.mag_corr, 2),
          `<span class="${cls(a.trade_every_bar_total)}">${spct(a.trade_every_bar_total, 0)}</span> <span class="caveat">(${a.trade_every_bar_n} trades)</span>`]; }));
    cj(root.querySelector(".scan-chart"), "line", sc.map((x) => `${x.horizon}h`), [
      { label: "Right direction", data: sc.map((x) => x.all_test.dir_accuracy * 100), borderColor: css("--accent"), backgroundColor: css("--accent"), pointRadius: 3 },
      { label: "Needed after costs", data: sc.map((x) => x.all_test.breakeven_accuracy * 100), borderColor: css("--down"), backgroundColor: css("--down"), borderDash: [5, 4], pointRadius: 3 },
      { label: "Coin flip", data: sc.map(() => 50), borderColor: css("--ink-3"), pointRadius: 0, borderWidth: 1 },
    ], { scales: { x: { ticks: { color: css("--ink-3") }, grid: { display: false } }, y: { ticks: { color: css("--ink-3"), callback: (v) => v + "%" }, grid: { color: css("--line-2") } } } });
    const hz = sc.find((x) => x.horizon === 24) || sc[sc.length - 1];
    root.querySelector(".scan-seg").innerHTML = table(["Next " + hz.horizon + " bars", "Right direction", "Needed", "Size corr.", "Largest-10% forecast: avg move", "Other bars"],
      Object.keys(segName).filter((k) => hz[k]).map((k) => [segName[k], pct(hz[k].dir_accuracy, 1), pct(hz[k].breakeven_accuracy, 1), num(hz[k].mag_corr, 2),
        pct(hz[k].mag_top10_avg_move, 2), pct(hz[k].mag_rest_avg_move, 2)]));
    const h1 = sc[0];
    cj(root.querySelector(".hour-chart"), "bar", h1.by_hour.map((r) => String(r.hour).padStart(2, "0")), [
      { label: "Right direction, next bar (%)", data: h1.by_hour.map((r) => r.accuracy * 100), backgroundColor: css("--accent") }],
      { scales: { x: { ticks: { color: css("--ink-3"), font: { size: 9 } }, grid: { display: false }, title: { display: true, text: "entry hour (UTC)", color: css("--ink-3") } },
        y: { min: 40, max: 60, ticks: { color: css("--ink-3") }, grid: { color: css("--line-2") } } } });
    // autopsy
    root.querySelector(".autopsy").innerHTML = table(["Trade", "Entry (UTC)", "Result", "Best it got", "Worst it got", "Kept of best", "Best came after", "Move before entry"],
      M.autopsy.map((a) => [`${esc(a.strategy)} · ${a.side}`, a.entry_time.slice(5, 16), `<span class="${cls(a.ret)}">${spct(a.ret, 2)}</span>`,
        `<span class="pos">${spct(a.mfe, 2)}</span>`, `<span class="neg">${spct(a.mae, 2)}</span>`, isNum(a.kept) ? pct(a.kept, 0) : "–",
        `${num(a.hours_to_best, 0)}h`, `<span class="${cls(a.move_before_entry_24h)}">${spct(a.move_before_entry_24h, 2)}</span>`]));
    const au = M.autopsy.filter((a) => isNum(a.mfe));
    const sumBest = au.reduce((s, a) => s + Math.max(a.mfe, 0), 0), sumRes = au.reduce((s, a) => s + a.ret, 0);
    root.querySelector(".autopsy-figs").innerHTML = [
      fig("Trades that were ever in profit", `${au.filter((a) => a.mfe > 0.002).length} of ${au.length}`, "by more than 0.2%"),
      fig("Sum of best points", spct(sumBest, 1), "if each had exited at its best"),
      fig("What they actually made", spct(sumRes, 1), `gave back ${spct(sumRes - sumBest, 1)}`),
      fig("Already moved before entry", pct(au.filter((a) => a.move_before_entry_24h > 0.01).length / Math.max(au.length, 1), 0), "of trades came after a 1%+ move in their direction"),
    ].join("");
    // variants
    const V = M.directional_variants || M.variants;
    root.querySelector(".variants").innerHTML = table(["Rule set", "Dev year return", "Dev worst fall", "Dev Sharpe", "Last 3 months", "3-month worst fall", "Time in market (3m)"],
      Object.entries(V).map(([k, v]) => [esc(k), `<span class="${cls(v.dev.total_return)}">${spct(v.dev.total_return, 1)}</span>`, pct(v.dev.max_drawdown, 1), num(v.dev.sharpe, 2),
        `<span class="${cls(v.window.total_return)}">${spct(v.window.total_return, 1)}</span>`, pct(v.window.max_drawdown, 1), pct(v.exposure_window, 0)]));
    const O = M.straddle_variants;
    if (O) {
      root.querySelector(".opt").innerHTML = table(["Straddle rule", "Dev: weeks traded", "Dev: total (% of spot)", "Dev: worst week", "3m: total", "3m: worst week", "Win rate (dev)"],
        Object.entries(O).map(([k, v]) => [esc(k), `${v.dev.traded} of ${v.dev.weeks}`, `<span class="${cls(v.dev.total)}">${spct(v.dev.total, 1)}</span>`, `<span class="neg">${spct(v.dev.worst, 1)}</span>`,
          `<span class="${cls(v.window.total)}">${spct(v.window.total, 1)}</span>`, `<span class="${cls(v.window.worst)}">${spct(v.window.worst, 1)}</span>`, pct(v.dev.win_rate, 0)]));
      const A = M.alarm_vs_week_size;
      root.querySelector(".alarm-weeks").innerHTML = [
        fig("Average weekly move, alarm Fridays", pct(A.avg_abs_move_alarm, 1), `${A.alarm_weeks} weeks`),
        fig("Average weekly move, quiet Fridays", pct(A.avg_abs_move_quiet, 1), `${A.weeks - A.alarm_weeks} weeks`),
        fig("Big weeks (top 20%) after an alarm", pct(A.big_week_share_alarm, 0), `after a quiet Friday: ${pct(A.big_week_share_quiet, 0)}`),
      ].join("");
    }
  }

  function render() {
    while (charts.length) charts.pop().destroy();
    market("crypto", "$");
    market("forex", "$");
  }
  render();
  let pending;
  const rerender = () => { clearTimeout(pending); pending = setTimeout(render, 50); };
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", rerender);
  new MutationObserver(rerender).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
})();
