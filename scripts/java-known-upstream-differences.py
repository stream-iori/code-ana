#!/usr/bin/env python3
"""Classify exact observed upstream defects without altering strict comparisons."""
import argparse
import collections
import hashlib
import json
import pathlib
import sqlite3


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def position(node):
    return dict(line=node['beginLine'], column=node['beginColumn'],
                end_line=node['endLine'], end_column=node['endColumn'])


def collision_aliases(proof):
    """Only positions actually overwritten by the frozen original store."""
    aliases, beans = {}, {}
    for collision in proof['collisions']:
        rows = collision['raw_occurrences']
        require(len(rows) == 2 and len(collision['published']) == 1
                and rows[0]['id'] == rows[1]['id'] == collision['raw_id']
                and rows[0]['beginLine'] != rows[1]['beginLine']
                and collision['published'][0]['begin_line'] == rows[-1]['beginLine'],
                'COLLISION_WITNESS_INCOMPLETE')
        if rows[0]['kind'] == 'BEAN':
            keys = [['bean', row['sourceFile'], row['label'], row['sourceOrdinal'], position(row)] for row in rows]
            aliases[encoded(keys[0])] = keys[1]
            beans[collision['raw_id']] = (rows, keys)
    require(len(beans) == 2, 'EXACT_DUPLICATE_BEAN_PROOF_REQUIRED')
    for collision in proof['collisions']:
        rows = collision['raw_occurrences']
        if rows[0]['kind'] == 'BEAN':
            continue
        parent = next((keys for identity, (_, keys) in beans.items()
                       if collision['raw_id'].startswith(identity + '/')), None)
        require(parent is not None, 'COLLISION_PARENT_NOT_PROVEN')
        if rows[0]['kind'] == 'XML_CALLABLE_REF':
            keys = [['callable', parent[i], row['metadata']['role']] for i, row in enumerate(rows)]
        else:
            # The original store also overwrites differing literal values.
            # Both exact values come from the independently captured raw rows;
            # no value is erased from the strict comparison or guessed here.
            keys = [['configuration', parent[i], row['metadata'], position(row)] for i, row in enumerate(rows)]
        aliases[encoded(keys[0])] = keys[1]
    require(len(aliases) == 14, 'EXACT_FOURTEEN_COLLISIONS_REQUIRED')
    return aliases


def collapsed_endpoint(value, aliases):
    if encoded(value) in aliases:
        return aliases[encoded(value)]
    if not isinstance(value, list):
        return value
    if value and value[0] == 'ambiguous_beans':
        candidates = value[1]
        require(len(candidates) == 2 and any(encoded(row) in aliases for row in candidates),
                'UNPROVEN_BEAN_AMBIGUITY')
        folded = [collapsed_endpoint(row, aliases) for row in candidates]
        require(folded[0] == folded[1], 'AMBIGUITY_NOT_CAUSED_BY_PROVEN_COLLISION')
        return folded[0]
    return [collapsed_endpoint(row, aliases) if isinstance(row, list) else row for row in value]


def counters(rows, normalize=lambda key: key):
    result = collections.Counter()
    for row in rows:
        require(row['count'] > 0, 'INVALID_DIFFERENCE_MULTIPLICITY')
        result[encoded(normalize(row['key']))] += row['count']
    return result


def callable_identity(record):
    return record['file'], record['bean'], record['position']['line'], record['member']


