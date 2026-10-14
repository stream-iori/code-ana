//! Validate the flat public configuration format; never parse XML or infer Java types.
use crate::{Fact, FactKind};
use serde_json::Value;

const ERROR: &str = "INVALID_JAVA_XML_CONFIGURATION";

pub(crate) fn validate(fact: &Fact, size: Option<u64>) -> Result<(), String> {
    let attributes = fact.attributes.wire_map();
    let Some(model) = attributes.get("xml_configuration_model") else {
        return Ok(());
    };
    let fail = || ERROR.to_string();
    if fact.kind != FactKind::Facet
        || fact.relation != "java.bean"
        || attributes.get("kind").and_then(Value::as_str) != Some("xml_bean")
        || fact.owner.kind != "resource"
        || size.is_none()
        || model.get("version").and_then(Value::as_u64) != Some(1)
    {
        return Err(fail());
    }
    let construction = model
        .get("construction")
        .and_then(Value::as_object)
        .ok_or_else(fail)?;
    if construction.get("name").and_then(Value::as_str).is_none()
        || construction
            .get("ordinal")
            .and_then(Value::as_u64)
            .is_none()
        || construction
            .get("abstract")
            .and_then(Value::as_bool)
            .is_none()
        || !construction.get("attributes").is_some_and(Value::is_object)
    {
        return Err(fail());
    }
    for key in [
        "className",
        "parent",
        "factoryBean",
        "factoryMethod",
        "initMethod",
        "destroyMethod",
        "nestedIn",
    ] {
        if !construction
            .get(key)
            .is_some_and(|v| v.is_null() || v.is_string())
        {
            return Err(fail());
        }
    }
    let nodes = model
        .get("nodes")
        .and_then(Value::as_array)
        .ok_or_else(fail)?;
    if nodes.len() > 30_000 {
        return Err(fail());
    }
    let index = |value: &Value| -> Result<usize, String> {
        let value = value.as_u64().ok_or_else(fail)?;
        if value >= nodes.len() as u64 {
            return Err(fail());
        }
        Ok(value as usize)
    };
    let roots = model
        .get("roots")
        .and_then(Value::as_array)
        .ok_or_else(fail)?;
    let mut incoming = vec![false; nodes.len()];
    let mut queue = Vec::with_capacity(nodes.len());
    for value in roots {
        let id = index(value)?;
        if std::mem::replace(&mut incoming[id], true) {
            return Err(fail());
        }
        queue.push(id);
    }
    let mut children = Vec::with_capacity(nodes.len());
    for node in nodes {
        let metadata = node
            .get("metadata")
            .and_then(Value::as_object)
            .ok_or_else(fail)?;
        if !matches!(
            metadata.get("xmlKind").and_then(Value::as_str),
            Some(
                "property"
                    | "constructor-arg"
                    | "map"
                    | "list"
                    | "entry"
                    | "ref"
                    | "idref"
                    | "value"
                    | "null"
                    | "bean"
            )
        ) || metadata.get("ordinal").and_then(Value::as_u64).is_none()
            || metadata.get("index").is_some_and(|v| v.as_i64().is_none())
        {
            return Err(fail());
        }
        for key in ["name", "key", "bean", "value", "type"] {
            if metadata.get(key).is_some_and(|v| !v.is_string()) {
                return Err(fail());
            }
        }
        let position = node
            .get("position")
            .and_then(Value::as_object)
            .ok_or_else(fail)?;
        let coordinate = |key| {
            position
                .get(key)
                .and_then(Value::as_u64)
                .filter(|v| *v > 0)
                .ok_or_else(fail)
        };
        let begin = (coordinate("line")?, coordinate("column")?);
        let end = (coordinate("end_line")?, coordinate("end_column")?);
        let synthetic = node
            .get("synthetic")
            .and_then(Value::as_bool)
            .ok_or_else(fail)?;
        if end < begin || synthetic && begin != end {
            return Err(fail());
        }
        let span = node
            .get("source_element_span")
            .and_then(Value::as_object)
            .ok_or_else(fail)?;
        let start = span.get("start").and_then(Value::as_u64).ok_or_else(fail)?;
        let end = span.get("end").and_then(Value::as_u64).ok_or_else(fail)?;
        if end < start
            || end > size.unwrap()
            || fact
                .span
                .as_ref()
                .is_none_or(|s| start < s.start || end > s.end)
        {
            return Err(fail());
        }
        let mut next = Vec::new();
        for value in node
            .get("children")
            .and_then(Value::as_array)
            .ok_or_else(fail)?
        {
            let id = index(value)?;
            if std::mem::replace(&mut incoming[id], true) {
                return Err(fail());
            }
            next.push(id);
        }
        children.push(next);
    }
    let mut seen = vec![false; nodes.len()];
    while let Some(id) = queue.pop() {
        if std::mem::replace(&mut seen[id], true) {
            return Err(fail());
        }
        queue.extend(children[id].iter().copied());
    }
    if seen.iter().any(|v| !*v) {
        return Err(fail());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Owner, Span};
    use serde_json::json;

    fn fixture() -> Fact {
        Fact {
            id: "bean".into(), kind: FactKind::Facet,
            owner: Owner {world: "main".into(),producer: "spring-model-v1".into(),kind: "resource".into(),id: "beans.xml".into()},
            source: "beans.xml".into(), span: Some(Span {start: 1,end: 100}), name: String::new(),
            signature: String::new(), parent: String::new(), target: String::new(), relation: "java.bean".into(),
            status: "possible".into(), origin: "spring-model-v1".into(), evidence_complete: false, declaration_resolved: false,
            attributes: serde_json::from_value(json!({"kind":"xml_bean","xml_configuration_model":{
                "version":1,"construction":{"name":"api","ordinal":0,"abstract":false,"attributes":{},
                    "className":null,"parent":null,"factoryBean":null,"factoryMethod":null,"initMethod":null,"destroyMethod":null,"nestedIn":null},
                "roots":[0],"nodes":[{"metadata":{"xmlKind":"list","ordinal":0},"synthetic":false,
                    "position":{"line":1,"column":1,"end_line":2,"end_column":1},"source_element_span":{"start":2,"end":90},"children":[1]},
                    {"metadata":{"xmlKind":"ref","ordinal":0,"index":0,"bean":"api"},"synthetic":true,
                    "position":{"line":1,"column":2,"end_line":1,"end_column":2},"source_element_span":{"start":3,"end":10},"children":[]}]}})).unwrap(),
        }
    }

    #[test]
    fn rejects_corrupt_graph_identity_geometry_and_source_evidence() {
        let good = fixture();
        validate(&good, Some(100)).unwrap();
        for (path, value) in [
            ("/version", json!(2)),
            ("/roots", json!([0, 0])),
            ("/roots", json!([])),
            ("/nodes/0/children", json!([1.0])),
            ("/nodes/1/children", json!([0])),
            ("/nodes/1/source_element_span/end", json!(101)),
            ("/nodes/1/source_element_span/start", json!(0)),
            ("/nodes/1/position/end_column", json!(3)),
            ("/nodes/1/metadata/xmlKind", json!("type")),
        ] {
            let mut bad = good.clone();
            let mut attrs = bad.attributes.wire_map();
            *attrs
                .get_mut("xml_configuration_model")
                .unwrap()
                .pointer_mut(path)
                .unwrap() = value;
            bad.attributes = crate::FactAttributes::from_wire_map(attrs).unwrap();
            assert_eq!(validate(&bad, Some(100)), Err(ERROR.into()), "{path}");
        }
        let mut bad = good;
        bad.owner.kind = "source".into();
        assert_eq!(validate(&bad, Some(100)), Err(ERROR.into()));
    }
}
