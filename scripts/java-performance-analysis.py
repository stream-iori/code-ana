#!/usr/bin/env python3
"""Read completed benchmark receipts and preserve evidence-based recommendations."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def accepted(path):
    value = json.loads(path.read_text())
    if value.get('status') != 'passed' or value.get('performance_accepted') is not True:
        raise ValueError('COMPLETED_PERFORMANCE_REQUIRED: ' + str(path))
    if value.get('changed_inputs') != [] or not value.get('inputs'):
        raise ValueError('UNCHANGED_PERFORMANCE_PROOFS_REQUIRED')
    for filename, checksum in value['inputs'].items():
        if not Path(filename).is_absolute() or sha(filename) != checksum:
            raise ValueError('PERFORMANCE_PROOF_DRIFT: ' + filename)
    return value


def main():
    parser = argparse.ArgumentParser()
    for name in ['backend', 'storage', 'output']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    backend, storage = accepted(args.backend), accepted(args.storage)
    if len(backend['rounds']) != 12 or len(storage['rounds']) != 3:
        raise ValueError('ALL_THREE_ROUNDS_REQUIRED')
    if args.output.exists() or args.output.with_suffix('.json').exists():
        raise ValueError('NEW_ANALYSIS_OUTPUT_REQUIRED')
    lines = ['# 三轮性能分析', '', '结论依据本轮实际回执；潜在瓶颈与已证实结果分开。尚未做热点采样，不把候选原因当作根因。', '',
             '| 后端 / 应用缓存 | 第1轮秒 | 第2轮秒 | 第3轮秒 | 中位数秒 | 进程树内存峰值范围 GiB |',
             '|---|---:|---:|---:|---:|---:|']
    groups = {}
    for name in ['jvm', 'native']:
        for temperature in ['cold_application_cache', 'warm_application_cache']:
            rows = sorted((r for r in backend['rounds'] if r['backend'] == name and r['temperature'] == temperature), key=lambda r: r['round'])
            if [r['round'] for r in rows] != [1, 2, 3]:
                raise ValueError('EXACT_ROUND_IDENTITIES_REQUIRED')
            values = [r['index_seconds'] for r in rows]
            peaks = [r['monitor']['process_tree_peak_bytes'] / 2**30 for r in rows]
            groups[name + ':' + temperature] = statistics.median(values)
            lines.append(f"| {name} / {'冷' if temperature.startswith('cold') else '热'} | " + ' | '.join(f'{v:.2f}' for v in values) + f' | {statistics.median(values):.2f} | {min(peaks):.2f}～{max(peaks):.2f} |')
    lines += ['', '冷热只指应用缓存；操作系统页缓存未控制。每次都是 --full 全量索引，热启动不代表无变更增量。索引计时不含随后全量图核对与查询；进程树观测包含这些步骤。', '',
              '| 缓存 | Native 相对 JVM 索引耗时变化 |', '|---|---:|']
    for temperature in ['cold_application_cache', 'warm_application_cache']:
        ratio = groups['native:' + temperature] / groups['jvm:' + temperature] - 1
        lines.append(f"| {'冷' if temperature.startswith('cold') else '热'} | {ratio:+.1%} |")
    lines += ['', '## 存储隔离测量', '', '| 阶段 | 第1轮秒 | 第2轮秒 | 第3轮秒 | 中位数秒 |', '|---|---:|---:|---:|---:|']
    storage_medians = {}
    for field in ['blob_seconds', 'export_archive_seconds', 'stage_seconds', 'publish_seconds']:
        values = [r['phases'][field] for r in storage['rounds']]
        storage_medians[field] = statistics.median(values)
        lines.append('| ' + field + ' | ' + ' | '.join(f'{v:.2f}' for v in values) + f' | {statistics.median(values):.2f} |')
    lines += ['', '存储回放使用 release example，后端索引使用来自 target/debug 的 Rust 二进制；回放不执行 javac，也不重算已派生的 Wiring。两组时间不能直接相减推算 Java 耗时，也不能直接当作相同编译配置下的阶段占比。', '',
              '## CPU 与查询证据', '', '| 后端 / 缓存 | 承载 Java 核心 + Rust worker 的采样 CPU 中位秒 | daemon 采样 CPU 中位秒 | javac 采样 CPU 中位秒 |', '|---|---:|---:|---:|']
    for name in ['jvm', 'native']:
        for temperature in ['cold_application_cache', 'warm_application_cache']:
            rows = [r for r in backend['rounds'] if r['backend'] == name and r['temperature'] == temperature]
            cpu = [r['monitor']['sampled_cpu_seconds'] for r in rows]
            medians = [statistics.median(c.get('rust_worker', 0) + c.get('jvm_runner', 0) for c in cpu), statistics.median(c.get('daemon', 0) for c in cpu), statistics.median(c.get('javac', 0) for c in cpu)]
            lines.append(f"| {name} / {'冷' if temperature.startswith('cold') else '热'} | " + ' | '.join(f'{v:.2f}' for v in medians) + ' |')
    lines += ['', 'Native 的 Java 核心运行在 Rust worker 进程里，因此合并 JVM runner + worker 后才比较。短命子进程可能漏采；累计 CPU 不是墙钟时间，重叠阶段不能相加当作总耗时。', '',
              '| 查询 | JVM端到端中位秒 | Native端到端中位秒 |', '|---|---:|---:|']
    for query in ['resolve', 'structure', 'source', 'search-code', 'search-text', 'overview']:
        values = {name: [q['seconds'] for r in backend['rounds'] if r['backend'] == name for q in r['phase']['queries'] if q['id'] == query] for name in ['jvm', 'native']}
        lines.append(f"| {query} | {statistics.median(values['jvm']):.3f} | {statistics.median(values['native']):.3f} |")
    lines += ['', '查询包含 CLI启动、RPC与输出，且每种仅一个固定选择器；不能据此声称所有查询都快，或把端到端耗时当作SQL耗时。', '',
              '## 改造顺序与验证方法', '',
              '| 优先级 | 潜在问题与代码证据 | 改造方向 | 验证与风险 |', '|---|---|---|---|',
              '| P0 | Rust运行器从target/debug复制二进制；debug优化程度影响JSON、hash、校验与SQLite入库周边代码 | 先用同源码release CLI/daemon/worker完成同样正确性门禁，再复测三轮；明确构建配置和机器负载 | 先消除测量配置影响，不能承诺提速倍数；保留当前原值 |',
              '| P0 | 目前只有进程级CPU及粗粒度stage计时，不能区分GC、解码、触发器和建索引 | 给解析/归档校验/入库/派生/投影/FTS/统计增加有界阶段计时；单独采样Rust与Native热点、GC和分配 | 采样与正式计时分开，先测观测开销；不降低诊断或预算检查 |',
              '| P1 | CAF1 push做Fact→JSON；reader还原Fact；SQLite入库再次序列化body。staged.rs同时维护投影触发器 | 如果热点证实重复编码昂贵，传递已验证的规范JSON字节与独立头字段，减少反复decode/encode | 不跳过边界校验；字段/重复次数/owner/provenance全量相等，坏帧、取消、预算回归通过 |',
              '| P1 | 全量新候选逐行维护declaration等投影，JSON提取与索引维护可能放大写入 | 若入库/投影主导，full专用候选先批量写事实，再集合式构建投影及查询索引；delta保留owner替换路径 | 候选内部完成全部派生、投影/FTS校验与统计后才发布；保持CAS、旧head和失败清理，禁止放松耐久性 |',
              '| P2 | 当前冷热测量均要求full，读取/校验/重建大量未变事实；不能证明增量缓存收益 | 单独测无变更、单文件、跨模块及配置失效的真实增量，再优化可证明未失效的signature/owner缓存与派生依赖 | 保守full回退、历史源码、相同目标full/delta图等价必须保留；不把full测量重命名为增量 |',
              '| P2 | Native较慢的具体原因未做热点证明 | 若热点落在Native Java核心，再比较GC/分配、JSON与图遍历、Native Image编译优化及PGO；使用同输入控制实验 | 一次改一个变量，记录吞吐与RSS取舍；保持单javac后端、C ABI与JVM事实等价，禁止通过扩大上限掩盖问题 |',
              '| P3 | 数据库包含完整JSON事实、投影和21项统计索引，空间/写入成本需结合真实DBSTAT排序 | 按表/索引体积分解；验证访问计划后删重复索引，或压缩/外置大payload并保留可验证身份 | 需要新schema版本及迁移/恢复；查询解码与写入收益可能互换，最后做，不为减小文件丢事实 |', '',
              '先完成P0，再由热点决定P1的先后。收益暂不量化；使用阶段耗时与CPU实测确定可优化上限，所有候选都要重跑受影响正确性与三轮测量。', '',
              '## 来源', '', f'- 后端回执：{args.backend.resolve()}', f'- 存储回执：{args.storage.resolve()}',
              f'- 原JSON归档：{ROOT / "crates/contracts/src/archive.rs"}', f'- 候选入库：{ROOT / "crates/storage/src/staged.rs"}',
              f'- 投影触发器：{ROOT / "crates/storage/src/schema.sql"}', f'- 运行器：{ROOT / "tests/java_product_runtime.py"}', '']
    source_paths = [args.backend, args.storage, Path(__file__), ROOT/'crates/contracts/src/archive.rs', ROOT/'crates/storage/src/staged.rs', ROOT/'crates/storage/src/schema.sql', ROOT/'tests/java_product_runtime.py']
    proof = {str(p.resolve()): sha(p) for p in source_paths}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text('\n'.join(lines))
    args.output.with_suffix('.json').write_text(json.dumps(dict(status='passed', kind='completed_performance_analysis', inputs=proof, output_sha256=sha(args.output), hotspot_root_cause_proven=False, product_changes=False), indent=2) + '\n')
    print(args.output)


if __name__ == '__main__':
    main()
