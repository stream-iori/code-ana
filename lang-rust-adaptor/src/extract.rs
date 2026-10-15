//! Source-level extraction. HIR handles binding; syntax supplies exact occurrence ranges.
use crate::facade::AnalyzerFacade;
use anyhow::{Result, ensure};
use codeana_model::{
    key,
    rust::{AnalysisConfig, DeclarationKind, RustRelation, RustResolution, RustVisibility},
};
use ra_ap_hir::{Adt, AsAssocItem, ModuleDef, PathResolution, Semantics};
use ra_ap_syntax::{
    AstNode, SyntaxNode, TextRange,
    ast::{self, HasName, HasVisibility},
};
use ra_ap_vfs::FileId;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone)]
pub struct Declaration {
    pub id: String,
    pub name: String,
    pub qualified_name: String,
    pub kind: DeclarationKind,
    pub visibility: RustVisibility,
    pub source: String,
    pub world: String,
    pub module: String,
    pub scope: String,
    pub start: usize,
    pub end: usize,
    pub parent: Option<String>,
    pub synthetic: bool,
    pub resolved: bool,
    pub signature_hash: String,
}

#[derive(Debug, Clone)]
pub struct Relation {
    pub source: String,
    pub target: Option<String>,
    pub external: Option<String>,
    pub kind: RustRelation,
    pub file: String,
    pub world: String,
    pub start: usize,
    pub end: usize,
    pub syntax: String,
    pub resolution: RustResolution,
}

#[derive(Debug, Default)]
pub struct Analysis {
    pub dirty: Option<BTreeSet<(String, String)>>,
    pub imports: BTreeMap<(String, String), Vec<String>>,
    pub declarations: Vec<Declaration>,
    pub relations: Vec<Relation>,
    pub gaps: BTreeMap<(String, String), BTreeSet<String>>,
    pub texts: BTreeMap<String, String>,
    /// Lookup keys include unresolved names, so later declarations invalidate their users.
    pub lookups: BTreeMap<(String, String), BTreeSet<String>>,
}

type Anchor = (FileId, u32, u32);
fn anchor(file: FileId, range: TextRange) -> Anchor {
    (file, range.start().into(), range.end().into())
}

fn item(node: &SyntaxNode) -> Option<(String, DeclarationKind, RustVisibility)> {
    macro_rules! named {
        ($ty:ty, $kind:ident) => {
            if let Some(value) = <$ty>::cast(node.clone()) {
                let name = value.name()?.text().to_string();
                let visibility = value
                    .visibility()
                    .map(|v| v.syntax().text().to_string())
                    .unwrap_or_default();
                return Some((
                    name,
                    DeclarationKind::$kind,
                    match visibility.as_str() {
                        "pub" => RustVisibility::Public,
                        "pub(crate)" => RustVisibility::Crate,
                        "" => RustVisibility::Private,
                        _ => RustVisibility::Restricted,
                    },
                ));
            }
        };
    }
    named!(ast::Fn, Function);
    named!(ast::Struct, Struct);
    named!(ast::Enum, Enum);
    named!(ast::Trait, Trait);
    named!(ast::Union, Union);
    named!(ast::TypeAlias, TypeAlias);
    named!(ast::Const, Constant);
    named!(ast::Static, Static);
    named!(ast::Module, Module);
    named!(ast::RecordField, Field);
    if let Some(value) = ast::Variant::cast(node.clone()) {
        return Some((
            value.name()?.text().to_string(),
            DeclarationKind::Variant,
            RustVisibility::Public,
        ));
    }
    if let Some(value) = ast::MacroRules::cast(node.clone()) {
        return Some((
            value.name()?.text().to_string(),
            DeclarationKind::Macro,
            RustVisibility::Private,
        ));
    }
    if let Some(value) = ast::Impl::cast(node.clone()) {
        let header = value
            .syntax()
            .text()
            .to_string()
            .split('{')
            .next()?
            .split_whitespace()
            .collect::<Vec<_>>()
            .join(" ");
        return Some((header, DeclarationKind::Impl, RustVisibility::Private));
    }
    None
}

