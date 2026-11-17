//! One request/terminal state machine for every worker. Engine/context data stays outside it.
use anyhow::{Result, bail, ensure};
use codeana_contracts::worker::{Envelope, envelope::Body};
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReusePolicy {
    FreshProcess,
    HealthyReuse,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    Idle,
    Hello,
    Discovering,
    Ready,
    Analyzing,
    AwaitAck,
    Acking,
    Reusing,
    Closed,
}
pub struct Session {
    phase: Phase,
    policy: ReusePolicy,
    attempt: Option<String>,
    replanned: bool,
}
impl Session {
    pub fn new(policy: ReusePolicy) -> Self {
        Self {
            phase: Phase::Idle,
            policy,
            attempt: None,
            replanned: false,
        }
    }
    pub fn phase(&self) -> Phase {
        self.phase
    }
    pub fn attempt(&self) -> Option<&str> {
        self.attempt.as_deref()
    }
    pub fn awaiting_ack(&self) -> bool {
        matches!(self.phase, Phase::AwaitAck | Phase::Acking)
    }
    /// Discovery and analysis may start new attempts, but only at their legitimate phase.
    pub fn admit(&mut self, frame: &Envelope) -> Result<()> {
        ensure!(
            !frame.attempt.is_empty() && frame.attempt.len() <= 256,
            "ATTEMPT_INVALID"
        );
        let body = frame
            .body
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("EMPTY_FRAME"))?;
        let next = match body {
            Body::Hello(_) => {
                ensure!(self.phase == Phase::Idle, "DUPLICATE_HELLO");
                Phase::Hello
            }
            Body::Discover(_) => {
                ensure!(self.phase == Phase::Hello, "DISCOVER_STATE");
                Phase::Discovering
            }
            Body::Analyze(_) => {
                ensure!(self.phase == Phase::Ready, "ANALYZE_STATE");
                Phase::Analyzing
            }
            _ => {
                ensure!(
                    self.attempt.as_deref() == Some(frame.attempt.as_str()),
                    "ATTEMPT_MISMATCH"
                );
                match body {
                    Body::Ack(ack) => {
                        ensure!(
                            self.phase == Phase::AwaitAck && !ack.revision.is_empty(),
                            "PUBLISH_ACK_REQUIRED"
                        );
                        Phase::Acking
                    }
                    Body::Reuse(_) => {
                        ensure!(self.phase == Phase::Ready, "ANALYSIS_REUSE_STATE");
                        Phase::Reusing
                    }
                    Body::Cancel(_) => Phase::Closed,
                    _ => bail!("UNEXPECTED_WORKER_MESSAGE"),
                }
            }
        };
        self.attempt = Some(frame.attempt.clone());
        self.phase = next;
        Ok(())
    }
    pub fn discovered(&mut self) -> Result<()> {
        ensure!(self.phase == Phase::Discovering, "DISCOVER_STATE");
        self.phase = Phase::Ready;
        Ok(())
    }
    pub fn completed(&mut self) -> Result<()> {
        ensure!(self.phase == Phase::Analyzing, "RESULT_END_STATE");
        self.phase = Phase::AwaitAck;
        Ok(())
    }
    pub fn replan(&mut self) -> Result<()> {
        ensure!(
            self.phase == Phase::Analyzing && !self.replanned,
            "REPLAN_STATE"
        );
        self.replanned = true;
        self.phase = Phase::Ready;
        Ok(())
    }
    fn retire(&mut self) {
        self.replanned = false;
        self.phase = match self.policy {
            ReusePolicy::FreshProcess => Phase::Closed,
            ReusePolicy::HealthyReuse => Phase::Idle,
        };
        // The host may retire an unused session with Reuse followed by Cancel.
        // Keep only its control identity until a new Hello replaces it; releasing
        // the engine/input state belongs to the caller, independently of pooling.
    }
    pub fn acked(&mut self) -> Result<()> {
        ensure!(self.phase == Phase::Acking, "ACK_STATE");
        self.retire();
        Ok(())
    }
    pub fn reused(&mut self) -> Result<()> {
        ensure!(self.phase == Phase::Reusing, "REUSE_STATE");
        self.retire();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use codeana_contracts::{envelope, worker as w};
    fn hello(id: &str) -> Envelope {
        envelope(id, Body::Hello(w::Hello::default()))
    }
    fn discover(id: &str) -> Envelope {
        envelope(id, Body::Discover(w::Discover::default()))
    }
    fn analyze(id: &str) -> Envelope {
        envelope(id, Body::Analyze(w::Analyze::default()))
    }
    fn ack(id: &str, revision: &str) -> Envelope {
        envelope(
            id,
            Body::Ack(w::Ack {
                revision: revision.into(),
            }),
        )
    }
    #[test]
    fn illegal_requests_are_atomic_and_never_default_a_context_or_attempt() {
        let mut session = Session::new(ReusePolicy::FreshProcess);
        for frame in [
            discover("d"),
            analyze("a"),
            ack("a", "revision"),
            envelope("a", Body::Reuse(w::Reuse {})),
        ] {
            assert!(session.admit(&frame).is_err());
            assert_eq!(session.phase(), Phase::Idle);
            assert_eq!(session.attempt(), None);
        }
        session.admit(&hello("h")).unwrap();
        assert!(session.admit(&hello("other")).is_err());
        assert_eq!(session.attempt(), Some("h"));
        session.admit(&discover("d")).unwrap();
        assert!(session.admit(&analyze("a")).is_err());
        session.discovered().unwrap();
        assert!(session.discovered().is_err());
        session.admit(&analyze("a")).unwrap();
        assert!(session.admit(&ack("a", "revision")).is_err());
        session.completed().unwrap();
        assert!(session.completed().is_err());
        assert!(session.admit(&analyze("other")).is_err());
        assert!(session.admit(&ack("old", "revision")).is_err());
        assert!(session.admit(&ack("a", "")).is_err());
        session.admit(&ack("a", "revision")).unwrap();
        session.acked().unwrap();
        assert_eq!(session.phase(), Phase::Closed);
        assert!(session.admit(&hello("next")).is_err());
    }
    #[test]
    fn successful_ack_or_reuse_only_reopens_the_explicit_healthy_policy() {
        for policy in [ReusePolicy::FreshProcess, ReusePolicy::HealthyReuse] {
            for reused in [false, true] {
                let mut session = Session::new(policy);
                session.admit(&hello("h")).unwrap();
                session.admit(&discover("d")).unwrap();
                session.discovered().unwrap();
                if reused {
                    session
                        .admit(&envelope("d", Body::Reuse(w::Reuse {})))
                        .unwrap();
                    session.reused().unwrap();
                } else {
                    session.admit(&analyze("a")).unwrap();
                    session.completed().unwrap();
                    session.admit(&ack("a", "revision")).unwrap();
                    session.acked().unwrap();
                }
                assert_eq!(
                    session.admit(&hello("next")).is_ok(),
                    policy == ReusePolicy::HealthyReuse
                );
            }
        }
    }
    #[test]
    fn replan_and_cancel_cannot_switch_a_control_attempt_or_renew_the_task() {
        let mut session = Session::new(ReusePolicy::HealthyReuse);
        session.admit(&hello("h")).unwrap();
        session.admit(&discover("d")).unwrap();
        session.discovered().unwrap();
        session.admit(&analyze("a")).unwrap();
        assert!(
            session
                .admit(&envelope("d", Body::Cancel(w::Cancel {})))
                .is_err()
        );
        session.replan().unwrap();
        session.admit(&analyze("retry")).unwrap();
        assert!(session.replan().is_err());
        session
            .admit(&envelope("retry", Body::Cancel(w::Cancel {})))
            .unwrap();
        assert_eq!(session.phase(), Phase::Closed);
        assert!(session.admit(&hello("next")).is_err());
    }
    #[test]
    fn reused_sessions_accept_the_retiring_cancel_for_both_lifecycle_policies() {
        for policy in [ReusePolicy::FreshProcess, ReusePolicy::HealthyReuse] {
            let mut session = Session::new(policy);
            session.admit(&hello("h")).unwrap();
            session.admit(&discover("d")).unwrap();
            session.discovered().unwrap();
            session
                .admit(&envelope("d", Body::Reuse(w::Reuse {})))
                .unwrap();
            session.reused().unwrap();
            assert!(
                session
                    .admit(&envelope("other", Body::Cancel(w::Cancel {})))
                    .is_err()
            );
            session
                .admit(&envelope("d", Body::Cancel(w::Cancel {})))
                .unwrap();
            assert_eq!(session.phase(), Phase::Closed);
        }
    }
}
