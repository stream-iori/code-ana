//! The foreign engine's integer status is decoded once at the ABI boundary.
use anyhow::Result;
use codeana_model::{DomainError, ErrorCode};
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunState {
    Running,
    Complete,
    Failed,
}
impl TryFrom<i32> for RunState {
    type Error = DomainError;
    fn try_from(value: i32) -> std::result::Result<Self, Self::Error> {
        match value {
            0 => Ok(Self::Running),
            1 => Ok(Self::Complete),
            -1 => Ok(Self::Failed),
            _ => Err(DomainError::new(
                ErrorCode::NativeNotificationProtocol,
                format!("unknown engine state {value}"),
            )),
        }
    }
}
pub fn decode(value: i32) -> Result<RunState> {
    Ok(value.try_into()?)
}
