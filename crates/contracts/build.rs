fn main() -> Result<(), Box<dyn std::error::Error>> {
    let files = ["hello", "task", "worker"].map(|s| format!("../../proto/codeana/v1/{s}.proto"));
    tonic_prost_build::configure()
        .type_attribute(".", "#[derive(serde::Serialize, serde::Deserialize)]")
        .compile_protos(&files, &["../../proto".to_string()])?;
    for file in files {
        println!("cargo:rerun-if-changed={file}");
    }
    Ok(())
}
