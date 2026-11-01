#!/usr/bin/env python3
"""Adversarial fixtures prove architecture checks reject regressions, not just today's tree."""
import importlib.util
import pathlib
import unittest

spec=importlib.util.spec_from_file_location('architecture', pathlib.Path(__file__).resolve().parents[1]/'scripts/check-architecture.py')
architecture=importlib.util.module_from_spec(spec);spec.loader.exec_module(architecture)

class Boundaries(unittest.TestCase):
    def test_storage_dependency_is_detected_through_an_intermediate_crate_and_cycle(self):
        graph={'query':{'helper'},'helper':{'storage'},'storage':{'query'}}
        self.assertEqual(architecture.dependency_path(graph,'query',{'storage'}),['query','helper','storage'])
        self.assertIsNone(architecture.dependency_path({'query':{'model'}},'query',{'storage'}))

    def test_engine_cannot_reacquire_runtime_state_or_be_reexported_by_deref(self):
        self.assertTrue(architecture.rust_violations('lib.rs','pub struct Engine { jobs: Mutex<BTreeMap<TaskId, Job>> }'))
        self.assertFalse(architecture.rust_violations('lib.rs','pub struct Engine { tasks: Arc<TaskService>, execution: TaskExecution, generation: String }'))
        self.assertTrue(architecture.rust_violations('history.rs','impl Deref for HistoryService { type Target = Engine; }'))
        self.assertTrue(architecture.rust_violations('reports.rs','impl Engine { fn report(&self) {} }'))

    def test_index_and_service_cannot_reach_other_tasks_or_the_raw_store(self):
        for source in ['self.jobs.lock()', 'let store: Store;', 'let repository: Arc<dyn TaskRepository>;']:
            self.assertTrue(architecture.rust_violations('indexing.rs',source))
        self.assertFalse(architecture.rust_violations('indexing.rs','self.task.event(id, event); self.index.publish(candidate);'))
        self.assertTrue(architecture.rust_violations('ports.rs','trait IndexBaselineReader: SourceContext { fn rust_baseline_summary(); }'))

    def test_a_language_unit_cannot_start_or_prepare_another_language(self):
        self.assertTrue(architecture.rust_violations('java.rs','crate::python_inputs::prepare_inputs(capture);'))
        self.assertTrue(architecture.rust_violations('java.rs','Worker::start_python(config);'))
        self.assertTrue(architecture.rust_violations('rust_inputs.rs','Worker::start(config);'))
        self.assertFalse(architecture.rust_violations('java.rs','prepare_captured_inputs(capture);'))

    def test_compiler_types_stay_in_the_worker_source_set(self):
        main='lang-java-adaptor/analysis-core/src/main/java/Leak.java'
        for source in ['import com.sun.tools.javac.code.Type;', 'class Leak { javax.tools.JavaCompiler compiler; }']:
            self.assertTrue(architecture.java_violations(main,source))
        self.assertFalse(architecture.java_violations('lang-java-adaptor/analysis-core/src/worker/java/Compiler.java','import com.sun.tools.javac.code.Type;'))
        self.assertFalse(architecture.java_violations(main,'String flags="jdk.compiler/com.sun.tools.javac.parser"; // com.sun.tools.javac.code.Type'))

    def test_core_cannot_reintroduce_a_framework_or_a_parser_backend(self):
        main='lang-java-adaptor/analysis-core/src/main/java/Leak.java'
        self.assertTrue(architecture.java_violations(main,'new com.anatomist.framework.lombok.LombokJavaModelExtension();'))
        self.assertTrue(architecture.java_violations(main,'import com.github.javaparser.JavaParser;'))

    def test_budget_callback_is_abstract_and_production_never_uses_diagnostic_account(self):
        path='lang-java-adaptor/analysis-core/src/main/java/JavaModelExtension.java'
        required='JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries, AnalysisContext resources);'
        self.assertFalse(architecture.java_violations(path,required))
        old='default JavaAnalysisContext augment(JavaAnalysisContext context, JavaBinaryRepository binaries, AnalysisContext resources) { return augment(context,binaries); }'
        self.assertTrue(architecture.java_violations(path,old))
        self.assertTrue(architecture.java_violations('lang-java-adaptor/java-bridge/src/main/java/Runtime.java','registry.overlays();'))
        self.assertFalse(architecture.java_violations('lang-java-adaptor/java-bridge/src/main/java/Runtime.java','registry.overlays(context.analysisContext());'))

if __name__=='__main__': unittest.main(verbosity=2)
