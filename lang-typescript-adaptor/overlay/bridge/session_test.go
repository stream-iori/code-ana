package main

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/microsoft/typescript-go/codeana/engine"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"golang.org/x/sys/unix"
	"google.golang.org/protobuf/proto"
)

func await(t *testing.T, channel <-chan struct{}) {
	t.Helper()
	select {
	case <-channel:
	case <-time.After(3 * time.Second):
		t.Fatal("explicit session barrier timed out")
	}
}
func sessionFixture(t *testing.T) (uint64, *session) {
	t.Helper()
	handle, code := newSession(4096, 4096)
	if code != ok {
		t.Fatal(code)
	}
	s := lookup(handle)
	pair, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		unix.Close(pair[0])
		unix.Close(pair[1])
		if lookup(handle) != nil {
			closeSession(handle, s, 3000)
		}
	})
	if unix.SetNonblock(pair[0], true) != nil || unix.SetNonblock(pair[1], true) != nil {
		t.Fatal("nonblocking pair")
	}
	if s.feed([]byte{1}) != ok || s.seal() != ok || s.setFD(int32(pair[1])) != ok {
		t.Fatal("session preparation")
	}
	return handle, s
}

func TestRunningCloseKeepsInputAndHandleUntilComputationStops(t *testing.T) {
	handle, s := sessionFixture(t)
	entered, cancelled, release := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var once sync.Once
	t.Cleanup(func() { once.Do(func() { close(release) }) })
	s.execute = func(ctx context.Context, _ time.Time, _ []byte, _ uint64) engine.Completion {
		close(entered)
		<-ctx.Done()
		close(cancelled)
		<-release
		return engine.UntrustedFailure(pb.FailureCode_CANCELLED, "test cancellation barrier")
	}
	if s.start() != ok {
		t.Fatal("start")
	}
	await(t, entered)
	if closeSession(handle, s, 0) != pending {
		t.Fatal("running close did not retain ownership")
	}
	await(t, cancelled)
	if lookup(handle) != s || len(s.input) != 1 || s.closed {
		t.Fatal("input released before computation stopped")
	}
	var group sync.WaitGroup
	for i := 0; i < 8; i++ {
		group.Go(func() {
			if s.stop() != ok {
				t.Error("concurrent cancellation")
			}
		})
	}
	group.Wait()
	once.Do(func() { close(release) })
	await(t, s.done)
	if closeSession(handle, s, 1000) != ok || lookup(handle) != nil || !s.closed || s.input != nil {
		t.Fatal("completed task was not released")
	}
}

func TestRecoverableGoroutinePanicProducesAnUnknownFailure(t *testing.T) {
	_, s := sessionFixture(t)
	s.execute = func(context.Context, time.Time, []byte, uint64) engine.Completion { panic("fixture panic") }
	if s.start() != ok {
		t.Fatal("start")
	}
	await(t, s.done)
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.state != engine.FailedState {
		t.Fatal(s.state)
	}
	result := &pb.Result{}
	if err := proto.Unmarshal(s.result, result); err != nil {
		t.Fatal(err)
	}
	if result.GetFailure() == nil || result.GetFailure().Code != pb.FailureCode_INTERNAL_PANIC || result.Receipt == nil || result.Receipt.GetUsageKnown() {
		t.Fatal("panic certified a complete account", result)
	}
}
