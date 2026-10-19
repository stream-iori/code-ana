//! The only layer allowed to hold Pyrefly state and compiler objects.
use anyhow::{Context, Result, ensure};
use pyrefly::state::{load::FileContents, lsp::FindPreference, require::Require, state::State};
use pyrefly_build::{handle::Handle, source_db::map_db::MapDatabase};
use pyrefly_config::{base::InferReturnTypes, config::ConfigFile, finder::ConfigFinder};
use pyrefly_python::{module_name::ModuleName, module_path::ModulePath, qname::QName};
use pyrefly_types::{
    function::FunctionKind,
    types::{Forallable, Type},
};
use pyrefly_util::{arc_id::ArcId, thread_pool::ThreadCount};
use ruff_python_ast::{
    Expr, Stmt,
    visitor::{self, Visitor},
};
use ruff_text_size::Ranged;
use serde::{Deserialize, Serialize};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Component, Path},
    sync::Arc,
};

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct FrozenModule {
    pub name: String,
    pub path: String,
    pub source: String,
}

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
pub struct Target {
    pub name: String,
    pub path: String,
    pub start: u32,
    pub end: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Declaration {
    pub name: String,
    pub qualified_name: String,
    pub kind: String,
    pub path: String,
    pub start: u32,
    pub end: u32,
    pub body_start: u32,
    pub body_end: u32,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Call {
    pub path: String,
    pub start: u32,
    pub end: u32,
    pub syntax: String,
    pub targets: Vec<Target>,
    pub unknown_reasons: Vec<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Reference {
    pub relation: String,
    pub path: String,
    pub start: u32,
    pub end: u32,
    pub targets: Vec<Target>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Analysis {
    pub declarations: Vec<Declaration>,
    pub calls: Vec<Call>,
    pub references: Vec<Reference>,
    pub diagnostics: Vec<Diagnostic>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Diagnostic {
    pub path: String,
    pub code: String,
    pub message: String,
    pub start: u32,
    pub end: u32,
    pub attribution: Attribution,
    pub affected_sources: BTreeSet<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Attribution {
    Exact,
    Unknown,
}

fn target(qname: &QName) -> Target {
    Target {
        name: qname.module_qualified_name(),
        path: qname.module_path().as_path().to_string_lossy().into_owned(),
        start: qname.range().start().into(),
        end: qname.range().end().into(),
    }
}

fn function(kind: &FunctionKind, targets: &mut Vec<Target>, unknown: &mut Vec<String>) {
    match kind {
        FunctionKind::Def(def) => targets.push(target(&def.qname)),
        _ => unknown.push("synthesized_callable".into()),
    }
}

// Read inferred types; never resolve a call by matching spelling against names.
fn call_targets(ty: &Type, targets: &mut Vec<Target>, unknown: &mut Vec<String>) {
    match ty {
        Type::Function(f) => function(&f.metadata.kind, targets, unknown),
        Type::BoundMethod(m) => call_targets(&m.func.clone().as_type(), targets, unknown),
        Type::Overload(o) => function(&o.metadata.kind, targets, unknown),
        Type::Forall(f) => match &f.body {
            Forallable::Function(f) => function(&f.metadata.kind, targets, unknown),
            _ => unknown.push("generic_callable_not_extracted".into()),
        },
        Type::Union(u) => {
            for member in &u.members {
                call_targets(member, targets, unknown);
            }
        }
        Type::Any(_) => unknown.push("dynamic_any".into()),
        Type::ClassDef(class) => targets.push(target(class.qname())),
        Type::Callable(_) => unknown.push("callable_target_unknown".into()),
        _ => unknown.push("callable_type_not_extracted".into()),
    }
}

struct AstFacts<'a> {
    statements: Vec<&'a Stmt>,
    expressions: Vec<&'a Expr>,
    bindings: Vec<(&'a str, &'a str, ruff_text_size::TextRange)>,
}

impl<'a> Visitor<'a> for AstFacts<'a> {
    fn visit_except_handler(&mut self, handler: &'a ruff_python_ast::ExceptHandler) {
        let ruff_python_ast::ExceptHandler::ExceptHandler(handler) = handler;
        if let Some(name) = &handler.name {
            self.bindings
                .push((name.as_str(), "variable", name.range()));
        }
        if let Some(type_) = &handler.type_ {
            self.visit_expr(type_);
        }
        self.visit_body(&handler.body);
    }
    fn visit_parameter(&mut self, parameter: &'a ruff_python_ast::Parameter) {
        self.bindings
            .push((parameter.name.as_str(), "parameter", parameter.name.range()));
        visitor::walk_parameter(self, parameter);
    }
    fn visit_alias(&mut self, alias: &'a ruff_python_ast::Alias) {
        let name = alias.asname.as_ref().unwrap_or(&alias.name);
        self.bindings.push((name.as_str(), "import", name.range()));
        visitor::walk_alias(self, alias);
    }
    fn visit_stmt(&mut self, stmt: &'a Stmt) {
        self.statements.push(stmt);
        visitor::walk_stmt(self, stmt);
    }
    fn visit_expr(&mut self, expr: &'a Expr) {
        self.expressions.push(expr);
        if let Expr::Name(name) = expr
            && name.ctx == ruff_python_ast::ExprContext::Store
        {
            self.bindings
                .push((name.id.as_str(), "variable", name.range()));
        }
        visitor::walk_expr(self, expr);
    }
}

/// P0 core extraction over supplied bytes. No target file/config/interpreter IO.
/// Module resolution uses the explicit source database plus the pinned bundled
/// stdlib. This is not yet a project discovery API or a complete graph producer.
pub fn analyze(modules: &[FrozenModule], version: &str, platform: &str) -> Result<Analysis> {
    analyze_inner(modules, version, platform, None, &|| Ok(()))
}

pub(crate) fn analyze_disk(
    modules: &[FrozenModule],
    version: &str,
    platform: &str,
    roots: &[std::path::PathBuf],
    dependencies: &[std::path::PathBuf],
    check: &dyn Fn() -> Result<()>,
) -> Result<Analysis> {
    analyze_inner(
        modules,
        version,
        platform,
        Some((roots, dependencies)),
        check,
    )
}

fn analyze_inner(
    modules: &[FrozenModule],
    version: &str,
    platform: &str,
    disk: Option<(&[std::path::PathBuf], &[std::path::PathBuf])>,
    check: &dyn Fn() -> Result<()>,
) -> Result<Analysis> {
    ensure!(!modules.is_empty(), "PYTHON_NO_INPUTS");
    let mut config = ConfigFile::default();
    config.python_environment.python_version = Some(version.parse()?);
    config.python_environment.python_platform = Some(platform.parse()?);
    config.python_environment.site_package_path = Some(vec![]);
    config.interpreters.skip_interpreter_query = true;
    config.disable_search_path_heuristics = true;
    config.enable_fallback_search_path = false;
    config.use_ignore_files = false;
    config.root.check_unannotated_defs = Some(true);
    config.root.infer_return_types = Some(InferReturnTypes::Checked);
    if let Some((roots, dependencies)) = disk {
        config.search_path_from_args = roots.to_vec();
        config.python_environment.site_package_path = Some(dependencies.to_vec());
    }
    let sys_info = config.get_sys_info();
    let mut source_db = MapDatabase::new(sys_info);
    let mut paths = BTreeSet::new();
    let mut names = BTreeSet::new();
    let mut handles = Vec::new();
    let mut memory = Vec::new();
    for module in modules {
        ensure!(
            disk.is_some()
                || Path::new(&module.path)
                    .components()
                    .all(|c| matches!(c, Component::Normal(_))),
            "PYTHON_INPUT_PATH"
        );
        ensure!(
            paths.insert(&module.path) && (disk.is_some() || names.insert(&module.name)),
            "PYTHON_DUPLICATE_MODULE"
        );
        let path = if disk.is_some() {
            ModulePath::filesystem(module.path.clone().into())
        } else {
            ModulePath::memory(module.path.clone().into())
        };
        let name = ModuleName::from_str(&module.name);
        source_db.insert(name, path.clone());
        handles.push(Handle::new(name, path, sys_info));
        memory.push((
            module.path.clone().into(),
            Some(Arc::new(FileContents::from_source(module.source.clone()))),
        ));
    }
    if disk.is_none() {
        config.source_db = Some(ArcId::new(Box::new(source_db)));
    }
    config.disable_project_excludes_heuristics = true;
    ensure!(config.configure().is_empty(), "PYTHON_BACKEND_CONFIG");
    let state = State::new(
        ConfigFinder::new_constant(ArcId::new(config)),
        ThreadCount::Inline,
    );
    let solving = std::time::Instant::now();
    eprintln!("codeana-python-phase=backend_run");
    let mut transaction = state.new_committable_transaction(Require::Everything, None);
    transaction.as_mut().set_memory(memory);
    transaction
        .as_mut()
        .run(&handles, Require::Everything, None);
    state.commit_transaction(transaction, None);
    eprintln!(
        "codeana-python-timing backend_solve_ms={}",
        solving.elapsed().as_millis()
    );
    eprintln!("codeana-python-phase=backend_facts");
    let extracting = std::time::Instant::now();
    let transaction = state.transaction();
    let mut output = Analysis::default();
    let mut by_path: BTreeMap<String, Vec<Handle>> = BTreeMap::new();
    for handle in transaction.handles() {
        by_path
            .entry(handle.path().as_path().to_string_lossy().into_owned())
            .or_default()
            .push(handle);
    }
    let selected = handles
        .iter()
        .map(|h| h.path().as_path().to_string_lossy().into_owned())
        .collect::<BTreeSet<_>>();
    let mut impacts: BTreeMap<String, (Attribution, BTreeSet<String>)> = BTreeMap::new();
    let errors = transaction.get_all_errors().collect_errors();
    for error in errors.ordinary.into_iter().chain(
        errors
            .suppressed
            .into_iter()
            .chain(errors.disabled)
            .chain(errors.baseline)
            .filter(|error| {
                matches!(
                    error.error_kind().to_name(),
                    "parse-error" | "missing-import" | "missing-source"
                )
            }),
    ) {
        check()?;
        let path = error.path().as_path().to_string_lossy().into_owned();
        if !impacts.contains_key(&path) {
            let mut affected = BTreeSet::new();
            let attribution = if disk.is_some()
                && let Some(origins) = by_path.get(&path)
            {
                for origin in origins {
                    check()?;
                    for dependent in transaction.get_transitive_rdeps(origin.clone()) {
                        let dependent = dependent.path().as_path().to_string_lossy().into_owned();
                        if selected.contains(&dependent) {
                            affected.insert(dependent);
                        }
                    }
                }
                Attribution::Exact
            } else {
                Attribution::Unknown
            };
            impacts.insert(path.clone(), (attribution, affected));
        }
        let (attribution, affected_sources) = impacts[&path].clone();
        output.diagnostics.push(Diagnostic {
            path,
            code: error.error_kind().to_name().into(),
            message: error.msg(),
            start: error.range().start().into(),
            end: error.range().end().into(),
            attribution,
            affected_sources,
        });
    }
    for (module, handle) in modules.iter().zip(&handles) {
        check()?;
        let ast = transaction.get_ast(handle).context("PYTHON_AST_MISSING")?;
        let mut facts = AstFacts {
            statements: vec![],
            expressions: vec![],
            bindings: vec![],
        };
        facts.visit_body(&ast.body);
        let mut declarations = vec![Declaration {
            name: module.name.clone(),
            qualified_name: module.name.clone(),
            kind: "module".into(),
            path: module.path.clone(),
            start: 0,
            end: 0,
            body_start: 0,
            body_end: module.source.len().try_into()?,
        }];
        for stmt in &facts.statements {
            let (name, kind, range) = match stmt {
                Stmt::FunctionDef(f) => (f.name.as_str(), "function", f.name.range()),
                Stmt::ClassDef(c) => (c.name.as_str(), "class", c.name.range()),
                _ => continue,
            };
            let parent = declarations
                .iter()
                .filter(|d| {
                    d.body_start <= u32::from(stmt.start()) && d.body_end >= u32::from(stmt.end())
                })
                .min_by_key(|d| d.body_end - d.body_start)
                .unwrap();
            declarations.push(Declaration {
                name: name.into(),
                qualified_name: format!("{}.{name}", parent.qualified_name),
                kind: kind.into(),
                path: module.path.clone(),
                start: range.start().into(),
                end: range.end().into(),
                body_start: stmt.start().into(),
                body_end: stmt.end().into(),
            });
        }
        for (name, kind, range) in &facts.bindings {
            let parent = declarations
                .iter()
                .filter(|d| {
                    matches!(d.kind.as_str(), "module" | "class" | "function")
                        && d.body_start <= u32::from(range.start())
                        && d.body_end >= u32::from(range.end())
                })
                .min_by_key(|d| d.body_end - d.body_start)
                .unwrap();
            declarations.push(Declaration {
                name: (*name).into(),
                qualified_name: format!("{}.{name}", parent.qualified_name),
                kind: (*kind).into(),
                path: module.path.clone(),
                start: range.start().into(),
                end: range.end().into(),
                body_start: range.start().into(),
                body_end: range.end().into(),
            });
        }
        output.declarations.extend(declarations);
        for stmt in &facts.statements {
            if let Stmt::ClassDef(class) = stmt
                && let Some(arguments) = &class.arguments
            {
                for base in &arguments.args {
                    let mut targets = vec![];
                    let mut unknown = vec![];
                    if let Some(ty) = transaction.get_type_trace(handle, base.range()) {
                        call_targets(&ty, &mut targets, &mut unknown);
                    }
                    output.references.push(Reference {
                        relation: "INHERITS".into(),
                        path: module.path.clone(),
                        start: class.name.start().into(),
                        end: class.name.end().into(),
                        targets,
                    });
                }
            }
        }
        for expr in facts.expressions {
            if let Expr::Call(call) = expr {
                let mut targets = Vec::new();
                let mut unknown_reasons = Vec::new();
                if let Some(ty) = transaction.get_type_trace(handle, call.func.range()) {
                    call_targets(&ty, &mut targets, &mut unknown_reasons);
                } else {
                    unknown_reasons.push("type_trace_missing".into());
                }
                targets.sort();
                targets.dedup();
                unknown_reasons.sort();
                unknown_reasons.dedup();
                output.calls.push(Call {
                    path: module.path.clone(),
                    start: expr.start().into(),
                    end: expr.end().into(),
                    syntax: module.source[std::ops::Range::<usize>::from(call.func.range())].into(),
                    targets,
                    unknown_reasons,
                });
            }
            if matches!(expr, Expr::Name(n) if n.ctx == ruff_python_ast::ExprContext::Load)
                || matches!(expr, Expr::Attribute(a) if a.ctx == ruff_python_ast::ExprContext::Load)
            {
                let range = expr.range();
                let mut preference = FindPreference::default();
                preference.resolve_call_dunders = false;
                preference.disable_style_fallback = true;
                preference.include_interpreter_stdlib = false;
                let targets = transaction
                    .find_definition(
                        handle,
                        range.end() - ruff_text_size::TextSize::new(1),
                        preference,
                    )
                    .map(|items| {
                        items
                            .into_iter()
                            .map(|d| Target {
                                name: d
                                    .display_name
                                    .unwrap_or_else(|| d.module.code_at(d.definition_range).into()),
                                path: d.module.path().as_path().to_string_lossy().into_owned(),
                                start: d.definition_range.start().into(),
                                end: d.definition_range.end().into(),
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                output.references.push(Reference {
                    relation: "REFERENCES".into(),
                    path: module.path.clone(),
                    start: range.start().into(),
                    end: range.end().into(),
                    targets,
                });
            }
        }
    }
    eprintln!(
        "codeana-python-timing backend_facts_ms={}",
        extracting.elapsed().as_millis()
    );
    Ok(output)
}
