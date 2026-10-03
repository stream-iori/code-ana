//! Static DI candidates derived from public facts, after all adapter owners
//! have been replaced. No Java type inference or runtime bean selection.
use anyhow::{Result, ensure};
use codeana_model::{Fact, FactKind, Manifest, Owner, digest, key};
use codeana_storage::{CandidateDerivation, CandidateFacts};
use serde_json::json;
use std::collections::{BTreeMap, BTreeSet};

pub(crate) const PROFILE: &str = "derived-wiring-4";
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

/// An adapter may represent an upstream captured source as external within
/// its compiler world. Only exact captured provenance can join that alias to
/// an actually published declaration; names alone never establish identity.
fn internal(facts: &CandidateFacts<'_>, fact: Fact) -> Result<Option<Fact>> {
    if fact.kind == FactKind::Declaration {
        return Ok(Some(fact));
    }
    if fact.kind != FactKind::ExternalSymbol {
        return Ok(None);
    }
    let Some(origin) = fact
        .attributes
        .get("source_origin")
        .and_then(|a| a.as_object())
    else {
        return Ok(None);
    };
    let (Some(path), Some(hash), Some(world), Some(module), Some(scope)) = (
        origin.get("path").and_then(|a| a.as_str()),
        origin.get("hash").and_then(|a| a.as_str()),
        origin.get("world").and_then(|a| a.as_str()),
        origin.get("module").and_then(|a| a.as_str()),
        origin.get("scope").and_then(|a| a.as_str()),
    ) else {
        return Ok(None);
    };
    let Some(unit) = facts.source_unit(path) else {
        return Ok(None);
    };
    if unit.auxiliary
        || unit.hash != hash
        || unit.world != world
        || unit.module != module
        || unit.scope != scope
    {
        return Ok(None);
    }
    let found = facts.declarations(path, world, &fact.signature)?;
    Ok((found.len() == 1).then(|| found.into_iter().next().unwrap()))
}

fn class(fact: &Fact) -> bool {
    matches!(
        fact.attributes.get("element_kind").and_then(|a| a.as_str()),
        Some("CLASS" | "INTERFACE" | "ENUM" | "ANNOTATION_TYPE")
    )
}

fn declaring_type(facts: &CandidateFacts<'_>, point: &Fact) -> Result<Option<Fact>> {
    let mut current = point.clone();
    let mut visited = BTreeSet::new();
    for _ in 0..256 {
        ensure!(visited.insert(current.id.clone()), "INJECTION_PARENT_CYCLE");
        ensure!(
            current.kind == FactKind::Declaration
                && current.source == point.source
                && current.owner.world == point.owner.world,
            "INJECTION_PARENT_IDENTITY_MISMATCH"
        );
        if class(&current) {
            return Ok(Some(current));
        }
        if current.parent.is_empty() {
            return Ok(None);
        }
        let Some(parent) = facts.fact(&current.parent)? else {
            return Ok(None);
        };
        current = parent;
    }
    anyhow::bail!("INJECTION_PARENT_BUDGET")
}

fn bean_type(facts: &CandidateFacts<'_>, bean: &Fact) -> Result<Option<Fact>> {
    let configured = facts.references("config_type", Some(&bean.id))?;
    if configured.len() == 1 {
        return facts.fact(&configured[0].target);
    }
    let Some(parent) = facts.fact(&bean.parent)? else {
        return Ok(None);
    };
    if class(&parent) {
        return Ok(Some(parent));
    }
    // A method-backed annotation Bean supplies a Java-confirmed return-type
    // reference. Names alone never join it to a class in another world.
    let mut targets = BTreeMap::new();
    for edge in facts.references("type_reference", Some(&parent.id))? {
        if edge.attributes.get("type_context").and_then(|a| a.as_str()) != Some("return_type") {
            continue;
        }
        if let Some(target) = facts.fact(&edge.target)?
            && class(&target)
            && bean.attributes.get("type").and_then(|a| a.as_str())
                == Some(target.signature.as_str())
        {
            targets.insert(target.id.clone(), target);
        }
    }
    Ok((targets.len() == 1).then(|| targets.into_values().next().unwrap()))
}

