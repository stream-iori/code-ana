//! Admit the JSON value nodes that a library decoder will materialize, without
//! first allocating an intermediate JSON tree or implementing JSON grammar.
use codeana_model::{AnalysisContext, AnalysisFailure, DomainError};
use serde::Serialize;
use serde::ser::{
    self, SerializeMap, SerializeSeq, SerializeStruct, SerializeStructVariant, SerializeTuple,
    SerializeTupleStruct, SerializeTupleVariant,
};

type Error = serde_json::Error;
struct Admission<'a> {
    budget: &'a AnalysisContext,
    nodes: u64,
    maximum: u64,
}
impl Admission<'_> {
    fn take(&mut self, n: u64) -> Result<(), Error> {
        if n > self.maximum - self.nodes {
            self.budget.fail(AnalysisFailure::Limit {
                resource: "nodes".into(),
                limit: self.maximum,
                used: self.nodes,
                requested: n,
            });
        }
        self.budget.charge(n, n, 0).map_err(ser::Error::custom)?;
        self.nodes += n;
        Ok(())
    }
}
/// `reserved` covers fields added after the admission (e.g. the cumulative seed),
/// while `maximum` is the receiving codec's independently bounded node policy.
pub fn admit_serialization<T: Serialize>(
    value: &T,
    budget: &AnalysisContext,
    reserved: u64,
    maximum: u64,
) -> anyhow::Result<u64> {
    anyhow::ensure!(
        maximum > 0 && maximum <= i64::MAX as u64 && reserved <= maximum,
        "JSON_NODE_POLICY_INVALID"
    );
    budget
        .charge(reserved, reserved, 0)
        .map_err(DomainError::analysis)?;
    let mut counter = Admission {
        budget,
        nodes: reserved,
        maximum,
    };
    if let Err(error) = value.serialize(&mut counter) {
        if let Some(failure) = budget.usage().failure {
            return Err(DomainError::analysis(failure).into());
        }
        return Err(error.into());
    }
    Ok(counter.nodes)
}
macro_rules! scalar {
    ($($name:ident($arg:ident:$ty:ty)),*$(,)?) => {$(fn $name(self,$arg:$ty)->Result<(),Error>{let _=$arg;self.take(1)})*};
}
impl<'a, 'b> ser::Serializer for &'a mut Admission<'b> {
    type Ok = ();
    type Error = Error;
    type SerializeSeq = Self;
    type SerializeTuple = Self;
    type SerializeTupleStruct = Self;
    type SerializeTupleVariant = Self;
    type SerializeMap = Self;
    type SerializeStruct = Self;
    type SerializeStructVariant = Self;
    scalar!(serialize_bool(v:bool),serialize_i8(v:i8),serialize_i16(v:i16),serialize_i32(v:i32),serialize_i64(v:i64),serialize_i128(v:i128),
        serialize_u8(v:u8),serialize_u16(v:u16),serialize_u32(v:u32),serialize_u64(v:u64),serialize_u128(v:u128),serialize_f32(v:f32),serialize_f64(v:f64),serialize_char(v:char),serialize_str(v:&str));
    fn serialize_bytes(self, v: &[u8]) -> Result<(), Error> {
        self.take(1 + v.len() as u64)
    }
    fn serialize_none(self) -> Result<(), Error> {
        self.take(1)
    }
    fn serialize_some<T: ?Sized + Serialize>(self, v: &T) -> Result<(), Error> {
        v.serialize(self)
    }
    fn serialize_unit(self) -> Result<(), Error> {
        self.take(1)
    }
    fn serialize_unit_struct(self, _: &'static str) -> Result<(), Error> {
        self.take(1)
    }
    fn serialize_unit_variant(self, _: &'static str, _: u32, _: &'static str) -> Result<(), Error> {
        self.take(1)
    }
    fn serialize_newtype_struct<T: ?Sized + Serialize>(
        self,
        _: &'static str,
        v: &T,
    ) -> Result<(), Error> {
        v.serialize(self)
    }
    fn serialize_newtype_variant<T: ?Sized + Serialize>(
        self,
        _: &'static str,
        _: u32,
        _: &'static str,
        v: &T,
    ) -> Result<(), Error> {
        self.take(1)?;
        v.serialize(self)
    }
    fn serialize_seq(self, _: Option<usize>) -> Result<Self, Error> {
        self.take(1)?;
        Ok(self)
    }
    fn serialize_tuple(self, _: usize) -> Result<Self, Error> {
        self.take(1)?;
        Ok(self)
    }
    fn serialize_tuple_struct(self, _: &'static str, _: usize) -> Result<Self, Error> {
        self.take(1)?;
        Ok(self)
    }
    fn serialize_tuple_variant(
        self,
        _: &'static str,
        _: u32,
        _: &'static str,
        _: usize,
    ) -> Result<Self, Error> {
        self.take(2)?;
        Ok(self)
    }
    fn serialize_map(self, _: Option<usize>) -> Result<Self, Error> {
        self.take(1)?;
        Ok(self)
    }
    fn serialize_struct(self, _: &'static str, _: usize) -> Result<Self, Error> {
        self.take(1)?;
        Ok(self)
    }
    fn serialize_struct_variant(
        self,
        _: &'static str,
        _: u32,
        _: &'static str,
        _: usize,
    ) -> Result<Self, Error> {
        self.take(2)?;
        Ok(self)
    }
    fn collect_str<T: ?Sized + std::fmt::Display>(self, _: &T) -> Result<(), Error> {
        self.take(1)
    }
}
macro_rules! sequence {
    ($trait:ident,$method:ident) => {
        impl $trait for &mut Admission<'_> {
            type Ok = ();
            type Error = Error;
            fn $method<T: ?Sized + Serialize>(&mut self, v: &T) -> Result<(), Error> {
                v.serialize(&mut **self)
            }
            fn end(self) -> Result<(), Error> {
                Ok(())
            }
        }
    };
}
sequence!(SerializeSeq, serialize_element);
sequence!(SerializeTuple, serialize_element);
sequence!(SerializeTupleStruct, serialize_field);
sequence!(SerializeTupleVariant, serialize_field);
impl SerializeMap for &mut Admission<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_key<T: ?Sized + Serialize>(&mut self, _: &T) -> Result<(), Error> {
        Ok(())
    }
    fn serialize_value<T: ?Sized + Serialize>(&mut self, v: &T) -> Result<(), Error> {
        v.serialize(&mut **self)
    }
    fn end(self) -> Result<(), Error> {
        Ok(())
    }
}
impl SerializeStruct for &mut Admission<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: ?Sized + Serialize>(
        &mut self,
        _: &'static str,
        v: &T,
    ) -> Result<(), Error> {
        v.serialize(&mut **self)
    }
    fn end(self) -> Result<(), Error> {
        Ok(())
    }
}
impl SerializeStructVariant for &mut Admission<'_> {
    type Ok = ();
    type Error = Error;
    fn serialize_field<T: ?Sized + Serialize>(
        &mut self,
        _: &'static str,
        v: &T,
    ) -> Result<(), Error> {
        v.serialize(&mut **self)
    }
    fn end(self) -> Result<(), Error> {
        Ok(())
    }
}

