#!/usr/bin/env python3
"""Compile evidence-linked observations into questions, never inferred answers.

This deterministic scaffold does not inspect GitHub, establish source truth,
rank product choices or demonstrate autonomous engineering expertise.
"""
from __future__ import annotations
import argparse
import json
import re
import sys
from pathlib import Path
from string import Formatter

SCHEMA = 'mechanised-expertise/inquiry-input-v1'
OUTPUT = 'mechanised-expertise/inquiry-questions-v1'
OP_KINDS = {
    'GOAL-INVERT': 'goal_obstacle',
    'MENTAL-MODEL': 'competing_interpretation',
    'BOUNDARY-CROSS': 'mutable_boundary',
    'DESIGN-QOC': 'material_alternatives',
    'MAP-REVERSE': 'unresolved_relation',
    'SCOPE-CHALLENGE': 'scope_difference',
    'SOURCE-OPPOSITION': 'competing_evidence',
    'ORACLE-INVERT': 'proof_boundary',
    'COMPOSE-JOBS': 'cross_system_job',
}
SOURCE_TYPES = {'owner_contract', 'code', 'test', 'platform_document', 'user_observation',
                'prior_art', 'protocol', 'spec', 'published_recommendation'}

def nonempty(value, field, minimum=4):
    if not isinstance(value, str) or len(value.strip()) < minimum:
        raise ValueError(f'Invalid or absent {field}')
    return value.strip()

def render(template, facts, opid):
    keys = [field for _, field, _, _ in Formatter().parse(template) if field]
    missing = [key for key in keys if key not in facts]
    if missing:
        raise ValueError(f'{opid} missing required question facts: {missing}')
    if any(not isinstance(facts[k], str) or not facts[k].strip() for k in keys):
        raise ValueError(f'{opid} question placeholder must contain a source-grounded textual fact')
    question = template.format_map(facts)
    if not question.endswith('?'):
        raise ValueError('Question template did not produce a question')
    return question

def compile_case(case, catalog):
    if not isinstance(catalog, dict) or catalog.get('schema') != 'mechanised-expertise/inquiry-operators-v1':
        raise ValueError('Unknown operator catalog')
    operators = {op['id']:op for op in catalog.get('operators', [])}
    if set(operators) != set(OP_KINDS):
        raise ValueError('Operator registry disagrees with the catalog')
    if not isinstance(case, dict) or case.get('schema') != SCHEMA:
        raise ValueError('Unknown inquiry-input schema')
    ident = nonempty(case.get('task_id'), 'task id')
    goal = nonempty(case.get('goal'), 'user-observable goal', 8)
    tier = case.get('risk_tier')
    if tier not in {'routine', 'coupled', 'consequential', 'exploratory'}:
        raise ValueError('Unknown risk tier')
    raw_sources = case.get('sources')
    if not isinstance(raw_sources, list):
        raise ValueError('Sources must be an explicit list')
    sources = {}
    for source in raw_sources:
        if not isinstance(source, dict) or set(source) != {'id', 'kind', 'locator', 'scope'}:
            raise ValueError('Malformed source record')
        sid = nonempty(source['id'], 'source id', 2)
        if sid in sources:
            raise ValueError('Duplicate source id')
        if source['kind'] not in SOURCE_TYPES:
            raise ValueError('Unrecognized source type')
        nonempty(source['locator'], 'source locator')
        nonempty(source['scope'], 'source scope')
        sources[sid] = source
    observations = case.get('observations')
    if not isinstance(observations, list):
        raise ValueError('Observations must be a list (empty for routine cases)')
    seen = set()
    questions, skipped = [], []
    required = {'id', 'operator_id', 'kind', 'source_ids', 'cue', 'facts',
                'rejection_checked', 'rejection_applies', 'branches',
                'answer_source', 'oracle', 'reversal_observation', 'model_dependency'}
    for obs in observations:
        if not isinstance(obs, dict) or set(obs) != required:
            raise ValueError('Malformed evidence observation')
        oid = nonempty(obs['id'], 'observation id', 2)
        if oid in seen:
            raise ValueError('Duplicate observation id')
        seen.add(oid)
        opid = obs['operator_id']
        if opid not in operators or obs['kind'] != OP_KINDS[opid]:
            raise ValueError(f'{oid}: operator/cue mismatch')
        ids = obs['source_ids']
        if (not isinstance(ids, list) or not ids
                or any(source_id not in sources for source_id in ids)
                or len(set(ids)) != len(ids)):
            raise ValueError(f'{oid}: ungrounded or duplicated source references')
        nonempty(obs['cue'], 'observable cue', 12)
        if not isinstance(obs['facts'], dict):
            raise ValueError('Question parameter facts must be a mapping')
        if obs['rejection_checked'] is not True or type(obs['rejection_applies']) is not bool:
            raise ValueError(f'{oid}: explicit rejection gate must be checked')
        if obs['model_dependency'] not in {'none', 'relation_could_change_choice'}:
            raise ValueError(f'{oid}: concept-model gate must be explicit')
        branches = obs['branches']
        if (not isinstance(branches, list) or len(branches) != 2
                or any(not isinstance(b, dict) or set(b) != {'answer', 'decision_if_true'}
                       for b in branches)):
            raise ValueError(f'{oid}: exactly two plausible answer/decision branches required')
        for b in branches:
            nonempty(b['answer'], 'possible answer')
            nonempty(b['decision_if_true'], 'conditional decision', 12)
        if branches[0]['answer'] == branches[1]['answer']:
            raise ValueError(f'{oid}: both answers are identical')
        nonempty(obs['answer_source'], 'answer-source plan')
        nonempty(obs['oracle'], 'independent result oracle', 12)
        nonempty(obs['reversal_observation'], 'reversal observation', 12)
        if obs['rejection_applies']:
            skipped.append({'observation_id': oid, 'operator_id': opid,
                            'source_ids': ids,
                            'reason': 'Explicit operator rejection applies'})
            continue
        if tier == 'routine':
            raise ValueError(f'{oid}: routine classification conflicts with an active consequential cue')
        if branches[0]['decision_if_true'] == branches[1]['decision_if_true']:
            skipped.append({'observation_id': oid, 'operator_id': opid,
                            'source_ids': ids,
                            'reason': 'Both answers result in the same decision'})
            continue
        questions.append({'observation_id': oid, 'operator_id': opid,
                          'question': render(operators[opid]['question_template'], obs['facts'], opid),
                          'source_ids': ids, 'answer_source': obs['answer_source'],
                          'branches': branches,
                          'model_dependency': obs['model_dependency'],
                          'oracle': obs['oracle'],
                          'reversal_observation': obs['reversal_observation'],
                          'status': 'unanswered', 'effectiveness': 'not_measured'})
    return {'schema': OUTPUT, 'task_id': ident, 'goal': goal,
            'risk_tier': tier, 'source_count': len(sources),
            'questions': questions, 'skipped': skipped,
            'limits': 'Requires external verification of source facts and actual outcomes. No conclusions, tested behavior or missing features are inferred.'}

def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('case', type=Path)
    parser.add_argument('--catalog', type=Path, default=root/'assets/inquiry-operators-v1.json')
    args = parser.parse_args()
    try:
        output = compile_case(json.loads(args.case.read_text(encoding='utf-8')),
                              json.loads(args.catalog.read_text(encoding='utf-8')))
    except (ValueError, KeyError, TypeError, OSError, json.JSONDecodeError) as exc:
        print('FAIL:', exc, file=sys.stderr)
        return 1
    print(json.dumps(output, indent=2, ensure_ascii=False))
    return 0

if __name__ == '__main__':
    sys.exit(main())
