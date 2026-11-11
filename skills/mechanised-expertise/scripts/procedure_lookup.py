#!/usr/bin/env python3
"""Read ONE operational procedure or show a compact catalog.

No source evidence or user intent is inferred. This is an on-demand reader;
it does not choose or execute procedures, authorize work, or claim success.
"""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT = ROOT / 'assets' / 'source-mechanisms-v1.json'
ID = re.compile(r'PROC-\d{3}\Z')


def read_catalog(path):
    data=json.loads(path.read_text(encoding='utf-8'))
    if data.get('schema')!='mechanised-expertise/source-mechanisms-v1':
        raise ValueError('Unknown catalog schema')
    recipes=data.get('procedures')
    if not isinstance(recipes,list) or not recipes:
        raise ValueError('No procedures')
    by_id={}
    for p in recipes:
        ident=p.get('id') if isinstance(p,dict) else None
        if not isinstance(ident,str) or not ID.fullmatch(ident) or ident in by_id:
            raise ValueError('Malformed or duplicate procedure ID')
        for k in ('title','trigger','exclude','source_pattern_id','steps','oracle','stop'):
            if not p.get(k):
                raise ValueError(f'Missing {ident}.{k}')
        by_id[ident]=p
    return by_id


def select(catalog, ident=None, as_json=False):
    if ident is not None:
        if ident not in catalog:
            raise ValueError(f'Unknown procedure {ident}; inspect --list first')
        # One complete procedure only. It still needs current task evidence.
        return json.dumps(catalog[ident],indent=2,ensure_ascii=False)+'\n'
    items=[catalog[key] for key in sorted(catalog)]
    if as_json:
        return json.dumps([{'id':p['id'],'title':p['title'],'trigger':p['trigger'],
                            'exclude':p['exclude']} for p in items],indent=2,ensure_ascii=False)+'\n'
    lines=['Procedure index: candidate routes, NOT automatic diagnoses. Check both trigger and exclusion against inspected task facts.',
           '| ID | Purpose | Activate when | Skip when |','|---|---|---|---|']
    for p in items:
        vals=[p['id'],p['title'],p['trigger'],p['exclude']]
        lines.append('| '+' | '.join(s.replace('|','\\|').replace('\n',' ') for s in vals)+' |')
    lines.append('Inspect one procedure: python3 scripts/procedure_lookup.py --id PROC-005')
    return '\n'.join(lines)+'\n'


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--catalog',type=Path,default=DEFAULT)
    p.add_argument('--id',type=str,default=None,help='Print one complete procedure by exact ID')
    p.add_argument('--json',action='store_true',help='Print compact index as JSON')
    a=p.parse_args()
    try:
        print(select(read_catalog(a.catalog),a.id,a.json),end='')
        return 0
    except (OSError,ValueError,json.JSONDecodeError) as e:
        print('FAIL:',e,file=sys.stderr)
        return 2

if __name__=='__main__':
    sys.exit(main())