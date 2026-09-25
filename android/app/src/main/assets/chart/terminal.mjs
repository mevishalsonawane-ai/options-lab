// IraAlgo's chart terminal inside the Android app: the PC app's IraAlgo Charts
// widget (toolbar, drawing rail, 102 indicators, 51 drawing tools), fed by the
// app through window.IraBridge. Nothing here talks to the network.
import { createWidget } from './iraalgo-charts.widget.mjs';
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
  subscribeBars(req, onBar) {
    const timer = setInterval(async () => {
      if (paused) return;   // the Chart tab is not on screen
      try {
        const now = Math.floor(Date.now() / 1000);
        const bars = await call('bars', req.symbol, req.exchange, req.interval, now - 3 * 86400, now);
        bars.slice(-2).forEach(onBar);
      } catch (e) { /* the next tick tries again */ }
    }, 15000);
    return () => clearInterval(timer);
  },
};

// While another tab is showing, the chart stays loaded but stops asking for prices.
let paused = false;
window.__iraPause = (p) => { paused = !!p; };

const q = new URLSearchParams(location.search);
const symbol = q.get('symbol') || 'BANKNIFTY';
const exchange = q.get('exchange') || 'NSE';
const theme = q.get('theme') === 'dark' ? 'dark' : 'light';
document.body.classList.toggle('dark', theme === 'dark');

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
}
widget.on('data', (e) => {
  if (!e || !e.bars) return;
  const key = `${e.symbol}|${e.interval}`;
  if (placed.has(key)) return;
  placed.add(key);
  requestAnimationFrame(placeAtNow);
});

// The app switches symbol (e.g. from the option chain) through this.
window.__iraSetSymbol = (s, ex) => widget.setSymbol(s, ex);
