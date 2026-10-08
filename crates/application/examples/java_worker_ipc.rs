//! Native product IPC acceptance without a listening daemon or target build.
use anyhow::{Result, ensure};
use codeana_contracts::{convert, envelope, fact_from_wire, read_frame, worker as w, write_frame};
use codeana_model::*;
use std::{os::fd::AsRawFd, path::PathBuf};
use tokio::{net::UnixStream, process::Command};
use w::envelope::Body;

#[tokio::main]
async fn main() -> Result<()> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()?;
    let binary = root.join("target/debug/codeana-java-worker");
    let library = root.join("lang-java-adapotr/target/native/libcodeana_java.dylib");
    let jdk = std::env::var("CODEANA_JAVA8_HOME").unwrap_or_else(|_| {
        format!(
            "{}/.sdkman/candidates/java/8.0.492-zulu",
            std::env::var("HOME").expect("HOME")
        )
    });
    let scratch = tempfile::tempdir()?;
    let source = scratch.path().join("Use.java");
    let definitions = [
        (
            "RestController.java",
            "package org.springframework.web.bind.annotation; public @interface RestController {}",
        ),
        (
            "GetMapping.java",
            "package org.springframework.web.bind.annotation; public @interface GetMapping { String[] value() default {}; }",
        ),
        (
            "PostConstruct.java",
            "package javax.annotation; public @interface PostConstruct {}",
        ),
        (
            "SofaService.java",
            "package com.alipay.sofa.runtime.api.annotation; public @interface SofaService { Class<?> interfaceType() default void.class; String uniqueId() default \"\"; }",
        ),
        ("Getter.java", "package lombok; public @interface Getter {}"),
    ];
    for (name, text) in &definitions {
        std::fs::write(scratch.path().join(name), text)?;
    }
    let xml = scratch.path().join("beans.xml");
    std::fs::write(&xml, "<beans><bean id=\"use\" class=\"Use\"/></beans>")?;
    let mut prior = Vec::new();
    let (parent, worker) = std::os::unix::net::UnixStream::pair()?;
    parent.set_nonblocking(true)?;
    let fd = worker.as_raw_fd();
    let mut command = Command::new(binary);
    command
        .arg(library)
        .args(["--fd", "3"])
        .kill_on_drop(true)
        .process_group(0)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null());
    // The descriptor is a test-owned socket pair, identical to the product supervisor boundary.
    unsafe {
        command.pre_exec(move || {
            if fd != 3 && libc::dup2(fd, 3) < 0 {
                return Err(std::io::Error::last_os_error());
            }
            if libc::fcntl(3, libc::F_SETFD, 0) < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let mut child = command.spawn()?;
    drop(worker);
    let mut ipc = UnixStream::from_std(parent)?;
    let attempt = "native-ipc-fixture";
    write_frame(
        &mut ipc,
        &envelope(
            attempt,
            Body::Hello(w::Hello {
                protocol: WORKER_PROTOCOL_VERSION,
                fact_ir: VERSION,
                producer: "acceptance".into(),
            }),
        ),
    )
    .await?;
    ensure!(
        matches!(read_frame(&mut ipc).await?.body, Some(Body::Hello(h)) if h.protocol == WORKER_PROTOCOL_VERSION),
        "HELLO"
    );
    for round in 0..3 {
        std::fs::write(
            &source,
            format!(
                "@lombok.Getter @org.springframework.web.bind.annotation.RestController @com.alipay.sofa.runtime.api.annotation.SofaService(interfaceType=Runnable.class) class Use implements Runnable {{ int value; @javax.annotation.PostConstruct @org.springframework.web.bind.annotation.GetMapping(\"/native\") public void run(){{ value++; System.out.println(\"round{round}\"+this.getValue()); }} }}"
            ),
        )?;
        let unit = SourceUnit {
            auxiliary: false,
            path: "Use.java".into(),
            hash: hash_file(&source)?,
            size: source.metadata()?.len(),
            module: "app".into(),
            world: "world-app".into(),
            scope: "MAIN".into(),
            language: "java".into(),
        };
        let mut units = vec![unit.clone()];
        for (name, _) in &definitions {
            let path = scratch.path().join(name);
            units.push(SourceUnit {
                auxiliary: false,
                path: (*name).into(),
                hash: hash_file(&path)?,
                size: path.metadata()?.len(),
                ..unit.clone()
            });
        }
        let resource = SourceUnit {
            auxiliary: false,
            path: "beans.xml".into(),
            hash: hash_file(&xml)?,
            size: xml.metadata()?.len(),
            world: "resources".into(),
            language: "xml".into(),
            ..unit.clone()
        };
        let mut files = units.clone();
        files.push(resource.clone());
        let manifest = Manifest { files };
        let mut config = w::JavaConfig {
            java_home: jdk.clone(),
            bridge: root
                .join("lang-java-adapotr/target/compiler-bridge.jar")
                .to_string_lossy()
                .into(),
            scratch: scratch.path().to_string_lossy().into(),
            parallelism: 1,
            compiler_heap_bytes: 128 * 1024 * 1024,
            cache_root: scratch.path().join("cache").to_string_lossy().into(),
            signature_cache: true,
            contexts: vec![w::JavaContext {
                id: "app@MAIN".into(),
                module: "app".into(),
                world: unit.world.clone(),
                scope: "MAIN".into(),
                digest: unit.hash.clone(),
                lombok: "model".into(),
                spring: true,
                spring_xml: true,
                sofa: true,
                resources: vec![w::JavaSource {
                    unit: Some(convert(&resource)?),
                    input: xml.to_string_lossy().into(),
                    publish: false,
                }],
                sources: units
                    .iter()
                    .map(|u| {
                        Ok(w::JavaSource {
                            unit: Some(convert(u)?),
                            input: scratch.path().join(&u.path).to_string_lossy().into(),
                            publish: true,
                        })
                    })
                    .collect::<Result<_>>()?,
                ..Default::default()
            }],
            ..Default::default()
        };
        codeana_contracts::java::compact(&mut config)?;
        write_frame(
            &mut ipc,
            &envelope(
                attempt,
                Body::Discover(w::Discover {
                    manifest: Some(convert(&manifest)?),
                    java: Some(config),
                }),
            ),
        )
        .await?;
        ensure!(
            matches!(read_frame(&mut ipc).await?.body, Some(Body::Context(_))),
            "DISCOVER"
        );
        for (seq, facts) in prior.chunks(64).enumerate() {
            write_frame(
                &mut ipc,
                &envelope(
                    attempt,
                    Body::BaselineChunk(w::BaselineChunk {
                        seq: seq as u64,
                        facts: facts
                            .iter()
                            .map(codeana_contracts::fact_to_wire)
                            .collect::<Result<_>>()?,
                    }),
                ),
            )
            .await?;
        }
        write_frame(
            &mut ipc,
            &envelope(
                attempt,
                Body::BaselineEnd(w::BaselineEnd {
                    count: prior.len() as u64,
                    checksum: digest(&prior),
                }),
            ),
        )
        .await?;
        write_frame(
            &mut ipc,
            &envelope(
                attempt,
                Body::Analyze(w::Analyze {
                    manifest: Some(convert(&manifest)?),
                    profile: "native-ipc-profile".into(),
                    baseline: if round == 0 {
                        String::new()
                    } else {
                        "previous".into()
                    },
                    full: round == 0,
                    baseline_facts: vec![],
                }),
            ),
        )
        .await?;
        let body = tokio::time::timeout(std::time::Duration::from_secs(120), read_frame(&mut ipc))
            .await??
            .body;
        let begin = match body {
            Some(Body::Begin(begin)) => begin,
            other => anyhow::bail!("RESULT_BEGIN: {other:?}"),
        };
        let mut facts = Vec::new();
        let mut sequence = 0;
        loop {
            let body = read_frame(&mut ipc).await?.body;
            match body {
                Some(Body::Chunk(chunk)) => {
                    ensure!(chunk.seq == sequence, "CHUNK_SEQUENCE");
                    sequence += 1;
                    facts.extend(
                        chunk
                            .facts
                            .into_iter()
                            .map(fact_from_wire)
                            .collect::<Result<Vec<_>>>()?,
                    );
                }
                Some(Body::End(end)) => {
                    ensure!(
                        end.chunks == sequence
                            && end.count == facts.len() as u64
                            && end.checksum == digest(&facts),
                        "RESULT_CHECKSUM"
                    );
                    break;
                }
                other => anyhow::bail!("RESULT_CHUNK: {other:?}"),
            }
        }
        let result = AnalysisResult {
            mode: begin.mode,
            baseline: begin.baseline,
            manifest: begin.manifest,
            profile: begin.profile,
            producer: begin.producer,
            replacements: begin
                .replacements
                .iter()
                .map(convert)
                .collect::<Result<_>>()?,
            facts,
        };
        result
            .validate(&manifest, &prior)
            .map_err(anyhow::Error::msg)?;
        ensure!(
            result.facts.iter().any(|f| f.relation == "writes"),
            "FIELD_WRITE_MISSING"
        );
        ensure!(
            result.facts.iter().any(|f| f.relation == "java.syntax"),
            "SYNTAX_MISSING"
        );
        let owners: std::collections::BTreeSet<_> = result.replacements.iter().cloned().collect();
        prior.retain(|f| !owners.contains(&f.owner));
        prior.extend(result.facts);
        for namespace in [
            "java.spring.mvc",
            "java.spring.handler",
            "java.sofa",
            "java.lombok",
        ] {
            ensure!(
                prior.iter().any(|f| f.relation == namespace),
                "FRAMEWORK_FACET_MISSING: {namespace}"
            );
        }
        ensure!(
            prior
                .iter()
                .any(|f| f.signature == "Use#getValue()" && !f.declaration_resolved),
            "MODEL_OVERLAY_MISSING"
        );
        ensure!(
            prior.iter().any(|f| f.kind == FactKind::CallTarget
                && f.status == "possible"
                && f.origin == "lombok_model"),
            "MODEL_HYPOTHESIS_MISSING"
        );
        ensure!(
            prior.iter().any(|f| f.relation == "java.lombok"
                && f.attributes
                    .get("upstream_producer")
                    .and_then(|v| v.as_str())
                    == Some("lombok-model")),
            "LOMBOK_CAPABILITY_MISSING"
        );
        ensure!(
            prior
                .iter()
                .any(|f| f.source == "beans.xml" && f.relation == "java.bean"),
            "RESOURCE_ANALYSIS_MISSING"
        );
        write_frame(
            &mut ipc,
            &envelope(
                attempt,
                Body::Ack(w::Ack {
                    revision: format!("round-{round}"),
                }),
            ),
        )
        .await?;
        ensure!(
            matches!(read_frame(&mut ipc).await?.body, Some(Body::Acked(_))),
            "PUBLISH_ACK"
        );
    }
    write_frame(&mut ipc, &envelope(attempt, Body::Cancel(w::Cancel {}))).await?;
    let status = tokio::time::timeout(std::time::Duration::from_secs(5), child.wait()).await??;
    ensure!(status.success(), "WORKER_EXIT: {status}");
    ensure!(
        !std::fs::read_dir(scratch.path())?
            .any(|entry| entry
                .is_ok_and(|e| e.file_name().to_string_lossy().starts_with("compiler-"))),
        "COMPILER_LEAK"
    );
    println!(
        "native product IPC: protocol13, shared inputs, full/delta, checksums, fact validation, Acked and hot host reuse passed"
    );
    Ok(())
}
