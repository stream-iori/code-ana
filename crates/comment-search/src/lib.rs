//! Shared comment tokenization. No SQL, query dispatcher, or language parser dependencies.
//! Every operation accepts the caller's existing budget/cancellation admission callback.
use codeana_model::{DomainError, ErrorCode};
use jieba_rs::{Jieba, TokenizeMode};
use sha2::{Digest, Sha256};
use std::sync::LazyLock;
mod source_map;
pub use source_map::map_hits;
mod positions;
pub use positions::SourcePositions;

/// SHA-256 of jieba-rs 0.11.0's src/data/dict.txt, embedded by its default-dict feature.
/// Cargo.lock authenticates the package containing both code and dictionary.
pub const DICTIONARY_SHA256: &str =
    "139519822fe8ab9e10d9d07e68ea0451045380aedaf54ecc51e2a28c6b42a13f";
pub const ENGINE_VERSION: &str = "jieba-rs=0.11.0";
pub const RULES: &str = "comments-v1;index=search;query=default;hmm=true;unicode-lowercase;ascii-identifier-full+camel+underscore+digits;and;utf8-half-open";

// Never expose mutable dictionary/HMM configuration or runtime dictionary loading.
static JIEBA: LazyLock<Jieba> = LazyLock::new(Jieba::new);

/// Include this in the semantic index identity, not presentation configuration.
pub fn identity() -> String {
    let mut hash = Sha256::new();
    for part in [ENGINE_VERSION, DICTIONARY_SHA256, RULES] {
        hash.update((part.len() as u64).to_be_bytes());
        hash.update(part.as_bytes());
    }
    format!("{:x}", hash.finalize())
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
pub struct BodyHit {
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, PartialEq, Eq, PartialOrd, Ord)]
struct Token {
    term: String,
    start: usize,
    end: usize,
}

/// Use an existing AnalysisContext or ResourceBudget, plus the task's cancellation check.
/// Charge before jieba allocates: its temporary routes, token vectors, normalization and
/// our retained tokens are covered by a conservative per-byte reservation. No truncation.
fn tokens(
    text: &str,
    mode: TokenizeMode,
    admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
) -> Result<Vec<Token>, DomainError> {
    let bytes = text.len() as u64;
    admit(
        bytes.saturating_mul(4).saturating_add(1),
        bytes.saturating_mul(512).saturating_add(4096),
    )?;
    let mut result = Vec::new();
    for token in JIEBA.tokenize(text, mode, true) {
        admit(1, 0)?;
        push_token(&mut result, text, token.byte_start, token.byte_end);
    }
    // Independently identify ASCII code words, even next to Chinese text or punctuation.
    // Keep their complete spelling and add components without losing original offsets.
    let mut start = None;
    for (offset, ch) in text
        .char_indices()
        .chain(std::iter::once((text.len(), ' ')))
    {
        if ch.is_ascii_alphanumeric() || ch == '_' {
            start.get_or_insert(offset);
        } else if let Some(begin) = start.take() {
            admit(1, 0)?;
            push_token(&mut result, text, begin, offset);
            identifier_parts(&mut result, text, begin, offset);
        }
    }
    admit(
        (result.len() as u64).saturating_mul(result.len().checked_ilog2().unwrap_or(0) as u64 + 1),
        0,
    )?;
    result.sort_unstable();
    result.dedup();
    admit(0, 0)?;
    Ok(result)
}

fn push_token(tokens: &mut Vec<Token>, text: &str, start: usize, end: usize) {
    let word = &text[start..end];
    if word.chars().any(char::is_alphanumeric) {
        tokens.push(Token {
            term: word.to_lowercase(),
            start,
            end,
        });
    }
}

fn identifier_parts(tokens: &mut Vec<Token>, text: &str, start: usize, end: usize) {
    let bytes = text.as_bytes();
    let mut part = start;
    for i in start..end {
        if bytes[i] == b'_' {
            push_token(tokens, text, part, i);
            part = i + 1;
            continue;
        }
        if i == part {
            continue;
        }
        let previous = bytes[i - 1];
        let current = bytes[i];
        let camel = previous.is_ascii_lowercase() && current.is_ascii_uppercase();
        let acronym = previous.is_ascii_uppercase()
            && current.is_ascii_uppercase()
            && i + 1 < end
            && bytes[i + 1].is_ascii_lowercase();
        let digit = previous.is_ascii_digit() != current.is_ascii_digit();
        if camel || acronym || digit {
            push_token(tokens, text, part, i);
            part = i;
        }
    }
    push_token(tokens, text, part, end);
}

fn terms(tokens: Vec<Token>) -> Vec<String> {
    let mut terms: Vec<_> = tokens.into_iter().map(|token| token.term).collect();
    terms.dedup(); // tokens are already ordered by term, then source location.
    terms
}

pub fn index_terms(
    body: &str,
    admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
) -> Result<Vec<String>, DomainError> {
    Ok(terms(tokens(body, TokenizeMode::Search, admit)?))
}

pub fn query_terms(
    query: &str,
    admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
) -> Result<Vec<String>, DomainError> {
    let terms = terms(tokens(query, TokenizeMode::Default, admit)?);
    if terms.is_empty() {
        return Err(DomainError::new(ErrorCode::CommentQueryEmpty, ""));
    }
    Ok(terms)
}

