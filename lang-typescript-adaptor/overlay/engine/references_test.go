package engine

import (
	"context"
	"strings"
	"testing"
)

func referenceText(reference SemanticReference, text string) string {
	return text[reference.Span.Start.Byte:reference.Span.End.Byte]
}
func TestSemanticReferenceRolesUseSyntaxAndCompilerSymbols(t *testing.T) {
	sources := map[string]string{
		"lib.ts": "export interface Shape { value: number; } export function greet() { return 1; }\n",
		"entry.ts": `import {greet as hello} from './lib';
import type {Shape} from './lib';
export {greet as renamed} from './lib';
let local: Shape;
let count = 1;
count = 2; count += 3; count++;
const object = {count};
object.count = count;
const shown = {unboundKey: count};
type GreetType = typeof hello;
hello();
missing;
`,
	}
	program, _ := semanticFixture(t, sources, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]SemanticDeclaration{}
	for _, d := range facts.Declarations {
		ids[d.ID] = d
	}
	counts := map[string]int{}
	for _, ref := range facts.References {
		if ref.File != "entry.ts" {
			continue
		}
		text := referenceText(ref, sources[ref.File])
		counts[ref.Role+":"+text]++
		if ref.Source == "" || ref.Caller == "" {
			t.Fatal("reference owner missing", ref)
		}
		if text == "unboundKey" || text == "renamed" && ref.Role == "read" {
			t.Fatal("syntactic key or export name became a read", ref)
		}
		if ref.Role == "type" && text == "Shape" {
			if len(ref.Targets) != 1 || ids[ref.Targets[0]].Kind != "interface" || ids[ref.Targets[0]].File != "lib.ts" {
				t.Fatal("type-space endpoint", ref)
			}
		}
		if ref.Role == "write" && text == "count" {
			if len(ref.Targets) != 1 || ids[ref.Targets[0]].Name != "count" {
				t.Fatal("write binding", ref)
			}
		}
		if text == "missing" && (ref.Resolution != "unknown" || len(ref.Targets) != 0 || len(ref.External) != 0) {
			t.Fatal("missing name manufactured a target", ref)
		}
	}
	for key, want := range map[string]int{"imports:hello": 1, "imports_type:Shape": 1, "exports:renamed": 1, "imports_module:'./lib'": 1, "imports_module_type:'./lib'": 1, "exports_module:'./lib'": 1, "type:Shape": 1, "value_type_query:hello": 1, "write:count": 2, "read_write:count": 2, "read:count": 3, "read:object": 1, "read:missing": 1} {
		if counts[key] != want {
			t.Fatal(key, want, counts)
		}
	}
	if counts["read:hello"] != 0 || len(facts.Calls) != 1 || facts.Calls[0].TargetKind != "implementation" {
		t.Fatal("callee/declaration roles duplicated", counts, facts.Calls)
	}
}
func TestSemanticSideEffectImportsAndReexportsKeepModuleEvidence(t *testing.T) {
	program, _ := semanticFixture(t, map[string]string{"a.ts": "export const value = 1;", "entry.ts": "import './a'; export * from './a';"}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, ref := range facts.References {
		if ref.File != "entry.ts" {
			continue
		}
		if ref.Role != "imports_module" && ref.Role != "exports_module" {
			t.Fatal(ref)
		}
		if ref.Resolution != "symbol" || len(ref.Targets) != 1 || len(ref.External) != 0 {
			t.Fatal("module evidence missing", ref)
		}
		for _, decl := range facts.Declarations {
			if decl.ID == ref.Targets[0] && (decl.Kind != "module" || decl.File != "a.ts") {
				t.Fatal(decl)
			}
		}
		count++
	}
	if count != 2 || len(facts.Calls) != 0 {
		t.Fatal(count, facts.Calls)
	}
	for _, decl := range facts.Declarations {
		if strings.Contains(decl.ID, "/") {
			t.Fatal("path in opaque identity", decl)
		}
	}
}

func TestTypeAndNamespaceMeaningsDoNotBecomeValueReads(t *testing.T) {
	sources := map[string]string{
		"lib.ts":   "export class Box {} export namespace Box { export type Nested = number; }",
		"entry.ts": "import {Box} from './lib'; type Plain = Box; type Qualified = Box.Nested;",
	}
	program, _ := semanticFixture(t, sources, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]SemanticDeclaration{}
	for _, d := range facts.Declarations {
		ids[d.ID] = d
	}
	plain, qualifier, nested := 0, 0, 0
	for _, ref := range facts.References {
		if ref.File != "entry.ts" {
			continue
		}
		text := referenceText(ref, sources[ref.File])
		if text == "Box" && ref.Role == "read" {
			t.Fatal("namespace/type use became a value read", ref)
		}
		if text == "Box" && ref.Role == "type" {
			if len(ref.Targets) != 1 || ids[ref.Targets[0]].Kind != "class" {
				t.Fatal("merged namespace used as a type", ref)
			}
			plain++
		}
		if text == "Box" && ref.Role == "namespace_qualifier" {
			if len(ref.Targets) == 0 {
				t.Fatal(ref)
			}
			qualifier++
		}
		if text == "Nested" && ref.Role == "type" {
			if len(ref.Targets) != 1 || ids[ref.Targets[0]].Kind != "type_alias" {
				t.Fatal(ref)
			}
			nested++
		}
	}
	if plain != 1 || qualifier != 1 || nested != 1 {
		t.Fatal(plain, qualifier, nested, facts.References)
	}
}

func TestAccessorReferencesAndJSXNamesKeepTheirOwnRoles(t *testing.T) {
	source := `class Box {
 private storage = 1;
 get value() { return this.storage; }
 set value(next: number) { this.storage = next; }
}
const box = new Box();
const before = box.value; box.value = 2; box.value++;
namespace UI { export class Component {} }
const view = <UI.Component />;
`
	program, _ := semanticFixture(t, map[string]string{"entry.tsx": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]SemanticDeclaration{}
	for _, decl := range facts.Declarations {
		ids[decl.ID] = decl
	}
	counts := map[string]int{}
	for _, ref := range facts.References {
		text := referenceText(ref, source)
		if text == "UI" || text == "Component" {
			t.Fatal("JSX tag emitted a fabricated identifier read", ref)
		}
		if text != "value" {
			continue
		}
		counts[ref.Role]++
		want := map[string]bool{}
		switch ref.Role {
		case "read":
			want["get_accessor"] = true
		case "write":
			want["set_accessor"] = true
		case "read_write":
			want["get_accessor"] = true
			want["set_accessor"] = true
		default:
			t.Fatal(ref)
		}
		if len(ref.Targets) != len(want) {
			t.Fatal(ref)
		}
		for _, target := range ref.Targets {
			if !want[ids[target].Kind] {
				t.Fatal("read/write accessor meaning mixed", ref, ids[target])
			}
		}
	}
	if counts["read"] != 1 || counts["write"] != 1 || counts["read_write"] != 1 {
		t.Fatal(counts)
	}
	if len(facts.Calls) != 1 || facts.Calls[0].SyntaxKind != "new" {
		t.Fatal("JSX emitted a runtime call", facts.Calls)
	}
}
