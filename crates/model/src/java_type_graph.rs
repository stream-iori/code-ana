//! Validation and bounded access to detached Java graph facets. No type inference runs in Rust.
use serde_json::{Map, Value};
use std::collections::{BTreeMap, BTreeSet};

const TABLE: &str = "java_type_graphs";
const SINGLE: &[&str] = &[
    "enclosing",
    "component",
    "extends",
    "super",
    "upper",
    "lower",
    "return",
    "receiver",
];
const MANY: &[&str] = &[
    "arguments",
    "bounds",
    "alternatives",
    "parameters",
    "throws",
    "type_variables",
];
const MAX_NODES: usize = 100_000;

macro_rules! edges {
    ($name:ident, $($variant:ident=$field:literal),* $(,)?) => {
        #[derive(Debug,Clone,Copy)] pub enum $name {$($variant,)*}
        impl $name {pub fn as_str(self)->&'static str {match self {$ (Self::$variant=>$field,)*}}}
        impl std::str::FromStr for $name {type Err=String;fn from_str(value:&str)->Result<Self,String> {match value {$($field=>Ok(Self::$variant),)* _=>Err(invalid())}}}
    }
}
edges!(
    SingleEdge,
    Enclosing = "enclosing",
    Component = "component",
    Extends = "extends",
    Super = "super",
    Upper = "upper",
    Lower = "lower",
    Return = "return",
    Receiver = "receiver"
);
edges!(
    ManyEdge,
    Arguments = "arguments",
    Bounds = "bounds",
    Alternatives = "alternatives",
    Parameters = "parameters",
    Throws = "throws",
    TypeVariables = "type_variables"
);

fn invalid() -> String {
    "INVALID_JAVA_TYPE_GRAPH".into()
}
fn index(value: &Value, size: usize) -> Result<usize, String> {
    let n = value.as_u64().ok_or_else(invalid)?;
    if n >= size as u64 {
        return Err(invalid());
    }
    Ok(n as usize)
}
fn edge(value: &Value, size: usize, absent: bool) -> Result<Option<usize>, String> {
    if absent && value.as_i64() == Some(-1) {
        Ok(None)
    } else {
        index(value, size).map(Some)
    }
}

/// Borrowed, validated wire graphs. Traversal returns node handles instead of expanding cycles.
pub struct JavaTypeGraphs<'a> {
    graphs: Vec<Graph<'a>>,
}
struct Graph<'a> {
    nodes: &'a [Value],
    roots: &'a [Value],
}
#[derive(Clone, Copy)]
pub struct JavaTypeNode<'a> {
    nodes: &'a [Value],
    index: usize,
}
impl<'a> JavaTypeNode<'a> {
    pub fn attributes(&self) -> &'a Map<String, Value> {
        self.nodes[self.index].as_object().expect("validated node")
    }
    pub fn reference(&self) -> usize {
        self.index
    }
    pub fn child(&self, field: SingleEdge) -> Result<Option<Self>, String> {
        let field = field.as_str();
        self.attributes()
            .get(field)
            .map(|value| {
                edge(value, self.nodes.len(), true).map(|index| {
                    index.map(|index| Self {
                        nodes: self.nodes,
                        index,
                    })
                })
            })
            .unwrap_or(Ok(None))
    }
    pub fn children(&self, field: ManyEdge) -> Result<Vec<Self>, String> {
        let field = field.as_str();
        self.attributes()
            .get(field)
            .map(|value| {
                value
                    .as_array()
                    .ok_or_else(invalid)?
                    .iter()
                    .map(|value| {
                        index(value, self.nodes.len()).map(|index| Self {
                            nodes: self.nodes,
                            index,
                        })
                    })
                    .collect()
            })
            .unwrap_or(Ok(Vec::new()))
    }
}
impl<'a> JavaTypeGraphs<'a> {
    pub fn new(attributes: &'a BTreeMap<String, Value>) -> Result<Self, String> {
        let mut graphs = Vec::new();
        if let Some(table) = attributes.get(TABLE) {
            let table = table.as_array().ok_or_else(invalid)?;
            if table.len() > MAX_NODES {
                return Err(invalid());
            }
            for payload in table {
                let payload = payload.as_object().ok_or_else(invalid)?;
                if payload.get("version").and_then(Value::as_u64) != Some(3) {
                    return Err(invalid());
                }
                let nodes = payload
                    .get("nodes")
                    .and_then(Value::as_array)
                    .ok_or_else(invalid)?;
                let roots = payload
                    .get("roots")
                    .and_then(Value::as_array)
                    .ok_or_else(invalid)?;
                if nodes.len() > MAX_NODES || roots.len() > MAX_NODES {
                    return Err(invalid());
                }
                for root in roots {
                    index(root, nodes.len())?;
                }
                validate_nodes(nodes)?;
                graphs.push(Graph { nodes, roots });
            }
        }
        let result = Self { graphs };
        for (key, value) in attributes {
            if key != TABLE {
                result.validate_references(value)?;
            }
        }
        Ok(result)
    }

