//! Bounded metadata subprocesses and sanitized process output.
use anyhow::{Result, ensure};
use std::{
    fs,
    io::Read,
    os::unix::process::{CommandExt, ExitStatusExt},
    path::Path,
    process::{Command, Stdio},
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

struct ChildGroup(std::process::Child);
impl Drop for ChildGroup {
    fn drop(&mut self) {
        // Retire descendants too, including inherited log descriptors.
        unsafe {
            libc::kill(-(self.0.id() as i32), libc::SIGKILL);
        }
        let _ = self.0.wait();
    }
}
pub(super) fn run(
    command: &mut Command,
    log: &Path,
    cancel: &AtomicBool,
    timeout: u64,
) -> Result<bool> {
    command
        .env_remove("PYTHONPATH")
        .env_remove("PYTHONHOME")
        .env_remove("PYTHONSTARTUP");
    command
        .process_group(0)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .stdin(Stdio::null());
    let child = match command.spawn() {
        Ok(child) => child,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            fs::write(log, "INPUT_TOOL_UNAVAILABLE\n")?;
            return Ok(false);
        }
        Err(error) => return Err(error.into()),
    };
    let mut child = ChildGroup(child);
    let total = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let readers = [
        Box::new(child.0.stdout.take().unwrap()) as Box<dyn Read + Send>,
        Box::new(child.0.stderr.take().unwrap()),
    ]
    .map(|mut pipe| {
        let total = total.clone();
        thread::spawn(move || {
            let mut kept = Vec::new();
            let mut buffer = [0; 8192];
            while let Ok(count) = pipe.read(&mut buffer) {
                if count == 0 {
                    break;
                }
                total.fetch_add(count, Ordering::Relaxed);
                if kept.len() < 65536 {
                    kept.extend_from_slice(&buffer[..count.min(65536 - kept.len())]);
                }
            }
            kept
        })
    });
    let started = Instant::now();
    let outcome = loop {
        if cancel.load(Ordering::Acquire) {
            break Err(anyhow::anyhow!("CANCELLED"));
        }
        if started.elapsed().as_secs() >= timeout {
            break Err(anyhow::anyhow!("INPUT_PREPARE_TIMEOUT"));
        }
        if total.load(Ordering::Relaxed) > 32 << 20 {
            break Err(anyhow::anyhow!("INPUT_PREPARE_LOG_BUDGET"));
        }
        match child.0.try_wait() {
            Ok(Some(status)) => break Ok(status),
            Ok(None) => thread::sleep(Duration::from_millis(25)),
            Err(error) => break Err(error.into()),
        }
    };
    drop(child);
    let mut text = String::new();
    for reader in readers {
        text.push_str(&String::from_utf8_lossy(
            &reader
                .join()
                .map_err(|_| anyhow::anyhow!("INPUT_LOG_READER"))?,
        ));
    }
    let text = failure_details(&text);
    fs::write(log, &text)?;
    let status = outcome?;
    ensure!(status.signal().is_none(), "INPUT_TOOL_CRASHED: {status}");
    if !status.success() {
        ensure!(
            ![
                "TimeoutExpired",
                "RUST_INPUT_TOOL_CRASHED",
                "RUST_INPUT_EXPORT_BUDGET",
                "RUST_INPUT_POLICY_INVALID",
                "PYTHON_SHARED_ENVIRONMENT_CONFLICT",
                "PYTHON_SOURCE_OWNER_CONFLICT",
                "PYTHON_TARGET_UNSUPPORTED",
                "PYTHON_CONTROL_SYMLINK",
                "PYTHON_ROOT_SYMLINK"
            ]
            .iter()
            .any(|code| text.contains(code)),
            "INPUT_PROVIDER_INVALID: {text}"
        );
    }
    Ok(status.success())
}

/// Keep bounded actionable text without repository URLs or JVM option echoes.
pub(crate) fn failure_details(text: &str) -> String {
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

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancellation_reaps_the_metadata_process_group() {
        let temp = tempfile::tempdir().unwrap();
        let pid = temp.path().join("pid");
        let log = temp.path().join("prepare.log");
        let cancel = Arc::new(AtomicBool::new(false));
        let flag = cancel.clone();
        let ready = pid.clone();
        let signal = thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(3);
            while !ready.is_file() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(10));
            }
            flag.store(true, Ordering::Release);
        });
        let mut command = Command::new("/bin/sh");
        command
            .args(["-c", "echo $$ > \"$1\"; sleep 20", "fixture"])
            .arg(&pid);
        let started = Instant::now();
        assert!(
            run(&mut command, &log, &cancel, 5)
                .unwrap_err()
                .to_string()
                .contains("CANCELLED")
        );
        signal.join().unwrap();
        assert!(started.elapsed() < Duration::from_secs(5));
        let pid: i32 = fs::read_to_string(pid).unwrap().trim().parse().unwrap();
        assert_eq!(unsafe { libc::kill(pid, 0) }, -1);
    }
    #[test]
    fn ordinary_tool_failure_is_distinct_from_crash_and_timeout() {
        let temp = tempfile::tempdir().unwrap();
        let log = temp.path().join("prepare.log");
        let cancel = AtomicBool::new(false);
        assert!(
            !run(
                Command::new("/bin/sh").args(["-c", "echo missing-cache >&2; exit 1"]),
                &log,
                &cancel,
                5
            )
            .unwrap()
        );
        assert!(fs::read_to_string(&log).unwrap().contains("missing-cache"));
        assert!(
            run(
                Command::new("/bin/sh").args(["-c", "kill -KILL $$"]),
                &log,
                &cancel,
                5
            )
            .unwrap_err()
            .to_string()
            .contains("INPUT_TOOL_CRASHED")
        );
        assert!(
            run(
                Command::new("/bin/sh").args(["-c", "sleep 20"]),
                &log,
                &cancel,
                1
            )
            .unwrap_err()
            .to_string()
            .contains("TIMEOUT")
        );
    }
}
