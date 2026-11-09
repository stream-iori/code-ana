use super::*;
use std::collections::BTreeSet;

#[derive(Clone, Serialize, Deserialize)]
pub(super) struct SemanticFact {
    pub(super) kind: FactKind,
    pub(super) world: WorldId,
    pub(super) name: String,
    pub(super) signature: String,
    pub(super) parent: String,
    pub(super) target: String,
    pub(super) relation: String,
    pub(super) status: FactStatus,
    pub(super) origin: String,
    pub(super) evidence_complete: bool,
    pub(super) declaration_resolved: bool,
    pub(super) attributes: FactAttributes,
}
impl From<&Fact> for SemanticFact {
    fn from(f: &Fact) -> Self {
        Self {
            kind: f.kind,
            world: f.owner.world.clone(),
            name: f.name.clone(),
            signature: f.signature.clone(),
            parent: f.parent.clone(),
            target: f.target.clone(),
            relation: f.relation.clone(),
            status: f.status.clone(),
            origin: f.origin.clone(),
            evidence_complete: f.evidence_complete,
            declaration_resolved: f.declaration_resolved,
            attributes: f.attributes.clone(),
        }
    }
}
fn semantic_hash(f: &SemanticFact) -> String {
    digest(&serde_json::to_value(f).expect("semantic identity encoding"))
}
#[derive(Serialize, Deserialize)]
#[serde(untagged)]
pub(super) enum DiffRecord {
    File(FileChange),
    Fact(Box<FactChange>),
    Impact(ImpactRecord),
}
#[derive(Serialize, Deserialize)]
pub(super) struct FileChange {
    pub(super) path: String,
    pub(super) base: Option<String>,
    pub(super) target: Option<String>,
    pub(super) change: String,
}
#[derive(Serialize, Deserialize)]
pub(super) struct FactChange {
    pub(super) fact: SemanticFact,
    pub(super) base_count: i64,
    pub(super) target_count: i64,
}
#[derive(Serialize, Deserialize)]
pub(super) struct ImpactRecord {
    pub(super) side: String,
    pub(super) changed: String,
    pub(super) edge: CallRelation,
    pub(super) possible: bool,
}

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
#[serde(deny_unknown_fields)]
struct Page<T = Output> {
    projection_version: u32,
    request: Request,
    order_policy: QueryOrderPolicy,
    output: T,
    options: String,
    created: u64,
    total: usize,
}
fn option_request(req: &Request) -> Request {
    let mut r = req.clone();
    r.request_id.clear();
    r.delivery.normalize_identity();
    r.cursor.clear();
    r.max_records = 0;
    r.max_output_bytes = 0;
    r.background = false;
    r.deadline_seconds = 0;
    r
}
fn options(req: &Request) -> String {
    digest(&option_request(req))
}

