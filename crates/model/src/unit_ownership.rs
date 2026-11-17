//! Revision-owned publication ranges, independent of which rows an engine emits.
use crate::*;

pub const UNIT_OWNERSHIP_VERSION: u32 = 2;
/// Preparation generations and root-config presentation bytes do not change facts.
/// Every semantic source and all role/world/module/scope fields still compare exactly.
pub fn same_semantic_sources(before: &[SourceUnit], after: &[SourceUnit]) -> bool {
    fn files(sources: &[SourceUnit]) -> impl Iterator<Item = &SourceUnit> {
        sources.iter().filter(|source| {
            !(source.scope == "PREPARATION"
                && source.world == "files"
                && source.module.is_empty()
                && source.language.is_empty()
                && !source.auxiliary)
        })
    }
    files(before).count() == files(after).count()
        && files(before).zip(files(after)).all(|(old, new)| {
            old == new
                || old.path == ".code-ana/config.toml"
                    && new.path == old.path
                    && old.language.is_empty()
                    && new.language.is_empty()
                    && old.world == new.world
                    && old.module == new.module
                    && old.scope == new.scope
                    && old.auxiliary == new.auxiliary
        })
}
pub fn publication_language(source: &SourceUnit) -> Option<SourceLanguage> {
    if source.auxiliary {
        return None;
    }
    SourceLanguage::lookup(&source.language).or_else(|| {
        (source.language.is_empty()
            && matches!(source.scope.as_str(), "RESOURCE" | "JAVA_CONFIG")
            && source.path.ends_with(".xml"))
        .then_some(SourceLanguage::Java)
    })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum UnitAvailability {
    Ready,
    FilesOnly,
    Removed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UnitOwnership {
    pub unit: AnalysisUnitId,
    pub backend: AnalysisBackend,
    pub availability: UnitAvailability,
    pub source_languages: Vec<SourceLanguage>,
    pub modules: Vec<String>,
    pub worlds: Vec<String>,
    #[serde(default)]
    pub scopes: Vec<String>,
    pub producer: String,
    pub semantic_profile: String,
    pub input_digest: String,
    /// Includes zero-output sources. Auxiliary binding inputs never publish owners.
    pub sources: Vec<SourceUnit>,
    /// Exact per-unit roles, including shared auxiliary inputs and integrity controls.
    #[serde(default)]
    pub inputs: Vec<SourceUnit>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UnitOwnershipLedger {
    pub version: u32,
    pub units: Vec<UnitOwnership>,
}
impl Default for UnitOwnershipLedger {
    fn default() -> Self {
        Self {
            version: UNIT_OWNERSHIP_VERSION,
            units: Vec::new(),
        }
    }
}
impl UnitOwnershipLedger {
    pub fn validate(&self, manifest: &Manifest) -> Result<(), String> {
        if self.version != UNIT_OWNERSHIP_VERSION || self.units.len() > 10_000 {
            return Err("UNIT_OWNERSHIP_VERSION_OR_BUDGET".into());
        }
        let mut ids = BTreeSet::new();
        let mut owners = BTreeSet::new();
        let mut previous = None;
        let mut references = 0usize;
        let mut input_references = 0usize;
        let declared: BTreeMap<_, _> = manifest
            .files
            .iter()
            .map(|s| ((s.world.as_str(), s.path.as_str()), s))
            .collect();
        for unit in &self.units {
            let order = (unit.backend, unit.unit.as_str());
            if unit.unit.is_empty()
                || unit.unit.len() > 256
                || !ids.insert(&unit.unit)
                || previous.is_some_and(|p| p >= order)
                || unit.availability == UnitAvailability::Removed
            {
                return Err("UNIT_OWNERSHIP_ID_OR_ORDER".into());
            }
            previous = Some(order);
            if unit.source_languages.is_empty()
                || !unit.source_languages.windows(2).all(|p| p[0] < p[1])
                || unit
                    .source_languages
                    .iter()
                    .any(|l| !unit.backend.supports(*l))
                || !unit.modules.windows(2).all(|p| p[0] < p[1])
                || !unit.worlds.windows(2).all(|p| p[0] < p[1])
                || unit.scopes.is_empty()
                || unit.scopes.iter().any(String::is_empty)
                || !unit.scopes.windows(2).all(|p| p[0] < p[1])
            {
                return Err("UNIT_OWNERSHIP_CONTEXT".into());
            }
            if unit.availability == UnitAvailability::FilesOnly {
                if !unit.sources.is_empty()
                    || !unit.producer.is_empty()
                    || !unit.semantic_profile.is_empty()
                    || !unit.input_digest.is_empty()
                    || !unit.inputs.is_empty()
                {
                    return Err("UNIT_OWNERSHIP_FILES_ONLY".into());
                }
            } else if unit.producer.is_empty()
                || !valid_digest(&unit.semantic_profile)
                || !valid_digest(&unit.input_digest)
            {
                return Err("UNIT_OWNERSHIP_IDENTITY".into());
            }
            let mut inputs = BTreeMap::new();
            for input in &unit.inputs {
                input_references += 1;
                let declared = declared
                    .get(&(input.world.as_str(), input.path.as_str()))
                    .copied();
                let valid = declared.is_some_and(|s| {
                    s.path == input.path
                        && s.world == input.world
                        && s.hash == input.hash
                        && s.size == input.size
                        && s.module == input.module
                        && s.scope == input.scope
                        && s.language == input.language
                        && (input.auxiliary || !s.auxiliary)
                });
                if input_references > 10_000
                    || !valid
                    || inputs
                        .insert((input.world.as_str(), input.path.as_str()), input)
                        .is_some()
                {
                    return Err("UNIT_OWNERSHIP_INPUT".into());
                }
            }
            for source in &unit.sources {
                references += 1;
                let semantic = SourceLanguage::lookup(&source.language)
                    .is_some_and(|l| unit.source_languages.contains(&l));
                let java_resource = unit.backend == AnalysisBackend::Java
                    && source.language.is_empty()
                    && matches!(source.scope.as_str(), "RESOURCE" | "JAVA_CONFIG")
                    && source.path.ends_with(".xml");
                if references > 10_000
                    || !(semantic || java_resource)
                    || source.auxiliary
                    || declared
                        .get(&(source.world.as_str(), source.path.as_str()))
                        .copied()
                        != Some(source)
                    || !unit.worlds.contains(&source.world)
                    || !unit.scopes.contains(&source.scope)
                    || inputs
                        .get(&(source.world.as_str(), source.path.as_str()))
                        .copied()
                        != Some(source)
                    || !source.module.is_empty() && !unit.modules.contains(&source.module)
                    || SourceLanguage::lookup(&source.language)
                        .is_some_and(|l| !unit.source_languages.contains(&l))
                    || !owners.insert((&source.world, &source.path))
                {
                    return Err("UNIT_OWNERSHIP_SOURCE_OR_CONFLICT".into());
                }
            }
            if unit
                .inputs
                .iter()
                .filter(|s| {
                    publication_language(s).is_some_and(|l| unit.source_languages.contains(&l))
                })
                .count()
                != unit.sources.len()
            {
                return Err("UNIT_OWNERSHIP_PUBLICATION_INCOMPLETE".into());
            }
        }
        Ok(())
    }
}
fn valid_digest(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn source() -> SourceUnit {
        SourceUnit {
            path: "src/A.java".into(),
            hash: hash("class A {}"),
            size: 10,
            module: "app".into(),
            world: "world".into(),
            scope: "MAIN".into(),
            language: "java".into(),
            auxiliary: false,
        }
    }
    fn unit(id: &str, sources: Vec<SourceUnit>) -> UnitOwnership {
        UnitOwnership {
            inputs: sources.clone(),
            scopes: vec!["MAIN".into()],
            unit: id.into(),
            backend: AnalysisBackend::Java,
            availability: UnitAvailability::Ready,
            source_languages: vec![SourceLanguage::Java],
            modules: vec!["app".into()],
            worlds: vec!["world".into()],
            producer: "javac-v3".into(),
            semantic_profile: hash("profile"),
            input_digest: hash("input"),
            sources,
        }
    }
    #[test]
    fn zero_output_owners_survive_serialization_and_missing_sources_are_rejected() {
        let manifest = Manifest {
            files: vec![source()],
        };
        let ledger = UnitOwnershipLedger {
            version: UNIT_OWNERSHIP_VERSION,
            units: vec![unit("empty-output", manifest.files.clone())],
        };
        ledger.validate(&manifest).unwrap();
        let replay: UnitOwnershipLedger =
            serde_json::from_slice(&serde_json::to_vec(&ledger).unwrap()).unwrap();
        assert_eq!(ledger, replay);
        assert!(replay.validate(&Manifest::default()).is_err());
        let mut wrong = manifest.clone();
        wrong.files[0].hash = hash("changed");
        assert!(replay.validate(&wrong).is_err());
    }
    #[test]
    fn shared_auxiliary_input_does_not_authorize_shared_publication_owners() {
        let manifest = Manifest {
            files: vec![source()],
        };
        let mut ledger = UnitOwnershipLedger {
            version: UNIT_OWNERSHIP_VERSION,
            units: vec![
                unit("a", manifest.files.clone()),
                unit("b", manifest.files.clone()),
            ],
        };
        assert!(ledger.validate(&manifest).is_err());
        ledger.units[1].sources.clear();
        ledger.units[1].inputs[0].auxiliary = true;
        ledger.validate(&manifest).unwrap();
        ledger.units[0].sources[0].auxiliary = true;
        assert!(ledger.validate(&manifest).is_err());
    }
    #[test]
    fn files_only_removed_wrong_language_and_old_ledger_versions_are_not_publishable_claims() {
        let mut ledger = UnitOwnershipLedger {
            version: UNIT_OWNERSHIP_VERSION,
            units: vec![unit("unit", vec![])],
        };
        ledger.units[0].availability = UnitAvailability::Removed;
        assert!(ledger.validate(&Manifest::default()).is_err());
        ledger.units[0].availability = UnitAvailability::FilesOnly;
        assert!(ledger.validate(&Manifest::default()).is_err());
        ledger.units[0].producer.clear();
        ledger.units[0].semantic_profile.clear();
        ledger.units[0].input_digest.clear();
        ledger.validate(&Manifest::default()).unwrap();
        ledger.units[0].source_languages = vec![SourceLanguage::Python];
        assert!(ledger.validate(&Manifest::default()).is_err());
        ledger.units.clear();
        ledger.version = 0;
        assert!(ledger.validate(&Manifest::default()).is_err());
    }
}
