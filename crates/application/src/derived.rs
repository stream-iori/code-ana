//! Static DI candidates derived from public facts, after all adapter owners
//! have been replaced. No Java type inference or runtime bean selection.
use anyhow::{Result, ensure};
use codeana_model::{Fact, FactKind, Manifest, Owner, digest, key};
use codeana_storage::{CandidateDerivation, CandidateFacts};
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};

pub(crate) const PROFILE: &str = "derived-wiring-1";
const PRODUCER: &str = "rust.derived.wiring";
const AGGREGATE: &str = "static-injection-candidates";
pub(crate) struct Wiring;

pub(crate) fn owners(manifest: &Manifest) -> Vec<Owner> {
    manifest
        .files
        .iter()
        .map(|s| &s.world)
        .collect::<BTreeSet<_>>()
        .into_iter()
        .map(|world| owner(world))
        .collect()
}

fn owner(world: &str) -> Owner {
    Owner {
        world: world.into(),
        producer: PRODUCER.into(),
        kind: "aggregate".into(),
        id: AGGREGATE.into(),
    }
}

fn edge_key(fact: &Fact) -> String {
    digest(&(
        &fact.owner.world,
        &fact.parent,
        &fact.target,
        &fact.source,
        &fact.span,
    ))
}

impl CandidateDerivation for Wiring {
    fn producer(&self) -> &str {
        PRODUCER
    }
    fn aggregate_id(&self) -> &str {
        AGGREGATE
    }

