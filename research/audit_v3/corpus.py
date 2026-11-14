"""Read-only coverage inventory and structured research/knowledge consolidation."""
import collections
import csv
import hashlib
import io
import json
import gzip
from pathlib import Path
import re
import subprocess

import yaml
from research.neural_v2.pipeline import dump

ROOT=Path(__file__).resolve().parents[2]


def inspect_corpus(root=ROOT,out=None):
    names=subprocess.check_output(['git','ls-tree','-r','--name-only','HEAD','research'],cwd=root,text=True).splitlines()
    rows=[];counts=collections.Counter();verdicts=collections.Counter();families=collections.Counter()
    experiments=[];issues=[];variants=0;unknown=0;topic=collections.Counter();total=0
    for name in names:
        if name.startswith('research/audit_v3/'): continue
        path=root/name;suffix=path.suffix
        row=dict(path=name,type=suffix or 'marker',read=False)
        is_text=suffix in {'.md','.py','.yaml','.json','.csv','.txt','.sh','.flag','.log','.patch','.pine','.java','.c','.lock','.jsonl','.html'} or (not suffix and (path.name.isupper() or path.name=='.gitignore'))
        if is_text and path.is_file():
            raw=path.read_bytes();total+=len(raw);row.update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest(),read=True)
            text=raw.decode('utf-8',errors='replace');counts[suffix or 'marker']+=1
            for key,rx in [('leakage',r'look.ahead|leakage'),('holdout_reuse',r're.used|already seen|not pristine|second look'),
                           ('costs',r'spread|slippage|brokerage'),('win_profit_tradeoff',r'win rate|winrate'),('bugs',r'\bbug\b|erratum|incorrect')]:
                if re.search(rx,text,re.I):topic[key]+=1
            try:
                if suffix=='.json':json.loads(text)
                elif suffix=='.yaml':
                    value=yaml.safe_load(text)
                    if '/experiments/entries/' in name and isinstance(value,dict):
                        verdicts[str(value.get('verdict'))]+=1;families[str(value.get('family'))]+=1
                        v=value.get('variants_tried');variants+=v if isinstance(v,int) else 0;unknown+=v is None
                        experiments.append({k:value.get(k) for k in ['id','title','family','date','doc','prereg','period','verdict','variants_tried','holdout_read']})
                elif suffix=='.csv':row['columns']=next(csv.reader(io.StringIO(text)),[])
            except (ValueError,yaml.YAMLError,csv.Error) as exc:
                issues.append(dict(path=name,error=type(exc).__name__))
        row['text_expected']=is_text;rows.append(row)
    cards=yaml.safe_load((root/'research/knowledge/strategies.yaml').read_text())
    matrix=[]
    for c in cards:
        tags=' '.join(c.get('tags',[]));sid=c['id']
        if 'orb' in sid or 'fade' in sid:proposal='Re-entry caps and plain-rule parity first; generic filters failed R169/R170; no new edge established.'
        elif 'liquidity' in sid or 'pullback' in sid:proposal='Preserve rare runners; retest exits separately. Avoid transferring Donchian profit locks blindly.'
        elif 'night' in sid:proposal='Separate overnight gap/expiry risk; this intraday direction model is not eligible.'
        elif 'crypto' in sid:proposal='Separate crypto features, costs and horizon; Indian-index model is ineligible.'
        elif 'mcx' in sid:proposal='Validate actual futures/options expiries and data holes; separate model needed.'
        elif 'straddle' in sid or 'selling' in tags:proposal='Use magnitude/IV and both-leg liquidity, not binary direction alone; reprice stale wings.'
        elif 'hero' in sid:proposal='Validate cheap-premium costs and exercise tax; tiny targets can inflate win rate while losing.'
        else:proposal='Reproduce original ledger first; evaluate remove-only calibrated gate only on matching instrument/horizon.'
        matrix.append(dict(id=sid,name=c['name'],instruments=c.get('instruments'),knowledge_verdict=c.get('verdict'),
            source_docs=sorted({f['src'] for f in c.get('facts',[])}),proposal=proposal,new_win_rate_improvement_verified=False))
    for sid,name,doc,proposal in [
        ('r51','R51 Donchian','research/hunt/r163_donchian_picker/results/report.md','R151/R163 calibration and occupied-side parity; do not repeat failed 60% target search.'),
        ('r72','R72 Breadth Donchian','research/hunt/r163_donchian_picker/results/report.md','Keep breadth/entry semantics; compare net and drawdown, not only selected win rate.'),
        ('r52','R52 Open outside value','research/HUNT_R106_SMALL_TARGETS.md','Existing high-win target result needs current costs and genuinely fresh replication.'),
        ('cisd','CISD neural-filtered entry','research/HUNT_R133_ICT_NN_EXITS.md','Design win rate is not holdout win rate; only 35 later trades in cited variant.'),
        ('confirmed-breakout','Latest confirmed breakout','research/neural_v2/strategy_results/REPORT.md','Explicitly test runner-preserving exits and calibrated remove-only direction gate.')]:
        matrix.append(dict(id=sid,name=name,source_docs=[doc],knowledge_verdict='missing_from_strategy_cards',proposal=proposal,new_win_rate_improvement_verified=False))
    result=dict(tracked_research_files=len(rows),text_files_read=sum(counts.values()),text_bytes_read=total,
        read_by_type=dict(counts),missing_text_files=[r['path'] for r in rows if not r['read'] and r['text_expected']],
        structured_parse_issues=issues,experiment_entries=len(experiments),sum_logged_variant_counts_not_unique=variants,
        entries_with_unknown_variant_count=unknown,verdicts=dict(verdicts),families=dict(families),topic_file_hits=dict(topic),
        strategy_cards=len(cards),strategy_matrix_rows=len(matrix),
        limits='File coverage and parsing are not independent reproduction or a manual line-by-line proof of every study.')
    if out:
        out.mkdir(parents=True,exist_ok=True);dump(out/'coverage.json',result)
        for filename,value in [('inventory.json.gz',rows),('experiments.json.gz',experiments)]:
            (out/filename).write_bytes(gzip.compress(json.dumps(value,default=str,separators=(',',':')).encode(),mtime=0))
        dump(out/'strategy_matrix.json',matrix)
        lines=['# Strategy improvement and eligibility matrix','','No row asserts an improvement that has not been replayed.','',
            '| Family | Existing card status | Research-informed action | New improvement verified |','|---|---|---|---|']
        for r in matrix:lines.append(f"| {r['name']} | {r['knowledge_verdict']} | {r['proposal']} | No |")
        (out/'STRATEGY_MATRIX.md').write_text('\n'.join(lines)+'\n')
    return result


if __name__=='__main__':
    print(json.dumps(inspect_corpus(out=Path(__file__).parent/'audit'),indent=2))
