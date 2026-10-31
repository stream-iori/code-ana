//! Rebuild the captured directory layout privately, preserving Pyrefly's import rules.
use crate::{Analysis, FrozenModule, facade};
use aho_corasick::{AhoCorasick, AhoCorasickBuilder, AhoCorasickKind, MatchKind};
use anyhow::{Context, Result, ensure};
use codeana_model::{AnalysisContext, hash, python::AnalysisConfig};
use pyrefly_python::module_name::ModuleName;
use std::{
    collections::{BTreeMap, BTreeSet},
    io::Read,
    path::Path,
};

pub struct WorldAnalysis {
    pub world: String,
    pub context: codeana_model::python::Context,
    pub modules: Vec<FrozenModule>,
    pub analysis: Analysis,
    pub owners: BTreeMap<String, String>,
}

impl WorldAnalysis {
    pub fn module(&self, path: &str) -> String {
        self.owners
            .get(path)
            .cloned()
            .unwrap_or_else(|| self.context.module.clone())
    }
}

pub fn analyze(
    config: &AnalysisConfig,
    check: &dyn Fn() -> Result<()>,
) -> Result<Vec<WorldAnalysis>> {
    analyze_with_context(
        config,
        check,
        &AnalysisContext::new(Default::default(), None)?,
    )
}

