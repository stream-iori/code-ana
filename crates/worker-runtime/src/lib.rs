//! Shared worker IPC progress, terminal accounting and bounded FFI ownership.
pub mod analysis;
pub mod ffi;
pub mod ipc;
pub use ipc::{
    FrameReader, read_frame, read_frame_with_budget, write_frame, write_frame_with_budget,
};
pub mod delivery;
pub mod session;
