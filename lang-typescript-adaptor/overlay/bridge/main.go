package main

/*
#include <stdint.h>
*/
import "C"

import (
	"sync"
	"unsafe"

	"github.com/microsoft/typescript-go/codeana/engine"
	"google.golang.org/protobuf/proto"
)

func main() {}

func guard(s *session, operation func() int32) (status C.int32_t) {
	defer func() {
		if recover() != nil {
			if s != nil {
				s.onPanic()
			}
			status = C.int32_t(panicFailure)
		}
	}()
	return C.int32_t(operation())
}
func with(handle C.uint64_t, operation func(*session) int32) C.int32_t {
	s := lookup(uint64(handle))
	if s == nil {
		return C.int32_t(invalidHandle)
	}
	return guard(s, func() int32 { return operation(s) })
}

var runtimeBytes = sync.OnceValue(func() []byte {
	data, err := proto.Marshal(engine.RuntimeIdentity())
	if err != nil {
		panic(err)
	}
	return data
})

func copyChunk(data []byte, offset uint64, destination *C.uint8_t, capacity C.uint32_t, written *C.uint32_t) int32 {
	if written == nil {
		return invalidArgument
	}
	*written = 0
	if capacity > maxChunk || (capacity > 0 && destination == nil) || offset > uint64(len(data)) {
		return invalidArgument
	}
	if offset == uint64(len(data)) {
		return eof
	}
	if capacity == 0 {
		return invalidArgument
	}
	n := min(uint64(capacity), uint64(len(data))-offset)
	copy(unsafe.Slice((*byte)(unsafe.Pointer(destination)), int(n)), data[offset:offset+n])
	*written = C.uint32_t(n)
	return ok
}

//export codeana_ts_abi_version
func codeana_ts_abi_version() C.uint32_t { return C.uint32_t(engine.ABIVersion) }

//export codeana_ts_runtime_identity
func codeana_ts_runtime_identity(offset C.uint64_t, destination *C.uint8_t, capacity C.uint32_t, written *C.uint32_t) C.int32_t {
	if written != nil {
		*written = 0
	}
	return guard(nil, func() int32 { return copyChunk(runtimeBytes(), uint64(offset), destination, capacity, written) })
}

//export codeana_ts_session_create
func codeana_ts_session_create(inputLimit C.uint64_t, resultLimit C.uint64_t, handle *C.uint64_t) C.int32_t {
	if handle != nil {
		*handle = 0
	}
	return guard(nil, func() int32 {
		if handle == nil {
			return invalidArgument
		}
		value, status := newSession(uint64(inputLimit), uint64(resultLimit))
		*handle = C.uint64_t(value)
		return status
	})
}

//export codeana_ts_input_chunk
func codeana_ts_input_chunk(handle C.uint64_t, source *C.uint8_t, length C.uint32_t) C.int32_t {
	return with(handle, func(s *session) int32 {
		if length > maxChunk || (length > 0 && source == nil) {
			return invalidArgument
		}
		return s.feed(unsafe.Slice((*byte)(unsafe.Pointer(source)), int(length)))
	})
}

//export codeana_ts_seal_input
func codeana_ts_seal_input(handle C.uint64_t) C.int32_t {
	return with(handle, func(s *session) int32 { return s.seal() })
}

//export codeana_ts_set_completion_fd
func codeana_ts_set_completion_fd(handle C.uint64_t, descriptor C.int32_t) C.int32_t {
	return with(handle, func(s *session) int32 { return s.setFD(int32(descriptor)) })
}

//export codeana_ts_analyze_start
func codeana_ts_analyze_start(handle C.uint64_t) C.int32_t {
	return with(handle, func(s *session) int32 { return s.start() })
}

//export codeana_ts_poll_event
func codeana_ts_poll_event(handle C.uint64_t, state *C.uint32_t) C.int32_t {
	if state != nil {
		*state = 0
	}
	return with(handle, func(s *session) int32 {
		if state == nil {
			return invalidArgument
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.closed {
			return invalidHandle
		}
		*state = C.uint32_t(s.state)
		return ok
	})
}

//export codeana_ts_read_result_chunk
func codeana_ts_read_result_chunk(handle C.uint64_t, offset C.uint64_t, destination *C.uint8_t, capacity C.uint32_t, written *C.uint32_t) C.int32_t {
	if written != nil {
		*written = 0
	}
	return with(handle, func(s *session) int32 {
		if written == nil || capacity > maxChunk || (capacity > 0 && destination == nil) {
			return invalidArgument
		}
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.closed {
			return invalidHandle
		}
		if s.state < engine.CompleteState {
			return pending
		}
		return copyChunk(s.result, uint64(offset), destination, capacity, written)
	})
}

//export codeana_ts_cancel
func codeana_ts_cancel(handle C.uint64_t) C.int32_t {
	return with(handle, func(s *session) int32 { return s.stop() })
}

//export codeana_ts_publish_ack
func codeana_ts_publish_ack(handle C.uint64_t) C.int32_t {
	return with(handle, func(s *session) int32 {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.closed {
			return invalidHandle
		}
		if s.state != engine.CompleteState {
			return invalidState
		}
		s.state = acked
		return ok
	})
}

//export codeana_ts_session_close
func codeana_ts_session_close(handle C.uint64_t, timeout C.uint32_t) C.int32_t {
	return with(handle, func(s *session) int32 { return closeSession(uint64(handle), s, uint32(timeout)) })
}