    pub fn resolve(&self, value: &Value) -> Result<JavaTypeNode<'a>, String> {
        let value = value.as_object().ok_or_else(invalid)?;
        if value.len() != 1 {
            return Err(invalid());
        }
        let pair = value
            .get("$java_type")
            .and_then(Value::as_array)
            .ok_or_else(invalid)?;
        if pair.len() != 2 {
            return Err(invalid());
        }
        let graph = &self.graphs[index(&pair[0], self.graphs.len())?];
        let slot = index(&pair[1], graph.roots.len())?;
        Ok(JavaTypeNode {
            nodes: graph.nodes,
            index: index(&graph.roots[slot], graph.nodes.len())?,
        })
    }

    fn validate_references(&self, root: &Value) -> Result<(), String> {
        let mut pending = vec![(root, 0)];
        while let Some((value, depth)) = pending.pop() {
            if depth > 128 {
                return Err(invalid());
            }
            match value {
                Value::Object(object) if object.contains_key("$java_type") => {
                    self.resolve(value)?;
                }
                Value::Object(object) if object.contains_key("$java_object") => {
                    if object.len() != 1 {
                        return Err(invalid());
                    }
                    let entries = object["$java_object"].as_array().ok_or_else(invalid)?;
                    let mut keys = BTreeSet::new();
                    for entry in entries {
                        let pair = entry.as_array().ok_or_else(invalid)?;
                        if pair.len() != 2 || !keys.insert(pair[0].as_str().ok_or_else(invalid)?) {
                            return Err(invalid());
                        }
                        pending.push((&pair[1], depth + 1));
                    }
                }
                Value::Object(object) => {
                    pending.extend(object.values().map(|value| (value, depth + 1)));
                }
                Value::Array(array) => {
                    pending.extend(array.iter().map(|value| (value, depth + 1)));
                }
                _ => {}
            }
        }
        Ok(())
    }
}

fn validate_nodes(nodes: &[Value]) -> Result<(), String> {
    for node in nodes {
        let node = node.as_object().ok_or_else(invalid)?;
        let kind = node
            .get("kind")
            .and_then(Value::as_str)
            .ok_or_else(invalid)?;
        let complete = node
            .get("complete")
            .and_then(Value::as_bool)
            .ok_or_else(invalid)?;
        if node.get("version").and_then(Value::as_u64) != Some(3) {
            return Err(invalid());
        }
        let allowed: &[&str] = match kind {
            "ARRAY" => &["component"],
            "DECLARED" | "ERROR" => &["enclosing", "arguments"],
            "TYPEVAR" => &["upper", "lower"],
            "WILDCARD" => &["extends", "super"],
            "INTERSECTION" => &["bounds"],
            "UNION" => &["alternatives"],
            "EXECUTABLE" => &[
                "receiver",
                "return",
                "parameters",
                "throws",
                "type_variables",
            ],
            "BOOLEAN" | "BYTE" | "SHORT" | "INT" | "LONG" | "CHAR" | "FLOAT" | "DOUBLE"
            | "VOID" | "NONE" | "NULL" | "OTHER" | "UNKNOWN" | "POLY" => &[],
            _ => return Err(invalid()),
        };
        for (key, value) in node {
            let mut children = Vec::new();
            if SINGLE.contains(&key.as_str()) {
                if !allowed.contains(&key.as_str()) {
                    return Err(invalid());
                }
                children.extend(edge(value, nodes.len(), true)?);
            } else if MANY.contains(&key.as_str()) {
                if !allowed.contains(&key.as_str()) {
                    return Err(invalid());
                }
                for value in value.as_array().ok_or_else(invalid)? {
                    children.push(index(value, nodes.len())?);
                }
            }
            if complete
                && children
                    .iter()
                    .any(|&id| nodes[id].get("complete").and_then(Value::as_bool) != Some(true))
            {
                return Err(invalid());
            }
        }
        if complete {
            let required: &[&str] = match kind {
                "ERROR" | "UNKNOWN" => return Err(invalid()),
                "ARRAY" => &["component"],
                "TYPEVAR" => &["upper", "lower"],
                "WILDCARD" => &["extends", "super"],
                "DECLARED" => {
                    if node
                        .get("element")
                        .and_then(Value::as_str)
                        .is_none_or(str::is_empty)
                    {
                        return Err(invalid());
                    }
                    &["arguments"]
                }
                "INTERSECTION" => &["bounds"],
                "UNION" => &["alternatives"],
                "EXECUTABLE" => &["return", "parameters", "throws", "type_variables"],
                _ => &[],
            };
            for &key in required {
                let value = node.get(key).ok_or_else(invalid)?;
                if SINGLE.contains(&key) && kind != "WILDCARD" {
                    index(value, nodes.len())?;
                }
            }
        }
    }
    Ok(())
}

