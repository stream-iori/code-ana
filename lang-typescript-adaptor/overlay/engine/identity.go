package engine

import (
	"runtime"
	"strconv"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/core"
)

// The locked SDK builder supplies these values to both tests and c-shared.
// Unstamped development binaries cannot match a production SDK manifest.
var (
	Adapter               string
	UpstreamCommit        string
	TypeScriptCommit      string
	StandardLibraryDigest string
	AnalysisProtoSHA256   string
	GraphProtoSHA256      string
	WorkerProtocol        = "25"
	GraphModel            = "7"
	GraphSchema           = "37"
)

const ABIVersion uint32 = 1
const RequestVersion uint32 = 2

func RuntimeIdentity() *pb.RuntimeIdentity {
	number := func(value string) uint32 {
		n, err := strconv.ParseUint(value, 10, 32)
		if err != nil {
			panic("invalid stamped protocol identity")
		}
		return uint32(n)
	}
	return &pb.RuntimeIdentity{
		Abi: ABIVersion, AnalysisRequest: RequestVersion,
		WorkerProtocol: number(WorkerProtocol), GraphModel: number(GraphModel), GraphSchema: number(GraphSchema),
		Adapter: Adapter, UpstreamCommit: UpstreamCommit, TypescriptCommit: TypeScriptCommit,
		CompilerVersion: core.Version(), GoVersion: runtime.Version(),
		StandardLibraryDigest: StandardLibraryDigest,
		AnalysisProtoSha256:   AnalysisProtoSHA256, GraphProtoSha256: GraphProtoSHA256,
	}
}
