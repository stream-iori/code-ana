package engine

import (
	"context"
	"errors"
	"math"
	"strings"
	"time"
	"unicode/utf8"

	g "github.com/microsoft/typescript-go/codeana/graphpb"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
	"google.golang.org/protobuf/proto"
)

const CompleteState uint32 = 5
const FailedState uint32 = 6
const CancelledState uint32 = 7
const MaxResultBytes = 1 << 20

type Completion struct {
	Bytes []byte
	State uint32
}

func UntrustedFailure(code pb.FailureCode, message string) Completion {
	return failureResult(nil, nil, code, message, false)
}

func boundedMessage(message string) string {
	message = strings.ToValidUTF8(message, "?")
	if len(message) > 1024 {
		message = message[:1024]
		for !utf8.ValidString(message) {
			message = message[:len(message)-1]
		}
	}
	return message
}

func failureResult(request *pb.Request, meter *Meter, code pb.FailureCode, message string, known bool) Completion {
	result := &pb.Result{Version: RequestVersion, Runtime: RuntimeIdentity(), Outcome: &pb.Result_Failure{Failure: &pb.Failure{Code: code, Message: boundedMessage(message)}}}
	if request != nil {
		result.RequestId = request.RequestId
	}
	if meter != nil {
		result.Receipt = meter.Receipt(meter.phase, known)
	} else {
		result.Receipt = &w.AnalysisBudgetReceipt{Phase: "input_decode", UsageKnown: &known}
	}
	data, err := proto.Marshal(result)
	if err != nil {
		panic(err)
	}
	state := FailedState
	if code == pb.FailureCode_CANCELLED {
		state = CancelledState
	}
	return Completion{Bytes: data, State: state}
}

// Execute never returns compiler objects. The session's creation time accounts
// for feeding/decoding when constructing the Go monotonic task deadline.
func Execute(parent context.Context, created time.Time, input []byte, resultLimit uint64) (completion Completion) {
	var request *pb.Request
	var meter *Meter
	defer func() {
		if recovered := recover(); recovered != nil {
			completion = failureResult(request, meter, pb.FailureCode_INTERNAL_PANIC, "recoverable engine panic", false)
		}
	}()
	if resultLimit < 4096 || resultLimit > MaxResultBytes {
		return failureResult(nil, nil, pb.FailureCode_INVALID_REQUEST, "result byte limit", false)
	}
	decoded, nodes, err := DecodeRequest(input)
	if err != nil {
		return failureResult(nil, nil, pb.FailureCode_INVALID_REQUEST, err.Error(), false)
	}
	request = decoded
	ctx := parent
	if *request.DeadlineMs > 0 {
		if *request.DeadlineMs > uint64(math.MaxInt64/int64(time.Millisecond)) {
			return failureResult(request, nil, pb.FailureCode_INVALID_REQUEST, "deadline overflow", false)
		}
		var cancel context.CancelFunc
		ctx, cancel = context.WithDeadline(parent, created.Add(time.Duration(*request.DeadlineMs)*time.Millisecond))
		defer cancel()
	}
	meter, err = NewMeter(ctx, request.Grant, request.Seed)
	if err != nil {
		return failureResult(request, nil, pb.FailureCode_INVALID_REQUEST, err.Error(), false)
	}
	// Fund request admission and terminal encoding before taking the final
	// receipt. The bounded control failure lane remains available after refusal.
	if err = meter.Charge(nodes+1, 16, resultLimit*4+8192); err == nil {
		if analyze := request.GetAnalyze(); analyze != nil {
			var archive *pb.Archive
			archive, err = semanticGraph(ctx, analyze, meter)
			if err == nil {
				result := &pb.Result{Version: RequestVersion, RequestId: request.RequestId, Runtime: RuntimeIdentity(), Receipt: meter.Receipt("archive", true), Outcome: &pb.Result_Analysis{Analysis: &pb.AnalysisResult{Graph: archive}}}
				data, encodeErr := proto.Marshal(result)
				if encodeErr != nil {
					err = encodeErr
				} else if uint64(len(data)) > resultLimit {
					err = &Fault{Code: pb.FailureCode_OUTPUT_LIMIT, Message: "result descriptor byte limit"}
				} else {
					return Completion{Bytes: data, State: CompleteState}
				}
			}
		} else if prepare := request.GetPrepare(); prepare != nil {
			var archive *pb.Archive
			archive, err = prepareInventory(ctx, prepare, meter)
			if err == nil {
				result := &pb.Result{Version: RequestVersion, RequestId: request.RequestId, Runtime: RuntimeIdentity(), Receipt: meter.Receipt("archive", true), Outcome: &pb.Result_Prepared{Prepared: &pb.PreparedResult{Inventory: archive}}}
				data, encodeErr := proto.Marshal(result)
				if encodeErr != nil {
					err = encodeErr
				} else if uint64(len(data)) > resultLimit {
					err = &Fault{Code: pb.FailureCode_OUTPUT_LIMIT, Message: "preparation result descriptor byte limit"}
				} else {
					return Completion{Bytes: data, State: CompleteState}
				}
			}
		}
	}
	code := pb.FailureCode_IO_FAILURE
	var fault *Fault
	if errors.As(err, &fault) {
		code = fault.Code
	}
	return failureResult(request, meter, code, err.Error(), true)
}

func textCell(value string) *g.Cell { return &g.Cell{Value: &g.Cell_Text{Text: value}} }
func intCell(value int64) *g.Cell   { return &g.Cell{Value: &g.Cell_Integer{Integer: value}} }
func nullCell() *g.Cell             { return &g.Cell{} }
