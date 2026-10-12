#!/usr/bin/env python3
"""Compare one complete release round per version, without medians or hidden reruns."""
import argparse
import json
from pathlib import Path

TEMPERATURES = ('cold_application_cache', 'warm_application_cache')


def phases(receipt):
    if receipt.get('status') != 'passed' or receipt.get('build_profile') != 'release' or receipt.get('rounds_per_backend') != 1:
        raise ValueError('ONE_COMPLETE_RELEASE_ROUND_REQUIRED')
    result = {}
    for row in receipt['rounds']:
        key = (row['backend'], row['temperature'])
        if key in result or row['phase'].get('status') != 'passed' or row['monitor'].get('status') != 'passed' or row['monitor'].get('foreign_analyzers'):
            raise ValueError('EXCLUSIVE_SUCCESSFUL_PHASE_REQUIRED')
        result[key] = row
    if set(result) != {(b,t) for b in ('jvm','native') for t in TEMPERATURES}:
        raise ValueError('FOUR_PHASES_REQUIRED')
    return result


def compare(baseline, candidate):
    before, after = phases(baseline), phases(candidate)
    if baseline['budget'] != candidate['budget']:
        raise ValueError('BUDGET_DRIFT')
    checks = []
    for key, old in before.items():
        new = after[key]
        if old['phase']['graph'] != new['phase']['graph']:
            raise ValueError('FACT_GRAPH_DRIFT')
        a, b = old['index_seconds'], new['index_seconds']
        if a <= 0 or b <= 0: raise ValueError('POSITIVE_DURATION_REQUIRED')
        limit = .5 if key[0] == 'native' else 1.05
        checks.append(dict(backend=key[0], temperature=key[1], stage='full_index', baseline_seconds=a, candidate_seconds=b, ratio=b/a, maximum_ratio=limit, passed=b/a <= limit))
        oq, nq = old['phase']['queries'], new['phase']['queries']
        if len(oq) != 6 or len(nq) != 6 or len({r['id'] for r in oq}) != 6 or {r['id'] for r in oq} != {r['id'] for r in nq}:
            raise ValueError('SIX_FIXED_QUERIES_REQUIRED')
        for query in oq:
            target = next(r for r in nq if r['id'] == query['id'])
            if query['records'] != target['records']: raise ValueError('QUERY_RESULT_COUNT_DRIFT')
            a, b = query['seconds'], target['seconds']
            if a <= 0 or b <= 0: raise ValueError('POSITIVE_DURATION_REQUIRED')
            checks.append(dict(backend=key[0],temperature=key[1],stage=query['id'],baseline_seconds=a,candidate_seconds=b,ratio=b/a,maximum_ratio=1.05,passed=b/a <= 1.05))
    return dict(status='passed' if all(r['passed'] for r in checks) else 'failed', optimization_accepted=all(r['passed'] for r in checks), checks=checks, native_goal='both cold and warm <=50% of same-source release baseline', rounds_per_version=1)


def main():
    parser = argparse.ArgumentParser()
    for name in ('baseline','candidate','output'): parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    result=compare(json.loads(args.baseline.read_text()),json.loads(args.candidate.read_text()))
    import hashlib
    result['inputs']={str(p.resolve()):hashlib.sha256(p.read_bytes()).hexdigest() for p in (args.baseline,args.candidate,Path(__file__))}
    if args.output.exists(): raise ValueError('NEW_COMPARISON_RECEIPT_REQUIRED')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+'\n')
    return 0 if result['optimization_accepted'] else 1


if __name__=='__main__': raise SystemExit(main())
