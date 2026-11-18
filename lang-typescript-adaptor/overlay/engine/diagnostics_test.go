package engine

import (
	"context"
	"strings"
	"testing"
)

func TestCompilerDiagnosticsKeepResolutionAndTypeErrorsWithoutPoisoningValidCalls(t *testing.T) {
	source := "import {valid} from './a'; import {missing} from './absent'; const wrong: number = 'bad'; valid(1); missing();"
	program, _ := semanticFixture(t, map[string]string{"a.ts": "export function valid(value: number) { return value; }", "entry.ts": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, d := range facts.Diagnostics {
		if d.Phase == "context" {
			continue
		}
		if d.File != "entry.ts" || d.Message == "" || strings.Contains(d.Message, program.context.Root) {
			t.Fatal(d)
		}
		seen[d.Code] = true
		if d.Code == "TS2307" && (d.Reason != "TYPESCRIPT_RESOLUTION_GAP" || source[d.Span.Start.Byte:d.Span.End.Byte] != "'./absent'") {
			t.Fatal(d)
		}
		if d.Code == "TS2322" && d.Reason != "TYPESCRIPT_TYPE_ERROR" {
			t.Fatal(d)
		}
	}
	if !seen["TS2307"] || !seen["TS2322"] {
		t.Fatal(facts.Diagnostics)
	}
	calls := callsIn(facts, "entry.ts")
	if len(calls) != 2 || calls[0].TargetKind != "implementation" || calls[1].TargetKind != "unknown" {
		t.Fatal(calls)
	}
}

func TestSyntaxRecoveryAtCallEndNeverBecomesAnExactImplementation(t *testing.T) {
	source := "function valid(value: number) { return value; }\nvalid(1"
	program, _ := semanticFixture(t, map[string]string{"entry.ts": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	syntax := false
	for _, d := range facts.Diagnostics {
		if d.Phase == "syntax" && d.Reason == "TYPESCRIPT_SYNTAX_ERROR" {
			syntax = true
		}
	}
	if !syntax || len(facts.Calls) != 1 || facts.Calls[0].TargetKind != "unknown" || facts.Calls[0].Target != "" {
		t.Fatal(facts.Diagnostics, facts.Calls)
	}
}
