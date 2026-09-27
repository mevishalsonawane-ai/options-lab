/* Renders a market study page from the JSON in #data. Shared by crypto/index.html and forex/index.html. */
(function () {
  const D = JSON.parse(document.getElementById("data").textContent);
  const $ = (s, el = document) => el.querySelector(s);
  const css = (n) => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
  const isNum = (x) => typeof x === "number" && isFinite(x);
  const pct = (x, d = 1) => (isNum(x) ? (x * 100).toFixed(d) + "%" : "–");
  const spct = (x, d = 1) => (isNum(x) ? (x > 0 ? "+" : "") + (x * 100).toFixed(d) + "%" : "–");
  const num = (x, d = 2) => (isNum(x) ? x.toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d }) : "–");
  const cls = (x) => (isNum(x) ? (x > 0 ? "pos" : x < 0 ? "neg" : "") : "");
  const secs = (t) => Math.floor(Date.parse(String(t).replace(" ", "T")) / 1000);
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const charts = [];

  function colors() {
    return { ink: css("--ink"), ink2: css("--ink-2"), ink3: css("--ink-3"), line: css("--line"), line2: css("--line-2"),
      up: css("--up"), down: css("--down"), accent: css("--accent"), panel: css("--panel"), warn: css("--warn") };
  }

  // ---- lightweight-charts helpers --------------------------------------------------------------
  function lc(el, opts = {}) {
    const c = colors();
    const ch = LightweightCharts.createChart(el, Object.assign({
      autoSize: true,
      layout: { background: { type: "solid", color: c.panel }, textColor: c.ink3, fontFamily: css("--mono"), fontSize: 11 },
      grid: { vertLines: { color: c.line2 }, horzLines: { color: c.line2 } },
      rightPriceScale: { borderColor: c.line },
      timeScale: { borderColor: c.line, timeVisible: false },
      crosshair: { mode: 0 },
      handleScroll: { vertTouchDrag: false },
    }, opts));
    charts.push(ch);
    return ch;
  }
  function line(ch, t, v, color, width = 2, extra = {}) {
    const s = ch.addLineSeries(Object.assign({ color, lineWidth: width, priceLineVisible: false, lastValueVisible: false }, extra));
    const seen = new Set();
    const data = [];
    for (let i = 0; i < t.length; i++) {
      if (!isNum(v[i])) continue;
      const time = typeof t[i] === "number" ? t[i] : secs(t[i]);
      if (seen.has(time)) continue;
      seen.add(time);
      data.push({ time, value: v[i] });
    }
    s.setData(data);
    return s;
  }
  const legend = (items) => `<div class="legend">${items.map(([n, c]) => `<span><i style="background:${c}"></i>${esc(n)}</span>`).join("")}</div>`;

  // ---- simple html bars ------------------------------------------------------------------------
  function bars(rows, fmt = (v) => num(v, 3)) {
    const max = Math.max(...rows.map((r) => Math.abs(r[1])).filter(isNum), 1e-12);
    return `<div class="bars">${rows.map(([l, v, color]) => `<div class="bar"><span class="lbl">${esc(l)}</span>
      <span class="track"><span class="fill" style="width:${(Math.abs(v) / max) * 100}%;${color ? `background:${color}` : ""}"></span></span>
      <span class="val ${cls(v)}">${fmt(v)}</span></div>`).join("")}</div>`;
  }
  function table(head, rows) {
    return `<div class="tbl"><table><thead><tr>${head.map((h) => `<th>${esc(h)}</th>`).join("")}</tr></thead>
      <tbody>${rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  }
  function heatColor(v) {
    if (!isNum(v)) return "transparent";
    const c = v >= 0 ? css("--up") : css("--down");
    const a = Math.min(Math.abs(v) / 0.25, 1) * 55 + 6;
    return `color-mix(in srgb, ${c} ${a.toFixed(0)}%, transparent)`;
  }

  // ---- Chart.js --------------------------------------------------------------------------------
  function cj(el, type, labels, datasets, opts = {}) {
    const c = colors();
    const canvas = document.createElement("canvas");
    el.innerHTML = "";
    el.appendChild(canvas);
    const ch = new Chart(canvas, {
      type, data: { labels, datasets },
      options: Object.assign({
        responsive: true, maintainAspectRatio: false, animation: false,
        plugins: { legend: { labels: { color: c.ink2, boxWidth: 12, font: { size: 11 } } }, tooltip: { mode: "index", intersect: false } },
        scales: {
          x: { ticks: { color: c.ink3, font: { size: 10, family: css("--mono") }, maxRotation: 0, autoSkipPadding: 8 }, grid: { color: c.line2 } },
          y: { ticks: { color: c.ink3, font: { size: 10, family: css("--mono") } }, grid: { color: c.line2 } },
        },
      }, opts),
    });
    charts.push({ remove: () => ch.destroy() });
    return ch;
  }

  // ---- sections --------------------------------------------------------------------------------
  function header() {
    const s = D.study.summary, st = D.study.state;
    const figs = [
      ["Last close", num(st.close, 2), st.date],
      ["3-year return", spct(s.total_return, 0), `${s.start} → ${s.end}`],
      ["Yearly (CAGR)", spct(s.cagr, 1), ""],
      ["Volatility", pct(s.ann_vol, 0), "annualised"],
      ["Worst fall", pct(s.max_drawdown, 1), `trough ${s.max_drawdown_date}`],
      ["From high", pct(st.from_high, 1), `high ${num(s.all_time_high_in_window, 0)} on ${s.ath_date}`],
    ];
    if (D.options && D.options.dvol) figs.push(["DVOL now", num(D.options.dvol.now, 1), "30-day implied vol"]);
    $("#figs").innerHTML = figs.map(([k, v, n]) => `<div class="fig"><span class="k">${k}</span><span class="v ${k.includes("return") || k.includes("CAGR") ? cls(parseFloat(v)) : ""}">${v}</span><span class="n">${esc(n)}</span></div>`).join("");
  }

  let logScale = true;
  function price() {
    const s = D.study.series.daily, c = colors();
    const el = $("#price-chart");
    el.innerHTML = "";
    const ch = lc(el, { rightPriceScale: { borderColor: c.line, mode: logScale ? 1 : 0 } });
    const cs = ch.addCandlestickSeries({ upColor: c.up, downColor: c.down, borderVisible: false, wickUpColor: c.up, wickDownColor: c.down, priceLineVisible: false });
    cs.setData(s.t.map((t, i) => ({ time: t, open: s.o[i], high: s.h[i], low: s.l[i], close: s.c[i] })).filter((b) => isNum(b.close)));
    line(ch, s.t, s.sma50, c.accent, 1.5);
    line(ch, s.t, s.sma200, c.ink2, 1.5);
    (D.study.volume_profile || []).forEach((lv) => cs.createPriceLine({ price: lv.price, color: c.ink3, lineWidth: 1, lineStyle: 2, axisLabelVisible: false }));
    ch.timeScale().fitContent();
    const dd = $("#dd-chart");
    dd.innerHTML = "";
    const ch2 = lc(dd);
    const a = ch2.addAreaSeries({ lineColor: c.down, topColor: "rgba(0,0,0,0)", bottomColor: c.down + "55", lineWidth: 1.5, priceLineVisible: false, lastValueVisible: false,
      priceFormat: { type: "custom", formatter: (v) => (v * 100).toFixed(0) + "%" }, invertFilledArea: true });
    a.setData(s.t.map((t, i) => ({ time: t, value: s.dd[i] })).filter((p) => isNum(p.value)));
    ch2.timeScale().fitContent();
  }

  function returns() {
    const cal = D.study.calendar;
    $("#yearly").innerHTML = bars(Object.entries(cal.yearly).map(([y, v]) => [y + (y === String(new Date().getFullYear()) ? " (so far)" : ""), v, v >= 0 ? css("--up") : css("--down")]), (v) => spct(v, 1));
    const m = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    const rows = Object.entries(cal.monthly_grid).map(([y, arr]) => `<tr><td>${y}</td>${arr.map((v) => `<td style="background:${heatColor(v)}">${isNum(v) ? (v * 100).toFixed(1) : ""}</td>`).join("")}</tr>`);
    const avg = `<tr><td><b>avg</b></td>${m.map((_, i) => { const r = cal.month_of_year.find((x) => x.month === i + 1); return `<td style="background:${heatColor(r && r.mean)}">${r ? (r.mean * 100).toFixed(1) : ""}</td>`; }).join("")}</tr>`;
    $("#monthly").innerHTML = `<div class="tbl"><table class="heat"><thead><tr><th>%</th>${m.map((x) => `<th>${x}</th>`).join("")}</tr></thead><tbody>${rows.join("")}${avg}</tbody></table></div>`;
    const dow = D.study.seasonality.day_of_week;
    $("#dow").innerHTML = table(["Day", "Avg move", "Up days", "Typical size", "t-stat", "Days"],
      dow.map((r) => [r.day, `<span class="${cls(r.mean)}">${spct(r.mean, 2)}</span>`, pct(r.up_share, 0), pct(r.vol, 2), num(r.t_stat, 2), r.n]));
    const hod = D.study.seasonality.hour_of_day;
    if (hod) {
      const c = colors();
      cj($("#hod"), "bar", hod.map((r) => String(r.hour_utc).padStart(2, "0")), [
        { label: "Avg range of the hour (%)", data: hod.map((r) => r.avg_range * 100), backgroundColor: c.accent, yAxisID: "y" },
        { label: "Avg return (bp)", data: hod.map((r) => r.mean * 1e4), type: "line", borderColor: c.ink2, backgroundColor: c.ink2, pointRadius: 2, yAxisID: "y1" },
      ], { scales: {
        x: { ticks: { color: c.ink3, font: { size: 10 } }, grid: { display: false }, title: { display: true, text: "hour (UTC)", color: c.ink3 } },
        y: { position: "left", ticks: { color: c.ink3 }, grid: { color: c.line2 } },
        y1: { position: "right", ticks: { color: c.ink3 }, grid: { display: false } } } });
    }
  }

  function volatility() {
    const s = D.study.series.daily, c = colors();
    const el = $("#vol-chart");
    el.innerHTML = "";
    const ch = lc(el);
    const items = [["Realised vol, 30 days", c.ink2]];
    line(ch, s.t, D.study.series.rv30.map((v) => (isNum(v) ? v * 100 : null)), c.ink2, 1.5);
    if (D.options_series && D.options_series.dvol) {
      line(ch, s.t, D.options_series.dvol, c.accent, 2);
      items.push(["DVOL (implied, 30 days)", c.accent]);
    }
    ch.timeScale().fitContent();
    $("#vol-legend").innerHTML = legend(items);
    const v = D.study.volatility, t = D.study.trend;
    $("#vol-figs").innerHTML = [
      ["Vol now (30d)", pct(v.rv30_now, 0), `${pct(v.rv30_percentile_now, 0)} percentile`],
      ["Median vol", pct(v.rv30_median, 0), `range ${pct(v.rv30_min, 0)}–${pct(v.rv30_max, 0)}`],
      ["Next-day link", num(v.autocorr.ret_lag1, 3), "return autocorrelation"],
      ["Vol clustering", num(v.autocorr.absret_lag1, 3), "|return| autocorrelation"],
      ["Above 200-day avg", pct(t.share_above_sma200, 0), "share of days"],
      ["Trend straightness", num(t.trend_efficiency_20, 2), `now ${num(t.trend_efficiency_now, 2)}`],
    ].map(([k, val, n]) => `<div class="fig"><span class="k">${k}</span><span class="v">${val}</span><span class="n">${n}</span></div>`).join("");
    $("#drawdowns").innerHTML = table(["Fall", "Peak", "Bottom", "Recovered", "Days down", "Days back"],
      D.study.drawdowns.map((d) => [`<span class="neg">${pct(d.depth, 1)}</span>`, d.peak, d.trough, d.recovered || "not yet", d.days_down, d.days_to_recover ?? "–"]));
    $("#crosses").innerHTML = t.crosses.length ? table(["Date", "Signal", "Price", "Next 30d", "Next 90d"],
      t.crosses.map((x) => [x.date, x.type, num(x.price, 0), `<span class="${cls(x.ret_30d)}">${spct(x.ret_30d, 1)}</span>`, `<span class="${cls(x.ret_90d)}">${spct(x.ret_90d, 1)}</span>`])) : "<p class='caveat'>No 50/200-day crossings in the window.</p>";
    const last = D.study.state.close;
    $("#levels").innerHTML = table(["Price level", "Volume share", "Distance"],
      (D.study.volume_profile || []).slice().reverse().map((l) => [num(l.price, 0), pct(l.share, 2), `<span class="${cls(l.price / last - 1)}">${spct(l.price / last - 1, 1)}</span>`]));
    const st = D.study.state;
    $("#state").innerHTML = table(["Reading", "Value"], [
      ["RSI (14)", num(st.rsi14, 1)], ["vs 20-day average", spct(st.vs_sma20, 1)], ["vs 50-day average", spct(st.vs_sma50, 1)],
      ["vs 200-day average", spct(st.vs_sma200, 1)], ["Average daily range (ATR)", pct(st.atr14_pct, 2)],
      ["52-week high / low", `${num(st["52w_high"], 0)} / ${num(st["52w_low"], 0)}`]]);
  }

  function correlations() {
    const cs = D.study.series.correlation, c = colors();
    const names = Object.keys(cs);
    if (!names.length) { $("#corr").closest("section").hidden = true; return; }
    const palette = [c.accent, c.ink2, c.up, c.down, c.warn];
    const el = $("#corr-chart");
    el.innerHTML = "";
    const ch = lc(el);
    names.forEach((n, i) => line(ch, cs[n].t, cs[n].v, palette[i % palette.length], 1.5));
    ch.timeScale().fitContent();
    $("#corr-legend").innerHTML = legend(names.map((n, i) => [n, palette[i % palette.length]]));
    const k = D.study.correlations;
    $("#corr").innerHTML = table(["With", "Whole period", "Last 90 days", "Lowest", "Highest"],
      names.map((n) => [n, num(k[n].full, 2), num(k[n].now, 2), num(k[n].min, 2), num(k[n].max, 2)]));
  }

  function options() {
    const sec = $("#options");
    if (!sec) return;
    const o = D.options || {}, ch = D.chain, os = D.options_series || {}, c = colors();
    const figs = [];
    if (o.dvol) figs.push(["Implied > realised", pct(o.dvol.vrp_positive_share, 0), `avg gap ${num(o.dvol.vrp_mean, 1)} vol pts`],
      ["IV beat next 30d", pct(o.dvol.iv_over_future_rv_share, 0), `avg overstatement ${num(o.dvol.iv_minus_future_rv_mean, 1)} pts`]);
    if (ch) figs.push(["Put/call open interest", num(ch.put_call_oi, 2), `${num(ch.total_oi_btc, 0)} BTC open`]);
    if (o.skew_90_110) figs.push(["Put skew (90/110)", num(o.skew_90_110.now, 1), `avg ${num(o.skew_90_110.mean, 1)} vol pts`]);
    if (o.pc_ratio) figs.push(["Put/call volume", num(o.pc_ratio.now, 2), `avg ${num(o.pc_ratio.mean, 2)}`]);
    $("#opt-figs").innerHTML = figs.map(([k, v, n]) => `<div class="fig"><span class="k">${k}</span><span class="v">${v}</span><span class="n">${n}</span></div>`).join("");
    const t = D.study.series.daily.t;
    if (os.atm_iv) {
      const el = $("#iv-chart");
      el.innerHTML = "";
      const x = lc(el);
      line(x, t, os.atm_iv_short, c.down, 1.2);
      line(x, t, os.atm_iv, c.accent, 1.8);
      line(x, t, os.atm_iv_long, c.ink2, 1.2);
      x.timeScale().fitContent();
      $("#iv-legend").innerHTML = legend([["ATM IV, under 7 days", c.down], ["ATM IV, 7–45 days", c.accent], ["ATM IV, 45–180 days", c.ink2]]);
      const el2 = $("#skew-chart");
      el2.innerHTML = "";
      const y = lc(el2);
      line(y, t, os.skew_90_110, c.accent, 1.5);
      y.timeScale().fitContent();
      const el3 = $("#pc-chart");
      el3.innerHTML = "";
      const z = lc(el3);
      line(z, t, os.pc_ratio, c.ink2, 1.5);
      z.timeScale().fitContent();
    }
    const qrow = (name, x) => x && x.next7d_by_quintile ? [name, ...x.next7d_by_quintile.map((v) => `<span class="${cls(v)}">${spct(v, 1)}</span>`), num(x.corr_next7d, 3)] : null;
    const qr = [qrow("Put skew", o.skew_90_110), qrow("Put/call volume", o.pc_ratio)].filter(Boolean);
    $("#quint").innerHTML = qr.length ? table(["Signal (5-day avg)", "Lowest fifth", "2nd", "3rd", "4th", "Highest fifth", "Correlation"], qr) : "";
    if (ch) {
      const ex = ch.expiries.filter((e) => e.dte > 0.5);
      cj($("#term"), "line", ex.map((e) => `${e.expiry.slice(5)} (${Math.round(e.dte)}d)`), [
        { label: "ATM implied vol by expiry", data: ex.map((e) => e.atm_iv), borderColor: c.accent, backgroundColor: c.accent, pointRadius: 3, tension: 0.2 }]);
      cj($("#smile"), "line", ch.smile.strike.map((k) => (k / 1000).toFixed(0) + "k"), [
        { label: `Implied vol by strike, ${ch.smile.expiry}`, data: ch.smile.iv, borderColor: c.ink2, backgroundColor: c.ink2, pointRadius: 2, tension: 0.2 }]);
      const ob = ch.oi_by_strike;
      cj($("#oi"), "bar", ob.strike.map((k) => (k / 1000).toFixed(0) + "k"), [
        { label: "Call open interest (BTC)", data: ob.call, backgroundColor: c.up },
        { label: "Put open interest (BTC)", data: ob.put, backgroundColor: c.down }], {
        scales: { x: { stacked: true, ticks: { color: c.ink3, font: { size: 10 }, maxRotation: 0, autoSkipPadding: 6 }, grid: { display: false } },
          y: { stacked: true, ticks: { color: c.ink3 }, grid: { color: c.line2 } } } });
      $("#expiries").innerHTML = table(["Expiry", "Days", "Calls OI", "Puts OI", "Put/call", "ATM IV", "Max pain", "vs spot"],
        ex.slice(0, 14).map((e) => [e.expiry, num(e.dte, 1), num(e.call_oi, 0), num(e.put_oi, 0), num(e.put_oi / e.call_oi, 2), num(e.atm_iv, 1),
          num(e.max_pain, 0), `<span class="${cls(e.max_pain / ch.spot - 1)}">${spct(e.max_pain / ch.spot - 1, 1)}</span>`]));
      $("#chain-when").textContent = `Chain snapshot ${ch.snapshot_time.slice(0, 16).replace("T", " ")} UTC, BTC index ${num(ch.spot, 0)}.`;
    }
  }

  function sessions() {
    const el = $("#sessions");
    if (!el || !D.sessions) return;
    el.innerHTML = table(["Session", "Avg hourly move", "Avg hourly range", "Share of all movement"],
      D.sessions.map((s) => [s.session, `<span class="${cls(s.mean_ret)}">${(s.mean_ret * 1e4).toFixed(2)} bp</span>`, pct(s.avg_range, 3), pct(s.share_of_daily_move, 0)]));
  }

  function verdict(m) {
    const k = m.metrics;
    const beats = k.accuracy > Math.max(k.baseline_majority, k.baseline_momentum) && k.p_value_vs_majority < 0.05;
    if (beats) return ["Edge found", "good"];
    if (k.accuracy > 0.5 && k.p_value_vs_coin < 0.05) return ["Weak, not better than simple rules", ""];
    return ["No real edge", "bad"];
  }

  function models() {
    const box = $("#models");
    box.innerHTML = "";
    const c = colors();
    ["daily_price", "daily_full", "hourly_price"].forEach((key) => {
      const m = D.models[key];
      if (!m) return;
      const k = m.metrics, [vt, vc] = verdict(m), s = m.strategy;
      const id = "m-" + key;
      const card = document.createElement("div");
      card.className = "panel model";
      card.innerHTML = `<div class="model-head"><h2>${esc(m.label)}</h2><span class="verdict ${vc}">${vt}</span></div>
        <p class="caveat">Tested ${m.test_start.slice(0, 10)} → ${m.test_end.slice(0, 10)} on ${k.n.toLocaleString()} bars it had never seen,
          retrained as it went. The latest bar reads <b>${pct(m.latest.p_up, 0)} chance of up</b>.</p>
        <div class="figs">
          ${[["Right direction", pct(k.accuracy, 1), `coin 50%, always-${k.up_rate >= 0.5 ? "up" : "down"} ${pct(k.baseline_majority, 1)}`],
            ["vs 'repeat last bar'", pct(k.baseline_momentum, 1), "simple momentum rule"],
            ["Ranking skill (AUC)", num(k.auc, 3), "0.5 = none"],
            ["Could be luck?", k.p_value_vs_coin < 0.05 ? "unlikely" : "yes", `p = ${num(k.p_value_vs_coin, 3)} vs coin`],
            ["When confident", k.confident_accuracy == null ? "–" : pct(k.confident_accuracy, 1), `${pct(k.confident_share, 0)} of bars`]]
            .map(([a, b, n]) => `<div class="fig"><span class="k">${a}</span><span class="v">${b}</span><span class="n">${esc(n)}</span></div>`).join("")}
        </div>
        <div class="grid2">
          <div class="panel"><h3>Trading its signals, after costs</h3><div class="chart" id="${id}-eq"></div><div id="${id}-leg"></div>
            ${table(["", "Return", "Sharpe", "Worst fall", "Trades"], [
              ["Long + short", `<span class="${cls(s.long_short.total_return)}">${spct(s.long_short.total_return, 1)}</span>`, num(s.long_short.sharpe, 2), pct(s.long_short.max_drawdown, 1), s.long_short.trades],
              ["Long or flat", `<span class="${cls(s.long_flat.total_return)}">${spct(s.long_flat.total_return, 1)}</span>`, num(s.long_flat.sharpe, 2), pct(s.long_flat.max_drawdown, 1), s.long_flat.trades],
              ["Buy and hold", `<span class="${cls(s.buy_hold.total_return)}">${spct(s.buy_hold.total_return, 1)}</span>`, num(s.buy_hold.sharpe, 2), pct(s.buy_hold.max_drawdown, 1), 1]])}</div>
          <div class="panel"><h3>What it leaned on (drop in skill when shuffled)</h3>${bars(m.importance.slice(0, 12).map((f) => [f.feature, f.importance]), (v) => num(v, 3))}</div>
        </div>`;
      box.appendChild(card);
      const t = m.series.t.map(secs);
      const ch = lc($(`#${id}-eq`), { rightPriceScale: { borderColor: c.line, mode: 1 }, timeScale: { borderColor: c.line, timeVisible: key.startsWith("hourly") } });
      line(ch, t, s.long_short_equity, c.accent, 2);
      line(ch, t, s.long_flat_equity, c.up, 1.5);
      line(ch, t, s.buy_hold_equity, c.ink3, 1.5);
      ch.timeScale().fitContent();
      $(`#${id}-leg`).innerHTML = legend([["Model long + short", c.accent], ["Model long or flat", c.up], ["Buy and hold", c.ink3]]);
    });
    const v = D.models.daily_vol;
    if (v) {
      const k = v.metrics, ki = v.metrics_implied;
      const card = document.createElement("div");
      card.className = "panel model";
      const good = k.r2 > k.r2_naive && k.mae < k.mae_naive;
      card.innerHTML = `<div class="model-head"><h2>${esc(v.label)}</h2><span class="verdict ${good ? "good" : "bad"}">${good ? "Beats 'same as last week'" : "No better than 'same as last week'"}</span></div>
        <div class="figs">
          ${[["Explained (R²)", num(k.r2, 2), `naive ${num(k.r2_naive, 2)}`], ["Correlation", num(k.corr, 2), `naive ${num(k.corr_naive, 2)}`],
            ["Avg error (log)", num(k.mae, 3), `naive ${num(k.mae_naive, 3)}`]].concat(ki ? [["Implied vol as forecast", num(ki.corr, 2), `correlation, R² ${num(ki.r2, 2)}`]] : [])
            .map(([a, b, n]) => `<div class="fig"><span class="k">${a}</span><span class="v">${b}</span><span class="n">${esc(n)}</span></div>`).join("")}
        </div><div class="chart" id="m-vol"></div><div id="m-vol-leg"></div>`;
      box.appendChild(card);
      const ch = lc($("#m-vol"));
      const t = v.series.t.map(secs);
      line(ch, t, v.series.actual.map((x) => x * 100), c.ink3, 1);
      line(ch, t, v.series.naive.map((x) => x * 100), c.ink2, 1, { lineStyle: 2 });
      line(ch, t, v.series.pred.map((x) => x * 100), c.accent, 2);
      ch.timeScale().fitContent();
      $("#m-vol-leg").innerHTML = legend([["What happened", c.ink3], ["Network forecast", c.accent], ["Last 5 days repeated", c.ink2]]);
    }
  }

  function render() {
    while (charts.length) { try { charts.pop().remove(); } catch (e) { /* already gone */ } }
    header(); price(); returns(); volatility(); options(); sessions(); correlations(); models();
    $("#generated").textContent = `Data to ${D.study.summary.end}; results generated ${D.generated.slice(0, 16).replace("T", " ")} UTC.`;
  }

  document.querySelectorAll("[data-scale]").forEach((b) => b.addEventListener("click", () => {
    logScale = b.dataset.scale === "log";
    document.querySelectorAll("[data-scale]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
    price();
  }));
  render();
  let pending;
  const rerender = () => { clearTimeout(pending); pending = setTimeout(render, 50); };
  matchMedia("(prefers-color-scheme: dark)").addEventListener("change", rerender);
  new MutationObserver(rerender).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
})();
