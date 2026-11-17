//! Admit business wire buffers and decoded records before allocation. The small fixed
//! control lane is independent of a failed task ledger so failures/cancel can terminate.
use crate::{graph as g, packed_count, wire_fields, worker as w};
use anyhow::Result;
use std::mem::size_of;

pub const CONTROL_BYTES: usize = 16 * 1024;
pub const BOOTSTRAP_BYTES: usize = 4096;
pub const CANCEL_BYTES: usize = 1024;

pub fn control_tag(tag: u64) -> bool {
    matches!(tag, 2 | 8 | 9 | 10 | 11 | 12 | 16 | 17 | 18)
}
pub fn is_control(frame: &w::Envelope) -> bool {
    use w::envelope::Body;
    matches!(
        frame.body,
        Some(
            Body::Hello(_)
                | Body::End(_)
                | Body::Replan(_)
                | Body::Cancel(_)
                | Body::Ack(_)
                | Body::Failure(_)
                | Body::Acked(_)
                | Body::Reuse(_)
                | Body::Reused(_)
        )
    )
}
pub fn body_tag(bytes: &[u8]) -> Result<u64> {
    let mut body = 0;
    wire_fields(bytes, |tag, _| {
        if tag != 1 {
            body = tag;
        }
        Ok(())
    })?;
    Ok(body)
}

#[derive(Default, Debug)]
pub struct Allocation {
    pub work: u64,
    pub nodes: u64,
    pub bytes: u64,
}
impl Allocation {
    fn record<T>(&mut self) {
        self.work += 1;
        self.nodes += 1;
        self.bytes += 4 * size_of::<T>() as u64;
    }
    fn slots<T>(&mut self, count: usize) {
        self.work += count as u64;
        self.bytes += 4 * (size_of::<T>() as u64) * (count as u64);
    }
    fn source(&mut self, bytes: &[u8]) -> Result<()> {
        self.record::<w::JavaSource>();
        wire_fields(bytes, |tag, _| {
            if tag == 1 {
                self.record::<w::SourceUnit>();
            }
            Ok(())
        })
    }
    fn manifest(&mut self, bytes: &[u8]) -> Result<()> {
        wire_fields(bytes, |tag, _| {
            if tag == 1 {
                self.record::<w::SourceUnit>();
            }
            Ok(())
        })
    }
    fn context(&mut self, bytes: &[u8]) -> Result<()> {
        self.record::<w::JavaContext>();
        wire_fields(bytes, |tag, value| {
            match tag {
                5 | 12 => self.source(value)?,
                6 | 21 | 22 | 23 => self.slots::<String>(1),
                14..=17 => self.slots::<u32>(if value.is_empty() {
                    1
                } else {
                    packed_count(value)?
                }),
                _ => {}
            }
            Ok(())
        })
    }
    fn java(&mut self, bytes: &[u8]) -> Result<()> {
        self.record::<w::JavaConfig>();
        wire_fields(bytes, |tag, value| {
            match tag {
                5 => self.context(value)?,
                11 => self.source(value)?,
                12 => self.slots::<String>(1),
                _ => {}
            }
            Ok(())
        })
    }
}
pub fn allocation(bytes: &[u8]) -> Result<Allocation> {
    let mut cost = Allocation {
        work: 1,
        nodes: 1,
        bytes: 2 * bytes.len() as u64 + size_of::<w::Envelope>() as u64 + 256,
    };
    wire_fields(bytes, |body, value| {
        if body == 1 {
            return Ok(());
        }
        wire_fields(value, |field, nested| {
            match (body, field) {
                (3 | 5, 1) => cost.manifest(nested)?,
                (3, 2) => cost.java(nested)?,
                (3, 3) => cost.record::<w::RustConfig>(),
                (3, 4) => cost.record::<w::PythonConfig>(),
                (3, 7) => cost.record::<w::TypeScriptConfig>(),
                (3, 9) | (4, 2 | 6 | 8 | 14) => cost.slots::<String>(1),
                (4, 10) => {
                    cost.record::<w::JavaBuildContext>();
                    wire_fields(nested, |field, _| {
                        if matches!(field, 2 | 6) {
                            cost.slots::<String>(1);
                        }
                        Ok(())
                    })?;
                }
                (4, 11) => cost.record::<w::RustBuildContext>(),
                (4, 12) => cost.record::<w::PythonBuildContext>(),
                (4, 13) => cost.record::<w::TypeScriptBuildContext>(),
                (6, 6) => cost.record::<w::Owner>(),
                (7, 3) => {
                    cost.record::<g::Row>();
                    wire_fields(nested, |tag, _| {
                        if tag == 2 {
                            cost.slots::<g::Cell>(1);
                        }
                        Ok(())
                    })?;
                }
                (13, 1 | 3 | 5) => cost.slots::<String>(1),
                _ => {}
            }
            Ok(())
        })?;
        Ok(())
    })?;
    Ok(cost)
}
