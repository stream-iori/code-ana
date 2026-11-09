//! A bounded text query specification, not a shell program.
use anyhow::{Result, ensure};
use clap::{Args, FromArgMatches};
use codeana_model::{DomainError, ErrorCode, Stage};
use std::path::Path;

pub fn read(path: &Path) -> Result<Vec<Stage>> {
    let bytes = std::fs::read(path)?;
    ensure!(
        bytes.len() <= 1024 * 1024,
        DomainError::new(ErrorCode::PipelineSpecBudget, "maximum 1 MiB")
    );
    parse(std::str::from_utf8(&bytes)?)
}

pub fn parse(text: &str) -> Result<Vec<Stage>> {
    let mut stages = Vec::new();
    for (index, line) in text.lines().enumerate() {
        if line.trim().is_empty() {
            continue;
        }
        let words = words(line).map_err(|e| anyhow::anyhow!("pipeline line {}: {e}", index + 1))?;
        let operation: codeana_model::Operation = words[0].parse()?;
        ensure!(
            operation != codeana_model::Operation::Pipeline
                && codeana_application::query_input_fields(operation).is_some(),
            DomainError::new(
                ErrorCode::UnsupportedOperation,
                "pipeline accepts non-nested core queries only"
            )
        );
        let command = super::QueryArgs::augment_args(clap::Command::new(operation.as_str())).arg(
            clap::Arg::new("from")
                .long("from")
                .value_parser(["entity", "caller", "callee", "target", "parent", "site"])
                .default_value("entity"),
        );
        let matches = command.try_get_matches_from(words.iter())?;
        for field in super::command_contract::explicit(&matches) {
            ensure!(
                [
                    "selector",
                    "direction",
                    "relation",
                    "unique",
                    "meta",
                    "instantiability",
                    "transitive",
                    "algorithm",
                    "analysis_world",
                    "depth",
                    "from"
                ]
                .contains(&field.as_str()),
                DomainError::new(
                    ErrorCode::InvalidArgument,
                    format!(
                        "pipeline line {}: {field} belongs on the outer request or is not supported by a stage",
                        index + 1
                    )
                )
            );
            if field != "from" {
                ensure!(
                    codeana_application::query_input_fields(operation)
                        .unwrap()
                        .contains(&field.as_str()),
                    DomainError::new(
                        ErrorCode::InvalidArgument,
                        format!(
                            "pipeline line {}: {field} is not valid for {operation}",
                            index + 1
                        )
                    )
                );
            }
        }
        let from = matches.get_one::<String>("from").unwrap();
        let endpoint = if from == "entity" {
            codeana_model::Endpoint::Entity
        } else {
            from.parse()?
        };
        let a = super::QueryArgs::from_arg_matches(&matches)?;
        stages.push(Stage {
            operation,
            selector: a.selector,
            direction: a
                .direction
                .unwrap_or_else(|| {
                    if operation == codeana_model::Operation::References {
                        "in"
                    } else {
                        "out"
                    }
                    .into()
                })
                .parse()?,
            relation: a.relation,
            unique: a.unique,
            meta: a.meta,
            instantiability: a.instantiability.parse()?,
            transitive: a.transitive,
            algorithm: a.algorithm.parse()?,
            analysis_world: a.analysis_world.parse()?,
            depth: a.depth,
            endpoint,
        });
        ensure!(
            stages.len() <= 16,
            DomainError::new(ErrorCode::PipelineStageBudget, "maximum 16 stages")
        );
    }
    ensure!(
        !stages.is_empty(),
        DomainError::new(ErrorCode::PipelineRequired, "at least one stage")
    );
    Ok(stages)
}

fn words(line: &str) -> Result<Vec<String>> {
    let mut result = Vec::new();
    let mut current = String::new();
    let mut quote = None;
    let mut escaped = false;
    let mut started = false;
    for c in line.chars() {
        if escaped {
            current.push(match c {
                'n' if quote == Some('"') => '\n',
                'r' if quote == Some('"') => '\r',
                't' if quote == Some('"') => '\t',
                other => other,
            });
            escaped = false;
            started = true;
            continue;
        }
        if c == '\\' && quote != Some('\'') {
            escaped = true;
            started = true;
            continue;
        }
        if let Some(q) = quote {
            if c == q {
                quote = None;
            } else {
                current.push(c);
            }
            started = true;
        } else if c == '\'' || c == '"' {
            quote = Some(c);
            started = true;
        } else if c.is_whitespace() {
            if started {
                result.push(std::mem::take(&mut current));
                started = false;
            }
        } else {
            current.push(c);
            started = true;
        }
    }
    ensure!(
        quote.is_none() && !escaped,
        DomainError::new(
            ErrorCode::InvalidArgument,
            "unterminated pipeline quote or escape"
        )
    );
    if started {
        result.push(current);
    }
    ensure!(
        !result.is_empty(),
        DomainError::new(ErrorCode::PipelineRequired, "empty stage")
    );
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn selector_hash_and_typed_endpoints_survive_text_parsing() {
        let stages = parse("search-code 'greet'\nsource --from entity\n").unwrap();
        assert_eq!(stages.len(), 2);
        assert_eq!(
            words("source 'p.Service#greet(java.lang.String)'").unwrap()[1],
            "p.Service#greet(java.lang.String)"
        );
        assert!(parse("pipeline nested.txt").is_err());
        assert!(parse("source 'unterminated").is_err());
        assert!(parse("source --execute").is_err());
    }
    #[test]
    fn shell_substitutions_are_data_not_execution() {
        assert_eq!(
            words("search-code '$(touch /tmp/no)' ").unwrap()[1],
            "$(touch /tmp/no)"
        );
    }
}