pub fn analyze_with_context(
    config: &AnalysisConfig,
    check: &dyn Fn() -> Result<()>,
    budget: &AnalysisContext,
) -> Result<Vec<WorldAnalysis>> {
    config.inputs.validate().map_err(anyhow::Error::msg)?;
    let scratch = tempfile::Builder::new()
        .prefix("python-frozen-")
        .tempdir_in(&config.scratch)?;
    budget.charge(
        config.files.len() as u64,
        config.files.len() as u64,
        (config.files.len() as u64).saturating_mul(256),
    )?;
    let descriptors = config
        .files
        .iter()
        .map(|f| ((f.root.as_str(), f.relative.as_str()), f))
        .collect::<BTreeMap<_, _>>();
    ensure!(
        descriptors.len() == config.files.len()
            && config.files.len()
                == config
                    .inputs
                    .roots
                    .iter()
                    .map(|r| r.files.len())
                    .sum::<usize>(),
        "PYTHON_FROZEN_DESCRIPTOR_SET"
    );
    let mut remap = BTreeMap::new();
    let mut sources = BTreeMap::new();
    let mut bytes = 0u64;
    for root in &config.inputs.roots {
        for file in &root.files {
            check()?;
            let input = descriptors
                .get(&(root.id.as_str(), file.path.as_str()))
                .context("PYTHON_FROZEN_FILE_MISSING")?;
            ensure!(
                input.sha256 == file.sha256
                    && input.bytes == file.bytes
                    && codeana_model::safe_path(&input.source),
                "PYTHON_FROZEN_IDENTITY"
            );
            budget.charge(
                1,
                3,
                file.bytes.saturating_add(
                    (file.path.len() + root.id.len() + input.source.len() + 512) as u64,
                ),
            )?;
            let mut contents = Vec::new();
            std::fs::File::open(&input.input)?
                .take(file.bytes + 1)
                .read_to_end(&mut contents)?;
            ensure!(
                contents.len() as u64 == file.bytes && hash(&contents) == file.sha256,
                "PYTHON_FROZEN_INPUT_CHANGED"
            );
            bytes = bytes
                .checked_add(file.bytes)
                .context("PYTHON_SCRATCH_BUDGET")?;
            ensure!(bytes <= config.scratch_bytes, "PYTHON_SCRATCH_BUDGET");
            let target = scratch.path().join(&root.id).join(&file.path);
            std::fs::create_dir_all(target.parent().unwrap())?;
            std::fs::write(&target, &contents)?;
            remap.insert(target.to_string_lossy().into_owned(), input.source.clone());
            if file.path.ends_with(".py") || file.path.ends_with(".pyi") {
                let text = decode_source(contents)?;
                sources.insert((root.id.clone(), file.path.clone()), text);
            }
        }
    }
    let mut output = Vec::new();
    budget.charge(
        remap.len() as u64,
        remap.len() as u64,
        remap
            .iter()
            .map(|(k, v)| (k.len() + v.len() + 128) as u64)
            .sum(),
    )?;
    let mut diagnostic_remap = remap.clone();
    for root in &config.inputs.roots {
        diagnostic_remap.insert(
            scratch.path().join(&root.id).to_string_lossy().into_owned(),
            if root.publish {
                root.path.clone()
            } else {
                format!(".code-ana/python-dependencies/{}", root.id)
            },
        );
    }
    // Scan each diagnostic once, rather than cloning it for every captured path.
    let path_matcher = diagnostic_matcher(&diagnostic_remap, budget)?;
    let original_paths = diagnostic_remap
        .values()
        .map(String::as_str)
        .collect::<Vec<_>>();
    for context in &config.inputs.contexts {
        let strings = context
            .roots
            .iter()
            .chain(context.publish_roots.iter().flatten())
            .chain(context.gaps.iter())
            .map(|s| s.len() as u64 + 64)
            .sum::<u64>();
        budget.charge(
            (config.inputs.roots.len() as u64)
                .saturating_mul(context.roots.len() as u64)
                .saturating_add(1),
            1,
            2048 + strings
                + (context.id.len()
                    + context.module.len()
                    + context.python_version.len()
                    + context.platform.len()) as u64,
        )?;
        let world = context.world(&config.inputs.roots);
        check()?;
        let mut roots = Vec::new();
        let mut dependencies = Vec::new();
        let mut modules = Vec::new();
        let mut module_sources = BTreeSet::new();
        let mut owners = BTreeMap::new();
        for id in &context.roots {
            let root = config.inputs.roots.iter().find(|r| &r.id == id).unwrap();
            budget.charge(1, 1, (id.len() + 256) as u64)?;
            let directory = scratch.path().join(id);
            std::fs::create_dir_all(&directory)?;
            if root.publish {
                roots.push(directory.clone());
            } else {
                dependencies.push(directory.clone());
            }
            // Explicit source handles are checked; dependencies load only when needed.
            if context.publishes(root) {
                for file in &root.files {
                    budget.charge(1, 0, (id.len() + file.path.len() + 128) as u64)?;
                    let Some(text) = sources.get(&(id.clone(), file.path.clone())) else {
                        continue;
                    };
                    budget.charge(
                        1,
                        4,
                        (text.len() + file.path.len() * 3 + root.id.len() + 1024) as u64,
                    )?;
                    let path = directory.join(&file.path).to_string_lossy().into_owned();
                    if config
                        .excluded
                        .contains(&(remap[&path].clone(), world.clone()))
                    {
                        continue;
                    }
                    // Overlapping ordered roots can expose one source more than once.
                    // Its primary module comes from the first root containing it.
                    if !module_sources.insert(remap[&path].clone()) {
                        continue;
                    }
                    let name = ModuleName::from_relative_path(Path::new(&file.path))?;
                    owners.insert(
                        remap[&path].clone(),
                        root.module
                            .clone()
                            .unwrap_or_else(|| context.module.clone()),
                    );
                    modules.push(FrozenModule {
                        name: name.as_str().into(),
                        path,
                        source: text.clone(),
                    });
                }
            }
        }
        let mut analysis = if modules.is_empty() {
            Analysis::default()
        } else {
            facade::analyze_disk(
                &modules,
                &context.python_version,
                &context.platform,
                &roots,
                &dependencies,
                check,
                budget,
            )?
        };
        let translate = |path: &mut String| -> Result<()> {
            if let Some(original) = remap.get(path) {
                budget.charge(1, 1, original.len() as u64)?;
                *path = original.clone();
            } else {
                ensure!(!Path::new(path).is_absolute(), "PYTHON_UNCAPTURED_SOURCE");
            }
            Ok(())
        };
        for d in &mut analysis.declarations {
            translate(&mut d.path)?;
        }
        for c in &mut analysis.calls {
            translate(&mut c.path)?;
            for target in &mut c.targets {
                translate(&mut target.path)?;
            }
        }
        for r in &mut analysis.references {
            translate(&mut r.path)?;
            for target in &mut r.targets {
                translate(&mut target.path)?;
            }
        }
        for module in &mut modules {
            translate(&mut module.path)?;
        }
        for diagnostic in &mut analysis.diagnostics {
            translate(&mut diagnostic.path)?;
            let mut affected = BTreeSet::new();
            for mut path in std::mem::take(&mut diagnostic.affected_sources) {
                translate(&mut path)?;
                affected.insert(path);
            }
            diagnostic.affected_sources = affected;
            diagnostic.message =
                rewrite_diagnostic(&path_matcher, &diagnostic.message, &original_paths, budget)?;
        }
        check()?;
        output.push(WorldAnalysis {
            world,
            context: context.clone(),
            modules,
            analysis,
            owners,
        });
    }
    Ok(output)
}

