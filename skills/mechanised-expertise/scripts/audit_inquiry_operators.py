#!/usr/bin/env python3
"""Check the coherence of heuristic inquiry operators and fictional examples.

Passing means only that authored procedures have traceable structure. This tool
cannot infer whether a question matters to a real user or whether a source is right.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

ALLOWED_GAPS = {
    'acquisition_omission', 'alias_mismatch', 'schema_blind_spot',
    'underlinked_fact', 'contradiction', 'unknown_fact',
    'version_boundary', 'deliberate_exclusion', 'candidate_frontier'
}
GATES = {'not_needed', 'conditional', 'prefer'}
FIELDS = {
    'id', 'name', 'recognize', 'reject_when', 'mechanism', 'question_template',
    'contrasting_answers', 'decision_consequence', 'first_sources',
    'map_threshold', 'gap_checks', 'disconfirmation', 'oracle', 'stop', 'references'
}
APPLICATION_FIELDS = {
    'operator_id', 'question', 'answer_source', 'if_first_answer',
    'if_second_answer', 'reversal_observation', 'oracle'
}


def _string(x: object, field: str, min_chars: int = 12) -> str:
    if not isinstance(x, str) or len(x.strip()) < min_chars:
        raise ValueError(f'Missing or trivial {field}')
    return x.strip()


def _url(x: str) -> None:
    u = urlparse(x)
    if u.scheme != 'https' or not u.netloc or u.username or u.password:
        raise ValueError('Untrustworthy citation URL syntax')


def check_catalog(catalog: object) -> dict[str, dict]:
    if not isinstance(catalog, dict) or catalog.get('schema') != 'mechanised-expertise/inquiry-operators-v1':
        raise ValueError('Unknown catalog schema')
    status = _string(catalog.get('status'), 'hypothesis notice', 50).lower()
    if 'no measured' not in status:
        raise ValueError('Must distinguish suggested method from measured effectiveness')
    ops = catalog.get('operators')
    if not isinstance(ops, list) or len(ops) < 8:
        raise ValueError('Operator collection incomplete')
    indexed = {}
    for item in ops:
        if not isinstance(item, dict) or set(item) != FIELDS:
            raise ValueError('Operator structure missing/extra fields')
        ident = item['id']
        if not isinstance(ident, str) or not re.fullmatch('[A-Z]+(?:-[A-Z]+)+', ident) or ident in indexed:
            raise ValueError('Invalid/duplicate operator ID')
        indexed[ident] = item
        for field in ('name','recognize','reject_when','mechanism','question_template',
                      'decision_consequence','disconfirmation','oracle','stop'):
            _string(item[field], f'{ident}.{field}')
        if item['recognize'].casefold() == item['reject_when'].casefold():
            raise ValueError('No opposing applicability criteria')
        if '?' not in item['question_template'] or '{' not in item['question_template']:
            raise ValueError('Operator must generate a parameterized question')
        answers = item['contrasting_answers']
        if not isinstance(answers, list) or len(answers) != 2:
            raise ValueError('Exactly two plausible opposing answers required')
        if any(not isinstance(v, str) or len(v.strip()) < 15 for v in answers) or answers[0].casefold() == answers[1].casefold():
            raise ValueError('Opposing answers indistinguishable')
        sources = item['first_sources']
        if not isinstance(sources, list) or len(sources) < 2 or any(not isinstance(v,str) or len(v.strip()) < 15 for v in sources):
            raise ValueError('Missing reliable answer paths')
        if item['map_threshold'] not in GATES:
            raise ValueError('Invalid map threshold')
        gaps = item['gap_checks']
        if not isinstance(gaps, list) or not gaps or len(set(gaps)) != len(gaps) or any(x not in ALLOWED_GAPS for x in gaps):
            raise ValueError('Invalid/duplicate map gap class')
        refs = item['references']
        if not isinstance(refs, list) or len(refs) < 2 or len(set(refs)) != len(refs):
            raise ValueError('Each operator needs at least two distinct supporting sources')
        for link in refs:
            _url(link)
    return indexed


def check_cases(cases: object, ops: dict[str, dict]) -> dict:
    if not isinstance(cases, dict) or cases.get('schema') != 'mechanised-expertise/operator-applications-v1':
        raise ValueError('Unknown application schema')
    if 'no production' not in _string(cases.get('status'), 'unverified case disclaimer', 30).lower():
        raise ValueError('Examples must not claim production tests')
    items = cases.get('cases')
    if not isinstance(items, list) or len(items) < 3:
        raise ValueError('Expected independent teaching cases and negative control')
    seen = set()
    negative_controls = 0
    for item in items:
        if not isinstance(item, dict) or set(item) != {'id','goal','context','applied','skipped','model_gate','model_reason','evidence_status'}:
            raise ValueError('Unexpected case structure')
        ident = _string(item['id'], 'case id', 5)
        if ident in seen:
            raise ValueError('Duplicate case')
        seen.add(ident)
        _string(item['goal'], f'{ident}.goal')
        _string(item['context'], f'{ident}.context')
        _string(item['model_reason'], f'{ident}.model_reason')
        if item['model_gate'] not in GATES or item['evidence_status'] != 'unverified_teaching_case':
            raise ValueError('Unsupported model gate or effectiveness claim')
        applied = item['applied']; skipped = item['skipped']
        if not isinstance(applied,list) or not isinstance(skipped,list):
            raise ValueError('Invalid operator decisions')
        if not applied:
            negative_controls += 1
            if item['model_gate'] != 'not_needed' or len(skipped) < 2:
                raise ValueError('Negative control must reject unnecessary modelling and operators')
        refs = set()
        for app in applied:
            if not isinstance(app,dict) or set(app) != APPLICATION_FIELDS:
                raise ValueError('Incomplete question application')
            opid = app['operator_id']
            if opid not in ops or opid in refs:
                raise ValueError('Unknown or repeated operator')
            refs.add(opid)
            _string(app['question'], 'question')
            if '?' not in app['question']:
                raise ValueError('Not a discriminating question')
            for field in ('answer_source','if_first_answer','if_second_answer','reversal_observation','oracle'):
                _string(app[field], field)
            if app['if_first_answer'].casefold() == app['if_second_answer'].casefold():
                raise ValueError('A question must change the planned response')
        for exclusion in skipped:
            if not isinstance(exclusion,dict) or set(exclusion) != {'operator_id','reason'}:
                raise ValueError('Missing operator exclusion reason')
            opid = exclusion['operator_id']
            if opid not in ops or opid in refs:
                raise ValueError('Operator activated and skipped / unknown')
            refs.add(opid)
            _string(exclusion['reason'], 'skip reason')
    if not negative_controls:
        raise ValueError('Missing trivial-task negative control')
    return {'cases':len(items), 'negative_controls':negative_controls}


def audit(catalog_file: Path, cases_file: Path) -> dict:
    ops = check_catalog(json.loads(catalog_file.read_text(encoding='utf-8')))
    result = check_cases(json.loads(cases_file.read_text(encoding='utf-8')), ops)
    return {'status':'PASS', 'operators':len(ops), **result,
            'warning':'Structure only: no effectiveness, source-accuracy or real-runtime claim.'}


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operators',type=Path,default=root/'assets/inquiry-operators-v1.json')
    parser.add_argument('--cases',type=Path,default=root/'examples/operator-applications-v1.json')
    args=parser.parse_args()
    try:
        print(json.dumps(audit(args.operators,args.cases),indent=2))
        return 0
    except (ValueError, OSError, UnicodeError, json.JSONDecodeError) as exc:
        print('FAIL:',exc,file=sys.stderr)
        return 1


if __name__=='__main__':
    sys.exit(main())