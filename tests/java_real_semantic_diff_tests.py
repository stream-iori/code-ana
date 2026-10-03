#!/usr/bin/env python3
"""Acceptance guards for executed full-project semantic comparisons."""
import copy
import pathlib
import tempfile
import unittest

from java_real_semantic_diff import domain_accepted,validate_publication,proof_files,PENDING_DOMAINS
from java_declaration_inventory_diff import digest


class SemanticAcceptance(unittest.TestCase):
    def setUp(self):
        self.product=pathlib.Path('/fixture/facts.db')
        self.receipt=dict(state='finished',published=True,exit_code=0,query_exit_code=0,
            catalog_commits=[['task','revision']],catalog_heads=[['target','revision']],
            published_facts=str(self.product),commit='267c7ced4993aa88613f8f17a8099943fec98159',
            verified_committed_files=5039,committed_java_files=4217,include_uncommitted=False,
            scopes=['MAIN','TEST'],xml_resources_included=True,unexpected_signal_observed=False,
            changed_artifacts=[],changed_source_inputs=[],inputs_manifest_changed=False)
        self.manifest=dict(scopes=['MAIN','TEST'],modules=[dict(id=str(i)) for i in range(40)])

    def test_selected_modules_or_partial_scopes_cannot_certify_full_project(self):
        validate_publication(self.receipt,self.product,self.manifest)
        selected=copy.deepcopy(self.manifest);selected['modules'][0]['selected']=False
        with self.assertRaisesRegex(ValueError,'SELECTED_REACTOR'):validate_publication(self.receipt,self.product,selected)
        for override,reason in [(dict(scopes=['MAIN']),'ALL_SCOPES'),(dict(xml_resources_included=False),'ALL_SCOPES'),
            (dict(unexpected_signal_observed=True),'INPUT_DRIFT'),(dict(changed_artifacts=['native']),'INPUT_DRIFT'),
            (dict(published_facts='/other/facts.db'),'DATABASE_MISMATCH'),(dict(committed_java_files=4213),'FROZEN_PROJECT')]:
            with self.subTest(override=override),self.assertRaisesRegex(ValueError,reason):
                validate_publication(self.receipt|override,self.product,self.manifest)

    def test_all_type_and_call_domains_and_meta_must_execute(self):
        self.assertFalse(domain_accepted('calls',dict(full_semantic_diff_accepted=True)))
        self.assertFalse(domain_accepted('calls',dict(sites=dict(accepted=True))))
        self.assertFalse(domain_accepted('types_hierarchy_reflection',dict(projections={})))
        self.assertFalse(domain_accepted('lexical_controls',dict(equal=True)))
        self.assertFalse(domain_accepted('lexical_controls',dict(projections={'calls':dict(equal=True)})))
        self.assertFalse(domain_accepted('annotations_meta',dict(equal=True,meta=dict(equal=False))))
        self.assertTrue(domain_accepted('annotations_meta',dict(equal=True,meta=dict(equal=True))))
        self.assertTrue(PENDING_DOMAINS)  # Missing complete proofs remain blockers.

    def test_runtime_artifacts_and_sources_require_exact_frozen_bytes(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp);(root/'source').write_bytes(b'source');(root/'native').write_bytes(b'native')
            report=dict(source_inputs={'source':digest(root/'source')},artifacts={'native':digest(root/'native')})
            self.assertEqual(len(proof_files(root,report)),2)
            (root/'native').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError,'PROOF_DRIFT'):proof_files(root,report)
            with self.assertRaisesRegex(ValueError,'PROOF_MISSING'):proof_files(root,dict(source_inputs={},artifacts={}))


if __name__=='__main__':unittest.main(verbosity=2)
