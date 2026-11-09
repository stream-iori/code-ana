//! Publicly selectable query inputs. CLI presentation consumes this contract;
//! query validation remains authoritative for values and language capabilities.
use codeana_model::Operation;

pub fn input_fields(operation: Operation) -> Option<Vec<&'static str>> {
    if !super::OPERATIONS.contains(&operation.as_str()) {
        return None;
    }
    let mut fields = vec!["selector", "revision", "scope", "world", "module"];
    let specific: &[&str] = match operation {
        Operation::Resolve => &["unique"],
        Operation::SearchCode => &[
            "signature",
            "package",
            "package_prefix",
            "paths",
            "entity_kind",
            "annotation",
            "visibility",
            "synthetic",
            "search_in",
        ],
        Operation::SearchComments => &["paths"],
        Operation::SearchText => &["paths", "regex", "ignore_case", "context"],
        Operation::Structure => &["depth", "unique"],
        Operation::Calls => &["direction", "depth", "unique"],
        Operation::CallSites => &["direction", "unique"],
        Operation::References => &["direction", "relation", "unique"],
        Operation::Source => &["unique"],
        Operation::Annotations => &["meta", "unique"],
        Operation::TypeRelations | Operation::CallableRelations => {
            &["direction", "relation", "depth", "transitive", "unique"]
        }
        Operation::RuntimeImplementations => &["instantiability", "depth", "unique"],
        Operation::Dispatch => &["algorithm", "analysis_world", "unique"],
        Operation::Trace => &["target", "direction", "depth"],
        Operation::Overview => &[
            "section",
            "package_depth",
            "package",
            "package_prefix",
            "direction",
            "relation",
        ],
        Operation::Pipeline => &[
            "target",
            "regex",
            "ignore_case",
            "context",
            "search_in",
            "package",
            "package_prefix",
            "paths",
            "signature",
            "entity_kind",
            "annotation",
            "visibility",
            "synthetic",
        ],
        _ => &[],
    };
    fields.extend_from_slice(specific);
    Some(fields)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn index_and_maintenance_are_not_query_options() {
        let calls = input_fields(Operation::Calls).unwrap();
        assert!(calls.contains(&"direction"));
        assert!(!calls.contains(&"full"));
        assert!(!calls.contains(&"execute"));
        assert!(input_fields(Operation::Index).is_none());
    }
}