/// Reserve an owned DTO projection before any Clone/collection construction.
/// Scalar/string and container visits are cumulative, including repeated copies.
pub fn admit_owned_copy<T: Serialize>(value: &T, budget: &AnalysisContext) -> anyhow::Result<()> {
    let bytes = codeana_model::retained_size(value)?;
    budget
        .charge(0, 0, bytes.saturating_mul(32).saturating_add(512))
        .map_err(DomainError::analysis)?;
    admit_serialization(value, budget, 0, i64::MAX as u64)?;
    Ok(())
}
pub fn clone_with_budget<T: Serialize + Clone>(
    value: &T,
    budget: &AnalysisContext,
) -> anyhow::Result<T> {
    admit_owned_copy(value, budget)?;
    Ok(value.clone())
}

/// Library-based structural admission before materializing an internal JSON DTO.
/// The first pass retains no model tree. Decoder scratch, keys and the second
/// pass are reserved up front; value nodes and depth use the same task ledger.
pub fn decode_with_budget<T: serde::de::DeserializeOwned>(
    bytes: &[u8],
    budget: &AnalysisContext,
) -> anyhow::Result<T> {
    budget
        .charge(
            bytes.len() as u64,
            0,
            (bytes.len() as u64).saturating_mul(32).saturating_add(1024),
        )
        .map_err(DomainError::analysis)?;
    let mut decoder = serde_json::Deserializer::from_slice(bytes);
    let admitted =
        serde::de::DeserializeSeed::deserialize(DecodeAdmission { budget, depth: 1 }, &mut decoder);
    if let Err(error) = admitted {
        if let Some(failure) = budget.usage().failure {
            return Err(DomainError::analysis(failure).into());
        }
        return Err(error.into());
    }
    decoder.end()?;
    Ok(serde_json::from_slice(bytes)?)
}
#[derive(Clone, Copy)]
struct DecodeAdmission<'a> {
    budget: &'a AnalysisContext,
    depth: u32,
}
impl<'de> serde::de::DeserializeSeed<'de> for DecodeAdmission<'_> {
    type Value = ();
    fn deserialize<D: serde::Deserializer<'de>>(self, decoder: D) -> Result<(), D::Error> {
        self.budget
            .observe_depth(self.depth)
            .map_err(serde::de::Error::custom)?;
        self.budget
            .charge(1, 1, 128)
            .map_err(serde::de::Error::custom)?;
        decoder.deserialize_any(self)
    }
}
impl<'de> serde::de::Visitor<'de> for DecodeAdmission<'_> {
    type Value = ();
    fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("JSON value")
    }
    fn visit_bool<E: serde::de::Error>(self, _: bool) -> Result<(), E> {
        Ok(())
    }
    fn visit_i64<E: serde::de::Error>(self, _: i64) -> Result<(), E> {
        Ok(())
    }
    fn visit_u64<E: serde::de::Error>(self, _: u64) -> Result<(), E> {
        Ok(())
    }
    fn visit_f64<E: serde::de::Error>(self, _: f64) -> Result<(), E> {
        Ok(())
    }
    fn visit_str<E: serde::de::Error>(self, _: &str) -> Result<(), E> {
        Ok(())
    }
    fn visit_unit<E: serde::de::Error>(self) -> Result<(), E> {
        Ok(())
    }
    fn visit_none<E: serde::de::Error>(self) -> Result<(), E> {
        Ok(())
    }
    fn visit_seq<A: serde::de::SeqAccess<'de>>(self, mut sequence: A) -> Result<(), A::Error> {
        let child = Self {
            depth: self.depth + 1,
            ..self
        };
        while sequence.next_element_seed(child)?.is_some() {}
        Ok(())
    }
    fn visit_map<A: serde::de::MapAccess<'de>>(self, mut map: A) -> Result<(), A::Error> {
        while map.next_key::<serde::de::IgnoredAny>()?.is_some() {
            self.budget
                .charge(1, 0, 64)
                .map_err(serde::de::Error::custom)?;
            map.next_value_seed(Self {
                depth: self.depth + 1,
                ..self
            })?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::AnalysisLimits;
    fn count(v: &serde_json::Value) -> u64 {
        1 + match v {
            serde_json::Value::Array(a) => a.iter().map(count).sum(),
            serde_json::Value::Object(m) => m.values().map(count).sum(),
            _ => 0,
        }
    }
    #[test]
    fn counts_the_actual_serde_json_representation_without_a_model_tree() {
        #[derive(Serialize)]
        enum Item {
            Unit,
            New(u32),
            Tuple(bool, String),
            Struct { x: Option<u32> },
        }
        let value = vec![
            Item::Unit,
            Item::New(7),
            Item::Tuple(true, "x".into()),
            Item::Struct { x: None },
        ];
        let budget = AnalysisContext::new(AnalysisLimits::default(), None).unwrap();
        let nodes = admit_serialization(&value, &budget, 16, 1000000).unwrap();
        let decoded: serde_json::Value =
            serde_json::from_slice(&serde_json::to_vec(&value).unwrap()).unwrap();
        assert_eq!(nodes, 16 + count(&decoded));
        assert_eq!(budget.usage().nodes, nodes);
        assert_eq!(budget.usage().payload_bytes, 0);
    }
    #[test]
    fn codec_node_limit_fails_before_decoder_allocation_and_is_sticky() {
        let budget = AnalysisContext::new(AnalysisLimits::default(), None).unwrap();
        assert!(admit_serialization(&vec![1, 2, 3], &budget, 0, 3).is_err());
        assert_eq!(budget.usage().nodes, 3);
        assert!(matches!(
            budget.usage().failure,
            Some(AnalysisFailure::Limit {
                limit: 3,
                requested: 1,
                ..
            })
        ));
        assert!(admit_serialization(&0, &budget, 0, 100).is_err());
        assert_eq!(budget.usage().nodes, 3);
    }
    #[test]
    fn node_reservation_cannot_exceed_the_shared_task_grant() {
        let budget = AnalysisContext::new(
            AnalysisLimits {
                max_nodes: 2,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(admit_serialization(&vec![1, 2], &budget, 0, 1000000).is_err());
        assert!(matches!(
            budget.usage().failure,
            Some(AnalysisFailure::Limit {
                limit: 2,
                requested: 1,
                ..
            })
        ));
    }
    #[test]
    fn dto_decode_admits_before_the_typed_deserializer_runs() {
        #[derive(Debug)]
        struct Never;
        impl<'de> serde::Deserialize<'de> for Never {
            fn deserialize<D: serde::Deserializer<'de>>(_: D) -> Result<Self, D::Error> {
                panic!("DTO allocated before admission")
            }
        }
        let budget = AnalysisContext::new(
            AnalysisLimits {
                max_nodes: 1,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(decode_with_budget::<Never>(b"[1,2]", &budget).is_err());
        assert_eq!(budget.usage().nodes, 1);
        assert!(budget.usage().failure.is_some());
    }
    #[test]
    fn dto_decode_preserves_library_values_and_rejects_trailing_input() {
        let bytes = br#"{"a":[true,null,"escaped\ntext",-7,1.25]}"#;
        let budget = AnalysisContext::new(Default::default(), None).unwrap();
        let decoded: serde_json::Value = decode_with_budget(bytes, &budget).unwrap();
        assert_eq!(
            decoded,
            serde_json::from_slice::<serde_json::Value>(bytes).unwrap()
        );
        assert_eq!(budget.usage().nodes, count(&decoded));
        assert!(decode_with_budget::<serde_json::Value>(b"[] trailing", &budget).is_err());
    }
    #[test]
    fn dto_decode_depth_denial_is_sticky_across_following_documents() {
        let budget = AnalysisContext::new(
            AnalysisLimits {
                max_depth: 2,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(decode_with_budget::<serde_json::Value>(b"[[[1]]]", &budget).is_err());
        assert!(
            matches!(budget.usage().failure,Some(AnalysisFailure::Limit{ref resource,..}) if resource=="depth")
        );
        assert!(decode_with_budget::<serde_json::Value>(b"0", &budget).is_err());
    }
    #[test]
    fn owned_copy_denial_precedes_clone_and_does_not_reset_usage() {
        #[derive(Serialize)]
        struct NeverClone {
            items: Vec<u32>,
        }
        impl Clone for NeverClone {
            fn clone(&self) -> Self {
                panic!("cloned before admission")
            }
        }
        let budget = AnalysisContext::new(
            AnalysisLimits {
                max_nodes: 2,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(clone_with_budget(&NeverClone { items: vec![1, 2] }, &budget).is_err());
        assert_eq!(budget.usage().nodes, 2);
        assert!(budget.usage().failure.is_some());
    }
}
