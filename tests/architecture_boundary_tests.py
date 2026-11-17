#!/usr/bin/env python3
"""Adversarial fixtures prove architecture checks reject regressions, not just today's tree."""
import importlib.util
import pathlib
import unittest
import tempfile

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

    def test_nested_production_modules_cannot_hide_compilers_or_graph_backend_code(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            (root/'Cargo.toml').write_text('[workspace]\nmembers=["crates/application","crates/graph-io","crates/contracts"]\n')
            graph={}
            for folder in ['application','graph-io','contracts']:
                directory=root/'crates'/folder
                (directory/'src/nested').mkdir(parents=True)
                package='codeana-'+folder
                (directory/'Cargo.toml').write_text('[package]\nname="'+package+'"\nversion="0.1.0"\n')
                graph[package]=set()
            files={
                'application':'use pyrefly_types::types::Type;',
                'graph-io':'use codeana_contracts::java as backend;',
                'contracts':'use tokio::sync::mpsc;',
            }
            for folder,source in files.items():
                (root/'crates'/folder/'src/nested/escape.rs').write_text(source)
            errors=architecture.check_rust_sources(root,graph)
            self.assertEqual(len(errors),3,errors)
            self.assertTrue(all('src/nested/escape.rs' in error for error in errors))
            for folder in files:
                (root/'crates'/folder/'src/nested/escape.rs').write_text('// pyrefly is only documentation\nconst NOTE: &str="use pyrefly_types::Type;";')
            self.assertEqual(architecture.check_rust_sources(root,graph),[])

    def test_production_code_after_a_test_module_is_not_hidden(self):
        harmless='#[cfg(test)] mod tests { fn helper() { let note="use pyrefly_types::Type; }"; } }'
        self.assertFalse(architecture.shared_rust_violations('codeana-application',harmless))
        self.assertTrue(architecture.shared_rust_violations('codeana-application',harmless+'\nuse pyrefly_types::Type;'))
        self.assertTrue(architecture.rust_violations('nested/reports.rs','#[cfg(test)] mod tests {}\nimpl Engine { fn report(&self) {} }'))

    def test_aliases_and_intermediate_packages_cannot_hide_a_compiler_dependency(self):
        with tempfile.TemporaryDirectory() as temp:
            root=pathlib.Path(temp)
            (root/'Cargo.toml').write_text('[workspace]\nmembers=["app","bridge"]\n')
            for directory,manifest in {
                'app':'[package]\nname="codeana-application"\nversion="0.1.0"\n[dependencies]\nrenamed={package="helper",path="../bridge"}\n',
                'bridge':'[package]\nname="helper"\nversion="0.1.0"\n[dependencies]\nparser_alias={package="pyrefly",version="1"}\n',
                'lang-python-adaptor':'[workspace]\nmembers=["worker"]\n',
                'lang-python-adaptor/worker':'[package]\nname="codeana-python-worker"\nversion="0.1.0"\n',
            }.items():
                path=root/directory;path.mkdir(parents=True,exist_ok=True);(path/'Cargo.toml').write_text(manifest)
            graph,_=architecture.dependency_graph(root)
            self.assertEqual(graph['codeana-application'],{'helper'})
            self.assertTrue(any('codeana-application -> helper -> pyrefly' in error for error in architecture.dependency_violations(graph)))

    def test_graph_io_and_runtime_boundaries_reject_transitive_storage_or_sdk_loading(self):
        for target in ['codeana-storage','libloading','codeana-rust-adaptor']:
            graph={'codeana-graph-io':{'middle'},'middle':{target}}
            self.assertTrue(any('codeana-graph-io -> middle -> '+target in error for error in architecture.dependency_violations(graph)))
        self.assertTrue(architecture.shared_rust_violations('codeana-graph-io','let child: std::process::Child;'))
        self.assertFalse(architecture.shared_rust_violations('codeana-graph-io','use codeana_contracts::graph_delivery::OutputLimits;'))

    def test_contracts_cannot_reacquire_java_codec_or_ipc_progress(self):
        for source in ['pub struct FrameReader { offset: usize }','pub struct EngineRequest { version: u32 }','use tokio::io::AsyncRead;']:
            self.assertTrue(architecture.shared_rust_violations('codeana-contracts',source))
        self.assertFalse(architecture.shared_rust_violations('codeana-contracts','pub struct Allocation { bytes: u64 }'))
        graph={'codeana-worker-runtime':{'middle'},'middle':{'libloading'}}
        self.assertTrue(any('codeana-worker-runtime -> middle -> libloading' in error for error in architecture.dependency_violations(graph)))

if __name__=='__main__': unittest.main(verbosity=2)
