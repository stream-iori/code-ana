use codeana_contracts::v1::{HelloRequest, hello_service_client::HelloServiceClient};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = std::env::args().skip(1);
    if args.next().as_deref() != Some("hello") {
        eprintln!("usage: codeana-cli hello [name]");
        std::process::exit(2);
    }
    let name = args.next().unwrap_or_else(|| "world".to_owned());
    if args.next().is_some() {
        eprintln!("usage: codeana-cli hello [name]");
        std::process::exit(2);
    }
    let port = match std::env::var("CODEANA_PORT") {
        Ok(value) => value.parse::<u16>()?,
        Err(std::env::VarError::NotPresent) => 50051,
        Err(error) => return Err(error.into()),
    };
    let mut client = HelloServiceClient::connect(format!("http://127.0.0.1:{port}")).await?;
    let reply = client.say_hello(HelloRequest { name }).await?;
    println!("{}", reply.into_inner().message);
    Ok(())
}
