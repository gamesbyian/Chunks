#!/usr/bin/env python3
"""Offline integrity checks for source-to-inquiry and composite mechanism claims.

This checks structural and provenance *consistency*, not upstream correctness,
semantic equivalence, effectiveness or real app runtime acceptance.
"""
import argparse
import csv
import json
import re
import sys
from pathlib import Path

REQUIRED = {'id', 'family', 'source_repository', 'source_commit', 'source_path',
            'source_line', 'source_url', 'source_paraphrase', 'editorial_status',
            'recognition_cue', 'decision_question', 'research_strategy',
            'model_trigger', 'decision_proposal', 'applicability_exception',
            'independent_oracle'}
FIELDS = ('title', 'trigger_cue', 'skip_if', 'consequential_question',
          'model_when', 'model_skip_when', 'proposed_choice', 'reversal_trigger',
          'independent_oracle', 'stop_rule', 'owner_escalation')
SHA = re.compile(r'[0-9a-f]{40}')
ID = re.compile(r'PRX-\d{3}')

def validate_sources(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        rd = csv.DictReader(f)
        if not rd.fieldnames or set(rd.fieldnames) != REQUIRED:
            raise ValueError('Unexpected source CSV header')
        rows = list(rd)
    if len(rows) < 22:
        raise ValueError('Source corpus unexpectedly truncated')
    results = {}
    links = set()
    for row in rows:
        sid = row['id']
        if not ID.fullmatch(sid) or sid in results:
            raise ValueError(f'Invalid/duplicate source ID: {sid}')
        if not SHA.fullmatch(row['source_commit']):
            raise ValueError(f'Unpinned source {sid}')
        if not row['source_line'].isdigit() or int(row['source_line']) < 1:
            raise ValueError(f'Invalid source line: {sid}')
        repo, sha, name = row['source_repository'], row['source_commit'], row['source_path']
        canonical = f'https://github.com/{repo}/blob/{sha}/{name}#L{row["source_line"]}'
        if row['source_url'] != canonical or canonical in links:
            raise ValueError(f'Incorrect/duplicate source link: {sid}')
        links.add(canonical)
        if 'not effectiveness-tested' not in row['editorial_status']:
            raise ValueError(f'Editorial/effectiveness distinction missing: {sid}')
        if '?' not in row['decision_question'] or any(not row[k].strip() for k in REQUIRED):
            raise ValueError(f'Incomplete inquiry source: {sid}')
        results[sid] = row
    return results


def validate_compositions(obj, sources):
    if not isinstance(obj, dict) or obj.get('schema') != 'mechanised-expertise/compositions-v1':
        raise ValueError('Unknown composition schema')
    if 'no measured improvement' not in obj.get('editorial_status', '').lower():
        raise ValueError('Editorial hypothesis disclaimer missing')
    items = obj.get('compositions')
    if not isinstance(items, list) or len(items) < 8:
        raise ValueError('Expected substantive composition collection')
    seen = set()
    for entry in items:
        if not isinstance(entry, dict) or not entry.get('id') or entry['id'] in seen:
            raise ValueError('Invalid/duplicate composition ID')
        seen.add(entry['id'])
        if set(entry) != set(FIELDS) | {'id','source_pattern_ids','evidence_status','answer_sources'}:
            raise ValueError(f'Incomplete/extra composition fields: {entry["id"]}')
        for key in FIELDS:
            if not isinstance(entry[key], str) or len(entry[key].strip()) < 20:
                raise ValueError(f'Empty/too vague {key}: {entry["id"]}')
        if '?' not in entry['consequential_question']:
            raise ValueError(f'Question missing: {entry["id"]}')
        if entry['evidence_status'] != 'editorial_hypothesis_not_effectiveness_tested':
            raise ValueError(f'Effectiveness overclaim: {entry["id"]}')
        refs = entry['source_pattern_ids']
        if not isinstance(refs,list) or len(refs)<2 or len(set(refs))!=len(refs):
            raise ValueError(f'Insufficient or duplicated sources: {entry["id"]}')
        if any(x not in sources for x in refs):
            raise ValueError(f'Unrecognized original source: {entry["id"]}')
        if len({sources[x]['source_repository'] for x in refs}) < 2:
            raise ValueError(f'Only one publisher repository: {entry["id"]}')
        if not isinstance(entry['answer_sources'],list) or len(entry['answer_sources'])<2 or any(not isinstance(x,str) or len(x)<15 for x in entry['answer_sources']):
            raise ValueError(f'Missing real evidence paths: {entry["id"]}')
        if entry['model_when'] == entry['model_skip_when']:
            raise ValueError(f'Model activation has no disqualifier: {entry["id"]}')
        if entry['trigger_cue'] == entry['skip_if']:
            raise ValueError(f'No opposing applicability criteria: {entry["id"]}')
    return {'source_rules':len(sources),'compositions':len(items),'compositions_using_multiple_source_repositories':len(items)}


def audit(csv_path, json_path):
    return validate_compositions(json.loads(json_path.read_text(encoding='utf-8')), validate_sources(csv_path))


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources',type=Path,default=root/'assets/source-to-expertise-v09.csv')
    parser.add_argument('--compositions',type=Path,default=root/'assets/compositions-v1.json')
    args=parser.parse_args()
    try:
        outcome=audit(args.sources,args.compositions)
        print(json.dumps({'status':'PASS',**outcome},sort_keys=True))
    except (OSError, ValueError, json.JSONDecodeError, UnicodeError) as exc:
        print(f'FAIL: {exc}',file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
