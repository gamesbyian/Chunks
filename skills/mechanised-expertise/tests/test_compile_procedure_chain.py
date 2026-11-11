"""Offline checks for symbolic procedure wiring. No real app or network is exercised."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from compile_procedure_chain import compile_chain


def make_catalog():
    def proc(ident, source, inputs, outputs):
        steps = [{'op': 'inspect', 'uses': [inputs[0]], 'produces': outputs[0],
                  'instruction': 'Inspect the named source evidence and record the exact observed claims and unresolved limits.'}]
        for j, output in enumerate(outputs[1:]):
            steps.append({'op': 'compare', 'uses': [outputs[j]], 'produces': output,
                          'instruction': 'Compare the previous output with the task goal and preserve any unsupported assumptions.'})
        return {'id': ident, 'source_pattern_id': source, 'title': 'Synthetic '+ident,
                'inputs': inputs, 'steps': steps,
                'decision_gate': {'question': 'Does a missing fact change the implementation?',
                                  'if_yes': 'Investigate before deciding.',
                                  'if_no': 'Use current implementation.',
                                  'discriminator': 'A second grounded source observation.'},
                'oracle': 'Observed outcome only after independent execution.',
                'stop': 'Stop when remaining unknowns cannot change the relevant decision.'}
    return {'schema': 'mechanised-expertise/source-mechanisms-v1', 'procedures': [
        proc('PROC-005', 'PRX-010', ['approved_target','protocol_contract','remote_command_path'],
             ['target_binding','uncertainty_states','reconciliation_plan','fault_probes']),
        proc('PROC-010', 'PRX-006', ['decision_target','hard_constraints','candidate_options'],
             ['decision_scope','viable_options','failure_table','discriminating_test'])]}


def make_case():
    return {'schema': 'mechanised-expertise/procedure-chain-input-v1',
            'task_id': 'synthetic-transfer', 'goal': 'Avoid duplicate writes after a timeout',
            'risk_tier': 'consequential',
            'facts': [
                {'id': 'f_target', 'kind': 'owner_contract', 'locator': 'synthetic://contract#target',
                 'status': 'documented', 'claim': 'The approved target is account A transfer X.'},
                {'id': 'f_protocol', 'kind': 'protocol', 'locator': 'synthetic://protocol#retry',
                 'status': 'unknown', 'claim': 'Server retry behavior is not established.'},
                {'id': 'f_code', 'kind': 'code', 'locator': 'synthetic://app#remote',
                 'status': 'observed', 'claim': 'An RPC write can time out before response.'},
                {'id': 'f_constraints', 'kind': 'spec', 'locator': 'synthetic://spec#integrity',
                 'status': 'documented', 'claim': 'Never duplicate a remote mutation.'}],
            'selected': [
                {'id': 'PROC-005', 'trigger': 'A remote mutation may be committed after a response timeout.',
                 'trigger_fact_ids': ['f_code','f_target'],
                 'exclusion_checked': True, 'exclusion_applies': False,
                 'bindings': {'approved_target': {'fact': 'f_target'},
                              'protocol_contract': {'fact': 'f_protocol'},
                              'remote_command_path': {'fact': 'f_code'}}},
                {'id': 'PROC-010', 'trigger': 'A consequential alternative uses the reconciliation result.',
                 'trigger_fact_ids': ['f_code','f_constraints'],
                 'exclusion_checked': True, 'exclusion_applies': False,
                 'bindings': {'decision_target': {'output': 'PROC-005.target_binding'},
                              'hard_constraints': {'fact': 'f_constraints'},
                              'candidate_options': {'output': 'PROC-005.reconciliation_plan'}}}]}


class ProcedureChainTests(unittest.TestCase):
    def setUp(self):
        self.catalog = make_catalog()
        self.case = make_case()

    def compile(self):
        return compile_chain(self.case, self.catalog)

    def test_chain_preserves_provenance_and_unexecuted_truth(self):
        result = self.compile()
        self.assertEqual([p['id'] for p in result['stages']], ['PROC-005','PROC-010'])
        self.assertEqual(result['execution_status'], 'not_executed')
        self.assertEqual(result['stages'][0]['source_pattern_id'], 'PRX-010')
        self.assertEqual(result['stages'][0]['readiness'], 'needs_source_evidence')
        self.assertIn('protocol_contract', result['stages'][0]['inputs_needing_evidence'])
        self.assertEqual(result['stages'][1]['readiness'], 'depends_on_unexecuted_stage')
        self.assertEqual(result['stages'][1]['inputs_waiting_on_execution'], ['decision_target','candidate_options'])
        for p in result['stages']:
            self.assertEqual(p['decision_gate']['status'], 'unanswered')
            self.assertEqual(p['oracle']['status'], 'not_run')
            self.assertTrue(all(s['execution'] == 'not_run' for s in p['operations']))
            self.assertTrue(all(s['status'] == 'planned_not_observed' for s in p['outputs']))

    def test_negative_control_produces_no_plan(self):
        self.case.update(risk_tier='routine', selected=[])
        result = self.compile()
        self.assertEqual(result['stages'], [])
        self.assertEqual(result['skipped'], [])

    def test_excluded_selection_is_skipped_without_bindings(self):
        self.case['risk_tier'] = 'routine'
        self.case['selected'] = [self.case['selected'][0]]
        self.case['selected'][0]['exclusion_applies'] = True
        self.case['selected'][0]['bindings'] = {}
        result = self.compile()
        self.assertEqual(len(result['skipped']), 1)
        self.assertEqual(result['stages'], [])

    def test_consequential_active_routine_is_error(self):
        self.case['risk_tier'] = 'routine'
        with self.assertRaisesRegex(ValueError, 'contradicts routine'):
            self.compile()

    def test_missing_binding_rejected(self):
        del self.case['selected'][0]['bindings']['protocol_contract']
        with self.assertRaisesRegex(ValueError, 'input bindings'):
            self.compile()

    def test_future_output_rejected(self):
        self.case['selected'][0]['bindings']['approved_target'] = {'output': 'PROC-010.viable_options'}
        with self.assertRaisesRegex(ValueError, 'forward'):
            self.compile()

    def test_unrecognized_upstream_output_rejected(self):
        self.case['selected'][1]['bindings']['decision_target'] = {'output': 'PROC-005.nonexistent'}
        with self.assertRaisesRegex(ValueError, 'undefined'):
            self.compile()

    def test_duplicate_procedure_rejected(self):
        self.case['selected'].append(copy.deepcopy(self.case['selected'][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate selected'):
            self.compile()

    def test_unknown_procedure_rejected(self):
        self.case['selected'][0]['id'] = 'PROC-999'
        with self.assertRaisesRegex(ValueError, 'Unknown selected'):
            self.compile()

    def test_trigger_must_have_evidence_locator(self):
        self.case['selected'][0]['trigger_fact_ids'] = ['invented']
        with self.assertRaisesRegex(ValueError, 'lacks recorded'):
            self.compile()

    def test_duplicate_fact_rejected(self):
        self.case['facts'].append(copy.deepcopy(self.case['facts'][0]))
        with self.assertRaisesRegex(ValueError, 'Duplicate fact'):
            self.compile()

    def test_source_status_must_be_supported(self):
        self.case['facts'][0]['status'] = 'proved_correct'
        with self.assertRaisesRegex(ValueError, 'status'):
            self.compile()

    def test_hypothesis_never_becomes_observation(self):
        self.case['facts'][2]['status'] = 'hypothesized'
        result = self.compile()
        self.assertIn('remote_command_path', result['stages'][0]['inputs_needing_evidence'])
        self.assertEqual(result['stages'][0]['inputs'][2]['evidence_status'], 'hypothesized')

    def test_missing_exclusion_check_rejected(self):
        self.case['selected'][0]['exclusion_checked'] = False
        with self.assertRaisesRegex(ValueError, 'exclusion not checked'):
            self.compile()

    def test_catalog_unknown_schema_rejected(self):
        self.catalog['schema'] = 'unknown'
        with self.assertRaisesRegex(ValueError, 'catalog'):
            self.compile()

    def test_no_auto_selection_from_task_keywords(self):
        self.case['selected'] = []
        self.case['goal'] = 'Remote transfer security storage lifecycle accessibility test'
        self.assertEqual(self.compile()['stages'], [])

    def test_bad_binding_shape_rejected(self):
        self.case['selected'][0]['bindings']['approved_target'] = {'fact':'f_target','output':'PROC-005.target_binding'}
        with self.assertRaisesRegex(ValueError, 'exactly one'):
            self.compile()

    def test_source_kind_not_self_declared_research_conclusion(self):
        self.case['facts'][0]['kind'] = 'verified_expertise'
        with self.assertRaisesRegex(ValueError, 'source kind'):
            self.compile()

    def test_skipped_procedure_cannot_supply_downstream_output(self):
        self.case['selected'][0]['exclusion_applies'] = True
        with self.assertRaisesRegex(ValueError, 'skipped'):
            self.compile()


if __name__ == '__main__':
    unittest.main()
