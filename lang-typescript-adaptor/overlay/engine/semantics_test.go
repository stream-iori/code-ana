package engine

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
	"google.golang.org/protobuf/proto"
)

func semanticFixture(t *testing.T, sources map[string]string, auxiliary map[string]bool) (*Program, *Meter) {
	t.Helper()
	request, meter := preparationFixture(t)
	request.AllowedRoots = []string{request.ProjectRoot}
	for _, name := range []string{"a.ts", "b.ts", "unused.ts"} {
		if err := os.Remove(filepath.Join(request.ProjectRoot, name)); err != nil {
			t.Fatal(err)
		}
	}
	for name, text := range sources {
		if err := os.WriteFile(filepath.Join(request.ProjectRoot, name), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	// Source enumeration order must not select the first overload/merge member.
	// An explicit config makes the same public facts independent of map order.
	config := `{"compilerOptions":{"noLib":true,"allowJs":true,"checkJs":true,"module":"ESNext","moduleResolution":"bundler","strict":true,"jsx":"preserve","types":[]},"include":["*.ts","*.js","*.tsx","*.jsx"]}`
	if err := os.WriteFile(request.ConfigPath, []byte(config), 0600); err != nil {
		t.Fatal(err)
	}
	request.PublishLanguages = []pb.SourceLanguage{pb.SourceLanguage_TYPESCRIPT, pb.SourceLanguage_JAVASCRIPT}
	project, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range project.Files {
		if !beneath(file.File.Path, request.ProjectRoot) {
			t.Fatal("fixture captured an external body", file.File.Path)
		}
		if auxiliary[filepath.Base(file.File.Path)] {
			file.File.Role = pb.FileRole_AUXILIARY
			file.File.Module = ""
			file.File.Scope = ""
			file.File.World = ""
		}
		if err := os.Remove(file.File.Path); err != nil {
			t.Fatal(err)
		}
	}
	program, err := OpenProgram(project, meter)
	if err != nil {
		t.Fatal(err)
	}
	return program, meter
}
func declarationsNamed(facts *SemanticFacts, name string) []SemanticDeclaration {
	var result []SemanticDeclaration
	for _, decl := range facts.Declarations {
		if decl.Name == name {
			result = append(result, decl)
		}
	}
	return result
}
func callsIn(facts *SemanticFacts, source string) []SemanticCall {
	var result []SemanticCall
	for _, call := range facts.Calls {
		if call.File == source {
			result = append(result, call)
		}
	}
	return result
}

func TestSemanticDeclarationTablePreservesMergedSpacesAndOverloadProvenance(t *testing.T) {
	source := `export class Box { value = 1; }
export namespace Box { export const version = 1; }
export interface Shape { a: number; }
export interface Shape { b: number; }
export function choose(value: number): number;
export function choose(value: string): string;
export function choose(value: number | string): number | string { return value; }
choose(1); choose("ok"); choose(true);
`
	program, _ := semanticFixture(t, map[string]string{"entry.ts": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	box := declarationsNamed(facts, "Box")
	if len(box) != 2 || box[0].SymbolGroup == "" || box[0].SymbolGroup != box[1].SymbolGroup || !box[0].ValueSpace || !box[0].TypeSpace || !box[0].NamespaceSpace {
		t.Fatal("merged class/namespace provenance", box)
	}
	shapes := declarationsNamed(facts, "Shape")
	if len(shapes) != 2 || shapes[0].SymbolGroup != shapes[1].SymbolGroup || shapes[0].ValueSpace || !shapes[0].TypeSpace {
		t.Fatal("interface merging", shapes)
	}
	choices := declarationsNamed(facts, "choose")
	if len(choices) != 3 {
		t.Fatal(choices)
	}
	var body string
	for _, d := range choices {
		if d.SymbolGroup != choices[0].SymbolGroup || d.SymbolGroup == "" {
			t.Fatal("overload group lost", choices)
		}
		if d.Implementation {
			if body != "" {
				t.Fatal("duplicate body")
			}
			body = d.ID
		}
	}
	if body == "" || len(facts.Calls) != 3 {
		t.Fatal(body, facts.Calls)
	}
	for _, call := range facts.Calls[:2] {
		if call.TargetKind != "implementation" || call.Target != body || call.SelectedDeclaration == body || call.SelectedDeclaration == "" {
			t.Fatal("valid overload target", call)
		}
	}
	if call := facts.Calls[2]; call.TargetKind != "unknown" || call.Target != "" || call.Reason != "call_type_error" {
		t.Fatal("error recovery became exact", call)
	}
}

func TestSemanticCallsSeparateJSBindingSignaturesExternalAndUnknown(t *testing.T) {
	sources := map[string]string{
		"a.ts": "export function greet(value: number) { return value + 1; }\n",
		"b.js": "import { greet as hello } from './a'; hello(1); hello(2);\n",
		"entry.ts": `import {greet} from './a';
interface Runner { run(value: number): number; }
function invoke(callback: (value: number) => number, runner: Runner) { callback(1); runner.run(2); }
declare let dynamic: any;
dynamic();
declare let choice: ((value: number) => number) | ((value: number) => string);
choice(1);
const broken: number = "unrelated";
greet(3);
external(4);
const wrapper = () => greet(5);
`,
		"external.d.ts": "declare function external(value: number): number;\n",
	}
	program, _ := semanticFixture(t, sources, map[string]bool{"external.d.ts": true})
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	greet := declarationsNamed(facts, "greet")
	var implementationID string
	for _, decl := range greet {
		if decl.Implementation {
			implementationID = decl.ID
		}
	}
	if implementationID == "" {
		t.Fatal(greet)
	}
	js := callsIn(facts, "b.js")
	if len(js) != 2 {
		t.Fatal(js)
	}
	for _, call := range js {
		if call.Language != "javascript" || call.TargetKind != "implementation" || call.Target != implementationID {
			t.Fatal("JS alias binding", call)
		}
	}
	entry := callsIn(facts, "entry.ts")
	if len(entry) != 7 {
		t.Fatal(entry)
	}
	if entry[0].TargetKind != "declaration" || entry[1].TargetKind != "declaration" {
		t.Fatal("parameter/interface became implementation", entry[:2])
	}
	if entry[2].TargetKind != "unknown" || entry[3].TargetKind != "unknown" {
		t.Fatal("dynamic/union became precise", entry[2:4])
	}
	if entry[4].TargetKind != "implementation" || entry[4].Target != implementationID {
		t.Fatal("unrelated error disabled binding", entry[4])
	}
	if entry[5].TargetKind != "external" || !strings.HasPrefix(entry[5].External, "external.d.ts@") || !strings.Contains(entry[5].External, "#external:") || entry[5].Target != "" {
		t.Fatal("auxiliary declaration promoted", entry[5])
	}
	if entry[6].TargetKind != "implementation" || entry[6].Caller == entry[4].Caller {
		t.Fatal("arrow callable owner lost", entry[6])
	}
	for _, decl := range facts.Declarations {
		if decl.File == "external.d.ts" {
			t.Fatal("auxiliary declaration published", decl)
		}
	}
}

func TestSemanticIdentitiesAndCoordinatesIgnoreCaptureRootAndScratch(t *testing.T) {
	source := "\ufeff// 😀\r\nexport function greet(名: number) { return 名; }\r\ngreet(1);greet(1);"
	program, _ := semanticFixture(t, map[string]string{"entry.ts": source}, nil)
	first, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	program, _ = semanticFixture(t, map[string]string{"entry.ts": source}, nil)
	second, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(first, second) {
		t.Fatal("machine path or compiler ID leaked into facts", first, second)
	}
	greet := declarationsNamed(first, "greet")
	if len(greet) != 1 || greet[0].NameSpan.Start.Line != 2 || greet[0].NameSpan.Start.Column != 17 || greet[0].NameSpan.Start.Byte != uint64(strings.Index(source, "greet")) {
		t.Fatal(greet)
	}
	parameter := declarationsNamed(first, "名")
	if len(parameter) != 1 || parameter[0].NameSpan.Start.Column != 23 || parameter[0].NameSpan.End.Column != 24 {
		t.Fatal(parameter)
	}
	if len(first.Calls) != 2 || first.Calls[0].ID == first.Calls[1].ID || first.Calls[0].Ordinal != 0 || first.Calls[1].Ordinal != 1 {
		t.Fatal("repeated call sites collapsed", first.Calls)
	}
}

func TestSemanticExtractionKeepsSeedAndReturnsNoPartialFactsOnRefusal(t *testing.T) {
	for _, resource := range []string{"work", "payload_bytes", "depth", "cancel"} {
		t.Run(resource, func(t *testing.T) {
			program, old := semanticFixture(t, map[string]string{"entry.ts": "export function greet(value: number) { return value; } greet(1);"}, nil)
			seed := old.Receipt("backend", true).Usage
			grant := proto.Clone(old.grant).(*w.AnalysisBudgetGrant)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			switch resource {
			case "work":
				grant.Limits.MaxWork = seed.Work + 1
			case "payload_bytes":
				grant.Limits.MaxPayloadBytes = seed.PayloadBytes + 1
			case "depth":
				grant.Limits.MaxDepth = seed.MaxDepth + 1
			case "cancel":
				cancel()
			}
			meter, err := NewMeter(ctx, grant, seed)
			if err != nil {
				t.Fatal(err)
			}
			program.meter = meter
			program.frozen.meter = meter
			facts, err := program.Semantics(ctx)
			if err == nil || facts != nil || meter.Check() != err {
				t.Fatal("partial semantic result escaped", facts, err)
			}
			receipt := meter.Receipt("extract", true)
			if receipt.Usage.Work < seed.Work || receipt.Usage.PayloadBytes < seed.PayloadBytes || receipt.Usage.Nodes < seed.Nodes || receipt.Usage.Failure == nil {
				t.Fatal("seed or failure disappeared", receipt)
			}
			if resource != "cancel" && receipt.Usage.Failure.Resource != resource {
				t.Fatal(receipt)
			}
			if meter.Charge(1, 1, 1) != err {
				t.Fatal("failure was not sticky")
			}
		})
	}
}

func TestSemanticGlobalMergesShadowingVisibilityAndEscapedNames(t *testing.T) {
	sources := map[string]string{
		"a.ts":      "interface GlobalShape { first: number; }\nfunction same() { return 1; }\nfunction outer() { function same() { return 2; } same(); }\nsame();\n",
		"b.ts":      "interface GlobalShape { second: number; }\n",
		"module.ts": "export {};\ninterface GlobalShape { isolated: number; }\nexport class Box { value = 1; protected guarded = 2; private hidden = 3; }\nnamespace LocalNamespace { const hiddenMember = 1; export const visibleMember = 2; }\nexport function \\u0066oo() { const local = 1; return local; }\nfoo();\n",
	}
	program, _ := semanticFixture(t, sources, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	shapes := declarationsNamed(facts, "GlobalShape")
	if len(shapes) != 3 {
		t.Fatal(shapes)
	}
	if shapes[0].SymbolGroup == "" || shapes[0].SymbolGroup != shapes[1].SymbolGroup || shapes[2].SymbolGroup == shapes[0].SymbolGroup {
		t.Fatal("global/module namespaces collapsed", shapes)
	}
	for name, visibility := range map[string]string{"value": "public", "guarded": "protected", "hidden": "private", "hiddenMember": "namespace", "visibleMember": "public", "local": "local", "foo": "public"} {
		decls := declarationsNamed(facts, name)
		if len(decls) != 1 || decls[0].Visibility != visibility {
			t.Fatal(name, visibility, decls)
		}
	}
	foo := declarationsNamed(facts, "foo")[0]
	if !strings.Contains(foo.QualifiedName, "::foo") || foo.NameSpan.End.Byte-foo.NameSpan.Start.Byte != 8 {
		t.Fatal("escaped identifier lost semantic name or raw range", foo)
	}
	same := declarationsNamed(facts, "same")
	if len(same) != 2 || same[0].ID == same[1].ID || same[0].SymbolGroup == same[1].SymbolGroup {
		t.Fatal("shadowed declarations collapsed", same)
	}
	calls := callsIn(facts, "a.ts")
	if len(calls) != 2 || calls[0].Target == calls[1].Target || calls[0].Caller == calls[1].Caller {
		t.Fatal("lexical call ownership lost", calls)
	}
}

func TestNamedDeclarationIdentitySurvivesBodyAndUnrelatedPrefixEdits(t *testing.T) {
	before := "export function greet(value: number) { return value; }\nconst wrapper = () => greet(1);\n"
	after := "const unrelated = 1;\nexport function greet(value: number) { const extra = 2; return value + extra; }\nconst wrapper = () => greet(1);\n"
	p, _ := semanticFixture(t, map[string]string{"entry.ts": before}, nil)
	first, err := p.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	p, _ = semanticFixture(t, map[string]string{"entry.ts": after}, nil)
	second, err := p.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"greet", "value", "wrapper", "<function>"} {
		a, b := declarationsNamed(first, name), declarationsNamed(second, name)
		if len(a) != 1 || len(b) != 1 || a[0].ID != b[0].ID || a[0].SymbolGroup != b[0].SymbolGroup {
			t.Fatal("unrelated edit changed named scope identity", name, a, b)
		}
	}
	if first.Calls[0].Target != second.Calls[0].Target || first.Calls[0].Caller != second.Calls[0].Caller {
		t.Fatal("unchanged binding changed endpoints", first.Calls, second.Calls)
	}
}

func TestAuxiliaryImplementationBodiesCannotBecomePublishedEndpoints(t *testing.T) {
	program, _ := semanticFixture(t, map[string]string{
		"a.js":     "export function hidden(value) { return value + 1; }",
		"entry.ts": "import {hidden} from './a'; hidden(1);",
	}, map[string]bool{"a.js": true})
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	for _, decl := range facts.Declarations {
		if decl.File == "a.js" {
			t.Fatal("auxiliary implementation published", decl)
		}
	}
	if len(facts.Calls) != 1 || facts.Calls[0].TargetKind != "external" || facts.Calls[0].Target != "" || !strings.HasPrefix(facts.Calls[0].External, "a.js@") {
		t.Fatal("auxiliary body became a precise endpoint", facts.Calls)
	}
	for _, ref := range facts.References {
		for _, target := range ref.Targets {
			for _, decl := range facts.Declarations {
				if decl.ID == target && decl.File == "a.js" {
					t.Fatal(ref)
				}
			}
		}
	}
}

func TestFilteredEmptySemanticsNeverHidesAFailedFrozenView(t *testing.T) {
	program, _ := semanticFixture(t, map[string]string{"entry.ts": "export function hidden() { return 1; } hidden();"}, map[string]bool{"entry.ts": true})
	facts, err := program.Semantics(context.Background())
	if err != nil || len(facts.Declarations) != 0 || len(facts.Calls) != 0 || len(facts.References) != 0 {
		t.Fatal("filtered source leaked facts", facts, err)
	}
	failure := invalid("frozen observation failed")
	program.frozen.fail(failure)
	facts, err = program.Semantics(context.Background())
	if facts != nil || err != failure {
		t.Fatal("empty publication hid frozen-view failure", facts, err)
	}
}

func TestSemanticStandardLibraryTargetsRemainExternalAndVersioned(t *testing.T) {
	request, meter := preparationFixture(t)
	request.AllowedRoots = []string{request.ProjectRoot}
	source := "export function greet(value: number) { return Math.max(value, 1); }"
	if err := os.WriteFile(filepath.Join(request.ProjectRoot, "a.ts"), []byte(source), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(request.ConfigPath, []byte(`{"compilerOptions":{"types":[],"module":"ESNext","moduleResolution":"bundler"},"files":["a.ts","b.ts"]}`), 0600); err != nil {
		t.Fatal(err)
	}
	project, err := DiscoverProject(context.Background(), request, meter)
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range project.Files {
		if !beneath(file.File.Path, request.ProjectRoot) {
			t.Fatal("fixture captured external body", file.File.Path)
		}
		if err := os.Remove(file.File.Path); err != nil {
			t.Fatal(err)
		}
	}
	program, err := OpenProgram(project, meter)
	if err != nil {
		t.Fatal(err)
	}
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(facts.Calls) != 3 {
		t.Fatal(facts.Calls)
	}
	libraryCall := facts.Calls[0]
	if libraryCall.TargetKind != "external" || libraryCall.Target != "" || !strings.HasPrefix(libraryCall.External, "typescript-lib/lib.es5.d.ts@"+RuntimeIdentity().StandardLibraryDigest+"#max:") {
		t.Fatal("stdlib was published or lost version identity", libraryCall)
	}
	for _, decl := range facts.Declarations {
		if decl.File != "a.ts" && decl.File != "b.ts" {
			t.Fatal("stdlib declaration published", decl)
		}
	}
	mathReads := 0
	for _, ref := range facts.References {
		if ref.File == "a.ts" && ref.Role == "read" && referenceText(ref, source) == "Math" {
			mathReads++
			if len(ref.External) != 1 || !strings.Contains(ref.External[0], "#Math:") {
				t.Fatal("type/value stdlib meanings mixed", ref)
			}
		}
	}
	if mathReads != 1 {
		t.Fatal("stdlib value read missing", mathReads)
	}
}
