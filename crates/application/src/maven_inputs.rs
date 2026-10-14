//! Explicit Maven discovery. Indexing only consumes the resulting immutable manifest.
use anyhow::{Context, Result, bail, ensure};
use codeana_config::java_inputs::{
    Artifact, DependencyFailure, DependencyInput, DependencyMode, ExportReceipt, InputModule,
    InputRoot, JavaInputs,
};
use codeana_model::{hash, hash_file};
use roxmltree::{Document, Node, ParsingOptions};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    fs,
    io::{Read, Write},
    os::{fd::AsRawFd, unix::process::CommandExt},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicUsize, Ordering},
    },
    thread,
    time::{Duration, Instant},
};
const MAX_RESPONSE: u64 = 32 * 1024 * 1024;
const MAX_LOG: usize = 32 * 1024 * 1024;

#[derive(Debug, Clone)]
pub struct ExportOptions {
    pub project: PathBuf,
    pub output: PathBuf,
    pub maven: String,
    pub settings: Option<PathBuf>,
    pub profiles: Vec<String>,
    pub modules: Vec<String>,
    pub scopes: Vec<String>,
    pub offline: bool,
    pub source_only: bool,
    pub timeout: u64,
    pub extension: Option<PathBuf>,
    pub java_home: Option<PathBuf>,
}
fn resolve(path: &Path) -> Result<PathBuf> {
    let absolute = if path.is_absolute() {
        path.to_owned()
    } else {
        std::env::current_dir()?.join(path)
    };
    if absolute.exists() {
        return Ok(absolute.canonicalize()?);
    }
    let parent = absolute.parent().context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::MavenInputPath,
        "",
    ))?;
    let name = absolute
        .file_name()
        .context(codeana_model::DomainError::new(
            codeana_model::ErrorCode::MavenInputPath,
            "",
        ))?;
    Ok(resolve(parent)?.join(name))
}
fn inside(root: &Path, value: &str) -> Result<PathBuf> {
    let path = resolve(Path::new(value))?;
    ensure!(
        path.starts_with(root),
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::MavenInputOutsideProject,
            format!("{}", path.display())
        )
    );
    Ok(path)
}
fn relative(root: &Path, path: &Path) -> Result<String> {
    let value = path.strip_prefix(root)?.to_string_lossy();
    Ok(if value.is_empty() {
        ".".into()
    } else {
        value.into_owned()
    })
}
fn relative_root(module: &Path, path: &Path) -> String {
    let a: Vec<_> = module.components().collect();
    let b: Vec<_> = path.components().collect();
    let shared = a.iter().zip(&b).take_while(|(a, b)| a == b).count();
    let mut out = PathBuf::new();
    for _ in shared..a.len() {
        out.push("..");
    }
    for part in &b[shared..] {
        out.push(part.as_os_str());
    }
    if out.as_os_str().is_empty() {
        ".".into()
    } else {
        out.to_string_lossy().into_owned()
    }
}
fn executable(value: &str) -> Result<PathBuf> {
    let direct = Path::new(value);
    if direct.is_file() {
        return Ok(direct.canonicalize()?);
    }
    for directory in std::env::split_paths(&std::env::var_os("PATH").unwrap_or_default()) {
        let path = directory.join(value);
        if path.is_file() {
            return Ok(path.canonicalize()?);
        }
    }
    bail!("Maven executable required")
}
fn parse(text: &str) -> Result<Document<'_>> {
    ensure!(
        !text.to_ascii_uppercase().contains("<!DOCTYPE")
            && !text.to_ascii_uppercase().contains("<!ENTITY"),
        codeana_model::DomainError::new(codeana_model::ErrorCode::MavenResponseEntity, "")
    );
    Ok(Document::parse_with_options(
        text,
        ParsingOptions {
            nodes_limit: 1_000_000,
            ..Default::default()
        },
    )?)
}
fn attr<'a, 'i>(node: Node<'a, 'i>, key: &str) -> Result<&'a str> {
    node.attribute(key)
        .with_context(|| format!("MAVEN_RESPONSE_ATTRIBUTE: {key}"))
}
fn children<'a, 'i>(node: Node<'a, 'i>, name: &'a str) -> impl Iterator<Item = Node<'a, 'i>> {
    node.children()
        .filter(move |n| n.is_element() && n.tag_name().name() == name)
}
fn response(directory: &Path, name: &str, nonce: &str) -> Result<(String, String)> {
    let path = directory.join(format!("{name}.xml"));
    let receipt = directory.join(format!("{name}.sha256"));
    ensure!(
        path.is_file()
            && fs::metadata(&path)?.len() <= MAX_RESPONSE
            && receipt.is_file()
            && fs::metadata(&receipt)?.len() <= 128,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::MavenReceiptMissingOrOversized,
            ""
        )
    );
    let mut data = Vec::new();
    fs::File::open(path)?
        .take(MAX_RESPONSE + 1)
        .read_to_end(&mut data)?;
    ensure!(
        data.len() as u64 <= MAX_RESPONSE,
        codeana_model::DomainError::new(
            codeana_model::ErrorCode::MavenReceiptMissingOrOversized,
            ""
        )
    );
    let digest = hash(&data);
    ensure!(
        fs::read_to_string(receipt)?.trim() == digest,
        codeana_model::DomainError::new(codeana_model::ErrorCode::MavenReceiptChecksum, "")
    );
    let text = String::from_utf8(data)?;
    let document = parse(&text)?;
    let root = document.root_element();
    ensure!(
        root.has_tag_name(if name == "dependency-failure" {
            "maven-dependency-failure"
        } else {
            "maven-inputs"
        }) && root.attribute("format") == Some("maven-inputs-v2")
            && root.attribute("nonce") == Some(nonce),
        codeana_model::DomainError::new(codeana_model::ErrorCode::MavenReceiptIdentity, "")
    );
    Ok((text, digest))
}
fn write_utf(out: &mut Vec<u8>, text: &str) -> Result<()> {
    let mut bytes = Vec::new();
    for c in text.encode_utf16() {
        if (1..=127).contains(&c) {
            bytes.push(c as u8);
        } else if c <= 2047 {
            bytes.extend([192 | (c >> 6) as u8, 128 | (c & 63) as u8]);
        } else {
            bytes.extend([
                224 | (c >> 12) as u8,
                128 | ((c >> 6) & 63) as u8,
                128 | (c & 63) as u8,
            ]);
        }
    }
    ensure!(
        bytes.len() <= 65535,
        "Maven selection path exceeds protocol budget"
    );
    out.extend((bytes.len() as u16).to_be_bytes());
    out.extend(bytes);
    Ok(())
}
fn prerequisite_modules(
    xml: &str,
    root: &Path,
    names: &BTreeMap<PathBuf, String>,
    selected: &BTreeSet<PathBuf>,
    tests: bool,
) -> Result<BTreeSet<PathBuf>> {
    let document = parse(xml)?;
    let mut modules = BTreeMap::new();
    for node in children(document.root_element(), "module") {
        let directory = inside(root, attr(node, "directory")?)?;
        ensure!(
            names.contains_key(&directory) && modules.insert(directory, node).is_none(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenModuleInventoryChanged,
                ""
            )
        );
    }
    ensure!(
        modules.len() == names.len(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::MavenModuleInventoryChanged, "")
    );
    let mut queue: VecDeque<_> = selected.iter().cloned().collect();
    let mut visited = BTreeSet::new();
    let mut pending = BTreeSet::new();
    while let Some(directory) = queue.pop_front() {
        if !visited.insert(directory.clone()) {
            continue;
        }
        let node = *modules
            .get(&directory)
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenPrerequisiteModule,
                "",
            ))?;
        if node.attribute("resolved") != Some("true") {
            ensure!(
                !selected.contains(&directory),
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::MavenSelectedClasspathRequired,
                    ""
                )
            );
            pending.insert(directory);
            continue;
        }
        for tag in ["main-dependencies", "test-dependencies"] {
            if tag == "test-dependencies" && !(tests && selected.contains(&directory)) {
                continue;
            }
            if let Some(section) = children(node, tag).next() {
                for dependency in children(section, "reactor") {
                    let parent = inside(root, attr(dependency, "directory")?)?;
                    ensure!(
                        modules.contains_key(&parent),
                        codeana_model::DomainError::new(
                            codeana_model::ErrorCode::MavenPrerequisiteModule,
                            ""
                        )
                    );
                    queue.push_back(parent);
                }
            }
        }
    }
    Ok(pending)
}

