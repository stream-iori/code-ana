"""Valid current SDK metadata for installation and codec boundary fixtures."""
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]


def versions():
    text = (ROOT / 'crates/model/src/lib.rs').read_text()
    read = lambda name: int(re.search(r'pub const '+name+r': u32 = (\d+)', text)[1])
    return dict(worker_protocol=read('WORKER_PROTOCOL_VERSION'), fact_ir=read('VERSION'),
                analysis_request=read('JAVA_ANALYSIS_VERSION'))


def java_sdk(files, runtime, adapter='fixture', gc='G1'):
    current = versions()
    return dict(version=4, worker_protocol=current['worker_protocol'], fact_ir=current['fact_ir'],
                graph_schema=36, graph_encoding='GRA1-protobuf-row-v1', adapter=adapter,
                os='Darwin', arch='arm64', files=files,
                backend=dict(kind='java', analysis_request=current['analysis_request'],
                    compiler_batch_protocol=2, java_model='javac-model-v51', target_java=[8, 17],
                    compiler_bridges={'8': 'compiler-bridge.jar', '17': 'compiler-bridge17.jar'},
                    runtime=dict(kind='native', abi=4, gc=gc, build_mode='fixture') if runtime == 'native' else dict(kind='jvm')))
