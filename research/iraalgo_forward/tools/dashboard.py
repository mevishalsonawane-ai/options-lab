"""Generate a portable snapshot that also polls the run directory when served."""
import json
from pathlib import Path
from html import escape
from datetime import datetime, timezone

ROOT=Path(__file__).resolve().parents[1]
RUN=ROOT/'run'


def load(n):return json.loads((RUN/n).read_text())


def render():
    result,status,audit=load('results.json'),load('status.json'),load('audit.json')
    events=[json.loads(l) for l in (RUN/'events.jsonl').read_text().splitlines()]
    last=next(e for e in reversed(events) if 'checkpoint' in e)
    family=load('frozen.json')['family']
    def money(v):return '₹'+format(v,',.0f')
    def pct(v):return '—' if v is None else format(v,'.1%')
    def rows(fold):
        return ''.join(f'<tr><td>{escape(name)}</td><td>{m["trades"]}</td><td>{pct(m["win_rate"])}</td><td>{money(m["net"])}</td><td>{money(m["max_drawdown"])}</td></tr>' for name,m in result['folds'][fold].items())
    tables=[]
    for i,title in [('3','Final · 21 Oct – 13 Nov'),('0','Validation · Apr – Jun'),('1','Validation · Jul – Aug'),('2','Validation · Sep – 19 Oct')]:
        tables.append(f'<details {"open" if i=="3" else ""}><summary>{title}</summary><div class="scroll"><table><thead><tr><th>Model</th><th>Trades</th><th>Wins</th><th>Net</th><th>Drawdown</th></tr></thead><tbody>{rows(i)}</tbody></table></div></details>')
    m=result['folds']['3'][family]
    kpis=[('Selected model',family),('Win rate',pct(m['win_rate'])),('Net P&L',money(m['net'])),('Profit factor',f"{m['profit_factor']:.2f}"),('Maximum drawdown',money(m['max_drawdown'])),('Average trade',money(m['avg_net']))]
    cards=''.join(f'<article><small>{label}</small><b>{value}</b></article>' for label,value in kpis)
    html='''<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>IraAlgo research monitor</title><style>
    :root{color-scheme:dark}*{box-sizing:border-box}body{margin:0;background:#101a22;color:#e2eeed;font:16px/1.5 "Inter",system-ui,sans-serif}main{max-width:1080px;margin:auto;padding:clamp(16px,5vw,65px)}h1{font-size:clamp(30px,6vw,58px);letter-spacing:-.05em;line-height:1.05;margin:.3em 0}h2{margin:1.8em 0 .6em;font-size:20px}small,.muted{color:#a8bbbf}#kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:10px}article,details,.panel{background:#192833;border:1px solid #354650;border-radius:9px;padding:14px}article b{display:block;font-size:24px}details{margin:8px 0}summary{cursor:pointer;color:#a8e3d1;font-weight:650}.scroll{overflow:auto}table{width:100%;border-collapse:collapse;white-space:nowrap}td,th{text-align:right;padding:9px;border-bottom:1px solid #334650}td:first-child,th:first-child{text-align:left}p{max-width:74ch}#live{color:#a8e3d1}code{overflow-wrap:anywhere}</style></head><body><main>'''
    html+=f'<small>IraAlgo / Research monitor</small><h1>Intraday options research</h1><p id="live">{escape(status["task"])} · {escape(status["utc"])}</p><p>One-lot NIFTY weekly options. The selected model failed the profitability and win-rate gates.</p><div id="kpis">{cards}</div>'
    html+=f'<h2>Execution</h2><div class="panel" id="execution">4 completed folds · 8 epochs per neural fit · CPU, 2 threads · {len(audit["inputs"])} input files · {sum(a["rows"] for a in audit["inputs"].values()):,} source rows · {audit["candidates"]:,} candidates. Last checkpoint <code>{last["checkpoint"]}</code>. Final epoch loss {last["train_loss"]:.4f}. Selected model final-period log loss {result["classification"]["3"][family]["validation_log_loss"]:.4f}, accuracy {pct(result["classification"]["3"][family]["accuracy"])}.</div>'
    html+='<h2>Strategy comparison</h2>'+''.join(tables)
    html+='<h2>Research status</h2><p>Historical replay complete. The latest full-session evaluation ends 13 November 2026. A separate 16 November quote sample covers part of one session. No validated strategy or production promotion.</p><p class="muted" id="sync">Saved execution snapshot.</p>'
    html+='''<script>
    const endpoint=location.protocol==='file:'?'https://raw.githubusercontent.com/mevishalsonawane-ai/options-lab/research/iraalgo-nn-20261124/research/iraalgo_forward/run/status.json':'run/status.json';
    async function poll(){try{const r=await fetch(endpoint,{cache:'no-store'});if(!r.ok)throw Error(r.status);const s=await r.json();
    document.querySelector('#live').textContent=[s.task,s.experiment,s.epoch?'epoch '+s.epoch:null,s.utc].filter(Boolean).join(' · ');
    document.querySelector('#sync').textContent='Status checked '+new Date().toLocaleTimeString()+'. Comparison tables show the verified November run.';
    if(s.task==='training')document.querySelector('#execution').textContent=['Experiment '+s.experiment,'epoch '+s.epoch,'loss '+s.train_loss,'checkpoint '+s.checkpoint].join(' · ');
    }catch(e){document.querySelector('#sync').textContent='Saved execution snapshot. Live status feed unavailable.'}}
    poll();setInterval(poll,15000);</script></main></body></html>'''
    (ROOT/'dashboard.html').write_text(html)


if __name__=='__main__':render()