def classify_bindings(value, identity, raw, reference):
    require(sum(row['count'] for row in value['missing']) == 2
            and sum(row['count'] for row in value['extra']) == 9, 'XML_BINDING_DIFFERENCE_SET_CHANGED')
    missing = {callable_identity(json.loads(row['key'][0])): json.loads(row['key'][0]) for row in value['missing']}
    remaining = []
    arity = {
        ('common-service-integration-afs.xml', 15, 'setOssAdaptor'):
            ('com.alipay.zoss.impl.OssStorageManager', 'com.alipay.zoss.impl.OSSAdaptor'),
        ('merge-smvc-scheme-default.xml', 218, 'setJsonViewResolver'):
            ('com.alipay.sofa.web.mvc.core.servlet.view.json.AlipayJsonViewResolver', 'org.springframework.web.servlet.ViewResolver')}
    collision_ids = {row['raw_id']: row for row in identity['collisions']}
    for row in value['extra']:
        record = json.loads(row['key'][0]); key = callable_identity(record)
        require(row['count'] == 1 and record['candidates'] == []
                and record['candidate_count'] == 0 and record['resolution'] == 'unresolved'
                and record['metadata_consistent'] is True, 'UNEXPECTED_XML_BINDING_CANDIDATE')
        if key in missing:
            original = dict(missing.pop(key))
            hints, parameter = arity[(pathlib.Path(record['file']).name, record['position']['line'], record['member'])]
            require(original['hints'] == [hints] and len(original['candidates']) == 1
                    and original['candidates'][0][0] == original['owner'] + '#' + original['member'] + '(' + parameter + ')'
                    and original['candidate_count'] == 0 and original['metadata_consistent'] is False,
                    'UNPROVEN_ARITY_REBINDING')
            original['candidates'] = []; original['metadata_consistent'] = True
            require(original == record, 'ARITY_EXCEPTION_CHANGED_OTHER_METADATA')
            continue
        edges = [edge for edge in raw['edges'] if edge['relation'] == 'BINDS_TO'
                 and edge['sourceFile'] == record['file'] and edge['beginLine'] == record['position']['line']]
        require(len(edges) == 1 and edges[0]['sourceId'] in collision_ids, 'UNPROVEN_XML_OCCURRENCE')
        edge = edges[0]; collision = collision_ids[edge['sourceId']]
        require(record['position']['line'] == collision['raw_occurrences'][0]['beginLine'], 'NOT_OVERWRITTEN_OCCURRENCE')
        symbol = edge['metadata']['symbolRef']
        member = '<init>' if symbol['symbolKind'] == 'constructor' else symbol['memberName']
        require(symbol['owner'] == record['owner'] and member == record['member']
                and symbol['arity'] == record['arity'] and symbol['parameterTypeHints'] == record['hints'],
                'RAW_XML_SYMBOL_METADATA_CHANGED')
        nodes = reference.execute("SELECT id,begin_line FROM nodes WHERE producer_id='spring-xml' AND symbol_id=?", (edge['sourceId'],)).fetchall()
        require(len(nodes) == 1 and nodes[0][1] == collision['raw_occurrences'][-1]['beginLine'], 'REAL_REFERENCE_IDENTITY_NOT_OVERWRITTEN')
        stored = reference.execute("SELECT target_id,metadata FROM edges WHERE producer_id='spring-xml' AND relation='BINDS_TO' AND source_id=?", (nodes[0][0],)).fetchall()
        require(stored and all(target is None and json.loads(metadata)['candidateCount'] == 0
                               and json.loads(metadata)['symbolRef'] == symbol for target, metadata in stored),
                'REAL_JAVA_REFERENCE_METADATA_NOT_PROVEN')
        remaining.append(key)
    require(not missing and len(remaining) == 7, 'XML_BINDING_CLASSIFICATION_INCOMPLETE')
    return remaining


