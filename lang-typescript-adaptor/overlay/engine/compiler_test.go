package engine

import (
	"context"
	"testing"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
)

func compilerFixture(t *testing.T) (*pb.FrozenProject, *Meter) {
	t.Helper()
	project := frozenFixture("/src")
	project.Files = []*pb.FrozenFile{
		frozenFile("/src/a.ts", "export function greet(value: number): number { return value + 1; }\r\n", pb.FileRole_PUBLISH),
		frozenFile("/src/b.ts", "import { greet as hello } from \"./a\";\r\n// 中文😀\r\n/*😀*/hello(1);\r\nhello(1);", pb.FileRole_PUBLISH),
		frozenFile("/src/tsconfig.json", `{"compilerOptions":{"skipLibCheck":true,"module":"ESNext","moduleResolution":"bundler"},"files":["a.ts","b.ts"]}`, pb.FileRole_CONTROL),
	}
	project.Context.ConfigPath = "/src/tsconfig.json"
	project.Context.RootFiles = []string{"/src/a.ts", "/src/b.ts"}
	project.Context.Directories = []*pb.Directory{{Path: "/", Directories: []string{"src"}}, {Path: "/src", Files: []string{"a.ts", "b.ts", "tsconfig.json"}}}
	meter, err := NewMeter(context.Background(), &w.AnalysisBudgetGrant{Id: "compiler", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 5_000_000, MaxNodes: 1_000_000, MaxPayloadBytes: 1 << 30, MaxDepth: 256}}, &w.AnalysisBudgetUsage{})
	if err != nil {
		t.Fatal(err)
	}
	return project, meter
}

func TestRealCheckerResolvesBothAliasCallsFromOnlyCapturedUTF8Bytes(t *testing.T) {
	project, meter := compilerFixture(t)
	program, err := OpenProgram(project, meter)
	if err != nil {
		t.Fatal(err)
	}
	var calls []CallBinding
	if err := program.WalkCallBindings(context.Background(), func(binding CallBinding) error { calls = append(calls, binding); return nil }); err != nil {
		t.Fatal(err)
	}
	if len(calls) != 2 {
		t.Fatal(calls)
	}
	for i, span := range [][2]uint64{{62, 70}, {73, 81}} {
		call := calls[i]
		if call.File != "/src/b.ts" || call.Start != span[0] || call.End != span[1] || !call.Resolved || call.TargetFile != "/src/a.ts" || call.TargetName != "greet" || call.TargetStart != 16 || call.TargetEnd != 21 {
			t.Fatal(call)
		}
	}
	if meter.Receipt("archive", true).Usage.Work == 0 {
		t.Fatal("compiler analysis was not charged")
	}
}

func TestCompilerCannotSilentlyRediscoverDifferentRootFiles(t *testing.T) {
	project, meter := compilerFixture(t)
	project.Context.RootFiles = []string{"/src/b.ts", "/src/a.ts"}
	if program, err := OpenProgram(project, meter); err == nil || program != nil {
		t.Fatal("changed root order accepted")
	}
}
