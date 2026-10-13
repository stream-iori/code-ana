#!/usr/bin/env python3
"""Select smaller configurations within 5% of the fastest single CAF replay."""
import argparse
import hashlib
import json
import pathlib


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser()
    for name in ('experiment', 'resources', 'output'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    value = json.loads(args.experiment.read_text())
    resources = json.loads(args.resources.read_text())
    rows = value['measurements']
    assert value['experiment'] == 'sealed_raw_caf' and value['rounds_per_configuration'] == 1
    assert len(rows) == 6 and len({(r['batch'], r['page_size']) for r in rows}) == 6
    assert len({(r['unique'], r['duplicates']) for r in rows}) == 1
    assert rows[0]['unique'] + rows[0]['duplicates'] == value['records']
    assert resources['status'] == 'finished'
    assert resources['peak_rss_bytes'] <= 4 << 30
    assert resources['peak_scratch_logical_bytes'] <= 32 << 30
    selected = {}
    for kind, group, field, baseline in [
        ('dedup', [r for r in rows if r['page_size'] == 0], 'batch', 2048),
        ('sqlite', [r for r in rows if r['page_size'] != 0], 'page_size', 4096),
    ]:
        original = next(r for r in group if r[field] == baseline)
        fastest = min(r['seconds'] for r in group)
        eligible = [r for r in group if r['seconds'] <= fastest * 1.05
                    and (r[field] == baseline or r['seconds'] <= original['seconds'] * .95)]
        choice = min(eligible, key=lambda r: r[field]) if eligible else original
        selected[kind] = dict(value=choice[field], baseline=baseline,
                              seconds=choice['seconds'], baseline_seconds=original['seconds'],
                              single_observation_change=choice['seconds'] / original['seconds'] - 1)
    inputs = {str(p.resolve()): sha(p) for p in
              (args.experiment, args.resources, pathlib.Path(__file__))}
    result = dict(status='selected_for_full_chain_validation', selections=selected,
                  rule='at least 5% faster than baseline; within 5% of fastest choose smaller',
                  inputs=inputs, end_to_end_performance_accepted=False,
                  native_accepted=False, stable_gain_established=False)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(selected))


if __name__ == '__main__':
    main()
