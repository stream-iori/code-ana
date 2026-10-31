#!/usr/bin/env python3
"""Actual three-adaptor failure, cancellation and deadline publication matrix.

Uses self-built source fixtures. No product fault hook or target compilation.
"""
import json
import unittest
import python_language_e2e as python

COMBINATIONS = [('java','rust'),('java','python'),('rust','python'),('java','rust','python')]

class MixedResources(python.PythonLanguage):
    def prepare_languages(self, languages):
        if 'java' in languages:
            self.add_java()
        if 'rust' in languages:
            self.add_rust()
        self.export()
        config=self.project/'.code-ana/config.toml'
        original=config.read_text()
        if 'python' not in languages:
            original=original.replace('[languages.python]\nenabled=true','[languages.python]\nenabled=false')
        original+='\n[resources.index]\nworker_memory_bytes=1073741824\n'
        config.write_text(original)
        _, result=self.cli('index','--full',timeout=180)
        self.assertEqual(result['publication'],'published')
        with self.db(result['revision']) as db:
            self.assertEqual({r[0] for r in db.execute('SELECT DISTINCT language FROM nodes')},set(languages))
        return config,original,result['revision']

    def preserved(self, revision, graph):
        self.assertEqual(self.cli('overview')[1]['revision'],revision)
        self.assertEqual(self.canonical(revision),graph)

    def verify_report(self, terminal, languages):
        report=self.cli('report',terminal['report'])[0][0]['analysis_resources']
        coordinator=report['coordinator']
        self.assertEqual({s['allocation']['language'] for s in coordinator['languages']},set(languages))
        for slot in coordinator['languages']:
            for worker in slot['workers']:
                usage=worker.get('usage')
                if usage is not None:
                    for used,limit in [('work','max_work'),('nodes','max_nodes'),('payload_bytes','max_payload_bytes')]:
                        self.assertLessEqual(usage[used],worker['grant']['limits'][limit])
        return report

    def failure_matrix(self,languages):
        config,original,revision=self.prepare_languages(languages)
        graph=self.canonical(revision)
        for failing in languages:
            with self.subTest(failing=failing):
                # Keep the root analysis grant healthy; fail the selected real
                # adaptor's output policy after parsing/extraction instead.
                source=self.project/{'java':'src/p/App.java','rust':'core/src/lib.rs','python':'main.py'}[failing]
                saved=source.read_text()
                source.write_text(saved+('\n#' if failing=='python' else '\n//')+' changed input for failed attempt\n')
                self.refresh_inputs(languages)
                config.write_text(original.replace('worker_memory_bytes=1073741824','worker_memory_bytes=1073741824\n'+failing+'_fact_limit=1'))
                for args in [('--full',),()]:
                    _,failed=self.cli('index',*args,code=1,timeout=180)
                    self.assertEqual(failed['publication'],'retained')
                    self.preserved(revision,graph)
                    self.verify_report(failed,languages)
                config.write_text(original)
                source.write_text(saved)
                self.refresh_inputs(languages)
                _,restored=self.cli('index','--full',timeout=180)
                self.assertEqual(restored['publication'],'published')
                self.assertEqual(self.canonical(restored['revision']),graph)
                self.preserved(restored['revision'],graph)
                revision=restored['revision']

    def refresh_inputs(self,languages):
        if 'rust' in languages:
            import subprocess
            from product_support import ROOT,BIN
            script=BIN/'codeana-export-rust-inputs'
            subprocess.run(['python3',str(script if script.exists() else ROOT/'scripts/export-rust-inputs.py'),'--project',str(self.project)],check=True,capture_output=True)
        self.export()

    def interrupt_matrix(self,languages,cancel):
        config,original,revision=self.prepare_languages(languages)
        graph=self.canonical(revision)
        # Keep timeout/cancellation independent of the logical budget tests.
        config.write_text(original+'\n[resources.index.analysis]\nmax_work=1000000000\nmax_nodes=10000000\nmax_payload_bytes=17179869184\n')
        sources={}
        if 'java' in languages:
            sources[self.project/'src/p/App.java']='package p; public class App {'+''.join('int m%d(){return %d;}'%(i,i) for i in range(10000))+'}'
        if 'rust' in languages:
            sources[self.project/'core/src/lib.rs']='\n'.join('pub fn large_%d()->u32{%d}'%(i,i) for i in range(10000))
        if 'python' in languages:
            sources[self.project/'main.py']='\n'.join('def large_%d():\n    return %d\n'%(i,i) for i in range(10000))
        originals={path:path.read_text() for path in sources}
        try:
            for path,text in sources.items():path.write_text(text)
            if 'rust' in languages:
                # Export inventories explicitly; neither exporter compiles.
                import subprocess
                from product_support import ROOT,BIN
                script=BIN/'codeana-export-rust-inputs'
                subprocess.run(['python3',str(script if script.exists() else ROOT/'scripts/export-rust-inputs.py'),'--project',str(self.project)],check=True,capture_output=True)
            self.export()
            args=['index','--full','--background']
            if not cancel:args+=['--deadline-seconds','1']
            task=json.loads(self.cli(*args,raw=True))['task_id']
            if cancel:
                self.wait_task(task)
                self.cli('task','cancel',task,raw=True)
            _,failed=self.cli('task','watch',task,code=130 if cancel else 124,timeout=45)
            self.assertEqual(failed['code'],'CANCELLED' if cancel else 'TIMEOUT')
            self.assertEqual(failed['publication'],'retained')
            self.preserved(revision,graph)
            # Deadline can occur during admission, so no grant is invented for
            # an adaptor that was never started.
            report=self.cli('report',failed['report'])[0][0]['analysis_resources']
            self.assertIsNotNone(report)
        finally:
            for path,text in originals.items():path.write_text(text)
            config.write_text(original)
            if 'rust' in languages:
                import subprocess
                from product_support import ROOT,BIN
                script=BIN/'codeana-export-rust-inputs'
                subprocess.run(['python3',str(script if script.exists() else ROOT/'scripts/export-rust-inputs.py'),'--project',str(self.project)],check=True,capture_output=True)
            self.export()
        _,restored=self.cli('index','--full',timeout=180)
        self.assertEqual(restored['publication'],'published')
        self.assertEqual(self.canonical(restored['revision']),graph)

# Register only this matrix, without rerunning inherited Python-only cases.
NAMES=[]
for languages in COMBINATIONS:
    for mode in ('failure','cancel','deadline'):
        name='test_'+'_'.join(languages)+'_'+mode
        def run(self,languages=languages,mode=mode):
            if mode=='failure':self.failure_matrix(languages)
            else:self.interrupt_matrix(languages,mode=='cancel')
        setattr(MixedResources,name,run)
        NAMES.append(name)

def load_tests(loader,tests,pattern):
    return unittest.TestSuite(MixedResources(name) for name in NAMES)

if __name__=='__main__':unittest.main(verbosity=2)
