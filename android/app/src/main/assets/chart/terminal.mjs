// IraAlgo's chart terminal inside the Android app: the PC app's IraAlgo Charts
// widget (toolbar, drawing rail, 102 indicators, 51 drawing tools), fed by the
// app through window.IraBridge. Nothing here talks to the network.
import { createWidget, WIDGET_DIALOGS } from './iraalgo-charts.widget.mjs';
import { registerIndicator } from './iraalgo-charts.mjs';
import './iraalgo-charts.indicators.mjs';
import './iraalgo-charts.transform.mjs';
import './iraalgo-charts.profile.mjs';

const bridge = window.IraBridge;
const pending = new Map();
let seq = 0;

// The app answers each request by calling back here.
window.__iraReply = (id, ok, payload) => {
  const p = pending.get(id);
  if (!p) return;
  pending.delete(id);
  if (ok) p.resolve(JSON.parse(payload)); else p.reject(new Error(payload));
};

function call(fn, ...args) {
  const id = String(++seq);
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    bridge[fn](id, ...args.map((a) => (a == null ? '' : String(a))));
  });
}

function show(text) {
  const el = document.getElementById('msg');
  el.textContent = text;
  el.hidden = false;
  clearTimeout(show.t);
  show.t = setTimeout(() => { el.hidden = true; }, 4000);
}

const feed = {
  async getBars(req) {
    try {
      return await call('bars', req.symbol, req.exchange, req.interval, req.from, req.to);
    } catch (e) {
      show(e.message);
      return [];
    }
  },
  // Live updates: the newest bars are asked for again every 15 seconds while the chart is open.
  // In Live mode the app also pushes every trade (window.__iraTick), and then the poll stands back.
  subscribeBars(req, onBar) {
    live = { req, onBar };
    const timer = setInterval(async () => {
      if (paused) return;   // the Chart tab is not on screen
      if (Date.now() - lastTickAt < 20000) return;   // the stream is moving the candle
      try {
        const now = Math.floor(Date.now() / 1000);
        const bars = await call('bars', req.symbol, req.exchange, req.interval, now - 3 * 86400, now);
        bars.slice(-2).forEach(onBar);
      } catch (e) { /* the next tick tries again */ }
    }, 15000);
    return () => { clearInterval(timer); if (live && live.onBar === onBar) live = null; };
  },
};

// ---- the live candle (Zerodha stream, Live mode) --------------------------------------
let live = null;
let lastTickAt = 0;

/**
 * One trade from the app: the last candle takes it (close, and high/low if it breaks
 * them), or, when the trade falls in the next candle's time, a new candle starts.
 * Only intraday candles up to 30 minutes start new bars; hourly and daily just move.
 */
window.__iraTick = (price, t) => {
  if (!live || !(price > 0)) return;
  const data = widget.series.getData();
  const last = data[data.length - 1];
  if (!last) return;
  lastTickAt = Date.now();
  const close = last.close ?? last.value ?? price;
  const open = last.open ?? close, high = last.high ?? close, low = last.low ?? close;
  const step = barSeconds(widget.interval());
  const bucket = step <= 1800 ? Math.floor(t / step) * step : last.time;
  const bar = bucket > last.time
    ? { time: bucket, open: price, high: price, low: price, close: price, volume: 0 }
    : { time: last.time, open, high: Math.max(high, price), low: Math.min(low, price), close: price, volume: last.volume ?? 0 };
  live.onBar(bar);
};

// While another tab is showing, the chart stays loaded but stops asking for prices.
let paused = false;
window.__iraPause = (p) => { paused = !!p; };

const q = new URLSearchParams(location.search);
const symbol = q.get('symbol') || 'BANKNIFTY';
const exchange = q.get('exchange') || 'NSE';
const theme = q.get('theme') === 'dark' ? 'dark' : 'light';
document.body.classList.toggle('dark', theme === 'dark');

// ---- the owner's Pine scripts (written in the app), as indicators ------------------------
// Registered before the widget starts so a saved layout that shows one can restore it.
const pineRuns = new Map();
let pineList = [];
function chartNow() {
  try { return [widget.symbol(), widget.interval()]; } catch (e) { return [symbol, '5m']; }
}
function pineRegister() {
  try { pineList = JSON.parse(bridge && bridge.pineList ? bridge.pineList() : '[]'); } catch (e) { pineList = []; }
  for (const p of pineList) {
    // Markers need a series to sit on: a script with none gets an invisible one.
    const plots = p.plots.length ? p.plots : [{ key: '_m', title: 'Signals', color: '#00000000', style: 'hidden' }];
    registerIndicator({
      id: p.id, name: p.name, category: 'Pine', placement: p.overlay ? 'onchart' : 'pane',
      inputs: [
        ...p.inputs.map((i) => i.kind === 'bool' ? { key: i.key, type: 'boolean', label: i.label, default: !!i.default }
          : i.kind === 'source' ? { key: i.key, type: 'source', label: i.label, default: i.default || 'close' }
          : { key: i.key, type: 'number', label: i.label, default: Number(i.default) || 0,
              ...(i.min != null ? { min: i.min } : {}), ...(i.max != null ? { max: i.max } : {}), step: i.kind === 'int' ? 1 : 0.1 }),
        ...plots.map((pl) => ({ key: pl.key + 'Color', type: 'color', label: pl.title + ' colour', default: pl.color })),
      ],
      plots: plots.map((pl) => ({
        key: pl.key, title: pl.title, colorKey: pl.key + 'Color',
        type: pl.style === 'histogram' ? 'histogram' : pl.style === 'column' ? 'column' : 'line',
        style: pl.style === 'points' ? { markersOnly: true, markerRadius: 1.5 } : { lineWidth: pl.style === 'hidden' ? 0 : pl.style === 'hline' ? 1 : 1.5 },
      })),
      calc: (bars, settings) => {
        const ins = {};
        for (const i of p.inputs) if (settings && settings[i.key] !== undefined) ins[i.key] = settings[i.key];
        const [sym, iv] = chartNow();
        let r = null;
        try {
          r = JSON.parse(bridge.pineCalc(p.id, sym, iv,
            JSON.stringify(bars.map((b) => [b.time, b.open, b.high, b.low, b.close, b.volume ?? 0])), JSON.stringify(ins)));
        } catch (e) { r = null; }
        pineRuns.set(p.id, r);
        const out = {};
        for (const pl of plots) out[pl.key] = pl.key === '_m' ? bars.map((b) => b.close) : (r && r.values && r.values[pl.key]) || bars.map(() => null);
        return out;
      },
      markers: ({ bars }) => {
        const r = pineRuns.get(p.id);
        if (!r || !r.markers) return [];
        return r.markers.filter((m) => bars[m[0]]).map((m) => ({
          time: bars[m[0]].time, position: m[1] ? 'aboveBar' : 'belowBar', shape: m[2], size: 'small', color: m[3], ...(m[4] ? { text: m[4] } : {}),
        }));
      },
    });
  }
}
pineRegister();

