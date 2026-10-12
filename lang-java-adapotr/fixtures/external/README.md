# External fixtures (L3 smoke tests)

This directory holds **vendored** open-source projects we use as larger-scale
smoke targets for anatomist. They are added as git submodules so the repo
itself stays small and tests pin a specific revision.

## Apache Commons Lang 3.12.0 (JDK 8)

Used by `CommonsLangSmokeIT` to validate that anatomist can index a real-world
dependency-free JDK 8 library end-to-end without crashing, and to exercise the
indexer at scale (hundreds of types, thousands of methods).

### Setup after cloning this repository

```bash
# Fetch the exact revision already pinned by this repository.
git submodule update --init --depth 1 -- fixtures/external/commons-lang

# Verify that the checkout matches the parent repository's gitlink.
git submodule status -- fixtures/external/commons-lang
```

The fixture is already registered in `.gitmodules`; do not add it again or choose
a nearby tag. The pinned commit is `105a350b154eac7c3f3a6bac94ec2cfb0fbe232b`.
Initialization needs network access once; subsequent test runs use the local tree.

### Fresh checkout

```bash
git submodule update --init --recursive
```

## Running external-fixture tests

External tests carry the JUnit tag `external` (set via `@Tag("external")`).
They run by default whenever you invoke them explicitly:

```bash
mvn test -Dtest=CommonsLangSmokeIT
```

If the submodule directory is missing, each test self-skips via
`assumeTrue(...)` rather than failing, and the Surefire report shows
`Tests run: N, Skipped: N` — visible enough to notice in CI.
