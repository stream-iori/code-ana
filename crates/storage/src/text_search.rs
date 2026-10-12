//! Optional trigram candidates over a content-verified captured manifest.
use super::*;
use crate::derived_cache::{DerivedCacheLimits, DerivedCacheSession};

pub type TextCandidates = BTreeSet<(String, String)>;
const NAMESPACE: &str = "text-trigram-v1";
const MAX_CAPTURE: u64 = 16 << 20;

impl Store {
    /// An optimization only: unsupported patterns/large captures keep the scan path.
    pub fn text_candidates(
        &self,
        manifest: &Manifest,
        pattern: &str,
        read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
        check: &dyn Fn() -> Result<()>,
    ) -> Result<Option<TextCandidates>> {
        check()?;
        if pattern.chars().count() < 3
            || pattern.contains('\0')
            || manifest
                .files
                .iter()
                .try_fold(0u64, |sum, s| sum.checked_add(s.size))
                .is_none_or(|size| size > MAX_CAPTURE)
        {
            return Ok(None);
        }
        let scratch = tempfile::tempdir()?;
        let mut cache = DerivedCacheSession::open(
            &self.home.join("derived-cache"),
            scratch.path(),
            &[NAMESPACE],
            DerivedCacheLimits::default(),
        )?;
        let key = digest(&(NAMESPACE, manifest));
        let entry = cache.namespace_path(NAMESPACE)?.join(&key);
        let path = entry.join("text.db");
        if !path.is_file() {
            std::fs::create_dir_all(&entry)?;
            let mut db = Connection::open(&path)?;
            db.execute_batch("PRAGMA journal_mode=DELETE; PRAGMA max_page_count=32768; CREATE TABLE identity(manifest TEXT NOT NULL); CREATE TABLE sources(id INTEGER PRIMARY KEY,world TEXT,path TEXT,invalid INTEGER); CREATE VIRTUAL TABLE text_fts USING fts5(body,tokenize='trigram case_sensitive 1');")?;
            let transaction = db.transaction()?;
            transaction.execute("INSERT INTO identity VALUES(?)", [&key])?;
            for (index, source) in manifest.files.iter().enumerate() {
                check()?;
                let bytes = read_source(source)?;
                ensure!(hash(&bytes) == source.hash, "SOURCE_HASH_MISMATCH");
                let text = std::str::from_utf8(&bytes);
                let id = index as i64 + 1;
                transaction.execute(
                    "INSERT INTO sources VALUES(?,?,?,?)",
                    params![id, source.world, source.path, i64::from(text.is_err())],
                )?;
                if let Ok(text) = text {
                    transaction.execute(
                        "INSERT INTO text_fts(rowid,body) VALUES(?,?)",
                        params![id, text],
                    )?;
                }
            }
            transaction.commit()?;
            drop(db);
            check()?;
            cache.commit()?;
        }
        check()?;
        let db = Connection::open_with_flags(&path, rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY)?;
        let identity: String = db.query_row("SELECT manifest FROM identity", [], |r| r.get(0))?;
        ensure!(identity == key, "TEXT_CACHE_IDENTITY");
        let phrase = format!("\"{}\"", pattern.replace('"', "\"\""));
        let mut query = db.prepare("SELECT world,path FROM sources WHERE invalid=1 OR id IN (SELECT rowid FROM text_fts WHERE text_fts MATCH ?) ORDER BY id")?;
        let rows = query.query_map([phrase], |r| Ok((r.get(0)?, r.get(1)?)))?;
        let mut candidates = BTreeSet::new();
        for row in rows {
            check()?;
            candidates.insert(row?);
        }
        Ok(Some(candidates))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::Cell;
    #[test]
    fn literal_candidates_are_verified_reused_and_keep_invalid_encoding() {
        let directory = tempfile::tempdir().unwrap();
        let store = Store::open(directory.path()).unwrap();
        let inputs = BTreeMap::from([
            ("A", "quote \" 中文 alphabet".as_bytes().to_vec()),
            ("B", b"unrelated text".to_vec()),
            ("C", vec![255]),
        ]);
        let manifest = Manifest {
            files: inputs
                .iter()
                .map(|(path, bytes)| SourceUnit {
                    path: (*path).into(),
                    hash: hash(bytes),
                    size: bytes.len() as u64,
                    world: "main".into(),
                    module: "module".into(),
                    scope: "MAIN".into(),
                    language: "java".into(),
                    auxiliary: false,
                })
                .collect(),
        };
        let reads = Cell::new(0);
        let source = |s: &SourceUnit| {
            reads.set(reads.get() + 1);
            Ok(inputs[s.path.as_str()].clone())
        };
        let first = store
            .text_candidates(&manifest, "alphabet", &source, &|| Ok(()))
            .unwrap()
            .unwrap();
        assert_eq!(
            first,
            BTreeSet::from([("main".into(), "A".into()), ("main".into(), "C".into())])
        );
        assert_eq!(reads.get(), 3);
        for pattern in ["中文 ", "\" 中文", "missing"] {
            let expected = inputs
                .iter()
                .filter(|(_, b)| std::str::from_utf8(b).map_or(true, |s| s.contains(pattern)))
                .map(|(p, _)| ("main".into(), (*p).into()))
                .collect();
            assert_eq!(
                store
                    .text_candidates(&manifest, pattern, &source, &|| Ok(()))
                    .unwrap()
                    .unwrap(),
                expected
            );
        }
        assert_eq!(
            reads.get(),
            3,
            "verified cache must avoid rereading nonmatches"
        );
        assert!(
            store
                .text_candidates(&manifest, "中", &source, &|| Ok(()))
                .unwrap()
                .is_none()
        );
        assert!(
            store
                .text_candidates(&manifest, "", &source, &|| Ok(()))
                .unwrap()
                .is_none()
        );
        let mut large = manifest.clone();
        large.files[0].size = MAX_CAPTURE + 1;
        assert!(
            store
                .text_candidates(&large, "alphabet", &source, &|| Ok(()))
                .unwrap()
                .is_none()
        );
        assert_eq!(
            reads.get(),
            3,
            "large/short fallback must not build an index"
        );
        assert_eq!(
            store
                .text_candidates(&manifest, "alphabet", &source, &|| anyhow::bail!(
                    "CANCELLED"
                ))
                .unwrap_err()
                .to_string(),
            "CANCELLED"
        );
        let corrupt = directory
            .path()
            .join("derived-cache")
            .join(NAMESPACE)
            .join(digest(&(NAMESPACE, &manifest)))
            .join("payload/text.db");
        std::fs::write(corrupt, b"corrupt cache").unwrap();
        assert_eq!(
            store
                .text_candidates(&manifest, "alphabet", &source, &|| Ok(()))
                .unwrap()
                .unwrap(),
            first
        );
        assert_eq!(
            reads.get(),
            6,
            "corrupt cache must rebuild captured content"
        );
    }
}