fn xml_wires(facts: &CandidateFacts<'_>) -> Result<()> {
    for reference in facts.references("config", None)? {
        let Some(site) = facts.fact(&reference.parent)? else {
            continue;
        };
        if site.relation != "java.spring.xml"
            || !matches!(
                site.attributes.get("kind").and_then(|a| a.as_str()),
                Some("bean_ref" | "idref" | "map_value_ref")
            )
        {
            continue;
        }
        ensure!(
            site.source == reference.source && site.owner.world == reference.owner.world,
            "XML_CONFIGURATION_EVIDENCE_IDENTITY_MISMATCH"
        );
        let mut current = Some(site.clone());
        let mut visited = BTreeSet::new();
        let mut bean = None;
        while let Some(node) = current {
            ensure!(
                visited.insert(node.id.clone()) && visited.len() <= 256,
                "XML_CONFIGURATION_OWNER_CYCLE_OR_BUDGET"
            );
            if node.relation == "java.bean" {
                bean = Some(node);
                break;
            }
            current = facts.fact(&node.parent)?;
        }
        let (Some(bean), Some(target_bean)) = (bean, facts.fact(&reference.target)?) else {
            continue;
        };
        if target_bean.relation != "java.bean" {
            continue;
        }
        let configured = facts.references("config_owner", Some(&bean.id))?;
        let source = if configured.len() == 1 {
            facts.fact(&configured[0].target)?
        } else {
            None
        };
        let (Some(source), Some(target)) = (source, bean_type(facts, &target_bean)?) else {
            continue;
        };
        let Some(source) = internal(facts, source)? else {
            continue;
        };
        let target = internal(facts, target.clone())?.unwrap_or(target);
        let mut wire = Fact {
            id: String::new(),
            kind: FactKind::Relation,
            owner: owner(&reference.owner.world),
            source: reference.source.clone(),
            span: reference.span.clone(),
            name: String::new(),
            signature: String::new(),
            parent: source.id,
            target: target.id,
            relation: "wires_possible".into(),
            status: "possible".into(),
            origin: PROFILE.into(),
            evidence_complete: false,
            declaration_resolved: false,
            attributes: BTreeMap::from([
                ("via".into(), json!("xml_configuration")),
                (
                    "confidence".into(),
                    json!(if site
                        .attributes
                        .get("candidate_count")
                        .and_then(|a| a.as_u64())
                        == Some(1)
                    {
                        "configured"
                    } else {
                        "ambiguous"
                    }),
                ),
                ("configuration_evidence".into(), json!(reference.id)),
                ("source_bean".into(), json!(bean.id)),
                ("target_bean".into(), json!(target_bean.id)),
                (
                    "position".into(),
                    site.attributes
                        .get("position")
                        .cloned()
                        .unwrap_or(json!(null)),
                ),
                ("runtime_confirmed".into(), json!(false)),
            ]),
        };
        // Each captured reference is distinct evidence, including duplicates.
        wire.id = key("derived_xml_wiring", &[PROFILE, &digest(&wire)]);
        facts.insert(&wire)?;
    }
    Ok(())
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
            if let (Some(source), Some(target)) =
                (internal(facts, source)?, internal(facts, target)?)
            {
                implementations
                    .entry(target.id)
                    .or_default()
                    .insert(source.id);
            }
        }
        let mut seen: BTreeSet<_> = facts
            .relations("wires_possible")?
            .iter()
            .map(edge_key)
            .collect();
        let mut typed = BTreeMap::<String, Fact>::new();
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
            let Some(interface) = internal(facts, interface)? else {
                continue;
            };
            if point.kind != FactKind::Declaration {
                continue;
            }
            ensure!(
                point.owner.world == injection.owner.world && point.source == injection.source,
                "INJECTION_EVIDENCE_IDENTITY_MISMATCH"
            );
            let Some(candidates) = implementations.get(&interface.id) else {
                continue;
            };
            let candidates: Vec<_> = candidates.iter().cloned().collect();
            let declaring = if let (Some(expected), Some(line)) = (
                registration
                    .attributes
                    .get("declaring_type")
                    .and_then(|v| v.as_str()),
                registration
                    .attributes
                    .get("source_line")
                    .and_then(|v| v.as_u64()),
            ) {
                ensure!(
                    line > 0 && line <= u32::MAX as u64,
                    "INJECTION_SOURCE_LINE_INVALID"
                );
                let actual = declaring_type(facts, &point)?;
                ensure!(
                    actual.as_ref().is_some_and(|f| f.id == expected),
                    "INJECTION_DECLARING_TYPE_MISMATCH"
                );
                actual.map(|f| (f, line))
            } else {
                None
            };
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
                        ("projection".into(), json!("injection_point")),
                        ("source".into(), json!(interface.id)),
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
                if let Some((declaring, line)) = &declaring {
                    // A type query needs the reference's declaring-type projection;
                    // keep all point facts and aggregate their actual evidence here.
                    let identity = digest(&(
                        &wire.owner.world,
                        &declaring.id,
                        target,
                        &wire.source,
                        line,
                        &interface.id,
                    ));
                    let projected = typed.entry(identity).or_insert_with(|| {
                        let mut projected = wire.clone();
                        projected.id.clear();
                        projected.parent = declaring.id.clone();
                        projected.span = None;
                        projected
                            .attributes
                            .insert("projection".into(), json!("declaring_type"));
                        projected
                            .attributes
                            .insert("source_line".into(), json!(line));
                        projected
                            .attributes
                            .insert("injection_points".into(), json!([]));
                        projected
                            .attributes
                            .insert("injection_evidences".into(), json!([]));
                        projected
                    });
                    for (name, value) in [
                        ("injection_points", &point.id),
                        ("injection_evidences", &injection.id),
                    ] {
                        let values = projected
                            .attributes
                            .get_mut(name)
                            .unwrap()
                            .as_array_mut()
                            .unwrap();
                        if !values.contains(&json!(value)) {
                            values.push(json!(value));
                        }
                    }
                }
            }
        }
        for mut wire in typed.into_values() {
            for name in ["injection_points", "injection_evidences"] {
                wire.attributes
                    .get_mut(name)
                    .unwrap()
                    .as_array_mut()
                    .unwrap()
                    .sort_by(|a, b| a.as_str().cmp(&b.as_str()));
            }
            // The primary evidence is stable regardless of SQLite traversal order.
            wire.attributes.insert(
                "injection_evidence".into(),
                wire.attributes["injection_evidences"][0].clone(),
            );
            wire.id = key("derived_wiring", &[PROFILE, &digest(&wire)]);
            facts.insert(&wire)?;
        }
        xml_wires(facts)
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
    fn xml_configuration_preserves_occurrences_and_removes_derived_wires_atomically() {
        let root = tempfile::tempdir().unwrap();
        let store = Store::open(root.path()).unwrap();
        let (mut revision, mut result) = fixture();
        let path = "beans.xml";
        store
            .put_blobs(&[(hash(path), path.as_bytes().to_vec())].into())
            .unwrap();
        revision.manifest.files.push(SourceUnit {
            path: path.into(),
            hash: hash(path),
            size: path.len() as u64,
            module: "app".into(),
            world: "main".into(),
            scope: "MAIN".into(),
            language: String::new(),
            auxiliary: false,
        });
        result.manifest = revision.manifest.id();
        for id in ["a", "b"] {
            result
                .facts
                .iter_mut()
                .find(|f| f.id == id)
                .unwrap()
                .attributes
                .insert("element_kind".into(), json!("CLASS"));
        }
        let xml_owner = Owner {
            world: "main".into(),
            producer: "spring-model-v1".into(),
            kind: "resource".into(),
            id: path.into(),
        };
        let mut xml = vec![
            fact("source-bean", FactKind::Facet, "a", "", "java.bean"),
            fact("target-bean", FactKind::Facet, "b", "", "java.bean"),
            fact(
                "ref-site",
                FactKind::Facet,
                "source-bean",
                "",
                "java.spring.xml",
            ),
            fact(
                "ref-one",
                FactKind::Reference,
                "ref-site",
                "target-bean",
                "config",
            ),
            fact(
                "ref-two",
                FactKind::Reference,
                "ref-site",
                "target-bean",
                "config",
            ),
            fact(
                "owner-class",
                FactKind::Reference,
                "source-bean",
                "a",
                "config_owner",
            ),
        ];
        xml[2].attributes.extend(BTreeMap::from([
            ("kind".into(), json!("bean_ref")),
            ("candidate_count".into(), json!(1)),
            ("position".into(), json!({"line":1,"column":2})),
        ]));
        for (index, node) in xml.iter_mut().enumerate() {
            node.source = path.into();
            node.owner = xml_owner.clone();
            node.span = Some(Span {
                start: index as u64,
                end: index as u64 + 1,
            });
        }
        result.facts.extend(xml);
        let first = publish(&store, revision.clone(), &result, "");
        let output: Vec<_> = wires(&store, &first.id)
            .into_iter()
            .filter(|f| f.attributes["via"] == "xml_configuration")
            .collect();
        assert_eq!(output.len(), 2);
        assert_eq!(
            output
                .iter()
                .map(|f| f.attributes["configuration_evidence"].as_str().unwrap())
                .collect::<BTreeSet<_>>(),
            BTreeSet::from(["ref-one", "ref-two"])
        );
        assert!(output.iter().all(|f| f.parent == "a"
            && f.target == "b"
            && f.source == path
            && f.status == "possible"
            && !f.evidence_complete
            && !f.declaration_resolved));
        let delta = AnalysisResult {
            mode: "delta".into(),
            baseline: first.id.clone(),
            manifest: revision.manifest.id(),
            profile: PROFILE.into(),
            producer: "adapter".into(),
            replacements: vec![xml_owner],
            facts: vec![],
        };
        let second = publish(&store, revision, &delta, &first.id);
        assert!(
            wires(&store, &second.id)
                .iter()
                .all(|f| f.attributes["via"] != "xml_configuration")
        );
        assert_eq!(wires(&store, &first.id).len(), 4);
    }

    #[test]
    fn type_projection_preserves_both_points_and_validates_declaring_ancestry() {
        let root = tempfile::tempdir().unwrap();
        let store = Store::open(root.path()).unwrap();
        let (revision, mut result) = fixture();
        let mut container = fact("container", FactKind::Declaration, "", "", "");
        container
            .attributes
            .insert("element_kind".into(), json!("CLASS"));
        result.facts.push(container);
        result
            .facts
            .iter_mut()
            .find(|f| f.id == "field")
            .unwrap()
            .parent = "container".into();
        for id in ["registration", "registration2"] {
            if id == "registration2" {
                result
                    .facts
                    .push(fact("field2", FactKind::Declaration, "container", "", ""));
                result.facts.push(fact(
                    id,
                    FactKind::Facet,
                    "field2",
                    "",
                    "java.spring.injection",
                ));
                result.facts.push(fact(
                    "injection2",
                    FactKind::Relation,
                    id,
                    "interface",
                    "injects_possible",
                ));
            }
            let registration = result.facts.iter_mut().find(|f| f.id == id).unwrap();
            registration
                .attributes
                .insert("declaring_type".into(), json!("container"));
            registration
                .attributes
                .insert("source_line".into(), json!(3));
        }
        let before = publish(&store, revision.clone(), &result, "");
        let output = wires(&store, &before.id);
        assert_eq!(output.len(), 6);
        let projected: Vec<_> = output.iter().filter(|f| f.parent == "container").collect();
        assert_eq!(projected.len(), 2);
        for wire in projected {
            assert_eq!(wire.span, None);
            assert_eq!(wire.attributes["projection"], "declaring_type");
            assert_eq!(wire.attributes["source_line"], 3);
            assert_eq!(
                wire.attributes["injection_points"],
                json!(["field", "field2"])
            );
            assert_eq!(
                wire.attributes["injection_evidences"],
                json!(["injection", "injection2"])
            );
        }
        let mut reversed = result.clone();
        reversed.facts.reverse();
        let other_root = tempfile::tempdir().unwrap();
        let other_store = Store::open(other_root.path()).unwrap();
        let full = publish(&other_store, revision.clone(), &reversed, "");
        assert_eq!(output, wires(&other_store, &full.id));
        let mut wrong = result.clone();
        wrong
            .facts
            .iter_mut()
            .find(|f| f.id == "registration")
            .unwrap()
            .attributes
            .insert("declaring_type".into(), json!("a"));
        let error = store
            .stage_archive_with_derivation(
                revision,
                &wrong.header(),
                &archive(&wrong.facts),
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
                .contains("INJECTION_DECLARING_TYPE_MISMATCH")
        );
        assert_eq!(output, wires(&store, &before.id));
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

    #[test]
    fn upstream_aliases_join_only_exact_published_capture_identity() {
        let root = tempfile::tempdir().unwrap();
        let store = Store::open(root.path()).unwrap();
        let (mut revision, mut result) = fixture();
        let bytes = b"interface Api {}";
        let unit = SourceUnit {
            path: "Api.java".into(),
            hash: hash(bytes),
            size: bytes.len() as u64,
            module: "api".into(),
            world: "upstream".into(),
            scope: "MAIN".into(),
            language: "fixture".into(),
            auxiliary: false,
        };
        revision.manifest.files.push(unit.clone());
        result.manifest = revision.manifest.id();
        store
            .put_blobs(&BTreeMap::from([(unit.hash.clone(), bytes.to_vec())]))
            .unwrap();
        let interface = result
            .facts
            .iter_mut()
            .find(|f| f.id == "interface")
            .unwrap();
        interface.owner.world = unit.world.clone();
        interface.owner.id = unit.path.clone();
        interface.source = unit.path.clone();
        interface.signature = "pkg.Api".into();
        for (id, edge) in [
            ("impl-alias", "a-implements"),
            ("injection-alias", "injection"),
        ] {
            let mut alias = fact(id, FactKind::ExternalSymbol, "", "", "");
            alias.source.clear();
            alias.span = None;
            alias.signature = "pkg.Api".into();
            alias.attributes.insert(
                "source_origin".into(),
                json!({"path":unit.path,"hash":unit.hash,
                "world":unit.world,"module":unit.module,"scope":unit.scope}),
            );
            result.facts.push(alias);
            result
                .facts
                .iter_mut()
                .find(|f| f.id == edge)
                .unwrap()
                .target = id.into();
        }
        result.facts.retain(|f| {
            !matches!(
                f.id.as_str(),
                "a-duplicate" | "b-implements" | "external-implements"
            )
        });
        let before = publish(&store, revision.clone(), &result, "");
        let actual = wires(&store, &before.id);
        assert_eq!(actual.len(), 1);
        assert_eq!(actual[0].attributes["source"], "interface");
        assert_eq!(actual[0].target, "a");
        for field in ["hash", "world", "module", "scope", "path"] {
            let mut corrupt = result.clone();
            corrupt
                .facts
                .iter_mut()
                .find(|f| f.id == "impl-alias")
                .unwrap()
                .attributes
                .get_mut("source_origin")
                .unwrap()[field] = json!("different");
            let rejected = publish(
                &store,
                revision.clone(),
                &corrupt,
                &store.head("t").unwrap(),
            );
            assert!(wires(&store, &rejected.id).is_empty(), "{field}");
        }
        let mut unavailable = result;
        unavailable.facts.retain(|f| f.id != "interface");
        revision
            .manifest
            .files
            .iter_mut()
            .find(|s| s.path == "Api.java")
            .unwrap()
            .auxiliary = true;
        unavailable.manifest = revision.manifest.id();
        let rejected = publish(&store, revision, &unavailable, &store.head("t").unwrap());
        assert!(wires(&store, &rejected.id).is_empty());
        assert_eq!(wires(&store, &before.id), actual);
    }
}
