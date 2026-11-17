package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io/fs"
	"os"
	"path/filepath"
	"testing"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
)

func boolPointer(value bool) *bool { return &value }

func frozenFile(name, text string, role pb.FileRole) *pb.FrozenFile {
	digest := sha256.Sum256([]byte(text))
	return &pb.FrozenFile{File: &pb.FileIdentity{Path: name, Sha256: hex.EncodeToString(digest[:]), Size: uint64(len(text)), Role: role, Language: pb.SourceLanguage_TYPESCRIPT, Module: "fixture", Scope: "MAIN", World: "main"}, Content: []byte(text)}
}

func frozenFixture(root string) *pb.FrozenProject {
	return &pb.FrozenProject{Context: &pb.ProjectContext{Unit: "fixture", Root: root, CaseSensitive: boolPointer(true), PublishLanguages: []pb.SourceLanguage{pb.SourceLanguage_TYPESCRIPT}, Directories: []*pb.Directory{{ListingComplete: true, Path: root, Files: []string{"a.ts"}}}}, Files: []*pb.FrozenFile{frozenFile(root+"/a.ts", "export const captured = 1;", pb.FileRole_PUBLISH)}}
}

func frozenMeter(t *testing.T) *Meter {
	t.Helper()
	meter, err := NewMeter(context.Background(), &w.AnalysisBudgetGrant{Id: "frozen", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 100000, MaxNodes: 100000, MaxPayloadBytes: 8 << 20, MaxDepth: 64}}, &w.AnalysisBudgetUsage{})
	if err != nil {
		t.Fatal(err)
	}
	return meter
}

func TestFrozenBytesAndListingsNeverReadTheChangedOrDeletedLiveFiles(t *testing.T) {
	root := t.TempDir()
	project := frozenFixture(root)
	file := filepath.Join(root, "a.ts")
	if err := os.WriteFile(file, []byte("changed live bytes"), 0600); err != nil {
		t.Fatal(err)
	}
	frozen, err := NewFrozenFS(project, frozenMeter(t))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(file); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "new.ts"), []byte("live only"), 0600); err != nil {
		t.Fatal(err)
	}
	if text, ok := frozen.ReadFile(file); !ok || text != "export const captured = 1;" {
		t.Fatal(text, ok)
	}
	if frozen.FileExists(filepath.Join(root, "new.ts")) {
		t.Fatal("uncaptured live file observed")
	}
	entries := frozen.GetAccessibleEntries(root)
	if len(entries.Files) != 1 || entries.Files[0] != "a.ts" || frozen.Check() != nil {
		t.Fatal(entries, frozen.Check())
	}
}

func TestUnknownReadsAndInvalidPathsFailInsteadOfInventingAbsence(t *testing.T) {
	for _, name := range []string{"relative.ts", "/outside/unobserved.ts"} {
		frozen, err := NewFrozenFS(frozenFixture("/src"), frozenMeter(t))
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := frozen.ReadFile(name); ok || frozen.Check() == nil {
			t.Fatal("unobserved read accepted", name)
		}
	}
	project := frozenFixture("/src")
	project.Context.Directories[0].Files = append(project.Context.Directories[0].Files, "unread.ts")
	frozen, err := NewFrozenFS(project, frozenMeter(t))
	if err != nil {
		t.Fatal(err)
	}
	if !frozen.FileExists("/src/unread.ts") {
		t.Fatal("captured positive entry lost")
	}
	if _, ok := frozen.ReadFile("/src/unread.ts"); ok || frozen.Check() == nil {
		t.Fatal("missing captured content accepted")
	}
}

func TestOnlyDeclaredLinksInsideTheirAllowedRootsCanResolve(t *testing.T) {
	project := frozenFixture("/src")
	project.Files = []*pb.FrozenFile{frozenFile("/deps/pkg/a.ts", "dependency bytes", pb.FileRole_AUXILIARY)}
	project.Context.Directories = []*pb.Directory{{ListingComplete: true, Path: "/src", Directories: []string{"link"}}, {ListingComplete: true, Path: "/deps/pkg", Files: []string{"a.ts"}}}
	project.Context.Links = []*pb.SymbolicLink{{Path: "/src/link", Target: "/deps/pkg", AllowedRoot: "/deps"}}
	frozen, err := NewFrozenFS(project, frozenMeter(t))
	if err != nil {
		t.Fatal(err)
	}
	if text, ok := frozen.ReadFile("/src/link/a.ts"); !ok || text != "dependency bytes" {
		t.Fatal(text, ok)
	}
	if frozen.Realpath("/src/link/a.ts") != "/deps/pkg/a.ts" || frozen.Check() != nil {
		t.Fatal(frozen.Check())
	}
	project.Context.Links[0].AllowedRoot = "/src"
	if _, err := NewFrozenFS(project, frozenMeter(t)); err == nil {
		t.Fatal("link escaped its root")
	}
	project.Context.Links = []*pb.SymbolicLink{{Path: "/src/a", Target: "b", AllowedRoot: "/src"}, {Path: "/src/b", Target: "a", AllowedRoot: "/src"}}
	if _, err := NewFrozenFS(project, frozenMeter(t)); err == nil {
		t.Fatal("link cycle accepted")
	}
}

