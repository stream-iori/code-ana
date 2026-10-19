//! Rebuild the captured directory layout privately, preserving Pyrefly's import rules.
use crate::{Analysis, FrozenModule, facade};
use aho_corasick::{AhoCorasickBuilder, MatchKind};
use anyhow::{Context, Result, ensure};
use codeana_model::{hash, python::AnalysisConfig};
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
    config.inputs.validate().map_err(anyhow::Error::msg)?;
    let scratch = tempfile::Builder::new()
        .prefix("python-frozen-")
        .tempdir_in(&config.scratch)?;
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
    let path_matcher = AhoCorasickBuilder::new()
        .match_kind(MatchKind::LeftmostLongest)
        .build(diagnostic_remap.keys())?;
    let original_paths = diagnostic_remap
        .values()
        .map(String::as_str)
        .collect::<Vec<_>>();
    for context in &config.inputs.contexts {
        check()?;
        let mut roots = Vec::new();
        let mut dependencies = Vec::new();
        let mut modules = Vec::new();
        let mut module_sources = BTreeSet::new();
        let mut owners = BTreeMap::new();
        for id in &context.roots {
            let root = config.inputs.roots.iter().find(|r| &r.id == id).unwrap();
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
                    let Some(text) = sources.get(&(id.clone(), file.path.clone())) else {
                        continue;
                    };
                    let path = directory.join(&file.path).to_string_lossy().into_owned();
                    if config
                        .excluded
                        .contains(&(remap[&path].clone(), context.world(&config.inputs.roots)))
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
            )?
        };
        let translate = |path: &mut String| -> Result<()> {
            if let Some(original) = remap.get(path) {
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
            diagnostic.message = path_matcher.replace_all(&diagnostic.message, &original_paths);
        }
        check()?;
        output.push(WorldAnalysis {
            world: context.world(&config.inputs.roots),
            context: context.clone(),
            modules,
            analysis,
            owners,
        });
    }
    Ok(output)
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
