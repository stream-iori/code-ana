"""Native model replay must reject partial, duplicated, or signalled JVM evidence."""
import copy
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('native_models', ROOT / 'scripts/java-native-project-models.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class NativeModelProof(unittest.TestCase):
    def test_model_success_alone_cannot_authorize_native_replay(self):
        with self.assertRaisesRegex(ValueError, 'COMPLETE_JVM_PROJECT_GATE_REQUIRED'):
            gate.check_jvm_gate(dict(jvm_migration_accepted=True, unexplained_failures=[]))
        complete = dict(kind='complete_jvm_migration_acceptance', status='passed',
                        jvm_migration_accepted=True, unexplained_failures=[], inputs={'proof': 'checksum'})
        gate.check_jvm_gate(complete)
        for change in (dict(status='running'), dict(inputs={}), dict(unexplained_failures=['calls'])):
            with self.assertRaisesRegex(ValueError, 'COMPLETE_JVM_PROJECT_GATE_REQUIRED'):
                gate.check_jvm_gate(dict(complete, **change))

    def inventory(self):
        categories = {name: dict(encoding='sorted-row-sha256-v2-graph-captures', rows=0,
                                sha256='0' * 64) for name in gate.models.CATEGORIES}
        cases = [dict(id=mode + ':module' + str(number) + '@MAIN',
                      results={label: copy.deepcopy(categories) for label in ('reference', 'migrated')},
                      executions={label: dict(status='passed', unexpected_signal=None)
                                  for label in ('reference', 'migrated')},
                      proofs={'/sealed/' + mode + '/' + str(number) + '/context.json': '0' * 64})
                 for mode in ('raw', 'ast') for number in range(80)]
        return dict(scope_model_inventory_accepted=True, all_contexts_executed=True,
                    unexplained_failures=[], expected_count=160, executed_count=160,
                    changed_inputs=[], cases=cases)

    def test_complete_cases_are_required_even_when_summary_claims_success(self):
        inventory = self.inventory()
        self.assertEqual(len(gate.check_inventory(inventory)), 160)
        for change in (inventory['cases'][:-1], inventory['cases'][:-1] + [inventory['cases'][0]]):
            with self.assertRaisesRegex(ValueError, 'CASE_COVERAGE'):
                gate.check_inventory(dict(inventory, cases=change))

    def test_signal_and_missing_model_domain_are_not_hidden_by_passed_summary(self):
        inventory = self.inventory()
        inventory['cases'][0]['executions']['migrated']['unexpected_signal'] = 9
        with self.assertRaisesRegex(ValueError, 'EXECUTION_NOT_ACCEPTED'):
            gate.check_inventory(inventory)
        inventory = self.inventory()
        del inventory['cases'][0]['results']['reference']['raw.syntax']
        with self.assertRaisesRegex(ValueError, 'CATEGORY_COVERAGE'):
            gate.check_inventory(inventory)

    def test_drift_and_unexplained_failures_are_not_accepted(self):
        for change in (dict(changed_inputs=['changed.jar']),
                       dict(unexplained_failures=['raw:module0@MAIN']),
                       dict(all_contexts_executed=False)):
            with self.assertRaisesRegex(ValueError, 'VERIFIED_JVM_MODELS_REQUIRED'):
                gate.check_inventory(dict(self.inventory(), **change))


if __name__ == '__main__':
    unittest.main(verbosity=2)
