//! Opaque derived artifacts: bounded persistent storage and isolated task copies.
//! Producers own keys and semantic validation; this store never interprets language models.
use anyhow::{Context, Result, bail, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
#[cfg(unix)]
use std::os::unix::fs::OpenOptionsExt;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    time::{Duration, Instant, SystemTime},
};
use tempfile::TempDir;

const FORMAT: u32 = 1;
const MAX_MANIFEST: u64 = 64 * 1024;
const MAX_ENTRIES: usize = 100_000;
const MAX_FILES: usize = 16;

#[derive(Clone, Copy)]
pub struct DerivedCacheLimits {
    pub disk_bytes: u64,
    pub pin_bytes: u64,
    pub entry_bytes: u64,
}
impl Default for DerivedCacheLimits {
    fn default() -> Self {
        Self {
            disk_bytes: 256 << 20,
            pin_bytes: 128 << 20,
            entry_bytes: 192 << 20,
        }
    }
}
#[derive(Default, Clone, Serialize)]
pub struct DerivedCacheStats {
    pub imported: usize,
    pub imported_bytes: u64,
    pub published: usize,
    pub evicted: usize,
    pub corrupt: usize,
}
#[derive(Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct ArtifactFile {
    bytes: u64,
    sha256: String,
}
#[derive(Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
struct Receipt {
    version: u32,
    namespace: String,
    key: String,
    files: BTreeMap<String, ArtifactFile>,
}
struct Entry {
    path: PathBuf,
    receipt: Receipt,
    bytes: u64,
    accessed: SystemTime,
}

/// Each session copies verified bytes under the supervisor-owned scratch root. No hard links
/// or borrowed persistent paths can be changed by another process or by cache eviction.
pub struct DerivedCacheSession {
    root: PathBuf,
    task: TempDir,
    namespaces: BTreeSet<String>,
    limits: DerivedCacheLimits,
    stats: DerivedCacheStats,
}
impl DerivedCacheSession {
    pub fn open(
        root: &Path,
        scratch: &Path,
        namespaces: &[&str],
        limits: DerivedCacheLimits,
    ) -> Result<Self> {
        ensure!(
            limits.disk_bytes > 0
                && limits.disk_bytes <= 256 << 20
                && limits.pin_bytes > 0
                && limits.pin_bytes <= 128 << 20
                && limits.entry_bytes > 0
                && limits.entry_bytes <= 192 << 20,
            "DERIVED_CACHE_LIMITS"
        );
        let namespaces: BTreeSet<_> = namespaces.iter().map(|s| (*s).to_owned()).collect();
        ensure!(
            !namespaces.is_empty()
                && namespaces.len() <= 16
                && namespaces.iter().all(|s| namespace(s)),
            "DERIVED_CACHE_NAMESPACE"
        );
        let parent = root.parent().context("DERIVED_CACHE_PARENT")?;
        fs::create_dir_all(parent)?;
        // The caller selects the parent. The actual store and every artifact must be link-free.
        let root = parent
            .canonicalize()?
            .join(root.file_name().context("DERIVED_CACHE_ROOT")?);
        no_link(&root)?;
        fs::create_dir_all(&root)?;
        ensure!(root.is_dir(), "DERIVED_CACHE_ROOT");
        let task = tempfile::Builder::new()
            .prefix("derived-cache-")
            .tempdir_in(scratch.canonicalize()?)?;
        let mut session = Self {
            root,
            task,
            namespaces,
            limits,
            stats: DerivedCacheStats::default(),
        };
        for name in &session.namespaces {
            fs::create_dir(session.task.path().join(name))?;
        }
        {
            let _lock = session.lock()?;
            let mut entries = session.entries()?;
            trim(&mut entries, limits.disk_bytes, &mut session.stats)?;
            entries.sort_by(|a, b| {
                b.accessed
                    .cmp(&a.accessed)
                    .then_with(|| a.path.cmp(&b.path))
            });
            for entry in entries {
                if !session.namespaces.contains(&entry.receipt.namespace) {
                    continue;
                }
                let payload_bytes: u64 = entry.receipt.files.values().map(|f| f.bytes).sum();
                if payload_bytes > limits.pin_bytes - session.stats.imported_bytes {
                    continue;
                }
                let target = session
                    .namespace_path(&entry.receipt.namespace)?
                    .join(&entry.receipt.key);
                fs::create_dir(&target)?;
                copy_payload(&entry.path.join("payload"), &target, &entry.receipt)?;
                // Record LRU only after a content-verified, complete pin has been created.
                File::open(entry.path.join("receipt.json"))?
                    .set_times(fs::FileTimes::new().set_modified(SystemTime::now()))?;
                session.stats.imported += 1;
                session.stats.imported_bytes += payload_bytes;
            }
        }
        Ok(session)
    }
    pub fn namespace_path(&self, name: &str) -> Result<PathBuf> {
        ensure!(self.namespaces.contains(name), "DERIVED_CACHE_NAMESPACE");
        Ok(self.task.path().join(name))
    }
    pub fn stats(&self) -> &DerivedCacheStats {
        &self.stats
    }

