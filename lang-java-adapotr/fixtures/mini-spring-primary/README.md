# Static @Primary binding fixture

The local annotation declarations are parser fixtures, not a Spring runtime.
`SpringPrimaryBindingIT` copies this project and enables Spring XML before indexing.

| Injection | Expected result |
|---|---|
| `api`, constructor `Api` | `preferred`, with both eligible registration IDs retained |
| `qualified` | `ordinary`: qualifier filtering precedes Primary |
| `missing`, `incompatible` | unresolved, no fallback to Primary |
| `factory` | `primaryFactory`: method-level Primary |
| `fake` | ambiguous: `p.Primary` is not Spring Primary |
| `xml` | ambiguous: class-level Primary does not apply to XML registrations |
| `all` | unsupported collection matching, never reduced to a primary singleton |
| XML `ref="ordinary"` | explicit reference unaffected by Primary |

Tests also add/remove Primary annotations and compare incremental with full facts.
All matches retain unknown runtime conditions and container visibility.
