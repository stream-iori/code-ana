//! Consume a sealed native CAF1 archive without a whole-project JSON expansion.
use anyhow::{Context, Result, bail, ensure};
use codeana_contracts::archive::{FactArchive, FactArchiveBuilder};
use codeana_contracts::java::OutputLimits;
use codeana_contracts::performance::{Metric, measure};
use codeana_model::{Fact, FactValidator, Manifest, Owner};
use codeana_storage::fact_dedup::{FactDeduplicator, Observation};
use serde::{
    Deserialize,
    de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor},
};
use sha2::{Digest, Sha256};
use std::os::unix::fs::{FileExt, OpenOptionsExt};
use std::{
    collections::BTreeSet,
    fmt,
    fs::File,
    io::{BufReader, Read},
    path::{Path, PathBuf},
};

pub struct NativeResult {
    pub mode: String,
    pub replacements: Vec<Owner>,
    pub records: FactArchive,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Descriptor {
    format: String,
    path: PathBuf,
    count: u64,
    bytes: u64,
    sha256: String,
}
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
                "facts_archive" => archive = Some(map.next_value::<Descriptor>()?),
                _ => {
                    return Err(de::Error::custom("UNKNOWN_RESULT_FIELD"));
                }
            }
        }
        if version != Some(8) {
            return Err(de::Error::custom("JAVA_MODEL_VERSION_MISMATCH"));
        }
        let mode = mode.ok_or_else(|| de::Error::missing_field("mode"))?;
        if !matches!(mode.as_str(), "full" | "delta") {
            return Err(de::Error::custom("INVALID_MODE"));
        }
        Ok(Envelope {
            mode,
            replacements: replacements.ok_or_else(|| de::Error::missing_field("replacements"))?,
            archive: archive.ok_or_else(|| de::Error::missing_field("facts_archive"))?,
        })
    }
}

pub fn read_owned(
    input: impl Read,
    manifest: &Manifest,
    limits: OutputLimits,
    root: Option<&Path>,
) -> Result<NativeResult> {
    let root = root.context("NATIVE_ARCHIVE_ROOT_REQUIRED")?;
    load(input, Some(manifest), limits, root, true)
}

fn load(
    input: impl Read,
    manifest: Option<&Manifest>,
    limits: OutputLimits,
    root: &Path,
    consume: bool,
) -> Result<NativeResult> {
    let mut decoder = serde_json::Deserializer::from_reader(input.take(64 << 20));
    let envelope = measure(Metric::DescriptorDecode, 0, false, || {
        EnvelopeSeed(limits).deserialize(&mut decoder)
    })?;
    decoder.end()?;
    let descriptor = envelope.archive;
    ensure!(
        descriptor.format == "CAF1"
            && descriptor.count <= limits.facts
            && descriptor.bytes >= 4
            && descriptor.bytes <= limits.bytes,
        "NATIVE_ARCHIVE_BUDGET"
    );
    let root = root.canonicalize()?;
    ensure!(
        descriptor.path.is_absolute() && descriptor.path.canonicalize()?.starts_with(&root),
        "NATIVE_ARCHIVE_PATH"
    );
    let file = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&descriptor.path)?;
    ensure!(
        file.metadata()?.is_file() && file.metadata()?.len() == descriptor.bytes,
        "NATIVE_ARCHIVE_SIZE"
    );
    let mut input = BufReader::new(&file);
    let mut digest = Sha256::new();
    let mut magic = [0u8; 4];
    input.read_exact(&mut magic)?;
    ensure!(magic == 0x43414631u32.to_be_bytes(), "FACT_ARCHIVE_VERSION");
    digest.update(magic);
    let mut builder = FactArchiveBuilder::new_in(limits.bytes, limits.facts, &root)?;
    let mut identities = FactDeduplicator::new(&root)?;
    let validator = manifest.map(FactValidator::new);
    let mut offset = 4u64;
    for _ in 0..descriptor.count {
        let mut length = [0u8; 4];
        input.read_exact(&mut length)?;
        digest.update(length);
        let size = u32::from_be_bytes(length) as usize;
        ensure!(
            size > 0 && size <= 8 << 20 && offset + 4 + size as u64 <= descriptor.bytes,
            "FACT_FRAME_BUDGET"
        );
        let mut bytes = vec![0u8; size];
        measure(Metric::RawRead, size as u64, true, || {
            input.read_exact(&mut bytes)
        })?;
        measure(Metric::RawDigest, bytes.len() as u64, true, || {
            digest.update(&bytes)
        });
        let fact: Fact = measure(Metric::RawDecode, bytes.len() as u64, true, || {
            serde_json::from_slice(&bytes)
        })?;
        if let Some(validator) = &validator
            && let Err(error) = measure(Metric::RawValidate, 0, true, || validator.validate(&fact))
        {
            let evidence = serde_json::json!({"error":error,"offset":offset,"fact":fact});
            std::fs::write(
                root.join("invalid-fact.json"),
                serde_json::to_vec(&evidence)?,
            )
            .with_context(|| format!("{error}: {} (save rejected fact)", fact.id))?;
            bail!("{error}: {}", fact.id);
        }
        let identity = measure(Metric::RawIdentityHash, bytes.len() as u64, true, || {
            codeana_model::hash(&bytes)
        });
        match measure(Metric::RawDedup, bytes.len() as u64, true, || {
            identities.observe(&fact.id, &identity, offset)
        })? {
            Observation::New => {
                measure(Metric::DedupNew, 0, true, || ());
                builder.push(&fact)?;
            }
            Observation::Same => {
                measure(Metric::DedupSame, 0, true, || ());
            }
            Observation::Conflict { first_offset } => {
                let first = row_at(&file, first_offset)?;
                let evidence = serde_json::json!({"id":fact.id,"first":serde_json::from_slice::<serde_json::Value>(&first)?,
                    "second":serde_json::from_slice::<serde_json::Value>(&bytes)?});
                std::fs::write(
                    root.join("fact-conflict.json"),
                    serde_json::to_vec(&evidence)?,
                )?;
                bail!("CONFLICTING_FACT_ID: {}", fact.id);
            }
        }
        offset += 4 + size as u64;
    }
    let mut trailing = [0u8; 1];
    ensure!(
        offset == descriptor.bytes && input.read(&mut trailing)? == 0,
        "NATIVE_ARCHIVE_EOF"
    );
    ensure!(
        format!("{:x}", digest.finalize()) == descriptor.sha256,
        "NATIVE_ARCHIVE_IDENTITY"
    );
    let records = measure(Metric::RawSeal, descriptor.bytes, false, || builder.seal())?;
    if consume {
        // The native session has closed its exported CAF1. Its validated Rust
        // replacement owns delivery now; retaining both through staging doubles
        // task disk usage. Failed validation keeps the original for diagnosis.
        drop(input);
        drop(file);
        std::fs::remove_file(&descriptor.path).context("NATIVE_ARCHIVE_RELEASE")?;
    }
    Ok(NativeResult {
        mode: envelope.mode,
        replacements: envelope.replacements,
        records,
    })
}
fn row_at(file: &File, offset: u64) -> Result<Vec<u8>> {
    let mut length = [0u8; 4];
    file.read_exact_at(&mut length, offset)?;
    let size = u32::from_be_bytes(length) as usize;
    ensure!(size <= 8 << 20, "FACT_FRAME_BUDGET");
    let mut bytes = vec![0u8; size];
    file.read_exact_at(&mut bytes, offset + 4)?;
    Ok(bytes)
}

