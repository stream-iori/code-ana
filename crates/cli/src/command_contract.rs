//! The CLI view of domain input capabilities. Unsupported fields are hidden
//! from help and rejected locally before daemon discovery or task submission.
use super::{Cli, Command, QueryArgs};
use clap::{ArgMatches, CommandFactory, FromArgMatches, parser::ValueSource};

pub fn fields(name: &str) -> Option<Vec<&'static str>> {
    codeana_application::command_input_fields(name.parse().ok()?)
}

pub fn command() -> clap::Command {
    let mut root = Cli::command().mut_arg("project", |a| {
        a.help("Project commands only; task/daemon/recover use the current home")
    });
    let names: Vec<_> = root
        .get_subcommands()
        .map(|s| s.get_name().to_owned())
        .collect();
    for name in names {
        root = root.mut_subcommand(&name, |sub| sub.about(description(&name)));
        let Some(allowed) = fields(&name) else {
            continue;
        };
        root = root.mut_subcommand(&name, |mut sub| {
            let ids: Vec<_> = sub
                .get_arguments()
                .map(|a| a.get_id().to_string())
                .collect();
            for id in ids {
                if !allowed.contains(&id.as_str()) && id != "help" {
                    sub = sub.mut_arg(id, |a| a.hide(true));
                }
            }
            if allowed.contains(&"direction") {
                sub = sub.mut_arg("direction", |a| {
                    if name == "overview" {
                        a
                    } else {
                        a.value_parser(["in", "out"])
                    }
                });
            }
            if name == "diff" {
                sub = sub.mut_arg("depth", |arg| arg.help("Incoming-call depth for impact only"))
                    .after_help("Impact follows incoming calls from changed relation endpoints; it is not a complete source/data-flow impact analysis.");
                sub = sub.mut_arg("section", |a| {
                    a.value_parser(["files", "declarations", "relations", "impact"])
                        .default_value("files")
                });
            }
            if name == "snapshot" {
                sub = sub.mut_arg("entity_kind", |a| {
                    a.value_parser(["files", "analysis"])
                        .default_value("files")
                        .help("Snapshot content")
                });
            }
            if allowed.contains(&"format") && name != "report" {
                sub = sub.mut_arg("format", |a| a.value_parser(["text", "json"]));
            }
            sub
        });
    }
    root
}

fn description(name: &str) -> &'static str {
    match name {
        "hello" => "Check local service connectivity",
        "daemon" => {
            "Manage this home's service and all its tasks; stop/restart wait up to --deadline-seconds (10s)"
        }
        "task" => "Observe or request cancellation by task ID; --after-seq applies only to watch",
        "java-inputs" => {
            "Explicitly export prepared Maven inputs; --output is the input artifact, --export the receipt"
        }
        "index" => {
            "Generate and atomically publish this project's semantic index; languages must be enabled"
        }
        "doctor" => "Inspect project inputs, installed capabilities and readiness without indexing",
        "resolve" => {
            "Locate exact entities by public ID or canonical selector; multiple matches remain explicit"
        }
        "search-code" => {
            "Find declarations/configuration entities in a published index (names and/or comments)"
        }
        "search-comments" => "Find captured comment text with source ranges and declaration links",
        "structure" => "Inspect declarations contained by an entity",
        "calls" => "Inspect static caller/callee relations, preserving all call sites",
        "call-sites" => {
            "Locate individual call occurrences; equal targets do not merge occurrences"
        }
        "references" => "Inspect typed references and their actual source occurrences",
        "source" => "Read exact captured source; current queries verify workspace hashes",
        "annotations" => "Inspect Java annotation occurrences and optional meta-annotation paths",
        "type-relations" => {
            "Inspect inheritance/implementation relations and optional transitive closure"
        }
        "callable-relations" => "Inspect method override/implementation relations",
        "runtime-implementations" => {
            "Inspect Java implementation candidates with instantiability evidence"
        }
        "dispatch" => "Inspect binding/dispatch candidates with proof and world assumptions",
        "trace" => "Find a bounded static call path from selector to --target",
        "overview" => {
            "Summarize project/module/package/dependency structure from a published index"
        }
        "search-text" => "Search workspace or captured text without requiring a semantic index",
        "pipeline" => {
            "Run a text file of typed query stages; one stage per line, --from selects an endpoint"
        }
        "snapshot" => {
            "Save named files or analysis state; --kind files requires no language adapter"
        }
        "snapshot-list" => "List this project's saved snapshots",
        "snapshot-remove" => "Remove one named snapshot while retaining referenced assets",
        "diff" => {
            "Compare frozen --base and --target; --section selects files/declarations/relations/impact"
        }
        "report" => "Read a stored report; Markdown requires --format markdown --export NEWPATH",
        "gc" => "Preview unreferenced project assets; --execute performs collection",
        "recover" => {
            "Preview this home's storage remnants; --execute repairs them when tasks are idle"
        }
        _ => "",
    }
}

