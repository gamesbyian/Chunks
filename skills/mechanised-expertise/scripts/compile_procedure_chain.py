#!/usr/bin/env python3
"""Compile selected source-derived procedures into a *nonexecuted* dependency plan.

The compiler cannot retrieve evidence, execute procedures or validate semantic
claims. Unknown and hypothesized facts remain unknown; planned upstream outputs
are never promoted to observations. Caller selection must be supported by source
IDs and checked outside this syntactic tool.
"""
import argparse
import json
import sys
from pathlib import Path

INPUT_SCHEMA = 'mechanised-expertise/procedure-chain-input-v1'
OUTPUT_SCHEMA = 'mechanised-expertise/procedure-chain-plan-v1'
CATALOG_SCHEMA = 'mechanised-expertise/source-mechanisms-v1'
FACT_STATUS = {'observed', 'documented', 'hypothesized', 'unknown'}
SOURCE_KIND = {'owner_contract', 'code', 'test', 'platform_document',
               'user_observation', 'prior_art', 'protocol', 'spec', 'published_recommendation'}


def string(value, label, size=2):
    if not isinstance(value, str) or len(value.strip()) < size:
        raise ValueError('Missing or invalid ' + label)
    return value.strip()


def read_catalog(catalog):
    if not isinstance(catalog, dict) or catalog.get('schema') != CATALOG_SCHEMA:
        raise ValueError('Invalid source mechanism catalog')
    rows = catalog.get('procedures')
    if not isinstance(rows, list) or len(rows) == 0:
        raise ValueError('Empty mechanism catalog')
    keyed = {}
    for p in rows:
        ident = string(p.get('id'), 'procedure id')
        if ident in keyed:
            raise ValueError('Duplicate mechanism id')
        if not isinstance(p.get('inputs'), list) or not isinstance(p.get('steps'), list):
            raise ValueError('Invalid mechanism structure')
        keyed[ident] = p
    return keyed


