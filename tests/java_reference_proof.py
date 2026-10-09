from contextlib import closing
"""Independent provenance for a frozen reference JAR and its published database."""
import json
import pathlib
import sqlite3
from java_declaration_inventory_diff import digest

# Keep the frozen JAR identity separate from every database identity.
REFERENCE_SHA = '457031ca061c58f7b094ce322a23a98dddea87b3eb602374c49b8e868994749e'
COMMIT = '267c7ced4993aa88613f8f17a8099943fec98159'


def validate_reference(jar, database, receipt_path, manifest, project):
    jar,database,receipt_path,manifest,project=map(pathlib.Path,(jar,database,receipt_path,manifest,project))
    report=json.loads(receipt_path.read_text())
    if digest(jar)!=REFERENCE_SHA:raise ValueError('REFERENCE_JAR_DRIFT')
    if (report.get('kind')!='frozen_anatomist_database_generation' or report.get('state')!='finished'
        or report.get('exit_code')!=0 or report.get('changed_inputs')!=[]
        or report.get('commit')!=COMMIT or report.get('scopes')!=['MAIN','TEST']
        or report.get('xml') is not True or report.get('lombok')!='ast'):
        raise ValueError('REFERENCE_GENERATION_PROOF_REQUIRED')
    if report.get('reference_jar_sha256')!=digest(jar) or report.get('database_sha256')!=digest(database):
        raise ValueError('REFERENCE_ARTIFACT_DRIFT')
    if report.get('inputs_manifest_sha256')!=digest(manifest):raise ValueError('REFERENCE_MANIFEST_DRIFT')
    if report.get('maven_inputs_verified') is not True or not report.get('maven_proofs'):
        raise ValueError('REFERENCE_MAVEN_PROOF_REQUIRED')
    inputs=report.get('source_sha256',{})
    if not inputs:raise ValueError('REFERENCE_SOURCE_PROOF_REQUIRED')
    for relative,checksum in inputs.items():
        path=(project/relative).resolve()
        if not path.is_relative_to(project.resolve()) or digest(path)!=checksum:
            raise ValueError('REFERENCE_SOURCE_DRIFT: '+relative)
    excluded=report.get('excluded_invalid_utf8',{})
    for relative,checksum in excluded.items():
        if inputs.get(relative)!=checksum:raise ValueError('REFERENCE_INVALID_SOURCE_PROOF')
        try:(project/relative).read_bytes().decode('utf-8')
        except UnicodeDecodeError:continue
        raise ValueError('REFERENCE_EXCLUSION_NOT_INVALID_UTF8')
    for group in ('inputs','maven_proofs'):
        for path,checksum in report.get(group,{}).items():
            if not pathlib.Path(path).is_file() or digest(pathlib.Path(path))!=checksum:
                raise ValueError('REFERENCE_GENERATION_INPUT_DRIFT: '+path)
    configured=set()
    model=json.loads(manifest.read_text())
    for module in model['modules']:
        for root in module['source_roots']:
            base=(project/module.get('root',module['id'])/root['path']).resolve()
            if not base.is_relative_to(project.resolve()):raise ValueError('REFERENCE_SOURCE_ROOT_ESCAPE')
            configured.update(str(p.relative_to(project.resolve())) for p in base.rglob('*.java') if p.is_file())
    configured_identities=set()
    for relative in configured:
        if relative in inputs:configured_identities.add(relative);continue
        candidates=[p for p in inputs if p.casefold()==relative.casefold()]
        if len(candidates)!=1 or not (project/relative).samefile(project/candidates[0]):
            raise ValueError('REFERENCE_CONFIGURED_SOURCE_NOT_COMMITTED: '+relative)
        configured_identities.add(candidates[0])
    with closing(sqlite3.connect(database.resolve().as_uri()+'?mode=ro',uri=True)) as db:
        captured=dict(db.execute("SELECT source_file,hash FROM file_cache WHERE provider_id='java-core' AND source_file LIKE '%.java'"))
        expected={p:h for p,h in inputs.items() if p in configured_identities and p not in excluded}
        aliases={};normalized={}
        for relative,checksum in captured.items():
            identity=relative
            if relative not in expected:
                candidates=[p for p in expected if p.casefold()==relative.casefold()]
                if len(candidates)!=1 or not (project/relative).samefile(project/candidates[0]):
                    raise ValueError('REFERENCE_DATABASE_SOURCE_COVERAGE')
                identity=candidates[0];aliases[relative]=identity
            if identity in normalized:raise ValueError('REFERENCE_DATABASE_SOURCE_ALIAS_COLLISION')
            normalized[identity]=checksum
        if normalized!=expected:raise ValueError('REFERENCE_DATABASE_SOURCE_COVERAGE')
    report['verified_source_coverage']=dict(configured_java_files=len(configured),analyzed_java_files=len(expected),
        filesystem_identity_aliases=aliases)
    return report
