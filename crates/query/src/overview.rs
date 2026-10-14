//! Query operation implementation over narrow read capabilities.
use super::*;

pub(super) fn run(
    r: &dyn OverviewReader,
    req: &Request,
    _evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut records = Vec::new();
    if matches!(
        req.overview_section.as_str(),
        "modules" | "packages" | "dependencies"
    ) {
        let filter = selector_filter(r, req)?;
        let relations = overview_relations(&req.relation)?;
        let mut groups = r
            .overview_groups(&filter, &req.overview_section, &req.direction, &relations)
            .map_err(anyhow::Error::new)?;
        if req.overview_depth > 0 && req.overview_section != "modules" {
            let mut counts = BTreeMap::<(String, String, String), u64>::new();
            for row in groups {
                let package = |value: Option<String>| {
                    value
                        .unwrap_or_default()
                        .split('.')
                        .take(req.overview_depth as usize)
                        .collect::<Vec<_>>()
                        .join(".")
                };
                *counts
                    .entry((
                        package(row.package),
                        package(row.target_package),
                        row.relation.unwrap_or_default(),
                    ))
                    .or_default() += row.count;
            }
            groups = counts
                .into_iter()
                .map(|((package, target, relation), count)| OverviewGroup {
                    package: Some(package),
                    count,
                    target_package: (req.overview_section == "dependencies").then_some(target),
                    relation: (req.overview_section == "dependencies").then_some(relation),
                    ..Default::default()
                })
                .collect();
        }
        records = groups.into_iter().map(Record::Group).collect();
    } else {
        let counts = r
            .count_by_kind(&scope_filter(req))
            .map_err(anyhow::Error::new)?;
        let sources = r
            .manifest()
            .files
            .iter()
            .filter(|source| {
                !source.auxiliary
                    && (req.module.is_empty() || source.module == req.module)
                    && (req.scope.is_empty() || req.scope == "ALL" || source.scope == req.scope)
                    && (req.world.is_empty() || source.world == req.world)
            })
            .count();
        records.push(Record::Project(ProjectOverview {
            counts,
            sources,
            profile: r.revision().profile.clone(),
        }));
    }
    Ok(records)
}
