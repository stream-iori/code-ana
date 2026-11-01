//! Compiler-only extraction; output contains detached ranges and exact AST anchors.
use super::{AstFacts, FrozenModule};
use anyhow::Result;
use codeana_model::{AnalysisContext, CommentBodySegment, CommentRelation};
use ruff_python_ast::{Expr, Stmt, token::TokenKind};
use ruff_text_size::Ranged;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Link {
    pub relation: CommentRelation,
    pub declaration: Option<u32>,
    pub start: u32,
    pub end: u32,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Comment {
    pub path: String,
    pub start: u32,
    pub end: u32,
    pub docstring: bool,
    pub body: String,
    pub body_map: Vec<CommentBodySegment>,
    pub links: Vec<Link>,
}
#[derive(Clone, Copy)]
struct Token {
    kind: TokenKind,
    start: u32,
    end: u32,
}
struct Suite<'a> {
    body: &'a [Stmt],
    start: u32,
    end: u32,
    indent: u32,
}
struct Scope {
    start: u32,
    end: u32,
    indent: u32,
    declaration: Option<u32>,
    callable: bool,
    target_start: u32,
}
fn first_doc(body: &[Stmt]) -> Option<&ruff_python_ast::ExprStringLiteral> {
    let Some(Stmt::Expr(stmt)) = body.first() else {
        return None;
    };
    if let Expr::StringLiteral(literal) = stmt.value.as_ref() {
        Some(literal)
    } else {
        None
    }
}
fn link(relation: CommentRelation, declaration: Option<u32>, start: u32, end: u32) -> Link {
    Link {
        relation,
        declaration,
        start,
        end,
    }
}
/// Token and physical-line boundaries are indexed once per captured source.
struct TokenBounds<'a> {
    tokens: &'a [Token],
    close: &'a [Option<u32>],
    colons: Vec<usize>,
    indents: Vec<usize>,
    line_ends: Vec<u32>,
    line_starts: Vec<usize>,
}
impl<'a> TokenBounds<'a> {
    fn new(
        source: &str,
        tokens: &'a [Token],
        close: &'a [Option<u32>],
        budget: &AnalysisContext,
        check: &dyn Fn() -> Result<()>,
    ) -> Result<Self> {
        // Retained boundary vectors are covered by the lexer/source reservations.
        budget.charge(tokens.len() as u64 * 3 + source.len() as u64, 0, 0)?;
        let mut result = Self {
            tokens,
            close,
            colons: vec![],
            indents: vec![],
            line_ends: vec![],
            line_starts: vec![0],
        };
        for (i, token) in tokens.iter().enumerate() {
            if i % 256 == 0 {
                check()?;
            }
            match token.kind {
                TokenKind::Colon => result.colons.push(i),
                TokenKind::Indent => result.indents.push(i),
                TokenKind::Newline | TokenKind::EndOfFile => result.line_ends.push(token.start),
                _ => {}
            }
        }
        for (i, b) in source.bytes().enumerate() {
            if i % 4096 == 0 {
                check()?;
                budget.checkpoint()?;
            }
            if b == b'\n' || b == b'\r' && source.as_bytes().get(i + 1) != Some(&b'\n') {
                result.line_starts.push(i + 1);
            }
        }
        Ok(result)
    }
    fn colon_before(&self, offset: u32, budget: &AnalysisContext) -> Result<Option<usize>> {
        budget.charge(
            self.colons.len().checked_ilog2().unwrap_or(0) as u64 + 1,
            0,
            0,
        )?;
        let at = self
            .colons
            .partition_point(|i| self.tokens[*i].end <= offset);
        Ok(at.checked_sub(1).map(|i| self.colons[i]))
    }
    fn indentation(
        &self,
        source: &str,
        offset: u32,
        budget: &AnalysisContext,
        check: &dyn Fn() -> Result<()>,
    ) -> Result<u32> {
        budget.charge(
            self.line_starts.len().checked_ilog2().unwrap_or(0) as u64 + 1,
            0,
            0,
        )?;
        let at = offset as usize;
        let line = self.line_starts[self.line_starts.partition_point(|start| *start <= at) - 1];
        let mut column = 0u32;
        for (n, ch) in source[line..at].chars().enumerate() {
            if n % 256 == 0 {
                check()?;
            }
            budget.charge(1, 0, 0)?;
            column = match ch {
                ' ' => column
                    .checked_add(1)
                    .ok_or_else(|| anyhow::anyhow!("PYTHON_INDENTATION_RANGE"))?,
                '\t' => column
                    .checked_div(8)
                    .and_then(|n| n.checked_add(1))
                    .and_then(|n| n.checked_mul(8))
                    .ok_or_else(|| anyhow::anyhow!("PYTHON_INDENTATION_RANGE"))?,
                '\x0c' => 0,
                _ => break,
            };
        }
        Ok(column)
    }
}
fn body_key(body: &[Stmt]) -> (usize, usize) {
    (body.as_ptr() as usize, body.len())
}

