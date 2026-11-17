//! Freeze publication scopes from declared inputs, including sources emitting no rows.
use super::*;
use std::collections::BTreeSet;

pub(super) fn freeze(
    capture: &Capture,
    contexts: &[&BuildContext],
    mut admit: impl FnMut(AnalysisBackend, u64) -> Result<()>,
) -> Result<UnitOwnershipLedger> {
    let mut ledger = UnitOwnershipLedger::default();
    for context in contexts {
        let backend = context.details.backend();
        let view = capture
            .unit_views
            .get(&context.unit)
            .context("UNIT_OWNERSHIP_VIEW_REQUIRED")?;
        ensure!(view.backend == backend, "UNIT_OWNERSHIP_BACKEND");
        admit(
            backend,
            (context.unit.len()
                + context.producer.len()
                + 1024
                + view.manifest.files.len() * std::mem::size_of::<&SourceUnit>())
                as u64
                + 4 * codeana_model::retained_size(&view.manifest)?,
        )?;
        let mut sources = Vec::new();
        for source in &view.manifest.files {
            if publication_language(source).is_some_and(|l| context.source_languages.contains(&l)) {
                admit(
                    backend,
                    (2 * (std::mem::size_of::<SourceUnit>()
                        + source.path.len()
                        + source.hash.len()
                        + source.world.len()
                        + source.module.len()
                        + source.scope.len()
                        + source.language.len())) as u64,
                )?;
                sources.push(source.clone());
            }
        }
        sources.sort_by(|a, b| (&a.world, &a.path).cmp(&(&b.world, &b.path)));
        let prepared = capture
            .effective
            .input_units
            .iter()
            .find(|u| u.prepared.id == context.unit.as_str());
        let module_bytes = match prepared {
            Some(unit) => unit
                .prepared
                .modules
                .iter()
                .map(|m| 2 * m.len() + 256)
                .sum::<usize>(),
            None => capture
                .effective
                .config
                .modules
                .iter()
                .filter(|m| crate::index_plan::language(&m.language) == Some(backend))
                .map(|m| 2 * m.id.len() + 256)
                .sum(),
        };
        admit(backend, module_bytes as u64)?;
        let declared_modules = match prepared {
            Some(unit) => unit
                .prepared
                .modules
                .iter()
                .map(|m| m.as_str())
                .collect::<Vec<_>>(),
            None => capture
                .effective
                .config
                .modules
                .iter()
                .filter(|m| crate::index_plan::language(&m.language) == Some(backend))
                .map(|m| m.id.as_str())
                .collect(),
        };
        let modules = sources
            .iter()
            .map(|s| s.module.clone())
            .chain(declared_modules.into_iter().map(str::to_owned))
            .filter(|m| !m.is_empty())
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        let worlds = sources
            .iter()
            .map(|s| s.world.clone())
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        let semantic = &view.semantic_digest;
        // Generation receipts and root-config presentation bytes are integrity inputs,
        // rather than semantic inputs. Their canonical policy is in `semantic`.
        let inputs = view
            .manifest
            .files
            .iter()
            .filter(|s| s.scope != "PREPARATION" && s.path != ".code-ana/config.toml")
            .collect::<Vec<_>>();
        let unit_config = prepared
            .and_then(|u| u.context.as_ref())
            .map(|c| &c.config)
            .unwrap_or(&capture.effective.config);
        let scopes = unit_config
            .scan
            .scopes
            .iter()
            .cloned()
            .chain(
                unit_config
                    .modules
                    .iter()
                    .flat_map(|m| m.source_roots.iter().map(|r| r.scope.clone())),
            )
            .chain(sources.iter().map(|s| s.scope.clone()))
            .collect::<BTreeSet<_>>()
            .into_iter()
            .collect();
        ledger.units.push(UnitOwnership {
            inputs: view.manifest.files.clone(),
            scopes,
            unit: context.unit.clone(),
            backend,
            availability: UnitAvailability::Ready,
            source_languages: context.source_languages.clone(),
            modules,
            worlds,
            producer: context.producer.clone(),
            semantic_profile: digest(&(&semantic, &context.semantic_digest)),
            input_digest: digest(&inputs),
            sources,
        });
    }
    ensure!(
        contexts.len() == capture.unit_views.len(),
        "UNIT_OWNERSHIP_CONTEXT_REQUIRED"
    );
    if capture.effective.input_units.is_empty() {
        let plan = crate::index_plan::IndexPlan::with_baseline(capture, None, None)?;
        for unit in plan
            .units
            .iter()
            .filter(|u| u.availability == UnitAvailability::FilesOnly)
        {
            let modules = capture
                .effective
                .config
                .modules
                .iter()
                .filter(|m| crate::index_plan::language(&m.language) == Some(unit.language))
                .map(|m| m.id.clone())
                .collect::<BTreeSet<_>>()
                .into_iter()
                .collect();
            admit(unit.language, (unit.id.len() + 1024) as u64)?;
            ledger.units.push(UnitOwnership {
                inputs: vec![],
                scopes: capture
                    .effective
                    .config
                    .scan
                    .scopes
                    .iter()
                    .cloned()
                    .collect::<BTreeSet<_>>()
                    .into_iter()
                    .collect(),
                unit: unit.id.clone(),
                backend: unit.language,
                availability: UnitAvailability::FilesOnly,
                source_languages: vec![
                    SourceLanguage::lookup(unit.language.as_str()).context("INPUT_LANGUAGE")?,
                ],
                modules,
                worlds: vec![],
                producer: String::new(),
                semantic_profile: String::new(),
                input_digest: String::new(),
                sources: vec![],
            });
        }
    }
    for unit in capture
        .effective
        .input_units
        .iter()
        .filter(|u| u.context.is_none())
    {
        let backend =
            crate::index_plan::language(&unit.prepared.language).context("INPUT_LANGUAGE")?;
        admit(
            backend,
            (unit.prepared.id.len() + 1024) as u64 + 2 * retained_size(&unit.prepared.modules)?,
        )?;
        let mut modules = if unit.prepared.modules.is_empty() {
            unit.prepared.entries.clone()
        } else {
            unit.prepared.modules.clone()
        };
        modules.sort();
        modules.dedup();
        ledger.units.push(UnitOwnership {
            inputs: vec![],
            scopes: capture
                .effective
                .config
                .scan
                .scopes
                .iter()
                .cloned()
                .collect::<BTreeSet<_>>()
                .into_iter()
                .collect(),
            unit: unit.prepared.id.as_str().into(),
            backend,
            availability: UnitAvailability::FilesOnly,
            source_languages: vec![
                SourceLanguage::lookup(&unit.prepared.language).context("INPUT_LANGUAGE")?,
            ],
            modules,
            worlds: vec![],
            producer: String::new(),
            semantic_profile: String::new(),
            input_digest: String::new(),
            sources: vec![],
        });
    }
    ledger
        .units
        .sort_by(|a, b| (a.backend, &a.unit).cmp(&(b.backend, &b.unit)));
    ledger
        .validate(&capture.manifest)
        .map_err(anyhow::Error::msg)?;
    Ok(ledger)
}

impl crate::indexing::IndexTask {
    pub(super) fn ownership(
        &self,
        capture: &Capture,
        contexts: &[&BuildContext],
    ) -> Result<Option<UnitOwnershipLedger>> {
        if self.workers.is_mock() {
            return Ok(None);
        }
        let coordinator = self.task.analysis.coordinator.lock().unwrap().clone();
        Ok(Some(freeze(capture, contexts, |backend, bytes| {
            if let Some(coordinator) = &coordinator {
                coordinator
                    .lock()
                    .unwrap()
                    .host(backend)?
                    .charge(1, 1, bytes)
                    .map_err(DomainError::analysis)?;
            }
            Ok(())
        })?))
    }
}
