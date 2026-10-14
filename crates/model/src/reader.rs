//! Narrow read capabilities over a fixed revision; no SQL or process dependencies.
use crate::*;
use std::collections::BTreeMap;
pub trait SourceContext {
    fn manifest(&self) -> &Manifest;
    fn revision(&self) -> &Revision;
}
pub trait GraphAccess: SourceContext {
    fn scan(
        &self,
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> Result<Vec<Fact>, DomainError>;
    fn scan_symbols(
        &self,
        text: &str,
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> Result<Vec<Fact>, DomainError>;
    fn search_symbols_filtered(
        &self,
        text: &str,
        filter: &FactFilter,
    ) -> Result<Vec<Fact>, DomainError> {
        let mut filter = filter.clone();
        filter.kinds = vec![
            FactKind::Declaration,
            FactKind::ExternalSymbol,
            FactKind::Facet,
        ];
        Ok(self
            .select(&filter)?
            .into_iter()
            .filter(|f| {
                f.is_public_entity() && (f.name.contains(text) || f.signature.contains(text))
            })
            .collect())
    }
    fn search_symbols(&self, text: &str) -> std::result::Result<Vec<Fact>, DomainError> {
        Ok(self
            .select(&FactFilter {
                kinds: vec![FactKind::Declaration, FactKind::ExternalSymbol],
                ..Default::default()
            })?
            .into_iter()
            .filter(|f| f.name.contains(text) || f.signature.contains(text))
            .collect())
    }
    fn select(&self, filter: &FactFilter) -> Result<Vec<Fact>, DomainError>;
}
pub trait SourceLocator: SourceContext {
    fn source_span(&self, entity: &Fact) -> Result<Option<Span>, DomainError> {
        Ok(entity.span.clone())
    }
}
pub trait AnnotationReader: GraphAccess {
    fn annotation_meta_edges(
        &self,
        names: &[String],
    ) -> Result<Vec<AnnotationMetaEdge>, DomainError> {
        if names.is_empty() || names.len() > 500 {
            return Err("ANNOTATION_META_KEY_BUDGET".into());
        }
        let mut pairs = BTreeMap::<(String, String), FactAttributes>::new();
        for fact in self.select(&FactFilter {
            kinds: vec![FactKind::Facet],
            relation: Some("java.annotation.meta".into()),
            ..Default::default()
        })? {
            let (Some(annotation), Some(meta)) = (
                fact.attributes.annotation.as_ref(),
                fact.attributes.meta_annotation.as_ref(),
            ) else {
                continue;
            };
            if names.iter().any(|name| name == annotation) {
                pairs
                    .entry((annotation.clone(), meta.clone()))
                    .or_default()
                    .merge_min_annotation_meta(&fact.attributes);
            }
        }
        if pairs.len() > 10000 {
            return Err("QUERY_WORK_BUDGET".into());
        }
        Ok(pairs
            .into_iter()
            .map(
                |((annotation, meta_annotation), attributes)| AnnotationMetaEdge {
                    annotation,
                    meta_annotation,
                    attributes,
                },
            )
            .collect())
    }
}
pub trait OverviewReader: GraphAccess {
    fn overview_groups(
        &self,
        _filter: &FactFilter,
        _section: &str,
        _direction: &str,
        _relations: &[String],
    ) -> Result<Vec<OverviewGroup>, DomainError> {
        Err("OVERVIEW_PROJECTION_UNAVAILABLE".into())
    }
    fn count_by_kind(&self, filter: &FactFilter) -> Result<BTreeMap<String, u64>, DomainError> {
        let mut counts = BTreeMap::new();
        for fact in self.select(filter)? {
            *counts.entry(fact.kind.name().into()).or_default() += 1;
        }
        Ok(counts)
    }
}
pub trait DependencyReader: GraphAccess {
    fn reverse_file_dependencies(
        &self,
        _targets: &[FileKey],
        _source_world: Option<&str>,
        _after: &str,
        _limit: usize,
    ) -> Result<Vec<FileDependency>, DomainError> {
        Err("FILE_DEPENDENCIES_UNAVAILABLE".into())
    }
    fn reverse_dependencies(
        &self,
        targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> Result<Vec<Fact>, DomainError> {
        if targets.len() > 500 || !(1..=1024).contains(&limit) {
            return Err("INVALID_DEPENDENCY_BATCH".into());
        }
        if targets.is_empty() {
            return Ok(Vec::new());
        }
        if !filter.targets.is_empty() || filter.target.is_some() {
            return Err("DEPENDENCY_TARGETS_ALREADY_FILTERED".into());
        }
        let mut filter = filter.clone();
        filter.targets = targets.to_vec();
        if filter.kinds.is_empty() {
            filter.kinds = vec![
                FactKind::Dependency,
                FactKind::Reference,
                FactKind::Relation,
                FactKind::CallTarget,
            ];
        }
        self.scan(&filter, after, limit)
    }
    fn unresolved_consumers(
        &self,
        syntax_targets: &[String],
        filter: &FactFilter,
        after: &str,
        limit: usize,
    ) -> Result<Vec<Fact>, DomainError> {
        if syntax_targets.len() > 500 || !(1..=1024).contains(&limit) {
            return Err("INVALID_DEPENDENCY_BATCH".into());
        }
        if syntax_targets.is_empty() {
            return Ok(Vec::new());
        }
        let mut filter = filter.clone();
        filter.kinds = vec![FactKind::CallSite];
        let mut facts = self.select(&filter)?;
        facts.retain(|fact| {
            fact.id.as_str() > after
                && fact.status == "unresolved"
                && syntax_targets.contains(&fact.name)
        });
        facts.sort_by(|a, b| a.id.cmp(&b.id));
        facts.truncate(limit);
        Ok(facts)
    }
}
/// Composite capability used only by the query dispatcher.
pub trait GraphReader:
    SourceQueryReader + AnnotationQueryReader + OverviewReader + DependencyReader
{
}
impl<T: GraphAccess + SourceLocator + AnnotationReader + OverviewReader + DependencyReader + ?Sized>
    GraphReader for T
{
}
pub trait AnnotationQueryReader: GraphAccess + AnnotationReader {}
impl<T: GraphAccess + AnnotationReader + ?Sized> AnnotationQueryReader for T {}
pub trait SourceQueryReader: GraphAccess + SourceLocator {}
impl<T: GraphAccess + SourceLocator + ?Sized> SourceQueryReader for T {}
