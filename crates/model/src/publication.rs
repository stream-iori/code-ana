//! One task owns the cancellation/commit boundary; storage owns the durable outcome.
use crate::{DomainError, ErrorCode};
use std::{
    sync::{
        Mutex,
        atomic::{AtomicU8, Ordering},
    },
    time::Instant,
};

#[derive(Default)]
pub struct PublicationFence {
    state: AtomicU8,
    deadline: Mutex<Option<Instant>>,
}
impl PublicationFence {
    /// Once committing, cancellation may stop post-processing but cannot revoke the commit.
    pub fn cancel(&self) {
        let _ = self
            .state
            .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Acquire);
    }
    pub fn tighten_deadline(&self, deadline: Instant) {
        let mut current = self.deadline.lock().unwrap();
        *current = Some(current.map_or(deadline, |old| old.min(deadline)));
    }
    fn check_deadline(&self, deadline: Option<Instant>) {
        if deadline.is_some_and(|d| Instant::now() >= d) {
            let _ = self
                .state
                .compare_exchange(0, 4, Ordering::AcqRel, Ordering::Acquire);
        }
    }
    fn failure(state: u8) -> Option<DomainError> {
        match state {
            1 => Some(DomainError::new(ErrorCode::Cancelled, "publication fence")),
            4 => Some(DomainError::new(ErrorCode::Timeout, "publication fence")),
            _ => None,
        }
    }
    pub fn check(&self) -> Result<(), DomainError> {
        self.check_deadline(*self.deadline.lock().unwrap());
        Self::failure(self.state.load(Ordering::Acquire)).map_or(Ok(()), Err)
    }
    pub fn begin_commit(&self) -> Result<(), DomainError> {
        let deadline = self.deadline.lock().unwrap();
        self.check_deadline(*deadline);
        self.state
            .compare_exchange(0, 2, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ())
            .map_err(|state| {
                Self::failure(state).unwrap_or_else(|| {
                    DomainError::new(ErrorCode::InvalidTaskState, "publication fence")
                })
            })
    }
    pub fn committed(&self) {
        self.state.store(3, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn expired_deadline_blocks_commit_with_timeout_identity() {
        let fence = PublicationFence::default();
        fence.tighten_deadline(Instant::now());
        assert_eq!(fence.begin_commit().unwrap_err().code, ErrorCode::Timeout);
        fence.cancel();
        assert_eq!(fence.check().unwrap_err().code, ErrorCode::Timeout);
    }
    #[test]
    fn deadline_and_cancellation_cannot_revoke_an_admitted_commit() {
        let fence = PublicationFence::default();
        fence.begin_commit().unwrap();
        fence.tighten_deadline(Instant::now());
        fence.cancel();
        fence.check().unwrap();
        fence.committed();
        fence.check().unwrap();
    }
    #[test]
    fn first_cancel_cause_is_preserved_when_deadline_later_expires() {
        let fence = PublicationFence::default();
        fence.cancel();
        fence.tighten_deadline(Instant::now());
        assert_eq!(fence.begin_commit().unwrap_err().code, ErrorCode::Cancelled);
    }
}
