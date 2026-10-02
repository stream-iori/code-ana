//! Decode a native result one fact at a time into an owned archive.
use anyhow::{Result, ensure};
use codeana_contracts::archive::{FactArchive, FactArchiveBuilder};
use codeana_contracts::java::OutputLimits;
use codeana_model::{Fact, FactValidator, Manifest, Owner};
use serde::de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use std::{collections::BTreeSet, fmt, io::Read};

pub struct NativeResult {
    pub mode: String,
    pub replacements: Vec<Owner>,
    pub records: FactArchive,
}
struct ResultSeed<'a>(&'a Manifest, OutputLimits, Option<&'a std::path::Path>);
struct FactsSeed<'a> {
    builder: &'a mut FactArchiveBuilder,
    validator: &'a FactValidator<'a>,
}
impl<'de> DeserializeSeed<'de> for FactsSeed<'_> {
    type Value = ();
    fn deserialize<D: de::Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        d.deserialize_seq(self)
    }
}
impl<'de> Visitor<'de> for FactsSeed<'_> {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("fact array")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<(), A::Error> {
        while let Some(fact) = seq.next_element::<Fact>()? {
            self.validator.validate(&fact).map_err(de::Error::custom)?;
            self.builder.push(&fact).map_err(de::Error::custom)?;
        }
        Ok(())
    }
}
struct OwnersSeed(u64);
impl<'de> DeserializeSeed<'de> for OwnersSeed {
    type Value = Vec<Owner>;
    fn deserialize<D: de::Deserializer<'de>>(self, decoder: D) -> Result<Self::Value, D::Error> {
        decoder.deserialize_seq(self)
    }
}
impl<'de> Visitor<'de> for OwnersSeed {
    type Value = Vec<Owner>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("bounded owner array")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> {
        let mut owners = Vec::new();
        while let Some(owner) = seq.next_element::<Owner>()? {
            if owners.len() as u64 >= self.0 {
                return Err(de::Error::custom("OWNER_BUDGET"));
            }
            owners.push(owner);
        }
        Ok(owners)
    }
}
impl<'de> DeserializeSeed<'de> for ResultSeed<'_> {
    type Value = NativeResult;
    fn deserialize<D: de::Deserializer<'de>>(self, d: D) -> Result<NativeResult, D::Error> {
        d.deserialize_map(self)
    }
}
impl<'de> Visitor<'de> for ResultSeed<'_> {
    type Value = NativeResult;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("native analysis result")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<NativeResult, A::Error> {
        let mut version = None;
        let mut mode = None;
        let mut replacements = None;
        let mut seen = BTreeSet::new();
        let validator = FactValidator::new(self.0);
        let mut records = match self.2 {
            Some(root) => FactArchiveBuilder::new_in(self.1.bytes, self.1.facts, root),
            None => FactArchiveBuilder::new(self.1.bytes, self.1.facts),
        }
        .map_err(de::Error::custom)?;
        while let Some(key) = map.next_key::<String>()? {
            if !seen.insert(key.clone()) {
                return Err(de::Error::custom("DUPLICATE_RESULT_FIELD"));
            }
            match key.as_str() {
                "version" => version = Some(map.next_value::<u32>()?),
                "mode" => mode = Some(map.next_value::<String>()?),
                "replacements" => {
                    replacements = Some(map.next_value_seed(OwnersSeed(self.1.facts))?)
                }
                "facts" => map.next_value_seed(FactsSeed {
                    builder: &mut records,
                    validator: &validator,
                })?,
                _ => {
                    map.next_value::<de::IgnoredAny>()?;
                }
            }
        }
        if version != Some(5) {
            return Err(de::Error::custom("JAVA_MODEL_VERSION_MISMATCH"));
        }
        if !seen.contains("facts") {
            return Err(de::Error::missing_field("facts"));
        }
        let mode = mode.ok_or_else(|| de::Error::missing_field("mode"))?;
        if !matches!(mode.as_str(), "full" | "delta") {
            return Err(de::Error::custom("INVALID_MODE"));
        }
        Ok(NativeResult {
            mode,
            replacements: replacements.ok_or_else(|| de::Error::missing_field("replacements"))?,
            records: records.seal().map_err(de::Error::custom)?,
        })
    }
}
#[cfg(test)]
pub fn read(input: impl Read, manifest: &Manifest) -> Result<NativeResult> {
    read_with_limits(input, manifest, OutputLimits::default())
}
#[cfg(test)]
pub fn read_with_limits(
    input: impl Read,
    manifest: &Manifest,
    limits: OutputLimits,
) -> Result<NativeResult> {
    read_owned(input, manifest, limits, None)
}
pub fn read_owned(
    input: impl Read,
    manifest: &Manifest,
    limits: OutputLimits,
    root: Option<&std::path::Path>,
) -> Result<NativeResult> {
    let mut decoder = serde_json::Deserializer::from_reader(input);
    let result = ResultSeed(manifest, limits, root).deserialize(&mut decoder)?;
    decoder.end()?;
    ensure!(
        result.replacements.len() as u64 <= limits.facts,
        "OWNER_BUDGET"
    );
    Ok(result)
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owner_deserialization_stops_at_configured_budget() {
        let body = br#"{"version":5,"mode":"delta","facts":[],"replacements":[{"world":"w","producer":"p","kind":"file","id":"a"},{"world":"w","producer":"p","kind":"file","id":"b"}]}"#;
        let limits = OutputLimits {
            bytes: 65536,
            facts: 1,
        };
        let error = read_with_limits(body.as_slice(), &Manifest::default(), limits)
            .err()
            .unwrap();
        assert!(error.to_string().contains("OWNER_BUDGET"));
        let allowed = read_with_limits(
            body.as_slice(),
            &Manifest::default(),
            OutputLimits { facts: 2, ..limits },
        )
        .unwrap();
        assert_eq!(allowed.replacements.len(), 2);
    }
    #[test]
    fn streaming_reader_checks_schema_duplicates_and_required_arrays() {
        let m = Manifest::default();
        let r = read(
            br#"{"facts":[],"version":5,"mode":"full","replacements":[],"compiler_sources":1}"#
                .as_slice(),
            &m,
        )
        .unwrap();
        assert_eq!(r.records.count, 0);
        for bad in [
            r#"{"facts":[],"facts":[],"version":5,"mode":"full","replacements":[]}"#,
            r#"{"version":5,"mode":"full","replacements":[]}"#,
            r#"{"facts":[],"version":3,"mode":"full","replacements":[]}"#,
        ] {
            assert!(read(bad.as_bytes(), &m).is_err());
        }
    }
}
