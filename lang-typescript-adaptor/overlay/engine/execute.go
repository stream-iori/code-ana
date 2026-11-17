package engine

import (
	"context"
	"errors"
	"fmt"
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
			archive, err = componentGraph(ctx, analyze, meter)
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
		} else {
			err = &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "live preparation is not implemented yet"}
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

// T2b transport evidence is deliberately partial: it proves real Checker
// bindings in ordinary GRA1 diagnostic rows. T4 supplies semantic declarations,
// symbols, relations and the public TS graph projection before product launch.
func componentGraph(ctx context.Context, analyze *pb.Analyze, meter *Meter) (_ *pb.Archive, failure error) {
	if analyze.SemanticProfile == "" {
		return nil, invalid("semantic profile required")
	}
	meter.phase = "backend"
	program, err := OpenProgram(analyze.Project, meter)
	if err != nil {
		return nil, err
	}
	meter.phase = "records"
	writer, err := NewArchive(analyze.Scratch, analyze.Output, meter)
	if err != nil {
		return nil, err
	}
	defer func() { failure = errors.Join(failure, writer.Close()) }()
	project := analyze.Project
	world := ""
	for _, file := range project.Files {
		if file.File.Role == pb.FileRole_PUBLISH {
			world = file.File.World
			break
		}
	}
	if world == "" {
		return nil, invalid("component has no publishable source")
	}
	for _, sourceLanguage := range project.Context.PublishLanguages {
		if err := meter.Charge(1, 7, 4096); err != nil {
			return nil, err
		}
		language := "typescript"
		if sourceLanguage == pb.SourceLanguage_JAVASCRIPT {
			language = "javascript"
		}
		provider := &g.Row{Table: g.Table_PROVIDER, World: world, Cells: []*g.Cell{textCell(Adapter + ":" + language), textCell(language), textCell(RuntimeIdentity().CompilerVersion), textCell("[]"), textCell(`["semantic graph projection pending T4"]`), textCell(analyze.SemanticProfile)}}
		if err := writer.Write(provider); err != nil {
			return nil, err
		}
	}
	for _, file := range project.Files {
		identity := file.File
		if identity.Role != pb.FileRole_PUBLISH {
			continue
		}
		if err := meter.Charge(1, 13, 4096); err != nil {
			return nil, err
		}
		language := "typescript"
		if identity.Language == pb.SourceLanguage_JAVASCRIPT {
			language = "javascript"
		}
		name := strings.TrimPrefix(identity.Path, project.Context.Root+"/")
		row := &g.Row{Table: g.Table_COVERAGE, World: identity.World, Cells: []*g.Cell{textCell(name), textCell(identity.Module), textCell(identity.Scope), textCell(language), textCell(Adapter + ":" + language), textCell("CALLS"), textCell("partial"), intCell(1), intCell(1), textCell(`["TYPESCRIPT_COMPONENT_ONLY"]`), textCell(`{"TYPESCRIPT_COMPONENT_ONLY":1}`), intCell(0)}}
		if err := writer.Write(row); err != nil {
			return nil, err
		}
	}
	meter.phase = "extract"
	err = program.WalkCallBindings(ctx, func(binding CallBinding) error {
		identity := program.files[binding.File]
		if err := meter.Charge(1, 15, uint64(len(binding.TargetName))*4+8192); err != nil {
			return err
		}
		name := strings.TrimPrefix(binding.File, project.Context.Root+"/")
		target := strings.TrimPrefix(binding.TargetFile, project.Context.Root+"/")
		sample := fmt.Sprintf("%s:%d..%d -> %s#%s:%d..%d", name, binding.Start, binding.End, target, binding.TargetName, binding.TargetStart, binding.TargetEnd)
		code := "TYPESCRIPT_COMPONENT_BINDING"
		symbol := textCell(binding.TargetName)
		if !binding.Resolved {
			code = "TYPESCRIPT_COMPONENT_UNRESOLVED"
			symbol = nullCell()
			sample = fmt.Sprintf("unresolved %s:%d..%d", name, binding.Start, binding.End)
		}
		language := "typescript"
		if identity.Language == pb.SourceLanguage_JAVASCRIPT {
			language = "javascript"
		}
		row := &g.Row{Table: g.Table_DIAGNOSTIC, World: identity.World, Cells: []*g.Cell{textCell("info"), textCell(code), textCell("binding"), textCell(name), textCell(identity.Module), textCell(identity.Scope), symbol, textCell(language), textCell(Adapter + ":" + language), textCell("semantic graph projection pending T4"), textCell("TYPESCRIPT_COMPONENT_ONLY"), nullCell(), intCell(1), textCell(sample)}}
		return writer.Write(row)
	})
	if err != nil {
		return nil, err
	}
	if err := meter.Check(); err != nil {
		return nil, err
	}
	meter.phase = "archive"
	return writer.Seal()
}