/// Balanced interval bounds prune sibling scopes, while keeping enclosing ranges
/// available when a trailing comment has already dedented out of an inner suite.
struct RangeIndex {
    entries: Vec<(u32, u32, u32, usize)>,
    max_end: Vec<u32>,
    base: usize,
}
impl RangeIndex {
    fn new(
        ranges: impl ExactSizeIterator<Item = (u32, u32, u32)>,
        budget: &AnalysisContext,
    ) -> Result<Self> {
        let n = ranges.len();
        budget.charge(
            (n as u64).saturating_mul(n.checked_ilog2().unwrap_or(0) as u64 + 5),
            1,
            (n as u64).saturating_mul(96).saturating_add(128),
        )?;
        let mut entries = ranges
            .enumerate()
            .map(|(i, (s, e, d))| (s, e, d, i))
            .collect::<Vec<_>>();
        entries.sort_unstable_by_key(|e| (e.0, std::cmp::Reverse(e.1), e.3));
        let base = n
            .max(1)
            .checked_next_power_of_two()
            .ok_or_else(|| anyhow::anyhow!("PYTHON_COMMENT_RANGE_INDEX"))?;
        let nodes = base
            .checked_mul(2)
            .ok_or_else(|| anyhow::anyhow!("PYTHON_COMMENT_RANGE_INDEX"))?;
        let mut max_end = vec![0; nodes];
        for (i, entry) in entries.iter().enumerate() {
            max_end[base + i] = entry.1;
        }
        for i in (1..base).rev() {
            max_end[i] = max_end[i * 2].max(max_end[i * 2 + 1]);
        }
        Ok(Self {
            entries,
            max_end,
            base,
        })
    }
    fn nearest(
        &self,
        start: u32,
        end: u32,
        indent: u32,
        budget: &AnalysisContext,
        check: &dyn Fn() -> Result<()>,
    ) -> Result<Option<usize>> {
        let depth = self.base.ilog2() as usize + 2;
        budget.charge(depth as u64, 0, (depth as u64).saturating_mul(48))?;
        let upper = self.entries.partition_point(|e| e.0 <= start);
        let mut stack = Vec::with_capacity(depth);
        stack.push((1usize, 0usize, self.base));
        let mut best: Option<(u32, usize)> = None;
        while let Some((node, left, right)) = stack.pop() {
            check()?;
            budget.charge(1, 0, 0)?;
            if left >= upper || self.max_end[node] < end {
                continue;
            }
            if right - left == 1 {
                let entry = &self.entries[left];
                if entry.2 <= indent {
                    let candidate = (entry.1 - entry.0, entry.3);
                    if best.is_none_or(|old| candidate < old) {
                        best = Some(candidate);
                    }
                }
            } else {
                let middle = (left + right) / 2;
                stack.push((node * 2, left, middle));
                stack.push((node * 2 + 1, middle, right));
            }
        }
        Ok(best.map(|entry| entry.1))
    }
}

