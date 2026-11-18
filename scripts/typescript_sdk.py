"""Verified, immutable-version TypeScript SDK installation shared by packaging."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import uuid
from sdk_manifest import load_manifest


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream,'sha256').hexdigest()


def verify(sdk,worker):
    sdk=Path(sdk).resolve(strict=True)
    manifest=load_manifest(sdk/'manifest.json')
    if manifest['backend']['kind']!='typescript':raise ValueError('SDK_BACKEND_MISMATCH')
    total=0
    for name,digest in manifest['files'].items():
        path=sdk/name
        if not path.resolve().is_relative_to(sdk) or not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError('SDK_INSTALL_FILE_TYPE: '+name)
        total+=path.stat().st_size
        if total>1 << 30:raise ValueError('SDK_INSTALL_SIZE')
        if sha(path)!=digest:raise ValueError('SDK_INSTALL_HASH_MISMATCH: '+name)
    command=[str(Path(worker).resolve()),'--sdk',str(sdk),'--runtime-info']
    probe=subprocess.run(command,capture_output=True,text=True,timeout=30)
    if probe.returncode:raise ValueError('SDK_INSTALL_RUNTIME: '+probe.stderr)
    runtime=json.loads(probe.stdout)
    identity=manifest['backend']
    expected={'abi':identity['abi'],'analysis_request':identity['analysis_request'],
              'worker_protocol':manifest['worker_protocol'],'graph_model':manifest['fact_ir'],
              'adapter':manifest['adapter'],'upstream_commit':identity['upstream_commit'],
              'typescript_commit':identity['typescript_commit']}
    if any(runtime.get(k)!=v for k,v in expected.items()):raise ValueError('SDK_INSTALL_RUNTIME_IDENTITY')
    return manifest,runtime


def install(sdk,prefix,worker):
    sdk=Path(sdk).resolve(strict=True)
    manifest,runtime=verify(sdk,worker)
    prefix=Path(prefix).absolute()
    root=prefix/'lib/typescript'
    versions=root/'versions';versions.mkdir(parents=True,exist_ok=True)
    version=sha(sdk/'manifest.json')
    destination=versions/version
    if destination.exists():
        installed,_=verify(destination,worker)
        if installed!=manifest:raise ValueError('SDK_INSTALL_VERSION_CONFLICT')
    else:
        with tempfile.TemporaryDirectory(prefix='.install-',dir=root) as temporary:
            staged=Path(temporary)/'sdk';staged.mkdir()
            for name in [*manifest['files'],'manifest.json']:
                target=staged/name;target.parent.mkdir(parents=True,exist_ok=True)
                shutil.copy2(sdk/name,target)
            verify(staged,worker)
            staged.rename(destination)
    current=root/'current'
    if current.exists() and not current.is_symlink():raise ValueError('SDK_INSTALL_CURRENT_MUST_BE_SYMLINK')
    temporary=root/('.current-'+uuid.uuid4().hex)
    try:
        temporary.symlink_to(Path('versions')/version,target_is_directory=True)
        os.replace(temporary,current)
    finally:temporary.unlink(missing_ok=True)
    return {'status':'installed','version':version,'sdk':str(destination),'current':str(current),'runtime':runtime}
