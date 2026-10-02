#!/usr/bin/env python3
"""Repeatable fixture gate for two bounded javac contexts (5 warmups, 20 samples)."""
import json
import statistics
import time

from java_model_tests import Models


def main():
    test = Models('test_parallel_contexts_are_deterministic')
    test.setUp()
    try:
        contexts = []
        for name in ('a', 'b'):
            methods = ''.join('int m%d(){return %d;}' % (i, i) for i in range(100))
            contexts.append(test.context(name, {name.upper()+'.java':
                'class '+name.upper()+' { '+methods+' void run(){ System.out.println(m99()); } }'}))
        expected = test.analyze(contexts, parallelism=1)['facts']
        def sample(parallelism):
            started = time.perf_counter()
            result = test.analyze(contexts, parallelism=parallelism)
            assert result['facts'] == expected
            assert result['compiler_peak'] == parallelism
            return time.perf_counter() - started
        for _ in range(5):
            sample(1); sample(2)
        measured = {1: [], 2: []}
        for i in range(20):
            for parallelism in ((1, 2) if i % 2 == 0 else (2, 1)):
                measured[parallelism].append(sample(parallelism))
        serial = statistics.median(measured[1]); parallel = statistics.median(measured[2])
        result = dict(warmups=5, samples=20, serial_median_s=serial,
                      parallel_median_s=parallel, ratio=parallel/serial,
                      facts=len(expected), equivalent=True)
        print(json.dumps(result, sort_keys=True))
        assert result['ratio'] <= 1.10, 'parallelism regression exceeds 10%'
    finally:
        test.doCleanups()


if __name__ == '__main__': main()