    fn derive(&self, facts: &CandidateFacts<'_>) -> Result<()> {
        // Like the frozen resolver, only direct, internal implementations
        // participate. Inheritance and framework qualifiers do not select beans.
        let mut implementations: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
        for edge in facts.relations("implements")? {
            let (Some(source), Some(target)) =
                (facts.fact(&edge.parent)?, facts.fact(&edge.target)?)
            else {
                continue; // Whole-candidate validation reports dangling inputs.
            };
            if source.kind == FactKind::Declaration && target.kind == FactKind::Declaration {
                implementations
                    .entry(edge.target)
                    .or_default()
                    .insert(edge.parent);
            }
        }
        let mut seen: BTreeSet<_> = facts
            .relations("wires_possible")?
            .iter()
            .map(edge_key)
            .collect();
        for injection in facts.relations("injects_possible")? {
            let Some(registration) = facts.fact(&injection.parent)? else {
                continue;
            };
            if registration.kind != FactKind::Facet
                || registration.relation != "java.spring.injection"
            {
                continue;
            }
            let Some(point) = facts.fact(&registration.parent)? else {
                continue;
            };
            let Some(interface) = facts.fact(&injection.target)? else {
                continue;
            };
            if point.kind != FactKind::Declaration || interface.kind != FactKind::Declaration {
                continue;
            }
            ensure!(
                point.owner.world == injection.owner.world && point.source == injection.source,
                "INJECTION_EVIDENCE_IDENTITY_MISMATCH"
            );
            let Some(candidates) = implementations.get(&injection.target) else {
                continue;
            };
            let candidates: Vec<_> = candidates.iter().cloned().collect();
            for target in &candidates {
                let mut wire = Fact {
                    id: String::new(),
                    kind: FactKind::Relation,
                    owner: owner(&injection.owner.world),
                    source: injection.source.clone(),
                    span: injection.span.clone(),
                    name: String::new(),
                    signature: String::new(),
                    parent: point.id.clone(),
                    target: target.clone(),
                    relation: "wires_possible".into(),
                    status: "possible".into(),
                    origin: PROFILE.into(),
                    evidence_complete: false,
                    declaration_resolved: false,
                    attributes: BTreeMap::from([
                        (
                            "confidence".into(),
                            json!(if candidates.len() == 1 {
                                "inferred"
                            } else {
                                "ambiguous"
                            }),
                        ),
                        ("via".into(), json!("injection")),
                        ("source".into(), json!(injection.target)),
                        ("candidates".into(), json!(candidates)),
                        ("injection_evidence".into(), json!(injection.id)),
                        ("runtime_confirmed".into(), json!(false)),
                    ]),
                };
                let identity = edge_key(&wire);
                if !seen.insert(identity.clone()) {
                    continue;
                }
                wire.id = key("derived_wiring", &[PROFILE, &digest(&wire)]);
                facts.insert(&wire)?;
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::archive::{FactArchive, FactArchiveBuilder};
    use codeana_model::{AnalysisResult, FactFilter, FactReader, Revision, SourceUnit, Span, hash};
    use codeana_storage::Store;

    fn fact(id: &str, kind: FactKind, parent: &str, target: &str, relation: &str) -> Fact {
        Fact {
            id: id.into(),
            kind,
            owner: Owner {
                world: "main".into(),
                producer: "adapter".into(),
                kind: "source".into(),
                id: if id.starts_with('b') {
                    "B.java"
                } else {
                    "Use.java"
                }
                .into(),
            },
            source: if id.starts_with('b') {
                "B.java"
            } else {
                "Use.java"
            }
            .into(),
            span: Some(Span { start: 1, end: 5 }),
            name: id.into(),
            signature: id.into(),
            parent: parent.into(),
            target: target.into(),
            relation: relation.into(),
            status: "resolved".into(),
            origin: "adapter".into(),
            evidence_complete: true,
            declaration_resolved: true,
            attributes: if kind == FactKind::Facet {
                BTreeMap::from([("schema_version".into(), json!(1))])
            } else {
                BTreeMap::new()
            },
        }
    }

    fn fixture() -> (Revision, AnalysisResult) {
        let manifest = Manifest {
            files: ["Use.java", "B.java"]
                .into_iter()
                .map(|path| SourceUnit {
                    path: path.into(),
                    hash: hash(path),
                    size: 100,
                    module: "app".into(),
                    world: "main".into(),
                    scope: "MAIN".into(),
                    language: "fixture".into(),
                    auxiliary: false,
                })
                .collect(),
        };
        let revision = Revision {
            id: String::new(),
            target: "t".into(),
            project: "p".into(),
            checkout: "c".into(),
            manifest: manifest.clone(),
            profile: PROFILE.into(),
            producer: "adapter".into(),
            created: 0,
        };
        let mut facts = vec![
            fact("interface", FactKind::Declaration, "", "", ""),
            fact("a", FactKind::Declaration, "", "", ""),
            fact("b", FactKind::Declaration, "", "", ""),
            fact("child", FactKind::Declaration, "", "", ""),
            fact("external", FactKind::ExternalSymbol, "", "", ""),
            fact("field", FactKind::Declaration, "", "", ""),
            fact(
                "registration",
                FactKind::Facet,
                "field",
                "",
                "java.spring.injection",
            ),
            fact(
                "a-implements",
                FactKind::Relation,
                "a",
                "interface",
                "implements",
            ),
            fact(
                "a-duplicate",
                FactKind::Relation,
                "a",
                "interface",
                "implements",
            ),
            fact(
                "b-implements",
                FactKind::Relation,
                "b",
                "interface",
                "implements",
            ),
            fact("child-extends", FactKind::Relation, "child", "a", "extends"),
            fact(
                "external-implements",
                FactKind::Relation,
                "external",
                "interface",
                "implements",
            ),
            fact(
                "injection",
                FactKind::Relation,
                "registration",
                "interface",
                "injects_possible",
            ),
        ];
        facts
            .iter_mut()
            .find(|f| f.id == "registration")
            .unwrap()
            .attributes
            .insert("qualifier".into(), json!("preferred"));
        let result = AnalysisResult {
            mode: "full".into(),
            baseline: String::new(),
            manifest: manifest.id(),
            profile: PROFILE.into(),
            producer: "adapter".into(),
            replacements: vec![],
            facts,
        };
        (revision, result)
    }

    fn archive(facts: &[Fact]) -> FactArchive {
        let mut out = FactArchiveBuilder::new(1024 * 1024, 1000).unwrap();
        for f in facts {
            out.push(f).unwrap();
        }
        out.seal().unwrap()
    }

    fn publish(
        store: &Store,
        revision: Revision,
        result: &AnalysisResult,
        expected: &str,
    ) -> Revision {
        store
            .put_blobs(
                &["Use.java", "B.java"]
                    .into_iter()
                    .map(|path| (hash(path), path.as_bytes().to_vec()))
                    .collect(),
            )
            .unwrap();
        let candidate = store
            .stage_archive_with_derivation(
                revision,
                &result.header(),
                &archive(&result.facts),
                expected,
                || Ok(()),
                |_| Ok(()),
                Some(&Wiring),
            )
            .unwrap();
        store
            .publish_staged(candidate, &codeana_model::new_id("fixture"))
            .unwrap()
    }

    fn wires(store: &Store, revision: &str) -> Vec<Fact> {
        store
            .read(revision)
            .unwrap()
            .select(&FactFilter {
                relation: Some("wires_possible".into()),
                ..Default::default()
            })
            .unwrap()
    }

    #[test]
    fn direct_candidates_keep_ambiguity_and_do_not_select_by_qualifier_or_inheritance() {
        let root = tempfile::tempdir().unwrap();
        let store = Store::open(root.path()).unwrap();
        let (revision, result) = fixture();
        let published = publish(&store, revision, &result, "");
        let output = wires(&store, &published.id);
        assert_eq!(output.len(), 2);
        assert_eq!(
            output
                .iter()
                .map(|f| f.target.as_str())
                .collect::<BTreeSet<_>>(),
            BTreeSet::from(["a", "b"])
        );
        for f in &output {
            assert_eq!(f.parent, "field");
            assert_eq!(f.span, Some(Span { start: 1, end: 5 }));
            assert_eq!(f.status, "possible");
            assert!(!f.declaration_resolved && !f.evidence_complete);
            assert_eq!(f.attributes["confidence"], "ambiguous");
            assert_eq!(f.attributes["candidates"], json!(["a", "b"]));
            assert_eq!(f.attributes["source"], "interface");
            assert_eq!(f.attributes["via"], "injection");
        }
        let reader = store.read(&published.id).unwrap();
        let query = codeana_query::run(
            &reader,
            &codeana_model::Request {
                operation: "type-relations".into(),
                selector: "field".into(),
                relation: "wires_possible".into(),
                max_records: 100,
                max_output_bytes: 1024 * 1024,
                ..Default::default()
            },
            &|_| unreachable!(),
        )
        .unwrap();
        assert_eq!(query.records.len(), 2);
        assert!(!query.evidence.semantic);
        assert!(
            query
                .evidence
                .reasons
                .iter()
                .any(|r| r == "static_injection_candidates_open_world")
        );
        let exported = reader
            .export_adapter_archive_checked_in(
                1024 * 1024,
                1000,
                None,
                &owners(&reader.revision().manifest),
                || Ok(()),
            )
            .unwrap();
        assert_eq!(exported.count as usize, result.facts.len());
        assert!(
            exported
                .iter()
                .unwrap()
                .all(|f| f.unwrap().owner.producer != PRODUCER)
        );
        assert_eq!(
            reader.export_archive(1024 * 1024, 1000).unwrap().count as usize,
            result.facts.len() + 2
        );
    }

    #[test]
    fn implementation_removal_and_deletion_rebuild_unmodified_injections_equal_to_full() {
        let root = tempfile::tempdir().unwrap();
        let store = Store::open(root.path()).unwrap();
        let (revision, first) = fixture();
        let baseline = publish(&store, revision.clone(), &first, "");
        let mut changed = first.clone();
        changed.facts.retain(|f| f.id != "b-implements");
        let mut delta = changed.clone();
        delta.mode = "delta".into();
        delta.baseline = baseline.id.clone();
        delta.replacements = vec![fact("b", FactKind::Declaration, "", "", "").owner];
        delta.facts.retain(|f| f.id == "b");
        let updated = publish(&store, revision.clone(), &delta, &baseline.id);
        let actual = wires(&store, &updated.id);
        assert_eq!(actual.len(), 1);
        assert_eq!(actual[0].target, "a");
        assert_eq!(actual[0].attributes["confidence"], "inferred");
        assert_eq!(actual[0].attributes["candidates"], json!(["a"]));
        let full = publish(&store, revision.clone(), &changed, &updated.id);
        assert_eq!(actual, wires(&store, &full.id));
        // Removing the injection's source clears all derived owners before
        // changed-source checks; no stale target or dangling evidence remains.
        let mut removed = changed.clone();
        removed.mode = "delta".into();
        removed.baseline = full.id.clone();
        removed.replacements = vec![fact("field", FactKind::Declaration, "", "", "").owner];
        removed.facts.clear();
        let mut deleted_revision = revision;
        deleted_revision
            .manifest
            .files
            .retain(|s| s.path == "B.java");
        removed.manifest = deleted_revision.manifest.id();
        let deleted = publish(&store, deleted_revision, &removed, &full.id);
        assert!(wires(&store, &deleted.id).is_empty());
        assert_eq!(wires(&store, &baseline.id).len(), 2);
    }

    #[test]
    fn invalid_derivation_and_reserved_producer_cannot_replace_the_head() {
        let root = tempfile::tempdir().unwrap();
        let store = Store::open(root.path()).unwrap();
        let (revision, result) = fixture();
        let before = publish(&store, revision.clone(), &result, "");
        let mut invalid = result.clone();
        invalid
            .facts
            .iter_mut()
            .find(|f| f.id == "injection")
            .unwrap()
            .source = "B.java".into();
        let error = store
            .stage_archive_with_derivation(
                revision.clone(),
                &invalid.header(),
                &archive(&invalid.facts),
                &before.id,
                || Ok(()),
                |_| Ok(()),
                Some(&Wiring),
            )
            .err()
            .unwrap();
        assert!(
            error
                .to_string()
                .contains("INJECTION_EVIDENCE_IDENTITY_MISMATCH")
        );
        invalid = result;
        invalid.facts[0].owner.producer = PRODUCER.into();
        let error = store
            .stage_archive_with_derivation(
                revision,
                &invalid.header(),
                &archive(&invalid.facts),
                &before.id,
                || Ok(()),
                |_| Ok(()),
                Some(&Wiring),
            )
            .err()
            .unwrap();
        assert!(error.to_string().contains("DERIVED_PRODUCER_RESERVED"));
        assert_eq!(store.head("t").unwrap(), before.id);
        assert_eq!(wires(&store, &before.id).len(), 2);
    }
}
