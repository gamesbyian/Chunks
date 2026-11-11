#!/usr/bin/env python3
"""Validate source-grounded operational recipes, not their empirical efficacy."""
import csv
import json
import re
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ALLOWED={'inspect','enumerate','trace','compare','route','record','verify','measure','reproduce'}
PROCEDURE_FIELDS={'id','source_pattern_id','title','provenance_status','trigger',
                  'exclude','inputs','steps','decision_gate','oracle','stop'}
STEP_FIELDS={'op','uses','produces','instruction'}
GATE_FIELDS={'question','if_yes','if_no','discriminator'}
VAGUE=re.compile(r'(?i)(think like an? expert|use (?:your )?intuition|'
                 r'apply common sense|choose what feels right|consider everything)')
def require(value,where,n=15):
    if not isinstance(value,str) or len(value.strip())<n:
        raise ValueError('Missing concrete '+where)
    return value.strip()

def audit(data,originals):
    if not isinstance(data,dict) or data.get('schema')!='mechanised-expertise/source-mechanisms-v1':
        raise ValueError('Unknown schema')
    if 'not demonstrated improvements' not in require(data.get('status'),'provenance warning',40):
        raise ValueError('Effectiveness disclaimer missing')
    items=data.get('procedures')
    if not isinstance(items,list) or len(items)<8:
        raise ValueError('Insufficient curated procedures')
    used_ids=set()
    for p in items:
        if not isinstance(p,dict) or set(p)!=PROCEDURE_FIELDS:
            raise ValueError('Incomplete procedure')
        ident=require(p['id'],'id',5)
        if ident in used_ids:
            raise ValueError('Duplicate procedure id')
        used_ids.add(ident)
        if p['source_pattern_id'] not in originals:
            raise ValueError('Procedure without pinned source ID')
        if p['provenance_status']!='editorial_procedure_derived_from_pinned_source_not_effectiveness_tested':
            raise ValueError('Procedure misstates original provenance')
        for k in ('title','trigger','exclude','oracle','stop'):
            require(p[k],ident+'.'+k)
        if p['trigger']==p['exclude']:
            raise ValueError('No rejection gate')
        inputs=p['inputs']
        if not isinstance(inputs,list) or len(inputs)<2 or len(set(inputs))!=len(inputs):
            raise ValueError('Missing or duplicate required inputs')
        known=set(inputs)
        steps=p['steps']
        if not isinstance(steps,list) or not 3<=len(steps)<=7:
            raise ValueError('Recipe not bounded to 3-7 operations')
        for n,s in enumerate(steps):
            if not isinstance(s,dict) or set(s)!=STEP_FIELDS or s['op'] not in ALLOWED:
                raise ValueError('Unknown operation')
            refs=s['uses']
            if not isinstance(refs,list) or not refs or len(set(refs))!=len(refs) or any(v not in known for v in refs):
                raise ValueError('Undefined, forward or duplicate operation input')
            out=require(s['produces'],'output',5)
            if out in known:
                raise ValueError('Output overwrites earlier fact or step')
            instruction=require(s['instruction'],'operation step',50)
            if VAGUE.search(instruction):
                raise ValueError('Nebulous instruction')
            known.add(out)
        gate=p['decision_gate']
        if not isinstance(gate,dict) or set(gate)!=GATE_FIELDS:
            raise ValueError('Decision gate incomplete')
        for k in GATE_FIELDS:
            require(gate[k],ident+'.'+k,24)
        if '?' not in gate['question'] or gate['if_yes']==gate['if_no']:
            raise ValueError('No consequential opposing decisions')
        if VAGUE.search(gate['discriminator']):
            raise ValueError('Nebulous discriminator')
    return {'status':'PASS','source_patterns':len(originals),'curated_procedures':len(items),
            'warning':'Validates structure and linked IDs, not source accuracy or effectiveness.'}

def main():
    path=ROOT/'assets/source-mechanisms-v1.json'
    original=ROOT/'assets/source-to-expertise-v09.csv'
    try:
        source_ids=set()
        for csv_path in (original, ROOT/'assets/source-to-expertise-v11-additions.csv'):
            with csv_path.open(encoding='utf-8-sig',newline='') as f:
                for row in csv.DictReader(f):
                    if row['id'] in source_ids:
                        raise ValueError('Duplicate source ID across source tables')
                    source_ids.add(row['id'])
        result=audit(json.loads(path.read_text(encoding='utf-8')),source_ids)
        print(json.dumps(result,sort_keys=True))
        return 0
    except (OSError,ValueError,json.JSONDecodeError,UnicodeError) as e:
        print('FAIL:',e,file=sys.stderr)
        return 1
if __name__=='__main__':
    sys.exit(main())
