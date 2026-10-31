//! Bounded typed stage execution; no JSON field lookup or record cloning between stages.
use super::*;
pub(super) fn run(
    r: &dyn GraphReader,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
    evidence: &mut Evidence,
) -> Result<Vec<Record>> {
    let mut input: Vec<Record> = Vec::new();
    let mut records = Vec::new();
    for (i, stage) in req.stages.iter().enumerate() {
        let mut s = req.clone();
        s.operation = stage.operation;
        s.direction = stage.direction;
        s.relation = stage.relation.clone();
        s.depth = stage.depth;
        s.meta = stage.meta;
        s.instantiability = stage.instantiability;
        s.transitive = stage.transitive;
        s.algorithm = stage.algorithm;
        s.analysis_world = stage.analysis_world;
        s.unique = stage.unique;
        s.stages.clear();
        s.max_records = 10000;
        let selectors = if i == 0 {
            vec![stage.selector.clone()]
        } else {
            let mut selectors = BTreeSet::new();
            for record in &input {
                selectors.extend(
                    record
                        .endpoint(stage.endpoint)?
                        .into_iter()
                        .map(str::to_owned),
                );
            }
            selectors.into_iter().collect()
        };
        records.clear();
        for selector in selectors {
            if r.charge(1, selector.len() as u64).is_err() {
                break;
            }
            s.selector = selector;
            let out = super::run_query(r, &s, read_source)?;
            evidence.merge(&out.evidence);
            let remaining = 10000usize.saturating_sub(records.len());
            let limited = out.records.len() > remaining;
            records.extend(out.records.into_iter().take(remaining));
            if limited {
                evidence.enumeration = false;
                evidence.limits.push("records".into());
                return Ok(if i + 1 == req.stages.len() {
                    records
                } else {
                    Vec::new()
                });
            }
        }
        if r.resource_exhausted() {
            if let Some(b) = r.resource_budget() {
                b.apply(evidence);
            }
            return Ok(if i + 1 == req.stages.len() {
                records
            } else {
                Vec::new()
            });
        }
        input = std::mem::take(&mut records);
    }
    Ok(input)
}
