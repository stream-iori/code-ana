mod command_contract;
mod output_io;
mod pipeline;
mod watch_output;
use anyhow::{Context, Result, bail, ensure};
use clap::{Args, Parser, Subcommand};
use codeana_application::{Endpoint, default_home, private_home};
use codeana_contracts::{map, v1 as p};
use codeana_model::{Request, new_id};
use p::task_service_client::TaskServiceClient;
use std::os::unix::process::CommandExt;
use std::{
    path::{Path, PathBuf},
    time::Duration,
};
use tonic::{metadata::MetadataValue, transport::Channel};
#[derive(Parser)]
#[command(version, about = "Code analysis via a local Rust daemon")]
struct Cli {
    #[arg(long, global = true)]
    home: Option<PathBuf>,
    #[arg(long, global = true, default_value = ".")]
    project: PathBuf,
    #[command(subcommand)]
    command: Command,
    #[arg(skip)]
    project_explicit: bool,
}
#[derive(Subcommand)]
enum Command {
    JavaInputs {
        #[command(subcommand)]
        action: JavaInputsCommand,
    },
    Hello {
        #[command(flatten)]
        output: LocalOutputArgs,
        #[arg(default_value = "world")]
        name: String,
    },
    Daemon {
        #[command(flatten)]
        output: LocalOutputArgs,
        #[arg(long, default_value_t = 10, value_parser = clap::value_parser!(u64).range(0..=codeana_model::MAX_TASK_DEADLINE_SECONDS))]
        deadline_seconds: u64,
        #[arg(value_parser=["status","drain","stop","restart"])]
        action: String,
    },
    Task {
        #[command(flatten)]
        output: LocalOutputArgs,
        #[arg(value_parser=["status","cancel","watch"])]
        action: String,
        id: String,
        #[arg(long, default_value_t = 0)]
        after_seq: u64,
    },
    Index(IndexArgs),
    Doctor(QueryArgs),
    Resolve(QueryArgs),
    SearchCode(QueryArgs),
    SearchComments(QueryArgs),
    Structure(QueryArgs),
    #[command(
        about = "Inspect static call relations",
        long_about = "Inspect static call relations. recursion=direct marks equal, nonempty caller/callee method IDs; it does not prove runtime execution or termination. Inspect source for exit conditions. A missing marker does not exclude indirect recursion."
    )]
    Calls(QueryArgs),
    CallSites(QueryArgs),
    References(QueryArgs),
    Source(QueryArgs),
    Annotations(QueryArgs),
    TypeRelations(QueryArgs),
    CallableRelations(QueryArgs),
    RuntimeImplementations(QueryArgs),
    Dispatch(QueryArgs),
    Trace(QueryArgs),
    Overview(QueryArgs),
    SearchText(QueryArgs),
    Pipeline(QueryArgs),
    Snapshot(QueryArgs),
    SnapshotList(QueryArgs),
    SnapshotRemove(QueryArgs),
    Diff(QueryArgs),
    Report(ReportArgs),
    Gc(QueryArgs),
    Recover(QueryArgs),
}
#[derive(Subcommand)]
enum JavaInputsCommand {
    Export(JavaInputsExport),
}
#[derive(Args)]
struct JavaInputsExport {
    #[command(flatten)]
    response: LocalOutputArgs,
    #[arg(long)]
    output: PathBuf,
    #[arg(long, default_value = "mvn")]
    maven: String,
    #[arg(long)]
    settings: Option<PathBuf>,
    #[arg(long = "profile")]
    profiles: Vec<String>,
    #[arg(long = "module")]
    modules: Vec<String>,
    #[arg(long = "scope", value_parser = ["MAIN", "TEST"], default_values = ["MAIN", "TEST"])]
    scopes: Vec<String>,
    #[arg(long)]
    offline: bool,
    #[arg(long)]
    source_only: bool,
    #[arg(long, default_value_t = 300, value_parser = clap::value_parser!(u64).range(1..=3600))]
    timeout: u64,
    #[arg(long)]
    extension: Option<PathBuf>,
    #[arg(long)]
    java_home: Option<PathBuf>,
}
#[derive(Args)]
struct IndexArgs {
    #[command(flatten)]
    query: QueryArgs,
    #[arg(long)]
    prepare: bool,
}
#[derive(Args)]
struct ReportArgs {
    #[command(flatten)]
    query: QueryArgs,
}
#[derive(Args, Default)]
#[command(
    after_help = "Execution budgets: set max_work and max_payload_bytes in [resources.query]. Each field inherits project -> user -> default. Limited results retain completed evidence; --strict exits 3."
)]
struct QueryArgs {
    #[arg(long, value_parser = ["text", "json", "markdown"])]
    format: Option<String>,
    #[arg(long)]
    detail: bool,
    #[arg(skip)]
    supplied: Vec<String>,
    #[arg(long, default_value = "", value_parser = ["", "project", "modules", "packages", "dependencies"])]
    section: String,
    #[arg(long, default_value_t = 0)]
    package_depth: u32,
    #[arg(long, default_value = "")]
    signature: String,
    #[arg(long, default_value = "", conflicts_with = "package_prefix")]
    package: String,
    #[arg(long, default_value = "", conflicts_with = "package")]
    package_prefix: String,
    #[arg(long = "path")]
    paths: Vec<String>,
    #[arg(long = "kind", default_value = "")]
    entity_kind: String,
    #[arg(long, default_value = "")]
    annotation: String,
    #[arg(long)]
    meta: bool,
    #[arg(long, default_value = "", value_parser = ["", "yes", "unknown", "all"])]
    instantiability: String,
    #[arg(long)]
    transitive: bool,
    #[arg(long, default_value = "", value_parser = ["", "auto", "exact", "cha"])]
    algorithm: String,
    #[arg(long, default_value = "", value_parser = ["", "workspace-open", "workspace-closed", "classpath-open"])]
    analysis_world: String,
    #[arg(long, default_value = "")]
    visibility: String,
    #[arg(long)]
    synthetic: Option<bool>,
    #[arg(long)]
    regex: bool,
    #[arg(long)]
    ignore_case: bool,
    #[arg(long, default_value_t = 0)]
    context: u32,
    #[arg(long = "in", default_value = "names", value_parser = ["names", "comments", "names,comments"])]
    search_in: String,
    #[arg(default_value = "")]
    selector: String,
    #[arg(long, default_value = "")]
    revision: String,
    #[arg(long, default_value = "ALL")]
    scope: String,
    #[arg(long, default_value = "")]
    world: String,
    #[arg(long, default_value = "")]
    module: String,
    #[arg(long,value_parser=["in","out","both"])]
    direction: Option<String>,
    #[arg(long, default_value = "")]
    relation: String,
    #[arg(long)]
    unique: bool,
    #[arg(long)]
    full: bool,
    #[arg(long)]
    strict: bool,
    #[arg(long)]
    execute: bool,
    #[arg(long)]
    background: bool,
    #[arg(long, default_value = "")]
    base: String,
    #[arg(long, default_value = "")]
    target: String,
    #[arg(long, default_value = "")]
    cursor: String,
    #[arg(long, default_value = "")]
    name: String,
    #[arg(long, default_value_t = 100)]
    max_records: u32,
    #[arg(long, default_value_t = 32768, value_parser = clap::value_parser!(u64).range(2048..=8388608))]
    max_output_bytes: u64,
    #[arg(long, default_value_t = 1)]
    depth: u32,
    #[arg(long, default_value_t = 120, value_parser = clap::value_parser!(u64).range(0..=codeana_model::MAX_TASK_DEADLINE_SECONDS))]
    deadline_seconds: u64,
    #[arg(long)]
    source_version: Option<String>,
    #[arg(long)]
    toolchain: Option<String>,
    #[arg(long)]
    suggestions: Option<bool>,
    #[arg(long, default_value = "")]
    expected_revision: String,
    #[arg(long)]
    request_id: Option<String>,
    #[arg(long)]
    export: Option<PathBuf>,
}
#[derive(Args, Clone, Default)]
struct LocalOutputArgs {
    #[arg(long, value_parser = ["text", "json"])]
    format: Option<String>,
    #[arg(long)]
    detail: bool,
    #[arg(long)]
    export: Option<PathBuf>,
    #[arg(long, default_value_t = 32768, value_parser = clap::value_parser!(u64).range(2048..=8388608))]
    max_output_bytes: u64,
}
impl LocalOutputArgs {
    fn request(&self) -> Result<Request> {
        Ok(Request {
            delivery: codeana_model::DeliveryOptions {
                format: self.format.as_deref().map(str::parse).transpose()?,
                detail: Some(self.detail),
                ..Default::default()
            },
            max_output_bytes: self.max_output_bytes,
            ..Default::default()
        })
    }
}
fn local_response<T: serde::Serialize>(value: &T, output: &LocalOutputArgs) -> Result<i32> {
    let request = output.request()?;
    let mut result = codeana_model::TaskResult::success(codeana_model::Output {
        records: vec![codeana_model::Record::display(value)?],
        evidence: codeana_model::Evidence::complete(),
        ..Default::default()
    });
    let bytes = codeana_application::render_response(&request, &mut result)?;
    output_io::emit(&request, &result, &bytes, output.export.as_deref())
}
fn authenticated<T>(value: T, e: &Endpoint) -> Result<tonic::Request<T>> {
    let mut r = tonic::Request::new(value);
    r.metadata_mut()
        .insert("authorization", MetadataValue::try_from(&e.token)?);
    Ok(r)
}
async fn try_connect(home: &Path) -> Result<(TaskServiceClient<Channel>, Endpoint)> {
    let endpoint: Endpoint = serde_json::from_slice(&std::fs::read(home.join("endpoint.json"))?)?;
    ensure!(
        endpoint.address.starts_with("http://127.0.0.1:"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::InvalidLocalEndpoint, "")
    );
    let mut client = TaskServiceClient::connect(endpoint.address.clone())
        .await?
        .max_decoding_message_size(codeana_model::MAX_FRAME);
    let hello = client
        .handshake(authenticated(
            p::HandshakeRequest {
                protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
            },
            &endpoint,
        )?)
        .await?
        .into_inner();
    ensure!(
        hello.protocol == codeana_model::DAEMON_PROTOCOL_VERSION
            && hello.generation == endpoint.generation
            && hello.mock == endpoint.mock,
        codeana_model::DomainError::new(codeana_model::ErrorCode::StaleEndpoint, "")
    );
    Ok((client, endpoint))
}
async fn connect(home: &Path, start: bool) -> Result<(TaskServiceClient<Channel>, Endpoint)> {
    match try_connect(home).await {
        Ok(connection) => return Ok(connection),
        Err(error)
            if error.downcast_ref::<tonic::Status>().is_some_and(|status| {
                matches!(
                    status.code(),
                    tonic::Code::FailedPrecondition
                        | tonic::Code::Unauthenticated
                        | tonic::Code::PermissionDenied
                )
            }) =>
        {
            return Err(error);
        }
        Err(error)
            if error
                .downcast_ref::<codeana_model::DomainError>()
                .is_some_and(|error| {
                    matches!(
                        error.code,
                        codeana_model::ErrorCode::StaleEndpoint
                            | codeana_model::ErrorCode::InvalidLocalEndpoint
                    )
                }) =>
        {
            return Err(error);
        }
        Err(_) => {}
    }
    ensure!(
        start,
        codeana_model::DomainError::new(codeana_model::ErrorCode::DaemonUnavailable, "")
    );
    ensure!(
        !std::fs::read_to_string(home.join("mode")).is_ok_and(|s| s == "mock-v1"),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::MockDaemonStopped,
            "restart explicitly with --dev-mock-fixtures".to_string()
        )
    );
    let binary = std::env::current_exe()?.with_file_name("codeana-daemon");
    let log = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(home.join("daemon.log"))?;
    let mut child = std::process::Command::new(binary)
        .arg("--home")
        .arg(home)
        .process_group(0)
        .stdin(std::process::Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log)
        .spawn()
        .context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::DaemonNotInstalled,
            "",
        ))?;
    for _ in 0..100 {
        if let Ok(c) = try_connect(home).await {
            return Ok(c);
        }
        if let Some(status) = child.try_wait()?
            && !status.success()
        {
            // A concurrent CLI may have won the singleton lock.
            tokio::time::sleep(Duration::from_millis(100)).await;
            if let Ok(c) = try_connect(home).await {
                return Ok(c);
            }
            bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::DaemonStartFailed,
                format!("exit={status}; see {}/daemon.log", home.display())
            ));
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    bail!(codeana_model::DomainError::new(
        codeana_model::ErrorCode::DaemonStartTimeout,
        ""
    ))
}
struct WatchOptions<'a> {
    after_seq: u64,
    export: Option<&'a Path>,
    markdown: bool,
    requested: &'a Request,
    observing: bool,
}
async fn watch(
    client: &mut TaskServiceClient<Channel>,
    endpoint: &Endpoint,
    id: &str,
    options: WatchOptions<'_>,
) -> Result<i32> {
    watch_inner(client, endpoint, id, options)
        .await
        .with_context(|| format!("task={id}; output not confirmed"))
}
async fn watch_inner(
    client: &mut TaskServiceClient<Channel>,
    e: &Endpoint,
    id: &str,
    options: WatchOptions<'_>,
) -> Result<i32> {
    let WatchOptions {
        after_seq,
        export,
        markdown,
        requested,
        observing,
    } = options;
    let mut stream = client
        .watch_task(authenticated(
            p::WatchRequest {
                task_id: id.into(),
                after_seq,
            },
            e,
        )?)
        .await?
        .into_inner();
    let mut records = Vec::new();
    let mut payload_bytes = 0usize;
    let mut cancelled = false;
    let mut terminal = None;
    let mut last_seq = after_seq;
    let mut observation = watch_output::WatchOutput::new(requested, id, after_seq);
    loop {
        let next = tokio::select! {
            message = stream.message() => message.context(codeana_model::DomainError::new(codeana_model::ErrorCode::EventStreamInterrupted, format!("task={id}; last_seq={last_seq}")))?,
            _ = tokio::time::sleep(Duration::from_millis(100)), if export.is_none() => {
                if let Err(error) = output_io::ensure_open() {
                    if !observing { let _ = client.cancel_task(authenticated(p::TaskId { task_id: id.into() }, e)?).await; }
                    return Err(error);
                }
                continue;
            }
            _ = tokio::signal::ctrl_c(), if !cancelled => { cancelled = true; client.cancel_task(authenticated(p::TaskId { task_id: id.into() }, e)?).await?; continue; }
        };
        let Some(event) = next else {
            break;
        };
        ensure!(
            event.seq == last_seq.saturating_add(1),
            "EVENT_ORDER_INVALID: task={id}; after_seq={last_seq}"
        );
        let seq = event.seq;
        match event.event {
            Some(p::task_event::Event::Progress(progress)) => {
                if observing {
                    observation.progress(event.seq, &progress.phase)?;
                }
            }
            Some(p::task_event::Event::Record(record)) => {
                ensure!(
                    record.projection_version == codeana_model::PUBLIC_PROJECTION_VERSION,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::OutputFormatUnavailable,
                        "task projection changed"
                    )
                );
                payload_bytes = payload_bytes.saturating_add(record.record.len());
                ensure!(
                    payload_bytes <= codeana_model::MAX_RESULT && records.len() < 10000,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::ExportBudget,
                        "bounded task observation exceeded"
                    )
                );
                let record = codeana_model::Record::decode_current(&record.record)?;
                if observing {
                    observation.record(event.seq, record)?;
                } else {
                    records.push(record);
                }
            }
            Some(p::task_event::Event::Terminal(value)) => {
                terminal = Some(value);
                last_seq = seq;
                break;
            }
            None => bail!(codeana_model::DomainError::new(
                codeana_model::ErrorCode::EmptyTaskEvent,
                ""
            )),
        }
        last_seq = seq;
    }
    let terminal = terminal.context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::EventStreamInterrupted,
        format!("task={id}; last_seq={last_seq}; body not confirmed"),
    ))?;
    let mut result = codeana_contracts::task_result(&terminal)?;
    result.output.records = records;
    let mut request = requested.clone();
    let context = &result.output.delivery.context;
    if !observing {
        if context.operation != codeana_model::Operation::Unspecified {
            request.operation = context.operation;
        }
        request.delivery.format = Some(context.format);
        request.delivery.detail = Some(context.detail);
        request.max_output_bytes = context.max_output_bytes;
    } else {
        if context.operation != codeana_model::Operation::Unspecified {
            request.operation = context.operation;
        }
    }
    if observing && after_seq > 0 {
        result.output.delivery.state = codeana_model::DeliveryState::Partial;
    }
    if markdown && result.exit_code == 0 {
        let value = result
            .output
            .records
            .first()
            .context("REPORT_OUTPUT_UNAVAILABLE")?
            .wire()?;
        let bytes = codeana_application::report_markdown(value)?.into_bytes();
        return output_io::emit(&request, &result, &bytes, export)
            .with_context(|| response_context(id, &result));
    }
    let bytes = if observing {
        observation
            .finish(&mut result, last_seq)
            .with_context(|| response_context(id, &result))?
    } else {
        codeana_application::render_response(&request, &mut result)
            .with_context(|| response_context(id, &result))?
    };
    output_io::emit(&request, &result, &bytes, export)
        .with_context(|| response_context(id, &result))
}
fn response_context(id: &str, result: &codeana_model::TaskResult) -> String {
    format!(
        "task={id}; execution={}; publication={}; gate={}; response incomplete",
        result.execution, result.publication, result.gate
    )
}
#[tokio::main]
async fn main() {
    match run().await {
        Ok(code) => std::process::exit(code),
        Err(e) => {
            eprintln!("{e:#}");
            std::process::exit(1);
        }
    }
}
async fn run() -> Result<i32> {
    let mut cli = match command_contract::parse() {
        Ok(cli) => cli,
        Err(error) => {
            if error.kind() == clap::error::ErrorKind::UnknownArgument
                && ["--context-before", "--context-after"]
                    .iter()
                    .any(|flag| error.to_string().contains(flag))
            {
                eprintln!(
                    "Removed comment context options: delete --context-before/--context-after; use source with the declaration symbol."
                );
            }
            error.exit()
        }
    };
    output_io::ensure_open()?;
    {
        let export = match &cli.command {
            Command::Hello { output, .. }
            | Command::Daemon { output, .. }
            | Command::Task { output, .. } => output.export.as_deref(),
            Command::JavaInputs {
                action: JavaInputsCommand::Export(a),
            } => a.response.export.as_deref(),
            _ => None,
        };
        output_io::preflight(export)?;
    }
    if let Some(query) = command_contract::query_mut(&mut cli.command) {
        output_io::preflight(query.export.as_deref())?;
    }
    let markdown = matches!(&cli.command, Command::Report(args) if args.query.format.as_deref() == Some("markdown"));
    if let Command::Report(args) = &cli.command {
        ensure!(
            !markdown || args.query.base.is_empty(),
            "--format markdown does not support --base"
        );
    }
    if let Command::Index(args) = &cli.command
        && args.prepare
    {
        use std::sync::{
            Arc,
            atomic::{AtomicBool, Ordering},
        };
        let project = cli.project.clone();
        let cancel = Arc::new(AtomicBool::new(false));
        let task_cancel = cancel.clone();
        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
        let mut task = tokio::task::spawn_blocking(move || {
            codeana_application::inputs::prepare(&project, &task_cancel)
        });
        let prepared = tokio::select! {
            result = &mut task => result?,
            _ = tokio::signal::ctrl_c() => { cancel.store(true, Ordering::Release); task.await? },
            _ = term.recv() => { cancel.store(true, Ordering::Release); task.await? },
        };
        if cancel.load(Ordering::Acquire) {
            if let Err(error) = prepared {
                eprintln!("{error:#}");
            }
            return Ok(130);
        }
        let prepared = prepared?;
        if args.query.detail {
            eprintln!(
                "input preparation: {}; units={}; report={}",
                prepared.status,
                prepared.units.len(),
                prepared.report
            );
        }
    }
    if let Command::JavaInputs {
        action: JavaInputsCommand::Export(args),
    } = &cli.command
    {
        use codeana_application::maven_inputs::{ExportOptions, export};
        use std::sync::{
            Arc,
            atomic::{AtomicBool, Ordering},
        };
        let options = ExportOptions {
            project: cli.project.clone(),
            manifest: None,
            output: args.output.clone(),
            maven: args.maven.clone(),
            settings: args.settings.clone(),
            profiles: args.profiles.clone(),
            modules: args.modules.clone(),
            scopes: args.scopes.clone(),
            offline: args.offline,
            source_only: args.source_only,
            timeout: args.timeout,
            extension: args.extension.clone(),
            java_home: args.java_home.clone(),
        };
        let cancel = Arc::new(AtomicBool::new(false));
        let task_cancel = cancel.clone();
        let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
        let mut task = tokio::task::spawn_blocking(move || export(&options, &task_cancel));
        let result = tokio::select! {
            result = &mut task => result??,
            _ = tokio::signal::ctrl_c() => { cancel.store(true, Ordering::Release); task.await?? },
            _ = term.recv() => { cancel.store(true, Ordering::Release); task.await?? },
        };
        let value = serde_json::json!({"manifest":args.output,"modules":result.modules.len(), "analysis_mode":if result.resolved_classpath { "semantic" } else { "source_only" }, "classpath_status":if result.resolved_classpath { "available" } else { "unavailable" }, "dependency_mode":result.dependency_mode, "dependency_failure":result.dependency_failure});
        return local_response(&value, &args.response);
    }

    if let Command::Hello { name, output } = &cli.command
        && let Ok(port) = std::env::var("CODEANA_PORT")
    {
        let port: u16 = port.parse()?;
        let mut client = p::hello_service_client::HelloServiceClient::connect(format!(
            "http://127.0.0.1:{port}"
        ))
        .await?;
        let response = client
            .say_hello(p::HelloRequest { name: name.clone() })
            .await?
            .into_inner();
        return local_response(&serde_json::json!({"hello":response.message}), output);
    }

    let home = private_home(&cli.home.unwrap_or(default_home()?))?;
    let (operation, a) = match cli.command {
        Command::JavaInputs { .. } => {
            unreachable!("explicit Maven export returned before daemon connection")
        }
        Command::Hello { name, output } => {
            let (_, e) = connect(&home, true).await?;
            let mut c = p::hello_service_client::HelloServiceClient::connect(e.address).await?;
            let response = c.say_hello(p::HelloRequest { name }).await?.into_inner();
            return local_response(&serde_json::json!({"hello":response.message}), &output);
        }
        Command::Daemon {
            action,
            output,
            deadline_seconds,
        } => {
            let (mut client, e) = connect(&home, false).await?;
            if action == "restart" {
                ensure!(
                    !e.mock,
                    codeana_model::DomainError::new(
                        codeana_model::ErrorCode::MockRestartRequiresExplicitFixture,
                        ""
                    )
                );
            }
            let manage_action = if action == "restart" {
                "drain"
            } else {
                &action
            };
            let response = client
                .manage(authenticated(
                    p::ManageRequest {
                        action: manage_action.into(),
                    },
                    &e,
                )?)
                .await?
                .into_inner();
            if action == "stop" || action == "restart" {
                ensure!(
                    e.pid != 0 && !e.process_started.is_empty(),
                    "PROCESS_STATE_UNAVAILABLE: service identity not recorded"
                );
                let deadline = std::time::Instant::now() + Duration::from_secs(deadline_seconds);
                loop {
                    let exited = match codeana_application::process_identity(e.pid)? {
                        None => true,
                        Some((started, zombie)) => zombie || started != e.process_started,
                    };
                    if exited {
                        break;
                    }
                    if std::time::Instant::now() >= deadline {
                        let request = output.request()?;
                        let mut result = codeana_model::TaskResult::failure(
                            "TIMEOUT",
                            "Original service exit was not confirmed",
                            124,
                        );
                        result.publication = codeana_model::PublicationState::NotRequested;
                        result.output.records = vec![codeana_model::Record::display(
                            &serde_json::json!({"daemon":response.state,"active_tasks":response.active_tasks}),
                        )?];
                        let bytes = codeana_application::render_response(&request, &mut result)?;
                        return output_io::emit(
                            &request,
                            &result,
                            &bytes,
                            output.export.as_deref(),
                        );
                    }
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
                if action == "restart" {
                    let (_, next) = connect(&home, true).await?;
                    ensure!(
                        next.generation != e.generation,
                        "STALE_ENDPOINT: restart did not create a new instance"
                    );
                }
                return local_response(
                    &serde_json::json!({"daemon":if action == "stop" {"stopped"} else {"restarted"}}),
                    &output,
                );
            }
            return local_response(&response, &output);
        }
        Command::Task {
            action,
            id,
            after_seq,
            output,
        } => {
            let (mut client, e) = connect(&home, false).await?;
            if action == "watch" {
                let mut request = output.request()?;
                let status = client
                    .get_task(authenticated(
                        p::TaskId {
                            task_id: id.clone(),
                        },
                        &e,
                    )?)
                    .await?
                    .into_inner();
                request.operation = status.operation.parse()?;
                return watch(
                    &mut client,
                    &e,
                    &id,
                    WatchOptions {
                        after_seq,
                        export: output.export.as_deref(),
                        markdown: false,
                        requested: &request,
                        observing: true,
                    },
                )
                .await;
            }
            let r = if action == "cancel" {
                client
                    .cancel_task(authenticated(p::TaskId { task_id: id }, &e)?)
                    .await?
            } else {
                client
                    .get_task(authenticated(p::TaskId { task_id: id }, &e)?)
                    .await?
            };
            if output.format.as_deref().unwrap_or("text") == "text" && !output.detail {
                let status = r.get_ref();
                let mut view = serde_json::json!({"task":status.task_id,"state":status.state,"last-seq":status.last_seq});
                if action == "cancel" {
                    view["cancel"] = "requested".into();
                }
                if let Some(terminal) = &status.terminal {
                    let result = codeana_contracts::task_result(terminal)?;
                    view["publication"] = serde_json::to_value(result.publication)?;
                    if result.assessment != codeana_model::Assessment::Complete {
                        view["assessment"] = serde_json::to_value(result.assessment)?;
                    }
                    if matches!(
                        result.gate,
                        codeana_model::GateState::Rejected | codeana_model::GateState::Unknown
                    ) {
                        view["gate"] = serde_json::to_value(result.gate)?;
                    }

                    if result.exit_code != 0 {
                        view["task-error"] = serde_json::to_value(result.code)?;
                        view["reason"] = result.message.into();
                    }
                    if matches!(result.report.as_str(), "pending" | "failed" | "unknown") {
                        view["report"] = result.report.to_string().into();
                    }
                }
                return local_response(&view, &output);
            }
            let mut value = serde_json::to_value(r.get_ref())?;
            if action == "cancel" {
                value
                    .as_object_mut()
                    .expect("task status")
                    .insert("cancel".into(), "requested".into());
            }
            return local_response(&value, &output);
        }
        Command::Index(a) => ("index", a.query),
        Command::Doctor(a) => ("doctor", a),
        Command::Resolve(a) => ("resolve", a),
        Command::SearchCode(a) => ("search-code", a),
        Command::SearchComments(a) => ("search-comments", a),
        Command::Structure(a) => ("structure", a),
        Command::Calls(a) => ("calls", a),
        Command::CallSites(a) => ("call-sites", a),
        Command::References(a) => ("references", a),
        Command::Source(a) => ("source", a),
        Command::Annotations(a) => ("annotations", a),
        Command::TypeRelations(a) => ("type-relations", a),
        Command::CallableRelations(a) => ("callable-relations", a),
        Command::RuntimeImplementations(a) => ("runtime-implementations", a),
        Command::Dispatch(a) => ("dispatch", a),
        Command::Trace(a) => ("trace", a),
        Command::Overview(a) => ("overview", a),
        Command::SearchText(a) => ("search-text", a),
        Command::Pipeline(a) => ("pipeline", a),
        Command::Snapshot(a) => ("snapshot", a),
        Command::SnapshotList(a) => ("snapshot-list", a),
        Command::SnapshotRemove(a) => ("snapshot-remove", a),
        Command::Diff(a) => ("diff", a),
        Command::Report(a) => ("report", a.query),
        Command::Gc(a) => ("gc", a),
        Command::Recover(a) => ("recover", a),
    };
    let stages = if operation == "pipeline" {
        pipeline::read(Path::new(&a.selector))?
    } else {
        vec![]
    };
    let reference_options = (operation == "references"
        && (a.direction.is_some() || !a.relation.is_empty()))
        || stages.iter().any(|stage| stage.operation == "references");
    let mut req = Request {
        delivery: codeana_model::DeliveryOptions {
            format: a
                .format
                .as_deref()
                .filter(|s| *s != "markdown")
                .map(str::parse)
                .transpose()?,
            detail: a.supplied.iter().any(|s| s == "detail").then_some(a.detail),
            supplied: a.supplied.clone(),
            project_explicit: cli.project_explicit,
        },
        overview_section: if operation == "overview" {
            a.section.clone()
        } else {
            String::new()
        },
        overview_depth: a.package_depth,
        signature: a.signature,
        package: a.package,
        package_prefix: a.package_prefix,
        paths: a.paths,
        entity_kind: if operation == "snapshot" {
            String::new()
        } else {
            a.entity_kind.clone()
        },
        annotation: a.annotation,
        visibility: a.visibility,
        synthetic: a.synthetic,
        text_regex: a.regex,
        text_ignore_case: a.ignore_case,
        text_context: a.context,
        search_in: a.search_in.parse()?,
        request_id: a.request_id.unwrap_or_else(|| new_id("request")).into(),
        project: codeana_application::project_path(&cli.project)?
            .to_str()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::NonUtf8Project,
                "",
            ))?
            .into(),
        operation: operation.parse()?,
        selector: if operation == "pipeline" {
            String::new()
        } else {
            a.selector
        },
        revision: a.revision,
        scope: a.scope,
        world: a.world,
        module: a.module,
        direction: a
            .direction
            .unwrap_or_else(|| {
                if operation == "references" {
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
        full: a.full,
        strict: a.strict,
        execute: a.execute,
        background: a.background,
        base: a.base,
        target: a.target,
        cursor: a.cursor,
        name: match operation {
            "diff" => a.section,
            "snapshot" => a.entity_kind,
            _ => String::new(),
        },
        stages,
        max_records: a.max_records,
        max_output_bytes: a.max_output_bytes,
        depth: a.depth,
        deadline_seconds: a.deadline_seconds,
        source_version: a.source_version,
        toolchain: a.toolchain,
        suggestions: a.suggestions,
        expected_revision: a.expected_revision,
    };
    if !req.cursor.is_empty() {
        req = Request {
            operation: req.operation,
            request_id: req.request_id,
            project: req.project,
            cursor: req.cursor,
            max_records: if a.supplied.iter().any(|s| s == "max_records") {
                req.max_records
            } else {
                0
            },
            max_output_bytes: if a.supplied.iter().any(|s| s == "max_output_bytes") {
                req.max_output_bytes
            } else {
                0
            },
            deadline_seconds: req.deadline_seconds,
            delivery: req.delivery,
            ..Default::default()
        };
    }
    if req.cursor.is_empty()
        && let Err(error) = codeana_application::preflight_request(&req)
    {
        eprintln!("{error:#}");
        return Ok(2);
    }
    let (mut client, e) = connect(&home, true).await?;
    let budgeted_query = codeana_query_operation(&req.operation);
    let runtime_candidates = req.operation == "runtime-implementations"
        || req
            .stages
            .iter()
            .any(|stage| stage.operation == "runtime-implementations");
    let transitive_relations = req.transitive || req.stages.iter().any(|stage| stage.transitive);
    let dispatch_options =
        req.operation == "dispatch" || req.stages.iter().any(|s| s.operation == "dispatch");
    let analysis_world =
        !req.analysis_world.is_empty() || req.stages.iter().any(|s| !s.analysis_world.is_empty());
    if budgeted_query
        || runtime_candidates
        || transitive_relations
        || dispatch_options
        || analysis_world
    {
        let hello = client
            .handshake(authenticated(
                p::HandshakeRequest {
                    protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
                },
                &e,
            )?)
            .await?
            .into_inner();
        for (required, capability) in [
            (budgeted_query, "query-resources-v1"),
            (
                req.operation == "calls"
                    || req.operation == "pipeline"
                    || req.operation == "diff"
                    || req.operation == "trace",
                "call-recursion-v1",
            ),
            (runtime_candidates, "runtime-implementations-v1"),
            (transitive_relations, "relation-transitive-v1"),
            (dispatch_options, "java-dispatch-v1"),
            (analysis_world, "java-analysis-world-v1"),
        ] {
            ensure!(
                !required || hello.capabilities.iter().any(|item| item == capability),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::DaemonQueryCapabilityUnsupported,
                    capability.to_string()
                )
            );
        }
    }
    if req.meta || req.stages.iter().any(|stage| stage.meta) {
        let hello = client
            .handshake(authenticated(
                p::HandshakeRequest {
                    protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
                },
                &e,
            )?)
            .await?
            .into_inner();
        ensure!(
            hello
                .capabilities
                .iter()
                .any(|capability| capability == "annotations-meta-v1"),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::DaemonAnnotationsMetaUnsupported,
                ""
            )
        );
    }
    if reference_options {
        let hello = client
            .handshake(authenticated(
                p::HandshakeRequest {
                    protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
                },
                &e,
            )?)
            .await?
            .into_inner();
        ensure!(
            hello
                .capabilities
                .iter()
                .any(|capability| capability == "reference-options-v1"),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::DaemonReferenceOptionsUnsupported,
                ""
            )
        );
    }
    if !req.overview_section.is_empty() || req.overview_depth > 0 {
        let hello = client
            .handshake(authenticated(
                p::HandshakeRequest {
                    protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
                },
                &e,
            )?)
            .await?
            .into_inner();
        ensure!(
            hello
                .capabilities
                .iter()
                .any(|c| c == "overview-sections-v1"),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::DaemonOverviewSectionsUnsupported,
                ""
            )
        );
    }
    if req.operation == codeana_model::Operation::SearchComments
        || req.search_in != codeana_model::SearchIn::Names
        || req
            .stages
            .iter()
            .any(|s| s.operation == codeana_model::Operation::SearchComments)
    {
        let hello = client
            .handshake(authenticated(
                p::HandshakeRequest {
                    protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
                },
                &e,
            )?)
            .await?
            .into_inner();
        ensure!(
            hello.capabilities.iter().any(|c| c == "comment-search-v2"),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::UnsupportedCapability,
                "daemon comment-search-v2"
            )
        );
    }
    if req.text_regex || req.text_ignore_case || req.text_context > 0 {
        let hello = client
            .handshake(authenticated(
                p::HandshakeRequest {
                    protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
                },
                &e,
            )?)
            .await?
            .into_inner();
        ensure!(
            hello
                .capabilities
                .iter()
                .any(|c| c == "text-search-options-v1"),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::DaemonTextOptionsUnsupported,
                ""
            )
        );
    }
    let request: p::TaskRequest = map(&req)?;
    let accepted = client
        .submit_task(authenticated(request, &e)?)
        .await?
        .into_inner();
    if req.background {
        let output = LocalOutputArgs {
            format: a.format.clone(),
            detail: a.detail,
            export: None,
            max_output_bytes: a.max_output_bytes,
        };
        return local_response(
            &serde_json::json!({"task":accepted.task_id,"state":"accepted"}),
            &output,
        );
    }
    watch(
        &mut client,
        &e,
        &accepted.task_id,
        WatchOptions {
            after_seq: 0,
            export: a.export.as_deref(),
            markdown,
            requested: &req,
            observing: false,
        },
    )
    .await
}

fn codeana_query_operation(operation: &str) -> bool {
    matches!(
        operation,
        "resolve"
            | "search-code"
            | "search-comments"
            | "structure"
            | "calls"
            | "call-sites"
            | "references"
            | "source"
            | "annotations"
            | "type-relations"
            | "callable-relations"
            | "runtime-implementations"
            | "dispatch"
            | "trace"
            | "overview"
            | "search-text"
            | "pipeline"
            | "diff"
    )
}
