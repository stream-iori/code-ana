package engine

import (
	"context"
	"testing"
)

func TestJSDocTypedefAndParameterTypeReferencesKeepOriginalJavaScriptSpans(t *testing.T) {
	source := `/** @typedef {{ size: number }} Shape */
/** @param {Shape} value */
export function read(value) { return value.size; }
`
	program, _ := semanticFixture(t, map[string]string{"entry.js": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	shapes := declarationsNamed(facts, "Shape")
	if len(shapes) != 1 || shapes[0].Kind != "type_alias" || shapes[0].Language != "javascript" || !shapes[0].TypeSpace || shapes[0].ValueSpace {
		t.Fatal("JSDoc typedef lost", shapes, facts.Declarations)
	}
	shape := shapes[0]
	if source[shape.NameSpan.Start.Byte:shape.NameSpan.End.Byte] != "Shape" {
		t.Fatal("JSDoc name not mapped to source", shape)
	}
	count := 0
	for _, ref := range facts.References {
		if ref.Role == "type" && len(ref.Targets) == 1 && ref.Targets[0] == shape.ID {
			if source[ref.Span.Start.Byte:ref.Span.End.Byte] != "Shape" {
				t.Fatal("JSDoc type reference source", ref)
			}
			count++
		}
	}
	if count != 1 {
		t.Fatal("JSDoc parameter type reference lost", facts.References)
	}
	documented := false
	for _, comment := range facts.Comments {
		for _, id := range comment.Documents {
			if id == shape.ID {
				documented = true
			}
		}
	}
	if !documented {
		t.Fatal("typedef comment ownership lost", facts.Comments)
	}
}

func TestJSDocImportKeepsTypeOnlyModuleAliasAndReferenceEvidence(t *testing.T) {
	source := `/** @import { Shape as ImportedShape } from './types' */
/** @param {ImportedShape} value */
export function read(value) { return value.size; }
`
	program, _ := semanticFixture(t, map[string]string{"types.ts": "export interface Shape { size: number; }", "entry.js": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	shape := declarationsNamed(facts, "Shape")
	if len(shape) != 1 {
		t.Fatal(shape)
	}
	counts := map[string]int{}
	for _, ref := range facts.References {
		if ref.File != "entry.js" {
			continue
		}
		if ref.Role == "imports_module_type" {
			counts[ref.Role]++
		}
		if ref.Role == "imports_type" || ref.Role == "type" {
			if len(ref.Targets) != 1 || ref.Targets[0] != shape[0].ID {
				t.Fatal(ref)
			}
			counts[ref.Role]++
		}
	}
	if counts["imports_module_type"] != 1 || counts["imports_type"] != 1 || counts["type"] != 1 {
		t.Fatal(counts, facts.References)
	}
}

func TestJSDocCallbackRemainsADeclarationTargetWithOriginalParameterName(t *testing.T) {
	source := `/** @callback Mapper
 * @param {number} value
 * @returns {number}
 */
/** @param {Mapper} fn */
export function use(fn) { return fn(1); }
`
	program, _ := semanticFixture(t, map[string]string{"entry.js": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	mapper := declarationsNamed(facts, "Mapper")
	if len(mapper) != 1 || mapper[0].Kind != "type_alias" || !mapper[0].TypeSpace {
		t.Fatal(mapper)
	}
	if len(facts.Calls) != 1 || facts.Calls[0].TargetKind != "declaration" || facts.Calls[0].Target == "" {
		t.Fatal(facts.Calls)
	}
	found := false
	for _, d := range facts.Declarations {
		if d.ID == facts.Calls[0].Target {
			if d.Kind != "call_signature" || d.Implementation {
				t.Fatal(d)
			}
			found = true
		}
		if d.Kind == "parameter" && d.Name == "value" && source[d.NameSpan.Start.Byte:d.NameSpan.End.Byte] != "value" {
			t.Fatal("callback parameter original span", d)
		}
	}
	if !found {
		t.Fatal("callback selected signature not in declarations", facts.Declarations)
	}
}
