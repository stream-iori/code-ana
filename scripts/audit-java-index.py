#!/usr/bin/env python3
"""Read-only I1/I2/I4 checks. FTS write checks use an independent SQLite backup."""
import argparse
from contextlib import closing
import hashlib
import json
import pathlib
import re
import sqlite3
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def sha(path):
    with pathlib.Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def normalized(sql):
    return re.sub(r'\s+', ' ', sql).strip().rstrip(';')


def annotation_dependencies(db, relation, field):
    if field not in ('annotation', 'meta_annotation'):
        raise ValueError('ANNOTATION_DEPENDENCY_FIELD')
    # declaration_projection is checked against authoritative body rows above.
    # Its signature index avoids a declarations x annotations JSON comparison.
    sql = f"SELECT a.world,a.source,t.world,t.source FROM facts a JOIN declaration_projection p ON p.signature=json_extract(a.body,'$.attributes.{field}') JOIN facts t ON t.id=p.id AND json_extract(t.body,'$.attributes.provider_id')=json_extract(a.body,'$.attributes.provider_id') WHERE a.kind='facet' AND a.relation=? AND t.kind='declaration' AND a.source<>'' AND t.source<>'' AND a.source<>t.source"
    plan = list(db.execute('EXPLAIN QUERY PLAN ' + sql, [relation]))
    return list(db.execute(sql, [relation])), plan


def structures(db):
    return {(kind, name): normalized(sql) for kind, name, sql in db.execute(
        "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'")}


