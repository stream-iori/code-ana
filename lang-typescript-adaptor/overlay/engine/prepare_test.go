package engine

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
	"google.golang.org/protobuf/proto"
)

func preparationFixture(t *testing.T) (*pb.Prepare, *Meter) {
	t.Helper()
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	for name, text := range map[string]string{
		"a.ts":          "export function greet(value: number): number { return value + 1; }",
		"b.ts":          "import { greet as hello } from './a'; hello(1); hello(2);",
		"unused.ts":     "throw Error('not in compiler inputs')",
		"tsconfig.json": `{"compilerOptions":{"noLib":true,"module":"ESNext","moduleResolution":"bundler"},"files":["a.ts","b.ts"]}`,
	} {
		if err := os.WriteFile(filepath.Join(root, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	request := &pb.Prepare{Unit: "prepare-fixture", ProjectRoot: root, ConfigPath: root + "/tsconfig.json", PublishLanguages: []pb.SourceLanguage{pb.SourceLanguage_TYPESCRIPT}, AllowedRoots: []string{"/"}, Module: "fixture", Scope: "MAIN", World: "main", Scratch: t.TempDir(), InventoryBytes: 8 << 20, FileLimit: 1000, ScratchBytes: 8 << 20, CaseSensitive: boolPointer(true)}
	meter, err := NewMeter(context.Background(), &w.AnalysisBudgetGrant{Id: "prepare", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 10_000_000, MaxNodes: 10_000_000, MaxPayloadBytes: 1 << 30, MaxDepth: 256}}, &w.AnalysisBudgetUsage{})
	if err != nil {
		t.Fatal(err)
	}
	return request, meter
}

func TestActualCompilerPreparationReplaysAfterLiveInputsAreDeleted(t *testing.T) {
	request, meter := preparationFixture(t)
	project, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if len(project.Files) != 3 {
		for _, file := range project.Files {
			t.Log(file.File.Path)
		}
		t.Fatal("captured unrelated sources", len(project.Files))
	}
	if len(project.Context.Absent) == 0 {
		t.Fatal("compiler failed lookups were not captured")
	}
	for _, file := range project.Files {
		if err := os.Remove(file.File.Path); err != nil {
			t.Fatal(err)
		}
	}
	program, err := OpenProgram(project, meter)
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	if err := program.WalkCallBindings(context.Background(), func(call CallBinding) error {
		if !call.Resolved || call.TargetFile != request.ProjectRoot+"/a.ts" || call.TargetName != "greet" {
			t.Fatal(call)
		}
		count++
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if count != 2 {
		t.Fatal(count)
	}
}

func TestPreparationInventoryIsDeterministicAndCarriesOnlyIdentities(t *testing.T) {
	request, meter := preparationFixture(t)
	first, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	second, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(first, second) {
		t.Fatal("identical preparation changed observations")
	}
	archive, err := sealInventory(request, first, meter)
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(request.Scratch, archive.Path))
	if err != nil {
		t.Fatal(err)
	}
	var inventory pb.PreparationInventory
	if err := proto.Unmarshal(data, &inventory); err != nil {
		t.Fatal(err)
	}
	if len(inventory.Files) != 3 || archive.Count != 3 || archive.Bytes != uint64(len(data)) || !proto.Equal(inventory.Context, first.Context) {
		t.Fatal("wrong sealed inventory")
	}
	if _, err := sealInventory(request, first, meter); err == nil {
		t.Fatal("existing inventory overwritten")
	}
}

func TestPreparationRootsLinksAndQuotaAreEnforcedBeforeReading(t *testing.T) {
	request, meter := preparationFixture(t)
	request.AllowedRoots = []string{request.ProjectRoot}
	observed, err := NewObservedFS(request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := observed.ReadFile("/etc/hosts"); ok || observed.Check() == nil {
		t.Fatal("outside read accepted")
	}
	outside := t.TempDir()
	if err := os.Symlink(outside, request.ProjectRoot+"/escape"); err != nil {
		t.Fatal(err)
	}
	observed, err = NewObservedFS(request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if observed.DirectoryExists(request.ProjectRoot+"/escape") || observed.Check() == nil {
		t.Fatal("symlink escape accepted")
	}
	request.InventoryBytes = 8
	observed, err = NewObservedFS(request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := observed.ReadFile(request.ConfigPath); ok || observed.Check() == nil {
		t.Fatal("quota ignored")
	}
}

func TestPrepareOperationRunsThroughProductionExecute(t *testing.T) {
	request, meter := preparationFixture(t)
	wire := &pb.Request{Version: RequestVersion, RequestId: "prepare/1", ExpectedRuntime: RuntimeIdentity(), Grant: meter.grant, Seed: &w.AnalysisBudgetUsage{}, DeadlineMs: new(uint64), Operation: &pb.Request_Prepare{Prepare: request}}
	data, err := proto.Marshal(wire)
	if err != nil {
		t.Fatal(err)
	}
	completion := Execute(context.Background(), time.Now(), data, MaxResultBytes)
	var result pb.Result
	if err := proto.Unmarshal(completion.Bytes, &result); err != nil {
		t.Fatal(err)
	}
	if completion.State != CompleteState || result.GetPrepared() == nil || result.Receipt == nil || !result.Receipt.GetUsageKnown() {
		t.Fatal(completion.State, &result)
	}
}

func TestIncompleteDirectoryPresenceCannotCertifyMissingChildren(t *testing.T) {
	project := frozenFixture("/src")
	project.Context.Directories[0].ListingComplete = false
	project.Context.Directories[0].Files = nil
	frozen, err := NewFrozenFS(project, frozenMeter(t))
	if err != nil {
		t.Fatal(err)
	}
	if !frozen.DirectoryExists("/src") {
		t.Fatal("directory existence lost")
	}
	if frozen.FileExists("/src/unobserved.ts") || frozen.Check() == nil {
		t.Fatal("presence invented absence")
	}
}
