use renamed::{Counter, Service};
fn dynamic(service: &dyn Service) -> u32 { service.value() }
fn main() {
    renamed::answer();
    renamed::generated();
    let counter = Counter::new();
    dynamic(&counter);
    missing();
}
