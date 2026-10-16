//! Bounded lanes: analysis cannot consume query or maintenance capacity.
use super::*;
#[derive(Clone, Copy)]
enum Lane {
    Analysis,
    Query,
    Maintenance,
}
impl Lane {
    fn of(command: &TaskCommand) -> Self {
        if command.requires_analysis() {
            Self::Analysis
        } else if command.requires_writer() {
            Self::Maintenance
        } else {
            Self::Query
        }
    }
    fn index(self) -> usize {
        match self {
            Self::Analysis => 0,
            Self::Query => 1,
            Self::Maintenance => 2,
        }
    }
}
pub(super) struct Scheduler {
    queues: [Arc<Semaphore>; 3],
    slots: [Arc<Semaphore>; 3],
    writers: Mutex<BTreeMap<String, Weak<Semaphore>>>,
    projects: Mutex<BTreeMap<String, Weak<Semaphore>>>,
}
pub(super) struct ExecutionPermits {
    _analysis: Option<OwnedSemaphorePermit>,
    _writer: Option<OwnedSemaphorePermit>,
    _slot: OwnedSemaphorePermit,
}
impl Scheduler {
    pub(super) fn new(workers: usize, queries: usize) -> Self {
        Self {
            queues: std::array::from_fn(|_| Arc::new(Semaphore::new(8))),
            slots: [workers, queries, 1].map(|n| Arc::new(Semaphore::new(n))),
            writers: Mutex::default(),
            projects: Mutex::default(),
        }
    }
    pub(super) fn admit(&self, command: &TaskCommand) -> Result<OwnedSemaphorePermit> {
        self.queues[Lane::of(command).index()]
            .clone()
            .try_acquire_owned()
            .map_err(|_| DomainError::new(ErrorCode::QueueBudget, "").into())
    }
    fn gate(map: &Mutex<BTreeMap<String, Weak<Semaphore>>>, key: String) -> Arc<Semaphore> {
        let mut gates = map.lock().unwrap();
        gates.retain(|_, gate| gate.strong_count() > 0);
        if let Some(gate) = gates.get(&key).and_then(Weak::upgrade) {
            return gate;
        }
        let gate = Arc::new(Semaphore::new(1));
        gates.insert(key, Arc::downgrade(&gate));
        gate
    }
    async fn acquire(
        gate: Arc<Semaphore>,
        cancel: &CancellationToken,
    ) -> Result<OwnedSemaphorePermit> {
        tokio::select! { biased;
            _ = cancel.cancelled() => Err(DomainError::new(ErrorCode::Cancelled, "").into()),
            permit = gate.acquire_owned() => Ok(permit?),
        }
    }
    pub(super) async fn execute(
        &self,
        command: &TaskCommand,
        project: String,
        target: String,
        cancel: &CancellationToken,
    ) -> Result<ExecutionPermits> {
        let analysis = if command.requires_analysis() {
            Some(Self::acquire(Self::gate(&self.projects, project), cancel).await?)
        } else {
            None
        };
        let writer = if command.requires_writer() {
            Some(Self::acquire(Self::gate(&self.writers, target), cancel).await?)
        } else {
            None
        };
        let slot = Self::acquire(self.slots[Lane::of(command).index()].clone(), cancel).await?;
        Ok(ExecutionPermits {
            _analysis: analysis,
            _writer: writer,
            _slot: slot,
        })
    }
}
