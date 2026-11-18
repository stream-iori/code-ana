package engine

import (
	"context"
	"testing"
)

func TestCompilerModuleExportsKeepDefaultReexportAndCommonJSProvenance(t *testing.T) {
	sources := map[string]string{
		"a.ts":      "export default function(value: number) { return value; } export interface Shape { size: number; }",
		"barrel.ts": "export {default as renamed} from './a'; export type {Shape} from './a';",
		"common.js": "function greet(value) { return value; } exports.greet = greet;",
		"equals.js": "module.exports = function(value) { return value; };",
		"entry.js":  "const imported = require('./common'); imported.greet(1); function local(require) { return require('./a'); }",
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
	seen := map[string]bool{}
	imports := 0
	for _, ref := range facts.References {
		if ref.Role == "imports_module_commonjs" {
			imports++
			if ref.File != "entry.js" || len(ref.Targets) != 1 || ids[ref.Targets[0]].File != "common.js" {
				t.Fatal("require module evidence", ref)
			}
		}
		if ref.Role != "exports_symbol" {
			continue
		}
		key := ref.File + ":" + ref.Name
		if seen[key] {
			t.Fatal("duplicate export", key)
		}
		seen[key] = true
		if len(ref.Targets) != 1 || len(ref.External) != 0 {
			t.Fatal("export target provenance", key, ref)
		}
		target := ids[ref.Targets[0]]
		switch key {
		case "a.ts:default", "barrel.ts:renamed":
			if target.File != "a.ts" || target.Kind != "function" || !target.Implementation {
				t.Fatal(key, target)
			}
		case "a.ts:Shape", "barrel.ts:Shape":
			if target.Kind != "interface" || target.File != "a.ts" {
				t.Fatal(key, target)
			}
		case "common.js:greet":
			if target.Kind != "function" || target.Name != "greet" {
				t.Fatal(key, target)
			}
		case "equals.js:export=":
			if target.Kind != "function" || !target.Implementation {
				t.Fatal(key, target)
			}
		default:
			t.Fatal("unexpected export", key, ref)
		}
	}
	if len(seen) != 6 || imports != 1 {
		t.Fatal(seen, imports)
	}
}
