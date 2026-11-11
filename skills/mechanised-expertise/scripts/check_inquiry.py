#!/usr/bin/env python3
"""Dependency-free semantic validator for mechanised-expertise inquiry cards.
JSON Schema provides shape validation when a schema-aware tool is available.
This checker enforces cross-field invariants deliberately omitted from JSON Schema.
"""
import json
import sys
from pathlib import Path

GAP_TYPES = {'acquisition', 'alias', 'schema', 'missing-edge', 'contradictory', 'boundary', 'excluded', 'unknown', 'frontier-candidate'}
QUESTION_STATES = {'open', 'answered', 'not-applicable'}
VERIFICATION_TIERS = {'not-run', 'static', 'simulated', 'device', 'field', 'controlled-comparison'}


def validate(card):
    problems = []
    if not isinstance(card, dict):
        return ['Card must be an object']
    required = {'schema_version','id','status','focus','context','cues','questions','model','alternatives','decision','verification','sources'}
    problems += [f'Missing field: {k}' for k in sorted(required - card.keys())]
    if problems:
        return problems
    if card['schema_version'] != '1.0':
        problems.append('Unsupported schema version')
    questions = card['questions']
    sources = card['sources']
    model = card['model']
    options = card['alternatives']
    verification = card['verification']
    decision = card['decision']
    if not all(isinstance(v, t) for v,t in [(questions,list),(sources,list),(model,dict),(options,list),(verification,dict),(decision,dict)]):
        return problems + ['Incorrect types in questions, sources, model, alternatives, decision or verification']
    qids = [q.get('id') for q in questions if isinstance(q,dict)]
    source_ids = [s.get('id') for s in sources if isinstance(s,dict)]
    option_ids = [o.get('id') for o in options if isinstance(o,dict)]
    for name,ids in [('question',qids),('source',source_ids),('alternative',option_ids)]:
        if not ids or len(ids)!=len(set(ids)) or any(not x for x in ids):
            problems.append(f'Missing or repeated {name} ID')
    for q in questions:
        if q.get('status') not in QUESTION_STATES:
            problems.append(f"Invalid question status: {q.get('id')}")
        if q.get('status')=='answered' and not q.get('answer'):
            problems.append(f"Answered question has no answer: {q.get('id')}")
        for s in q.get('source_ids',[]):
            if s not in source_ids: problems.append(f"Missing evidence source {s} in question {q.get('id')}")
    nodes = model.get('nodes',[])
    for edge in model.get('edges',[]):
        if edge.get('from') not in nodes or edge.get('to') not in nodes:
            problems.append(f"Edge references undefined node: {edge}")
        for s in edge.get('source_ids',[]):
            if s not in source_ids: problems.append(f'Edge refers to unknown source {s}')
        if edge.get('truth_kind')=='observed' and not edge.get('source_ids'):
            problems.append('Observed edge requires at least one source')
    for gap in model.get('gaps',[]):
        if gap.get('classification') not in GAP_TYPES:
            problems.append(f"Invalid gap classification: {gap.get('name')}")
        if gap.get('classification') == 'frontier-candidate' and not gap.get('next_probe'):
            problems.append('Frontier candidate must specify novelty-disconfirmation probe')
    if decision.get('selected') is not None and decision.get('selected') not in option_ids:
        problems.append('Chosen option is absent from alternatives')
    if verification.get('tier') not in VERIFICATION_TIERS:
        problems.append('Invalid verification tier')
    if verification.get('tier')=='not-run' and verification.get('result')!='not-run':
        problems.append('Unexecuted verification cannot be marked pass/fail')
    if verification.get('result')=='pass' and not verification.get('evidence_refs'):
        problems.append('Pass requires concrete evidence references')
    if card.get('status')=='verified' and verification.get('result')!='pass':
        problems.append('Verified card requires successful independent proof')
    if decision.get('status')=='accepted' and not decision.get('selected'):
        problems.append('Accepted decision requires selected option')
    for source in sources:
        if not source.get('url','').startswith(('https://','http://')):
            problems.append(f"Invalid source URL: {source.get('id')}")
    return problems


def main(argv):
    if not argv:
        print('Usage: check_inquiry.py CARD.json [CARD2.json ...]',file=sys.stderr)
        return 2
    bad=0
    for filename in argv:
        try:
            card=json.loads(Path(filename).read_text(encoding='utf-8'))
            errors=validate(card)
        except (OSError, ValueError) as exc:
            errors=[str(exc)]
        print(f'{filename}: '+('PASS' if not errors else '; '.join(errors)))
        bad+=bool(errors)
    return 1 if bad else 0


if __name__=='__main__':
    raise SystemExit(main(sys.argv[1:]))