fn definition(
    sema: &Semantics<'_, ra_ap_ide_db::RootDatabase>,
    node: &SyntaxNode,
) -> Option<ModuleDef> {
    macro_rules! resolve {
        ($ty:ty) => {
            if let Some(value) = <$ty>::cast(node.clone()) {
                return sema.to_def(&value).map(Into::into);
            }
        };
    }
    resolve!(ast::Fn);
    resolve!(ast::Struct);
    resolve!(ast::Enum);
    resolve!(ast::Trait);
    resolve!(ast::Union);
    resolve!(ast::TypeAlias);
    resolve!(ast::Const);
    resolve!(ast::Static);
    resolve!(ast::Module);
    resolve!(ast::Variant);
    resolve!(ast::Macro);
    None
}

fn source_node(
    sema: &Semantics<'_, ra_ap_ide_db::RootDatabase>,
    def: ModuleDef,
) -> Option<SyntaxNode> {
    macro_rules! source {
        ($v:expr) => {
            sema.source($v).map(|v| v.value.syntax().clone())
        };
    }
    match def {
        ModuleDef::Function(v) => source!(v),
        ModuleDef::Adt(Adt::Struct(v)) => source!(v),
        ModuleDef::Adt(Adt::Enum(v)) => source!(v),
        ModuleDef::Adt(Adt::Union(v)) => source!(v),
        ModuleDef::EnumVariant(v) => source!(v),
        ModuleDef::Const(v) => source!(v),
        ModuleDef::Static(v) => source!(v),
        ModuleDef::Trait(v) => source!(v),
        ModuleDef::TypeAlias(v) => source!(v),
        _ => None,
    }
}

// Container headers and member signatures are hashed separately. Executable bodies
// do not become an interface merely because they are nested inside an impl/module.
fn interface_text(node: &SyntaxNode) -> String {
    let boundary = if let Some(f) = ast::Fn::cast(node.clone()) {
        f.body().map(|body| body.syntax().text_range().start())
    } else if let Some(i) = ast::Impl::cast(node.clone()) {
        i.assoc_item_list()
            .map(|body| body.syntax().text_range().start())
    } else if let Some(m) = ast::Module::cast(node.clone()) {
        m.item_list().map(|body| body.syntax().text_range().start())
    } else if let Some(t) = ast::Trait::cast(node.clone()) {
        t.assoc_item_list()
            .map(|body| body.syntax().text_range().start())
    } else {
        None
    };
    let text = node.text().to_string();
    let end = boundary
        .map(|end| u32::from(end - node.text_range().start()) as usize)
        .unwrap_or(text.len());
    text[..end].to_owned()
}

pub fn analyze(config: &AnalysisConfig, cancelled: &dyn Fn() -> Result<()>) -> Result<Analysis> {
    cancelled()?;
    ensure!(
        matches!(config.mode.as_str(), "syntax" | "semantic"),
        "RUST_ANALYSIS_MODE"
    );
    eprintln!("codeana-rust-phase=database_load");
    let started = std::time::Instant::now();
    let analyzer = AnalyzerFacade::new(config)?;
    eprintln!(
        "codeana-rust-timing database_load_ms={}",
        started.elapsed().as_millis()
    );
    ra_ap_hir::attach_db(&analyzer.db, || {
        analyze_attached(config, cancelled, &analyzer)
    })
}

