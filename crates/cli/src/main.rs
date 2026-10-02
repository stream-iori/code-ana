use anyhow::{Context, Result, bail, ensure};
use clap::{Args, Parser, Subcommand};
use codeana_application::{Endpoint, default_home, private_home};
use codeana_contracts::{convert, v1 as p};
use codeana_model::{Request, Stage, new_id};
use p::task_service_client::TaskServiceClient;
use std::os::unix::process::CommandExt;
use std::{
    io::Write,
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
}
#[derive(Subcommand)]
enum Command {
    JavaInputs {
        #[command(subcommand)]
        action: JavaInputsCommand,
    },
    Hello {
        #[arg(default_value = "world")]
        name: String,
    },
    Daemon {
        #[arg(value_parser=["status","drain","stop","restart"])]
        action: String,
    },
    Task {
        #[arg(value_parser=["status","cancel","watch"])]
        action: String,
        id: String,
        #[arg(long, default_value_t = 0)]
        after_seq: u64,
    },
    Index(QueryArgs),
    Doctor(QueryArgs),
    Resolve(QueryArgs),
    SearchCode(QueryArgs),
    Structure(QueryArgs),
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
    Report(QueryArgs),
    Annotate(QueryArgs),
    Gc(QueryArgs),
    Recover(QueryArgs),
}
#[derive(Subcommand)]
enum JavaInputsCommand {
    Export(JavaInputsExport),
}
#[derive(Args)]
struct JavaInputsExport {
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
    #[arg(long, default_value_t = 300)]
    timeout: u64,
    #[arg(long)]
    extension: Option<PathBuf>,
    #[arg(long)]
    java_home: Option<PathBuf>,
}
#[derive(Args, Default)]
struct QueryArgs {
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
    #[arg(long,default_value="out",value_parser=["in","out"])]
    direction: String,
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
    #[arg(long, default_value = "")]
    text: String,
    #[arg(long, default_value_t = 100)]
    max_records: u32,
    #[arg(long, default_value_t = 32768)]
    max_output_bytes: u64,
    #[arg(long, default_value_t = 1)]
    depth: u32,
    #[arg(long, default_value_t = 120)]
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
        "INVALID_LOCAL_ENDPOINT"
    );
    let mut client = TaskServiceClient::connect(endpoint.address.clone()).await?;
    let hello = client
        .handshake(authenticated(
            p::HandshakeRequest { protocol: 1 },
            &endpoint,
        )?)
        .await?
        .into_inner();
    ensure!(
        hello.generation == endpoint.generation && hello.mock == endpoint.mock,
        "STALE_ENDPOINT"
    );
    Ok((client, endpoint))
}
async fn connect(home: &Path, start: bool) -> Result<(TaskServiceClient<Channel>, Endpoint)> {
    if let Ok(c) = try_connect(home).await {
        return Ok(c);
    }
    ensure!(start, "DAEMON_UNAVAILABLE");
    ensure!(
        !std::fs::read_to_string(home.join("mode")).is_ok_and(|s| s == "mock-v1"),
        "MOCK_DAEMON_STOPPED: restart explicitly with --dev-mock-fixtures"
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
        .context("DAEMON_NOT_INSTALLED")?;
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
            bail!(
                "DAEMON_START_FAILED: exit={status}; see {}/daemon.log",
                home.display()
            );
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    bail!("DAEMON_START_TIMEOUT")
}
async fn watch(
    client: &mut TaskServiceClient<Channel>,
    e: &Endpoint,
    id: &str,
    after_seq: u64,
    export: Option<&Path>,
) -> Result<i32> {
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
    let mut saved = Vec::new();
    let mut cancelled = false;
    let mut terminal = None;
    loop {
        let next = tokio::select! {m=stream.message()=>m?,_=tokio::signal::ctrl_c(),if !cancelled=>{cancelled=true;client.cancel_task(authenticated(p::TaskId{task_id:id.into()},e)?).await?;continue;}};
        let Some(event) = next else {
            break;
        };
        match event.event {
            Some(p::task_event::Event::Progress(progress)) => {
                eprintln!("{}: {}", id, progress.phase)
            }
            Some(p::task_event::Event::Output(chunk)) => {
                if export.is_some() {
                    ensure!(
                        saved.len() + chunk.data.len() <= 8 * 1024 * 1024,
                        "EXPORT_BUDGET"
                    );
                    saved.extend(chunk.data);
                } else {
                    std::io::stdout().write_all(&chunk.data)?;
                }
            }
            Some(p::task_event::Event::Terminal(t)) => {
                terminal = Some(t);
                break;
            }
            None => bail!("EMPTY_TASK_EVENT"),
        }
    }
    let t = if let Some(t) = terminal {
        t
    } else {
        client
            .get_task(authenticated(p::TaskId { task_id: id.into() }, e)?)
            .await?
            .into_inner()
            .terminal
            .context("EVENT_STREAM_INTERRUPTED: use task watch to reconnect")?
    };
    if let Some(path) = export {
        let mut output = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(path)
            .context("EXPORT_PATH_EXISTS_OR_UNAVAILABLE")?;
        output.write_all(&saved)?;
        output.sync_all()?;
        eprintln!("exported {} bytes to {}", saved.len(), path.display());
    }
    if t.exit_code != 0 {
        eprintln!("{}: {}", t.code, t.message);
    }
    Ok(t.exit_code)
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
    let cli = Cli::parse();
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
        println!(
            "{}",
            serde_json::json!({"manifest":args.output,"modules":result["modules"].as_array().map_or(0,Vec::len)})
        );
        return Ok(0);
    }
    if let Command::Hello { name } = &cli.command
        && let Ok(port) = std::env::var("CODEANA_PORT")
    {
        let port: u16 = port.parse()?;
        let mut client = p::hello_service_client::HelloServiceClient::connect(format!(
            "http://127.0.0.1:{port}"
        ))
        .await?;
        println!(
            "{}",
            client
                .say_hello(p::HelloRequest { name: name.clone() })
                .await?
                .into_inner()
                .message
        );
        return Ok(0);
    }
    let home = private_home(&cli.home.unwrap_or(default_home()?))?;
    let auto = !matches!(cli.command, Command::Task { .. } | Command::Daemon { .. });
    let (mut client, e) = connect(&home, auto).await?;
    let (operation, a) = match cli.command {
        Command::JavaInputs { .. } => {
            unreachable!("explicit Maven export returned before daemon connection")
        }
        Command::Hello { name } => {
            let mut c = p::hello_service_client::HelloServiceClient::connect(e.address).await?;
            println!(
                "{}",
                c.say_hello(p::HelloRequest { name })
                    .await?
                    .into_inner()
                    .message
            );
            return Ok(0);
        }
        Command::Daemon { action } => {
            if action == "restart" {
                ensure!(!e.mock, "MOCK_RESTART_REQUIRES_EXPLICIT_FIXTURE");
                client
                    .manage(authenticated(
                        p::ManageRequest {
                            action: "drain".into(),
                        },
                        &e,
                    )?)
                    .await?;
                for _ in 0..200 {
                    if try_connect(&home).await.is_err() {
                        let _ = connect(&home, true).await?;
                        println!("restarted");
                        return Ok(0);
                    }
                    tokio::time::sleep(Duration::from_millis(50)).await;
                }
                bail!("DRAIN_PENDING");
            }
            let r = client
                .manage(authenticated(p::ManageRequest { action }, &e)?)
                .await?
                .into_inner();
            println!("{}", serde_json::to_string(&r)?);
            return Ok(0);
        }
        Command::Task {
            action,
            id,
            after_seq,
        } => {
            if action == "watch" {
                return watch(&mut client, &e, &id, after_seq, None).await;
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
            println!("{}", serde_json::to_string(r.get_ref())?);
            return Ok(0);
        }
        Command::Index(a) => ("index", a),
        Command::Doctor(a) => ("doctor", a),
        Command::Resolve(a) => ("resolve", a),
        Command::SearchCode(a) => ("search-code", a),
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
        Command::Report(a) => ("report", a),
        Command::Annotate(a) => ("annotate", a),
        Command::Gc(a) => ("gc", a),
        Command::Recover(a) => ("recover", a),
    };
    let stages = if operation == "pipeline" {
        let bytes = std::fs::read(&a.selector)?;
        ensure!(bytes.len() <= 1024 * 1024, "PIPELINE_SPEC_BUDGET");
        serde_json::from_slice::<Vec<Stage>>(&bytes)?
    } else {
        vec![]
    };
    let req = Request {
        request_id: a.request_id.unwrap_or_else(|| new_id("request")),
        project: codeana_application::project_path(&cli.project)?
            .to_str()
            .context("NON_UTF8_PROJECT")?
            .into(),
        operation: operation.into(),
        selector: if operation == "pipeline" {
            String::new()
        } else {
            a.selector
        },
        revision: a.revision,
        scope: a.scope,
        world: a.world,
        module: a.module,
        direction: a.direction,
        relation: a.relation,
        unique: a.unique,
        full: a.full,
        strict: a.strict,
        execute: a.execute,
        background: a.background,
        base: a.base,
        target: a.target,
        cursor: a.cursor,
        name: a.name,
        text: a.text,
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
    let request: p::TaskRequest = convert(&req)?;
    let accepted = client
        .submit_task(authenticated(request, &e)?)
        .await?
        .into_inner();
    if req.background {
        println!("{}", serde_json::to_string(&accepted)?);
        return Ok(0);
    }
    watch(&mut client, &e, &accepted.task_id, 0, a.export.as_deref()).await
}
