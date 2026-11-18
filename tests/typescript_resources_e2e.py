#!/usr/bin/env python3
"""Real TypeScript resource failures, cumulative receipts and head preservation."""
import argparse
import pathlib
import unittest
from budget_product_support import BudgetCases
from typescript_product_support import TypeScriptHarness
from backend_test_support import run_cases


class TypeScriptResources(BudgetCases, TypeScriptHarness):
    language = 'typescript'

    def setUp(self):
        super().setUp()
        self.a.write_text(self.a.read_text()+''.join(f'export function generated_{i}() {{ return {i}; }}\n' for i in range(600)))
        self.prepared_config=self.config
        self.index('--prepare','--full')
        self.use_ordinary_inputs()
        if '[resources.index]' not in self.config:
            self.config+='\n[resources.index]\n'
        self.write_config()

    def test_fact_and_archive_limits_return_known_failure_without_publication(self):
        baseline = self.cli('overview')[1]['revision']
        original = self.config
        for field,value in [('typescript_fact_limit',1),('typescript_result_bytes',65536)]:
            with self.subTest(field=field):
                self.config = original.replace('[resources.index]',f'[resources.index]\n{field}={value}')
                self.write_config()
                _,failed=self.index('--full',code=1)
                self.assertEqual(failed['code'],'TYPESCRIPT_OUTPUT_LIMIT')
                self.assertEqual(self.cli('overview')[1]['revision'],baseline)
                account=self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
                self.assertTrue(all(w['state']=='failed' and w['usage']['work']>0
                                    for s in account['languages'] for w in s['workers']))
        self.config=original;self.write_config()
        self.assertEqual(self.index('--full')[1]['publication'],'published')

    def test_actual_ast_depth_exhaustion_occurs_after_input_admission(self):
        old=self.cli('overview')[1]['revision']
        self.a.write_text('export const deep = '+'('*80+'1'+')'*80+';\n')
        self.config=self.prepared_config+'\n[resources.index.analysis]\nmax_depth=12\n'
        self.write_config()
        _,failed=self.index('--prepare','--full',code=1)
        self.assertEqual(failed['code'],'ANALYSIS_RESOURCE_LIMIT',failed)
        account=self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
        self.assertIsNone(account['admission']['failure'])
        slot=account['languages'][0]
        self.assertIsNone(slot['host_usage']['failure'])
        self.assertEqual(slot['workers'][0]['state'],'failed')
        self.assertEqual(slot['workers'][0]['usage']['failure']['Limit']['resource'],'depth')
        self.assertEqual(self.cli('overview')[1]['revision'],old)

    def test_captured_source_file_budget_retains_head(self):
        old=self.cli('overview')[1]['revision']
        self.a.write_text('/*'+'x'*(8 << 20)+'*/\nexport const giant = 1;\n')
        self.config=self.prepared_config+'\n[resources.index.analysis]\nmax_work=1000000000\nmax_nodes=10000000\nmax_payload_bytes=2147483648\n'
        self.write_config()
        _,failed=self.index('--prepare','--full',code=1)
        self.assertEqual(failed['code'],'CAPTURE_FILE_BUDGET',failed)
        self.assertEqual(self.cli('overview')[1]['revision'],old)

    def test_single_graph_row_limit_is_independent_of_total_archive_limit(self):
        old=self.cli('overview')[1]['revision']
        self.a.write_text('/*'+'x'*((8 << 20)-128)+'*/\nexport const giant = 1;\n')
        self.config=self.prepared_config+'\n[resources.index.analysis]\nmax_work=1000000000\nmax_nodes=10000000\nmax_payload_bytes=2147483648\n'
        self.write_config()
        _,failed=self.index('--prepare','--full',code=1)
        self.assertEqual(failed['code'],'TYPESCRIPT_OUTPUT_LIMIT',failed)
        account=self.cli('report',failed['report'])[0][0]['analysis_resources']['coordinator']
        worker=account['languages'][0]['workers'][0]
        self.assertEqual(worker['state'],'failed')
        self.assertIsNone(worker['usage']['failure'])
        self.assertEqual(self.cli('overview')[1]['revision'],old)

    def test_descriptor_and_result_must_fit_the_scratch_budget(self):
        baseline=self.cli('overview')[1]['revision']
        self.config=self.config.replace('[resources.index]','[resources.index]\ntypescript_result_bytes=65536\ntypescript_scratch_bytes=65536')
        self.write_config()
        _,failed=self.index('--full',code=1)
        self.assertEqual(failed['code'],'TYPESCRIPT_SCRATCH_BUDGET')
        self.assertEqual(self.cli('overview')[1]['revision'],baseline)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',type=pathlib.Path)
    args=parser.parse_args()
    cases=list(unittest.defaultTestLoader.loadTestsFromTestCase(TypeScriptResources))
    return run_cases(cases,['typescript'],{},args.evidence)

if __name__=='__main__':
    raise SystemExit(main())