struct Logs {
    tail: Mutex<VecDeque<u8>>,
    total: AtomicUsize,
    ready: AtomicBool,
    classpath_ready: AtomicUsize,
    dependency_failed: AtomicUsize,
    protocol_error: AtomicBool,
    complete: AtomicBool,
    closed: AtomicBool,
}
impl Logs {
    fn drain(
        self: Arc<Self>,
        mut stream: impl Read + AsRawFd + Send + 'static,
        nonce: String,
    ) -> Result<thread::JoinHandle<()>> {
        let fd = stream.as_raw_fd();
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        ensure!(
            flags >= 0 && unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } >= 0,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenLogPipe,
                format!("{}", std::io::Error::last_os_error())
            )
        );
        Ok(thread::spawn(move || {
            let mut buffer = [0; 4096];
            let mut pending = Vec::new();
            while !self.closed.load(Ordering::Acquire) {
                let n = match stream.read(&mut buffer) {
                    Ok(n) => n,
                    Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(10));
                        continue;
                    }
                    Err(_) => break,
                };
                if n == 0 {
                    break;
                }
                self.total.fetch_add(n, Ordering::Relaxed);
                if let Ok(mut tail) = self.tail.lock() {
                    tail.extend(&buffer[..n]);
                    while tail.len() > 8192 {
                        tail.pop_front();
                    }
                }
                pending.extend(&buffer[..n]);
                if pending.len() > 65536 {
                    pending.drain(..pending.len() - 65536);
                }
                while let Some(end) = pending.iter().position(|b| *b == b'\n') {
                    let line: Vec<_> = pending.drain(..=end).collect();
                    let s = String::from_utf8_lossy(&line);
                    let s = s.trim();
                    if s == format!("CODEANA_MAVEN_INPUTS_READY:{nonce}")
                        && self.ready.swap(true, Ordering::AcqRel)
                    {
                        self.protocol_error.store(true, Ordering::Release);
                    }
                    for (name, failed) in [("CLASSPATH_READY", false), ("DEPENDENCY_FAILED", true)]
                    {
                        if let Some(suffix) =
                            s.strip_prefix(&format!("CODEANA_MAVEN_{name}:{nonce}:"))
                        {
                            let round = suffix.parse::<usize>().ok().filter(|r| *r <= 256);
                            let valid = if let Some(round) = round {
                                if failed {
                                    self.classpath_ready.load(Ordering::Acquire) == round
                                        && self
                                            .dependency_failed
                                            .compare_exchange(
                                                0,
                                                round + 1,
                                                Ordering::AcqRel,
                                                Ordering::Acquire,
                                            )
                                            .is_ok()
                                } else {
                                    self.dependency_failed.load(Ordering::Acquire) == 0
                                        && self
                                            .classpath_ready
                                            .compare_exchange(
                                                round,
                                                round + 1,
                                                Ordering::AcqRel,
                                                Ordering::Acquire,
                                            )
                                            .is_ok()
                                }
                            } else {
                                false
                            };
                            if !valid {
                                self.protocol_error.store(true, Ordering::Release);
                            }
                        }
                    }
                    if s.contains("CODEANA_MAVEN_INPUTS_COMPLETE") {
                        self.complete.store(true, Ordering::Release);
                    }
                }
            }
        }))
    }
    fn text(&self) -> String {
        self.tail
            .lock()
            .map(|b| String::from_utf8_lossy(&b.iter().copied().collect::<Vec<_>>()).into_owned())
            .unwrap_or_default()
    }
}
struct Session {
    child: Child,
    logs: Arc<Logs>,
    readers: Vec<thread::JoinHandle<()>>,
    deadline: Instant,
}
impl Session {
    fn selection(&mut self, bytes: &[u8], cancel: &AtomicBool) -> Result<()> {
        let mut input = self
            .child
            .stdin
            .take()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenStdin,
                "",
            ))?;
        let fd = input.as_raw_fd();
        let flags = unsafe { libc::fcntl(fd, libc::F_GETFL) };
        ensure!(
            flags >= 0 && unsafe { libc::fcntl(fd, libc::F_SETFL, flags | libc::O_NONBLOCK) } >= 0,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenSelectionPipe,
                format!("{}", std::io::Error::last_os_error())
            )
        );
        let mut written = 0;
        while written < bytes.len() {
            self.check(cancel)?;
            match input.write(&bytes[written..]) {
                Ok(0) => bail!(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::MavenSelectionPipeClosed,
                    ""
                )),
                Ok(count) => written += count,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    thread::sleep(Duration::from_millis(25))
                }
                Err(error) => return Err(error.into()),
            }
        }
        self.child.stdin = Some(input);
        Ok(())
    }
    fn start(command: &mut Command, nonce: &str, timeout: u64) -> Result<Self> {
        let child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .process_group(0)
            .spawn()?;
        let logs = Arc::new(Logs {
            tail: Mutex::new(VecDeque::new()),
            total: AtomicUsize::new(0),
            ready: AtomicBool::new(false),
            classpath_ready: AtomicUsize::new(0),
            dependency_failed: AtomicUsize::new(0),
            protocol_error: AtomicBool::new(false),
            complete: AtomicBool::new(false),
            closed: AtomicBool::new(false),
        });
        let mut session = Self {
            child,
            logs,
            readers: Vec::new(),
            deadline: Instant::now() + Duration::from_secs(timeout),
        };
        session.readers.push(
            session.logs.clone().drain(
                session
                    .child
                    .stdout
                    .take()
                    .context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::MavenStdout,
                        "",
                    ))?,
                nonce.into(),
            )?,
        );
        session.readers.push(
            session.logs.clone().drain(
                session
                    .child
                    .stderr
                    .take()
                    .context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::MavenStderr,
                        "",
                    ))?,
                nonce.into(),
            )?,
        );
        Ok(session)
    }
    fn check(&self, cancel: &AtomicBool) -> Result<()> {
        ensure!(
            !self.logs.protocol_error.load(Ordering::Acquire),
            "MAVEN_RESPONSE_SEQUENCE"
        );
        ensure!(
            !cancel.load(Ordering::Acquire),
            codeana_model::DomainError::new(codeana_model::ErrorCode::MavenInputsCancelled, "")
        );
        ensure!(
            self.logs.total.load(Ordering::Relaxed) <= MAX_LOG,
            codeana_model::DomainError::new(codeana_model::ErrorCode::MavenLogBudget, "")
        );
        ensure!(
            Instant::now() < self.deadline,
            codeana_model::DomainError::new(codeana_model::ErrorCode::MavenInputsTimeout, "")
        );
        Ok(())
    }
    fn ready(&mut self, cancel: &AtomicBool) -> Result<()> {
        loop {
            self.check(cancel)?;
            if self.logs.ready.load(Ordering::Acquire) {
                return Ok(());
            }
            if let Some(status) = self.child.try_wait()? {
                self.join();
                self.check(cancel)?;
                if self.logs.ready.load(Ordering::Acquire) {
                    return Ok(());
                }
                bail!("MAVEN_INPUTS_FAILED status={status}: {}", self.logs.text());
            }
            thread::sleep(Duration::from_millis(25));
        }
    }
    fn classpath_ready(&mut self, round: usize, cancel: &AtomicBool) -> Result<bool> {
        loop {
            self.check(cancel)?;
            if self.logs.classpath_ready.load(Ordering::Acquire) > round {
                return Ok(true);
            }
            if self.logs.dependency_failed.load(Ordering::Acquire) == round + 1 {
                return Ok(false);
            }
            if let Some(status) = self.child.try_wait()? {
                self.join();
                self.check(cancel)?;
                // The process can exit before a reader observes its final marker.
                if self.logs.classpath_ready.load(Ordering::Acquire) > round {
                    return Ok(true);
                }
                if self.logs.dependency_failed.load(Ordering::Acquire) == round + 1 {
                    return Ok(false);
                }
                bail!("MAVEN_INPUTS_FAILED status={status}: {}", self.logs.text());
            }
            thread::sleep(Duration::from_millis(25));
        }
    }
    fn finish(&mut self, cancel: &AtomicBool) -> Result<()> {
        loop {
            self.check(cancel)?;
            if let Some(status) = self.child.try_wait()? {
                self.join();
                self.check(cancel)?;
                ensure!(
                    !self.logs.closed.load(Ordering::Acquire),
                    "MAVEN_CLEANUP_INCOMPLETE"
                );
                ensure!(
                    status.code() == Some(1) && self.logs.complete.load(Ordering::Acquire),
                    "MAVEN_INPUTS_FAILED status={status}: {}",
                    self.logs.text()
                );
                return Ok(());
            }
            thread::sleep(Duration::from_millis(25));
        }
    }
    fn join(&mut self) {
        let until = Instant::now() + Duration::from_secs(2);
        while self.readers.iter().any(|r| !r.is_finished()) && Instant::now() < until {
            thread::sleep(Duration::from_millis(10));
        }
        if self.readers.iter().any(|r| !r.is_finished()) {
            // A descendant may still hold a log FD after Maven exits. Reap the owned group.
            unsafe {
                libc::kill(-(self.child.id() as i32), libc::SIGKILL);
            }
            // An escaped descendant can retain a pipe outside the owned group. Reader shutdown
            // must still finish within the export deadline instead of blocking on inherited FDs.
            self.logs.closed.store(true, Ordering::Release);
        }
        for reader in self.readers.drain(..) {
            let _ = reader.join();
        }
    }
}
impl Drop for Session {
    fn drop(&mut self) {
        if self.child.try_wait().ok().flatten().is_none() {
            unsafe {
                libc::kill(-(self.child.id() as i32), libc::SIGTERM);
            }
            let until = Instant::now() + Duration::from_secs(2);
            while Instant::now() < until && self.child.try_wait().ok().flatten().is_none() {
                thread::sleep(Duration::from_millis(25));
            }
            if self.child.try_wait().ok().flatten().is_none() {
                unsafe {
                    libc::kill(-(self.child.id() as i32), libc::SIGKILL);
                }
                let _ = self.child.wait();
            }
        }
        self.join();
    }
}
fn unique(values: &[String]) -> Vec<String> {
    let mut seen = BTreeSet::new();
    values
        .iter()
        .filter(|s| seen.insert((*s).clone()))
        .cloned()
        .collect()
}

