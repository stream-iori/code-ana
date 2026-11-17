//! Source/publication fencing before a provisional batch reaches a candidate.
use anyhow::Result;
use codeana_contracts::graph::{Row, Table, cell};
use codeana_model::{
    AnalysisContext, DomainError, ErrorCode, Manifest, Owner, SourceLanguage, SourceUnit, safe_path,
};
use std::collections::{BTreeMap, BTreeSet};

macro_rules! check {
    ($condition:expr,$($message:tt)*) => {anyhow::ensure!($condition,DomainError::new(ErrorCode::AnalysisUnitScope,format!($($message)*)))};
}
#[derive(Clone, Copy)]
pub struct SourceColumns {
    pub source: usize,
    pub identity: Option<(usize, usize)>,
    pub language: Option<usize>,
}
pub fn source_columns(table: Table) -> Option<SourceColumns> {
    let (source, identity, language) = match table {
        Table::Node => (12, Some((19, 20)), Some(3)),
        Table::Declaration => (17, Some((23, 24)), Some(2)),
        Table::Edge => (16, Some((26, 27)), Some(9)),
        Table::Annotation => (10, Some((16, 17)), Some(6)),
        Table::UnresolvedCall => (1, Some((13, 14)), None),
        Table::Coverage => (0, Some((1, 2)), Some(3)),
        Table::Diagnostic => (3, Some((4, 5)), Some(7)),
        Table::FileCache => (1, None, None),
        Table::FileDependency => (1, None, None),
        Table::SymbolDependency => (1, None, None),
        Table::SourceState | Table::AnalysisDependency => (0, None, None),
        Table::AnnotationMeta => (7, None, Some(3)),
        Table::Comment => (1, Some((3, 4)), Some(5)),
        Table::CommentLink => (3, Some((4, 5)), None),
        _ => return None,
    };
    Some(SourceColumns {
        source,
        identity,
        language,
    })
}
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Compiler,
    Fixture,
}
pub struct Scope<'a> {
    unit: String,
    sources: BTreeMap<(&'a str, &'a str), &'a SourceUnit>,
    previous: BTreeSet<(String, String)>,
    modules: BTreeSet<String>,
    languages: BTreeSet<SourceLanguage>,
    mode: Mode,
}
impl<'a> Scope<'a> {
    pub fn new(
        unit: &str,
        manifest: &'a Manifest,
        previous: &[SourceUnit],
        modules: &[String],
        languages: &[SourceLanguage],
        mode: Mode,
        budget: Option<&AnalysisContext>,
    ) -> Result<Self> {
        Self::from_sources(
            unit,
            &manifest.files,
            previous,
            modules,
            languages,
            mode,
            budget,
        )
    }
    pub fn from_sources(
        unit: &str,
        sources: &'a [SourceUnit],
        previous: &[SourceUnit],
        modules: &[String],
        languages: &[SourceLanguage],
        mode: Mode,
        budget: Option<&AnalysisContext>,
    ) -> Result<Self> {
        if let Some(budget) = budget {
            let bytes = 512
                + std::mem::size_of::<Self>() * 2
                + sources.len().saturating_mul(256)
                + previous
                    .iter()
                    .map(|s| 2 * (s.path.len() + s.world.len()) + 256)
                    .sum::<usize>()
                + modules.iter().map(|m| m.len() + 128).sum::<usize>()
                + unit.len();
            budget.charge(1, 0, bytes as u64)?;
        }
        Ok(Self {
            unit: unit.into(),
            sources: sources
                .iter()
                .map(|s| ((s.path.as_str(), s.world.as_str()), s))
                .collect(),
            previous: previous
                .iter()
                .map(|s| (s.path.clone(), s.world.clone()))
                .collect(),
            modules: modules.iter().cloned().collect(),
            languages: languages.iter().copied().collect(),
            mode,
        })
    }
    pub fn owner(&self, owner: &Owner, producer: &str) -> Result<()> {
        check!(
            owner.kind == "source" && owner.producer == producer && safe_path(&owner.id),
            "UNIT_REPLACEMENT_IDENTITY: {}",
            self.unit
        );
        check!(
            self.sources
                .contains_key(&(owner.id.as_str(), owner.world.as_str()))
                || self
                    .previous
                    .contains(&(owner.id.clone(), owner.world.to_string())),
            "UNIT_REPLACEMENT_OUTSIDE_SCOPE: {} {}",
            self.unit,
            owner.id
        );
        Ok(())
    }
    pub fn row(&self, row: &Row) -> Result<()> {
        self.source_world(row).map(|_| ())
    }
    /// Resolve only an implicit world against this unit's frozen inputs.
    /// Raw bytes remain the transport verifier's responsibility.
    pub fn source_world(&self, row: &Row) -> Result<Option<&str>> {
        let table = Table::try_from(row.table)?;
        check!(table != Table::Unspecified, "UNIT_ROW_TABLE_UNSPECIFIED");
        let text = |column: usize| -> Result<Option<&str>> {
            match row.cells.get(column).and_then(|c| c.value.as_ref()) {
                None => Ok(None),
                Some(cell::Value::Text(v)) => Ok(Some(v)),
                _ => Err(
                    DomainError::new(ErrorCode::AnalysisUnitScope, "UNIT_SCOPE_COLUMN_TYPE").into(),
                ),
            }
        };
        let columns = source_columns(table);
        let path = columns
            .map(|c| text(c.source))
            .transpose()?
            .flatten()
            .unwrap_or("");
        let administrative = matches!(table, Table::Coverage | Table::Diagnostic);
        if let Some(language) = columns
            .and_then(|c| c.language)
            .or((table == Table::Provider).then_some(1))
            && let Some(language) = text(language)?.filter(|s| {
                !s.is_empty() && !(*s == "*" && (administrative || table == Table::Provider))
            })
            && self.mode == Mode::Compiler
        {
            let binding_metadata = table == Table::Provider
                || path.is_empty() && matches!(table, Table::Node | Table::Declaration)
                || administrative
                    && self
                        .sources
                        .range((path, "")..)
                        .take_while(|((p, _), _)| *p == path)
                        .any(|(_, s)| s.auxiliary && s.language == language);
            let permitted = SourceLanguage::lookup(language).is_some_and(|l| {
                self.languages.contains(&l)
                    || binding_metadata && self.languages.iter().any(|p| p.backend() == l.backend())
            });
            check!(permitted, "UNIT_ROW_LANGUAGE: {} {language}", self.unit);
        }
        let Some(columns) = columns else {
            return Ok(None);
        };
        if path.is_empty() || path == "*" {
            check!(
                path != "*" || administrative,
                "UNIT_ROW_WILDCARD_SOURCE: {}",
                self.unit
            );
            if administrative
                && let Some((module, _)) = columns.identity
                && let Some(module) = text(module)?.filter(|s| !s.is_empty() && *s != "*")
            {
                check!(
                    self.modules.contains(module),
                    "UNIT_ROW_MODULE: {} {module}",
                    self.unit
                );
            }
            return Ok(None);
        }
        check!(safe_path(path), "UNIT_ROW_SOURCE_UNSAFE: {}", self.unit);
        let module = columns
            .identity
            .map(|(m, _)| text(m))
            .transpose()?
            .flatten();
        let scope = columns
            .identity
            .map(|(_, s)| text(s))
            .transpose()?
            .flatten();
        let matches = |source: &&SourceUnit| {
            module.is_none_or(|m| {
                m == source.module
                    || source.module.is_empty() && source.language.is_empty()
                    || administrative && m == "*"
            }) && scope.is_none_or(|s| {
                s == source.scope
                    || source.scope == "RESOURCE" && matches!(s, "MAIN" | "TEST")
                    || administrative && s == "*"
            })
        };
        let mut candidates = self
            .sources
            .range((path, "")..)
            .take_while(|((p, _), _)| *p == path)
            .map(|(_, s)| *s)
            .filter(|s| (row.world.is_empty() || row.world == s.world) && matches(s));
        let source = candidates.next().ok_or_else(|| {
            DomainError::new(
                ErrorCode::AnalysisUnitScope,
                format!("UNIT_ROW_OUTSIDE_SCOPE: {} {path} {}", self.unit, row.world),
            )
        })?;
        let input_metadata = matches!(
            table,
            Table::FileCache
                | Table::FileDependency
                | Table::SymbolDependency
                | Table::AnnotationMeta
                | Table::Diagnostic
                | Table::Coverage
        );
        check!(
            candidates.next().is_none(),
            "UNIT_ROW_AMBIGUOUS_SOURCE: {} {path}",
            self.unit
        );
        check!(
            input_metadata || !source.auxiliary,
            "UNIT_ROW_AUXILIARY_PUBLICATION: {} {path}",
            self.unit
        );
        if self.mode == Mode::Compiler && !input_metadata {
            check!(
                codeana_model::publication_language(source)
                    .is_some_and(|l| self.languages.contains(&l)),
                "UNIT_ROW_INPUT_ROLE: {} {path}",
                self.unit
            );
        }
        if self.mode == Mode::Compiler
            && matches!(table, Table::SourceState | Table::AnalysisDependency)
        {
            check!(
                source.language == "rust" && text(1)? == Some(source.world.as_str()),
                "UNIT_ROW_RUST_STATE_OWNER: {}",
                self.unit
            );
        }
        Ok(Some(&source.world))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::graph_rows::{CoverageRow, DiagnosticRow, GraphRow, NodeRow};
    fn source(path: &str, world: &str, auxiliary: bool) -> SourceUnit {
        SourceUnit {
            path: path.into(),
            world: world.into(),
            module: "app".into(),
            scope: "MAIN".into(),
            language: "java".into(),
            hash: codeana_model::hash("source"),
            size: 6,
            auxiliary,
        }
    }
    fn node(path: &str, world: &str, module: &str, scope: &str, language: &str) -> Row {
        NodeRow {
            id: Some("node".into()),
            symbol_id: Some("symbol".into()),
            language: Some(language.into()),
            source_file: Some(path.into()),
            module: Some(module.into()),
            scope: Some(scope.into()),
            ..Default::default()
        }
        .into_row(world)
    }
    fn cache(path: &str) -> Row {
        Row {
            table: Table::FileCache as i32,
            world: String::new(),
            cells: ["provider", path]
                .into_iter()
                .map(|v| codeana_contracts::graph::Cell {
                    value: Some(cell::Value::Text(v.into())),
                })
                .collect(),
        }
    }
    fn compiler(manifest: &Manifest) -> Scope<'_> {
        Scope::new(
            "unit",
            manifest,
            &[],
            &["app".into()],
            &[SourceLanguage::Java],
            Mode::Compiler,
            None,
        )
        .unwrap()
    }
    #[test]
    fn source_world_module_scope_and_language_are_bound_to_the_unit() {
        let manifest = Manifest {
            files: vec![source("A.java", "own", false)],
        };
        let scope = compiler(&manifest);
        scope
            .row(&node("A.java", "own", "app", "MAIN", "java"))
            .unwrap();
        scope
            .row(&node("A.java", "", "app", "MAIN", "java"))
            .unwrap();
        for row in [
            node("B.java", "own", "app", "MAIN", "java"),
            node("A.java", "foreign", "app", "MAIN", "java"),
            node("A.java", "own", "other", "MAIN", "java"),
            node("A.java", "own", "app", "TEST", "java"),
            node("A.java", "own", "app", "MAIN", "rust"),
            node("A.java", "own", "app", "MAIN", "*"),
            node("*", "own", "app", "MAIN", "java"),
        ] {
            let error = scope.row(&row).unwrap_err();
            assert_eq!(
                error.downcast_ref::<DomainError>().unwrap().code,
                ErrorCode::AnalysisUnitScope
            );
        }
    }
    #[test]
    fn auxiliary_bytes_and_controls_do_not_authorize_declarations_but_keep_diagnostics_and_cache_metadata()
     {
        let mut control = source("settings.toml", "control", false);
        control.scope = "CONTROL".into();
        control.language.clear();
        let manifest = Manifest {
            files: vec![source("Aux.java", "aux", true), control],
        };
        let scope = compiler(&manifest);
        assert!(
            scope
                .row(&node("Aux.java", "aux", "app", "MAIN", "java"))
                .is_err()
        );
        assert!(
            scope
                .row(&node("settings.toml", "control", "app", "CONTROL", "java"))
                .is_err()
        );
        scope.row(&cache("Aux.java")).unwrap();
        scope
            .row(
                &DiagnosticRow {
                    source_file: Some("Aux.java".into()),
                    module: Some("app".into()),
                    scope: Some("MAIN".into()),
                    language: Some("java".into()),
                    ..Default::default()
                }
                .into_row("aux"),
            )
            .unwrap();
    }
    #[test]
    fn same_path_worldless_rows_need_an_unambiguous_compilation_owner() {
        let mut test = source("Shared.java", "test", false);
        test.scope = "TEST".into();
        let manifest = Manifest {
            files: vec![source("Shared.java", "main", false), test],
        };
        let scope = compiler(&manifest);
        scope
            .row(&node("Shared.java", "", "app", "MAIN", "java"))
            .unwrap();
        scope
            .row(&node("Shared.java", "", "app", "TEST", "java"))
            .unwrap();
        let mut ambiguous = node("Shared.java", "", "app", "MAIN", "java");
        ambiguous.cells[20].value = None;
        assert!(scope.row(&ambiguous).is_err());
        assert!(scope.row(&cache("Shared.java")).is_err());
        let mut explicit = cache("Shared.java");
        explicit.world = "main".into();
        scope.row(&explicit).unwrap();
    }
    #[test]
    fn deletion_requires_the_previous_frozen_input_and_the_expected_producer() {
        let manifest = Manifest::default();
        let previous = [source("deleted.java", "previous", true)];
        let scope = Scope::new(
            "unit",
            &manifest,
            &previous,
            &["app".into()],
            &[SourceLanguage::Java],
            Mode::Compiler,
            None,
        )
        .unwrap();
        let valid = Owner {
            world: "previous".into(),
            producer: "javac-v3".into(),
            kind: "source".into(),
            id: "deleted.java".into(),
        };
        scope.owner(&valid, "javac-v3").unwrap();
        for invalid in [
            Owner {
                world: "foreign".into(),
                ..valid.clone()
            },
            Owner {
                producer: "rust-core".into(),
                ..valid.clone()
            },
            Owner {
                id: "other.java".into(),
                ..valid.clone()
            },
            Owner {
                kind: "producer".into(),
                ..valid
            },
        ] {
            assert!(scope.owner(&invalid, "javac-v3").is_err());
        }
    }
    #[test]
    fn aggregate_coverage_stays_in_declared_modules_and_fixture_labels_do_not_grant_foreign_sources()
     {
        let manifest = Manifest {
            files: vec![source("A.java", "own", false)],
        };
        let scope = compiler(&manifest);
        scope
            .row(
                &CoverageRow {
                    source_file: Some("*".into()),
                    module: Some("app".into()),
                    scope: Some("MAIN".into()),
                    language: Some("java".into()),
                    ..Default::default()
                }
                .into_row(""),
            )
            .unwrap();
        assert!(
            scope
                .row(
                    &CoverageRow {
                        source_file: Some("*".into()),
                        module: Some("other".into()),
                        ..Default::default()
                    }
                    .into_row("")
                )
                .is_err()
        );
        let fixture = Scope::new(
            "fixture",
            &manifest,
            &[],
            &["app".into()],
            &[SourceLanguage::Java],
            Mode::Fixture,
            None,
        )
        .unwrap();
        fixture
            .row(&node("A.java", "own", "app", "MAIN", "opaque-fixture-tag"))
            .unwrap();
        assert!(
            fixture
                .row(&node("B.java", "own", "app", "MAIN", "opaque-fixture-tag"))
                .is_err()
        );
    }
    #[test]
    fn scope_metadata_is_admitted_before_copying_old_owners() {
        let manifest = Manifest {
            files: vec![source("A.java", "own", false)],
        };
        let budget = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_payload_bytes: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(
            Scope::new(
                "unit",
                &manifest,
                &manifest.files,
                &["app".into()],
                &[SourceLanguage::Java],
                Mode::Compiler,
                Some(&budget)
            )
            .is_err()
        );
        assert!(budget.checkpoint().is_err());
    }
}
