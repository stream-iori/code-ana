# Index report task_809fdfea636044759da36f6ecf8051fc

| Execution | Quality | Gate | Publication | Revision |
|---|---|---|---|---|
| completed | partial | passed | published | rev_4a3399913dae49abafcea4a28c2445c3 |

Physical files: 2622; semantic files: 615; analysis instances: 615; facts: 438667.

| Entry | Language | Input mode | Reason | Preparation log |
|---|---|---|---|---|
| python | python | Semantic | none | .code-ana/inputs/generation_64c26fc06c5b4dfca3399de3ae495d04/python/prepare.log |

Input mode does not certify complete semantics.

Diagnostics: 4019 occurrences; 20 additional groups omitted.

| Diagnostic group | Occurrences |
|---|---:|
| bad-argument-type | 338 |
| bad-assignment | 298 |
| bad-function-definition | 1211 |
| bad-index | 323 |
| bad-override | 22 |
| bad-override-param-name | 19 |
| bad-return | 35 |
| bad-typed-dict-key | 24 |
| deprecated | 13 |
| missing-attribute | 738 |
| missing-import | 47 |
| no-matching-overload | 47 |
| not-iterable | 64 |
| redundant-condition | 9 |
| unbound-name | 47 |
| unexpected-keyword | 82 |
| unknown-name | 14 |
| unnecessary-type-conversion | 29 |
| unreachable-except-clause | 27 |
| unsupported-operation | 572 |

Coverage: coverage:partial_files:585

- doctor: inspect configuration and input readiness
- inspect task events and referenced preparation logs; after repairing inputs, run index --prepare
