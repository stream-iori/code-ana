package engine

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
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

func TestPreparationWitnessesAncestorAbsenceButRejectsPositiveGlobalInputs(t *testing.T) {
	request, meter := preparationFixture(t)
	outer, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	child := filepath.Join(outer, "app")
	if err := os.Mkdir(child, 0700); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"a.ts", "b.ts", "tsconfig.json"} {
		data, err := os.ReadFile(filepath.Join(request.ProjectRoot, name))
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(child, name), data, 0600); err != nil {
			t.Fatal(err)
		}
	}
	request.ProjectRoot = child
	request.ConfigPath = filepath.Join(child, "tsconfig.json")
	request.AllowedRoots = []string{child}
	project, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	seen := false
	for _, lookup := range project.Context.Absent {
		if lookup.Path == filepath.Join(outer, "package.json") {
			seen = true
		}
	}
	if !seen {
		t.Fatal("ancestor absence was invented instead of observed")
	}
	if err := os.WriteFile(filepath.Join(outer, "package.json"), []byte(`{"type":"module"}`), 0600); err != nil {
		t.Fatal(err)
	}
	observed, err := NewObservedFS(request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if observed.FileExists(filepath.Join(outer, "package.json")) || observed.Check() == nil {
		t.Fatal("positive global package read accepted")
	}
	if err := os.Mkdir(filepath.Join(outer, "node_modules"), 0700); err != nil {
		t.Fatal(err)
	}
	observed, err = NewObservedFS(request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if observed.DirectoryExists(filepath.Join(outer, "node_modules")) || observed.Check() == nil {
		t.Fatal("positive global type root accepted")
	}
}

func TestProductStateIsOutsideTheCompilerViewAndNewGenerationsDoNotInvalidateInputs(t *testing.T) {
	request, meter := preparationFixture(t)
	request.AllowedRoots = []string{request.ProjectRoot}
	state := filepath.Join(request.ProjectRoot, ".code-ana")
	if err := os.Mkdir(state, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(state, "private.ts"), []byte("export const privateState = 1;"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(request.ConfigPath, []byte(`{"compilerOptions":{"noLib":true,"module":"ESNext","moduleResolution":"bundler"},"include":["**/*.ts"]}`), 0600); err != nil {
		t.Fatal(err)
	}
	first, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range first.Files {
		if filepath.Dir(file.File.Path) == state {
			t.Fatal("indexer state became a compiler input")
		}
	}
	if err := os.Mkdir(filepath.Join(state, "generation-next"), 0700); err != nil {
		t.Fatal(err)
	}
	second, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if !proto.Equal(first, second) {
		t.Fatal("generation-only state invalidated semantic input")
	}
	if _, err := OpenProgram(first, meter); err != nil {
		t.Fatal(err)
	}
	observed, err := NewObservedFS(request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := observed.ReadFile(filepath.Join(state, "private.ts")); ok || observed.Check() == nil {
		t.Fatal("explicit private state read accepted")
	}
}

// Each case uses the real resolver and then removes every observed file before
// checking bindings again. node_modules content is never flattened or mocked.
func TestResolutionClosureUsesCompilerOptionsAndReplaysWithoutLiveFiles(t *testing.T) {
	var cases []struct {
		Name             string            `json:"name"`
		Files            map[string]string `json:"files"`
		Links            map[string]string `json:"links"`
		Target           string            `json:"target"`
		PublishedSources int               `json:"published_sources"`
	}
	data, err := os.ReadFile("testdata/resolution.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) != 5 {
		t.Fatal("resolution matrix incomplete", len(cases))
	}

	for _, test := range cases {
		t.Run(test.Name, func(t *testing.T) {
			request, meter := preparationFixture(t)
			request.AllowedRoots = []string{request.ProjectRoot}
			for name, text := range test.Files {
				destination := filepath.Join(request.ProjectRoot, name)
				if err := os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(destination, []byte(text), 0600); err != nil {
					t.Fatal(err)
				}
			}
			for name, target := range test.Links {
				if err := os.Symlink(target, filepath.Join(request.ProjectRoot, name)); err != nil {
					t.Fatal(err)
				}
			}
			project, err := DiscoverProject(context.Background(), request, meter)
			if err != nil {
				t.Fatal(err)
			}
			repeated, err := DiscoverProject(context.Background(), request, meter)
			if err != nil {
				t.Fatal(err)
			}
			if !proto.Equal(project, repeated) {
				t.Fatal("unchanged resolver closure changed identity")
			}
			found := false
			published := 0
			for _, file := range project.Files {
				if file.File.Role == pb.FileRole_PUBLISH {
					published++
				}
				if strings.HasSuffix(file.File.Path, "/runtime.js") || strings.HasSuffix(file.File.Path, "/runtime.cjs") {
					t.Fatal("runtime condition selected instead of types", file.File.Path)
				}
				if file.File.Path == request.ProjectRoot+test.Target {
					found = true
				}
				if strings.Contains(file.File.Path, "/node_modules/") && file.File.Role != pb.FileRole_AUXILIARY && file.File.Language != pb.SourceLanguage_SOURCE_LANGUAGE_UNSPECIFIED {
					t.Fatal("dependency became a published source", file.File)
				}
			}
			if !found {
				t.Fatal("expected resolution target absent", test.Target)
			}
			if published != test.PublishedSources {
				t.Fatal("publication role differs from fixture truth", published, test.PublishedSources)
			}
			if len(test.Links) > 0 && len(project.Context.Links) == 0 {
				t.Fatal("pnpm symlink chain not captured")
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
				if !call.Resolved || call.TargetName != "greet" || call.TargetFile != request.ProjectRoot+test.Target {
					t.Fatal(call)
				}
				count++
				return nil
			}); err != nil {
				t.Fatal(err)
			}
			if count != 2 {
				t.Fatal("lost repeated call sites", count)
			}
		})
	}
}

func TestMissingReferencedDeclarationsStayMissingAndFrozen(t *testing.T) {
	request, meter := preparationFixture(t)
	request.AllowedRoots = []string{request.ProjectRoot}
	for name, text := range map[string]string{
		"tsconfig.json":      `{"compilerOptions":{"noLib":true,"types":[],"module":"ESNext","moduleResolution":"bundler"},"files":["entry.ts"],"references":[{"path":"./lib"}]}`,
		"entry.ts":           `import { greet } from "./lib/src/library"; greet(1); greet(2);`,
		"lib/tsconfig.json":  `{"compilerOptions":{"noLib":true,"composite":true,"declaration":true,"rootDir":"src","outDir":"dist"},"files":["src/library.ts"]}`,
		"lib/src/library.ts": `export function greet(value: number) {return value + 1;}`,
	} {
		destination := filepath.Join(request.ProjectRoot, name)
		if err := os.MkdirAll(filepath.Dir(destination), 0700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(destination, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	project, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if len(project.Context.References) != 1 || project.Context.References[0] != request.ProjectRoot+"/lib/tsconfig.json" {
		t.Fatal("missing reference identity", project.Context.References)
	}
	witnessed := false
	for _, lookup := range project.Context.Absent {
		if lookup.Path == request.ProjectRoot+"/lib/dist/library.d.ts" && lookup.Kind == pb.LookupKind_FILE {
			witnessed = true
		}
	}
	if !witnessed {
		t.Fatal("missing declaration output was not observed")
	}
	for _, file := range project.Files {
		if file.File.Path == request.ProjectRoot+"/lib/src/library.ts" && file.File.Role == pb.FileRole_PUBLISH {
			t.Fatal("referenced implementation became a consumer declaration target")
		}
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
	calls := 0
	if err := program.WalkCallBindings(context.Background(), func(call CallBinding) error {
		if call.Resolved {
			t.Fatal("missing declaration became an exact implementation", call)
		}
		calls++
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatal("unresolved call sites lost", calls)
	}
	project.Context.References = nil
	if _, err := OpenProgram(project, meter); err == nil {
		t.Fatal("forged reference identity accepted")
	}
}

func TestCaseInsensitivePreparationRecordsPhysicalFileAndLinkSpelling(t *testing.T) {
	request, meter := preparationFixture(t)
	request.AllowedRoots = []string{request.ProjectRoot}
	actual := filepath.Join(request.ProjectRoot, "Actual.ts")
	if err := os.WriteFile(actual, []byte(`export function greet(value: number) {return value+1;}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(request.ProjectRoot, "actual.ts")); err != nil {
		t.Skip("case-sensitive target volume")
	}
	request.CaseSensitive = boolPointer(false)
	if err := os.Symlink("Actual.ts", filepath.Join(request.ProjectRoot, "Link.ts")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(request.ProjectRoot, "entry.ts"), []byte(`import {greet as one} from "./actual"; import {greet as two} from "./link"; one(1); two(2);`), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(request.ConfigPath, []byte(`{"compilerOptions":{"noLib":true,"types":[],"module":"ESNext","moduleResolution":"bundler"},"files":["entry.ts"]}`), 0600); err != nil {
		t.Fatal(err)
	}
	project, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	seen := false
	for _, file := range project.Files {
		if file.File.Path == actual {
			seen = true
		}
		if file.File.Path == filepath.Join(request.ProjectRoot, "actual.ts") {
			t.Fatal("query casing became public source identity")
		}
	}
	if !seen {
		t.Fatal("physical source spelling missing")
	}
	if len(project.Context.Links) != 1 || project.Context.Links[0].Path != filepath.Join(request.ProjectRoot, "Link.ts") {
		t.Fatal("physical link spelling missing", project.Context.Links)
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
	calls := 0
	if err := program.WalkCallBindings(context.Background(), func(call CallBinding) error {
		if !call.Resolved || call.TargetFile != actual {
			t.Fatal(call)
		}
		calls++
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatal("case/link aliases lost calls", calls)
	}
}

func TestRootlessSolutionKeepsReferenceControlsWithoutPublishingSources(t *testing.T) {
	request, meter := preparationFixture(t)
	leaf := filepath.Join(request.ProjectRoot, "leaf")
	if err := os.Mkdir(leaf, 0700); err != nil {
		t.Fatal(err)
	}
	for name, text := range map[string]string{
		request.ConfigPath:                   `{"compilerOptions":{"noLib":true},"files":[],"references":[{"path":"./leaf"}]}`,
		filepath.Join(leaf, "tsconfig.json"): `{"compilerOptions":{"noLib":true,"composite":true},"files":["index.ts"]}`,
		filepath.Join(leaf, "index.ts"):      "export const answer = 42;",
	} {
		if err := os.WriteFile(name, []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	project, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	if len(project.Context.RootFiles) != 0 || len(project.Context.References) != 1 || project.Context.References[0] != filepath.Join(leaf, "tsconfig.json") {
		t.Fatal("rootless solution lost compiler reference identity", project.Context)
	}
	controls := map[string]bool{}
	for _, file := range project.Files {
		if file.File.Role == pb.FileRole_PUBLISH {
			t.Fatal("solution published a referenced project source", file.File)
		}
		controls[file.File.Path] = true
		if err := os.Remove(file.File.Path); err != nil {
			t.Fatal(err)
		}
	}
	if !controls[request.ConfigPath] || !controls[filepath.Join(leaf, "tsconfig.json")] {
		t.Fatal("solution/reference controls missing", controls)
	}
	program, err := OpenProgram(project, meter)
	if err != nil {
		t.Fatal(err)
	}
	if err := program.WalkCallBindings(context.Background(), func(CallBinding) error { t.Fatal("empty solution emitted calls"); return nil }); err != nil {
		t.Fatal(err)
	}
}

func TestNestedProgramsCannotObserveAncestorIndexerState(t *testing.T) {
	request, meter := preparationFixture(t)
	parent := request.ProjectRoot
	request.ProjectRoot = filepath.Join(parent, "nested")
	if err := os.Mkdir(request.ProjectRoot, 0700); err != nil {
		t.Fatal(err)
	}
	state := filepath.Join(parent, ".code-ana")
	if err := os.Mkdir(state, 0700); err != nil {
		t.Fatal(err)
	}
	secret := filepath.Join(state, "private.ts")
	if err := os.WriteFile(secret, []byte("export const secret = 1"), 0600); err != nil {
		t.Fatal(err)
	}
	request.AllowedRoots = []string{parent}
	observed, err := NewObservedFS(request, meter)
	if err != nil {
		t.Fatal(err)
	}
	observed.GetAccessibleEntries(parent)
	if observed.Check() != nil {
		t.Fatal(observed.Check())
	}
	inventory := observed.directories[observed.key(parent)]
	if inventory == nil {
		t.Fatal("parent was not observed")
	}
	for _, name := range inventory.Directories {
		if name == ".code-ana" {
			t.Fatal("ancestor state enumerated")
		}
	}
	if _, ok := observed.ReadFile(secret); ok || observed.Check() == nil {
		t.Fatal("ancestor state read accepted")
	}
}
