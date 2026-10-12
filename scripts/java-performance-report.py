#!/usr/bin/env python3
"""Derive bounded CSV/JSON reports from a completed, verified diagnostic round."""
import argparse
import csv
import hashlib
import importlib.util
import json
from collections import defaultdict
from pathlib import Path


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b''):
            value.update(chunk)
    return value.hexdigest()


def phase_runtime(java):
    pending, rows = {}, []
    for sample in java['phase_runtime_samples']:
        key = sample['phase'], sample.get('context')
        if sample['beginning']:
            if key in pending:
                raise ValueError('DUPLICATE_PHASE_START')
            pending[key] = sample
            continue
        before = pending.pop(key)
        a, z = before['runtime'], sample['runtime']
        def delta(name):
            return z[name] - a[name] if a.get(name) is not None and z.get(name) is not None else None
        gc = {g['name']: g for g in a['gc']}
        rows.append(dict(phase=key[0], context=key[1], wall_seconds=(sample['elapsed_ns']-before['elapsed_ns'])/1e9,
                         cpu_seconds=None if delta('thread_cpu_ns') is None else delta('thread_cpu_ns')/1e9,
                         allocated_bytes=delta('thread_allocated_bytes'),
                         gc={g['name']: dict(count=g['count']-gc[g['name']]['count'],
                                             time_ms=g['time_ms']-gc[g['name']]['time_ms']) for g in z['gc']}))
    if pending:
        raise ValueError('UNFINISHED_RUNTIME_PHASE')
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    root = args.evidence.resolve()
    receipt_path = root/'receipt.json'
    receipt = json.loads(receipt_path.read_text())
    if receipt['status'] != 'passed' or len(receipt['rounds']) != 4:
        raise ValueError('COMPLETE_FOUR_PHASE_ROUND_REQUIRED')
    inputs = {str(receipt_path): digest(receipt_path), str(Path(__file__).resolve()): digest(Path(__file__))}
    monitor_path = Path(__file__).resolve().parents[1]/'scripts/java-performance-monitor-single-v2.py'
    if not monitor_path.exists():
        monitor_path = Path.cwd()/'scripts/java-performance-monitor-single-v2.py'
    loader = importlib.util.spec_from_file_location('monitor', monitor_path)
    monitor = importlib.util.module_from_spec(loader)
    loader.loader.exec_module(monitor)
    inputs[str(monitor_path.resolve())] = digest(monitor_path)
    output = dict(scope='one serial round; diagnostic only', rounds=[], optimization_accepted=False,
                  sample_estimates='Fixed-interval samples are not exclusive totals or confidence intervals; periodic aliasing is possible.',
                  resource_scope='Whole phase, including startup, graph verification, and queries; not index-only CPU.')
    with (root/'metrics.csv').open('w') as metrics, (root/'resources.csv').open('w') as resources, (root/'runtime-phases.csv').open('w') as phases, (root/'storage-stages.csv').open('w') as storage:
        mw, rw, pw = csv.writer(metrics), csv.writer(resources), csv.writer(phases)
        sw = csv.writer(storage)
        sw.writerow(('backend','temperature','scope','stage','seconds'))
        mw.writerow(('backend','temperature','collector','metric','calls','bytes','samples','sampling_stride','measured_wall_seconds','measured_cpu_seconds','cpu_samples','sampled_bytes','illustrative_wall_estimate_seconds'))
        rw.writerow(('backend','temperature','phase_elapsed_seconds','role','rss_bytes','sampled_cumulative_cpu_seconds','disk_logical_bytes','disk_allocated_bytes'))
        pw.writerow(('backend','temperature','phase','context','wall_seconds','thread_cpu_seconds','thread_allocated_bytes','gc_json'))
        for row in receipt['rounds']:
            backend, temperature = row['backend'], row['temperature']
            folder = root/backend/temperature
            monitor_folder = root/backend/(temperature+'-monitor')
            monitor_receipt_path = monitor_folder/'receipt.json'
            observation = json.loads(monitor_receipt_path.read_text())
            if observation['status'] != 'passed' or observation['foreign_analyzers'] or observation['sampling_errors']:
                raise ValueError('CLEAN_MONITOR_REQUIRED')
            java_path = next((folder/'worker-timings').glob('worker-*/java-timings.json'))
            java = json.loads(java_path.read_text())
            coarse = {}
            for name in ['index','capture']:
                paths = list((folder/'worker-timings').glob(name+'-*.json'))
                if len(paths) != 1:
                    raise ValueError('ONE_COARSE_TIMELINE_REQUIRED: '+name)
                path = paths[0]
                inputs[str(path.resolve())] = digest(path)
                coarse[name] = json.loads(path.read_text())
            for name, value in coarse['index']['storage_phase_ms'].items():
                sw.writerow((backend,temperature,'storage',name,value/1000))
            elapsed = coarse['index']['phase_elapsed_ms']
            order = ['prepared','worker_context','baseline_exported','analyzed','candidate_validated','published','ack_finished']
            previous = 0
            for name in order:
                if elapsed[name] < previous:
                    raise ValueError('INVALID_INDEX_TIMELINE')
                sw.writerow((backend,temperature,'index',name,(elapsed[name]-previous)/1000))
                previous = elapsed[name]
            sw.writerow((backend,temperature,'capture','captured',coarse['capture']['elapsed_ms']/1000))
            for p in [java_path, monitor_receipt_path, folder/'receipt.json', monitor_folder/'samples.ndjson']:
                inputs[str(p.resolve())] = digest(p)
            for path, expected in row['timing_files'].items():
                if digest(Path(path)) != expected:
                    raise ValueError('TIMING_FILE_DRIFT')
                inputs[path] = expected
            runtime_rows = phase_runtime(java)
            totals = defaultdict(lambda:dict(calls=0,wall_seconds=0,cpu_seconds=0,allocated_bytes=0,gc=defaultdict(lambda:dict(count=0,time_ms=0))))
            for item in runtime_rows:
                pw.writerow((backend,temperature,item['phase'],item['context'],item['wall_seconds'],item['cpu_seconds'],item['allocated_bytes'],json.dumps(item['gc'],sort_keys=True)))
                total = totals[item['phase']]
                total['calls'] += 1
                for name in ['wall_seconds','cpu_seconds','allocated_bytes']:
                    if item[name] is None:
                        total[name] = None
                    elif total[name] is not None:
                        total[name] += item[name]
                for name, value in item['gc'].items():
                    for key in ['count','time_ms']:
                        total['gc'][name][key] += value[key]
            for collector, counters in dict(java=row['java_metrics'],worker=row['rust_metrics'],**row['daemon_metrics']).items():
                for name, value in counters.items():
                    if not value['calls']:
                        continue
                    wall = value['measured_ns']/1e9
                    cpu_samples = value.get('cpu_samples',0)
                    mw.writerow((backend,temperature,collector,name,value['calls'],value['bytes'],value['samples'],value['sampling_stride'],wall,value.get('measured_cpu_ns',0)/1e9 if cpu_samples else None,cpu_samples,value['sampled_bytes'],wall*value['calls']/value['samples'] if value['samples'] else None))
            cpu, roles = {}, {}
            with (monitor_folder/'samples.ndjson').open() as stream:
                for line in stream:
                    sample = json.loads(line)
                    for process in sample['processes']:
                        monitor.record_cpu(cpu,roles,process)
                    cumulative = monitor.cpu_totals(cpu,roles)
                    for role in sorted(set(sample['roles'])|set(cumulative)):
                        rw.writerow((backend,temperature,sample['seconds'],role,sample['roles'].get(role,0),cumulative.get(role,0),sample.get('disk',{}).get('logical_bytes'),sample.get('disk',{}).get('allocated_bytes')))
            if cumulative != observation['sampled_cpu_seconds']:
                raise ValueError('RESOURCE_CPU_AGGREGATION_DRIFT')
            output['rounds'].append(dict(backend=backend,temperature=temperature,index_seconds=row['index_seconds'],java_seconds=row['java_seconds'],
                                        phase_runtime=totals,java_runtime=row['runtime'],projection=row['java_projection_statistics'],
                                        monitor=observation,graph=row['graph'],daemon_timeline=coarse))
    (root/'report-data.json').write_text(json.dumps(output,indent=2)+'\n')
    files=['metrics.csv','resources.csv','runtime-phases.csv','storage-stages.csv','report-data.json']
    proof=dict(status='passed',purpose='verified diagnostic report derivation',inputs=inputs,
               outputs={str((root/name).resolve()):digest(root/name) for name in files})
    (root/'report-data-receipt.json').write_text(json.dumps(proof,indent=2)+'\n')
    print(json.dumps(dict(status=proof['status'],rounds=len(output['rounds']),outputs=files)))


if __name__ == '__main__':
    main()
