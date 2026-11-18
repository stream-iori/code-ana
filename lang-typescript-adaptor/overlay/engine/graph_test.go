package engine

import (
	"context"
	"encoding/binary"
	"os"
	"path/filepath"
	"testing"

	g "github.com/microsoft/typescript-go/codeana/graphpb"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"google.golang.org/protobuf/proto"
)

func readGraphRows(t *testing.T, root string, archive *pb.Archive) []*g.Row {
	t.Helper()
	data, err := os.ReadFile(filepath.Join(root, archive.Path))
	if err != nil {
		t.Fatal(err)
	}
	if len(data) < 4 || string(data[:4]) != "GRA1" {
		t.Fatal("graph magic")
	}
	data = data[4:]
	var rows []*g.Row
	for len(data) > 0 {
		if len(data) < 4 {
			t.Fatal("short frame")
		}
		n := int(binary.BigEndian.Uint32(data))
		data = data[4:]
		if n > len(data) {
			t.Fatal("truncated row")
		}
		row := &g.Row{}
		if err := proto.Unmarshal(data[:n], row); err != nil {
			t.Fatal(err)
		}
		data = data[n:]
		rows = append(rows, row)
	}
	if uint64(len(rows)) != archive.Count {
		t.Fatal("row count", archive.Count, len(rows))
	}
	return rows
}
func TestPublicProjectionContainsDeclarationsAndExactCrossTagCallEndpoints(t *testing.T) {
	program, meter := semanticFixture(t, map[string]string{"a.ts": "export function greet(value: number) { return value + 1; }", "b.js": "import {greet} from './a'; greet(1); greet(2);"}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
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
	rows := readGraphRows(t, root, archive)
	nodes := map[string]*g.Row{}
	declarations := 0
	calls := 0
	greet := ""
	for _, row := range rows {
		switch row.Table {
		case g.Table_NODE:
			if len(row.Cells) != 24 {
				t.Fatal("node columns", len(row.Cells))
			}
			nodes[row.Cells[0].GetText()] = row
			if row.Cells[7].GetText() == "greet" && row.Cells[8].GetText() == "function" {
				greet = row.Cells[0].GetText()
			}
		case g.Table_DECLARATION:
			if len(row.Cells) != 30 {
				t.Fatal("declaration columns", len(row.Cells))
			}
			declarations++
		}
	}
	for _, row := range rows {
		if row.Table != g.Table_EDGE {
			continue
		}
		if len(row.Cells) != 34 {
			t.Fatal("edge columns", len(row.Cells))
		}
		if row.Cells[6].GetText() == "CALLS" {
			calls++
			if row.Cells[1].GetText() != greet || row.Cells[9].GetText() != "javascript" || row.Cells[13].GetText() != "exact" || nodes[row.Cells[0].GetText()] == nil {
				t.Fatal("call binding projection", row)
			}
		}
	}
	if greet == "" || calls != 2 || declarations != len(nodes) || len(nodes) < 4 {
		t.Fatal(greet, calls, declarations, len(nodes))
	}
}