def compile_chain(case, catalog):
    procedures = read_catalog(catalog)
    if not isinstance(case, dict) or case.get('schema') != INPUT_SCHEMA:
        raise ValueError('Invalid chain input schema')
    task_id = string(case.get('task_id'), 'task_id')
    goal = string(case.get('goal'), 'observable user goal', 8)
    tier = case.get('risk_tier')
    if tier not in ('routine', 'coupled', 'consequential', 'exploratory'):
        raise ValueError('Invalid risk tier')
    raw = case.get('facts')
    if not isinstance(raw, list):
        raise ValueError('Facts must be a list')
    facts = {}
    for f in raw:
        if not isinstance(f, dict) or set(f) != {'id', 'kind', 'locator', 'status', 'claim'}:
            raise ValueError('Invalid source fact fields')
        sid = string(f['id'], 'fact id')
        if sid in facts:
            raise ValueError('Duplicate fact id')
        if f['kind'] not in SOURCE_KIND or f['status'] not in FACT_STATUS:
            raise ValueError('Invalid source kind or evidence status')
        string(f['locator'], 'source locator', 8)
        string(f['claim'], 'source claim', 8)
        facts[sid] = f
    selected = case.get('selected')
    if not isinstance(selected, list):
        raise ValueError('Selected procedures must be a list (possibly empty)')
    ids = set()
    earlier = {}
    stages = []
    skipped = []
    for item in selected:
        if not isinstance(item, dict) or set(item) != {'id', 'trigger', 'trigger_fact_ids',
                                                       'exclusion_checked', 'exclusion_applies', 'bindings'}:
            raise ValueError('Invalid selected-procedure record')
        ident = string(item['id'], 'selected procedure id')
        if ident not in procedures:
            raise ValueError('Unknown selected procedure ' + ident)
        if ident in ids:
            raise ValueError('Duplicate selected procedure ' + ident)
        ids.add(ident)
        p = procedures[ident]
        string(item['trigger'], 'observable trigger', 12)
        supports = item['trigger_fact_ids']
        if not isinstance(supports, list) or not supports or len(set(supports)) != len(supports) or any(x not in facts for x in supports):
            raise ValueError(ident + ': trigger lacks recorded source facts')
        if item['exclusion_checked'] is not True or type(item['exclusion_applies']) is not bool:
            raise ValueError(ident + ': exclusion not checked')
        if item['exclusion_applies']:
            skipped.append({'id': ident, 'reason': 'exclusion_applies', 'source_pattern_id': p['source_pattern_id']})
            continue
        if tier == 'routine':
            raise ValueError(ident + ': active consequential procedure contradicts routine tier')
        bindings = item['bindings']
        if not isinstance(bindings, dict) or set(bindings) != set(p['inputs']):
            raise ValueError(ident + ': missing/extra required input bindings')
        inputs = []
        unresolved = []
        upstream = []
        for name in p['inputs']:
            binding = bindings[name]
            if not isinstance(binding, dict) or len(binding) != 1:
                raise ValueError(ident + ': input must bind exactly one fact or upstream output')
            if 'fact' in binding:
                fact_id = binding['fact']
                if fact_id not in facts:
                    raise ValueError(ident + ': unknown input fact')
                f = facts[fact_id]
                inputs.append({'name': name, 'origin': 'source_fact', 'ref': fact_id,
                               'evidence_status': f['status'], 'locator': f['locator']})
                if f['status'] in ('unknown', 'hypothesized'):
                    unresolved.append(name)
            elif 'output' in binding:
                ref = binding['output']
                if not isinstance(ref, str) or ref.count('.') != 1:
                    raise ValueError(ident + ': invalid upstream output name')
                src, out = ref.split('.')
                if src not in earlier or out not in earlier[src]:
                    raise ValueError(ident + ': forward, skipped or undefined output reference')
                inputs.append({'name': name, 'origin': 'planned_upstream_output', 'ref': ref,
                               'evidence_status': 'not_observed'})
                upstream.append(name)
            else:
                raise ValueError(ident + ': unrecognized input binding')
        produced = [s['produces'] for s in p['steps']]
        if len(set(produced)) != len(produced):
            raise ValueError(ident + ': duplicate step outputs')
        stages.append({'id': ident, 'source_pattern_id': p['source_pattern_id'],
                       'title': p['title'], 'trigger_fact_ids': supports,
                       'inputs': inputs, 'inputs_needing_evidence': unresolved,
                       'inputs_waiting_on_execution': upstream,
                       'readiness': ('needs_source_evidence' if unresolved else
                                     'depends_on_unexecuted_stage' if upstream else
                                     'ready_to_investigate'),
                       'operations': [{'op': s['op'], 'uses': s['uses'],
                                       'produces': s['produces'],
                                       'instruction': s['instruction'],
                                       'execution': 'not_run', 'evidence_refs': []}
                                      for s in p['steps']],
                       'decision_gate': {**p['decision_gate'], 'status': 'unanswered'},
                       'oracle': {'description': p['oracle'], 'status': 'not_run', 'evidence_refs': []},
                       'outputs': [{'id': name, 'status': 'planned_not_observed'} for name in produced],
                       'stop': p['stop']})
        earlier[ident] = set(produced)
    return {'schema': OUTPUT_SCHEMA, 'task_id': task_id, 'goal': goal,
            'risk_tier': tier, 'fact_count': len(facts), 'stages': stages, 'skipped': skipped,
            'execution_status': 'not_executed',
            'limitations': 'Structural plan only. Source claims, triggers and real outcomes require independent inspection. Planned outputs are not facts or verification.'}


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('case', type=Path)
    parser.add_argument('--catalog', type=Path, default=root/'assets/source-mechanisms-v1.json')
    args = parser.parse_args()
    try:
        result = compile_chain(json.loads(args.case.read_text(encoding='utf-8')),
                               json.loads(args.catalog.read_text(encoding='utf-8')))
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
        print('FAIL:', exc, file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    sys.exit(main())