pub fn parse() -> Result<Cli, clap::Error> {
    parse_from(std::env::args_os())
}

pub fn parse_from<I, T>(args: I) -> Result<Cli, clap::Error>
where
    I: IntoIterator<Item = T>,
    T: Into<std::ffi::OsString> + Clone,
{
    let matches = command().try_get_matches_from(args)?;
    validate(&matches)?;
    let mut cli = Cli::from_arg_matches(&matches)?;
    if let Some((_, sub)) = matches.subcommand()
        && let Some(query) = query_mut(&mut cli.command)
    {
        query.supplied = explicit(sub);
    }
    cli.project_explicit = matches.value_source("project") == Some(ValueSource::CommandLine);
    Ok(cli)
}

fn invalid(message: impl Into<String>) -> clap::Error {
    clap::Error::raw(
        clap::error::ErrorKind::ArgumentConflict,
        format!("INVALID_ARGUMENT: {}", message.into()),
    )
}

pub fn explicit(matches: &ArgMatches) -> Vec<String> {
    matches
        .ids()
        .filter(|id| {
            id.as_str().starts_with(|c: char| c.is_ascii_lowercase())
                && matches.value_source(id.as_str()) == Some(ValueSource::CommandLine)
        })
        .map(ToString::to_string)
        .collect()
}

fn validate(matches: &ArgMatches) -> Result<(), clap::Error> {
    let Some((name, sub)) = matches.subcommand() else {
        return Ok(());
    };
    if ["daemon", "task", "hello", "recover"].contains(&name)
        && matches.value_source("project") == Some(ValueSource::CommandLine)
    {
        return Err(invalid(format!(
            "{name} acts on the current home; --project does not scope this command"
        )));
    }
    if sub
        .try_get_one::<u64>("max_output_bytes")
        .ok()
        .flatten()
        .is_some_and(|n| *n < 2048 || *n > 8388608)
    {
        return Err(invalid(
            "--max-output-bytes must be between 2048 and 8388608",
        ));
    }
    if name == "task"
        && sub
            .get_one::<String>("action")
            .is_some_and(|s| s != "watch")
        && explicit(sub).iter().any(|s| s == "after_seq")
    {
        return Err(invalid("--after-seq requires task watch"));
    }
    if name == "daemon"
        && sub
            .get_one::<String>("action")
            .is_some_and(|s| s != "stop" && s != "restart")
        && explicit(sub).iter().any(|s| s == "deadline_seconds")
    {
        return Err(invalid(
            "--deadline-seconds requires daemon stop or restart",
        ));
    }
    let Some(allowed) = fields(name) else {
        return Ok(());
    };
    for field in explicit(sub) {
        if !allowed.contains(&field.as_str()) && !matches!(field.as_str(), "home" | "project") {
            return Err(invalid(format!(
                "{name} does not accept --{}",
                field.replace('_', "-")
            )));
        }
    }
    let cursor = sub
        .try_get_one::<String>("cursor")
        .ok()
        .flatten()
        .is_some_and(|s| !s.is_empty());
    if !cursor {
        let selector = sub
            .get_one::<String>("selector")
            .is_some_and(|s| !s.is_empty());
        if [
            "resolve",
            "structure",
            "calls",
            "call-sites",
            "references",
            "source",
            "annotations",
            "type-relations",
            "callable-relations",
            "runtime-implementations",
            "dispatch",
            "trace",
            "pipeline",
            "snapshot",
            "snapshot-remove",
            "report",
        ]
        .contains(&name)
            && !selector
        {
            return Err(invalid(format!(
                "{name} requires a selector (or --cursor where supported)"
            )));
        }
        if name == "trace" && sub.get_one::<String>("target").is_none_or(String::is_empty) {
            return Err(invalid("trace requires --target"));
        }
        if name == "diff"
            && ["base", "target"]
                .iter()
                .any(|key| sub.get_one::<String>(key).is_none_or(String::is_empty))
        {
            return Err(invalid("diff requires --base and --target"));
        }
    }
    let background = *sub.get_one::<bool>("background").unwrap_or(&false);
    let export = sub.get_one::<std::path::PathBuf>("export").is_some();
    if background && (export || cursor) {
        return Err(invalid(
            "--background cannot be combined with --export or --cursor",
        ));
    }
    if cursor {
        for id in explicit(sub) {
            if ![
                "cursor",
                "max_records",
                "max_output_bytes",
                "format",
                "detail",
                "export",
                "deadline_seconds",
                "request_id",
                "project",
                "home",
            ]
            .contains(&id.as_str())
            {
                return Err(invalid(format!(
                    "--cursor restores the original request; omit {id}"
                )));
            }
        }
    }
    if name == "report"
        && sub
            .get_one::<String>("format")
            .is_some_and(|s| s == "markdown")
        && !export
    {
        return Err(invalid("report --format markdown requires --export"));
    }
    Ok(())
}

