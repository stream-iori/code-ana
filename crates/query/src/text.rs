//! Text matching over captured bytes; bounded context windows and result batches.
use super::*;

pub(crate) fn text_matcher(req: &Request) -> Result<Option<regex::Regex>> {
    ensure!(
        !req.selector.is_empty(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::TextPatternRequired, "")
    );
    ensure!(
        req.selector.len() <= 16384,
        codeana_model::DomainError::new(codeana_model::ErrorCode::TextPatternBudget, "")
    );
    if !req.text_regex && !req.text_ignore_case {
        return Ok(None);
    }
    let pattern = if req.text_regex {
        req.selector.clone()
    } else {
        regex::escape(&req.selector)
    };
    Ok(Some(
        regex::RegexBuilder::new(&pattern)
            .case_insensitive(req.text_ignore_case)
            .size_limit(4 * 1024 * 1024)
            .build()
            .map_err(|e| {
                anyhow::anyhow!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::TextRegexInvalid,
                    format!("{e}")
                ))
            })?,
    ))
}

fn lines(
    text: &str,
    source: &SourceUnit,
    req: &Request,
    matcher: Option<&regex::Regex>,
    sink: &mut dyn FnMut(Vec<Record>) -> Result<()>,
) -> Result<()> {
    let mut incoming = text.lines().enumerate();
    let mut following = VecDeque::new();
    let mut previous: VecDeque<(usize, &str)> = VecDeque::new();
    let context = req.text_context as usize;
    for _ in 0..=context {
        if let Some(line) = incoming.next() {
            following.push_back(line);
        }
    }
    let mut batch = Vec::new();
    let mut batch_bytes = 0;
    while let Some(&(index, line)) = following.front() {
        if matcher.map_or_else(|| line.contains(&req.selector), |m| m.is_match(line)) {
            let record = Record::Text(TextMatch {
                path: source.path.clone(),
                line: index + 1,
                text: line.to_owned(),
                hash: source.hash.clone(),
                context_before: (context > 0).then(|| {
                    previous
                        .iter()
                        .map(|&(i, t)| TextLine {
                            line: i + 1,
                            text: t.to_owned(),
                        })
                        .collect()
                }),
                context_after: (context > 0).then(|| {
                    following
                        .iter()
                        .skip(1)
                        .map(|&(i, t)| TextLine {
                            line: i + 1,
                            text: t.to_owned(),
                        })
                        .collect()
                }),
            });
            let size = serde_json::to_vec(&record.wire()?)?.len();
            ensure!(
                size <= 8 * 1024 * 1024,
                codeana_model::DomainError::new(codeana_model::ErrorCode::OutputRecordBudget, "")
            );
            if !batch.is_empty() && batch_bytes + size > 1024 * 1024 {
                sink(std::mem::take(&mut batch))?;
                batch_bytes = 0;
            }
            batch.push(record);
            batch_bytes += size;
            if batch.len() == 512 || batch_bytes >= 1024 * 1024 {
                sink(std::mem::take(&mut batch))?;
                batch_bytes = 0;
            }
        }
        if index % 512 == 0 {
            sink(Vec::new())?;
        }
        let current = following.pop_front().unwrap();
        if context > 0 {
            previous.push_back(current);
            if previous.len() > context {
                previous.pop_front();
            }
        }
        if let Some(next) = incoming.next() {
            following.push_back(next);
        }
    }
    if !batch.is_empty() {
        sink(batch)?;
    }
    Ok(())
}

fn stream_text_query(
    reader: &dyn SourceContext,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
    sink: &mut dyn FnMut(Vec<Record>) -> Result<()>,
) -> Result<Output> {
    stream_text_candidates_query(reader, req, read_source, None, sink)
}

fn stream_text_candidates_query(
    reader: &dyn SourceContext,
    req: &Request,
    read_source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
    candidates: Option<&BTreeSet<(String, String)>>,
    sink: &mut dyn FnMut(Vec<Record>) -> Result<()>,
) -> Result<Output> {
    preflight_query(req)?;
    ensure!(
        req.operation == "search-text",
        codeana_model::DomainError::new(codeana_model::ErrorCode::StreamTextOperationRequired, "")
    );
    let matcher = text_matcher(req)?;
    let mut paths = globset::GlobSetBuilder::new();
    for path in &req.paths {
        paths.add(
            globset::GlobBuilder::new(path)
                .literal_separator(true)
                .backslash_escape(false)
                .build()?,
        );
    }
    let paths = paths.build()?;
    let mut evidence = selection_evidence(reader, req);
    for source in &reader.manifest().files {
        if (!req.scope.is_empty() && req.scope != "ALL" && req.scope != source.scope)
            || (!req.module.is_empty() && req.module != source.module)
            || (!req.world.is_empty() && req.world != source.world)
            || (!req.paths.is_empty() && !paths.is_match(&source.path))
        {
            continue;
        }
        if candidates.is_some_and(|set| !set.contains(&(source.world.clone(), source.path.clone())))
        {
            continue;
        }
        let bytes = read_source(source)?;
        ensure!(
            hash(&bytes) == source.hash,
            codeana_model::DomainError::new(codeana_model::ErrorCode::SourceHashMismatch, "")
        );
        match std::str::from_utf8(&bytes) {
            Ok(text) => lines(text, source, req, matcher.as_ref(), sink)?,
            Err(_) => {
                evidence.enumeration = false;
                evidence.semantic = false;
                evidence
                    .reasons
                    .push(format!("text_encoding_unsupported:{}", source.path));
            }
        }
    }
    Ok(Output {
        revision: reader.revision().id.clone(),
        producer: reader.revision().producer.clone(),
        evidence,
        ..Default::default()
    })
}

