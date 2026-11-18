use anyhow::{Context, Result, ensure};
use codeana_contracts::{
    bundle::{BackendIdentity, BundleManifest},
    protobuf::typescript::v1 as p,
};
use std::path::{Path, PathBuf};

pub struct Sdk {
    pub root: PathBuf,
    pub manifest: BundleManifest,
    pub runtime: p::RuntimeIdentity,
}

impl Sdk {
    pub fn semantic_identity(&self) -> codeana_model::typescript::CompilerIdentity {
        codeana_model::typescript::CompilerIdentity {
            adapter: self.runtime.adapter.clone(),
            upstream_commit: self.runtime.upstream_commit.clone(),
            typescript_commit: self.runtime.typescript_commit.clone(),
            compiler_version: self.runtime.compiler_version.clone(),
            go_version: self.runtime.go_version.clone(),
            library_sha256: self.manifest.files["libcodeana_typescript.dylib"].clone(),
            header_sha256: self.manifest.files["codeana_ts.h"].clone(),
            standard_library_digest: self.runtime.standard_library_digest.clone(),
            analysis_proto_sha256: self.runtime.analysis_proto_sha256.clone(),
            graph_proto_sha256: self.runtime.graph_proto_sha256.clone(),
        }
    }

    pub fn load(root: &Path) -> Result<Self> {
        Self::load_with_fixture(root, false)
    }
    pub fn load_with_fixture(root: &Path, allow_fixture: bool) -> Result<Self> {
        let root = root.canonicalize()?;
        let metadata = root.join("manifest.json");
        ensure!(
            std::fs::metadata(&metadata)?.len() <= 1 << 20,
            "SDK_MANIFEST_BUDGET"
        );
        let manifest = BundleManifest::parse(&std::fs::read(metadata)?)?;
        manifest.validate_protocol(codeana_model::AnalysisBackend::TypeScript)?;
        ensure!(manifest.platform_matches(), "SDK_PLATFORM_MISMATCH");
        let BackendIdentity::TypeScript(identity) = &manifest.backend else {
            anyhow::bail!("SDK_BACKEND_MISMATCH")
        };
        let fixture = identity
            .build_options
            .iter()
            .any(|value| value == "codeana-abi-test-fixture")
            || manifest.adapter.ends_with("-abi-fixture");
        ensure!(
            !fixture || allow_fixture,
            "SDK_DEVELOPMENT_FIXTURE_REJECTED"
        );
        ensure!(
            identity.abi == 1
                && identity.analysis_request == codeana_model::typescript::ANALYSIS_VERSION,
            "SDK_TYPESCRIPT_PROTOCOL_MISMATCH"
        );
        let lock: serde_json::Value = serde_json::from_str(include_str!(
            "../../../lang-typescript-adaptor/upstream.lock.json"
        ))?;
        ensure!(
            identity.upstream_commit == lock["commit"].as_str().context("UPSTREAM_COMMIT")?
                && identity.typescript_commit
                    == lock["typescript"]["commit"]
                        .as_str()
                        .context("TYPESCRIPT_COMMIT")?
                && identity.go_version
                    == lock["go"]["distribution"]["version"]
                        .as_str()
                        .context("GO_VERSION")?,
            "SDK_TYPESCRIPT_SOURCE_MISMATCH"
        );
        let expected_adapter = format!(
            "typescript-go-{}-codeana-graph{}-tsabi1-inputs{}{}",
            &identity.upstream_commit[..12],
            codeana_model::VERSION,
            codeana_model::typescript::ADAPTER_REVISION,
            if fixture { "-abi-fixture" } else { "" }
        );
        ensure!(
            manifest.adapter == expected_adapter,
            "SDK_TYPESCRIPT_ADAPTER_MISMATCH"
        );
        ensure!(
            identity.analysis_proto_sha256
                == codeana_model::hash(include_bytes!(
                    "../../../proto/codeana/typescript/v1/analysis.proto"
                ))
                && identity.graph_proto_sha256
                    == codeana_model::hash(include_bytes!("../../../proto/codeana/v1/graph.proto")),
            "SDK_PROTO_SCHEMA_MISMATCH"
        );
        for required in [
            "libcodeana_typescript.dylib",
            "codeana_ts.h",
            "codeana/typescript/v1/analysis.proto",
            "codeana/v1/graph.proto",
        ] {
            ensure!(
                manifest.files.contains_key(required),
                "SDK_COMPONENT_MISSING: {required}"
            );
        }
        ensure!(
            manifest.files["codeana_ts.h"] == identity.header_sha256
                && manifest.files["codeana/typescript/v1/analysis.proto"]
                    == identity.analysis_proto_sha256
                && manifest.files["codeana/v1/graph.proto"] == identity.graph_proto_sha256,
            "SDK_COMPONENT_IDENTITY_MISMATCH"
        );
        for (name, digest) in &identity.standard_library {
            ensure!(
                manifest.files.get(name) == Some(digest),
                "SDK_STANDARD_LIBRARY_MISMATCH"
            );
        }
        for (name, expected) in &manifest.files {
            let mut path = root.clone();
            for component in Path::new(name).components() {
                let std::path::Component::Normal(part) = component else {
                    anyhow::bail!("SDK_UNSAFE_PATH")
                };
                path.push(part);
                ensure!(
                    !std::fs::symlink_metadata(&path)?.file_type().is_symlink(),
                    "SDK_UNSAFE_PATH: {name}"
                );
            }
            ensure!(
                std::fs::metadata(&path)?.is_file()
                    && codeana_model::hash_file(&path)? == *expected,
                "SDK_HASH_MISMATCH: {name}"
            );
        }
        let runtime = p::RuntimeIdentity {
            abi: identity.abi,
            analysis_request: identity.analysis_request,
            worker_protocol: manifest.worker_protocol,
            graph_model: manifest.fact_ir,
            graph_schema: manifest.graph_schema,
            adapter: manifest.adapter.clone(),
            upstream_commit: identity.upstream_commit.clone(),
            typescript_commit: identity.typescript_commit.clone(),
            compiler_version: identity.compiler_version.clone(),
            go_version: identity.go_version.clone(),
            standard_library_digest: codeana_model::hash(serde_json::to_vec(
                &identity.standard_library,
            )?),
            analysis_proto_sha256: identity.analysis_proto_sha256.clone(),
            graph_proto_sha256: identity.graph_proto_sha256.clone(),
        };
        Ok(Self {
            root,
            manifest,
            runtime,
        })
    }
}