def audit(path, revision=None):
    path = pathlib.Path(path).resolve()
    before = sha(path)
    start = time.monotonic()
    report = dict(status='failed', database=str(path), before_sha256=before,
                  checks={}, errors=[], independent_reference_parity_accepted=False)
    errors = report['errors']
    def error(code, details):
        if len(errors) < 100:
            errors.append(dict(code=code, details=details))
    revision = pathlib.Path(revision) if revision else path.parent/'revision.json'
    captured = {}
    if revision.is_file():
        report['captured_manifest_sha256'] = sha(revision)
        for row in json.loads(revision.read_text())['manifest']['files']:
            key = (row['world'], row['path'])
            if key in captured: error('CAPTURE_MANIFEST_DUPLICATE', list(key))
            captured[key] = row
    else:
        error('CAPTURE_MANIFEST_REQUIRED', str(revision))
    with closing(sqlite3.connect(path.as_uri()+'?mode=ro', uri=True)) as db:
        db.execute('PRAGMA query_only=ON')
        expected = sqlite3.connect(':memory:')
        try:
            for name in ('schema.sql', 'query-indexes.sql', 'symbol-search.sql'):
                expected.executescript((ROOT/'crates/storage/src'/name).read_text())
            wanted, actual = structures(expected), structures(db)
            drift = [dict(kind=kind, name=name, expected=sql, actual=actual.get((kind,name)))
                     for (kind,name),sql in wanted.items() if actual.get((kind,name)) != sql]
            if drift: error('SCHEMA_STRUCTURE', drift)
            version = db.execute('PRAGMA user_version').fetchone()[0]
            if version != 4: error('SCHEMA_VERSION', version)
            report['checks']['I1'] = dict(schema_version=version, expected_objects=len(wanted), drift=drift)
        finally:
            expected.close()
        integrity = [r[0] for r in db.execute('PRAGMA integrity_check')]
        if integrity != ['ok']: error('SQLITE_INTEGRITY', integrity)
        if drift:
            # Missing/wrong structures are failures, not grounds to guess a legacy layout.
            report['checks']['I2'] = dict(status='not_executed', reason='schema_structure_failed')
            report['checks']['I4'] = dict(status='not_executed', reason='schema_structure_failed')
        else:
            counts = dict(db.execute('SELECT kind,count(*) FROM facts GROUP BY kind'))
            checks = {
                'dangling_parent': "SELECT f.id FROM facts f LEFT JOIN facts p ON p.id=f.parent WHERE f.parent<>'' AND p.id IS NULL",
                'dangling_target': "SELECT f.id FROM facts f LEFT JOIN facts t ON t.id=f.target WHERE f.target<>'' AND t.id IS NULL",
                'source_world': "SELECT f.id FROM facts f LEFT JOIN source_units s ON s.path=f.source AND s.world=f.world AND s.auxiliary=0 WHERE f.source<>'' AND s.path IS NULL",
                'call_target_site': "SELECT f.id FROM facts f LEFT JOIN facts p ON p.id=f.parent WHERE f.kind='call_target' AND (p.kind IS NOT 'call_site' OR p.owner<>f.owner OR p.source<>f.source)",
                'binding_owner': "SELECT f.id FROM facts f LEFT JOIN facts p ON p.id=f.parent WHERE f.kind='facet' AND f.relation='java.binding' AND json_extract(f.body,'$.attributes.schema_version')>=3 AND (p.kind IS NOT 'call_site' OR p.owner<>f.owner OR p.source<>f.source)",
                'fact_columns': "SELECT id FROM facts WHERE id IS NOT json_extract(body,'$.id') OR kind IS NOT json_extract(body,'$.kind') OR world IS NOT json_extract(body,'$.owner.world') OR source IS NOT json_extract(body,'$.source') OR parent IS NOT json_extract(body,'$.parent') OR target IS NOT json_extract(body,'$.target') OR name IS NOT json_extract(body,'$.name') OR relation IS NOT json_extract(body,'$.relation')",
                'source_span': "SELECT f.id FROM facts f JOIN source_units s ON s.path=f.source AND s.world=f.world WHERE json_type(f.body,'$.span')='object' AND (json_extract(f.body,'$.span.start')<0 OR json_extract(f.body,'$.span.end')<json_extract(f.body,'$.span.start'))",
            }
            findings = {}
            for name, sql in checks.items():
                rows = [r[0] for r in db.execute(sql+' LIMIT 100')]
                findings[name] = rows
                if rows: error(name.upper(), rows)
            for source, world, digest in db.execute('SELECT path,world,hash FROM source_units'):
                row = captured.get((world,source))
                if row is None or row['hash'] != digest:
                    error('CAPTURE_MANIFEST_IDENTITY', dict(source=source,world=world,hash=digest))
            bounds = []
            for identifier, source, world, start, end in db.execute("SELECT id,source,world,json_extract(body,'$.span.start'),json_extract(body,'$.span.end') FROM facts WHERE json_type(body,'$.span')='object'"):
                row = captured.get((world,source))
                if row is not None and (not isinstance(start,int) or not isinstance(end,int)
                        or start < 0 or end < start or end > row['size']):
                    if len(bounds) < 100: bounds.append(identifier)
            findings['source_span_bounds'] = bounds
            if bounds: error('SOURCE_SPAN_BOUNDS', bounds)
            # Only declaration parent chains have a tree contract. Relation cycles are legal.
            parents = dict(db.execute("SELECT id,parent FROM facts WHERE kind='declaration'"))
            checked = set()
            for node in parents:
                current, chain = node, set()
                while current in parents and current not in checked:
                    if current in chain:
                        error('DECLARATION_PARENT_CYCLE', sorted(chain)); break
                    chain.add(current); current=parents[current]
                checked.update(chain)
            for body, owner in db.execute('SELECT body,owner FROM facts'):
                fact=json.loads(body)
                # Rust Owner derives Serialize in this field order.
                ordered={key:fact['owner'][key] for key in ('world','producer','kind','id')}
                expected_owner=hashlib.sha256(json.dumps(ordered,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
                if owner!=expected_owner: error('OWNER_IDENTITY',fact['id'])
            projection = {}
            for label, left, right in [('missing','declaration_rows','declaration_projection'),
                                       ('stale','declaration_projection','declaration_rows')]:
                projection[label] = [list(r) for r in db.execute(f'SELECT * FROM {left} EXCEPT SELECT * FROM {right} LIMIT 100')]
                if projection[label]: error('DECLARATION_PROJECTION_'+label.upper(),projection[label])
            # Independent endpoint inventory; annotation names use the original provider join.
            dependencies = set(db.execute("SELECT e.world,e.source,t.world,t.source FROM facts e JOIN facts t ON e.target=t.id WHERE e.kind IN ('reference','relation','dependency','call_target') AND t.kind IN ('declaration','facet') AND e.source<>'' AND t.source<>'' AND e.source<>t.source"))
            annotation_plans = {}
            for relation, field in [('java.annotation','annotation'),('java.annotation.meta','meta_annotation')]:
                rows, annotation_plans[relation] = annotation_dependencies(db, relation, field)
                dependencies.update(rows)
            report['checks']['annotation_dependency_plans'] = annotation_plans
            stored=set(db.execute('SELECT source_world,source_file,target_world,target_file FROM file_dependencies'))
            invalid_ids = [r[0] for r in db.execute('SELECT id FROM file_dependencies WHERE id<>json_array(source_world,source_file,target_world,target_file) LIMIT 100')]
            if invalid_ids: error('FILE_DEPENDENCY_IDENTITY',invalid_ids)
            dependency_diff=dict(missing=sorted(dependencies-stored),stale=sorted(stored-dependencies))
            if dependency_diff['missing'] or dependency_diff['stale']: error('FILE_DEPENDENCY_PROJECTION',dependency_diff)
            report['checks']['I2']=dict(fact_counts=counts,findings=findings)
            # integrity-check with rank=1 compares external content, not merely FTS internal pages.
            with tempfile.TemporaryDirectory(prefix='codeana-index-audit-') as directory:
                backup=pathlib.Path(directory)/'facts.db'
                with closing(sqlite3.connect(backup)) as copy:
                    db.backup(copy)
                    try:
                        copy.execute("INSERT INTO symbol_names(symbol_names,rank) VALUES('integrity-check',1)")
                        fts='passed'
                    except sqlite3.DatabaseError as failure:
                        fts=str(failure); error('SYMBOL_FTS_INTEGRITY',fts)
                backup_bytes=backup.stat().st_size
            report['checks']['I4']=dict(integrity=integrity,declaration_projection=projection,
                file_dependencies=dict(expected=len(dependencies),stored=len(stored),**dependency_diff),
                symbol_fts=fts,private_backup_bytes=backup_bytes)
    report.update(after_sha256=sha(path),seconds=time.monotonic()-start)
    if report['after_sha256']!=before: error('READ_ONLY_DATABASE_DRIFT',report['after_sha256'])
    report['status']='passed' if not errors else 'failed'
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('database',type=pathlib.Path)
    parser.add_argument('--output',required=True,type=pathlib.Path)
    parser.add_argument('--revision',type=pathlib.Path)
    args=parser.parse_args()
    result=audit(args.database,args.revision)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps(dict(status=result['status'],errors=result['errors'],seconds=result['seconds']),ensure_ascii=False))
    raise SystemExit(0 if result['status']=='passed' else 1)
