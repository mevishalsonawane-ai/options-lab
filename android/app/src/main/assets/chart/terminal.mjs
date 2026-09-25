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
      try {
        const now = Math.floor(Date.now() / 1000);
        const bars = await call('bars', req.symbol, req.exchange, req.interval, now - 3 * 86400, now);
        bars.slice(-2).forEach(onBar);
      } catch (e) { /* the next tick tries again */ }
    }, 15000);
    return () => clearInterval(timer);
  },
};

const q = new URLSearchParams(location.search);
const symbol = q.get('symbol') || 'BANKNIFTY';
const exchange = q.get('exchange') || 'NSE';
const theme = q.get('theme') === 'dark' ? 'dark' : 'light';
document.body.classList.toggle('dark', theme === 'dark');

const widget = createWidget('#t', {
  feed,
  symbol,
  exchange,
  interval: q.get('interval') || '5m',
  theme,
  timezone: 'Asia/Kolkata',
  persist: 'iraalgo-android',
  symbolSearch: (text) => call('search', text).catch(() => []),
  onOrder: (order) => bridge.order(JSON.stringify({ ...order, symbol: widget.symbol(), exchange: widget.exchange() })),
});

// A saved layout may remember another symbol or theme; what the app asked for wins.
if (widget.symbol() !== symbol || widget.exchange() !== exchange) widget.setSymbol(symbol, exchange);
if (widget.theme() !== theme) widget.setTheme(theme);

bridge.symbol(widget.symbol(), widget.exchange());
widget.on('symbol', () => bridge.symbol(widget.symbol(), widget.exchange()));

// The app switches symbol (e.g. from the option chain) through this.
window.__iraSetSymbol = (s, ex) => widget.setSymbol(s, ex);