    /// Call only after a validated publication Ack. Failures are optional cache failures, never
    /// permission to change facts, a published head, or the producer's semantic evidence.
    pub fn commit(&mut self) -> Result<DerivedCacheStats> {
        let _lock = self.lock()?;
        let mut entries = self.entries()?;
        trim(&mut entries, self.limits.disk_bytes, &mut self.stats)?;
        let mut bytes: u64 = entries.iter().map(|e| e.bytes).sum();
        for ns in self.namespaces.clone() {
            let source = self.namespace_path(&ns)?;
            let mut children: Vec<_> = fs::read_dir(&source)?.collect::<std::io::Result<_>>()?;
            children.sort_by_key(|e| e.file_name());
            for child in children {
                let key = child
                    .file_name()
                    .into_string()
                    .map_err(|_| anyhow::anyhow!("DERIVED_CACHE_KEY"))?;
                // Producer-owned unfinished entries are not publishable artifacts.
                if key.starts_with('.') {
                    continue;
                }
                ensure!(valid_key(&key), "DERIVED_CACHE_KEY");
                let record = inventory(&child.path(), &ns, &key, self.limits.entry_bytes)?;
                let encoded = serde_json::to_vec(&record)?;
                ensure!(
                    encoded.len() as u64 <= MAX_MANIFEST,
                    "DERIVED_CACHE_MANIFEST_BUDGET"
                );
                let incoming =
                    record.files.values().map(|f| f.bytes).sum::<u64>() + encoded.len() as u64;
                if incoming > self.limits.disk_bytes {
                    continue;
                }
                let destination = self.root.join(&ns).join(&key);
                if let Some(index) = entries.iter().position(|e| e.path == destination) {
                    if entries[index].receipt == record {
                        continue;
                    }
                    let old = entries.remove(index);
                    fs::remove_dir_all(&old.path)?;
                    bytes -= old.bytes;
                }
                entries.sort_by(|a, b| {
                    a.accessed
                        .cmp(&b.accessed)
                        .then_with(|| a.path.cmp(&b.path))
                });
                while incoming > self.limits.disk_bytes - bytes && !entries.is_empty() {
                    let old = entries.remove(0);
                    fs::remove_dir_all(&old.path)?;
                    bytes -= old.bytes;
                    self.stats.evicted += 1;
                }
                ensure!(
                    incoming <= self.limits.disk_bytes - bytes,
                    "DERIVED_CACHE_DISK_BUDGET"
                );
                let parent = self.root.join(&ns);
                no_link(&parent)?;
                fs::create_dir_all(&parent)?;
                let temp = tempfile::Builder::new()
                    .prefix(".incoming-")
                    .tempdir_in(&parent)?;
                fs::create_dir(temp.path().join("payload"))?;
                copy_payload(&child.path(), &temp.path().join("payload"), &record)?;
                let mut receipt = File::create(temp.path().join("receipt.json"))?;
                receipt.write_all(&encoded)?;
                receipt.sync_all()?;
                File::open(temp.path().join("payload"))?.sync_all()?;
                File::open(temp.path())?.sync_all()?;
                let temporary = temp.keep();
                if let Err(error) = fs::rename(&temporary, &destination) {
                    let _ = fs::remove_dir_all(&temporary);
                    return Err(error.into());
                }
                File::open(&parent)?.sync_all()?;
                entries.push(Entry {
                    path: destination,
                    receipt: record,
                    bytes: incoming,
                    accessed: SystemTime::now(),
                });
                bytes += incoming;
                self.stats.published += 1;
            }
        }
        Ok(self.stats.clone())
    }
    fn lock(&self) -> Result<File> {
        no_link(&self.root)?;
        let path = self.root.join(".lock");
        no_link(&path)?;
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)?;
        let end = Instant::now() + Duration::from_secs(5);
        loop {
            match file.try_lock() {
                Ok(()) => return Ok(file),
                Err(fs::TryLockError::WouldBlock) if Instant::now() < end => {
                    std::thread::sleep(Duration::from_millis(10))
                }
                Err(fs::TryLockError::WouldBlock) => bail!("DERIVED_CACHE_LOCK_TIMEOUT"),
                Err(fs::TryLockError::Error(error)) => return Err(error.into()),
            }
        }
    }
    fn entries(&mut self) -> Result<Vec<Entry>> {
        let mut result = Vec::new();
        for group in fs::read_dir(&self.root)? {
            let group = group?;
            let name = group.file_name();
            if name == ".lock" {
                continue;
            }
            let name = name
                .into_string()
                .map_err(|_| anyhow::anyhow!("DERIVED_CACHE_NAMESPACE"))?;
            ensure!(namespace(&name), "DERIVED_CACHE_NAMESPACE");
            no_link(&group.path())?;
            ensure!(group.file_type()?.is_dir(), "DERIVED_CACHE_LAYOUT");
            for row in fs::read_dir(group.path())? {
                let row = row?;
                no_link(&row.path())?;
                let key = row
                    .file_name()
                    .into_string()
                    .map_err(|_| anyhow::anyhow!("DERIVED_CACHE_KEY"))?;
                if key.starts_with(".incoming-") {
                    fs::remove_dir_all(row.path())?;
                    continue;
                }
                ensure!(
                    valid_key(&key) && row.file_type()?.is_dir(),
                    "DERIVED_CACHE_LAYOUT"
                );
                ensure!(result.len() < MAX_ENTRIES, "DERIVED_CACHE_ENTRY_BUDGET");
                // Corrupt contents cannot be pinned. A link is an error, not a deletion target.
                reject_tree_links(&row.path(), 0, &mut 0)?;
                match read_entry(&row.path(), &name, &key, self.limits.entry_bytes) {
                    Ok(entry) => result.push(entry),
                    Err(_) => {
                        fs::remove_dir_all(row.path())?;
                        self.stats.corrupt += 1;
                    }
                }
            }
        }
        Ok(result)
    }
}
fn trim(entries: &mut Vec<Entry>, budget: u64, stats: &mut DerivedCacheStats) -> Result<()> {
    let mut bytes: u64 = entries.iter().map(|e| e.bytes).sum();
    entries.sort_by(|a, b| {
        a.accessed
            .cmp(&b.accessed)
            .then_with(|| a.path.cmp(&b.path))
    });
    while bytes > budget {
        let old = entries.remove(0);
        fs::remove_dir_all(&old.path)?;
        bytes -= old.bytes;
        stats.evicted += 1;
    }
    Ok(())
}

