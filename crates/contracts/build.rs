fn main() -> Result<(), Box<dyn std::error::Error>> {
    tonic_prost_build::compile_protos("../../proto/codeana/v1/hello.proto")?;
    Ok(())
}
