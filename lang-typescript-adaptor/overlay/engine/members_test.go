package engine

import (
	"context"
	"testing"
)

func TestSemanticMembersConstructorsAndImmutableCallables(t *testing.T) {
	source := `export class Box {
 constructor(public value: number) {}
 run(value: number): number;
 run(value: string): string;
 run(value: number | string) { return value; }
}
const box = new Box(1);
box.run(1); box.run("ok");
const arrow = (value: number) => value;
arrow(1); arrow(2);
let mutable = (value: number) => value;
mutable(3);
(function(value: number) { return value; })(4);
interface Runner { run(value: number): number; }
function invoke(callback: (value: number) => number, runner: Runner) {
 callback(5); runner.run(6);
}
const object = { first(value: number) { return value; } };
object.first(7); object["first"](8);
declare const key: string;
object[key](9);
`
	program, _ := semanticFixture(t, map[string]string{"entry.ts": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	byID := map[string]SemanticDeclaration{}
	for _, d := range facts.Declarations {
		byID[d.ID] = d
	}
	want := map[string]string{
		"new Box(1)":    "selected_constructor_body",
		"box.run(1)":    "method_symbol_unique_body",
		`box.run("ok")`: "method_symbol_unique_body",
		"arrow(1)":      "immutable_callable_initializer",
		"arrow(2)":      "immutable_callable_initializer",
		"(function(value: number) { return value; })(4)": "inline_callable_body",
		"object.first(7)":    "method_symbol_unique_body",
		`object["first"](8)`: "method_symbol_unique_body",
	}
	seen := map[string]bool{}
	for _, call := range facts.Calls {
		text := source[call.Span.Start.Byte:call.Span.End.Byte]
		seen[text] = true
		if reason, ok := want[text]; ok {
			if call.TargetKind != "implementation" || call.Reason != reason || !byID[call.Target].Implementation {
				t.Fatal(text, call)
			}
			if text == "box.run(1)" && call.SelectedDeclaration == call.Target {
				t.Fatal("overload signature lost", call)
			}
		} else if text == "object[key](9)" {
			if call.TargetKind != "unknown" || call.Target != "" {
				t.Fatal("dynamic key became exact", call)
			}
		} else if text == "mutable(3)" || text == "callback(5)" || text == "runner.run(6)" {
			if call.TargetKind != "declaration" || call.Target == "" {
				t.Fatal("mutable/structural binding promoted", text, call)
			}
		} else {
			t.Fatal("unexpected call", text, call)
		}
	}
	if len(seen) != len(want)+4 {
		t.Fatal("missing call sites", seen)
	}
}

func TestSemanticHeritageAndJSXKeepCompilerEndpointsWithoutRuntimeCalls(t *testing.T) {
	source := `export interface Contract { run(value: number): number; }
export interface ChildContract extends Contract {}
export class Base { run(value: number) { return value; } }
export class Child extends Base implements Contract {}
namespace UI { export class Component {} }
function Local() { return null; }
const view = <UI.Component><Local /><div /><span>text</span></UI.Component>;
`
	program, _ := semanticFixture(t, map[string]string{"entry.tsx": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]SemanticDeclaration{}
	for _, d := range facts.Declarations {
		ids[d.ID] = d
	}
	want := map[string]string{"Child:extends": "Base", "Child:implements": "Contract", "ChildContract:extends": "Contract"}
	components := map[string]int{}
	for _, ref := range facts.References {
		if ref.Role == "jsx_component" {
			if len(ref.Targets) != 1 {
				t.Fatal("component target missing", ref)
			}
			components[ids[ref.Targets[0]].Name]++
		}
		if ref.Role == "extends" || ref.Role == "implements" {
			key := ids[ref.Source].Name + ":" + ref.Role
			if len(ref.Targets) != 1 || ids[ref.Targets[0]].Name != want[key] {
				t.Fatal("heritage endpoint", key, ref)
			}
			delete(want, key)
		}
	}
	if len(want) != 0 || len(components) != 2 || components["Component"] != 1 || components["Local"] != 1 || len(facts.Calls) != 0 {
		t.Fatal("missing/duplicate heritage or JSX became runtime call", want, components, facts.Calls)
	}
}
