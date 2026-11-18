package engine

import (
	"context"
	"testing"
)

func TestParameterPropertiesSeparateLexicalParameterAndInstanceMemberSymbols(t *testing.T) {
	source := `export class Box {
 constructor(public readonly value: number, private hidden: number, readonly label: string) {
  value; this.value; hidden; this.hidden; label; this.label;
 }
 read() { return this.value; }
}

`
	program, _ := semanticFixture(t, map[string]string{"entry.ts": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]SemanticDeclaration{}
	for _, d := range facts.Declarations {
		ids[d.ID] = d
	}
	for _, name := range []string{"value", "hidden", "label"} {
		decls := declarationsNamed(facts, name)
		if len(decls) != 2 {
			t.Fatal(name, decls)
		}
		var parameter, property SemanticDeclaration
		for _, d := range decls {
			if d.Kind == "parameter" {
				parameter = d
			} else if d.Kind == "property" {
				property = d
			}
		}
		if parameter.ID == "" || property.ID == "" || parameter.ID == property.ID || parameter.Visibility != "local" || !property.ParameterProperty || !property.Resolved || !property.ValueSpace || ids[property.Parent].Kind != "class" || parameter.SymbolGroup == property.SymbolGroup || property.SymbolGroup == "" {
			t.Fatal(name, parameter, property)
		}
		visibility := "public"
		if name == "hidden" {
			visibility = "private"
		}
		if property.Visibility != visibility {
			t.Fatal(property)
		}
		if source[property.NameSpan.Start.Byte:property.NameSpan.End.Byte] != name {
			t.Fatal(property)
		}
	}
	parameters, properties := 0, 0
	for _, ref := range facts.References {
		if ref.Role != "read" || len(ref.Targets) != 1 {
			continue
		}
		d := ids[ref.Targets[0]]
		if d.Name != "value" && d.Name != "hidden" && d.Name != "label" {
			continue
		}
		start := ref.Span.Start.Byte
		member := start >= 5 && source[start-5:start] == "this."
		if member {
			if d.Kind != "property" {
				t.Fatal("member became parameter", ref, d)
			}
			properties++
		} else {
			if d.Kind != "parameter" {
				t.Fatal("parameter became member", ref, d)
			}
			parameters++
		}
	}
	if parameters != 3 || properties != 4 {
		t.Fatal(parameters, properties, facts.References)
	}
	for _, d := range facts.Diagnostics {
		if d.Code == "TYPESCRIPT_PARAMETER_PROPERTY_UNSUPPORTED" {
			t.Fatal(d)
		}
	}
}

func TestGenericParameterPropertyReferencesSurviveCheckerBoundariesAndBadDuplicates(t *testing.T) {
	program, _ := semanticFixture(t, map[string]string{"a.ts": "export class Box<T> { constructor(public value: T) {} }", "b.ts": "import {Box} from './a'; const box = new Box(1); box.value;"}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	property := ""
	for _, d := range facts.Declarations {
		if d.Name == "value" && d.ParameterProperty {
			property = d.ID
		}
	}
	found := false
	for _, ref := range facts.References {
		if ref.File == "b.ts" && len(ref.Targets) == 1 && ref.Targets[0] == property {
			found = true
		}
	}
	if property == "" || !found {
		t.Fatal(property, facts.References)
	}
	program, _ = semanticFixture(t, map[string]string{"entry.ts": "export class Bad { constructor(public value: number, public value: number) {} }"}, nil)
	facts, err = program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]bool{}
	for _, d := range facts.Declarations {
		if ids[d.ID] {
			t.Fatal("duplicate public declaration ID", d)
		}
		ids[d.ID] = true
	}
}
