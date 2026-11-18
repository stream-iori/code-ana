"""Build an isolated ABI proxy SDK; never modify an installed or original SDK."""
import hashlib
import json
import pathlib
import shutil
import subprocess
from product_support import ROOT


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fault_sdk(source, destination, home, mode):
    assert mode in {'crash','fatal','hang','truncate','append','corrupt','observe','rss','scratch','ack'}
    source=pathlib.Path(source).resolve()
    shutil.copytree(source,destination)
    library=destination/'libcodeana_typescript.dylib'
    engine=destination/'engine.dylib'
    library.rename(engine)
    # C string quoting only, passed as argv; no shell evaluation.
    command=['clang','-dynamiclib','-O0','-Wall','-Wextra','-Werror',
             '-I',str(destination),'-DFAULT_MODE='+json.dumps(mode),
             '-DENGINE_LIBRARY='+json.dumps(str(engine)),
             '-DGRAPH_PATTERN='+json.dumps(str(home/'analysis-index-*'/'u*'/'engine'/'graph.rows')),
             '-DBARRIER_PATH='+json.dumps(str(destination/'entered')),
             '-DSCRATCH_PATTERN='+json.dumps(str(home/'analysis-index-*'/'u*'/'engine')),
             str(ROOT/'tests/fixtures/typescript/native-fault.c'),'-o',str(library)]
    result=subprocess.run(command,capture_output=True,text=True,timeout=30)
    if result.returncode:
        raise AssertionError(result.stdout+result.stderr)
    manifest=destination/'manifest.json'
    data=json.loads(manifest.read_text())
    data['files']['engine.dylib']=sha(engine)
    data['files'][library.name]=sha(library)
    manifest.write_text(json.dumps(data,indent=2)+'\n')
    return {'mode':mode,'compiler_command':command,'proxy_sha256':sha(library),'engine_sha256':sha(engine)}
