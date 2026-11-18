package engine

import (
	"context"
	g "github.com/microsoft/typescript-go/codeana/graphpb"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"testing"
)

func TestImplicitConstructorsKeepDeclarationEvidenceWithoutInventingBodies(t *testing.T) {
	source := `export class Empty {}
export class Explicit { constructor(value: number) {} }
new Empty(); new Explicit(1);
`
	program, meter := semanticFixture(t, map[string]string{"entry.ts": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(facts.Calls) != 2 {
		t.Fatal(facts.Calls)
	}
	implicit := facts.Calls[0]
	explicit := facts.Calls[1]
	empty := declarationsNamed(facts, "Empty")
	if len(empty) != 1 {
		t.Fatal(empty)
	}
	if implicit.Target != empty[0].ID || implicit.TargetKind != "declaration" || implicit.Reason != "implicit_constructor_declaration" || empty[0].Implementation {
		t.Fatal(implicit, empty)
	}
	if explicit.TargetKind != "implementation" || explicit.Reason != "selected_constructor_body" {
		t.Fatal(explicit)
	}
	for _, d := range facts.Declarations {
		if d.Kind == "constructor" && (d.Synthetic || source[d.NameSpan.Start.Byte:d.NameSpan.End.Byte] != "constructor") {
			t.Fatal("constructor token location", d)
		}
	}
	root := t.TempDir()
	writer, err := NewArchive(root, &pb.OutputLimits{ArchiveBytes: 1 << 20, Rows: 1000, ScratchBytes: 1 << 20}, meter)
	if err != nil {
		t.Fatal(err)
	}
	if err := (&projection{writer: writer, meter: meter}).facts(facts); err != nil {
		t.Fatal(err)
	}
	archive, err := writer.Seal()
	if err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, row := range readGraphRows(t, root, archive) {
		if row.Table == g.Table_EDGE && row.Cells[6].GetText() == "CALLS" && row.Cells[1].GetText() == empty[0].ID {
			if row.Cells[7].GetText() != "declaration" {
				t.Fatal(row)
			}
			found = true
		}
	}
	if !found {
		t.Fatal("implicit constructor declaration target lost in projection")
	}
}