func TestConflictingFrozenIdentityAndDisabledPublicationAreRejected(t *testing.T) {
	for _, edit := range []func(*pb.FrozenProject){
		func(p *pb.FrozenProject) { p.Files[0].File.Sha256 = "bad" },
		func(p *pb.FrozenProject) { p.Files = append(p.Files, p.Files[0]) },
		func(p *pb.FrozenProject) {
			p.Context.Absent = []*pb.AbsentLookup{{Path: "/src/a.ts", Kind: pb.LookupKind_FILE}}
		},
		func(p *pb.FrozenProject) {
			p.Context.PublishLanguages = []pb.SourceLanguage{pb.SourceLanguage_JAVASCRIPT}
		},
		func(p *pb.FrozenProject) { p.Files[0].File.Role = pb.FileRole_FILE_ROLE_UNSPECIFIED },
	} {
		p := frozenFixture("/src")
		edit(p)
		if _, err := NewFrozenFS(p, frozenMeter(t)); err == nil {
			t.Fatal("invalid frozen identity accepted", p)
		}
	}
}

func TestFrozenCaseRulesPreserveKernelUnicodeAndOriginalRealpaths(t *testing.T) {
	project := frozenFixture("/Source")
	project.Context.CaseSensitive = boolPointer(false)
	project.Context.Directories[0].Files = []string{"A.ts", "İ.ts", "ı.ts"}
	project.Files = []*pb.FrozenFile{
		frozenFile("/Source/A.ts", "ASCII", pb.FileRole_PUBLISH),
		frozenFile("/Source/İ.ts", "dotted", pb.FileRole_PUBLISH),
		frozenFile("/Source/ı.ts", "dotless", pb.FileRole_PUBLISH),
	}
	frozen, err := NewFrozenFS(project, frozenMeter(t))
	if err != nil {
		t.Fatal(err)
	}
	for name, want := range map[string]string{"/SOURCE/a.TS": "ASCII", "/source/İ.TS": "dotted", "/source/ı.TS": "dotless"} {
		if got, ok := frozen.ReadFile(name); !ok || got != want {
			t.Fatal(name, got, ok)
		}
	}
	if frozen.FileExists("/source/i.ts") || frozen.Check() != nil {
		t.Fatal("kernel Unicode casing was replaced by generic lowercasing", frozen.Check())
	}
	if frozen.UseCaseSensitiveFileNames() || frozen.Realpath("/SOURCE/a.TS") != "/Source/A.ts" {
		t.Fatal("lost captured case or spelling")
	}
	// Metadata must remain sealed after construction.
	project.Context.Directories[0].Files[0] = "mutated.ts"
	if frozen.GetAccessibleEntries("/SOURCE").Files[0] != "A.ts" {
		t.Fatal("borrowed mutable directory metadata")
	}
}

func TestFrozenRejectsAbsentCaseRulesCollisionsAndContradictoryListings(t *testing.T) {
	for _, edit := range []func(*pb.FrozenProject){
		func(p *pb.FrozenProject) { p.Context.CaseSensitive = nil },
		func(p *pb.FrozenProject) {
			p.Context.CaseSensitive = boolPointer(false)
			p.Files = append(p.Files, frozenFile("/SRC/A.TS", "collision", pb.FileRole_PUBLISH))
		},
		func(p *pb.FrozenProject) {
			p.Context.CaseSensitive = boolPointer(false)
			p.Context.Directories[0].Files = []string{"a.ts", "A.TS"}
		},
		func(p *pb.FrozenProject) { p.Context.Directories[0].Files = nil },
		func(p *pb.FrozenProject) {
			p.Context.Directories[0].Files = nil
			p.Context.Directories[0].Directories = []string{"a.ts"}
		},
		func(p *pb.FrozenProject) {
			p.Context.Absent = []*pb.AbsentLookup{{Path: "/src", Kind: pb.LookupKind_DIRECTORY}}
		},
		func(p *pb.FrozenProject) {
			p.Context.Directories[0].Files = append(p.Context.Directories[0].Files, "missing.ts")
			p.Context.Absent = []*pb.AbsentLookup{{Path: "/src/missing.ts", Kind: pb.LookupKind_FILE}}
		},
		func(p *pb.FrozenProject) {
			p.Context.Links = []*pb.SymbolicLink{{Path: "/src/a.ts", Target: "b.ts", AllowedRoot: "/src"}}
		},
	} {
		project := frozenFixture("/src")
		edit(project)
		if _, err := NewFrozenFS(project, frozenMeter(t)); err == nil {
			t.Fatal("contradictory view accepted", project)
		}
	}
}

func TestFrozenWalkDirDoesNotFollowLinksAndHonorsSkipDirOnFiles(t *testing.T) {
	project := frozenFixture("/src")
	project.Context.Directories[0].Files = []string{"a.ts", "b.ts"}
	project.Context.Directories[0].Directories = []string{"loop"}
	project.Context.Links = []*pb.SymbolicLink{{Path: "/src/loop", Target: "/src", AllowedRoot: "/src"}}
	project.Files = append(project.Files, frozenFile("/src/b.ts", "b", pb.FileRole_PUBLISH))
	frozen, err := NewFrozenFS(project, frozenMeter(t))
	if err != nil {
		t.Fatal(err)
	}
	var seen []string
	err = frozen.WalkDir("/src", func(name string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		seen = append(seen, name)
		if name == "/src/loop" && entry.Type()&fs.ModeSymlink == 0 {
			t.Fatal("link followed")
		}
		return nil
	})
	if err != nil || len(seen) != 4 {
		t.Fatal(seen, err)
	}
	seen = nil
	err = frozen.WalkDir("/src", func(name string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		seen = append(seen, name)
		if name == "/src/a.ts" {
			return fs.SkipDir
		}
		return nil
	})
	if err != nil || len(seen) != 2 {
		t.Fatal(seen, err)
	}
	if frozen.WriteFile("/src/a.ts", "replacement") == nil || frozen.Check() == nil {
		t.Fatal("mutation attempt did not poison frozen view")
	}
}