fn suite<'a>(
    source: &str,
    body: &'a [Stmt],
    bounds: &TokenBounds<'_>,
    budget: &AnalysisContext,
    check: &dyn Fn() -> Result<()>,
) -> Result<Option<Suite<'a>>> {
    let Some(first) = body.first().map(|s| s.start().to_u32()) else {
        return Ok(None);
    };
    let Some(colon) = bounds.colon_before(first, budget)? else {
        return Ok(None);
    };
    let begin = bounds.tokens[colon].end;
    budget.charge(
        u64::from(first - begin)
            + bounds.indents.len().checked_ilog2().unwrap_or(0) as u64
            + bounds.line_ends.len().checked_ilog2().unwrap_or(0) as u64
            + 2,
        0,
        0,
    )?;
    let newline = source[begin as usize..first as usize].contains(['\n', '\r']);
    let end = if newline {
        let at = bounds.indents.partition_point(|i| *i <= colon);
        let Some(&index) = bounds.indents.get(at) else {
            return Ok(None);
        };
        if bounds.tokens[index].start > first {
            return Ok(None);
        }
        let Some(end) = bounds.close[index] else {
            return Ok(None);
        };
        end
    } else {
        let at = bounds.line_ends.partition_point(|end| *end < first);
        bounds
            .line_ends
            .get(at)
            .copied()
            .unwrap_or(source.len() as u32)
    };
    if end < begin {
        return Ok(None);
    }
    let indent = bounds.indentation(
        source,
        if newline {
            first
        } else {
            bounds.tokens[colon].start
        },
        budget,
        check,
    )?;
    Ok(Some(Suite {
        body,
        start: begin,
        end,
        indent,
    }))
}

