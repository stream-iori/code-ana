#!/usr/bin/env python3
"""Compare the generated Graal C header with the actual Rust FFI struct."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime-root', type=pathlib.Path, required=True)
    parser.add_argument('--evidence', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = args.runtime_root.resolve()
    headers = root / 'lang-java-adaptor/target/native'
    source = root / 'crates/java-worker/src/native.rs'
    structure = re.search(r'#\[repr\(C\)\]\s*struct IsolateParams \{[\s\S]*?\n\}', source.read_text())
    if structure is None:
        raise ValueError('RUST_ISOLATE_LAYOUT_MISSING')
    fields = re.findall(r'^    (\w+):', structure[0], re.M)
    # Rust member names do not affect repr(C). Graal names its two reserved
    # bytes with an underscore; compare their actual offsets explicitly.
    c_fields = {name: {'reserved_4': '_reserved_4', 'reserved_5': '_reserved_5'}.get(name, name)
                for name in fields}
    if not fields or not (headers / 'graal_isolate.h').is_file():
        raise ValueError('GENERATED_GRAAL_HEADER_REQUIRED')
    args.evidence.mkdir(parents=True, exist_ok=False)
    c = '#include <stddef.h>\n#include <stdio.h>\n#include "graal_isolate.h"\nint main(void) {\n'
    c += 'printf("%zu %zu", sizeof(graal_create_isolate_params_t), _Alignof(graal_create_isolate_params_t));\n'
    c += ''.join('printf(" %zu", offsetof(graal_create_isolate_params_t, ' + c_fields[name] + '));\n' for name in fields)
    c += 'puts(""); return 0; }\n'
    rust = structure[0] + '\nfn main() {\n'
    rust += 'print!("{} {}", std::mem::size_of::<IsolateParams>(), std::mem::align_of::<IsolateParams>());\n'
    rust += ''.join('print!(" {}", std::mem::offset_of!(IsolateParams, ' + name + '));\n' for name in fields)
    rust += 'println!(); }\n'
    (args.evidence / 'layout.c').write_text(c)
    (args.evidence / 'layout.rs').write_text(rust)
    values = {}
    for language, command in [('c', ['cc', '-std=c11', '-I', str(headers)]),
                              ('rs', ['rustc', '--edition=2024'])]:
        path = args.evidence / ('layout.' + language)
        executable = args.evidence / ('layout-' + language)
        command += [str(path), '-o', str(executable)]
        with (args.evidence / ('compile-' + language + '.log')).open('wb') as log:
            subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=60)
        values[language] = list(map(int, subprocess.check_output([str(executable)], timeout=10).split()))
    report = dict(status='passed' if values['c'] == values['rs'] else 'failed',
                  fields=['size', 'alignment', *fields], c_field_names=c_fields, layouts=values,
                  native_loaded=False, native_accepted=False,
                  inputs={str(p.resolve()): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in [source, pathlib.Path(__file__), *sorted(headers.glob('*.h'))]})
    (args.evidence / 'receipt.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report['layouts']))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
