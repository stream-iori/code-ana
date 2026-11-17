//! Resolution uses the same read port in diagnostics and immutable capture transactions.
use super::*;

pub(crate) trait InputReader {
    fn enter_backend(&self, _backend: AnalysisBackend) -> Result<bool> {
        Ok(false)
    }
    fn enter_unit(&self, _unit: &AnalysisUnitId) -> Result<()> {
        Ok(())
    }
    fn leave_unit(&self) {}
    fn read(&self, path: &Path, limit: usize) -> Result<Vec<u8>>;
    /// Identity-only external controls must never enter the captured content store.
    fn identity(&self, path: &Path, limit: usize) -> Result<String>;
}
pub(crate) struct LiveInputs;
impl InputReader for LiveInputs {
    fn read(&self, path: &Path, limit: usize) -> Result<Vec<u8>> {
        bounded_read(path, limit)
    }
    fn identity(&self, path: &Path, limit: usize) -> Result<String> {
        Ok(hash(bounded_read(path, limit)?))
    }
}
impl InputReader for std::cell::RefCell<capture::CaptureReader> {
    fn enter_backend(&self, backend: AnalysisBackend) -> Result<bool> {
        self.borrow_mut().enter_backend(backend)
    }
    fn enter_unit(&self, unit: &AnalysisUnitId) -> Result<()> {
        self.borrow_mut().enter_unit(unit)
    }
    fn leave_unit(&self) {
        self.borrow_mut().leave_unit();
    }
    fn read(&self, path: &Path, limit: usize) -> Result<Vec<u8>> {
        self.borrow_mut().read(path, limit)
    }
    fn identity(&self, path: &Path, limit: usize) -> Result<String> {
        let mut reader = self.borrow_mut();
        reader.allow_file(path)?;
        reader.digest(path, limit)
    }
}
pub(crate) fn in_backend<T>(
    reader: &dyn InputReader,
    backend: AnalysisBackend,
    action: impl FnOnce() -> Result<T>,
) -> Result<T> {
    let entered = reader.enter_backend(backend)?;
    let result = action();
    if entered {
        reader.leave_unit();
    }
    result
}