/// Recheck candidate bodies. Offsets come from original UTF-8 text, never FTS text.
/// None means at least one AND term is absent; all occurrences retain their own ranges.
pub fn match_body(
    body: &str,
    query: &[String],
    admit: &dyn Fn(u64, u64) -> Result<(), DomainError>,
) -> Result<Option<Vec<BodyHit>>, DomainError> {
    if query.is_empty() {
        return Err(DomainError::new(ErrorCode::CommentQueryEmpty, ""));
    }
    let tokens = tokens(body, TokenizeMode::Search, admit)?;
    admit(
        (query.len() as u64).saturating_mul(query.len().checked_ilog2().unwrap_or(0) as u64 + 1),
        (query.len() as u64).saturating_mul(32),
    )?;
    let mut query: Vec<&str> = query.iter().map(String::as_str).collect();
    query.sort_unstable();
    query.dedup();
    for term in &query {
        admit(tokens.len().checked_ilog2().unwrap_or(0) as u64 + 1, 0)?;
        if tokens
            .binary_search_by(|token| token.term.as_str().cmp(term))
            .is_err()
        {
            return Ok(None);
        }
    }
    let mut hits = Vec::new();
    for token in tokens {
        admit(query.len().checked_ilog2().unwrap_or(0) as u64 + 1, 0)?;
        if query.binary_search(&token.term.as_str()).is_ok() {
            hits.push(BodyHit {
                start: token.start,
                end: token.end,
            });
        }
    }
    hits.sort_unstable();
    hits.dedup();
    Ok(Some(hits))
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_model::{AnalysisContext, AnalysisLimits, ResourceBudget, ResourceLimits};

    fn budget() -> ResourceBudget {
        ResourceBudget::new(ResourceLimits::default())
    }

    #[test]
    fn chinese_search_mode_recalls_default_query_and_requires_every_term() {
        let budget = budget();
        let admit = |work, bytes| budget.charge(work, bytes);
        let body = "南京市长江大桥，库存不足时扣减失败。";
        let indexed = index_terms(body, &admit).unwrap();
        for query in ["南京", "长江大桥", "库存 不足"] {
            let query = query_terms(query, &admit).unwrap();
            assert!(query.iter().all(|term| indexed.contains(term)), "{query:?}");
            assert!(match_body(body, &query, &admit).unwrap().is_some());
        }
        let query = query_terms("库存 支付", &admit).unwrap();
        assert!(match_body(body, &query, &admit).unwrap().is_none());
    }

    #[test]
    fn identifier_spelling_components_acronyms_and_digits_keep_source_ranges() {
        let budget = budget();
        let admit = |work, bytes| budget.charge(work, bytes);
        let body = "库存😀 getHTTPResponse2 stock_count";
        let terms = index_terms(body, &admit).unwrap();
        for term in [
            "gethttpresponse2",
            "get",
            "http",
            "response",
            "2",
            "stock_count",
            "stock",
            "count",
        ] {
            assert!(
                terms.iter().any(|actual| actual == term),
                "{term}: {terms:?}"
            );
        }
        let query = query_terms("HTTP count", &admit).unwrap();
        let hits = match_body(body, &query, &admit).unwrap().unwrap();
        let words: Vec<_> = hits.iter().map(|hit| &body[hit.start..hit.end]).collect();
        assert_eq!(words, ["HTTP", "count"]);
    }

    #[test]
    fn punctuation_is_not_a_query_and_repeated_terms_are_deduplicated() {
        let budget = budget();
        let admit = |work, bytes| budget.charge(work, bytes);
        for query in ["", " \r\n", "😀，！_ + - \" : *"] {
            assert_eq!(
                query_terms(query, &admit).unwrap_err().code,
                ErrorCode::CommentQueryEmpty
            );
        }
        assert_eq!(query_terms("库存 库存", &admit).unwrap(), ["库存"]);
        let terms = query_terms("OR", &admit).unwrap();
        assert!(match_body("ordinary", &terms, &admit).unwrap().is_none());
        assert_eq!(index_terms("/* */", &admit).unwrap(), Vec::<String>::new());
    }

    #[test]
    fn unicode_lowercase_and_crlf_highlights_use_original_byte_spans() {
        let budget = budget();
        let admit = |work, bytes| budget.charge(work, bytes);
        let body = "😀\r\nÉCOLE 库存\r\n库存";
        let query = query_terms("é 库存", &admit).unwrap();
        let hits = match_body(body, &query, &admit).unwrap().unwrap();
        let words: Vec<_> = hits.iter().map(|hit| &body[hit.start..hit.end]).collect();
        assert_eq!(words, ["É", "库存", "库存"]);
        assert_eq!(hits[0].start, 6);
        // Lowercasing U+0130 expands to i + combining dot. Its hit still spans the
        // original two bytes, rather than the three normalized UTF-8 bytes.
        let query = query_terms("İ", &admit).unwrap();
        let hits = match_body("😀İ", &query, &admit).unwrap().unwrap();
        assert_eq!(hits, [BodyHit { start: 4, end: 6 }]);
    }

    #[test]
    fn rejected_admission_and_cancellation_return_no_partial_terms() {
        let budget = ResourceBudget::new(ResourceLimits {
            max_work: 1,
            ..Default::default()
        });
        assert!(index_terms("超大注释", &|work, bytes| budget.charge(work, bytes)).is_err());
        assert!(budget.exhausted());
        let cancelled = |_, _| Err(DomainError::new(ErrorCode::Cancelled, ""));
        assert_eq!(
            index_terms("库存", &cancelled).unwrap_err().code,
            ErrorCode::Cancelled
        );
        let context = AnalysisContext::new(AnalysisLimits::default(), None).unwrap();
        let terms = index_terms("库存", &|work, bytes| {
            context
                .charge(work, 0, bytes)
                .map_err(DomainError::analysis)
        })
        .unwrap();
        assert!(terms.contains(&"库存".into()));
    }
}
