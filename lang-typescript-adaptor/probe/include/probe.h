#ifndef CODEANA_TS_PROBE_H
#define CODEANA_TS_PROBE_H
#include <stdint.h>
uint32_t codeana_ts_probe_abi_version(void);
uint64_t codeana_ts_probe_analyze(uint8_t *input, uint64_t size);
int64_t codeana_ts_probe_read(uint64_t handle, uint64_t offset, uint8_t *output, uint64_t capacity);
int32_t codeana_ts_probe_close(uint64_t handle);
#endif
