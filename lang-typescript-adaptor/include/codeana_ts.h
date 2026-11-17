#ifndef CODEANA_TS_H
#define CODEANA_TS_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define CODEANA_TS_ABI_VERSION UINT32_C(1)

/* All calls return a status, never a Go pointer or an owned C allocation.
 * Handles are nonzero, process-local and never reused. Every output count is
 * set to zero before an error is returned. Buffers belong to the caller.
 */
enum codeana_ts_status {
    CODEANA_TS_OK = 0,
    CODEANA_TS_EOF = 1,
    CODEANA_TS_PENDING = 2,
    CODEANA_TS_INVALID_HANDLE = -1,
    CODEANA_TS_INVALID_ARGUMENT = -2,
    CODEANA_TS_INVALID_STATE = -3,
    CODEANA_TS_LIMIT = -4,
    CODEANA_TS_IO = -5,
    CODEANA_TS_PANIC = -6
};

enum codeana_ts_state {
    CODEANA_TS_CREATED = 1,
    CODEANA_TS_FEEDING = 2,
    CODEANA_TS_SEALED = 3,
    CODEANA_TS_RUNNING = 4,
    CODEANA_TS_COMPLETE = 5,
    CODEANA_TS_FAILED = 6,
    CODEANA_TS_CANCELLED = 7,
    CODEANA_TS_ACKED = 8
};

uint32_t codeana_ts_abi_version(void);
/* RuntimeIdentity protobuf. Chunk reads do not allocate caller-owned memory. */
int32_t codeana_ts_runtime_identity(uint64_t offset, uint8_t *destination,
                                   uint32_t capacity, uint32_t *written);
int32_t codeana_ts_session_create(uint64_t input_limit, uint64_t result_limit,
                                 uint64_t *session);
/* Copies input before returning; no caller pointer survives a call. */
int32_t codeana_ts_input_chunk(uint64_t session, uint8_t *source,
                              uint32_t length);
int32_t codeana_ts_seal_input(uint64_t session);
/* Duplicates the nonblocking socket FD. The caller retains its original FD.
 * Register exactly once before start. Publish terminal state before writing
 * one byte (value 1), then close the duplicate. No blocking notification write.
 */
int32_t codeana_ts_set_completion_fd(uint64_t session, int32_t descriptor);
/* Bounded start: compiler execution occurs on a Go goroutine. */
int32_t codeana_ts_analyze_start(uint64_t session);
int32_t codeana_ts_poll_event(uint64_t session, uint32_t *state);
/* Result protobuf contains a bounded descriptor/failure, never the whole graph.
 * Pending returns PENDING; EOF is only legal after a terminal result exists.
 */
int32_t codeana_ts_read_result_chunk(uint64_t session, uint64_t offset,
                                    uint8_t *destination, uint32_t capacity,
                                    uint32_t *written);
/* Thread safe, idempotent, cooperative; the supervisor enforces hard timeout. */
int32_t codeana_ts_cancel(uint64_t session);
int32_t codeana_ts_publish_ack(uint64_t session);
/* Cancel and wait for computation. PENDING on timeout retains all ownership;
 * only OK releases the registry entry, input, result and duplicate FD. A second
 * close returns INVALID_HANDLE. Never dlclose: retain the library until exit.
 */
int32_t codeana_ts_session_close(uint64_t session, uint32_t timeout_ms);

#ifdef __cplusplus
}
#endif
#endif
