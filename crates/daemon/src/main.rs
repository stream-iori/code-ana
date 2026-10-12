use anyhow::{Context, Result, ensure};
use clap::Parser;
use codeana_application::{Endpoint, Engine, Event, default_home, private_home};
use codeana_contracts::{convert, terminal, v1::*};
use std::{
    os::fd::AsRawFd,
    path::PathBuf,
    sync::{Arc, atomic::Ordering},
    time::Duration,
};
use tokio_stream::wrappers::{ReceiverStream, TcpListenerStream};
use tonic::{Request, Response, Status, transport::Server};
#[derive(Parser)]
struct Args {
    #[arg(long)]
    home: Option<PathBuf>,
    #[arg(long)]
    dev_mock_fixtures: Option<PathBuf>,
}
struct Hello;
#[tonic::async_trait]
impl hello_service_server::HelloService for Hello {
    async fn say_hello(&self, r: Request<HelloRequest>) -> Result<Response<HelloReply>, Status> {
        let name = r.into_inner().name;
        Ok(Response::new(HelloReply {
            message: format!("Hello, {}!", if name.is_empty() { "world" } else { &name }),
        }))
    }
}
#[derive(Clone)]
struct Service(Arc<Engine>);
fn failure(e: impl std::fmt::Display) -> Status {
    Status::failed_precondition(e.to_string())
}
impl Service {
    fn status(&self, id: &str) -> Result<TaskStatus, Status> {
        let (state, result, last_seq) = self.0.store.task(id).map_err(failure)?;
        Ok(TaskStatus {
            task_id: id.into(),
            state,
            terminal: result.as_ref().map(terminal),
            last_seq,
        })
    }
}
#[tonic::async_trait]
impl task_service_server::TaskService for Service {
    async fn handshake(
        &self,
        r: Request<HandshakeRequest>,
    ) -> Result<Response<HandshakeReply>, Status> {
        self.0.touch();
        if r.into_inner().protocol != codeana_model::DAEMON_PROTOCOL_VERSION {
            return Err(Status::failed_precondition("UNSUPPORTED_PROTOCOL"));
        }
        Ok(Response::new(HandshakeReply {
            protocol: codeana_model::DAEMON_PROTOCOL_VERSION,
            capabilities: vec![
                "task-v2".into(),
                "text-search-options-v1".into(),
                "overview-sections-v1".into(),
                "reference-options-v1".into(),
                "annotations-meta-v1".into(),
                "runtime-implementations-v1".into(),
                "relation-transitive-v1".into(),
                "java-dispatch-v1".into(),
                "java-analysis-world-v1".into(),
                "fact-ir-v1".into(),
                "immutable-revisions".into(),
                if self.0.mock.is_some() {
                    "mock-fixture-v1".into()
                } else if self.0.java_available() {
                    "javac-java8-v1".into()
                } else {
                    "java-unavailable".into()
                },
            ],
            generation: self.0.generation.clone(),
            mock: self.0.mock.is_some(),
        }))
    }
    async fn submit_task(&self, r: Request<TaskRequest>) -> Result<Response<TaskAccepted>, Status> {
        let request = convert(&r.into_inner()).map_err(failure)?;
        let engine = self.0.clone();
        let task_id = tokio::task::spawn_blocking(move || engine.submit(request))
            .await
            .map_err(failure)?
            .map_err(failure)?;
        Ok(Response::new(TaskAccepted { task_id }))
    }
    type WatchTaskStream = ReceiverStream<Result<TaskEvent, Status>>;
    async fn watch_task(
        &self,
        r: Request<WatchRequest>,
    ) -> Result<Response<Self::WatchTaskStream>, Status> {
        let r = r.into_inner();
        let status = self.status(&r.task_id)?;
        if status.state == "expired" {
            return Err(Status::failed_precondition("TASK_EVENTS_EXPIRED"));
        }
        if r.after_seq > status.last_seq {
            return Err(Status::invalid_argument("EVENT_CURSOR_AHEAD"));
        }
        let (tx, rx) = tokio::sync::mpsc::channel(8);
        let engine = self.0.clone();
        let job = engine.jobs.lock().unwrap().get(&r.task_id).cloned();
        if let Some(j) = &job {
            j.watchers.fetch_add(1, Ordering::SeqCst);
        }
        tokio::spawn(async move {
            let mut seq = r.after_seq;
            loop {
                if tx.is_closed() {
                    break;
                }
                let events = match engine.store.events::<Event>(&r.task_id, seq) {
                    Ok(v) => v,
                    Err(e) => {
                        let _ = tx.send(Err(failure(e))).await;
                        break;
                    }
                };
                let mut terminal_seen = false;
                for (n, e) in events {
                    terminal_seen = matches!(e, Event::Terminal(_));
                    seq = n;
                    if tx.send(Ok(e.wire(r.task_id.clone(), n))).await.is_err() {
                        terminal_seen = true;
                        break;
                    }
                }
                if terminal_seen {
                    break;
                }
                if let Ok((state, _, last)) = engine.store.task(&r.task_id)
                    && state == "terminal"
                    && seq >= last
                {
                    break;
                }
                tokio::select! {_=tx.closed()=>break,_=tokio::time::sleep(Duration::from_millis(30))=>{}}
            }
            if let Some(j) = job {
                *j.disconnected.lock().unwrap() = std::time::Instant::now();
                j.watchers.fetch_sub(1, Ordering::SeqCst);
            }
        });
        Ok(Response::new(ReceiverStream::new(rx)))
    }
    async fn get_task(&self, r: Request<TaskId>) -> Result<Response<TaskStatus>, Status> {
        self.0.touch();
        Ok(Response::new(self.status(&r.into_inner().task_id)?))
    }
    async fn cancel_task(&self, r: Request<TaskId>) -> Result<Response<TaskStatus>, Status> {
        let id = r.into_inner().task_id;
        self.0.cancel(&id).map_err(failure)?;
        Ok(Response::new(self.status(&id)?))
    }
    async fn manage(&self, r: Request<ManageRequest>) -> Result<Response<ManageReply>, Status> {
        self.0.touch();
        match r.into_inner().action.as_str() {
            "status" => {}
            "drain" => {
                self.0.draining.store(true, Ordering::SeqCst);
            }
            "stop" => {
                self.0.draining.store(true, Ordering::SeqCst);
                for j in self.0.jobs.lock().unwrap().values() {
                    j.cancel.cancel();
                }
            }
            _ => return Err(Status::invalid_argument("INVALID_MANAGEMENT_ACTION")),
        };
        Ok(Response::new(ManageReply {
            state: if self.0.draining.load(Ordering::SeqCst) {
                "draining"
            } else {
                "running"
            }
            .into(),
            active_tasks: self.0.active() as u32,
        }))
    }
}
#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    let default = default_home()?;
    if args.dev_mock_fixtures.is_some() {
        ensure!(args.home.is_some(), "MOCK_REQUIRES_EXPLICIT_ISOLATED_HOME");
    }
    let home = private_home(args.home.as_deref().unwrap_or(&default))?;
    if args.dev_mock_fixtures.is_some() {
        ensure!(
            home != default.canonicalize().unwrap_or(default),
            "MOCK_CANNOT_USE_DEFAULT_HOME"
        );
    }
    let lock = std::fs::OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(home.join("daemon.lock"))?;
    // SAFETY: the live File owns this descriptor for the daemon lifetime; flock is nonblocking.
    ensure!(
        unsafe { libc::flock(lock.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) } == 0,
        "DAEMON_ALREADY_RUNNING"
    );
    let fixture = args
        .dev_mock_fixtures
        .map(|p| p.canonicalize())
        .transpose()?;
    let mode_path = home.join("mode");
    let mode = if fixture.is_some() {
        "mock-v1"
    } else {
        "production-v1"
    };
    if mode_path.exists() {
        ensure!(
            std::fs::read_to_string(&mode_path)? == mode,
            "HOME_MODE_MISMATCH"
        );
    } else {
        codeana_storage::durable_write(&mode_path, mode.as_bytes())?;
    }
    let engine = Engine::new(&home, fixture)?;
    engine.maintenance();
    let port = std::env::var("CODEANA_PORT")
        .ok()
        .map(|s| s.parse::<u16>())
        .transpose()?
        .unwrap_or(0);
    let listener = tokio::net::TcpListener::bind((std::net::Ipv4Addr::LOCALHOST, port)).await?;
    let endpoint = Endpoint {
        address: format!("http://{}", listener.local_addr()?),
        token: format!(
            "{}{}",
            uuid::Uuid::new_v4().simple(),
            uuid::Uuid::new_v4().simple()
        ),
        generation: engine.generation.clone(),
        mock: engine.mock.is_some(),
    };
    codeana_storage::durable_write(&home.join("endpoint.json"), &serde_json::to_vec(&endpoint)?)?;
    let token = endpoint.token;
    let service = task_service_server::TaskServiceServer::with_interceptor(
        Service(engine.clone()),
        move |request: Request<()>| {
            if request
                .metadata()
                .get("authorization")
                .and_then(|v| v.to_str().ok())
                != Some(token.as_str())
            {
                return Err(Status::unauthenticated("AUTH_REQUIRED"));
            }
            Ok(request)
        },
    );
    eprintln!("codeana daemon ready: {} mode={mode}", endpoint.address);
    let shutdown = engine.shutdown.clone();
    let stop = engine.clone();
    tokio::spawn(async move {
        let mut terminate =
            tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
                .expect("signal handler");
        tokio::select! {_=tokio::signal::ctrl_c()=>{},_=terminate.recv()=>{}}
        stop.draining.store(true, Ordering::SeqCst);
        for job in stop.jobs.lock().unwrap().values() {
            job.cancel.cancel();
        }
    });
    let served = Server::builder()
        .add_service(service)
        .add_service(hello_service_server::HelloServiceServer::new(Hello))
        .serve_with_incoming_shutdown(TcpListenerStream::new(listener), shutdown.cancelled())
        .await;
    if let Ok(bytes) = std::fs::read(home.join("endpoint.json"))
        && serde_json::from_slice::<Endpoint>(&bytes)
            .is_ok_and(|e| e.generation == engine.generation)
    {
        let _ = std::fs::remove_file(home.join("endpoint.json"));
    }
    served.context("GRPC_SERVER")?;
    drop(lock);
    Ok(())
}