/// Keep bounded actionable text without repository URLs or JVM option echoes.
fn failure_details(text: &str) -> String {
    let mut plain = String::new();
    let mut escape = false;
    for c in text.chars() {
        if c == '\u{1b}' {
            escape = true;
            continue;
        }
        if escape {
            if c.is_ascii_alphabetic() {
                escape = false;
            }
            continue;
        }
        plain.push(c);
    }
    let mut result = plain
        .lines()
        .filter(|line| {
            let line = line.trim().trim_start_matches("[ERROR]").trim();
            !line.is_empty()
                && ![
                    "To see the full stack trace",
                    "Re-run Maven using",
                    "For more information",
                    "[Help ",
                    "NOTE: Picked up ",
                    "Picked up ",
                ]
                .iter()
                .any(|prefix| line.starts_with(prefix))
        })
        .map(|line| {
            line.split_whitespace()
                .map(|word| {
                    if ["http://", "https://", "file://"]
                        .iter()
                        .any(|prefix| word.to_ascii_lowercase().contains(prefix))
                    {
                        "<repository>"
                    } else {
                        word
                    }
                })
                .collect::<Vec<_>>()
                .join(" ")
        })
        .collect::<Vec<_>>()
        .join("\n");
    let mut end = result.len().min(4096);
    while !result.is_char_boundary(end) {
        end -= 1;
    }
    result.truncate(end);
    result
}

