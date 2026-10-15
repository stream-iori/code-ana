//! Consume sealed Anatomist protobuf rows; no Fact IR conversion or global dedup database.
use anyhow::{Context, Result, ensure};
use codeana_contracts::graph_archive::Descriptor;
#[cfg(test)]
use codeana_contracts::graph_archive::GraphArchive;
use codeana_contracts::{
    graph::Row,
    graph_archive::{self, Builder},
    java::OutputLimits,
};
use codeana_model::{Manifest, Owner};
use serde::de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use sha2::{Digest, Sha256};
use std::collections::BTreeSet;
use std::{
    fmt,
    io::{BufReader, Read},
    os::unix::fs::OpenOptionsExt,
    path::Path,
};
struct Envelope {
    mode: String,
    replacements: Vec<Owner>,
    archive: Descriptor,
}
struct EnvelopeSeed(OutputLimits);
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

impl<'de> DeserializeSeed<'de> for EnvelopeSeed {
    type Value = Envelope;
    fn deserialize<D: de::Deserializer<'de>>(self, d: D) -> Result<Envelope, D::Error> {
        d.deserialize_map(self)
    }
}
impl<'de> Visitor<'de> for EnvelopeSeed {
    type Value = Envelope;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("native archive descriptor")
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Envelope, A::Error> {
        let (mut version, mut mode, mut replacements, mut archive) = (None, None, None, None);
        let mut seen = BTreeSet::new();
        while let Some(key) = map.next_key::<String>()? {
            if !seen.insert(key.clone()) {
                return Err(de::Error::custom("DUPLICATE_RESULT_FIELD"));
            }
            match key.as_str() {
                "version" => version = Some(map.next_value::<u32>()?),
                "mode" => mode = Some(map.next_value::<String>()?),
                "replacements" => {
                    replacements = Some(map.next_value_seed(OwnersSeed(self.0.facts))?)
                }
                "graph_archive" => archive = Some(map.next_value::<Descriptor>()?),
                _ => {
                    return Err(de::Error::custom("UNKNOWN_RESULT_FIELD"));
                }
            }
        }
        if version != Some(10) {
            return Err(de::Error::custom("JAVA_MODEL_VERSION_MISMATCH"));
        }
        let mode = mode.ok_or_else(|| de::Error::missing_field("mode"))?;
        if !matches!(mode.as_str(), "full" | "delta") {
            return Err(de::Error::custom("INVALID_MODE"));
        }
        Ok(Envelope {
            mode,
            replacements: replacements.ok_or_else(|| de::Error::missing_field("replacements"))?,
            archive: archive.ok_or_else(|| de::Error::missing_field("graph_archive"))?,
        })
    }
}

