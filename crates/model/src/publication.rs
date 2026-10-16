//! One task owns the cancellation/commit boundary; storage owns the durable outcome.
use crate::{DomainError, ErrorCode};
use std::sync::atomic::{AtomicU8, Ordering};

#[derive(Default)]
pub struct PublicationFence(AtomicU8);
impl PublicationFence {
    /// Once committing, cancellation may stop post-processing but cannot revoke the commit.
    pub fn cancel(&self) {
        let _ = self
            .0
            .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire);
    }
    pub fn check(&self) -> Result<(), DomainError> {
        if self.0.load(Ordering::Acquire) == 1 {
            Err(DomainError::new(ErrorCode::Cancelled, ""))
        } else {
            Ok(())
        }
    }
    pub fn begin_commit(&self) -> Result<(), DomainError> {
        self.0
            .compare_exchange(0, 2, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ())
            .map_err(|state| {
                DomainError::new(
                    if state == 1 {
                        ErrorCode::Cancelled
                    } else {
                        ErrorCode::InvalidTaskState
                    },
                    "publication fence",
                )
            })
    }
    pub fn committed(&self) {
        self.0.store(3, Ordering::Release);
    }
}