fn namespace(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 64
        && value
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}
fn valid_key(value: &str) -> bool {
    value.len() == 64
        && value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn no_link(path: &Path) -> Result<()> {
    match fs::symlink_metadata(path) {
        Ok(meta) => ensure!(!meta.file_type().is_symlink(), "DERIVED_CACHE_SYMLINK"),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(error.into()),
    }
    Ok(())
}
fn reject_tree_links(path: &Path, depth: usize, total: &mut usize) -> Result<()> {
    *total += 1;
    ensure!(*total <= 256, "DERIVED_CACHE_FILE_COUNT");
    ensure!(depth <= 8, "DERIVED_CACHE_DEPTH");
    no_link(path)?;
    if fs::symlink_metadata(path)?.is_dir() {
        let mut count = 0;
        for entry in fs::read_dir(path)? {
            count += 1;
            ensure!(count <= 64, "DERIVED_CACHE_FILE_COUNT");
            reject_tree_links(&entry?.path(), depth + 1, total)?;
        }
    }
    Ok(())
}
fn digest(path: &Path) -> Result<(u64, String)> {
    ensure!(
        fs::symlink_metadata(path)?.len() <= 192 << 20,
        "DERIVED_CACHE_ENTRY_BYTES"
    );
    no_link(path)?;
    ensure!(fs::symlink_metadata(path)?.is_file(), "DERIVED_CACHE_FILE");
    let mut file = File::open(path)?;
    let mut hash = Sha256::new();
    let mut bytes = 0_u64;
    let mut buffer = [0; 65536];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        bytes = bytes
            .checked_add(read as u64)
            .context("DERIVED_CACHE_BYTES")?;
        ensure!(bytes <= 192 << 20, "DERIVED_CACHE_ENTRY_BYTES");
        hash.update(&buffer[..read]);
    }
    Ok((bytes, format!("{:x}", hash.finalize())))
}
fn inventory(path: &Path, ns: &str, key: &str, budget: u64) -> Result<Receipt> {
    no_link(path)?;
    ensure!(fs::symlink_metadata(path)?.is_dir(), "DERIVED_CACHE_LAYOUT");
    reject_tree_links(path, 0, &mut 0)?;
    let mut files = BTreeMap::new();
    let mut bytes = 0;
    fn visit(
        root: &Path,
        path: &Path,
        files: &mut BTreeMap<String, ArtifactFile>,
        bytes: &mut u64,
        budget: u64,
        depth: usize,
    ) -> Result<()> {
        ensure!(depth <= 8, "DERIVED_CACHE_DEPTH");
        for entry in fs::read_dir(path)? {
            let entry = entry?;
            no_link(&entry.path())?;
            if entry.file_type()?.is_dir() {
                visit(root, &entry.path(), files, bytes, budget, depth + 1)?;
            } else {
                ensure!(files.len() < MAX_FILES, "DERIVED_CACHE_FILE_COUNT");
                ensure!(
                    fs::symlink_metadata(entry.path())?.len() <= budget - *bytes,
                    "DERIVED_CACHE_ENTRY_BYTES"
                );
                let (size, sha256) = digest(&entry.path())?;
                *bytes = bytes.checked_add(size).context("DERIVED_CACHE_BYTES")?;
                ensure!(*bytes <= budget, "DERIVED_CACHE_ENTRY_BYTES");
                let name = entry
                    .path()
                    .strip_prefix(root)?
                    .to_str()
                    .context("DERIVED_CACHE_PATH")?
                    .to_owned();
                ensure!(relative(&name), "DERIVED_CACHE_PATH");
                files.insert(
                    name,
                    ArtifactFile {
                        bytes: size,
                        sha256,
                    },
                );
            }
        }
        Ok(())
    }
    visit(path, path, &mut files, &mut bytes, budget, 0)?;
    ensure!(!files.is_empty(), "DERIVED_CACHE_EMPTY");
    Ok(Receipt {
        version: FORMAT,
        namespace: ns.to_owned(),
        key: key.to_owned(),
        files,
    })
}
fn relative(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 4096
        && Path::new(value)
            .components()
            .all(|c| matches!(c, Component::Normal(_)))
}
fn read_entry(path: &Path, ns: &str, key: &str, budget: u64) -> Result<Entry> {
    // The quota covers the whole artifact, including its receipt. Unknown siblings must
    // invalidate an entry instead of hiding bytes outside the accounted payload.
    let children = fs::read_dir(path)?.collect::<std::io::Result<Vec<_>>>()?;
    ensure!(
        children.len() == 2
            && children
                .iter()
                .all(|c| { c.file_name() == "receipt.json" || c.file_name() == "payload" }),
        "DERIVED_CACHE_LAYOUT"
    );
    let manifest = path.join("receipt.json");
    no_link(&manifest)?;
    let size = fs::metadata(&manifest)?.len();
    ensure!(size <= MAX_MANIFEST, "DERIVED_CACHE_MANIFEST_BUDGET");
    let receipt: Receipt = serde_json::from_slice(&fs::read(&manifest)?)?;
    ensure!(
        receipt.version == FORMAT && receipt.namespace == ns && receipt.key == key,
        "DERIVED_CACHE_IDENTITY"
    );
    ensure!(
        receipt == inventory(&path.join("payload"), ns, key, budget)?,
        "DERIVED_CACHE_CHECKSUM"
    );
    let bytes = receipt.files.values().map(|f| f.bytes).sum::<u64>() + size;
    Ok(Entry {
        path: path.to_owned(),
        receipt,
        bytes,
        accessed: fs::metadata(manifest)?.modified()?,
    })
}
fn copy_payload(source: &Path, target: &Path, receipt: &Receipt) -> Result<()> {
    for (name, record) in &receipt.files {
        ensure!(relative(name), "DERIVED_CACHE_PATH");
        let input = source.join(name);
        let output = target.join(name);
        no_link(&input)?;
        fs::create_dir_all(output.parent().context("DERIVED_CACHE_PATH")?)?;
        fs::copy(&input, &output)?;
        ensure!(
            digest(&output)? == (record.bytes, record.sha256.clone()),
            "DERIVED_CACHE_CHECKSUM"
        );
        File::open(output)?.sync_all()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn key(n: u8) -> String {
        format!("{n:064x}")
    }
    fn roots() -> (TempDir, PathBuf, PathBuf) {
        let temp = tempfile::tempdir().unwrap();
        let root = temp.path().join("store");
        let scratch = temp.path().join("scratch");
        fs::create_dir(&scratch).unwrap();
        (temp, root, scratch)
    }
    fn write(session: &DerivedCacheSession, ns: &str, n: u8, data: &[u8]) {
        let dir = session.namespace_path(ns).unwrap().join(key(n));
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("opaque.bin"), data).unwrap();
    }
    fn small() -> DerivedCacheLimits {
        DerivedCacheLimits {
            disk_bytes: 2400,
            pin_bytes: 1600,
            entry_bytes: 2000,
        }
    }
    #[test]
    fn eviction_and_corruption_do_not_change_task_pins_and_drop_does_not_publish() {
        let (_temp, root, scratch) = roots();
        let mut first = DerivedCacheSession::open(&root, &scratch, &["a", "b"], small()).unwrap();
        write(&first, "a", 1, &vec![1; 700]);
        write(&first, "b", 2, &vec![2; 700]);
        let stats = first.commit().unwrap();
        assert_eq!(stats.published, 2);
        let hot = DerivedCacheSession::open(&root, &scratch, &["a", "b"], small()).unwrap();
        assert_eq!(hot.stats().imported, 2);
        let pinned = hot
            .namespace_path("a")
            .unwrap()
            .join(key(1))
            .join("opaque.bin");
        fs::write(
            root.join("a").join(key(1)).join("payload/opaque.bin"),
            vec![9; 700],
        )
        .unwrap();
        let mut changed = DerivedCacheSession::open(&root, &scratch, &["a", "b"], small()).unwrap();
        assert_eq!(changed.stats().corrupt, 1);
        assert_eq!(changed.stats().imported, 1);
        write(&changed, "a", 3, &vec![3; 700]);
        write(&changed, "a", 4, &vec![4; 700]);
        assert!(changed.commit().unwrap().evicted >= 1);
        assert_eq!(fs::read(pinned).unwrap(), vec![1; 700]);
        let pending = DerivedCacheSession::open(&root, &scratch, &["a"], small()).unwrap();
        write(&pending, "a", 5, b"never acked");
        let path = pending.task.path().to_owned();
        drop(pending);
        assert!(!path.exists());
        assert!(!root.join("a").join(key(5)).exists());
    }
    #[test]
    fn pin_quota_namespace_identity_and_checksum_limit_reuse() {
        let (_temp, root, scratch) = roots();
        let mut session = DerivedCacheSession::open(&root, &scratch, &["a", "b"], small()).unwrap();
        write(&session, "a", 1, &vec![1; 700]);
        write(&session, "b", 1, &vec![2; 700]);
        session.commit().unwrap();
        let limits = DerivedCacheLimits {
            pin_bytes: 700,
            ..small()
        };
        let pin = DerivedCacheSession::open(&root, &scratch, &["a", "b"], limits).unwrap();
        assert_eq!(pin.stats().imported, 1);
        assert_eq!(pin.stats().imported_bytes, 700);
        assert!(pin.namespace_path("../a").is_err());
        let bad = root.join("a").join(key(1)).join("receipt.json");
        let mut receipt: serde_json::Value =
            serde_json::from_slice(&fs::read(&bad).unwrap()).unwrap();
        receipt["namespace"] = serde_json::json!("b");
        fs::write(bad, serde_json::to_vec(&receipt).unwrap()).unwrap();
        let clean = DerivedCacheSession::open(&root, &scratch, &["a", "b"], small()).unwrap();
        assert_eq!(clean.stats().corrupt, 1);
        assert_eq!(clean.stats().imported, 1);
        assert!(DerivedCacheSession::open(&root, &scratch, &["../a"], small()).is_err());
    }
    #[test]
    fn root_payload_and_lock_symlinks_never_read_or_modify_external_files() {
        use std::os::unix::fs::symlink;
        let (temp, root, scratch) = roots();
        let outside = temp.path().join("outside");
        fs::create_dir(&outside).unwrap();
        let marker = outside.join("marker");
        fs::write(&marker, b"unchanged").unwrap();
        symlink(&outside, &root).unwrap();
        assert!(DerivedCacheSession::open(&root, &scratch, &["a"], small()).is_err());
        fs::remove_file(&root).unwrap();
        let mut session = DerivedCacheSession::open(&root, &scratch, &["a"], small()).unwrap();
        write(&session, "a", 1, b"artifact");
        session.commit().unwrap();
        let payload = root.join("a").join(key(1)).join("payload/opaque.bin");
        fs::remove_file(&payload).unwrap();
        symlink(&marker, &payload).unwrap();
        assert!(DerivedCacheSession::open(&root, &scratch, &["a"], small()).is_err());
        assert_eq!(fs::read(&marker).unwrap(), b"unchanged");
        fs::remove_file(&payload).unwrap();
        fs::remove_file(root.join(".lock")).unwrap();
        symlink(&marker, root.join(".lock")).unwrap();
        assert!(DerivedCacheSession::open(&root, &scratch, &["a"], small()).is_err());
        assert_eq!(fs::read(marker).unwrap(), b"unchanged");
    }
    #[test]
    fn unknown_siblings_invalidate_entries_and_lower_quota_evicts_before_pinning() {
        let (_temp, root, scratch) = roots();
        let mut first = DerivedCacheSession::open(&root, &scratch, &["a"], small()).unwrap();
        write(&first, "a", 1, &vec![1; 700]);
        write(&first, "a", 2, &vec![2; 700]);
        first.commit().unwrap();
        fs::write(
            root.join("a").join(key(1)).join("unaccounted.bin"),
            vec![0; 3000],
        )
        .unwrap();
        let next = DerivedCacheSession::open(&root, &scratch, &["a"], small()).unwrap();
        assert_eq!(next.stats().corrupt, 1);
        assert_eq!(next.stats().imported, 1);
        assert!(!root.join("a").join(key(1)).exists());
        let limits = DerivedCacheLimits {
            disk_bytes: 100,
            ..small()
        };
        let reduced = DerivedCacheSession::open(&root, &scratch, &["a"], limits).unwrap();
        assert_eq!(reduced.stats().evicted, 1);
        assert_eq!(reduced.stats().imported, 0);
        assert_eq!(
            fs::read(
                next.namespace_path("a")
                    .unwrap()
                    .join(key(2))
                    .join("opaque.bin")
            )
            .unwrap(),
            vec![2; 700]
        );
    }
    #[test]
    fn concurrent_processes_publish_complete_entries_and_recover_interrupted_stage() {
        if let Ok(root) = std::env::var("CODEANA_DERIVED_CACHE_CHILD_ROOT") {
            let scratch =
                PathBuf::from(std::env::var("CODEANA_DERIVED_CACHE_CHILD_SCRATCH").unwrap());
            let n: u8 = std::env::var("CODEANA_DERIVED_CACHE_CHILD_KEY")
                .unwrap()
                .parse()
                .unwrap();
            let mut session =
                DerivedCacheSession::open(Path::new(&root), &scratch, &["a"], small()).unwrap();
            write(&session, "a", n, &vec![n; 700]);
            session.commit().unwrap();
            return;
        }
        let (_temp, root, scratch) = roots();
        let stage = root.join("a/.incoming-interrupted");
        fs::create_dir_all(&stage).unwrap();
        fs::write(stage.join("partial"), b"not complete").unwrap();
        let mut children = Vec::new();
        for n in [1, 2] {
            children.push(std::process::Command::new(std::env::current_exe().unwrap()).args(["--exact","derived_cache::tests::concurrent_processes_publish_complete_entries_and_recover_interrupted_stage","--nocapture"])
                .env("CODEANA_DERIVED_CACHE_CHILD_ROOT",&root).env("CODEANA_DERIVED_CACHE_CHILD_SCRATCH",&scratch).env("CODEANA_DERIVED_CACHE_CHILD_KEY",n.to_string()).spawn().unwrap());
        }
        for mut child in children {
            assert!(child.wait().unwrap().success());
        }
        assert!(!stage.exists());
        let pin = DerivedCacheSession::open(&root, &scratch, &["a"], small()).unwrap();
        assert_eq!(pin.stats().imported, 2);
        for n in [1, 2] {
            assert_eq!(
                fs::read(
                    pin.namespace_path("a")
                        .unwrap()
                        .join(key(n))
                        .join("opaque.bin")
                )
                .unwrap(),
                vec![n; 700]
            );
        }
    }
}
