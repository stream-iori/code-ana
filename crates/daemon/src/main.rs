use codeana_contracts::v1::{
    HelloReply, HelloRequest, hello_service_server::HelloService,
    hello_service_server::HelloServiceServer,
};
use tonic::{Request, Response, Status, transport::Server};

#[derive(Default)]
struct Hello;

#[tonic::async_trait]
impl HelloService for Hello {
    async fn say_hello(
        &self,
        request: Request<HelloRequest>,
    ) -> Result<Response<HelloReply>, Status> {
        let name = request.into_inner().name;
        let name = if name.is_empty() { "world" } else { &name };
        Ok(Response::new(HelloReply {
            message: format!("Hello, {name}!"),
        }))
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let port = match std::env::var("CODEANA_PORT") {
        Ok(value) => value.parse::<u16>()?,
        Err(std::env::VarError::NotPresent) => 50051,
        Err(error) => return Err(error.into()),
    };
    let address = format!("127.0.0.1:{port}").parse()?;
    println!("codeana daemon listening on {address}");
    Server::builder()
        .add_service(HelloServiceServer::new(Hello))
        .serve(address)
        .await?;
    Ok(())
}