const widget = createWidget('#t', {
  feed,
  symbol,
  exchange,
  interval: '5m',
  theme,
  timezone: 'Asia/Kolkata',
  persist: 'iraalgo-android',
  symbolSearch: (text) => call('search', text).catch(() => []),
  onOrder: (order) => bridge.order(JSON.stringify({ ...order, symbol: widget.symbol(), exchange: widget.exchange() })),
});

// A saved layout may remember another symbol, interval or theme; what the app asks for wins,
// and the chart always opens on 5-minute candles.
if (widget.interval() !== '5m') widget.setInterval('5m');
if (widget.symbol() !== symbol || widget.exchange() !== exchange) widget.setSymbol(symbol, exchange);
if (widget.theme() !== theme) widget.setTheme(theme);

window.__iraBooted = true;   // boot.js stops waiting
bridge.symbol(widget.symbol(), widget.exchange());
widget.on('symbol', () => bridge.symbol(widget.symbol(), widget.exchange()));

// Open at the latest candle with at least three hours in view, once per symbol and interval
// (after that the owner's own panning and zooming are left alone).
const SECONDS = { m: 60, h: 3600, d: 86400, w: 604800, M: 2592000 };
function barSeconds(code) {
  const m = /^(\d*)([a-zA-Z])/.exec(code || '5m');
  return m ? (Number(m[1] || 1) * (SECONDS[m[2]] || SECONDS[m[2].toLowerCase()] || 300)) : 300;
}
const placed = new Set();
function placeAtNow() {
  const n = widget.series.getData().length;
  if (!n) return;
  const step = barSeconds(widget.interval());
  // Intraday: at least 3 hours of candles (and never fewer than 40); daily and up: about 60 bars.
  const inView = step < 86400 ? Math.max(40, Math.ceil((3 * 3600) / step) + 4) : 60;
  widget.chart.setVisibleLogicalRange({ from: Math.max(0, n - inView), to: n + 3 });
  // Fit the price axis to this symbol: a range kept from the previous symbol (or a saved
  // layout) would leave e.g. NIFTY's candles far outside BANKNIFTY's 55,000s.
  try { for (const pane of widget.chart.panes()) pane.priceScale.setAutoScale(true); } catch (err) { /* older build */ }
}
// Every new symbol or interval opens at the latest candle again.
widget.on('symbol', () => placed.clear());
widget.on('interval', () => placed.clear());
widget.on('data', (e) => {
  if (!e || !e.bars) return;
  const key = `${e.symbol}|${e.interval}`;
  if (placed.has(key)) return;
  placed.add(key);
  requestAnimationFrame(() => {
    placeAtNow();
    // Tell the app the chart actually drew, and at what size: a 0 x 0 or missing report
    // means this phone's WebView cannot draw it, and the app shows its basic chart instead.
    const el = document.getElementById('t');
    const report = () => { try { bridge.painted && bridge.painted(el.clientWidth | 0, el.clientHeight | 0, e.bars | 0); } catch (err) { /* older app */ } };
    report();
    // Report again when the chart's size changes (it may start small and grow).
    if (typeof ResizeObserver !== 'undefined' && !window.__iraSized) { window.__iraSized = true; new ResizeObserver(report).observe(el); }
  });
});

// The app changed its Pine scripts: register them again and show the ones marked for the chart.
window.__iraPine = () => {
  pineRegister();
  try {
    for (const ind of widget.chart.indicators().slice()) if (String(ind.indicatorId).startsWith("pine-")) widget.chart.removeIndicator(ind.id);
    for (const p of pineList) if (p.onChart) { try { widget.chart.addIndicator(p.id); } catch (e) { window.__iraPineErr = String(e && e.message || e); } }
  } catch (e) { window.__iraPineErr = String(e && e.message || e); }
};

// The gear after an indicator's name in the legend opens its settings (inputs and style).
widget.chart.on('indicatorSettings', (e) => {
  try { WIDGET_DIALOGS.indicatorSettings(widget.context, undefined, { instanceId: e && e.instanceId }); } catch (err) { /* older chart build */ }
});

// The app switches symbol (e.g. from the option chain) through this.
window.__iraSetSymbol = (s, ex) => widget.setSymbol(s, ex);