pub(super) fn collect(
    module: &FrozenModule,
    body: &[Stmt],
    facts: &AstFacts<'_>,
    budget: &AnalysisContext,
    check: &dyn Fn() -> Result<()>,
) -> Result<(Vec<Comment>, BTreeSet<String>)> {
    let source = &module.source;
    budget.charge(source.len() as u64, 0, 0)?;
    // Conservative lexical absence check; quoted strings remain candidates so
    // malformed strings and docstrings still pass through the authoritative lexer.
    if !source.bytes().any(|b| matches!(b, b'#' | b'\'' | b'"')) {
        return Ok((vec![], BTreeSet::new()));
    }
    // Lexer internals, token/boundary vectors and source lines share this account.
    budget.charge(
        0,
        1,
        (source.len() as u64)
            .saturating_mul(32)
            .saturating_add(2048 + module.path.len() as u64),
    )?;
    let mut lexer = ruff_python_parser::lexer::lex(source, ruff_python_parser::Mode::Module);
    let mut tokens = Vec::new();
    let mut gaps = BTreeSet::new();
    let mut starts = Vec::new();
    let mut close = Vec::new();
    loop {
        check()?;
        budget.charge(1, 1, 256)?;
        let kind = lexer.next_token();
        let range = lexer.current_range();
        let i = tokens.len();
        tokens.push(Token {
            kind,
            start: range.start().to_u32(),
            end: range.end().to_u32(),
        });
        close.push(None);
        if kind == TokenKind::Indent {
            starts.push(i);
        } else if kind == TokenKind::Dedent
            && let Some(open) = starts.pop()
        {
            close[open] = Some(range.start().to_u32());
        }
        if kind == TokenKind::Unknown {
            gaps.insert("PYTHON_COMMENT_LEXICAL_ERROR".into());
        }
        if kind == TokenKind::EndOfFile {
            break;
        }
    }
    let bounds = TokenBounds::new(source, &tokens, &close, budget, check)?;
    let mut suites = vec![Suite {
        body,
        start: 0,
        end: source.len() as u32,
        indent: 0,
    }];
    let mut by_body = BTreeMap::from([(body_key(body), 0usize)]);
    for body in &facts.bodies {
        check()?;
        budget.charge(
            by_body.len().checked_ilog2().unwrap_or(0) as u64 + 1,
            1,
            256,
        )?;
        if by_body.contains_key(&body_key(body)) {
            continue;
        }
        if let Some(s) = suite(source, body, &bounds, budget, check)? {
            by_body.insert(body_key(body), suites.len());
            suites.push(s);
        } else if !body.is_empty() {
            gaps.insert("PYTHON_COMMENT_SUITE_RANGE_UNAVAILABLE".into());
        }
    }
    let mut scopes = Vec::new();
    let mut docs = vec![(body, Some(u32::MAX), 0, source.len() as u32)];
    for stmt in &facts.statements {
        check()?;
        let (body, anchor, callable) = match stmt {
            Stmt::FunctionDef(f) => (f.body.as_slice(), f.name.start().to_u32(), true),
            Stmt::ClassDef(c) => (c.body.as_slice(), c.name.start().to_u32(), false),
            _ => continue,
        };
        budget.charge(
            by_body.len().checked_ilog2().unwrap_or(0) as u64 + 1,
            1,
            256,
        )?;
        if let Some(s) = by_body.get(&body_key(body)).map(|i| &suites[*i]) {
            scopes.push(Scope {
                start: s.start,
                end: s.end,
                indent: s.indent,
                declaration: Some(anchor),
                callable,
                target_start: stmt.start().to_u32(),
            });
            docs.push((body, Some(anchor), stmt.start().to_u32(), s.end));
        }
    }
    for expr in &facts.expressions {
        if let Expr::Lambda(lambda) = expr {
            budget.charge(1, 1, 128)?;
            scopes.push(Scope {
                start: bounds
                    .colon_before(lambda.body.start().to_u32(), budget)?
                    .filter(|i| tokens[*i].start >= lambda.start().to_u32())
                    .map_or(lambda.body.start().to_u32(), |i| tokens[i].end),
                end: lambda.body.end().to_u32(),
                indent: bounds.indentation(source, lambda.start().to_u32(), budget, check)?,
                declaration: None,
                callable: true,
                target_start: lambda.start().to_u32(),
            });
        }
    }
    let mut result = Vec::new();
    for token in &tokens {
        check()?;
        if token.kind != TokenKind::Comment {
            continue;
        }
        let start = token.start as usize + 1;
        let end = token.end as usize;
        budget.charge(
            (end - start) as u64,
            1,
            ((end - start) as u64)
                .saturating_mul(8)
                .saturating_add(2048 + module.path.len() as u64),
        )?;
        let text = &source[start..end];
        let map = if text.is_empty() {
            vec![]
        } else {
            vec![CommentBodySegment {
                body_start: 0,
                body_end: text.len() as u64,
                source_start: start as u64,
                source_end: end as u64,
            }]
        };
        result.push(Comment {
            path: module.path.clone(),
            start: token.start,
            end: token.end,
            docstring: false,
            body: text.into(),
            body_map: map,
            links: vec![],
        });
    }
    for (body, owner, start, end) in docs {
        check()?;
        let Some(doc) = first_doc(body) else {
            continue;
        };
        budget.charge(
            u64::from(doc.range().len().to_u32()),
            1,
            u64::from(doc.range().len().to_u32())
                .saturating_mul(160)
                .saturating_add(2048 + module.path.len() as u64),
        )?;
        match doc_body(source, doc) {
            Some((body, body_map)) => result.push(Comment {
                path: module.path.clone(),
                start: doc.start().to_u32(),
                end: doc.end().to_u32(),
                docstring: true,
                body,
                body_map,
                links: vec![link(CommentRelation::Documents, owner, start, end)],
            }),
            None => {
                gaps.insert("PYTHON_DOCSTRING_SOURCE_MAP_UNAVAILABLE".into());
            }
        }
    }
    let scope_index = RangeIndex::new(scopes.iter().map(|s| (s.start, s.end, s.indent)), budget)?;
    let suite_index = RangeIndex::new(suites.iter().map(|s| (s.start, s.end, s.indent)), budget)?;
    for comment in &mut result {
        check()?;
        let indentation = bounds.indentation(source, comment.start, budget, check)?;
        if let Some(scope) = scope_index
            .nearest(comment.start, comment.end, indentation, budget, check)?
            .map(|i| &scopes[i])
            && scope.callable
        {
            budget.charge(1, 1, 128)?;
            comment.links.push(link(
                CommentRelation::EnclosingCallable,
                scope.declaration,
                scope.target_start,
                scope.end,
            ));
        }
        if comment.docstring {
            continue;
        }
        let Some(suite) = suite_index
            .nearest(comment.start, comment.end, indentation, budget, check)?
            .map(|i| &suites[i])
        else {
            continue;
        };
        budget.charge(
            suite.body.len().checked_ilog2().unwrap_or(0) as u64 + 1,
            0,
            0,
        )?;
        let at = suite
            .body
            .partition_point(|stmt| stmt.end().to_u32() <= comment.start);
        for (stmt, relation) in [
            (
                at.checked_sub(1).and_then(|i| suite.body.get(i)),
                CommentRelation::TrailingStatement,
            ),
            (suite.body.get(at), CommentRelation::LeadingStatement),
        ] {
            let Some(stmt) = stmt else {
                continue;
            };
            if matches!(stmt, Stmt::FunctionDef(_) | Stmt::ClassDef(_)) {
                continue;
            }
            let (a, b) = if relation == CommentRelation::TrailingStatement {
                (stmt.end().to_u32(), comment.start)
            } else {
                (comment.end, stmt.start().to_u32())
            };
            if a > b {
                continue;
            }
            budget.charge(
                u64::from(b - a) + tokens.len().checked_ilog2().unwrap_or(0) as u64 + 1,
                0,
                0,
            )?;
            let first = tokens.partition_point(|t| t.end <= a);
            let last = tokens.partition_point(|t| t.start < b);
            if a < b
                && tokens[first..last].iter().any(|t| {
                    !matches!(
                        t.kind,
                        TokenKind::Comment
                            | TokenKind::Newline
                            | TokenKind::NonLogicalNewline
                            | TokenKind::Indent
                            | TokenKind::Dedent
                    )
                })
            {
                continue;
            }
            let gap = &source[a as usize..b as usize];
            let allowed = if relation == CommentRelation::TrailingStatement {
                !gap.contains(['\n', '\r'])
            } else {
                !codeana_model::comment_gap_has_blank_line(gap)
            };
            if allowed {
                budget.charge(1, 1, 128)?;
                comment.links.push(link(
                    relation,
                    None,
                    stmt.start().to_u32(),
                    stmt.end().to_u32(),
                ));
            }
        }
    }
    result.sort_by_key(|c| (c.start, c.end));
    Ok((result, gaps))
}