fn load_into(
    input: impl Read,
    limits: OutputLimits,
    root: &Path,
    consume: bool,
    mut begin: impl FnMut(&str, &[Owner]) -> Result<()>,
    mut emit: impl FnMut(Row) -> Result<()>,
    check: impl Fn() -> Result<()>,
) -> Result<(String, Vec<Owner>)> {
    let mut decoder = serde_json::Deserializer::from_reader(input.take(64 << 20));
    let envelope = EnvelopeSeed(limits).deserialize(&mut decoder)?;
    decoder.end()?;
    let d = envelope.archive;
    ensure!(
        d.format == "GRA1" && d.count <= limits.facts && d.bytes >= 4 && d.bytes <= limits.bytes,
        codeana_model::DomainError::new(codeana_model::ErrorCode::NativeArchiveBudget, "")
    );
    let root = root.canonicalize()?;
    ensure!(
        d.path.is_absolute() && d.path.canonicalize()?.starts_with(&root),
        codeana_model::DomainError::new(codeana_model::ErrorCode::NativeArchivePath, "")
    );
    let file = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&d.path)?;
    ensure!(
        file.metadata()?.is_file() && file.metadata()?.len() == d.bytes,
        codeana_model::DomainError::new(codeana_model::ErrorCode::NativeArchiveSize, "")
    );
    let mut reader = BufReader::new(file);
    let mut magic = [0; 4];
    reader.read_exact(&mut magic)?;
    ensure!(
        magic == graph_archive::MAGIC,
        codeana_model::DomainError::new(codeana_model::ErrorCode::GraphArchiveFormat, "")
    );
    let mut digest = Sha256::new();
    digest.update(magic);
    begin(&envelope.mode, &envelope.replacements)?;
    for position in 0..d.count {
        if position % 256 == 0 {
            check()?;
        }
        let row = graph_archive::read_row(&mut reader, &mut digest)?.context(
            codeana_model::DomainError::new(codeana_model::ErrorCode::GraphArchiveTruncated, ""),
        )?;
        emit(row)?;
    }
    let mut trailing = [0; 1];
    ensure!(
        reader.read(&mut trailing)? == 0,
        codeana_model::DomainError::new(codeana_model::ErrorCode::NativeArchiveEof, "")
    );
    ensure!(
        format!("{:x}", digest.finalize()) == d.sha256,
        codeana_model::DomainError::new(codeana_model::ErrorCode::NativeArchiveIdentity, "")
    );
    check()?;
    drop(reader);
    if consume {
        std::fs::remove_file(d.path).context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::NativeArchiveRelease,
            "",
        ))?;
    }
    Ok((envelope.mode, envelope.replacements))
}
pub fn read_owned_stream(
    input: impl Read,
    _manifest: &Manifest,
    limits: OutputLimits,
    root: &Path,
    begin: impl FnMut(&str, &[Owner]) -> Result<()>,
    emit: impl FnMut(Row) -> Result<()>,
    check: impl Fn() -> Result<()>,
) -> Result<()> {
    load_into(input, limits, root, true, begin, emit, check)?;
    Ok(())
}
#[cfg(test)]
pub fn read_owned(
    input: impl Read,
    _manifest: &Manifest,
    limits: OutputLimits,
    root: &Path,
) -> Result<graph_archive::GraphArchive> {
    let mut archive = Builder::new(Some(root), limits.bytes, limits.facts)?;
    load_into(
        input,
        limits,
        root,
        true,
        |_, _| Ok(()),
        |r| archive.push(&r),
        || Ok(()),
    )?;
    archive.seal()
}
pub fn probe(bytes: &[u8], root: &Path) -> Result<Vec<u8>> {
    let mut rows = Vec::new();
    let (mode, replacements) = load_into(
        bytes,
        OutputLimits::default(),
        root,
        false,
        |_, _| Ok(()),
        |r| {
            rows.push(r);
            Ok(())
        },
        || Ok(()),
    )?;
    Ok(serde_json::to_vec(
        &serde_json::json!({"version":10,"mode":mode,"replacements":replacements,"rows":rows}),
    )?)
}
pub fn probe_archive(
    bytes: &[u8],
    root: &Path,
    limits: OutputLimits,
    destination: &Path,
) -> Result<String> {
    let mut archive = Builder::new(Some(root), limits.bytes, limits.facts)?;
    let (mode, replacements) = load_into(
        bytes,
        limits,
        root,
        false,
        |_, _| Ok(()),
        |r| archive.push(&r),
        || Ok(()),
    )?;
    let sealed = archive.seal()?;
    std::fs::copy(sealed.path(), destination)?;
    Ok(serde_json::to_string(
        &serde_json::json!({"version":10,"mode":mode,"replacements":replacements,"graph_archive":{"format":"GRA1","path":destination,"count":sealed.count,"bytes":sealed.bytes,"sha256":sealed.checksum}}),
    )?)
}
#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::graph::{Cell, Table, cell::Value};
    fn archive(root: &Path) -> Result<(GraphArchive, serde_json::Value)> {
        let mut builder = Builder::new(Some(root), 1 << 20, 100)?;
        builder.push(&Row {
            world: String::new(),
            table: Table::ProjectMeta as i32,
            cells: vec![
                Cell {
                    value: Some(Value::Text("中文😀".into())),
                },
                Cell { value: None },
            ],
        })?;
        let archive = builder.seal()?;
        let value = serde_json::json!({"version":10,"mode":"full","replacements":[],"graph_archive":{"format":"GRA1","path":archive.path(),"count":archive.count,"bytes":archive.bytes,"sha256":archive.checksum}});
        Ok((archive, value))
    }
    #[test]
    fn late_sha_failure_does_not_delete_or_certify_provisional_rows() -> Result<()> {
        let root = tempfile::tempdir()?;
        let (archive, mut value) = archive(root.path())?;
        value["graph_archive"]["sha256"] = serde_json::json!("wrong");
        let mut observed = 0;
        assert!(
            load_into(
                serde_json::to_vec(&value)?.as_slice(),
                OutputLimits::default(),
                root.path(),
                true,
                |_, _| Ok(()),
                |_| {
                    observed += 1;
                    Ok(())
                },
                || Ok(())
            )
            .is_err()
        );
        assert_eq!(observed, 1);
        assert!(archive.path().exists());
        Ok(())
    }
    #[test]
    fn valid_consumption_releases_only_owned_archive_and_old_format_is_rejected() -> Result<()> {
        let root = tempfile::tempdir()?;
        let (archive, value) = archive(root.path())?;
        let result = read_owned(
            serde_json::to_vec(&value)?.as_slice(),
            &Manifest::default(),
            OutputLimits::default(),
            root.path(),
        )?;
        assert!(!archive.path().exists());
        assert_eq!(result.count, 1);
        let (_, mut old) = self::archive(root.path())?;
        old["version"] = serde_json::json!(8);
        assert!(probe(&serde_json::to_vec(&old)?, root.path()).is_err());
        Ok(())
    }
}