pub fn stream_text<'a, R>(
    reader: &dyn SourceContext,
    request: &'a R,
    source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
    sink: &mut dyn FnMut(Vec<Record>) -> Result<()>,
) -> Result<Output>
where
    Request: From<&'a R>,
{
    stream_text_query(reader, &Request::from(request), source, sink)
}
pub fn stream_text_candidates<'a, R>(
    reader: &dyn SourceContext,
    request: &'a R,
    source: &dyn Fn(&SourceUnit) -> Result<Vec<u8>>,
    candidates: Option<&BTreeSet<(String, String)>>,
    sink: &mut dyn FnMut(Vec<Record>) -> Result<()>,
) -> Result<Output>
where
    Request: From<&'a R>,
{
    stream_text_candidates_query(reader, &Request::from(request), source, candidates, sink)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn upstream_source(text: &str) -> SourceUnit {
        SourceUnit {
            path: "a:b 空格.java".into(),
            hash: hash(text.as_bytes()),
            size: text.len() as u64,
            module: String::new(),
            world: String::new(),
            scope: "MAIN".into(),
            language: String::new(),
            auxiliary: false,
        }
    }

    #[test]
    fn upstream_literal_unicode_crlf_and_repeated_matches_preserve_context() {
        let text = "before\r\n中文.* 中文.*\r\nafter\r\n";
        let source = upstream_source(text);
        let mut req = Request {
            operation: "search-text".into(),
            selector: "中文.*".into(),
            text_context: 1,
            ..Default::default()
        };
        let mut rows = Vec::new();
        lines(
            text,
            &source,
            &req,
            text_matcher(&req).unwrap().as_ref(),
            &mut |batch| {
                rows.extend(batch);
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].wire().unwrap()["path"], "a:b 空格.java");
        assert_eq!(rows[0].wire().unwrap()["line"], 2);
        assert_eq!(rows[0].wire().unwrap()["text"], "中文.* 中文.*");
        assert_eq!(
            rows[0].wire().unwrap()["context_before"],
            json!([{"line":1,"text":"before"}])
        );
        assert_eq!(
            rows[0].wire().unwrap()["context_after"],
            json!([{"line":3,"text":"after"}])
        );
        req.text_context = 0;
        rows.clear();
        lines(text, &source, &req, None, &mut |batch| {
            rows.extend(batch);
            Ok(())
        })
        .unwrap();
        assert!(rows[0].wire().unwrap().get("context_before").is_none());
        assert!(rows[0].wire().unwrap().get("context_after").is_none());
        assert_eq!(rows[0].wire().unwrap()["hash"], source.hash);
    }

    #[test]
    fn upstream_invalid_regex_is_checked_without_files_and_missing_literal_is_empty() {
        let mut req = Request {
            operation: "search-text".into(),
            selector: "[".into(),
            text_regex: true,
            ..Default::default()
        };
        assert!(
            text_matcher(&req)
                .unwrap_err()
                .to_string()
                .starts_with("TEXT_REGEX_INVALID")
        );
        req.selector = "missing".into();
        req.text_regex = false;
        let mut count = 0;
        lines(
            "hit\nhit\nhit\n",
            &upstream_source("hit\nhit\nhit\n"),
            &req,
            None,
            &mut |batch| {
                count += batch.len();
                Ok(())
            },
        )
        .unwrap();
        assert_eq!(count, 0);
    }

    #[test]
    fn upstream_closed_output_and_expired_check_stop_without_scanning_remaining_lines() {
        let text = "hit\n".repeat(10000);
        let req = Request {
            operation: "search-text".into(),
            selector: "hit".into(),
            ..Default::default()
        };
        for message in ["closed output", "TEXT_SEARCH_TIMEOUT"] {
            let mut calls = 0;
            let error = lines(&text, &upstream_source(&text), &req, None, &mut |_| {
                calls += 1;
                anyhow::bail!("{message}")
            })
            .unwrap_err();
            assert_eq!(error.to_string(), message);
            assert_eq!(calls, 1);
        }
    }

    #[test]
    fn long_search_uses_bounded_batches_and_complete_context() {
        let text = "match\n".repeat(100003);
        let source = SourceUnit {
            path: "x".into(),
            hash: hash(text.as_bytes()),
            size: text.len() as u64,
            module: String::new(),
            world: String::new(),
            scope: "MAIN".into(),
            language: String::new(),
            auxiliary: false,
        };
        let req = Request {
            operation: "search-text".into(),
            selector: "match".into(),
            text_context: 1,
            ..Default::default()
        };
        let mut count = 0;
        let mut last = Value::Null;
        lines(&text, &source, &req, None, &mut |batch| {
            assert!(batch.len() <= 512);
            count += batch.len();
            if let Some(row) = batch.last() {
                last = row.wire().unwrap();
            }
            Ok(())
        })
        .unwrap();
        assert_eq!(count, 100003);
        assert_eq!(last["line"], 100003);
        assert_eq!(
            last["context_before"],
            json!([{"line":100002,"text":"match"}])
        );
        assert_eq!(last["context_after"], json!([]));
    }
}
