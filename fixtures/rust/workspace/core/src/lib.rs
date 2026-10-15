pub mod api;
pub use api::answer;
pub trait Service { fn value(&self) -> u32; }
pub struct Counter { pub value: u32 }
impl Service for Counter { fn value(&self) -> u32 { self.value } }
impl Counter { pub fn new() -> Self { Self { value: answer() } } }
macro_rules! generate { ($name:ident) => { pub fn $name() -> u32 { 7 } }; }
generate!(generated);
#[cfg(feature = "extra")]
pub fn feature_only() {}
#[cfg(test)]
fn test_only() {}