#[allow(clippy::too_many_arguments)] // Receipt identity spans the immutable export attempt and its current selection.
fn dependency_failure(
    directory: &Path,
    nonce: &str,
    round: usize,
    metadata_hash: &str,
    root: &Path,
    names: &BTreeMap<PathBuf, String>,
    attempted: &BTreeSet<PathBuf>,
) -> Result<(DependencyFailure, String)> {
    let (text, digest) = response(directory, "dependency-failure", nonce)?;
    let document = parse(&text)?;
    let node = document.root_element();
    ensure!(
        attr(node, "round")?.parse::<usize>()? == round
            && attr(node, "metadata-sha256")? == metadata_hash,
        "MAVEN_FAILURE_IDENTITY"
    );
    let (metadata, current_hash) = response(directory, "metadata", nonce)?;
    ensure!(current_hash == metadata_hash, "MAVEN_METADATA_CHANGED");
    let path = inside(root, attr(node, "directory")?)?;
    ensure!(attempted.contains(&path), "MAVEN_FAILURE_MODULE");
    let module = names.get(&path).context("MAVEN_FAILURE_MODULE")?.clone();
    let pom = inside(root, attr(node, "pom")?)?;
    let model = parse(&metadata)?;
    let owner = children(model.root_element(), "module")
        .find(|m| m.attribute("directory") == path.to_str())
        .context("MAVEN_FAILURE_MODULE")?;
    ensure!(owner.attribute("pom") == pom.to_str(), "MAVEN_FAILURE_POM");
    // Do not assume the effective POM is literally <module>/pom.xml.
    ensure!(
        children(owner, "model-input").any(|i| i.attribute("path") == pom.to_str()),
        "MAVEN_FAILURE_POM"
    );
    if directory.join("inputs.xml").exists() {
        let (inputs, _) = response(directory, "inputs", nonce)?;
        let inputs = parse(&inputs)?;
        ensure!(
            attr(inputs.root_element(), "round")?
                .parse::<usize>()?
                .checked_add(1)
                == Some(round),
            "MAVEN_CONFLICTING_TERMINAL"
        );
    }
    Ok((
        DependencyFailure {
            module,
            pom: relative(root, &pom)?,
            coordinate: failure_details(attr(node, "coordinate")?),
            reason: failure_details(node.text().unwrap_or_default()),
            round: round as u32,
        },
        digest,
    ))
}
fn controls(root: &Path) -> Result<BTreeMap<String, String>> {
    let mut out = BTreeMap::new();
    if root.join(".mvn").is_dir() {
        for item in walkdir::WalkDir::new(root.join(".mvn")) {
            let item = item?;
            if item.file_type().is_file() {
                out.insert(relative(root, item.path())?, hash_file(item.path())?);
            }
        }
    }
    Ok(out)
}
fn extension(options: &ExportOptions) -> Result<PathBuf> {
    if let Some(p) = &options.extension {
        return Ok(p.canonicalize()?);
    }
    let exe = std::env::current_exe()?;
    let base = exe.parent().context(codeana_model::DomainError::new(
        codeana_model::ErrorCode::MavenExtension,
        "",
    ))?;
    for p in [
        base.join("lib/java/maven-input-worker.jar"),
        base.join("../lib/java/maven-input-worker.jar"),
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../lang-java-adapotr/target/maven-input-worker.jar"),
    ] {
        if p.is_file() {
            return Ok(p.canonicalize()?);
        }
    }
    bail!("Maven extension missing; run scripts/build-java-maven.sh")
}

