//! The only module that constructs rust-analyzer's database. No loader or proc-macro server.
use anyhow::{Context, Result, ensure};
use codeana_model::rust::AnalysisConfig;
use ra_ap_base_db::{
    CrateGraphBuilder, CrateName, CrateOrigin, CrateWorkspaceData, DependencyBuilder, Env,
    FileChange, SourceRoot,
};
use ra_ap_cfg::CfgOptions;
use ra_ap_ide_db::RootDatabase;
use ra_ap_intern::Symbol;
use ra_ap_span::Edition;
use ra_ap_vfs::{AbsPathBuf, FileId, VfsPath, file_set::FileSet};
use std::collections::BTreeMap;
use triomphe::Arc;

pub struct AnalyzerFacade {
    pub db: RootDatabase,
    /// Each crate instance owns a separate virtual file, even for identical physical bytes.
    pub files: BTreeMap<FileId, (usize, usize)>,
}

impl AnalyzerFacade {
    pub fn new(config: &AnalysisConfig) -> Result<Self> {
        config.inputs.validate().map_err(anyhow::Error::msg)?;
        // 0.0.349's LRU setter is a no-op; this is not a memory budget.
        let mut db = RootDatabase::new(None);
        let mut change = FileChange::default();
        let mut graph = CrateGraphBuilder::default();
        let mut roots = Vec::new();
        let mut files = BTreeMap::new();
        let mut crates = BTreeMap::new();
        let mut next_file = 0;
        for (crate_index, input) in config.inputs.crates.iter().enumerate() {
            let mut set = FileSet::default();
            let mut root = None;
            for (file_index, file) in config
                .files
                .iter()
                .enumerate()
                .filter(|(_, f)| f.crates.contains(&input.id))
            {
                ensure!(
                    std::fs::metadata(&file.input)?.len() <= 8 << 20,
                    "RUST_SOURCE_BUDGET"
                );
                let bytes = std::fs::read(&file.input)?;
                ensure!(
                    codeana_model::hash(&bytes) == file.hash,
                    "RUST_INPUT_CHANGED: {}",
                    file.path
                );
                let text = String::from_utf8(bytes).context("RUST_SOURCE_ENCODING")?;
                let id = FileId::from_raw(next_file);
                next_file += 1;
                set.insert(
                    id,
                    VfsPath::new_virtual_path(format!("/{crate_index}/{}", file.path)),
                );
                change.change_file(id, Some(text));
                files.insert(id, (crate_index, file_index));
                if file.path == input.root {
                    root = Some(id);
                }
            }
            roots.push(if input.publish {
                SourceRoot::new_local(set)
            } else {
                SourceRoot::new_library(set)
            });
            let mut cfg = CfgOptions::default();
            for value in &input.cfg {
                if let Some((key, value)) = value.split_once('=') {
                    let value: String =
                        serde_json::from_str(value.trim()).context("RUST_CFG_VALUE")?;
                    cfg.insert_key_value(Symbol::intern(key.trim()), Symbol::intern(&value));
                } else {
                    cfg.insert_atom(Symbol::intern(value));
                }
            }
            let mut env = Env::default();
            for (key, value) in &input.environment {
                if key == "OUT_DIR" {
                    env.set(key, format!("/{crate_index}/{value}"));
                } else {
                    env.set(key, value);
                }
            }
            let edition: Edition = input
                .edition
                .parse()
                .map_err(|_| anyhow::anyhow!("RUST_EDITION_INVALID"))?;
            let id = graph.add_crate_root(
                root.context("RUST_CRATE_ROOT_MISSING")?,
                edition,
                Some(
                    CrateName::new(&input.name)
                        .map_err(|e| anyhow::anyhow!(e.to_string()))?
                        .into(),
                ),
                None,
                cfg,
                None,
                env,
                CrateOrigin::Local {
                    repo: None,
                    name: Some(Symbol::intern(&input.name)),
                },
                vec![],
                input.proc_macro,
                Arc::new(AbsPathBuf::assert("/".into())),
                Arc::new(CrateWorkspaceData {
                    target: Err("target layout not supplied".into()),
                    toolchain: None,
                }),
            );
            ensure!(
                crates.insert(input.id.clone(), id).is_none(),
                "RUST_DUPLICATE_CRATE"
            );
        }
        for input in &config.inputs.crates {
            for (name, target) in &input.dependencies {
                let target = *crates.get(target).context("RUST_DEPENDENCY_MISSING")?;
                let name = CrateName::new(name).map_err(|e| anyhow::anyhow!(e.to_string()))?;
                graph
                    .add_dep(crates[&input.id], DependencyBuilder::new(name, target))
                    .map_err(|_| anyhow::anyhow!("RUST_DEPENDENCY_CYCLE"))?;
            }
        }
        change.set_roots(roots);
        change.set_crate_graph(graph);
        change.apply(&mut db);
        Ok(Self { db, files })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::rust::{CrateInput, FrozenFile, Inputs};
    use ra_ap_hir::Semantics;
    use ra_ap_syntax::{AstNode, ast};

    #[test]
    fn resolves_a_real_cross_crate_call_without_cargo_or_lsp() -> Result<()> {
        let scratch = tempfile::tempdir_in(env!("CARGO_MANIFEST_DIR"))?;
        let mut files = Vec::new();
        for (name, text) in [
            ("library", "pub fn answer() -> u32 { 42 }"),
            ("app", "fn main() { library::answer(); }"),
        ] {
            let input = scratch.path().join(format!("{name}.rs"));
            std::fs::write(&input, text)?;
            files.push(FrozenFile {
                path: format!("{name}/lib.rs"),
                hash: codeana_model::hash(text),
                input,
                crates: vec![name.into()],
            });
        }
        let make_crate = |name: &str| CrateInput {
            id: name.into(),
            name: name.into(),
            module: name.into(),
            world: name.into(),
            scope: "MAIN".into(),
            root: format!("{name}/lib.rs"),
            directory: name.into(),
            edition: "2024".into(),
            target: "test".into(),
            cfg: vec![],
            dependencies: BTreeMap::new(),
            environment: BTreeMap::new(),
            publish: true,
            proc_macro: false,
            gaps: vec![],
        };
        let mut app = make_crate("app");
        app.dependencies.insert("library".into(), "library".into());
        let config = AnalysisConfig {
            baseline: None,
            excluded: Default::default(),
            inputs: Inputs {
                schema_version: 2,
                policy: codeana_model::hash(b"fixture-policy"),
                dependency_sources: BTreeMap::new(),
                omitted_dependencies: BTreeMap::new(),
                selection: Default::default(),
                toolchain: "fixture".into(),
                crates: vec![make_crate("library"), app],
                controls: BTreeMap::new(),
                generated: BTreeMap::new(),
            },
            files,
            mode: "semantic".into(),
            scratch: scratch.path().into(),
            result_bytes: 1 << 20,
            fact_limit: 1000,
        };
        let analyzer = AnalyzerFacade::new(&config)?;
        ra_ap_hir::attach_db(&analyzer.db, || {
            let semantics = Semantics::new(&analyzer.db);
            let app_file = analyzer.files.iter().find(|(_, (c, _))| *c == 1).unwrap().0;
            let source = semantics.parse_guess_edition(*app_file);
            let call = source
                .syntax()
                .descendants()
                .find_map(ast::CallExpr::cast)
                .unwrap();
            let ast::Expr::PathExpr(path) = call.expr().unwrap() else {
                panic!("path call expected")
            };
            let target = semantics
                .resolve_path(&path.path().unwrap())
                .expect("cross-crate binding");
            assert!(matches!(
                target,
                ra_ap_hir::PathResolution::Def(ra_ap_hir::ModuleDef::Function(_))
            ));
            Ok(())
        })
    }
}
