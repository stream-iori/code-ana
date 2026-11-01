//! Frozen syntax evidence. HIR failures never suppress lexically available comments.
use anyhow::{Result, ensure};
use codeana_model::{
    AnalysisContext, CommentBasis, CommentBodySegment, CommentFact, CommentKind, CommentLink,
    CommentRange, CommentRelation, SourcePositions,
    rust::{CrateInput, FrozenFile, PRODUCER},
};
use ra_ap_syntax::{
    AstNode, AstToken, SyntaxNode, SyntaxToken, TextRange,
    ast::{self, IsString},
};
use std::collections::{BTreeSet, HashMap};

pub const RULE: &str = "rust-comments-v1";
#[derive(Debug)]
pub struct Observation {
    pub fact: CommentFact,
    pub links: Vec<CommentLink>,
}
pub(crate) struct Collector<'a> {
    pub text: &'a str,
    pub source: &'a FrozenFile,
    pub input: &'a CrateInput,
    pub ids: &'a HashMap<SyntaxNode, String>,
    pub published: &'a BTreeSet<String>,
    pub budget: &'a AnalysisContext,
    pub check: &'a dyn Fn() -> Result<()>,
}
impl Collector<'_> {
    pub fn collect(&self, tree: &SyntaxNode) -> Result<(Vec<Observation>, BTreeSet<String>)> {
        let admit = |w, b| {
            self.budget
                .charge(w, 0, b)
                .map_err(codeana_model::DomainError::analysis)
        };
        let positions = SourcePositions::new(self.text, &admit)?;
        let mut result = Vec::new();
        let mut gaps = BTreeSet::new();
        for element in tree.descendants_with_tokens() {
            (self.check)()?;
            self.budget.charge(1, 0, 0)?;
            if let Some(token) = element
                .as_token()
                .and_then(|t| ast::Comment::cast(t.clone()))
            {
                let raw = token.text();
                self.budget.charge(
                    raw.len() as u64,
                    1,
                    (raw.len() as u64).saturating_mul(192).saturating_add(2048),
                )?;
                if token.kind().shape.is_block() && !raw.ends_with("*/") {
                    gaps.insert("RUST_COMMENT_UNTERMINATED".into());
                }
                let start = usize::from(token.syntax().text_range().start());
                let prefix = token.prefix().len();
                let end = if token.kind().shape.is_block() && raw.ends_with("*/") {
                    raw.len() - 2
                } else {
                    raw.len()
                };
                let end = end.max(prefix).min(raw.len());
                let (body, map) = plain_body(
                    self.text,
                    start + prefix,
                    start + end,
                    token.kind().shape.is_block(),
                );
                let kind = if token.is_doc() {
                    CommentKind::Doc
                } else if token.kind().shape.is_line() {
                    CommentKind::Line
                } else {
                    CommentKind::Block
                };
                let owner = if token.is_doc() {
                    token
                        .syntax()
                        .parent()
                        .and_then(|p| doc_owner(p, token.is_inner()))
                } else {
                    None
                };
                let mut observation =
                    self.observation(&positions, token.syntax().text_range(), kind, body, map)?;
                if let Some(owner) = owner {
                    self.link(
                        &positions,
                        &mut observation,
                        CommentRelation::Documents,
                        &owner,
                        CommentBasis::LanguageRule,
                    )?;
                }
                self.enclosing(&positions, &mut observation, token.syntax().parent())?;
                self.statements(&positions, &mut observation, token.syntax())?;
                result.push(observation);
            } else if let Some(attr) = element.as_node().and_then(|n| ast::Attr::cast(n.clone())) {
                if attr.simple_name().as_deref() != Some("doc") {
                    continue;
                }
                // doc(hidden), doc(alias=...) and doc(cfg(...)) are metadata, not text.
                let Some(ast::Meta::KeyValueMeta(meta)) = attr.meta() else {
                    continue;
                };
                self.budget.charge(
                    u32::from(attr.syntax().text_range().len()) as u64,
                    1,
                    (u32::from(attr.syntax().text_range().len()) as u64)
                        .saturating_mul(192)
                        .saturating_add(2048),
                )?;
                let literal = meta.expr().and_then(|e| {
                    if let ast::Expr::Literal(l) = e {
                        Some(l)
                    } else {
                        None
                    }
                });
                let string = literal.and_then(|l| {
                    if let ast::LiteralKind::String(s) = l.kind() {
                        Some(s)
                    } else {
                        None
                    }
                });
                let Some(string) = string else {
                    gaps.insert("RUST_DOC_ATTRIBUTE_NON_LITERAL".into());
                    continue;
                };
                let mut body = String::new();
                let mut map = Vec::new();
                let mut invalid = false;
                let origin = usize::from(string.syntax().text_range().start());
                string.escaped_char_ranges(&mut |range, value| match value {
                    Ok(ch) => {
                        let bs = body.len();
                        body.push(ch);
                        map.push(CommentBodySegment {
                            body_start: bs as u64,
                            body_end: body.len() as u64,
                            source_start: (origin + usize::from(range.start())) as u64,
                            source_end: (origin + usize::from(range.end())) as u64,
                        });
                    }
                    Err(e) => {
                        if e.is_fatal() {
                            invalid = true;
                        }
                    }
                });
                // RA's convenience value() also returns nonfatal continuation
                // warnings. The compiler escape callback above remains authoritative.
                let consistent = match string.value() {
                    Ok(value) => value == body,
                    Err(error) => !error.is_fatal(),
                };
                if invalid || !consistent {
                    gaps.insert("RUST_DOC_ATTRIBUTE_INVALID_ESCAPE".into());
                    continue;
                }
                let mut observation = self.observation(
                    &positions,
                    attr.syntax().text_range(),
                    CommentKind::DocAttribute,
                    body,
                    map,
                )?;
                if let Some(owner) = attr
                    .syntax()
                    .parent()
                    .and_then(|p| doc_owner(p, attr.kind().is_inner()))
                {
                    self.link(
                        &positions,
                        &mut observation,
                        CommentRelation::Documents,
                        &owner,
                        CommentBasis::LanguageRule,
                    )?;
                }
                self.enclosing(&positions, &mut observation, attr.syntax().parent())?;
                result.push(observation);
            }
        }
        Ok((result, gaps))
    }
    fn range(&self, positions: &SourcePositions, range: TextRange) -> Result<CommentRange> {
        let (start, end) = (usize::from(range.start()), usize::from(range.end()));
        let admit = |w, b| {
            self.budget
                .charge(w, 0, b)
                .map_err(codeana_model::DomainError::analysis)
        };
        let (begin_line, begin_column) = positions.position(self.text, start, &admit)?;
        let (end_line, end_column) = positions.position(self.text, end, &admit)?;
        Ok(CommentRange {
            byte_start: start as u64,
            byte_end: end as u64,
            begin_line,
            begin_column,
            end_line,
            end_column,
        })
    }
    fn observation(
        &self,
        positions: &SourcePositions,
        range: TextRange,
        kind: CommentKind,
        body: String,
        body_map: Vec<CommentBodySegment>,
    ) -> Result<Observation> {
        self.budget.charge(
            1,
            1,
            ((self.source.path.len()
                + self.source.hash.len()
                + self.input.module.len()
                + self.input.scope.len()
                + self.input.world.len()) as u64)
                .saturating_mul(8)
                .saturating_add(2048),
        )?;
        let mut fact = CommentFact {
            id: Default::default(),
            source_file: self.source.path.clone(),
            source_hash: self.source.hash.clone(),
            module: self.input.module.clone(),
            scope: self.input.scope.clone(),
            world: self.input.world.clone().into(),
            language: "rust".into(),
            provider: PRODUCER.into(),
            producer: PRODUCER.into(),
            kind,
            range: self.range(positions, range)?,
            body,
            body_map,
            rule_version: RULE.into(),
        };
        fact.id = fact.expected_id();
        Ok(Observation {
            fact,
            links: Vec::new(),
        })
    }
    fn link(
        &self,
        positions: &SourcePositions,
        observation: &mut Observation,
        relation: CommentRelation,
        node: &SyntaxNode,
        basis: CommentBasis,
    ) -> Result<()> {
        self.budget.charge(1, 1, 1024)?;
        let declaration = self
            .ids
            .get(node)
            .filter(|id| self.published.contains(*id))
            .cloned()
            .map(Into::into);
        observation.links.push(CommentLink {
            comment: observation.fact.id.clone(),
            relation,
            declaration,
            range: self.range(positions, node.text_range())?,
            basis,
            rule_version: RULE.into(),
        });
        Ok(())
    }
    fn enclosing(
        &self,
        positions: &SourcePositions,
        observation: &mut Observation,
        parent: Option<SyntaxNode>,
    ) -> Result<()> {
        let Some(parent) = parent else {
            return Ok(());
        };
        for node in parent.ancestors() {
            (self.check)()?;
            self.budget.charge(1, 0, 0)?;
            let inside = |range: TextRange| {
                u64::from(u32::from(range.start())) <= observation.fact.range.byte_start
                    && observation.fact.range.byte_end <= u64::from(u32::from(range.end()))
            };
            if let Some(f) = ast::Fn::cast(node.clone()) {
                if f.body().is_some_and(|b| inside(b.syntax().text_range())) {
                    self.link(
                        positions,
                        observation,
                        CommentRelation::EnclosingCallable,
                        &node,
                        CommentBasis::LanguageRule,
                    )?;
                }
                break;
            }
            if let Some(c) = ast::ClosureExpr::cast(node.clone()) {
                if c.body().is_some_and(|b| inside(b.syntax().text_range())) {
                    self.link(
                        positions,
                        observation,
                        CommentRelation::EnclosingCallable,
                        &node,
                        CommentBasis::LanguageRule,
                    )?;
                }
                break;
            }
            if let Some(b) = ast::BlockExpr::cast(node.clone())
                && (b.async_token().is_some()
                    || b.const_token().is_some()
                    || b.gen_token().is_some())
            {
                self.link(
                    positions,
                    observation,
                    CommentRelation::EnclosingCallable,
                    &node,
                    CommentBasis::LanguageRule,
                )?;
                break;
            }
            if ast::Item::can_cast(node.kind()) {
                break;
            }
        }
        Ok(())
    }
    fn statements(
        &self,
        positions: &SourcePositions,
        observation: &mut Observation,
        token: &SyntaxToken,
    ) -> Result<()> {
        let Some(parent) = token.parent() else {
            return Ok(());
        };
        if !ast::StmtList::can_cast(parent.kind()) {
            return Ok(());
        }
        for (direction, relation) in [
            (
                ra_ap_syntax::Direction::Prev,
                CommentRelation::TrailingStatement,
            ),
            (
                ra_ap_syntax::Direction::Next,
                CommentRelation::LeadingStatement,
            ),
        ] {
            let mut next = if direction == ra_ap_syntax::Direction::Prev {
                token.prev_sibling_or_token()
            } else {
                token.next_sibling_or_token()
            };
            while let Some(element) = next {
                self.budget.charge(1, 0, 0)?;
                (self.check)()?;
                if let Some(node) = element.as_node() {
                    if ast::LetStmt::can_cast(node.kind())
                        || ast::ExprStmt::can_cast(node.kind())
                        || ast::Expr::can_cast(node.kind())
                    {
                        let range = node.text_range();
                        let (a, b) = if direction == ra_ap_syntax::Direction::Prev {
                            (
                                usize::from(range.end()),
                                observation.fact.range.byte_start as usize,
                            )
                        } else {
                            (
                                observation.fact.range.byte_end as usize,
                                usize::from(range.start()),
                            )
                        };
                        ensure!(a <= b, "RUST_COMMENT_STATEMENT_RANGE");
                        self.budget.charge((b - a) as u64, 0, 0)?;
                        let gap = &self.text[a..b];
                        let newline_count = gap.bytes().filter(|c| *c == b'\n').count()
                            + gap
                                .as_bytes()
                                .iter()
                                .enumerate()
                                .filter(|(i, c)| {
                                    **c == b'\r' && gap.as_bytes().get(i + 1) != Some(&b'\n')
                                })
                                .count();
                        if (direction == ra_ap_syntax::Direction::Prev && newline_count == 0)
                            || (direction == ra_ap_syntax::Direction::Next
                                && !codeana_model::comment_gap_has_blank_line(gap))
                        {
                            self.link(
                                positions,
                                observation,
                                relation,
                                node,
                                CommentBasis::Heuristic,
                            )?;
                        }
                    }
                    break;
                }
                let trivia = element.as_token().is_some_and(|t| t.kind().is_trivia());
                if !trivia {
                    break;
                }
                next = if direction == ra_ap_syntax::Direction::Prev {
                    element.prev_sibling_or_token()
                } else {
                    element.next_sibling_or_token()
                };
            }
        }
        Ok(())
    }
}
fn doc_owner(parent: SyntaxNode, inner: bool) -> Option<SyntaxNode> {
    if !inner {
        return (ast::Item::can_cast(parent.kind())
            || ast::RecordField::can_cast(parent.kind())
            || ast::Variant::can_cast(parent.kind()))
        .then_some(parent);
    }
    for node in parent.ancestors() {
        if ast::SourceFile::can_cast(node.kind())
            || ast::Fn::can_cast(node.kind())
            || ast::Module::can_cast(node.kind())
            || ast::Impl::can_cast(node.kind())
            || ast::Trait::can_cast(node.kind())
        {
            return Some(node);
        }
        if ast::BlockExpr::can_cast(node.kind())
            && !node.parent().is_some_and(|p| ast::Fn::can_cast(p.kind()))
        {
            return Some(node);
        }
    }
    None
}
fn plain_body(
    source: &str,
    start: usize,
    end: usize,
    block: bool,
) -> (String, Vec<CommentBodySegment>) {
    let mut body = String::new();
    let mut map = Vec::new();
    let mut at = start;
    for (line_index, line) in source[start..end].split_inclusive('\n').enumerate() {
        let leading = if block && line_index > 0 {
            let trim = line.trim_start_matches([' ', '\t']);
            if trim.starts_with('*') && !trim.starts_with("*/") {
                line.len() - trim.len() + 1
            } else {
                0
            }
        } else {
            0
        };
        if leading < line.len() {
            let bs = body.len();
            body.push_str(&line[leading..]);
            map.push(CommentBodySegment {
                body_start: bs as u64,
                body_end: body.len() as u64,
                source_start: (at + leading) as u64,
                source_end: (at + line.len()) as u64,
            });
        }
        at += line.len();
    }
    (body, map)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn extract(source: &str) -> (Vec<Observation>, BTreeSet<String>) {
        let tree = ast::SourceFile::parse(source, ra_ap_syntax::Edition::Edition2024).tree();
        let ids: HashMap<_, _> = tree
            .syntax()
            .descendants()
            .filter(|n| super::super::extract::item(n).is_some())
            .enumerate()
            .map(|(n, node)| (node, format!("decl-{n}")))
            .collect();
        let published = ids.values().cloned().collect();
        let file = FrozenFile {
            path: "lib.rs".into(),
            hash: codeana_model::hash(source),
            input: Default::default(),
            crates: vec!["fixture".into()],
        };
        let input = CrateInput {
            id: "fixture".into(),
            name: "fixture".into(),
            module: "fixture".into(),
            world: "fixture-world".into(),
            scope: "MAIN".into(),
            root: "lib.rs".into(),
            directory: "".into(),
            edition: "2024".into(),
            target: "fixture".into(),
            cfg: vec![],
            dependencies: Default::default(),
            environment: Default::default(),
            publish: true,
            proc_macro: false,
            gaps: vec![],
        };
        let budget = AnalysisContext::new(Default::default(), None).unwrap();
        Collector {
            text: source,
            source: &file,
            input: &input,
            ids: &ids,
            published: &published,
            budget: &budget,
            check: &|| Ok(()),
        }
        .collect(tree.syntax())
        .unwrap()
    }
    fn find<'a>(all: &'a [Observation], text: &str) -> &'a Observation {
        all.iter()
            .find(|o| o.fact.body.contains(text))
            .unwrap_or_else(|| panic!("missing {text}: {all:#?}"))
    }
    #[test]
    fn lexer_preserves_nested_comments_and_ignores_strings() {
        let source = "// 文件😀\r\nconst S:&str=r#\"// 假 /* 注释 */\"#;\r\n/* 外层 /* 嵌套 */ 结束 */\nfn run(){ // 尾部\n}\n// EOF";
        let (all, gaps) = extract(source);
        assert!(gaps.is_empty());
        assert_eq!(all.len(), 4);
        assert_eq!(find(&all, "嵌套").fact.kind, CommentKind::Block);
        assert_eq!(find(&all, "文件").fact.range.end_column, 9);
        for o in all {
            let raw = &source[o.fact.range.byte_start as usize..o.fact.range.byte_end as usize];
            assert!(raw.starts_with("//") || raw.starts_with("/*"));
            for segment in o.fact.body_map {
                assert_eq!(
                    &o.fact.body[segment.body_start as usize..segment.body_end as usize],
                    &source[segment.source_start as usize..segment.source_end as usize]
                );
            }
        }
    }
    #[test]
    fn documentation_uses_ast_owners_and_literal_escape_ranges() {
        let source = "//! 模块文档\n/// 方法文档\n#[doc = \"转义\\u{5e93}\\u{5b58}\"]\n#[doc = r#\"原始库存\"#]\nfn run(){}\n#[doc=include_str!(\"must-not-read\")] fn hidden(){}\n/** 字段 */ struct S{/// 属性\nx:i32}\n";
        let (all, gaps) = extract(source);
        assert_eq!(
            gaps,
            BTreeSet::from(["RUST_DOC_ATTRIBUTE_NON_LITERAL".into()])
        );
        let module = find(&all, "模块");
        assert!(
            module
                .links
                .iter()
                .any(|l| l.relation == CommentRelation::Documents && l.declaration.is_none())
        );
        let method = find(&all, "方法");
        let escaped = find(&all, "转义库存");
        let raw = find(&all, "原始库存");
        let document = |o: &Observation| {
            o.links
                .iter()
                .find(|l| l.relation == CommentRelation::Documents)
                .unwrap()
                .declaration
                .clone()
        };
        assert!(document(method).is_some());
        assert_eq!(document(method), document(escaped));
        assert_eq!(document(method), document(raw));
        let segment = escaped
            .fact
            .body_map
            .iter()
            .find(|m| &escaped.fact.body[m.body_start as usize..m.body_end as usize] == "库")
            .unwrap();
        assert_eq!(
            &source[segment.source_start as usize..segment.source_end as usize],
            "\\u{5e93}"
        );
        assert!(document(find(&all, "属性")).is_some());
    }
    #[test]
    fn valid_doc_continuations_keep_compiler_warning_only_decodes() {
        let source = "#[doc = \"库存\\\n \n    检查\"] fn run() {}\n";
        let (all, gaps) = extract(source);
        assert!(gaps.is_empty(), "{gaps:?}");
        let doc = find(&all, "库存检查");
        assert_eq!(doc.fact.kind, CommentKind::DocAttribute);
        assert!(
            doc.links
                .iter()
                .any(|l| l.relation == CommentRelation::Documents && l.declaration.is_some())
        );
        for segment in &doc.fact.body_map {
            assert_eq!(
                &doc.fact.body[segment.body_start as usize..segment.body_end as usize],
                &source[segment.source_start as usize..segment.source_end as usize]
            );
        }
    }
    #[test]
    fn nearest_callable_and_anonymous_barriers_do_not_cross() {
        let source = "fn outer(){\n// 外方法\nlet x=1;\nfn inner(){ // 内方法\n}\nlet f=|| { // 闭包\n0 };\nlet f=async { // 异步\n0 };\nconst { // 常量块\n0 };\nstruct S{ // 类内\nx:i32}\n}\n";
        let (all, _) = extract(source);
        let enclosing = |name| {
            find(&all, name)
                .links
                .iter()
                .find(|l| l.relation == CommentRelation::EnclosingCallable)
                .cloned()
        };
        assert!(enclosing("外方法").unwrap().declaration.is_some());
        assert!(enclosing("内方法").unwrap().declaration.is_some());
        assert_ne!(
            enclosing("外方法").unwrap().declaration,
            enclosing("内方法").unwrap().declaration
        );
        for name in ["闭包", "异步", "常量块"] {
            assert!(
                enclosing(name).unwrap().declaration.is_none(),
                "{name}: {all:#?}"
            );
        }
        assert!(enclosing("类内").is_none());
    }
    #[test]
    fn statement_links_obey_same_block_and_blank_lines() {
        let source = "fn run(){\n// 前置\nlet a=1; // 行尾\n\n// 空行\n\nlet b=2;\n{ // 子块\nlet c=3;\n}\n}";
        let (all, _) = extract(source);
        let links = |name, relation| {
            find(&all, name)
                .links
                .iter()
                .filter(|l| l.relation == relation)
                .count()
        };
        assert_eq!(links("前置", CommentRelation::LeadingStatement), 1);
        assert_eq!(links("行尾", CommentRelation::TrailingStatement), 1);
        assert_eq!(links("空行", CommentRelation::LeadingStatement), 0);
        assert_eq!(links("子块", CommentRelation::TrailingStatement), 0);
        assert!(
            all.iter()
                .flat_map(|o| &o.links)
                .filter(|l| matches!(
                    l.relation,
                    CommentRelation::LeadingStatement | CommentRelation::TrailingStatement
                ))
                .all(|l| l.basis == CommentBasis::Heuristic && l.declaration.is_none())
        );
    }
}