pub fn export(options: &ExportOptions, cancel: &AtomicBool) -> Result<JavaInputs> {
    let root = options.project.canonicalize()?;
    let output = resolve(&options.output)?;
    ensure!(!output.exists(), "output must not exist");
    ensure!(root.join("pom.xml").is_file(), "pom.xml required");
    ensure!(
        (1..=3600).contains(&options.timeout),
        "timeout must be 1..3600 seconds"
    );
    let scopes = unique(&options.scopes);
    ensure!(
        !scopes.is_empty() && scopes.iter().all(|s| s == "MAIN" || s == "TEST"),
        "scope must be MAIN or TEST"
    );
    let profiles = unique(&options.profiles);
    ensure!(
        profiles.iter().all(|p| !p.is_empty() && !p.contains(',')),
        "invalid profile name"
    );
    let home = PathBuf::from(std::env::var_os("HOME").context("HOME missing")?);
    let maven = executable(&options.maven)?;
    let extension = extension(options)?;
    let java = options
        .java_home
        .clone()
        .or_else(|| std::env::var_os("CODEANA_JAVA8_HOME").map(PathBuf::from))
        .unwrap_or_else(|| home.join(".sdkman/candidates/java/8.0.492-zulu"))
        .canonicalize()?;
    ensure!(
        java.join("lib/tools.jar").is_file(),
        "SDKMAN Java 8 JDK required for Maven input discovery"
    );
    let mut external = BTreeMap::new();
    for p in [&maven, &extension, &java.join("release")] {
        external.insert(p.to_string_lossy().into_owned(), hash_file(p)?);
    }
    let settings = options
        .settings
        .clone()
        .unwrap_or_else(|| home.join(".m2/settings.xml"));
    ensure!(
        options.settings.is_none() || settings.is_file(),
        "explicit Maven settings file missing"
    );
    for p in [
        settings.clone(),
        maven
            .parent()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenHome,
                "",
            ))?
            .parent()
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenHome,
                "",
            ))?
            .join("conf/settings.xml"),
    ] {
        if p.is_file() {
            let p = p.canonicalize()?;
            external.insert(p.to_string_lossy().into_owned(), hash_file(&p)?);
        }
    }
    let mut control = controls(&root)?;
    let tmp = tempfile::Builder::new()
        .prefix("codeana-maven-export-")
        .tempdir()?;
    let nonce = uuid::Uuid::new_v4().to_string();
    let tests = scopes.iter().any(|s| s == "TEST");
    let mut command = Command::new(&maven);
    command
        .current_dir(&root)
        .args(["-B", "-q", "-Dstyle.color=never"]);
    for key in [
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "MAVEN_OPTS",
        "MAVEN_ARGS",
    ] {
        command.env_remove(key);
    }
    let paths = std::env::join_paths(std::iter::once(java.join("bin")).chain(
        std::env::split_paths(&std::env::var_os("PATH").unwrap_or_default()),
    ))?;
    command
        .env("JAVA_HOME", &java)
        .env("PATH", paths)
        .env("MAVEN_OPTS", "-Xmx512m");
    if options.settings.is_some() {
        command.arg("-s").arg(settings.canonicalize()?);
    }
    if !profiles.is_empty() {
        command.arg("-P").arg(profiles.join(","));
    }
    if options.offline {
        command.arg("-o");
    }
    command.arg("-f").arg(root.join("pom.xml"));
    command
        .arg(format!("-Dmaven.ext.class.path={}", extension.display()))
        .arg(format!("-Dcodeana.inputs.output={}", tmp.path().display()))
        .arg(format!("-Dcodeana.inputs.project={}", root.display()))
        .arg("-Dcodeana.inputs.interactive=true")
        .arg(format!("-Dcodeana.inputs.resolve={}", !options.source_only))
        .arg(format!("-Dcodeana.inputs.tests={tests}"))
        .arg(format!("-Dcodeana.inputs.nonce={nonce}"))
        .arg("validate");
    let mut session = Session::start(&mut command, &nonce, options.timeout)?;
    session.ready(cancel)?;
    let (metadata, metadata_hash) = response(tmp.path(), "metadata", &nonce)?;
    let meta = parse(&metadata)?;
    let inventory: Vec<_> = children(meta.root_element(), "module").collect();
    ensure!(
        !inventory.is_empty() && inventory.len() <= 256,
        codeana_model::DomainError::new(codeana_model::ErrorCode::MavenModuleBudget, "")
    );
    let mut names = BTreeMap::new();
    for node in &inventory {
        let path = inside(&root, attr(*node, "directory")?)?;
        let rel = relative(&root, &path)?;
        let name = if rel == "." {
            attr(*node, "coordinate")?
                .split(':')
                .nth(1)
                .context(codeana_model::DomainError::new(
                    codeana_model::ErrorCode::MavenCoordinate,
                    "",
                ))?
                .into()
        } else {
            rel
        };
        ensure!(
            names.insert(path, name).is_none(),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenDuplicateModuleDirectory,
                ""
            )
        );
    }
    let mut selected = BTreeSet::new();
    for label in &options.modules {
        let found: Vec<_> = names
            .iter()
            .filter(|(p, n)| label == *n || relative(&root, p).is_ok_and(|s| &s == label))
            .collect();
        ensure!(
            found.len() == 1,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenModuleSelection,
                label.to_string()
            )
        );
        selected.insert(found[0].0.clone());
    }
    if options.modules.is_empty() {
        selected.extend(names.keys().cloned());
    }
    let mut selection = 0x414d5331u32.to_be_bytes().to_vec();
    write_utf(&mut selection, &nonce)?;
    selection.push(u8::from(tests));
    selection.extend((selected.len() as u32).to_be_bytes());
    for p in &selected {
        write_utf(&mut selection, &p.to_string_lossy())?;
    }
    session.selection(&selection, cancel)?;
    let mut classpath_rounds = 0;
    let mut attempted = selected.clone();
    let mut failed = None;
    for round in 0..=256 {
        if !session.classpath_ready(round, cancel)? {
            ensure!(!options.source_only, "MAVEN_UNEXPECTED_DEPENDENCY_FAILURE");
            failed = Some(dependency_failure(
                tmp.path(),
                &nonce,
                round,
                &metadata_hash,
                &root,
                &names,
                &attempted,
            )?);
            break;
        }
        let (candidate, _) = response(tmp.path(), "inputs", &nonce)?;
        ensure!(
            attr(parse(&candidate)?.root_element(), "round")?.parse::<usize>()? == round,
            "MAVEN_RESPONSE_ROUND"
        );
        let pending = if options.source_only {
            BTreeSet::new()
        } else {
            prerequisite_modules(&candidate, &root, &names, &selected, tests)?
        };
        let mut frame = 0x414d5031u32.to_be_bytes().to_vec();
        write_utf(&mut frame, &nonce)?;
        frame.extend((pending.len() as u32).to_be_bytes());
        for directory in &pending {
            write_utf(&mut frame, &directory.to_string_lossy())?;
        }
        attempted.extend(pending.iter().cloned());
        session.selection(&frame, cancel)?;
        if pending.is_empty() {
            break;
        }
        ensure!(
            round < 256,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenPrerequisiteRoundBudget,
                ""
            )
        );
        classpath_rounds += 1;
    }
    session.finish(cancel)?;
    ensure!(
        failed.is_some() || !tmp.path().join("dependency-failure.xml").exists(),
        "MAVEN_UNEXPECTED_DEPENDENCY_FAILURE"
    );
    if let Some((failure, digest)) = &failed {
        let (confirmed, confirmed_digest) = dependency_failure(
            tmp.path(),
            &nonce,
            failure.round as usize,
            &metadata_hash,
            &root,
            &names,
            &attempted,
        )?;
        ensure!(
            confirmed_digest == *digest
                && serde_json::to_vec(&confirmed)? == serde_json::to_vec(failure)?,
            "MAVEN_FAILURE_CHANGED"
        );
    }
    let (inputs, inputs_hash) = response(
        tmp.path(),
        if failed.is_some() {
            "metadata"
        } else {
            "inputs"
        },
        &nonce,
    )?;
    if failed.is_some() {
        ensure!(inputs_hash == metadata_hash, "MAVEN_METADATA_CHANGED");
    }
    let doc = parse(&inputs)?;
    let inventory: Vec<_> = children(doc.root_element(), "module").collect();
    ensure!(
        inventory.len() == names.len(),
        codeana_model::DomainError::new(codeana_model::ErrorCode::MavenModuleInventoryChanged, "")
    );
    let mut observed = BTreeSet::new();
    let mut records = Vec::new();
    let mut active = Vec::new();
    let mut model_hashes = Vec::new();
    let mut artifact_hashes: BTreeMap<PathBuf, String> = BTreeMap::new();
    for node in inventory {
        session.check(cancel)?;
        let directory = inside(&root, attr(node, "directory")?)?;
        ensure!(
            observed.insert(directory.clone()),
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenModuleInventoryChanged,
                ""
            )
        );
        let module = names
            .get(&directory)
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenModuleInventoryChanged,
                "",
            ))?;
        let physical = relative(&root, &directory)?;
        let model = children(node, "effective-model")
            .next()
            .and_then(|n| n.text())
            .context(codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenEffectiveModelMissing,
                "",
            ))?;
        let parsed = parse(model)?;
        let packaging = children(parsed.root_element(), "packaging")
            .next()
            .and_then(|n| n.text())
            .unwrap_or("jar");
        ensure!(
            matches!(node.attribute("release"), Some("8" | "1.8")) || packaging == "pom",
            "explicit Java 8 effective compiler version required: {module}"
        );
        for item in children(node, "model-input") {
            let p = resolve(Path::new(attr(item, "path")?))?;
            let expected = attr(item, "sha256")?;
            ensure!(
                p.is_file() && hash_file(&p)? == expected,
                codeana_model::DomainError::new(
                    codeana_model::ErrorCode::MavenModelChanged,
                    format!("{}", p.display())
                )
            );
            if p.starts_with(&root) {
                control.insert(relative(&root, &p)?, expected.into());
            } else {
                external.insert(p.to_string_lossy().into_owned(), expected.into());
            }
        }
        let mut source_roots = Vec::new();
        let mut resource_roots = Vec::new();
        let mut outputs = Vec::new();
        for (tag, scope, generated, resource) in [
            ("main-root", "MAIN", false, false),
            ("test-root", "TEST", false, false),
            ("main-resource", "MAIN", false, true),
            ("test-resource", "TEST", false, true),
            ("generated-main-root", "MAIN", true, false),
            ("generated-test-root", "TEST", true, false),
        ] {
            if !(scopes.iter().any(|s| s == scope) || scope == "MAIN" && tests && !resource) {
                continue;
            }
            for item in children(node, tag) {
                let path = inside(
                    &root,
                    item.text().context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::MavenRootPath,
                        "",
                    ))?,
                )?;
                if generated && !path.exists() {
                    continue;
                }
                let mut record = InputRoot {
                    path: relative_root(&directory, &path),
                    scope: scope.into(),
                    generated: false,
                    generated_from: BTreeMap::new(),
                };
                if generated {
                    let mut proof = BTreeMap::new();
                    for entry in walkdir::WalkDir::new(&directory)
                        .into_iter()
                        .filter_entry(|e| e.file_name() != "target")
                    {
                        let entry = entry?;
                        if entry.file_type().is_file()
                            && entry.path().extension().is_some_and(|e| e == "java")
                        {
                            proof.insert(relative(&root, entry.path())?, hash_file(entry.path())?);
                        }
                    }
                    let pom = relative(&root, &directory.join("pom.xml"))?;
                    if let Some(value) = control.get(&pom) {
                        proof.insert(pom, value.clone());
                    }
                    record.generated = true;
                    record.generated_from = proof;
                }
                let dest = if resource {
                    &mut resource_roots
                } else {
                    &mut source_roots
                };
                if !dest.contains(&record) {
                    dest.push(record);
                }
            }
        }
        for item in children(node, "output-root") {
            outputs.push(relative_root(
                &directory,
                &inside(
                    &root,
                    item.text().context(codeana_model::DomainError::new(
                        codeana_model::ErrorCode::MavenOutputRoot,
                        "",
                    ))?,
                )?,
            ));
        }
        let mut dependencies = BTreeMap::new();
        let mut classpath = BTreeMap::new();
        let mut order = BTreeMap::new();
        for (scope, tag) in [("MAIN", "main-dependencies"), ("TEST", "test-dependencies")] {
            if !(scopes.iter().any(|s| s == scope) || scope == "MAIN" && tests) {
                continue;
            }
            let mut deps = Vec::new();
            let mut cp = Vec::new();
            let mut sequence = Vec::new();
            if let Some(section) = children(node, tag).next() {
                for item in section.children().filter(|n| n.is_element()) {
                    match item.tag_name().name() {
                        "reactor" => {
                            let path = inside(&root, attr(item, "directory")?)?;
                            let target = names
                                .get(&path)
                                .context(codeana_model::DomainError::new(
                                    codeana_model::ErrorCode::MavenReactorModule,
                                    "",
                                ))?
                                .clone();
                            if !deps.contains(&target) {
                                deps.push(target.clone());
                            }
                            sequence.push(DependencyInput::Reactor {
                                module: target,
                                coordinate: attr(item, "coordinate")?.into(),
                                classifier: attr(item, "classifier")?.into(),
                                extension: attr(item, "extension")?.into(),
                            });
                        }
                        "external" => {
                            session.check(cancel)?;
                            let path = resolve(Path::new(attr(item, "path")?))?;
                            ensure!(
                                path.is_file(),
                                codeana_model::DomainError::new(
                                    codeana_model::ErrorCode::MavenArtifactMissing,
                                    format!("{}", path.display())
                                )
                            );
                            let digest = if let Some(digest) = artifact_hashes.get(&path) {
                                digest.clone()
                            } else {
                                let digest = hash_file(&path)?;
                                artifact_hashes.insert(path.clone(), digest.clone());
                                digest
                            };
                            cp.push(Artifact {
                                path: path.to_string_lossy().into_owned(),
                                sha256: digest,
                                coordinate: attr(item, "coordinate")?.into(),
                                classifier: attr(item, "classifier")?.into(),
                                extension: attr(item, "extension")?.into(),
                            });
                            sequence.push(DependencyInput::Classpath {
                                index: cp.len() - 1,
                            });
                        }
                        _ => bail!(codeana_model::DomainError::new(
                            codeana_model::ErrorCode::MavenDependencyKind,
                            ""
                        )),
                    }
                }
            }
            dependencies.insert(scope.into(), deps);
            classpath.insert(scope.into(), cp);
            order.insert(scope.into(), sequence);
        }
        let model_hash = hash(model.as_bytes());
        model_hashes.push((physical.clone(), model_hash.clone()));
        active.push((
            physical.clone(),
            children(node, "active-profile")
                .map(|n| n.text().unwrap_or(""))
                .collect::<Vec<_>>(),
        ));
        records.push(InputModule {
            id: module.clone(),
            physical_id: physical.clone(),
            root: physical,
            coordinate: attr(node, "coordinate")?.into(),
            source_version: "8".into(),
            packaging: packaging.into(),
            selected: selected.contains(&directory),
            resolved_classpath: node.attribute("resolved") == Some("true"),
            source_roots,
            resource_roots,
            output_roots: outputs,
            dependencies,
            classpath,
            dependency_order: order,
            effective_model_sha256: model_hash,
        });
    }
    for (name, digest) in &control {
        session.check(cancel)?;
        ensure!(
            hash_file(&root.join(name))? == *digest,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenControlChanged,
                name.to_string()
            )
        );
    }
    for (name, digest) in &external {
        session.check(cancel)?;
        ensure!(
            hash_file(Path::new(name))? == *digest,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenExternalControlChanged,
                name.to_string()
            )
        );
    }
    for (path, digest) in &artifact_hashes {
        session.check(cancel)?;
        ensure!(
            hash_file(path)? == *digest,
            codeana_model::DomainError::new(
                codeana_model::ErrorCode::MavenArtifactChanged,
                format!("{}", path.display())
            )
        );
    }
    let result = JavaInputs {
        schema_version: 4,
        controls: control,
        external_controls: external,
        effective_model_sha256: hash(serde_json::to_vec(&model_hashes)?),
        active_profiles_sha256: hash(serde_json::to_vec(&active)?),
        selected_profiles: profiles,
        scopes,
        resolved_classpath: !options.source_only && failed.is_none(),
        dependency_mode: if options.source_only {
            DependencyMode::SourceOnlyRequested
        } else if failed.is_some() {
            DependencyMode::SourceOnlyFallback
        } else {
            DependencyMode::Resolved
        },
        dependency_failure: failed.as_ref().map(|(failure, _)| failure.clone()),
        receipt: Some(ExportReceipt {
            nonce,
            metadata_sha256: metadata_hash,
            inputs_sha256: inputs_hash,
            extension_sha256: hash_file(&extension)?,
            maven_processes: 1,
            prerequisite_classpath_rounds: classpath_rounds,
            dependency_failure_sha256: failed.map(|(_, digest)| digest),
        }),
        modules: records,
    };
    let mut encoded = serde_json::to_vec_pretty(&result)?;
    encoded.push(b'\n');
    ensure!(
        encoded.len() as u64 <= MAX_RESPONSE,
        codeana_model::DomainError::new(codeana_model::ErrorCode::MavenManifestBudget, "")
    );
    if let Some(p) = output.parent() {
        fs::create_dir_all(p)?;
    }
    session.check(cancel)?;
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&output)?;
    if let Err(error) = file.write_all(&encoded).and_then(|()| file.sync_all()) {
        let _ = fs::remove_file(&output);
        return Err(error.into());
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn dependency_receipt_requires_current_model_scope_and_round() {
        let temporary = tempfile::tempdir().unwrap();
        let root = temporary.path().canonicalize().unwrap();
        let directory = root.join("receipt");
        fs::create_dir(&directory).unwrap();
        let pom = root.join("pom.xml");
        fs::write(&pom, "<project/>").unwrap();
        let metadata = format!(
            "<maven-inputs format=\"maven-inputs-v2\" nonce=\"ok\"><module directory=\"{}\" pom=\"{1}\"><model-input path=\"{}\"/></module></maven-inputs>",
            root.display(),
            pom.display()
        );
        let write = |name: &str, text: &str| {
            fs::write(directory.join(format!("{name}.xml")), text).unwrap();
            fs::write(directory.join(format!("{name}.sha256")), hash(text)).unwrap();
        };
        write("metadata", &metadata);
        let receipt = format!(
            "<maven-dependency-failure format=\"maven-inputs-v2\" nonce=\"ok\" round=\"1\" metadata-sha256=\"{}\" directory=\"{}\" pom=\"{}\" coordinate=\"fixture:missing:1\">offline</maven-dependency-failure>",
            hash(&metadata),
            root.display(),
            pom.display()
        );
        let names = BTreeMap::from([(root.clone(), "app".into())]);
        let attempted = BTreeSet::from([root.clone()]);
        write("dependency-failure", &receipt);
        write(
            "inputs",
            "<maven-inputs format=\"maven-inputs-v2\" nonce=\"ok\" round=\"0\"/>",
        );
        assert_eq!(
            dependency_failure(
                &directory,
                "ok",
                1,
                &hash(&metadata),
                &root,
                &names,
                &attempted
            )
            .unwrap()
            .0
            .module,
            "app"
        );
        for changed in [
            receipt.replace("nonce=\"ok\"", "nonce=\"wrong\""),
            receipt.replace("round=\"1\"", "round=\"0\""),
            receipt.replace(&hash(&metadata), &"0".repeat(64)),
            receipt.replace("pom.xml", "other.xml"),
        ] {
            write("dependency-failure", &changed);
            assert!(
                dependency_failure(
                    &directory,
                    "ok",
                    1,
                    &hash(&metadata),
                    &root,
                    &names,
                    &attempted
                )
                .is_err()
            );
        }
        write("dependency-failure", &receipt);
        assert!(
            dependency_failure(
                &directory,
                "ok",
                1,
                &hash(&metadata),
                &root,
                &names,
                &BTreeSet::new()
            )
            .is_err()
        );
        write(
            "inputs",
            "<maven-inputs format=\"maven-inputs-v2\" nonce=\"ok\" round=\"1\"/>",
        );
        assert!(
            dependency_failure(
                &directory,
                "ok",
                1,
                &hash(&metadata),
                &root,
                &names,
                &attempted
            )
            .is_err()
        );
        write(
            "metadata",
            &metadata.replace("module directory", "module changed=\"true\" directory"),
        );
        assert!(
            dependency_failure(
                &directory,
                "ok",
                1,
                &hash(&metadata),
                &root,
                &names,
                &attempted
            )
            .is_err()
        );
    }

    #[test]
    fn dependency_failure_text_is_bounded_and_sanitized() {
        let value = failure_details(&format!(
            "\u{1b}[31moffline https://user:secret@repo/path\u{1b}[0m\nPicked up JAVA_TOOL_OPTIONS: secret\n[ERROR] Re-run Maven using -X\n{}",
            "汉".repeat(5000)
        ));
        assert!(value.starts_with("offline <repository>"));
        assert!(!value.contains("secret") && !value.contains("-X") && value.len() <= 4096);
    }

    #[test]
    fn terminal_markers_reject_duplicates_conflicts_and_out_of_order_rounds() {
        for events in [
            "CODEANA_MAVEN_INPUTS_READY:nonce\nCODEANA_MAVEN_INPUTS_READY:nonce\n",
            "CODEANA_MAVEN_INPUTS_READY:nonce\nCODEANA_MAVEN_CLASSPATH_READY:nonce:1\n",
            "CODEANA_MAVEN_INPUTS_READY:nonce\nCODEANA_MAVEN_CLASSPATH_READY:nonce:0\nCODEANA_MAVEN_DEPENDENCY_FAILED:nonce:0\n",
            "CODEANA_MAVEN_INPUTS_READY:nonce\nCODEANA_MAVEN_DEPENDENCY_FAILED:nonce:0\nCODEANA_MAVEN_DEPENDENCY_FAILED:nonce:0\n",
        ] {
            let mut command = Command::new("python3");
            command.args(["-c", "import sys; print(sys.argv[1],end='',flush=True); print('CODEANA_MAVEN_INPUTS_COMPLETE',flush=True); sys.exit(1)", events]);
            let mut session = Session::start(&mut command, "nonce", 5).unwrap();
            let cancel = AtomicBool::new(false);
            let result = session
                .ready(&cancel)
                .and_then(|_| session.classpath_ready(0, &cancel))
                .and_then(|_| session.finish(&cancel));
            assert!(
                result
                    .unwrap_err()
                    .to_string()
                    .contains("MAVEN_RESPONSE_SEQUENCE")
            );
        }
    }

    #[test]
    fn dependency_terminal_remains_visible_after_process_exit() {
        let mut command = Command::new("python3");
        command.args(["-c", "import sys; print('CODEANA_MAVEN_INPUTS_READY:nonce',flush=True); print('CODEANA_MAVEN_DEPENDENCY_FAILED:nonce:0',flush=True); print('CODEANA_MAVEN_INPUTS_COMPLETE',flush=True); sys.exit(1)"]);
        let mut session = Session::start(&mut command, "nonce", 5).unwrap();
        session.child.wait().unwrap();
        let cancel = AtomicBool::new(false);
        session.ready(&cancel).unwrap();
        assert!(!session.classpath_ready(0, &cancel).unwrap());
        session.finish(&cancel).unwrap();
    }

    #[test]
    fn timeout_after_metadata_never_becomes_source_only() {
        let mut command = Command::new("python3");
        command.args([
            "-c",
            "import time; print('CODEANA_MAVEN_INPUTS_READY:nonce',flush=True); time.sleep(30)",
        ]);
        let mut session = Session::start(&mut command, "nonce", 1).unwrap();
        let cancel = AtomicBool::new(false);
        session.ready(&cancel).unwrap();
        assert!(
            session
                .classpath_ready(0, &cancel)
                .unwrap_err()
                .to_string()
                .contains("TIMEOUT")
        );
    }
    #[test]
    fn modified_utf8_handles_nul_and_supplementary_paths() {
        let mut bytes = Vec::new();
        write_utf(&mut bytes, "A\0😀").unwrap();
        assert_eq!(
            bytes,
            vec![0, 9, 65, 192, 128, 237, 160, 189, 237, 184, 128]
        );
        assert!(write_utf(&mut Vec::new(), &"x".repeat(65536)).is_err());
    }
    #[test]
    fn prerequisites_follow_only_resolved_scope_graphs() {
        let root = tempfile::tempdir().unwrap();
        let project_root = root.path().canonicalize().unwrap();
        let mut names = BTreeMap::new();
        for id in ["app", "lib", "helper", "unused"] {
            let path = project_root.as_path().join(id);
            fs::create_dir(&path).unwrap();
            names.insert(path, id.to_owned());
        }
        let selected = BTreeSet::from([project_root.as_path().join("app")]);
        let xml = |lib_resolved: bool| {
            format!(
                "<maven-inputs><module directory=\"{0}/app\" resolved=\"true\"><main-dependencies><reactor directory=\"{0}/lib\"/></main-dependencies></module><module directory=\"{0}/lib\" resolved=\"{1}\"><main-dependencies><reactor directory=\"{0}/helper\"/></main-dependencies><test-dependencies><reactor directory=\"{0}/unused\"/></test-dependencies></module><module directory=\"{0}/helper\" resolved=\"false\"/><module directory=\"{0}/unused\" resolved=\"false\"/></maven-inputs>",
                project_root.as_path().display(),
                lib_resolved
            )
        };
        assert_eq!(
            prerequisite_modules(&xml(false), project_root.as_path(), &names, &selected, true)
                .unwrap(),
            BTreeSet::from([project_root.as_path().join("lib")])
        );
        assert_eq!(
            prerequisite_modules(&xml(true), project_root.as_path(), &names, &selected, true)
                .unwrap(),
            BTreeSet::from([project_root.as_path().join("helper")])
        );
        let wrong = xml(true).replace("/helper\"", "/missing\"");
        assert!(
            prerequisite_modules(&wrong, project_root.as_path(), &names, &selected, true).is_err()
        );
    }

    #[test]
    fn response_rejects_drift_identity_and_entities() {
        let tmp = tempfile::tempdir().unwrap();
        let raw = b"<maven-inputs format=\"maven-inputs-v2\" nonce=\"ok\"/>";
        fs::write(tmp.path().join("inputs.xml"), raw).unwrap();
        fs::write(tmp.path().join("inputs.sha256"), hash(raw)).unwrap();
        assert!(response(tmp.path(), "inputs", "ok").is_ok());
        assert!(response(tmp.path(), "inputs", "wrong").is_err());
        fs::write(tmp.path().join("inputs.sha256"), "bad").unwrap();
        assert!(response(tmp.path(), "inputs", "ok").is_err());
        assert!(parse("<!DOCTYPE x [<!ENTITY a 'b'>]><x/>").is_err());
        fs::OpenOptions::new()
            .write(true)
            .open(tmp.path().join("inputs.xml"))
            .unwrap()
            .set_len(MAX_RESPONSE + 1)
            .unwrap();
        assert!(
            response(tmp.path(), "inputs", "ok")
                .unwrap_err()
                .to_string()
                .contains("OVERSIZED")
        );
    }

    #[test]
    fn timeout_and_cancellation_reap_owned_process_and_log_readers() {
        for cancelled in [false, true] {
            let mut command = Command::new("/bin/sh");
            command.args(["-c", "sleep 30"]);
            let mut session = Session::start(&mut command, "nonce", 1).unwrap();
            let pid = session.child.id();
            let error = session.ready(&AtomicBool::new(cancelled)).unwrap_err();
            assert!(
                error
                    .to_string()
                    .contains(if cancelled { "CANCELLED" } else { "TIMEOUT" })
            );
            drop(session);
            assert_eq!(unsafe { libc::kill(pid as i32, 0) }, -1);
            assert_eq!(
                std::io::Error::last_os_error().raw_os_error(),
                Some(libc::ESRCH)
            );
        }
    }

    #[test]
    fn inherited_log_pipe_does_not_block_export_cleanup() {
        struct FixtureChild(i32);
        impl Drop for FixtureChild {
            fn drop(&mut self) {
                unsafe {
                    libc::kill(self.0, libc::SIGKILL);
                }
            }
        }
        let temporary = tempfile::tempdir().unwrap();
        let pid_file = temporary.path().join("descendant.pid");
        let mut command = Command::new("python3");
        command.args(["-c", "import pathlib,subprocess,sys,time; child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'],start_new_session=True); pathlib.Path(sys.argv[1]).write_text(str(child.pid)); print('CODEANA_MAVEN_INPUTS_READY:nonce',flush=True); print('CODEANA_MAVEN_INPUTS_COMPLETE',flush=True); time.sleep(.1); sys.exit(1)"]);
        command.arg(&pid_file);
        let mut session = Session::start(&mut command, "nonce", 10).unwrap();
        let cancel = AtomicBool::new(false);
        session.ready(&cancel).unwrap();
        let _fixture = FixtureChild(fs::read_to_string(pid_file).unwrap().parse().unwrap());
        let started = Instant::now();
        assert!(
            session
                .finish(&cancel)
                .unwrap_err()
                .to_string()
                .contains("MAVEN_CLEANUP_INCOMPLETE")
        );
        assert!(started.elapsed() < Duration::from_secs(3));
        assert!(session.readers.is_empty());
    }
}