pub fn query_mut(command: &mut Command) -> Option<&mut QueryArgs> {
    match command {
        Command::Index(a) => Some(&mut a.query),
        Command::Report(a) => Some(&mut a.query),
        Command::Doctor(a)
        | Command::Resolve(a)
        | Command::SearchCode(a)
        | Command::SearchComments(a)
        | Command::Structure(a)
        | Command::Calls(a)
        | Command::CallSites(a)
        | Command::References(a)
        | Command::Source(a)
        | Command::Annotations(a)
        | Command::TypeRelations(a)
        | Command::CallableRelations(a)
        | Command::RuntimeImplementations(a)
        | Command::Dispatch(a)
        | Command::Trace(a)
        | Command::Overview(a)
        | Command::SearchText(a)
        | Command::Pipeline(a)
        | Command::Snapshot(a)
        | Command::SnapshotList(a)
        | Command::SnapshotRemove(a)
        | Command::Diff(a)
        | Command::Gc(a)
        | Command::Recover(a) => Some(a),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn calls_help_and_parser_reject_index_options() {
        let help = parse_from(["codeana-cli", "calls", "--help"])
            .err()
            .unwrap()
            .to_string();
        assert!(help.contains("--direction"));
        assert!(!help.contains("--execute"));
        assert!(!help.contains("--full"));
        assert!(parse_from(["codeana-cli", "calls", "foo", "--full"]).is_err());
        assert!(parse_from(["codeana-cli", "calls", "foo", "--direction", "both"]).is_err());
    }
    #[test]
    fn local_management_and_export_budgets_are_checked_before_effects() {
        assert!(
            parse_from([
                "codeana-cli",
                "daemon",
                "stop",
                "--deadline-seconds",
                "18446744073709551615"
            ])
            .is_err()
        );
        assert!(
            parse_from([
                "codeana-cli",
                "java-inputs",
                "export",
                "--output",
                "new.json",
                "--max-output-bytes",
                "1"
            ])
            .is_err()
        );
        assert!(
            parse_from([
                "codeana-cli",
                "pipeline",
                "stages.txt",
                "--regex",
                "--target",
                "end"
            ])
            .is_ok()
        );
    }
    #[test]
    fn renamed_modes_and_cursor_contract() {
        assert!(
            parse_from([
                "codeana-cli",
                "diff",
                "--section",
                "impact",
                "--base",
                "a",
                "--target",
                "b"
            ])
            .is_ok()
        );
        assert!(parse_from(["codeana-cli", "snapshot", "before", "--kind", "files"]).is_ok());
        assert!(parse_from(["codeana-cli", "diff", "--name", "files"]).is_err());
        assert!(parse_from(["codeana-cli", "search-code", "--cursor", "page:1"]).is_ok());
        assert!(parse_from(["codeana-cli", "search-code", "foo", "--cursor", "page:1"]).is_err());
    }
}
