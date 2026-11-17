package main

import (
	"context"
	"math"
	"sync"
	"time"

	"github.com/microsoft/typescript-go/codeana/engine"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"golang.org/x/sys/unix"
)

const (
	ok              int32  = 0
	eof             int32  = 1
	pending         int32  = 2
	invalidHandle   int32  = -1
	invalidArgument int32  = -2
	invalidState    int32  = -3
	limitExceeded   int32  = -4
	ioFailure       int32  = -5
	panicFailure    int32  = -6
	created         uint32 = 1
	feeding         uint32 = 2
	sealed          uint32 = 3
	running         uint32 = 4
	acked           uint32 = 8
	maxChunk               = 4 << 20
)

type session struct {
	mu                      sync.Mutex
	state                   uint32
	inputLimit, resultLimit uint64
	input, result           []byte
	fd                      int
	ctx                     context.Context
	cancel                  context.CancelFunc
	created                 time.Time
	done                    chan struct{}
	closed                  bool
	boundaryPanic           bool
	execute                 func(context.Context, time.Time, []byte, uint64) engine.Completion
}

var registry = struct {
	sync.Mutex
	next    uint64
	entries map[uint64]*session
}{entries: make(map[uint64]*session)}

func newSession(inputLimit, resultLimit uint64) (uint64, int32) {
	if inputLimit == 0 || inputLimit > engine.MaxRequestBytes || resultLimit < 4096 || resultLimit > engine.MaxResultBytes {
		return 0, invalidArgument
	}
	registry.Lock()
	defer registry.Unlock()
	if len(registry.entries) >= 8 || registry.next == math.MaxUint64 {
		return 0, limitExceeded
	}
	registry.next++
	ctx, cancel := context.WithCancel(context.Background())
	registry.entries[registry.next] = &session{state: created, inputLimit: inputLimit, resultLimit: resultLimit, fd: -1, ctx: ctx, cancel: cancel, created: time.Now(), done: make(chan struct{}), execute: engine.Execute}
	return registry.next, ok
}

func lookup(handle uint64) *session {
	registry.Lock()
	defer registry.Unlock()
	return registry.entries[handle]
}

func (s *session) finish(value engine.Completion) {
	s.mu.Lock()
	if s.state >= engine.CompleteState {
		s.mu.Unlock()
		return
	}
	if s.boundaryPanic || uint64(len(value.Bytes)) > s.resultLimit {
		value = engine.UntrustedFailure(pb.FailureCode_INTERNAL_PANIC, "ABI control or result boundary failure")
	}
	s.result = value.Bytes
	s.state = value.State
	fd := s.fd
	s.fd = -1
	s.mu.Unlock()
	if fd >= 0 {
		_, _ = unix.Write(fd, []byte{1})
		_ = unix.Close(fd)
	}
	close(s.done)
}

func (s *session) onPanic() {
	s.mu.Lock()
	s.boundaryPanic = true
	s.cancel()
	active := s.state == running
	terminal := s.state >= engine.CompleteState
	s.mu.Unlock()
	if !active && !terminal {
		s.finish(engine.UntrustedFailure(pb.FailureCode_INTERNAL_PANIC, "recoverable ABI panic"))
	}
}

func (s *session) feed(data []byte) int32 {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return invalidHandle
	}
	if s.state != created && s.state != feeding {
		return invalidState
	}
	if uint64(len(data)) > s.inputLimit-uint64(len(s.input)) {
		return limitExceeded
	}
	s.input = append(s.input, data...)
	s.state = feeding
	return ok
}

func (s *session) seal() int32 {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return invalidHandle
	}
	if s.state != created && s.state != feeding {
		return invalidState
	}
	s.state = sealed
	return ok
}

func (s *session) setFD(descriptor int32) int32 {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return invalidHandle
	}
	if s.fd >= 0 || s.state >= running {
		return invalidState
	}
	if descriptor < 0 {
		return invalidArgument
	}
	flags, err := unix.FcntlInt(uintptr(descriptor), unix.F_GETFL, 0)
	if err != nil || flags&unix.O_NONBLOCK == 0 {
		return invalidArgument
	}
	var info unix.Stat_t
	if unix.Fstat(int(descriptor), &info) != nil || info.Mode&unix.S_IFMT != unix.S_IFSOCK {
		return invalidArgument
	}
	fd, err := unix.Dup(int(descriptor))
	if err != nil {
		return ioFailure
	}
	if _, err = unix.FcntlInt(uintptr(fd), unix.F_SETFD, unix.FD_CLOEXEC); err != nil {
		unix.Close(fd)
		return ioFailure
	}
	if err = unix.SetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_NOSIGPIPE, 1); err != nil {
		unix.Close(fd)
		return ioFailure
	}
	s.fd = fd
	return ok
}

func (s *session) start() int32 {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return invalidHandle
	}
	if s.state != sealed || s.fd < 0 {
		return invalidState
	}
	s.state = running
	go func() {
		defer func() {
			if recover() != nil {
				s.finish(engine.UntrustedFailure(pb.FailureCode_INTERNAL_PANIC, "recoverable computation panic"))
			}
		}()
		s.finish(s.execute(s.ctx, s.created, s.input, s.resultLimit))
	}()
	return ok
}

func (s *session) stop() int32 {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return invalidHandle
	}
	s.cancel()
	active := s.state == running
	terminal := s.state >= engine.CompleteState
	s.mu.Unlock()
	if !active && !terminal {
		s.finish(engine.UntrustedFailure(pb.FailureCode_CANCELLED, "cancelled before computation"))
	}
	return ok
}

func closeSession(handle uint64, s *session, timeout uint32) int32 {
	if timeout > 60000 {
		return invalidArgument
	}
	if code := s.stop(); code != ok {
		return code
	}
	if timeout == 0 {
		select {
		case <-s.done:
		default:
			return pending
		}
	} else {
		timer := time.NewTimer(time.Duration(timeout) * time.Millisecond)
		defer timer.Stop()
		select {
		case <-s.done:
		case <-timer.C:
			return pending
		}
	}
	registry.Lock()
	defer registry.Unlock()
	if registry.entries[handle] != s {
		return invalidHandle
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	s.input = nil
	s.result = nil
	if s.fd >= 0 {
		unix.Close(s.fd)
		s.fd = -1
	}
	delete(registry.entries, handle)
	return ok
}
