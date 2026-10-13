//! Exercise the daemon's index engine, supervisor and publication without a listening socket.
use anyhow::{Result, ensure};
use codeana_application::Engine;
use codeana_model::{FactFilter, FactKind, GraphReader, Request, new_id};
use std::{path::PathBuf, time::Duration};

#[tokio::main]
async fn main() -> Result<()> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()?;
    let task = tempfile::tempdir()?;
    let home = task.path().join("home");
    let project = task.path().join("project");
    std::fs::create_dir(&home)?;
    std::fs::create_dir_all(project.join(".code-ana"))?;
    std::fs::create_dir_all(project.join("src"))?;
    let jdk = std::env::var("CODEANA_JAVA8_HOME").unwrap_or_else(|_| {
        format!(
            "{}/.sdkman/candidates/java/8.0.492-zulu",
            std::env::var("HOME").expect("HOME")
        )
    });
    std::fs::write(
        home.join("config.toml"),
        format!(
            "schema_version=1\nkind=\"user\"\n[limits]\nmax_workers=1\nmax_worker_memory_bytes=1073741824\n[toolchains.java8]\nlanguage=\"java\"\nhome={}\n",
            serde_json::to_string(&jdk)?
        ),
    )?;
    std::fs::copy(
        root.join("tests/fixtures/java-native/base/.code-ana/config.toml"),
        project.join(".code-ana/config.toml"),
    )?;
    let engine = Engine::new(&home, None)?;
    let mut previous = Vec::new();
    let mut first_revision = String::new();
    for round in 0..3 {
        let value = round.min(1);
        std::fs::write(
            project.join("src/Use.java"),
            format!(
                "class Use {{ static {{ int local={value}; java.util.function.IntSupplier fn=()->local; fn.getAsInt(); }} int run(){{ return {value}; }} }}"
            ),
        )?;
        let id = engine.submit(Request {
            request_id: new_id("smoke"),
            project: project.to_string_lossy().into(),
            operation: "index".into(),
            full: round != 1,
            deadline_seconds: 150,
            ..Default::default()
        })?;
        let terminal = tokio::time::timeout(Duration::from_secs(160), async {
            loop {
                if let Some(result) = engine.store.task(&id)?.1 {
                    break Ok::<_, anyhow::Error>(result);
                }
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
        })
        .await??;
        ensure!(
            terminal.exit_code == 0 && terminal.publication == "published",
            "ENGINE_INDEX: {}",
            serde_json::to_string(&terminal)?
        );
        let reader = engine.store.read(&terminal.revision)?;
        let facts = reader
            .select(&FactFilter::default())
            .map_err(anyhow::Error::msg)?;
        let locals: std::collections::BTreeSet<_> = facts
            .iter()
            .filter(|f| f.kind == FactKind::Declaration && f.signature.contains("#local:"))
            .map(|f| &f.id)
            .collect();
        ensure!(locals.len() >= 2, "LOCAL_DECLARATIONS");
        ensure!(
            facts
                .iter()
                .any(|f| f.kind == FactKind::Reference && locals.contains(&f.target)),
            "LOCAL_NAVIGATION"
        );
        if round == 0 {
            first_revision = terminal.revision.clone();
        }
        if round == 2 {
            ensure!(facts == previous, "DELTA_FULL_DIFFERENCE");
        }
        println!(
            "round={round} publication={} facts={} revision={}",
            terminal.publication,
            facts.len(),
            terminal.revision
        );
        previous = facts;
    }
    ensure!(
        engine.store.read(&first_revision).is_ok(),
        "OLD_REVISION_LOST"
    );
    println!(
        "daemon index engine: supervisor, Fact IR validation, publication and delta/full equality passed; HTTP endpoint and capacity not certified"
    );
    drop(engine);
    Ok(())
}
