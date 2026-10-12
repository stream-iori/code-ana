use super::*;
use std::collections::BTreeSet;
#[derive(Clone, Serialize, Deserialize)]
pub(crate) struct Snapshot {
    pub id: String,
    pub kind: String,
    pub manifest: Manifest,
    pub revision: String,
    pub profile: String,
    pub project: String,
    pub selector: String,
    pub created: u64,
    #[serde(default)]
    pub effective: Option<codeana_config::Effective>,
}
#[derive(Serialize, Deserialize)]
struct Page {
    output: Output,
    options: String,
    created: u64,
    #[serde(default)]
    spooled: bool,
    #[serde(default)]
    total: Option<usize>,
}
fn options(req: &Request) -> String {
    let mut r = req.clone();
    r.request_id.clear();
    r.cursor.clear();
    r.max_records = 0;
    r.max_output_bytes = 0;
    r.background = false;
    r.deadline_seconds = 0;
    digest(&r)
}
impl Engine {
    pub(crate) async fn snapshot(
        &self,
        id: &str,
        req: &Request,
        capture: Option<Capture>,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let root = Path::new(&req.project);
        let (project, _checkout) = codeana_config::identity(root);
        let mut selector = "WORKTREE".to_owned();
        let c = if req.target.is_empty() || req.target == "WORKTREE" {
            if let Some(c) = capture {
                c
            } else {
                let root = root.to_path_buf();
                let request = req.clone();
                tokio::task::spawn_blocking(move || codeana_config::capture(&root, &request))
                    .await??
            }
        } else {
            let root = root.to_path_buf();
            let request = req.clone();
            let project = project.clone();
            let cancellation = cancel.clone();
            let (capture, commit) =
                tokio::task::spawn_blocking(move || -> Result<(Capture, String)> {
                    ensure!(!cancellation.is_cancelled(), "CANCELLED");
                    ensure!(!request.target.starts_with('-'), "INVALID_GIT_SELECTOR");
                    let commit = String::from_utf8(git_output(
                        &root,
                        &[
                            "rev-parse",
                            "--verify",
                            &format!("{}^{{commit}}", request.target),
                        ],
                    )?)?
                    .trim()
                    .to_owned();
                    ensure!(commit.len() == 40 || commit.len() == 64, "INVALID_COMMIT");
                    let temp = tempfile::tempdir()?;
                    let paths =
                        git_output(&root, &["ls-tree", "-r", "-z", "--name-only", &commit])?;
                    let mut total = 0usize;
                    for path in paths.split(|b| *b == 0).filter(|b| !b.is_empty()) {
                        ensure!(!cancellation.is_cancelled(), "CANCELLED");
                        let path = std::str::from_utf8(path)?;
                        ensure!(safe_path(path), "UNSAFE_GIT_PATH");
                        if path.starts_with(".git/") {
                            continue;
                        }
                        let bytes = git_output(&root, &["show", &format!("{commit}:{path}")])?;
                        total += bytes.len();
                        ensure!(total <= MAX_RESULT, "GIT_CAPTURE_BUDGET");
                        let target = temp.path().join(path);
                        std::fs::create_dir_all(target.parent().context("GIT_PATH_PARENT")?)?;
                        std::fs::write(target, bytes)?;
                    }
                    let mut c = codeana_config::capture(temp.path(), &request)?;
                    c.root = root;
                    c.project = project.clone();
                    c.checkout = key("historical_checkout", &[&project, &commit]);
                    c.target = key(
                        "historical_target",
                        &[&project, &commit, &c.effective.capture_digest],
                    );
                    Ok((c, commit))
                })
                .await??;
            selector = commit;
            capture
        };
        let manifest = c.manifest.clone();
        let effective = c.effective.clone();
        let mut revision = String::new();
        let mut profile = c.effective.semantic_digest.clone();
        let kind = if req.name.is_empty() {
            "files"
        } else {
            &req.name
        };
        ensure!(
            matches!(kind, "files" | "analysis"),
            "INVALID_SNAPSHOT_KIND"
        );
        let mut t = if kind == "analysis" {
            let t = self.index(id, req, c, cancel).await?;
            ensure!(
                t.publication == "published",
                "SNAPSHOT_ANALYSIS_NOT_PUBLISHED"
            );
            revision = t.revision.clone();
            let r = self.store.read(&revision)?;
            profile = r.revision().profile.clone();
            t
        } else {
            let store = self.store.clone();
            tokio::task::spawn_blocking(move || store.put_blob_files(&c.blobs)).await??;
            TaskResult::success(Output {
                evidence: Evidence::complete(),
                ..Default::default()
            })
        };
        let sid = if req.selector.is_empty() {
            new_id("snapshot")
        } else {
            ensure!(
                safe_path(&req.selector) && !req.selector.contains('/'),
                "INVALID_SNAPSHOT_NAME"
            );
            req.selector.clone()
        };
        let s = Snapshot {
            id: sid.clone(),
            kind: kind.into(),
            manifest,
            revision: revision.clone(),
            profile,
            project: project.clone(),
            selector,
            created: now(),
            effective: Some(effective),
        };
        let store = self.store.clone();
        let saved = s.clone();
        let saved_id = sid.clone();
        let saved_project = project.clone();
        let refs = if revision.is_empty() {
            vec![]
        } else {
            vec![revision]
        };
        tokio::task::spawn_blocking(move || {
            store.put_object("snapshot", &saved_id, &saved_project, &saved, &refs)
        })
        .await??;
        t.output.records = vec![serde_json::to_value(&s)?];
        Ok(t)
    }
    pub(crate) fn diff(&self, req: &Request, project: &str) -> Result<Output> {
        if !req.cursor.is_empty() {
            return Ok(self.continue_page(req, project)?.output);
        }
        let base: Snapshot = self.store.object("snapshot", &req.base, project)?;
        let target: Snapshot = self.store.object("snapshot", &req.target, project)?;
        let section = if req.name.is_empty() {
            "files"
        } else {
            &req.name
        };
        ensure!(
            matches!(section, "files" | "declarations" | "relations" | "impact"),
            "INVALID_DIFF_SECTION"
        );
        let mut evidence = Evidence::complete();
        let mut rows = Vec::new();
        let mut refs = Vec::new();
        if section == "files" {
            let a: BTreeMap<_, _> = base
                .manifest
                .files
                .iter()
                .map(|s| (&s.path, &s.hash))
                .collect();
            let b: BTreeMap<_, _> = target
                .manifest
                .files
                .iter()
                .map(|s| (&s.path, &s.hash))
                .collect();
            let paths: BTreeSet<_> = a.keys().chain(b.keys()).copied().collect();
            for p in paths {
                if a.get(p) != b.get(p) {
                    rows.push(json!({"path":p,"base":a.get(p),"target":b.get(p),"change":if !a.contains_key(p){"added"}else if !b.contains_key(p){"deleted"}else{"modified"}}));
                }
            }
        } else {
            ensure!(
                !base.revision.is_empty() && !target.revision.is_empty(),
                "ANALYSIS_SNAPSHOTS_REQUIRED"
            );
            let a = self.store.read(&base.revision)?;
            let b = self.store.read(&target.revision)?;
            refs.extend([base.revision.clone(), target.revision.clone()]);
            if base.profile != target.profile {
                evidence.semantic = false;
                evidence.reasons.push("semantic_profile_changed".into());
            }
            let kinds = if section == "declarations" {
                vec![FactKind::Declaration]
            } else {
                vec![
                    FactKind::CallSite,
                    FactKind::CallTarget,
                    FactKind::Relation,
                    FactKind::Reference,
                ]
            };
            let af = a
                .select(&FactFilter {
                    kinds: kinds.clone(),
                    ..Default::default()
                })
                .map_err(anyhow::Error::msg)?;
            let bf = b
                .select(&FactFilter {
                    kinds,
                    ..Default::default()
                })
                .map_err(anyhow::Error::msg)?;
            // Compare semantic multisets, ignoring occurrence IDs and byte movement but preserving multiplicity and binding evidence.
            fn canonical(f: &Fact) -> Value {
                json!({"kind":f.kind,"world":f.owner.world,"name":f.name,"signature":f.signature,"parent":f.parent,"target":f.target,"relation":f.relation,"status":f.status,"origin":f.origin,"evidence_complete":f.evidence_complete,"declaration_resolved":f.declaration_resolved,"attributes":f.attributes})
            }
            let mut ac = BTreeMap::<String, (Value, i64)>::new();
            let mut bc = BTreeMap::<String, (Value, i64)>::new();
            for (facts, map) in [(&af, &mut ac), (&bf, &mut bc)] {
                for f in facts {
                    let v = canonical(f);
                    map.entry(digest(&v)).or_insert((v, 0)).1 += 1;
                }
            }
            for key in ac.keys().chain(bc.keys()).collect::<BTreeSet<_>>() {
                let av = ac.get(key);
                let bv = bc.get(key);
                let n = av.map(|x| x.1).unwrap_or(0);
                let m = bv.map(|x| x.1).unwrap_or(0);
                if n != m {
                    rows.push(json!({"fact":av.or(bv).unwrap().0,"base_count":n,"target_count":m}));
                }
            }
            if section == "impact" {
                let changed: BTreeSet<String> = af
                    .iter()
                    .chain(bf.iter())
                    .filter(|f| rows.iter().any(|v| v["fact"] == canonical(f)))
                    .flat_map(|f| [f.parent.clone(), f.target.clone()])
                    .filter(|s| !s.is_empty())
                    .collect();
                let mut impact = Vec::new();
                for (side, reader) in [("base", &a), ("target", &b)] {
                    for symbol in &changed {
                        let r = Request {
                            operation: "calls".into(),
                            selector: symbol.clone(),
                            direction: "in".into(),
                            depth: req.depth.max(1),
                            ..Default::default()
                        };
                        if let Ok(out) =
                            codeana_query::run(reader, &r, &|s| self.store.blob(&s.hash))
                        {
                            evidence.merge(&out.evidence);
                            for edge in out.records {
                                impact.push(json!({"side":side,"changed":symbol,"edge":edge,"possible":true}));
                            }
                        }
                    }
                }
                rows = impact;
                evidence.semantic = false;
                evidence.reasons.push("static_possible_impact".into());
            }
            for reader in [&a, &b] {
                for f in reader
                    .select(&FactFilter {
                        kinds: vec![FactKind::Coverage],
                        ..Default::default()
                    })
                    .map_err(anyhow::Error::msg)?
                {
                    if !f.evidence_complete {
                        evidence.semantic = false;
                        evidence.reasons.push("endpoint_coverage_incomplete".into());
                    }
                }
            }
        }
        let output = Output {
            records: rows,
            evidence,
            producer: "rust-diff-v1".into(),
            ..Default::default()
        };
        Ok(self.page(req, project, output, refs)?.output)
    }
    pub(crate) fn page(
        &self,
        req: &Request,
        project: &str,
        mut output: Output,
        refs: Vec<String>,
    ) -> Result<TaskResult> {
        let mut spool = self.store.page_spool()?;
        for batch in output.records.chunks(1024) {
            spool.append(batch)?;
        }
        output.records.clear();
        self.freeze_page(req, project, output, refs, spool)
    }
    pub(crate) fn search_page(
        &self,
        req: &Request,
        project: &str,
        reader: &dyn FactReader,
        refs: Vec<String>,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let mut spool = self.store.page_spool()?;
        let output = codeana_query::stream_search(reader, req, &mut |batch| {
            ensure!(!cancel.is_cancelled(), "CANCELLED");
            spool.append(&batch)
        })?;
        ensure!(!cancel.is_cancelled(), "CANCELLED");
        self.freeze_page(req, project, output, refs, spool)
    }
    pub(crate) fn text_page(
        &self,
        req: &Request,
        reader: &dyn FactReader,
        source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
        refs: Vec<String>,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        codeana_query::preflight(req)?;
        let candidates = if !req.text_regex && !req.text_ignore_case {
            match self
                .store
                .text_candidates(reader.manifest(), &req.selector, source, &|| {
                    ensure!(!cancel.is_cancelled(), "CANCELLED");
                    Ok(())
                }) {
                Ok(candidates) => candidates,
                Err(error)
                    if error.to_string().contains("CANCELLED")
                        || error.to_string().contains("SOURCE_HASH_MISMATCH") =>
                {
                    return Err(error);
                }
                // A derived index is optional. Historical/missing/corrupt indexes
                // retain the authoritative captured-byte matching path.
                Err(_) => None,
            }
        } else {
            None
        };
        let mut spool = self.store.page_spool()?;
        let output = codeana_query::stream_text_candidates(
            reader,
            req,
            source,
            candidates.as_ref(),
            &mut |batch| {
                ensure!(!cancel.is_cancelled(), "CANCELLED");
                if batch.is_empty() {
                    Ok(())
                } else {
                    spool.append(&batch)
                }
            },
        )?;
        ensure!(!cancel.is_cancelled(), "CANCELLED");
        self.freeze_page(req, &reader.revision().project, output, refs, spool)
    }
    fn freeze_page(
        &self,
        req: &Request,
        project: &str,
        output: Output,
        refs: Vec<String>,
        spool: codeana_storage::PageSpool,
    ) -> Result<TaskResult> {
        ensure!(output.records.is_empty(), "PAGE_METADATA_CONTAINS_RECORDS");
        let id = new_id("page");
        let page = Page {
            output,
            options: options(req),
            created: now(),
            spooled: true,
            total: Some(spool.count()),
        };
        self.store.freeze_page(&id, project, &page, &refs, spool)?;
        self.slice_page(req, project, &id, page, 0)
    }
    pub(crate) fn continue_page(&self, req: &Request, project: &str) -> Result<TaskResult> {
        let (id, offset) = req.cursor.rsplit_once(':').context("INVALID_CURSOR")?;
        let page: Page = self.store.object("page", id, project)?;
        ensure!(page.options == options(req), "CURSOR_OPTIONS_MISMATCH");
        ensure!(now().saturating_sub(page.created) < 86400, "CURSOR_EXPIRED");
        self.slice_page(req, project, id, page, offset.parse()?)
    }
    fn slice_page(
        &self,
        req: &Request,
        project: &str,
        id: &str,
        page: Page,
        offset: usize,
    ) -> Result<TaskResult> {
        let mut output = page.output;
        let total = page.total.unwrap_or(output.records.len());
        ensure!(offset <= total, "INVALID_CURSOR_OFFSET");
        let limit = if req.max_records == 0 {
            100
        } else {
            req.max_records as usize
        };
        ensure!((1..=10000).contains(&limit), "RECORD_BUDGET");
        if page.spooled {
            output.records = self.store.page_records(id, project, offset, limit)?;
            ensure!(
                output.records.len() == (total - offset).min(limit),
                "INCOMPLETE_FROZEN_PAGE"
            );
        } else {
            output.records = output
                .records
                .into_iter()
                .skip(offset)
                .take(limit)
                .collect();
        }
        let bytes_limit = if req.max_output_bytes == 0 {
            32768
        } else {
            req.max_output_bytes as usize
        };
        ensure!(bytes_limit >= 2048, "OUTPUT_BUDGET_TOO_SMALL");
        let mut size = 1536;
        let mut count = 0;
        for row in &output.records {
            let n = serde_json::to_vec(row)?.len() + 8;
            if size + n > bytes_limit {
                break;
            }
            size += n;
            count += 1;
        }
        ensure!(
            count > 0 || offset == total,
            "RECORD_EXCEEDS_OUTPUT_BUDGET: increase --max-output-bytes"
        );
        let end = offset + count;
        output.records.truncate(count);
        if end < total {
            output.cursor = format!("{id}:{end}");
            output.evidence.enumeration = false;
            output.evidence.limits.push("page".into());
        } else {
            output.cursor.clear();
        }
        Ok(TaskResult::success(output))
    }
}
fn git_output(root: &Path, args: &[&str]) -> Result<Vec<u8>> {
    use std::io::Read;
    let mut child = std::process::Command::new("git")
        .arg("-C")
        .arg(root)
        .args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()?;
    let mut bytes = Vec::new();
    child
        .stdout
        .take()
        .context("GIT_STDOUT")?
        .take(MAX_RESULT as u64 + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() > MAX_RESULT {
        let _ = child.kill();
        let _ = child.wait();
        bail!("GIT_OUTPUT_BUDGET");
    }
    ensure!(child.wait()?.success(), "GIT_READ_FAILED");
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn frozen_pages_store_only_metadata_and_allow_different_page_sizes() {
        let directory = tempfile::tempdir().unwrap();
        let engine = Engine::new(directory.path(), None).unwrap();
        let mut request = Request {
            operation: "search-code".into(),
            selector: "same".into(),
            max_records: 7,
            max_output_bytes: 32768,
            ..Default::default()
        };
        let records: Vec<_> = (0..15003)
            .map(|id| json!({"id":format!("id-{id:05}"),"name":"same"}))
            .collect();
        let first = engine
            .page(
                &request,
                "project",
                Output {
                    records: records.clone(),
                    evidence: Evidence::complete(),
                    ..Default::default()
                },
                vec![],
            )
            .unwrap();
        let id = first.output.cursor.rsplit_once(':').unwrap().0.to_owned();
        let metadata: Value = engine.store.object("page", &id, "project").unwrap();
        assert!(metadata["output"]["records"].as_array().unwrap().is_empty());
        let mut actual = first.output.records;
        request.cursor = first.output.cursor;
        request.max_records = 83;
        while !request.cursor.is_empty() {
            let next = engine.continue_page(&request, "project").unwrap();
            actual.extend(next.output.records);
            request.cursor = next.output.cursor;
        }
        assert_eq!(actual, records);
        request.cursor = format!("{id}:0");
        request.selector = "different".into();
        assert!(
            engine
                .continue_page(&request, "project")
                .unwrap_err()
                .to_string()
                .contains("CURSOR_OPTIONS_MISMATCH")
        );
    }
}
