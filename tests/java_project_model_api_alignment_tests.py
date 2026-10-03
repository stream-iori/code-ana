#!/usr/bin/env python3
import copy
import unittest
from java_project_model_api_alignment import accepted_context, expected_contexts
from java_model_api_inventory_diff import PROJECTIONS


class ProjectModelInventoryGuards(unittest.TestCase):
    def test_every_physical_module_and_scope_is_required(self):
        manifest=dict(scopes=['MAIN','TEST'],modules=[dict(id=str(i),resolved_classpath=True) for i in range(40)])
        self.assertEqual(len(expected_contexts(manifest)),80)
        for change in ('missing','duplicate','unselected','unresolved','scope'):
            broken=copy.deepcopy(manifest)
            if change=='missing':broken['modules'].pop()
            elif change=='duplicate':broken['modules'][-1]['id']='0'
            elif change=='unselected':broken['modules'][1]['selected']=False
            elif change=='unresolved':broken['modules'][1]['resolved_classpath']=False
            else:broken['scopes']=['MAIN']
            with self.subTest(change=change),self.assertRaisesRegex(ValueError,'FULL_RESOLVED_REACTOR'):expected_contexts(broken)

    def test_jvm_or_incomplete_or_replanned_inventory_cannot_close_native_context(self):
        row=dict(exit_code=0,native_model_api_executable_sha256='native')
        report=dict(state='finished',equal=True,projections={name:dict(equal=True) for name in PROJECTIONS},
            changed_inputs=[],changed_source_inputs=[],native_model_api_executable_sha256='native',reference_compilation=dict(compilations=1))
        self.assertTrue(accepted_context(row,report))
        for override in (dict(native_model_api_executable_sha256=None),dict(reference_compilation=dict(compilations=21)),
            dict(projections={}),dict(changed_source_inputs=['parser']),dict(changed_inputs=['source']),dict(state='running')):
            with self.subTest(override=override):self.assertFalse(accepted_context(row,report|override))


if __name__=='__main__':unittest.main(verbosity=2)
