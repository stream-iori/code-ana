#!/usr/bin/env python3
"""Flat type graphs: Java 8 compatibility, original compiler proof and archive failures."""
import hashlib
import os
import pathlib
import subprocess
import tempfile
import unittest
import zipfile

import java_model_tests as models

REFERENCE_SHA = '457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e'
WORKER_SHA = '914727fd5e11a958f9c1b1b563fbbf1b1461daf06e1a5538ecc23d80b4118494'


class TypeGraphs(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='codeana-type-graphs-')
        cls.addClassCleanup(cls.temp.cleanup)
        root = pathlib.Path(cls.temp.name)
        cls.classes = root / 'classes'
        cls.classes.mkdir()
        jar = models.ROOT.parent / 'anatomist/target/anatomist.jar'
        if hashlib.sha256(jar.read_bytes()).hexdigest() != REFERENCE_SHA:
            raise ValueError('FROZEN_REFERENCE_JAR_CHANGED')
        with zipfile.ZipFile(jar) as archive:
            worker = archive.read('META-INF/anatomist/javac-worker.jar')
        if hashlib.sha256(worker).hexdigest() != WORKER_SHA:
            raise ValueError('FROZEN_COMPILER_WORKER_CHANGED')
        cls.worker = root / 'frozen-javac-worker.jar'
        cls.worker.write_bytes(worker)
        main = models.ROOT / 'lang-adaptor/src/main/java/dev/codeana/common'
        test = models.ROOT / 'lang-adaptor/src/test/java/dev/codeana/common'
        compiler = models.ROOT / 'lang-adaptor/src/compiler/java/dev/codeana/compiler'
        compiler_test = models.ROOT / 'lang-adaptor/src/test/compiler/dev/codeana/compiler'
        files = [main / (n + '.java') for n in ('Json', 'FactHeaders', 'RecordArchive', 'TypeGraph', 'TypeGraphArchive', 'ModelGraphs', 'ModelArchive', 'ArchiveRows', 'TypeEvidence', 'CanonicalTypes')]
        files += [test / (n + '.java') for n in ('TypeGraphProbe', 'TypeGraphCanonicalProbe', 'TypeGraphArchiveProbe', 'ModelGraphProbe', 'TypeEvidenceProbe', 'TypeEvidenceNormalizationProbe', 'ModelTransferProbe')]
        files += [compiler / 'CompilerTypeGraph.java']
        files += [compiler_test / (n + '.java') for n in ('CompilerTypeGraphProbe', 'CompilerTypeGraphBudgetProbe', 'CompilerGraphArchiveProbe')]
        compiled = subprocess.run([str(models.JDK8 / 'bin/javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
                                  '-cp', str(models.JDK8 / 'lib/tools.jar'), '-d', str(cls.classes), *map(str, files)],
                                 capture_output=True, text=True, timeout=60)
        if compiled.returncode:
            raise RuntimeError(compiled.stderr)
        cls.classpath = os.pathsep.join(map(str, [cls.classes, models.JDK8 / 'lib/tools.jar', cls.worker]))

    def run_probe(self, name):
        process = subprocess.run([str(models.JDK8 / 'bin/java'), '-Xmx64m', '-cp', self.classpath, name, str(models.ROOT / 'lang-adaptor/target/compiler-bridge.jar')],
                                 capture_output=True, text=True, timeout=60)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertTrue(process.stdout.strip())

    def test_legacy_generated_capture_scalar_roots_are_normalized_without_inventing_bounds(self):
        self.run_probe('dev.codeana.common.TypeEvidenceNormalizationProbe')

    def test_cycles_forward_references_immutability_and_numeric_validation(self):
        self.run_probe('dev.codeana.common.TypeGraphProbe')

    def test_root_closure_is_stable_across_node_orders_and_unreachable_types(self):
        self.run_probe('dev.codeana.common.TypeGraphCanonicalProbe')

    def test_large_flat_archive_roundtrip_corruption_and_cancel_under_64m(self):
        self.run_probe('dev.codeana.common.TypeGraphArchiveProbe')

    def test_model_and_row_spool_share_flat_nodes_without_recursive_root_copies(self):
        self.run_probe('dev.codeana.common.ModelGraphProbe')

    def test_compiler_transfer_preserves_graph_rows_and_cleans_consumed_and_failed_spools(self):
        self.run_probe('dev.codeana.common.ModelTransferProbe')

    def test_public_fact_graph_closure_sharing_identity_cycles_and_invalid_references(self):
        self.run_probe('dev.codeana.common.TypeEvidenceProbe')

    def test_original_worker_types_recursive_bounds_capture_and_80_generic_levels(self):
        self.run_probe('dev.codeana.compiler.CompilerTypeGraphProbe')

    def test_real_compiler_row_spool_and_final_model_preserve_deep_graph_and_errors(self):
        self.run_probe('dev.codeana.compiler.CompilerGraphArchiveProbe')

    def test_compiler_depth_failure_and_cancel_do_not_poison_healthy_roots(self):
        self.run_probe('dev.codeana.compiler.CompilerTypeGraphBudgetProbe')


if __name__ == '__main__':
    unittest.main(verbosity=2)
