//! Stable fact semantics plus opaque producer extensions. Decode once at the boundary.
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Preserve absent versus explicit JSON null while giving present data a concrete type.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub enum Field<T> {
    #[default]
    Missing,
    Null,
    Value(T),
}
impl<T> Field<T> {
    pub fn is_missing(&self) -> bool {
        matches!(self, Self::Missing)
    }
    pub fn as_ref(&self) -> Option<&T> {
        match self {
            Self::Value(v) => Some(v),
            _ => None,
        }
    }
    pub fn as_mut(&mut self) -> Option<&mut T> {
        match self {
            Self::Value(v) => Some(v),
            _ => None,
        }
    }
}
impl<T> From<T> for Field<T> {
    fn from(value: T) -> Self {
        Self::Value(value)
    }
}
impl<T: Serialize> Serialize for Field<T> {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        match self {
            Self::Value(v) => v.serialize(s),
            _ => s.serialize_none(),
        }
    }
}
impl<'de, T: Deserialize<'de>> Deserialize<'de> for Field<T> {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        Ok(match Option::<T>::deserialize(d)? {
            Some(v) => Self::Value(v),
            None => Self::Null,
        })
    }
}
/// Unknown producer data has no keyed business-access API.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(transparent)]
pub struct OpaqueMetadata(BTreeMap<String, serde_json::Value>);
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SourceOrigin {
    pub path: String,
    pub hash: String,
    pub world: String,
    pub module: String,
    pub scope: String,
    #[serde(flatten)]
    pub extensions: OpaqueMetadata,
}
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct BinaryClass {
    pub access: Option<i64>,
    #[serde(flatten)]
    pub extensions: OpaqueMetadata,
}
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct FactAttributes {
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub meta_annotation: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub source_range: Field<SourceRange>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub element_kind: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub entity_kind: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub modifiers: Field<Vec<String>>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub source_available: Field<bool>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub source_origin: Field<SourceOrigin>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub receiver_static_type: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub binary_class: Field<BinaryClass>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub mechanism: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub reference_mode: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub call_kind: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub via: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub recovery_input: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub operation: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub recovery_phase: Field<u32>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub annotation: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub raw_name: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub resolution: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub resolved_type: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub direct: Field<bool>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub meta_depth: Field<u32>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub values: Field<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub language: Field<String>,
    #[serde(default, skip_serializing_if = "Field::is_missing")]
    pub upstream_producer: Field<String>,
    #[serde(flatten)]
    pub extensions: OpaqueMetadata,
}
impl FactAttributes {
    pub fn from_wire_map(
        value: BTreeMap<String, serde_json::Value>,
    ) -> Result<Self, serde_json::Error> {
        serde_json::from_value(serde_json::to_value(value)?)
    }
    /// Wire-contract validators and fixtures operate on serialized producer data.
    pub fn wire_map(&self) -> BTreeMap<String, serde_json::Value> {
        serde_json::from_value(serde_json::to_value(self).expect("fact attribute encoding"))
            .expect("fact attribute object")
    }
    pub fn set_wire_field(
        &mut self,
        key: String,
        value: serde_json::Value,
    ) -> Option<serde_json::Value> {
        let mut map = self.wire_map();
        let previous = map.insert(key, value);
        *self = Self::from_wire_map(map).expect("valid fixture or producer attributes");
        previous
    }
    pub fn remove_wire_field(&mut self, key: &str) -> Option<serde_json::Value> {
        let mut map = self.wire_map();
        let previous = map.remove(key);
        *self = Self::from_wire_map(map).expect("valid fixture or producer attributes");
        previous
    }
    pub fn has_modifier(&self, name: &str) -> bool {
        self.modifiers
            .as_ref()
            .is_some_and(|items| items.iter().any(|item| item == name))
    }
    pub fn element_kind(&self) -> &str {
        self.element_kind.as_ref().map(String::as_str).unwrap_or("")
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct SourceRange {
    pub begin_line: Option<i64>,
    pub begin_column: Option<i64>,
    pub end_line: Option<i64>,
    pub end_column: Option<i64>,
}

impl FactAttributes {
    pub fn merge_annotation_meta(&mut self, meta: &Self) {
        self.raw_name = meta.raw_name.clone();
        self.language = meta.language.clone();
        self.mechanism = meta.mechanism.clone();
        self.resolution = meta.resolution.clone();
        self.upstream_producer = meta.upstream_producer.clone();
        self.extensions.0.extend(meta.extensions.0.clone());
    }
}

impl FactAttributes {
    pub fn merge_min_annotation_meta(&mut self, other: &Self) {
        fn minimum<T: Ord + Clone>(target: &mut Field<T>, value: &Field<T>) {
            if let Some(value) = value.as_ref()
                && target.as_ref().is_none_or(|old| value < old)
            {
                *target = value.clone().into();
            }
        }
        minimum(&mut self.raw_name, &other.raw_name);
        minimum(&mut self.language, &other.language);
        minimum(&mut self.mechanism, &other.mechanism);
        minimum(&mut self.resolution, &other.resolution);
        minimum(&mut self.upstream_producer, &other.upstream_producer);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn typed_semantics_preserve_extensions_nulls_and_missing_fields() {
        let wire = serde_json::json!({"modifiers":["final"],"source_available":null,"producer_extension":{"x":[1,null]}});
        let attributes: FactAttributes = serde_json::from_value(wire.clone()).unwrap();
        assert!(attributes.has_modifier("final"));
        assert_eq!(attributes.source_available, Field::Null);
        assert!(attributes.source_origin.is_missing());
        assert_eq!(serde_json::to_value(&attributes).unwrap(), wire);
        for malformed in [
            serde_json::json!({"modifiers":[7]}),
            serde_json::json!({"source_available":"false"}),
            serde_json::json!({"recovery_phase":-1}),
        ] {
            assert!(serde_json::from_value::<FactAttributes>(malformed).is_err());
        }
    }
}
