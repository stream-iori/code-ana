#!/usr/bin/env python3
"""Bounded Java 8 daemon stress: hot reuse, cold delta/full equality, pinned readers and GC."""
import argparse, concurrent.futures, json, pathlib, subprocess, sys, time
from java_e2e import JavaProduct

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--rounds',type=int,default=20)
    parser.add_argument('--helpers',type=int,default=64)
    args=parser.parse_args()
    if not 1<=args.rounds<=100:parser.error('rounds must be 1..100')
    if not 1<=args.helpers<=128:parser.error('helpers must be 1..128')
    case=JavaProduct('test_core');case.setUp()
    started=time.monotonic();times=[];idle_rss=[]
    try:
        source=case.project/'src/p/App.java'
        source.write_text('package p; public class App { public static void main(String[] args) { new Helper0().run("round0"); } }')
        for helper in range(args.helpers):
            (source.parent/('Helper%d.java'%helper)).write_text('package p; class Helper%d { void run(String value) { System.out.println(value); } }'%helper)
        baseline=case.cli('index','--full',timeout=150)[1]['revision']
        case.cli('snapshot','before','--name','analysis',timeout=150)
        worker=case.java_worker_pid();assert worker is not None
        def pinned_reader():
            rows,terminal=case.cli('source','main','--revision','snapshot:before',timeout=30)
            assert terminal['revision']
            assert 'round0' in json.dumps(rows)
        for round_number in range(1,args.rounds+1):
            source.write_text('package p; public class App { public static void main(String[] args) { new Helper0().run("round%d"); } }'%round_number)
            start=time.monotonic()
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                reading=pool.submit(pinned_reader)
                _,indexed=case.cli('index',timeout=150)
                reading.result()
            assert indexed['publication']=='published',indexed
            assert case.java_worker_pid()==worker,'warm worker was not reused'
            times.append(time.monotonic()-start)
            rss=subprocess.check_output(['ps','-p',str(worker),'-o','rss='],text=True).strip()
            idle_rss.append(int(rss)*1024)
            assert idle_rss[-1]<1024*1024*1024,idle_rss[-1]
            assert 'round%d'%round_number in json.dumps(case.cli('source','main')[0])
            if round_number%5==0 or round_number==args.rounds:
                _,full=case.cli('index','--full',timeout=150)
                assert case.facts(indexed['revision'])==case.facts(full['revision']),'delta/full mismatch at round %d'%round_number
                case.cli('gc','--execute',timeout=60)
                pinned_reader()
        assert max(idle_rss)-min(idle_rss)<128*1024*1024,'idle RSS grew beyond 128 MiB'
        report=dict(rounds=args.rounds,helpers=args.helpers,baseline=baseline,elapsed_seconds=round(time.monotonic()-started,3),
                    min_index_seconds=round(min(times),3),max_index_seconds=round(max(times),3),
                    idle_rss_min=min(idle_rss),idle_rss_max=max(idle_rss),warm_pid=worker,
                    gc_checks=(args.rounds+4)//5)
        print(json.dumps(report,sort_keys=True))
    finally:
        case.doCleanups()

if __name__=='__main__':main()