#[derive(Clone)]
pub(super) struct HistoryService {
    store: Store,
}
impl HistoryService {
    pub(super) fn new(store: Store) -> Self {
        Self { store }
    }
    pub(super) fn project_identity(&self, path: &str) -> Result<(String, String)> {
        self.store.project_path(path)
    }
    pub(super) fn list_snapshots(&self, project: &str) -> Result<Output> {
        Ok(Output {
            records: self
                .store
                .objects::<Snapshot>("snapshot", project)?
                .iter()
                .map(Record::display)
                .collect::<Result<_, _>>()?,
            evidence: Evidence::complete(),
            ..Default::default()
        })
    }
    pub(super) fn remove_snapshot(&self, project: &str, id: &str) -> Result<Output> {
        self.store.delete_object("snapshot", id, project)?;
        Ok(Output {
            records: vec![Record::display(&RemovedSnapshot { removed: id })?],
            evidence: Evidence::complete(),
            ..Default::default()
        })
    }
    pub(crate) async fn snapshot<F, Fut>(
        &self,
        req: &Request,
        capture: Option<Capture>,
        cancel: &CancellationToken,
        index: F,
    ) -> Result<TaskResult>
    where
        F: FnOnce(Capture) -> Fut + Send,
        Fut: std::future::Future<Output = Result<TaskResult>> + Send,
    {
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
                    ensure!(
                        !cancellation.is_cancelled(),
                        codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                    );
                    ensure!(
                        !request.target.starts_with('-'),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::InvalidGitSelector,
                            ""
                        )
                    );
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
                    ensure!(
                        commit.len() == 40 || commit.len() == 64,
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::InvalidCommit,
                            ""
                        )
                    );
                    let temp = tempfile::tempdir()?;
                    let paths =
                        git_output(&root, &["ls-tree", "-r", "-z", "--name-only", &commit])?;
                    let mut total = 0usize;
                    for path in paths.split(|b| *b == 0).filter(|b| !b.is_empty()) {
                        ensure!(
                            !cancellation.is_cancelled(),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::Cancelled,
                                ""
                            )
                        );
                        let path = std::str::from_utf8(path)?;
                        ensure!(
                            safe_path(path),
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::UnsafeGitPath,
                                ""
                            )
                        );
                        if path.starts_with(".git/") {
                            continue;
                        }
                        let bytes = git_output(&root, &["show", &format!("{commit}:{path}")])?;
                        total += bytes.len();
                        ensure!(
                            total <= MAX_RESULT,
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::GitCaptureBudget,
                                ""
                            )
                        );
                        let target = temp.path().join(path);
                        std::fs::create_dir_all(target.parent().context(
                            codeana_model::DomainError::new(
                                codeana_model::ErrorCode::GitPathParent,
                                "",
                            ),
                        )?)?;
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
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidSnapshotKind, "")
        );
        let _blob_pins = self.store.pin_blobs(c.blobs.keys());
        let mut t = if kind == "analysis" {
            let t = index(c).await?;
            ensure!(
                t.publication == codeana_model::PublicationState::Published,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::SnapshotAnalysisNotPublished,
                    ""
                )
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
                codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidSnapshotName, "")
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
        t.output.records = vec![Record::display(&s)?];
        Ok(t)
    }
    pub(crate) fn diff(&self, req: &Request, project: &str) -> Result<Output> {
        if !req.cursor.is_empty() {
            return Ok(self.continue_page(req, project)?.output);
        }
        let budget = ResourceBudget::new(codeana_config::query_limits(
            Path::new(&req.project),
            &self.store.home,
        )?);
        let endpoints = (|| -> Result<(Snapshot, Snapshot)> {
            Ok((
                self.store
                    .object_budgeted("snapshot", &req.base, project, &budget)?,
                self.store
                    .object_budgeted("snapshot", &req.target, project, &budget)?,
            ))
        })();
        let (base, target) = match endpoints {
            Ok(pair) => pair,
            Err(e) if resource_error(&e) => {
                return Ok(self
                    .resource_page(req, project, "", &budget, vec![])?
                    .output);
            }
            Err(e) => return Err(e),
        };
        let section = if req.name.is_empty() {
            "files"
        } else {
            &req.name
        };
        ensure!(
            matches!(section, "files" | "declarations" | "relations" | "impact"),
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidDiffSection, "")
        );
        let mut evidence = Evidence::complete();
        let mut rows = Vec::<DiffRecord>::new();
        let mut refs = Vec::new();
        let compared = (|| -> Result<()> {
            if section == "files" {
                for file in base.manifest.files.iter().chain(&target.manifest.files) {
                    budget.charge(1, retained_size(file)?)?;
                }
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
                    budget.charge(1, p.len() as u64)?;
                    if a.get(p) != b.get(p) {
                        rows.push(DiffRecord::File(FileChange {
                            path: p.into(),
                            base: a.get(p).map(|v| (*v).clone()),
                            target: b.get(p).map(|v| (*v).clone()),
                            change: if !a.contains_key(p) {
                                "added"
                            } else if !b.contains_key(p) {
                                "deleted"
                            } else {
                                "modified"
                            }
                            .into(),
                        }));
                    }
                }
            } else {
                ensure!(
                    !base.revision.is_empty() && !target.revision.is_empty(),
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::AnalysisSnapshotsRequired,
                        ""
                    )
                );
                let a = self.store.read_budgeted(&base.revision, budget.clone())?;
                let b = self.store.read_budgeted(&target.revision, budget.clone())?;
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
                if budget.exhausted() {
                    return Err(ResourceBudget::error().into());
                }
                // Compare semantic multisets, ignoring occurrence IDs and byte movement but preserving multiplicity and binding evidence.
                let mut ac = BTreeMap::<String, (SemanticFact, i64)>::new();
                let mut bc = BTreeMap::<String, (SemanticFact, i64)>::new();
                for (facts, map) in [(&af, &mut ac), (&bf, &mut bc)] {
                    for f in facts {
                        budget.charge(1, retained_size(f)?)?;
                        let v = SemanticFact::from(f);
                        map.entry(semantic_hash(&v)).or_insert((v, 0)).1 += 1;
                    }
                }
                let mut changed_facts = BTreeSet::new();
                for key in ac.keys().chain(bc.keys()).collect::<BTreeSet<_>>() {
                    budget.charge(1, key.len() as u64)?;
                    let av = ac.get(key);
                    let bv = bc.get(key);
                    let n = av.map(|x| x.1).unwrap_or(0);
                    let m = bv.map(|x| x.1).unwrap_or(0);
                    if n != m {
                        changed_facts.insert(key.clone());
                        rows.push(DiffRecord::Fact(Box::new(FactChange {
                            fact: av.or(bv).unwrap().0.clone(),
                            base_count: n,
                            target_count: m,
                        })));
                    }
                }
                if section == "impact" {
                    let changed: BTreeSet<String> = af
                        .iter()
                        .chain(bf.iter())
                        .filter(|f| changed_facts.contains(&semantic_hash(&SemanticFact::from(*f))))
                        .flat_map(|f| [f.parent.clone(), f.target.clone()])
                        .filter(|s| !s.is_empty())
                        .collect();
                    rows.clear();
                    evidence.semantic = false;
                    evidence.reasons.push("static_possible_impact".into());
                    evidence.reasons.push("relation_change_roots_only".into());
                    'impact: for (side, reader) in [("base", &a), ("target", &b)] {
                        let mut pending = changed.clone();
                        let mut seen = BTreeSet::new();
                        let mut roots = BTreeSet::new();
                        for _ in 0..32 {
                            if pending.is_empty() {
                                break;
                            }
                            let batch_ids: Vec<_> = std::mem::take(&mut pending)
                                .into_iter()
                                .filter(|id| seen.insert(id.clone()))
                                .collect();
                            for ids in batch_ids.chunks(500) {
                                budget.charge(1, ids.iter().map(|id| id.len() as u64).sum())?;
                                let facts = reader
                                    .select(&FactFilter {
                                        ids: ids.to_vec(),
                                        ..Default::default()
                                    })
                                    .map_err(anyhow::Error::new)?;
                                if budget.exhausted() {
                                    return Err(ResourceBudget::error().into());
                                }
                                for fact in facts {
                                    if matches!(
                                        fact.kind,
                                        FactKind::Declaration | FactKind::ExternalSymbol
                                    ) {
                                        roots.insert(fact.id.to_string());
                                    } else {
                                        for id in [fact.parent, fact.target] {
                                            if !id.is_empty() && !seen.contains(&id) {
                                                pending.insert(id);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if !pending.is_empty() {
                            evidence.enumeration = false;
                            evidence.limits.push("impact_endpoint_depth".into());
                        }
                        // Occurrence parents may be call sites, and added/removed
                        // endpoints need not exist on both sides. Only resolved
                        // entity roots enter the incoming-call solver.
                        for symbol in roots {
                            if budget.charge(1, symbol.len() as u64).is_err() {
                                break 'impact;
                            }
                            let request = Request {
                                operation: Operation::Calls,
                                selector: symbol.clone(),
                                direction: Direction::Incoming,
                                depth: req.depth.max(1),
                                ..Default::default()
                            };
                            let output = codeana_query::run(reader, &request, &|source| {
                                self.store.blob_bounded(&source.hash, source.size)
                            })?;
                            evidence.merge(&output.evidence);
                            for record in output.records {
                                if let Record::Call(edge) = record {
                                    rows.push(DiffRecord::Impact(ImpactRecord {
                                        side: side.into(),
                                        changed: symbol.clone(),
                                        edge,
                                        possible: true,
                                    }));
                                }
                            }
                        }
                    }
                }
                if budget.exhausted() {
                    return Ok(());
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
            Ok(())
        })();
        if let Err(e) = compared
            && !e
                .downcast_ref::<DomainError>()
                .is_some_and(|e| e.code == ErrorCode::ComputationLimit)
        {
            return Err(e);
        }
        if section == "impact"
            && budget.exhausted()
            && !evidence
                .reasons
                .iter()
                .any(|r| r == "static_possible_impact")
        {
            rows.clear();
        }
        budget.apply(&mut evidence);
        let mut output = Output {
            records: rows.iter().map(Record::display).collect::<Result<_, _>>()?,
            evidence,
            producer: "rust-diff-v1".into(),
            ..Default::default()
        };
        output.delivery.context.comparison = Some(ComparisonContext {
            section: section.to_owned(),
            base: req.base.clone(),
            target: req.target.clone(),
            base_kind: base.kind.clone(),
            target_kind: target.kind.clone(),
            base_identity: if base.revision.is_empty() {
                base.manifest.id()
            } else {
                base.revision.clone()
            },
            target_identity: if target.revision.is_empty() {
                target.manifest.id()
            } else {
                target.revision.clone()
            },
        });
        Ok(self.page(req, project, output, refs)?.output)
    }
    pub(crate) fn resource_page(
        &self,
        req: &Request,
        project: &str,
        revision: &str,
        budget: &ResourceBudget,
        refs: Vec<String>,
    ) -> Result<TaskResult> {
        let mut evidence = Evidence::complete();
        budget.apply(&mut evidence);
        self.page(
            req,
            project,
            Output {
                revision: revision.into(),
                producer: "rust-query-v1".into(),
                evidence,
                ..Default::default()
            },
            refs,
        )
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
        reader: &dyn GraphReader,
        refs: Vec<String>,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        let mut spool = self.store.page_spool()?;
        let output = codeana_query::stream_search(reader, req, &mut |batch| {
            ensure!(
                !cancel.is_cancelled(),
                codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
            );
            spool.append(&batch)
        })?;
        ensure!(
            !cancel.is_cancelled(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
        );
        self.freeze_page(req, project, output, refs, spool)
    }
    pub(crate) fn text_page(
        &self,
        req: &Request,
        reader: &dyn SourceContext,
        source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
        refs: Vec<String>,
        cancel: &CancellationToken,
    ) -> Result<TaskResult> {
        codeana_query::preflight(req)?;
        let owned_budget;
        let budget = match reader.resource_budget() {
            Some(b) => b,
            None => {
                owned_budget = ResourceBudget::new(codeana_config::query_limits(
                    Path::new(&req.project),
                    &self.store.home,
                )?);
                &owned_budget
            }
        };
        let reader = &BudgetedSource {
            inner: reader,
            budget,
        };
        let candidates = if !req.text_regex && !req.text_ignore_case {
            match self.store.text_candidates_budgeted(
                reader.manifest(),
                &req.selector,
                &|s| {
                    budget.charge(1, s.size)?;
                    source(s)
                },
                &|| {
                    budget.charge(1, 0)?;
                    ensure!(
                        !cancel.is_cancelled(),
                        codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                    );
                    Ok(())
                },
                Some(budget),
            ) {
                Ok(candidates) => candidates,
                Err(error)
                    if error.downcast_ref::<DomainError>().is_some_and(|error| {
                        matches!(
                            error.code,
                            ErrorCode::Cancelled
                                | ErrorCode::SourceHashMismatch
                                | ErrorCode::ComputationLimit
                        )
                    }) =>
                {
                    if error
                        .downcast_ref::<DomainError>()
                        .is_some_and(|e| e.code == ErrorCode::ComputationLimit)
                    {
                        let mut output = Output {
                            evidence: Evidence::complete(),
                            revision: reader.revision().id.to_string(),
                            producer: reader.revision().producer.clone(),
                            ..Default::default()
                        };
                        budget.apply(&mut output.evidence);
                        return self.page(req, &reader.revision().project, output, refs);
                    }
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
                ensure!(
                    !cancel.is_cancelled(),
                    codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
                );
                if batch.is_empty() {
                    Ok(())
                } else {
                    spool.append(&batch)
                }
            },
        )?;
        ensure!(
            !cancel.is_cancelled(),
            codeana_model::DomainError::new(codeana_model::ErrorCode::Cancelled, "")
        );
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
        ensure!(
            output.records.is_empty(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::PageMetadataContainsRecords,
                ""
            )
        );
        let id = new_id("page");
        let page = Page {
            projection_version: PUBLIC_PROJECTION_VERSION,
            request: req.clone(),
            order_policy: QueryOrderPolicy::for_operation(req.operation),
            output,
            options: options(req),
            created: now(),
            total: spool.count(),
        };
        self.store.freeze_page(&id, project, &page, &refs, spool)?;
        self.slice_page(req, project, &id, page, 0)
    }
    pub(crate) fn restore_request(&self, incoming: &Request) -> Result<Request> {
        if incoming.cursor.is_empty() {
            return Ok(incoming.clone());
        }
        let (id, _) = incoming
            .cursor
            .rsplit_once(':')
            .ok_or_else(|| DomainError::new(ErrorCode::InvalidCursor, "missing page offset"))?;
        let project = self.store.page_project(id)?;
        let stored: Page<Box<serde_json::value::RawValue>> = self
            .store
            .object("page", id, &project)
            .context(DomainError::new(
                ErrorCode::InvalidCursor,
                "invalid frozen result",
            ))?;
        ensure!(
            stored.projection_version == PUBLIC_PROJECTION_VERSION,
            DomainError::new(
                ErrorCode::InvalidCursor,
                "projection changed; issue a new query explicitly"
            )
        );
        ensure!(
            now().saturating_sub(stored.created) < 86400,
            DomainError::new(ErrorCode::CursorExpired, "")
        );
        let mut original = stored.request;
        ensure!(
            incoming.operation == original.operation,
            DomainError::new(
                ErrorCode::CursorOptionsMismatch,
                "operation differs from frozen request"
            )
        );
        ensure!(
            incoming.selector.is_empty(),
            DomainError::new(
                ErrorCode::CursorOptionsMismatch,
                "omit selector when resuming"
            )
        );
        for supplied in &incoming.delivery.supplied {
            ensure!(
                [
                    "cursor",
                    "max_records",
                    "max_output_bytes",
                    "format",
                    "detail",
                    "export",
                    "deadline_seconds",
                    "request_id",
                    "project",
                    "home"
                ]
                .contains(&supplied.as_str()),
                DomainError::new(
                    ErrorCode::CursorOptionsMismatch,
                    format!("omit {supplied} when resuming")
                )
            );
        }
        if incoming.delivery.project_explicit {
            ensure!(
                project_path(Path::new(&incoming.project))? == Path::new(&original.project),
                DomainError::new(ErrorCode::ProjectMismatch, "cursor project differs")
            );
        }
        if let Some(format) = incoming.delivery.format {
            ensure!(
                format == original.delivery.format(),
                DomainError::new(ErrorCode::CursorOptionsMismatch, "format differs")
            );
        }
        if let Some(detail) = incoming.delivery.detail {
            ensure!(
                detail == original.delivery.detail(),
                DomainError::new(ErrorCode::CursorOptionsMismatch, "detail differs")
            );
        }
        original.request_id = incoming.request_id.clone();
        original.cursor = incoming.cursor.clone();
        original.background = false;
        if incoming.max_records != 0
            || incoming
                .delivery
                .supplied
                .iter()
                .any(|s| s == "max_records")
        {
            original.max_records = incoming.max_records;
        }
        if incoming.max_output_bytes != 0
            || incoming
                .delivery
                .supplied
                .iter()
                .any(|s| s == "max_output_bytes")
        {
            original.max_output_bytes = incoming.max_output_bytes;
        }
        original.deadline_seconds = incoming.deadline_seconds;
        original.delivery.clear_presence();
        Ok(original)
    }

    pub(crate) fn continue_page(&self, req: &Request, project: &str) -> Result<TaskResult> {
        let (id, offset) = req
            .cursor
            .rsplit_once(':')
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::InvalidCursor,
                "",
            ))?;
        let stored: Page<Box<serde_json::value::RawValue>> =
            self.store.object("page", id, project)?;
        let page = Page {
            projection_version: stored.projection_version,
            request: stored.request,
            order_policy: stored.order_policy,
            output: serde_json::from_str(stored.output.get())?,
            options: stored.options,
            created: stored.created,
            total: stored.total,
        };
        ensure!(
            page.options == options(req),
            codeana_model::DomainError::new(codeana_model::ErrorCode::CursorOptionsMismatch, "")
        );
        ensure!(
            now().saturating_sub(page.created) < 86400,
            codeana_model::DomainError::new(codeana_model::ErrorCode::CursorExpired, "")
        );
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
        let total = page.total;
        ensure!(
            offset <= total,
            codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidCursorOffset, "")
        );
        let limit = if req.max_records == 0 {
            100
        } else {
            req.max_records as usize
        };
        ensure!(
            (1..=10000).contains(&limit),
            codeana_model::DomainError::new(codeana_model::ErrorCode::RecordBudget, "")
        );
        output.records = self.store.page_records(id, project, offset, limit)?;
        ensure!(
            output.records.len() == (total - offset).min(limit),
            DomainError::new(ErrorCode::IncompleteFrozenPage, "")
        );
        let bytes_limit = if req.max_output_bytes == 0 {
            32768
        } else {
            req.max_output_bytes as usize
        };
        ensure!(
            bytes_limit >= 2048,
            codeana_model::DomainError::new(codeana_model::ErrorCode::OutputBudgetTooSmall, "")
        );
        let count = output.records.len();
        let mut result = TaskResult::success(output);
        set_delivery_page(&mut result, id, offset, total);
        crate::output::prepare(req, &mut result);
        let fits_all = crate::presentation::encode(req, &result)?.len() <= bytes_limit;
        let mut low = if fits_all { count } else { 0 };
        let mut high = count;
        while low < high {
            let middle = low + (high - low).div_ceil(2);
            let tail = result.output.records.split_off(middle);
            set_delivery_page(&mut result, id, offset, total);
            crate::output::prepare(req, &mut result);
            let size = crate::presentation::encode(req, &result)?.len();
            result.output.records.extend(tail);
            if size <= bytes_limit {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        ensure!(
            low > 0 || offset == total,
            DomainError::new(
                ErrorCode::RecordExceedsOutputBudget,
                "a complete public record and required status do not fit"
            )
        );
        result.output.records.truncate(low);
        set_delivery_page(&mut result, id, offset, total);
        crate::output::prepare(req, &mut result);
        ensure!(
            crate::presentation::encode(req, &result)?.len() <= bytes_limit,
            DomainError::new(ErrorCode::TerminalOutputBudget, "")
        );
        Ok(result)
    }
}
fn set_delivery_page(result: &mut TaskResult, id: &str, offset: usize, total: usize) {
    let returned = result.output.records.len();
    let end = offset + returned;
    result.output.cursor = if end < total {
        format!("{id}:{end}")
    } else {
        String::new()
    };
    result.output.delivery = DeliveryStatus {
        state: if end < total {
            DeliveryState::Page
        } else {
            DeliveryState::Complete
        },
        projection_version: PUBLIC_PROJECTION_VERSION,
        returned,
        offset,
        range_known: true,
        total: result.output.evidence.enumeration.then_some(total),
        strict_rejected: false,
        context: std::mem::take(&mut result.output.delivery.context),
    };
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
        .context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::GitStdout,
            "",
        ))?
        .take(MAX_RESULT as u64 + 1)
        .read_to_end(&mut bytes)?;
    if bytes.len() > MAX_RESULT {
        let _ = child.kill();
        let _ = child.wait();
        bail!(codeana_model::DomainError::new(
            codeana_model::ErrorCode::GitOutputBudget,
            ""
        ));
    }
    ensure!(
        child.wait()?.success(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::GitReadFailed, "")
    );
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn limited_pages_replay_identical_usage_without_refilling() {
        let dir = tempfile::tempdir().unwrap();
        let engine = HistoryService::new(Store::open(dir.path()).unwrap());
        let budget = ResourceBudget::new(ResourceLimits {
            max_work: 1,
            max_payload_bytes: 100,
        });
        budget.charge(1, 0).unwrap();
        assert!(budget.charge(1, 0).is_err());
        let mut evidence = Evidence::complete();
        budget.apply(&mut evidence);
        let usage = evidence.resource_usage.clone();
        let mut request = Request {
            operation: Operation::SearchCode,
            selector: "x".into(),
            max_records: 1,
            max_output_bytes: 32768,
            strict: true,
            ..Default::default()
        };
        let first = engine
            .page(
                &request,
                "p",
                Output {
                    records: (0..3)
                        .map(|n| Record::display(&json!({"id":n})).unwrap())
                        .collect(),
                    evidence,
                    ..Default::default()
                },
                vec![],
            )
            .unwrap();
        assert!(!first.output.evidence.enumeration);
        assert_eq!(first.output.evidence.resource_usage, usage);
        request.cursor = first.output.cursor;
        while !request.cursor.is_empty() {
            let next = engine.continue_page(&request, "p").unwrap();
            assert_eq!(next.output.evidence.resource_usage, usage);
            assert!(!next.output.evidence.enumeration);
            request.cursor = next.output.cursor;
        }
    }
    #[tokio::test]
    async fn frozen_pages_store_only_metadata_and_allow_different_page_sizes() {
        let directory = tempfile::tempdir().unwrap();
        let engine = HistoryService::new(Store::open(directory.path()).unwrap());
        let mut request = Request {
            operation: codeana_model::Operation::SearchCode,
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
                    records: records
                        .iter()
                        .map(|r| Record::display(r).unwrap())
                        .collect(),
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
        assert_eq!(
            actual.iter().map(|r| r.wire().unwrap()).collect::<Vec<_>>(),
            records
        );
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