def main():
    parser = argparse.ArgumentParser()
    for name in ('semantics', 'identity-proof', 'arity-proof', 'global-type-proof',
                 'constructor-proof', 'inputs-manifest', 'output'):
        parser.add_argument('--' + name, type=pathlib.Path, required=True)
    args = parser.parse_args()
    require(not args.output.exists(), 'NEW_CLASSIFICATION_RECEIPT_REQUIRED')
    proofs = {str(pathlib.Path(__file__).resolve()): sha(__file__)}
    reports = {}
    for name in ('semantics', 'identity_proof', 'arity_proof', 'global_type_proof', 'constructor_proof'):
        path = getattr(args, name); reports[name] = json.loads(path.read_text()); proofs[str(path.resolve())] = sha(path)
        for filename, checksum in reports[name].get('inputs', {}).items():
            require(sha(filename) == checksum, 'UPSTREAM_PROOF_DRIFT: ' + filename)
            proofs[filename] = checksum
    semantic = reports['semantics']; require(semantic.get('changed_inputs') == [], 'SEMANTIC_INPUT_DRIFT')
    domains = {}
    for row in semantic['domains']:
        require(sha(row['output']) == row['output_sha256'], 'DOMAIN_RESULT_DRIFT')
        proofs[row['output']] = row['output_sha256']; domains[row['name']] = json.loads(pathlib.Path(row['output']).read_text())
    failures = {row['name'] for row in semantic['domains'] if row['status'] != 'passed'}
    require(failures == {'calls', 'declaration_attributes', 'xml_callable_bindings', 'xml_complete_graph'}, 'UNEXPECTED_FAILED_DOMAIN')
    ctor = reports['constructor_proof']
    require(ctor.get('status') == 'known_upstream_issue_verified' and ctor.get('changed_inputs') == [], 'CONSTRUCTOR_WITNESS_REQUIRED')
    source = ctor['source']; symbol = 'com.alipay.iopengw.biz.service.IOpengwManagerServiceImplTest#<init>()'
    calls = domains['calls']
    require(all(calls[name]['accepted'] is True for name in ('sites', 'targets', 'owners')), 'UNEXPLAINED_CALL_GRAPH_DIFFERENCE')
    declaration = calls['declarations']
    require(declaration['missing_count'] == 0 and declaration['extra_count'] == 1
            and declaration['differences_complete'] is True
            and declaration['extra_sample'] == [dict(key=[source, 'CONSTRUCTOR', symbol], count=1)], 'UNEXPLAINED_DECLARATION')
    attrs = domains['declaration_attributes']
    require(attrs['missing'] == 0 and attrs['extra'] == 1 and len(attrs['extra_sample']) == 1
            and attrs['extra_sample'][0]['key'][:3] == [source, 'CONSTRUCTOR', symbol]
            and json.loads(attrs['extra_sample'][0]['key'][3]) == {'generation': 'java', 'modifiers': ['public'], 'parameters': [], 'return': None},
            'UNEXPLAINED_CONSTRUCTOR_ATTRIBUTES')
    identity = reports['identity_proof']; aliases = collision_aliases(identity)
    for name in ('identity_proof', 'arity_proof', 'global_type_proof'):
        require(reports[name].get('status') == 'known_upstream_issue_verified'
                and reports[name].get('original_rules_unchanged') is True, 'ORIGINAL_STORE_WITNESS_REQUIRED')
    raw_path = next(path for path in identity['inputs'] if path.endswith('/raw.json'))
    raw = json.loads(pathlib.Path(raw_path).read_text())
    reference_path = domains['xml_callable_bindings']['database_sha256']['reference']
    # The verified independent DB path is in the complete scanner input proof.
    database = next(path for path, checksum in semantic['inputs'].items()
                    if path.endswith('/reference.db') and checksum == reference_path)
    with sqlite3.connect('file:' + database + '?mode=ro', uri=True) as reference:
        dropped = classify_bindings(domains['xml_callable_bindings'], identity, raw, reference)
    graph = domains['xml_complete_graph']
    require(not graph['schema_gaps'] and not graph['unknown_reference_edges'], 'UNEXPLAINED_XML_SCHEMA_GAP')
    witness_sources = {checksum for path, checksum in identity['inputs'].items()
                       if path.endswith('/project/' + identity['source'])}
    require(witness_sources == {graph['xml_source_sha256'][identity['source']]}, 'REAL_XML_WITNESS_SOURCE_MISMATCH')
    manifest = json.loads(args.inputs_manifest.read_text()); proofs[str(args.inputs_manifest.resolve())] = sha(args.inputs_manifest)
    require(semantic['inputs'].get(str(args.inputs_manifest.resolve())) == sha(args.inputs_manifest), 'SEMANTIC_MANIFEST_MISMATCH')
    modules = {row['id']: row for row in manifest['modules']}; visible = set(); pending = ['app/common/service/integration']
    while pending:
        module = pending.pop()
        if module in visible: continue
        visible.add(module); pending.extend(modules[module]['dependencies']['MAIN'])
    require('app/core/service' not in visible, 'REAL_MODULE_BOUNDARY_NOT_PROVEN')
    mismatch = []
    for relation, result in graph['projections'].items():
        if result['equal'] or relation == 'BINDS_TO': continue
        def normalize(key):
            key = list(key)
            for index in (0, 1): key[index] = encoded(collapsed_endpoint(json.loads(key[index]), aliases))
            if relation == 'DEFINED_BY' and json.loads(key[1]) == ['java', 'com.alipay.iopengw.core.service.migrate.ClientInfoCacheCallbackImpl']:
                require(key[2:] == [47, None, True] or key[2:] == [47, None, False], 'GLOBAL_TYPE_COORDINATE_CHANGED')
                require(json.loads(key[0])[1:3] == ['app/common/service/integration/src/main/resources/META-INF/spring/common-service-service.xml', 'clientInfoCacheCallbackImpl'], 'GLOBAL_TYPE_CONSUMER_CHANGED')
                key[4] = False
            return key
        if counters(result['missing'], normalize) != counters(result['extra'], normalize): mismatch.append(relation)
    require(not mismatch, 'UNEXPLAINED_XML_GRAPH_DIFFERENCES: ' + repr(mismatch))
    # BINDS_TO is emitted by both strict scanners; verify complete equality of
    # their actual difference records after whitespace-only JSON encoding.
    bindings = domains['xml_callable_bindings']
    for side in ('missing', 'extra'):
        canonical = lambda key: [json.loads(key[0])]
        require(counters(bindings[side], canonical) == counters(graph['projections']['BINDS_TO'][side], canonical), 'BINDING_SCANNERS_DISAGREE')
    report = dict(status='passed', revision=semantic['published_revision'], migration_semantics_accepted=True,
                  strict_reference_equal=False, full_semantic_diff_accepted=False,
                  unexplained_failures=[], native_accepted=False, inputs=proofs,
                  known_upstream_issues=[dict(kind='constructor_identity_collision', cases=ctor['collector_selected_cases']),
                    dict(kind='xml_wrong_arity_rebinding', occurrences=2),
                    dict(kind='xml_identity_overwrite', raw_identity_collisions=14, lost_callable_occurrences=dropped),
                    dict(kind='xml_global_type_promotion', occurrences=1, excluded_provider_module='app/core/service')])
    args.output.write_text(json.dumps(report, indent=2) + '\n'); print(report['status'])


if __name__ == '__main__':
    main()
