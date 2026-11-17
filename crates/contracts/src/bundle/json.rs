//! SDK JSON admission before typed decoding, including keys in open metadata maps.
use serde::de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use std::{collections::BTreeSet, fmt};

struct Check<'a> {
    nodes: &'a mut usize,
    depth: usize,
}
impl<'de> DeserializeSeed<'de> for Check<'_> {
    type Value = ();
    fn deserialize<D: de::Deserializer<'de>>(self, decoder: D) -> Result<(), D::Error> {
        *self.nodes += 1;
        if *self.nodes > 65536 || self.depth > 128 {
            return Err(de::Error::custom("SDK_JSON_BUDGET"));
        }
        decoder.deserialize_any(self)
    }
}
impl<'de> Visitor<'de> for Check<'_> {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("bounded SDK JSON with unique object keys")
    }
    fn visit_unit<E: de::Error>(self) -> Result<(), E> {
        Ok(())
    }
    fn visit_bool<E: de::Error>(self, _: bool) -> Result<(), E> {
        Ok(())
    }
    fn visit_i64<E: de::Error>(self, _: i64) -> Result<(), E> {
        Ok(())
    }
    fn visit_u64<E: de::Error>(self, _: u64) -> Result<(), E> {
        Ok(())
    }
    fn visit_f64<E: de::Error>(self, _: f64) -> Result<(), E> {
        Ok(())
    }
    fn visit_str<E: de::Error>(self, _: &str) -> Result<(), E> {
        Ok(())
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
        while seq
            .next_element_seed(Check {
                nodes: self.nodes,
                depth: self.depth + 1,
            })?
            .is_some()
        {}
        Ok(())
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<(), A::Error> {
        let mut seen = BTreeSet::new();
        while let Some(key) = map.next_key::<String>()? {
            *self.nodes += 1;
            if *self.nodes > 65536 {
                return Err(de::Error::custom("SDK_JSON_BUDGET"));
            }
            if !seen.insert(key) {
                return Err(de::Error::custom("SDK_MANIFEST_DUPLICATE_FIELD"));
            }
            map.next_value_seed(Check {
                nodes: self.nodes,
                depth: self.depth + 1,
            })?;
        }
        Ok(())
    }
}
pub(super) fn check(bytes: &[u8]) -> serde_json::Result<()> {
    let mut decoder = serde_json::Deserializer::from_slice(bytes);
    Check {
        nodes: &mut 0,
        depth: 1,
    }
    .deserialize(&mut decoder)?;
    decoder.end()
}
