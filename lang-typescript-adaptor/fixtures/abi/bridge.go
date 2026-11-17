// This file is compiled only into the explicitly requested ABI fixture SDK.
package main

/*
#include <stdint.h>
*/
import "C"

import (
	"context"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/microsoft/typescript-go/codeana/engine"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
)

var fixturePhase atomic.Uint32
var fixtureRelease = make(chan struct{})
var fixtureOnce sync.Once

func fixtureExecute(ctx context.Context, created time.Time, data []byte, limit uint64) engine.Completion {
	switch os.Getenv("CODEANA_TS_ABI_FIXTURE") {
	case "panic":
		panic("explicit Go ABI fixture panic")
	case "running-close":
		fixturePhase.Store(1)
		<-ctx.Done()
		fixturePhase.Store(2)
		<-fixtureRelease
		return engine.UntrustedFailure(pb.FailureCode_CANCELLED, "explicit running-close barrier")
	default:
		return engine.Execute(ctx, created, data, limit)
	}
}

//export codeana_ts_fixture_phase
func codeana_ts_fixture_phase() C.uint32_t { return C.uint32_t(fixturePhase.Load()) }

//export codeana_ts_fixture_release
func codeana_ts_fixture_release() { fixtureOnce.Do(func() { close(fixtureRelease) }) }