// Consume authoritative AST values. Scan only string literal content to locate each
// escape's source span; never reinterpret arbitrary source or execute a string.
fn doc_body(
    source: &str,
    doc: &ruff_python_ast::ExprStringLiteral,
) -> Option<(String, Vec<CommentBodySegment>)> {
    let mut body = String::new();
    let mut map = Vec::new();
    for part in doc.value.iter() {
        if part.flags.is_invalid() {
            return None;
        }
        let range = part.content_range();
        let raw = source.get(range.start().to_usize()..range.end().to_usize())?;
        let origin = range.start().to_usize();
        let raw_mode = part.flags.prefix().is_raw();
        let mut at = 0;
        let mut decoded = 0;
        while at < raw.len() {
            let start = at;
            let ch = raw[at..].chars().next()?;
            at += ch.len_utf8();
            let mut count = 1;
            if !raw_mode && ch == '\\' {
                let escape = raw[at..].chars().next()?;
                at += escape.len_utf8();
                match escape {
                    '\n' => count = 0,
                    '\r' => {
                        if raw.as_bytes().get(at) == Some(&b'\n') {
                            at += 1;
                        }
                        count = 0;
                    }
                    'x' | 'u' | 'U' => {
                        let digits = match escape {
                            'x' => 2,
                            'u' => 4,
                            _ => 8,
                        };
                        let value = u32::from_str_radix(raw.get(at..at + digits)?, 16).ok()?;
                        if (0xd800..=0xdfff).contains(&value) {
                            return None;
                        }
                        at += digits;
                    }
                    'N' => {
                        if raw.as_bytes().get(at) != Some(&b'{') {
                            return None;
                        }
                        at += raw[at..].find('}')? + 1;
                    }
                    '0'..='7' => {
                        for _ in 0..2 {
                            if raw
                                .as_bytes()
                                .get(at)
                                .is_some_and(|c| (b'0'..=b'7').contains(c))
                            {
                                at += 1;
                            } else {
                                break;
                            }
                        }
                    }
                    '\\' | '\'' | '"' | 'a' | 'b' | 'f' | 'n' | 'r' | 't' | 'v' => {}
                    _ => count = 2,
                }
            }
            let bs = body.len();
            let mut end = decoded;
            for _ in 0..count {
                end += part.value.get(end..)?.chars().next()?.len_utf8();
            }
            let value = part.value.get(decoded..end)?;
            if (raw_mode || ch != '\\') && value != &raw[start..at] {
                return None;
            }
            body.push_str(value);
            decoded = end;
            if bs < body.len() {
                map.push(CommentBodySegment {
                    body_start: bs as u64,
                    body_end: body.len() as u64,
                    source_start: (origin + start) as u64,
                    source_end: (origin + at) as u64,
                });
            }
        }
        if decoded != part.value.len() {
            return None;
        }
    }
    ensure_body(doc, &body)?;
    Some((body, map))
}
fn ensure_body(doc: &ruff_python_ast::ExprStringLiteral, body: &str) -> Option<()> {
    (doc.value.to_str() == body).then_some(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn extract(source: &str) -> (Vec<Comment>, BTreeSet<String>) {
        let parsed = ruff_python_parser::parse_module(source).unwrap();
        let budget = AnalysisContext::new(Default::default(), None).unwrap();
        let facts = super::super::ast_facts(&parsed.syntax().body, &budget).unwrap();
        collect(
            &FrozenModule {
                name: "fixture".into(),
                path: "fixture.py".into(),
                source: source.into(),
            },
            &parsed.syntax().body,
            &facts,
            &budget,
            &|| Ok(()),
        )
        .unwrap()
    }
    fn find<'a>(all: &'a [Comment], needle: &str) -> &'a Comment {
        all.iter()
            .find(|c| c.body.contains(needle))
            .unwrap_or_else(|| panic!("missing {needle}: {all:#?}"))
    }
    #[test]
    fn many_commented_suites_fit_one_existing_work_account() {
        let source = (0..1000)
            .map(|i| format!("def f_{i}():\n    # 库存{i}\n    return {i}\n"))
            .collect::<String>();
        let parsed = ruff_python_parser::parse_module(&source).unwrap();
        let budget = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_work: 1_000_000,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        let facts = super::super::ast_facts(&parsed.syntax().body, &budget).unwrap();
        let module = FrozenModule {
            name: "scale".into(),
            path: "scale.py".into(),
            source,
        };
        let (all, gaps) =
            collect(&module, &parsed.syntax().body, &facts, &budget, &|| Ok(())).unwrap();
        assert!(gaps.is_empty());
        assert_eq!(all.len(), 1000);
        assert!(all.iter().all(|c| {
            c.links.iter().any(|l| {
                l.relation == CommentRelation::EnclosingCallable && l.declaration.is_some()
            })
        }));
        assert!(all.iter().all(|c| {
            c.links
                .iter()
                .any(|l| l.relation == CommentRelation::LeadingStatement)
        }));
    }
    #[test]
    fn lexical_absence_does_not_build_per_function_token_scopes() {
        let source = (0..10000)
            .map(|i| format!("def f_{i}():\n    return {i}\n"))
            .collect::<String>();
        let parsed = ruff_python_parser::parse_module(&source).unwrap();
        let budget = AnalysisContext::new(
            codeana_model::AnalysisLimits {
                max_work: 2_000_000,
                ..Default::default()
            },
            None,
        )
        .unwrap();
        let facts = super::super::ast_facts(&parsed.syntax().body, &budget).unwrap();
        let module = FrozenModule {
            name: "scale".into(),
            path: "scale.py".into(),
            source,
        };
        let (all, gaps) =
            collect(&module, &parsed.syntax().body, &facts, &budget, &|| Ok(())).unwrap();
        assert!(all.is_empty());
        assert!(gaps.is_empty());
    }
    #[test]
    fn only_standard_docstring_positions_are_comments() {
        let source = "\"\"\"模块文档\"\"\"\nvalue='# 字符串伪注释'\n'普通晚字符串'\nclass C:\n    r'''类文档'''\n    def method(self):\n        ('方法' '文档')\n        value='普通字符串'\n        b'ordinary bytes'\n        f'普通fstring'\n        # 实际注释\n        return value\n";
        let (all, gaps) = extract(source);
        assert!(gaps.is_empty(), "{gaps:?}");
        assert_eq!(all.len(), 4, "{all:#?}");
        for needle in ["模块文档", "类文档", "方法文档"] {
            assert!(find(&all, needle).docstring);
            assert!(
                find(&all, needle)
                    .links
                    .iter()
                    .any(|l| l.relation == CommentRelation::Documents && l.declaration.is_some())
            );
        }
        assert!(!find(&all, "实际").docstring);
    }
    #[test]
    fn escaped_raw_concatenated_crlf_and_unicode_positions_keep_exact_spans() {
        let source = "\"\"\"模块😀\r\n第二行\"\"\"\r\ndef run():\r\n    (\"库存\\u5e93\\u5b58\\x41\\101\\N{GRINNING FACE}\" r\"原始\\n\" \"续\\\r\n行\")\r\n    pass # 尾部😀\r\n";
        let (all, gaps) = extract(source);
        assert!(gaps.is_empty(), "{gaps:?}");
        let doc = find(&all, "库存库存AA😀原始\\n续行");
        assert_eq!(doc.body_map.len(), doc.body.chars().count());
        let escaped = doc
            .body_map
            .iter()
            .find(|s| {
                &doc.body[s.body_start as usize..s.body_end as usize] == "库"
                    && &source[s.source_start as usize..s.source_end as usize] == "\\u5e93"
            })
            .unwrap();
        assert_eq!(escaped.source_end - escaped.source_start, 6);
        assert!(doc.body_map.iter().any(|s| &source
            [s.source_start as usize..s.source_end as usize]
            == "\\N{GRINNING FACE}"));
        for c in all {
            let mut end = 0;
            for s in c.body_map {
                assert_eq!(s.body_start, end);
                assert!(s.source_start < s.source_end);
                assert!(
                    source
                        .get(s.source_start as usize..s.source_end as usize)
                        .is_some()
                );
                end = s.body_end;
            }
            assert_eq!(end, c.body.len() as u64);
        }
    }
    #[test]
    fn nested_functions_classes_trailing_body_comments_and_file_tail_do_not_cross() {
        let source = "def outer():\n    # 外层方法\n    pass\n    def inner():\n        # 内层方法\n        pass\n        # 内层尾注释\n    # 外层恢复\n    class Local:\n        # 类内隔离\n        pass\n    # 外层尾注释\n# 文件尾注释\n";
        let (all, gaps) = extract(source);
        assert!(gaps.is_empty(), "{gaps:?}");
        let owner = |name| {
            find(&all, name)
                .links
                .iter()
                .find(|l| l.relation == CommentRelation::EnclosingCallable)
                .and_then(|l| l.declaration)
        };
        assert!(owner("外层方法").is_some());
        assert!(owner("内层方法").is_some());
        assert_ne!(owner("外层方法"), owner("内层方法"));
        assert_eq!(owner("内层方法"), owner("内层尾注释"));
        assert_eq!(owner("外层方法"), owner("外层恢复"));
        assert_eq!(owner("外层方法"), owner("外层尾注释"));
        assert!(owner("类内隔离").is_none());
        assert!(owner("文件尾注释").is_none());
    }
    #[test]
    fn same_suite_statement_links_blank_lines_and_inline_suite() {
        let source = "def run():\n    # 前置\n    a=1 # 行尾\n    # 空行\n\n    b=2\n    if a:\n        # 子块\n        b=3\ndef inline(): pass # 单行函数\n";
        let (all, gaps) = extract(source);
        assert!(gaps.is_empty());
        let has = |name, kind| find(&all, name).links.iter().any(|l| l.relation == kind);
        assert!(has("前置", CommentRelation::LeadingStatement), "{all:#?}");
        assert!(has("行尾", CommentRelation::TrailingStatement));
        assert!(!has("空行", CommentRelation::LeadingStatement));
        assert!(!has("子块", CommentRelation::TrailingStatement));
        assert!(has("单行函数", CommentRelation::EnclosingCallable));
    }
    #[test]
    fn surrogate_docstring_gap_keeps_other_lexical_comments() {
        let (all, gaps) = extract("def run():\n    '\\ud800'\n    # 仍可检索\n    pass\n");
        assert!(gaps.contains("PYTHON_DOCSTRING_SOURCE_MAP_UNAVAILABLE"));
        assert_eq!(all.len(), 1);
        assert!(
            find(&all, "仍可")
                .links
                .iter()
                .any(|l| l.relation == CommentRelation::EnclosingCallable)
        );
    }
}