// Select a predictable sparse NFA, then reserve its state/transition capacity
// before the third-party builder can allocate it. No unbounded default DFA.
fn diagnostic_matcher(
    paths: &BTreeMap<String, String>,
    budget: &AnalysisContext,
) -> Result<AhoCorasick> {
    let characters = paths
        .keys()
        .fold(1u64, |n, p| n.saturating_add(p.len() as u64));
    budget.charge(
        characters,
        characters,
        characters.saturating_mul(512).saturating_add(65536),
    )?;
    Ok(AhoCorasickBuilder::new()
        .kind(Some(AhoCorasickKind::NoncontiguousNFA))
        .match_kind(MatchKind::LeftmostLongest)
        .build(paths.keys())?)
}
fn rewrite_diagnostic(
    matcher: &AhoCorasick,
    message: &str,
    replacements: &[&str],
    budget: &AnalysisContext,
) -> Result<String> {
    budget.charge(message.len() as u64, 0, 128)?;
    let mut capacity = message.len() as u64;
    for found in matcher.find_iter(message) {
        budget.charge(1, 0, 0)?;
        capacity = capacity
            .saturating_sub(found.len() as u64)
            .saturating_add(replacements[found.pattern().as_usize()].len() as u64);
    }
    budget.charge(1, 1, capacity.saturating_add(128))?;
    Ok(matcher.replace_all(message, replacements))
}

fn decode_source(bytes: Vec<u8>) -> Result<String> {
    let text = String::from_utf8(bytes).context("PYTHON_ENCODING_UNSUPPORTED")?;
    for line in text.trim_start_matches('\u{feff}').lines().take(2) {
        let Some(comment) = line.trim_start().strip_prefix('#') else {
            continue;
        };
        let Some((_, suffix)) = comment.split_once("coding") else {
            continue;
        };
        let Some(suffix) = suffix
            .strip_prefix(':')
            .or_else(|| suffix.strip_prefix('='))
        else {
            continue;
        };
        let encoding = suffix
            .trim_start()
            .split(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.')))
            .next()
            .unwrap_or("")
            .to_ascii_lowercase();
        ensure!(
            matches!(
                encoding.as_str(),
                "utf-8" | "utf_8" | "utf8" | "ascii" | "us-ascii"
            ),
            "PYTHON_ENCODING_UNSUPPORTED: {encoding}"
        );
        if matches!(encoding.as_str(), "ascii" | "us-ascii") {
            ensure!(text.is_ascii(), "PYTHON_ENCODING_INVALID");
        }
    }
    Ok(text)
}

#[cfg(test)]
mod resource_tests {
    use super::*;
    #[test]
    fn diagnostic_growth_is_admitted_before_replacement() {
        let matcher = AhoCorasick::new(["x"]).unwrap();
        let budget = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_payload_bytes: 1000,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(rewrite_diagnostic(&matcher, "xxxxxxxxxx", &[&"a".repeat(1000)], &budget).is_err());
        assert!(budget.usage().failure.is_some());
    }
    #[test]
    fn diagnostic_rewrite_preserves_leftmost_longest_and_accumulates() {
        let budget = AnalysisContext::new(Default::default(), None).unwrap();
        let paths = BTreeMap::from([
            ("/scratch/a".into(), "A.py".into()),
            ("/scratch/ab".into(), "B.py".into()),
        ]);
        let matcher = diagnostic_matcher(&paths, &budget).unwrap();
        let values = paths.values().map(String::as_str).collect::<Vec<_>>();
        let before = budget.usage().payload_bytes;
        assert_eq!(
            rewrite_diagnostic(&matcher, "/scratch/ab /scratch/a", &values, &budget).unwrap(),
            "B.py A.py"
        );
        assert!(budget.usage().payload_bytes > before);
    }
    #[test]
    fn diagnostic_automaton_denies_before_building_states() {
        let budget = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_nodes: 4,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        assert!(
            diagnostic_matcher(
                &BTreeMap::from([("long-path".into(), "A.py".into())]),
                &budget
            )
            .is_err()
        );
        assert_eq!(budget.usage().nodes, 0);
    }
}
