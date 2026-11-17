package engine

import (
	"testing"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
)

func wireFixture() *pb.Request {
	zero := uint64(0)
	return &pb.Request{Version: RequestVersion, RequestId: "task/typescript/unit-a", ExpectedRuntime: RuntimeIdentity(), DeadlineMs: &zero,
		Grant: &w.AnalysisBudgetGrant{Id: "grant", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 100, MaxNodes: 100, MaxPayloadBytes: 10000, MaxDepth: 16}},
		Seed:  &w.AnalysisBudgetUsage{}, Operation: &pb.Request_Analyze{Analyze: &pb.Analyze{}}}
}

func TestDecodeRequiresExplicitIdentityBudgetDeadlineAndOperation(t *testing.T) {
	request := wireFixture()
	encoded, _ := proto.Marshal(request)
	decoded, nodes, err := DecodeRequest(encoded)
	if err != nil || nodes == 0 || !proto.Equal(request, decoded) {
		t.Fatal(decoded, nodes, err)
	}
	for _, edit := range []func(*pb.Request){
		func(r *pb.Request) { r.Version = 0 },
		func(r *pb.Request) { r.RequestId = "" },
		func(r *pb.Request) { r.Grant = nil },
		func(r *pb.Request) { r.Seed = nil },
		func(r *pb.Request) { r.DeadlineMs = nil },
		func(r *pb.Request) { r.Operation = nil },
		func(r *pb.Request) { r.ExpectedRuntime.Abi++ },
	} {
		bad := proto.Clone(request).(*pb.Request)
		edit(bad)
		data, _ := proto.Marshal(bad)
		if _, _, err := DecodeRequest(data); err == nil {
			t.Fatal("missing or foreign identity accepted", bad)
		}
	}
}

func TestWireRejectsDuplicateOperationsAndMergedSeedsBeforeDecode(t *testing.T) {
	encoded, _ := proto.Marshal(wireFixture())
	for _, suffix := range [][]byte{
		protowire.AppendBytes(protowire.AppendTag(nil, 10, protowire.BytesType), nil),
		protowire.AppendBytes(protowire.AppendTag(nil, 11, protowire.BytesType), nil),
		protowire.AppendBytes(protowire.AppendTag(nil, 5, protowire.BytesType), nil),
		protowire.AppendVarint(protowire.AppendTag(nil, 20, protowire.VarintType), 1),
		{0x80},
	} {
		data := append(append([]byte(nil), encoded...), suffix...)
		if _, _, err := DecodeRequest(data); err == nil {
			t.Fatal("ambiguous or malformed wire accepted", suffix)
		}
	}
	remaining := uint64(2)
	if err := admitWire(encoded, (&pb.Request{}).ProtoReflect().Descriptor(), 1, &remaining); err == nil {
		t.Fatal("wire values admitted past the predecode limit")
	}
}