pub(crate) fn validate(attributes: &BTreeMap<String, Value>, required: bool) -> Result<(), String> {
    if required || attributes.contains_key(TABLE) {
        JavaTypeGraphs::new(attributes)?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn recursive() -> BTreeMap<String, Value> {
        serde_json::from_value(json!({
            "declared":{"$java_type":[0,0]},
            "java_type_graphs":[{"version":3,"roots":[0],"nodes":[
                {"version":3,"kind":"TYPEVAR","complete":true,"name":"T","upper":1,"lower":2},
                {"version":3,"kind":"DECLARED","complete":true,"element":"Comparable","arguments":[0]},
                {"version":3,"kind":"NULL","complete":true}
            ]}]
        })).unwrap()
    }
    #[test]
    fn traverse_cycles_without_expansion_and_keep_literal_markers() {
        let mut attrs = recursive();
        attrs.insert(
            "literal".into(),
            json!({"$java_object":[["$java_type",[99,99]]]}),
        );
        let graphs = JavaTypeGraphs::new(&attrs).unwrap();
        let root = graphs.resolve(&attrs["declared"]).unwrap();
        let parent = root.child(SingleEdge::Upper).unwrap().unwrap();
        assert_eq!(parent.attributes()["element"], "Comparable");
        assert_eq!(
            parent.children(ManyEdge::Arguments).unwrap()[0].reference(),
            root.reference()
        );
        assert!("not-an-edge".parse::<SingleEdge>().is_err());
    }
    #[test]
    fn corrupt_references_shapes_completeness_and_versions_are_rejected() {
        for pair in [
            json!([1, 0]),
            json!([0, 1]),
            json!([0, 0.5]),
            json!([-1, 0]),
            json!(["0", 0]),
            json!([0]),
        ] {
            let mut attrs = recursive();
            attrs.insert("declared".into(), json!({"$java_type":pair}));
            assert!(JavaTypeGraphs::new(&attrs).is_err());
        }
        for (pointer, replacement) in [
            ("/0/version", json!(4)),
            ("/0/roots/0", json!(99)),
            ("/0/nodes/0/upper", json!(99)),
            ("/0/nodes/0/upper", json!(0.5)),
            ("/0/nodes/1/complete", json!(false)),
            ("/0/nodes/0/kind", json!("INT")),
            ("/0/nodes/2/version", json!(2)),
        ] {
            let mut attrs = recursive();
            *attrs.get_mut(TABLE).unwrap().pointer_mut(pointer).unwrap() = replacement;
            assert!(JavaTypeGraphs::new(&attrs).is_err(), "{pointer}");
        }
        let mut attrs = recursive();
        attrs.remove(TABLE);
        assert!(JavaTypeGraphs::new(&attrs).is_err());
    }
    #[test]
    fn ten_thousand_deep_types_fit_standard_json_and_bounded_node_navigation() {
        let mut nodes: Vec<Value> = (0..10_000)
            .map(|i| json!({"version":3,"kind":"ARRAY","complete":true,"component":i+1}))
            .collect();
        nodes.push(json!({"version":3,"kind":"INT","complete":true}));
        let encoded = json!({"declared":{"$java_type":[0,0]},"java_type_graphs":[{"version":3,"roots":[0],"nodes":nodes}]});
        let attrs = serde_json::from_slice(&serde_json::to_vec(&encoded).unwrap()).unwrap();
        let graphs = JavaTypeGraphs::new(&attrs).unwrap();
        let mut node = graphs.resolve(&attrs["declared"]).unwrap();
        for _ in 0..10_000 {
            node = node.child(SingleEdge::Component).unwrap().unwrap();
        }
        assert_eq!(node.attributes()["kind"], "INT");
    }
}