type SourceTree = (FileId, usize, usize, SyntaxNode, bool);
#[derive(Default)]
struct ExtractionState {
    output: Analysis,
    trees: Vec<SourceTree>,
    anchors: BTreeMap<Anchor, String>,
    syntax_ids: std::collections::HashMap<SyntaxNode, String>,
    published: BTreeSet<String>,
    source_cache: std::collections::HashMap<ModuleDef, Option<SyntaxNode>>,
    path_cache: std::collections::HashMap<(ModuleDef, String), Option<String>>,
}
struct Extraction<'a> {
    config: &'a AnalysisConfig,
    cancelled: &'a dyn Fn() -> Result<()>,
    analyzer: &'a AnalyzerFacade,
    sema: Semantics<'a, ra_ap_ide_db::RootDatabase>,
    state: ExtractionState,
}
fn analyze_attached(
    config: &AnalysisConfig,
    cancelled: &dyn Fn() -> Result<()>,
    analyzer: &AnalyzerFacade,
) -> Result<Analysis> {
    let mut extraction = Extraction {
        config,
        cancelled,
        analyzer,
        sema: Semantics::new(&analyzer.db),
        state: ExtractionState::default(),
    };
    eprintln!("codeana-rust-phase=declarations");
    let declarations_started = std::time::Instant::now();
    extraction.collect_sources()?;
    extraction.collect_declarations()?;
    eprintln!(
        "codeana-rust-timing declarations_ms={}",
        declarations_started.elapsed().as_millis()
    );
    if let Some(before) = &config.baseline {
        let current = crate::incremental::states(config, &extraction.state.output);
        extraction.state.output.dirty = Some(crate::incremental::dirty(before, &current));
    }
    if config.mode == codeana_model::rust::RustAnalysisMode::Syntax {
        return Ok(extraction.state.output);
    }
    eprintln!("codeana-rust-phase=bindings");
    let bindings_started = std::time::Instant::now();
    extraction.collect_implementations()?;
    extraction.collect_bindings()?;
    eprintln!(
        "codeana-rust-timing bindings_ms={}",
        bindings_started.elapsed().as_millis()
    );
    Ok(extraction.state.output)
}
impl Extraction<'_> {
    fn collect_sources(&mut self) -> Result<()> {
        let Self {
            config,
            cancelled,
            analyzer,
            sema,
            ..
        } = self;
        let ExtractionState { output, trees, .. } = &mut self.state;
        for (file, (crate_index, file_index)) in &analyzer.files {
            cancelled()?;
            let source = &config.files[*file_index];
            let input = &config.inputs.crates[*crate_index];
            // Dependency files remain available to HIR, but do not need a second
            // complete AST walk merely to create facts we will never publish.
            if !input.publish
                || config
                    .excluded
                    .contains(&(source.path.clone(), input.world.clone()))
            {
                continue;
            }
            let tree = sema.parse_guess_edition(*file);
            if tree
                .syntax()
                .descendants()
                .any(|n| ast::MacroCall::can_cast(n.kind()))
            {
                output
                    .gaps
                    .entry((source.path.clone(), input.world.clone()))
                    .or_default()
                    .insert("RUST_MACRO_EXPANSION_DISABLED".into());
            }
            output
                .texts
                .entry(source.path.clone())
                .or_insert_with(|| tree.syntax().text().to_string());
            if config.mode == "semantic" && sema.file_to_module_def(*file).is_none() {
                continue;
            }
            if input.publish
                && !config
                    .excluded
                    .contains(&(source.path.clone(), input.world.clone()))
            {
                output
                    .gaps
                    .entry((source.path.clone(), input.world.clone()))
                    .or_default()
                    .extend(input.gaps.iter().cloned());
                if !tree
                    .syntax()
                    .descendants()
                    .filter_map(ast::Attr::cast)
                    .filter_map(|a| a.path())
                    .all(|p| {
                        matches!(
                            p.syntax().text().to_string().as_str(),
                            "cfg"
                                | "test"
                                | "allow"
                                | "deny"
                                | "warn"
                                | "forbid"
                                | "expect"
                                | "doc"
                                | "inline"
                                | "cold"
                                | "no_mangle"
                                | "export_name"
                                | "repr"
                                | "non_exhaustive"
                                | "must_use"
                                | "path"
                                | "macro_export"
                                | "macro_use"
                                | "track_caller"
                                | "deprecated"
                        )
                    })
                {
                    output
                        .gaps
                        .entry((source.path.clone(), input.world.clone()))
                        .or_default()
                        .insert("RUST_PROC_MACRO_UNEXPANDED".into());
                }
                if tree
                    .syntax()
                    .descendants()
                    .any(|n| n.kind() == ra_ap_syntax::SyntaxKind::ERROR)
                {
                    output
                        .gaps
                        .entry((source.path.clone(), input.world.clone()))
                        .or_default()
                        .insert("RUST_SYNTAX_ERROR".into());
                }
                if config.mode == "syntax" {
                    output
                        .gaps
                        .entry((source.path.clone(), input.world.clone()))
                        .or_default()
                        .insert("RUST_SYNTAX_ONLY".into());
                }
            }
            trees.push((
                *file,
                *crate_index,
                *file_index,
                tree.syntax().clone(),
                false,
            ));
        }
        Ok(())
    }
    fn collect_declarations(&mut self) -> Result<()> {
        let Self {
            config,
            cancelled,
            analyzer,
            sema,
            ..
        } = self;
        let ExtractionState {
            output,
            trees,
            anchors,
            syntax_ids,
            published,
            path_cache,
            ..
        } = &mut self.state;
        for (file, ci, fi, tree, synthetic) in trees.iter() {
            let input = &config.inputs.crates[*ci];
            let source = &config.files[*fi];
            let imports = tree
                .descendants()
                .filter(|node| {
                    (ast::Use::can_cast(node.kind()) || ast::ExternCrate::can_cast(node.kind()))
                        && !node.ancestors().any(|n| ast::Fn::can_cast(n.kind()))
                })
                .map(|node| node.text().to_string());
            output
                .imports
                .entry((source.path.clone(), input.world.clone()))
                .or_default()
                .extend(imports);
            for node in tree.descendants() {
                let Some((name, kind, visibility)) = item(&node) else {
                    continue;
                };
                cancelled()?;
                let def = definition(sema, &node);
                // HIR omits cfg-disabled declarations. Never publish those as active facts.
                if config.mode == "semantic"
                    && def.is_none()
                    && !matches!(kind.as_str(), "impl" | "field")
                {
                    continue;
                }
                if let Some(value) = ast::Impl::cast(node.clone())
                    && sema.to_def(&value).is_none()
                {
                    continue;
                }
                if let Some(value) = ast::RecordField::cast(node.clone())
                    && sema.to_def(&value).is_none()
                {
                    continue;
                }
                let mut parents = node
                    .ancestors()
                    .skip(1)
                    .filter_map(|n| item(&n).map(|i| i.0))
                    .collect::<Vec<_>>();
                parents.reverse();
                parents.push(name.clone());
                let path = parents.join("::");
                let qualified_name = def
                    .and_then(|d| {
                        path_cache
                            .entry((d, input.edition.clone()))
                            .or_insert_with(|| {
                                d.canonical_path(&analyzer.db, input.edition.parse().unwrap())
                            })
                            .clone()
                    })
                    .unwrap_or_else(|| format!("{}::{path}", input.name));
                let id = key("rust", &[&input.world, &source.path, &path, kind.as_str()]);
                let range = if *synthetic {
                    sema.original_range(&node).range
                } else {
                    node.text_range()
                };
                let parent = node
                    .ancestors()
                    .skip(1)
                    .find_map(|n| syntax_ids.get(&n).cloned());
                syntax_ids.insert(node.clone(), id.clone());
                anchors.insert(anchor(*file, range), id.clone());
                let signature = interface_text(&node);
                if input.publish
                    && !config
                        .excluded
                        .contains(&(source.path.clone(), input.world.clone()))
                    && published.insert(id.clone())
                {
                    ensure!(
                        output.declarations.len() < config.fact_limit as usize,
                        "RUST_FACT_BUDGET"
                    );
                    let resolved = def.is_some() || matches!(kind.as_str(), "impl" | "field");
                    output.declarations.push(Declaration {
                        id,
                        name,
                        qualified_name,
                        kind,
                        visibility,
                        source: source.path.clone(),
                        world: input.world.clone(),
                        module: input.module.clone(),
                        scope: input.scope.clone(),
                        start: u32::from(range.start()) as usize,
                        end: u32::from(range.end()) as usize,
                        parent,
                        synthetic: *synthetic,
                        resolved,
                        signature_hash: codeana_model::hash(&signature),
                    });
                }
            }
        }
        // Out-of-line module contents have no AST ancestor in the declaring file.
        // Join their HIR-qualified parent only after every source has been visited.
        let modules = output
            .declarations
            .iter()
            .filter(|d| d.kind == "module")
            .map(|d| ((d.world.clone(), d.qualified_name.clone()), d.id.clone()))
            .collect::<BTreeMap<_, _>>();
        for declaration in &mut output.declarations {
            if declaration.parent.is_none()
                && let Some((parent, _)) = declaration.qualified_name.rsplit_once("::")
            {
                declaration.parent = modules
                    .get(&(declaration.world.clone(), parent.to_owned()))
                    .cloned();
            }
        }
        Ok(())
    }
    fn collect_implementations(&mut self) -> Result<()> {
        let Self {
            config,
            analyzer,
            sema,
            ..
        } = self;
        let ExtractionState {
            output,
            trees,
            syntax_ids,
            published,
            ..
        } = &mut self.state;
        for (_, ci, fi, tree, _) in trees.iter() {
            let input = &config.inputs.crates[*ci];
            if !input.publish
                || config
                    .excluded
                    .contains(&(config.files[*fi].path.clone(), input.world.clone()))
            {
                continue;
            }
            if output.dirty.as_ref().is_some_and(|dirty| {
                !dirty.contains(&(config.files[*fi].path.clone(), input.world.clone()))
            }) {
                continue;
            }
            for implementation in tree.descendants().filter_map(ast::Impl::cast) {
                let Some(def) = sema.to_def(&implementation) else {
                    continue;
                };
                let Some(trait_) = def.trait_(&analyzer.db) else {
                    continue;
                };
                let Some(self_ty) = def.self_ty(&analyzer.db).as_adt() else {
                    continue;
                };
                let _self_ty = self_ty;
                let source = syntax_ids.get(implementation.syntax()).cloned();
                let target = source_node(sema, ModuleDef::Trait(trait_))
                    .and_then(|n| syntax_ids.get(&n).cloned())
                    .filter(|id| published.contains(id));
                if let Some(source_id) = source.filter(|id| published.contains(id)) {
                    let range = implementation.syntax().text_range();
                    output.relations.push(Relation {
                        source: source_id,
                        target: target.clone(),
                        external: if target.is_none() {
                            ModuleDef::Trait(trait_)
                                .canonical_path(&analyzer.db, input.edition.parse().unwrap())
                        } else {
                            None
                        },
                        kind: RustRelation::Implements,
                        file: config.files[*fi].path.clone(),
                        world: input.world.clone(),
                        start: u32::from(range.start()) as usize,
                        end: u32::from(range.end()) as usize,
                        syntax: String::new(),
                        resolution: RustResolution::Exact,
                    });
                }
            }
        }

        Ok(())
    }
    fn collect_bindings(&mut self) -> Result<()> {
        let Self {
            config,
            cancelled,
            analyzer,
            sema,
            ..
        } = self;
        let ExtractionState {
            output,
            trees,
            anchors,
            syntax_ids,
            published,
            source_cache,
            path_cache,
            ..
        } = &mut self.state;
        for (_file, ci, fi, tree, synthetic) in trees.iter() {
            let input = &config.inputs.crates[*ci];
            if !input.publish
                || config
                    .excluded
                    .contains(&(config.files[*fi].path.clone(), input.world.clone()))
            {
                continue;
            }
            let source = &config.files[*fi];
            if output
                .dirty
                .as_ref()
                .is_some_and(|dirty| !dirty.contains(&(source.path.clone(), input.world.clone())))
            {
                continue;
            }
            for node in tree.descendants() {
                cancelled()?;
                if !(ast::MethodCallExpr::can_cast(node.kind())
                    || ast::CallExpr::can_cast(node.kind())
                    || ast::Path::can_cast(node.kind()))
                {
                    continue;
                }
                let caller = node
                    .ancestors()
                    .skip(1)
                    .find_map(|n| syntax_ids.get(&n).cloned());
                let Some(caller) = caller else { continue };
                let (kind, target, syntax) =
                    if let Some(call) = ast::MethodCallExpr::cast(node.clone()) {
                        let resolved = sema.resolve_method_call(&call);
                        let dynamic = resolved.is_some_and(|f| {
                            matches!(
                                f.as_assoc_item(&analyzer.db)
                                    .map(|a| a.container(&analyzer.db)),
                                Some(ra_ap_hir::AssocItemContainer::Trait(_))
                            )
                        });
                        if dynamic {
                            output
                                .gaps
                                .entry((source.path.clone(), input.world.clone()))
                                .or_default()
                                .insert("RUST_DYNAMIC_DISPATCH".into());
                        }
                        (
                            RustRelation::Calls,
                            if dynamic {
                                None
                            } else {
                                resolved.map(ModuleDef::Function)
                            },
                            call.name_ref()
                                .map(|n| n.text().to_string())
                                .unwrap_or_default(),
                        )
                    } else if let Some(call) = ast::CallExpr::cast(node.clone()) {
                        let Some(expr) = call.expr() else { continue };
                        let def = if let ast::Expr::PathExpr(path) = &expr {
                            path.path()
                                .and_then(|p| sema.resolve_path(&p))
                                .and_then(|p| {
                                    if let PathResolution::Def(
                                        d @ (ModuleDef::Function(_)
                                        | ModuleDef::Adt(Adt::Struct(_))
                                        | ModuleDef::EnumVariant(_)),
                                    ) = p
                                    {
                                        Some(d)
                                    } else {
                                        None
                                    }
                                })
                        } else {
                            None
                        };
                        (RustRelation::Calls, def, expr.syntax().text().to_string())
                    } else if let Some(path) = ast::Path::cast(node.clone()) {
                        if node.parent().is_some_and(|n| ast::Path::can_cast(n.kind())) {
                            continue;
                        }
                        let resolution = sema.resolve_path(&path);
                        let def = match resolution {
                            Some(PathResolution::Def(ModuleDef::BuiltinType(_))) => continue,
                            Some(PathResolution::Def(d)) => Some(d),
                            Some(_) => continue,
                            None => None,
                        };
                        (
                            RustRelation::References,
                            def,
                            path.syntax().text().to_string(),
                        )
                    } else {
                        continue;
                    };
                output
                    .lookups
                    .entry((source.path.clone(), input.world.clone()))
                    .or_default()
                    .insert(syntax.clone());
                let bound = target
                    .and_then(|d| {
                        source_cache
                            .entry(d)
                            .or_insert_with(|| source_node(sema, d))
                            .clone()
                    })
                    .and_then(|n| {
                        syntax_ids.get(&n).cloned().or_else(|| {
                            let original = sema.original_range(&n);
                            anchors
                                .get(&anchor(
                                    original.file_id.file_id(&analyzer.db),
                                    original.range,
                                ))
                                .cloned()
                        })
                    });
                let external = if bound.as_ref().is_none_or(|b| !published.contains(b)) {
                    target
                        .filter(|d| {
                            source_cache
                                .entry(*d)
                                .or_insert_with(|| source_node(sema, *d))
                                .as_ref()
                                .is_none_or(|n| sema.hir_file_for(n).macro_file().is_none())
                        })
                        .and_then(|d| {
                            path_cache
                                .entry((d, input.edition.clone()))
                                .or_insert_with(|| {
                                    d.canonical_path(&analyzer.db, input.edition.parse().unwrap())
                                })
                                .clone()
                        })
                } else {
                    None
                };
                let bound = bound.filter(|b| published.contains(b));
                let range = if *synthetic {
                    sema.original_range(&node).range
                } else {
                    node.text_range()
                };
                let resolution = if bound.is_some() || external.is_some() {
                    RustResolution::Exact
                } else {
                    RustResolution::Unresolved
                };
                if resolution == "unresolved" {
                    output
                        .gaps
                        .entry((source.path.clone(), input.world.clone()))
                        .or_default()
                        .insert("RUST_UNRESOLVED_REFERENCE".into());
                }
                output.relations.push(Relation {
                    source: caller,
                    target: bound,
                    external,
                    kind,
                    file: source.path.clone(),
                    world: input.world.clone(),
                    start: u32::from(range.start()) as usize,
                    end: u32::from(range.end()) as usize,
                    syntax,
                    resolution,
                });
                ensure!(
                    output.declarations.len() + output.relations.len() < config.fact_limit as usize,
                    "RUST_FACT_BUDGET"
                );
            }
        }
        Ok(())
    }
}
