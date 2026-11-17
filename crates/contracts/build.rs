fn main() -> Result<(), Box<dyn std::error::Error>> {
    let files =
        ["hello", "task", "worker", "graph"].map(|s| format!("../../proto/codeana/v1/{s}.proto"));
    tonic_prost_build::configure()
        .boxed("codeana.worker.v1.BuildContext.details.java")
        .boxed("codeana.worker.v1.Discover.config.java")
        .boxed("codeana.worker.v1.Discover.config.rust")
        .boxed("codeana.worker.v1.Discover.config.python")
        .boxed("codeana.worker.v1.Discover.config.typescript")
        .boxed("codeana.worker.v1.Discover.analysis_grant")
        .type_attribute(".", "#[derive(serde::Serialize, serde::Deserialize)]")
        .field_attribute(
            "codeana.worker.v1.SourceUnit.auxiliary",
            "#[serde(default)]",
        )
        .field_attribute(
            "codeana.worker.v1.JavaContext.prerequisite",
            "#[serde(default)]",
        )
        .field_attribute(
            "codeana.v1.Evidence.resource_usage",
            "#[serde(default, skip_serializing_if = \"Option::is_none\")]",
        )
        .compile_protos(&files, &["../../proto".to_string()])?;
    for file in files {
        println!("cargo:rerun-if-changed={file}");
    }
    Ok(())
}