/// The diagnostic CLI keeps its bounded fact-array output; production consumes CAF1.
pub fn probe(input: &[u8], root: &Path) -> Result<Vec<u8>> {
    let limits = OutputLimits {
        bytes: codeana_model::MAX_RESULT as u64,
        facts: 1_000_000,
    };
    let result = load(input, None, limits, root, false)?;
    let facts = result.records.iter()?.collect::<Result<Vec<_>>>()?;
    let output = serde_json::to_vec(&serde_json::json!({"version":8,"mode":result.mode,
        "replacements":result.replacements,"facts":facts}))?;
    ensure!(
        output.len() <= codeana_model::MAX_RESULT,
        "NATIVE_PROBE_BUDGET"
    );
    Ok(output)
}

pub fn probe_archive(
    input: &[u8],
    root: &Path,
    limits: OutputLimits,
    destination: &Path,
) -> Result<serde_json::Value> {
    let result = load(input, None, limits, root, false)?;
    let mut source = File::open(result.records.path())?;
    let mut target = std::fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(destination)?;
    std::io::copy(&mut source, &mut target)?;
    target.sync_all()?;
    Ok(
        serde_json::json!({"version":8,"mode":result.mode,"replacements":result.replacements,
        "facts_archive":{"format":"CAF1","path":destination,"count":result.records.count,
            "bytes":result.records.bytes,"sha256":result.records.file_hash()?}}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    fn fixture(root: &Path, conflict: bool) -> Result<(serde_json::Value, FactArchive)> {
        let first: Fact = serde_json::from_value(serde_json::json!({
            "id":"external","kind":"external_symbol","owner":{"world":"w","producer":"p","kind":"external","id":"external"},
            "origin":"javac","name":"A"}))?;
        let mut builder = FactArchiveBuilder::new_in(65536, 10, root)?;
        builder.push(&first)?;
        let mut second = first;
        if conflict {
            second.name = "B".into();
        }
        builder.push(&second)?;
        let archive = builder.seal()?;
        let envelope = serde_json::json!({"version":8,"mode":"full","replacements":[],
            "facts_archive":{"format":"CAF1","path":archive.path(),"count":archive.count,
                "bytes":archive.bytes,"sha256":archive.file_hash()?}});
        Ok((envelope, archive))
    }
    fn read_value(value: &serde_json::Value, root: &Path) -> Result<NativeResult> {
        read_owned(
            serde_json::to_vec(value)?.as_slice(),
            &Manifest::default(),
            OutputLimits {
                bytes: 65536,
                facts: 10,
            },
            Some(root),
        )
    }
    #[test]
    fn archive_dedup_preserves_first_and_conflict_evidence() -> Result<()> {
        let root = tempfile::tempdir()?;
        let (value, _archive) = fixture(root.path(), false)?;
        let result = read_value(&value, root.path())?;
        assert_eq!(result.records.count, 1);
        assert_eq!(result.records.iter()?.next().unwrap()?.name, "A");
        let (value, _archive) = fixture(root.path(), true)?;
        assert!(
            read_value(&value, root.path())
                .err()
                .unwrap()
                .to_string()
                .contains("CONFLICTING_FACT_ID")
        );
        let evidence: serde_json::Value =
            serde_json::from_slice(&std::fs::read(root.path().join("fact-conflict.json"))?)?;
        assert_eq!(evidence["first"]["name"], "A");
        assert_eq!(evidence["second"]["name"], "B");
        Ok(())
    }
    #[test]
    fn owned_archive_releases_only_after_validation_and_sealing() -> Result<()> {
        let root = tempfile::tempdir()?;
        let (value, archive) = fixture(root.path(), false)?;
        let mut invalid = value.clone();
        invalid["facts_archive"]["sha256"] = serde_json::json!("invalid");
        assert!(read_value(&invalid, root.path()).is_err());
        assert!(archive.path().is_file());
        // Diagnostic reads borrow the native export.
        let borrowed = load(
            serde_json::to_vec(&value)?.as_slice(),
            None,
            OutputLimits {
                bytes: 65536,
                facts: 10,
            },
            root.path(),
            false,
        )?;
        assert!(archive.path().is_file());
        drop(borrowed);
        let result = read_value(&value, root.path())?;
        assert!(!archive.path().exists());
        assert_eq!(result.records.iter()?.collect::<Result<Vec<_>>>()?.len(), 1);
        Ok(())
    }
    #[test]
    fn archive_rejects_identity_framing_paths_and_old_versions() -> Result<()> {
        let root = tempfile::tempdir()?;
        let (value, archive) = fixture(root.path(), false)?;
        for (field, replacement) in [
            ("sha256", serde_json::json!("bad")),
            ("count", serde_json::json!(1)),
            ("bytes", serde_json::json!(archive.bytes - 1)),
            ("count", serde_json::json!(11)),
        ] {
            let mut bad = value.clone();
            bad["facts_archive"][field] = replacement;
            assert!(read_value(&bad, root.path()).is_err(), "{field}");
        }
        let mut bad = value.clone();
        bad["version"] = serde_json::json!(7);
        assert!(read_value(&bad, root.path()).is_err());
        let outside = tempfile::tempdir()?;
        assert!(read_value(&value, outside.path()).is_err());
        let link = root.path().join("link.caf");
        std::os::unix::fs::symlink(archive.path(), &link)?;
        let mut bad = value.clone();
        bad["facts_archive"]["path"] = serde_json::json!(link);
        assert!(read_value(&bad, root.path()).is_err());
        Ok(())
    }
    #[test]
    fn owners_are_bounded_before_archive_access() {
        let body = br#"{"version":8,"mode":"delta","replacements":[{"world":"w","producer":"p","kind":"file","id":"a"},{"world":"w","producer":"p","kind":"file","id":"b"}]}"#;
        let root = tempfile::tempdir().unwrap();
        let error = read_owned(
            body.as_slice(),
            &Manifest::default(),
            OutputLimits {
                bytes: 65536,
                facts: 1,
            },
            Some(root.path()),
        )
        .err()
        .unwrap();
        assert!(error.to_string().contains("OWNER_BUDGET"));
    }
    #[test]
    fn rejected_fact_keeps_its_identity_and_raw_evidence() -> Result<()> {
        let root = tempfile::tempdir()?;
        let fact: Fact = serde_json::from_value(serde_json::json!({
            "id":"invalid-bean","kind":"facet","owner":{"world":"w","producer":"p","kind":"context","id":"ctx"},
            "origin":"framework_model","relation":"java.bean","attributes":{"xml_configuration_model":{"version":2}}
        }))?;
        let mut builder = FactArchiveBuilder::new_in(65536, 10, root.path())?;
        builder.push(&fact)?;
        let archive = builder.seal()?;
        let envelope = serde_json::json!({"version":8,"mode":"full","replacements":[],
            "facts_archive":{"format":"CAF1","path":archive.path(),"count":archive.count,
                "bytes":archive.bytes,"sha256":archive.file_hash()?}});
        let error = read_value(&envelope, root.path()).err().unwrap();
        assert!(
            error
                .to_string()
                .contains("INVALID_JAVA_XML_CONFIGURATION: invalid-bean")
        );
        let evidence: serde_json::Value =
            serde_json::from_slice(&std::fs::read(root.path().join("invalid-fact.json"))?)?;
        assert_eq!(evidence["fact"]["id"], fact.id);
        assert_eq!(evidence["offset"], 4);
        Ok(())
    }
}
