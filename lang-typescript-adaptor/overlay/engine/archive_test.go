package engine

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"

	g "github.com/microsoft/typescript-go/codeana/graphpb"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
	"google.golang.org/protobuf/proto"
)

func archiveFixture(t *testing.T, rows uint64) (*ArchiveWriter, string) {
	t.Helper()
	meter, err := NewMeter(context.Background(), &w.AnalysisBudgetGrant{Id: "graph", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 1000, MaxNodes: 1000, MaxPayloadBytes: 1 << 20, MaxDepth: 64}}, &w.AnalysisBudgetUsage{})
	if err != nil {
		t.Fatal(err)
	}
	root := t.TempDir()
	writer, err := NewArchive(root, &pb.OutputLimits{Rows: rows, ArchiveBytes: 1 << 20, ScratchBytes: 1 << 20}, meter)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := writer.Close(); err != nil {
			t.Error(err)
		}
	})
	return writer, root
}

func TestSealedArchiveMatchesOriginalFramedProtobufBytes(t *testing.T) {
	writer, root := archiveFixture(t, 10)
	row := &g.Row{Table: g.Table_PROVIDER, World: "main", Cells: []*g.Cell{{Value: &g.Cell_Text{Text: "typescript"}}, {}}}
	if err := writer.Write(row); err != nil {
		t.Fatal(err)
	}
	descriptor, err := writer.Seal()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(root, descriptor.Path))
	if err != nil {
		t.Fatal(err)
	}
	digest := sha256.Sum256(data)
	if string(data[:4]) != "GRA1" || uint64(len(data)) != descriptor.Bytes || descriptor.Count != 1 || hex.EncodeToString(digest[:]) != descriptor.Sha256 {
		t.Fatal(descriptor)
	}
	size := int(binary.BigEndian.Uint32(data[4:8]))
	decoded := &g.Row{}
	if size != len(data)-8 || proto.Unmarshal(data[8:], decoded) != nil || !proto.Equal(row, decoded) {
		t.Fatal("original framing or protobuf columns changed")
	}
	if _, err := writer.Seal(); err == nil {
		t.Fatal("duplicate seal accepted")
	}
}

func TestOutputRefusalCannotBeTurnedIntoASealedPartialGraph(t *testing.T) {
	writer, _ := archiveFixture(t, 1)
	row := &g.Row{Table: g.Table_PROVIDER}
	if err := writer.Write(row); err != nil {
		t.Fatal(err)
	}
	first := writer.Write(row)
	if first == nil {
		t.Fatal("row output limit ignored")
	}
	if writer.Write(row) != first {
		t.Fatal("first output error replaced")
	}
	if descriptor, err := writer.Seal(); descriptor != nil || err != first {
		t.Fatal("partial graph acquired a descriptor", descriptor, err)
	}
}
