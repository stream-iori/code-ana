package engine

import (
	"context"
	"github.com/microsoft/typescript-go/internal/vfs"
	"strings"
	"testing"
)

type compilerFixtureFS struct {
	vfs.FS
	reads int
}

func (f *compilerFixtureFS) ReadFile(name string) (string, bool) {
	f.reads++
	if name == "selected.d.ts" {
		return "declare const selected: number;", true
	}
	return "", false
}
func TestCompilerWorkspaceFundsOnlyRequestedFilesBeforeKernelAccess(t *testing.T) {
	_, meter := preparationFixture(t)
	source := &compilerFixtureFS{}
	fs := &compilerBudgetFS{FS: source, meter: meter, seen: map[string]bool{}}
	before := meter.usage.PayloadBytes
	text, ok := fs.ReadFile("selected.d.ts")
	if !ok || text == "" || meter.usage.PayloadBytes != before+uint64(len(text))*32+4096 {
		t.Fatal("workspace not funded before return")
	}
	funded := meter.usage.PayloadBytes
	if _, ok := fs.ReadFile("selected.d.ts"); !ok || meter.usage.PayloadBytes != funded {
		t.Fatal("repeated immutable read charged twice")
	}
	if _, ok := fs.ReadFile("unselected.d.ts"); ok || meter.usage.PayloadBytes != funded {
		t.Fatal("unused library charged")
	}
	meter.grant.Limits.MaxPayloadBytes = funded
	if _, ok := fs.ReadFile("selected-again.d.ts"); ok {
		t.Fatal("missing file exposed")
	}
	// A fresh alias forces a workspace reservation; failure returns no text
	// and the sticky meter prevents any later compiler filesystem access.
	fs.seen = map[string]bool{}
	if _, ok := fs.ReadFile("selected.d.ts"); ok || meter.Check() == nil {
		t.Fatal("unfunded parser input exposed")
	}
	reads := source.reads
	if value, ok := fs.ReadFile("selected.d.ts"); ok || value != "" || source.reads != reads {
		t.Fatal("reads continued after refusal")
	}
}
func TestNestedProgramUsesPublicationRootForEverySemanticSource(t *testing.T) {
	program, _ := semanticFixture(t, map[string]string{"entry.ts": "/** publication root */\nexport function call() {}\ncall();"}, nil)
	program.publicationRoot = strings.TrimSuffix(program.context.Root, "/"+lastComponent(program.context.Root))
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	expected := lastComponent(program.context.Root) + "/entry.ts"
	if len(facts.Calls) != 1 || facts.Calls[0].File != expected || len(facts.Comments) != 1 || facts.Comments[0].File != expected {
		t.Fatalf("nested sources %#v", facts)
	}
	for _, d := range facts.Declarations {
		if d.File != expected {
			t.Fatal("context-relative declaration", d.File)
		}
	}
}
func lastComponent(name string) string { return name[strings.LastIndex(name, "/")+1:] }
