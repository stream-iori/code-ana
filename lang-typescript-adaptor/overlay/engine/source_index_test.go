package engine

import (
	"context"
	"testing"

	w "github.com/microsoft/typescript-go/codeana/workerpb"
	"google.golang.org/protobuf/proto"
)

func indexMeter(t *testing.T) *Meter {
	t.Helper()
	meter, err := NewMeter(context.Background(), &w.AnalysisBudgetGrant{Id: "coordinates", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 100000, MaxNodes: 10000, MaxPayloadBytes: 1 << 20, MaxDepth: 64}}, &w.AnalysisBudgetUsage{})
	if err != nil {
		t.Fatal(err)
	}
	return meter
}
func TestSourceCoordinatesUseRawUTF8AndUTF16Columns(t *testing.T) {
	index, err := newSourceIndex("\ufeff中😀x\r\nβ\ry\nz\u2028q\u2029", indexMeter(t))
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		offset       int
		line, column uint64
	}{{0, 1, 1}, {3, 1, 2}, {6, 1, 3}, {10, 1, 5}, {13, 2, 1}, {15, 2, 2}, {16, 3, 1}, {18, 4, 1}, {22, 5, 1}, {26, 6, 1}} {
		point, err := index.point(test.offset)
		if err != nil || point.Line != test.line || point.Column != test.column {
			t.Fatal(test, point, err)
		}
	}
	for _, offset := range []int{-1, 1, 2, 4, 7, 8, 9, 27} {
		if _, err := index.point(offset); err == nil {
			t.Fatal("invalid byte boundary accepted", offset)
		}
	}
	if _, err := index.span(10, 3); err == nil {
		t.Fatal("reversed range accepted")
	}
}
func TestSourceIndexFundsAllocationAndLookupBeforeWork(t *testing.T) {
	meter, err := NewMeter(context.Background(), &w.AnalysisBudgetGrant{Id: "coordinates", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 5, MaxNodes: 100, MaxPayloadBytes: 1 << 20, MaxDepth: 64}}, &w.AnalysisBudgetUsage{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := newSourceIndex("longer than budget", meter); err == nil {
		t.Fatal("unfunded index accepted")
	}
	first := meter.Check()
	if first == nil || meter.Charge(1, 0, 0) != first {
		t.Fatal("first limit failure changed")
	}
}

func TestCoordinateLookupCannotRunAfterItsWorkAllowanceIsConsumed(t *testing.T) {
	meter := indexMeter(t)
	index, err := newSourceIndex("中😀\r\nx", meter)
	if err != nil {
		t.Fatal(err)
	}
	seed := meter.Receipt("extract", true).Usage
	grant := proto.Clone(meter.grant).(*w.AnalysisBudgetGrant)
	grant.Limits.MaxWork = seed.Work
	tight, err := NewMeter(context.Background(), grant, seed)
	if err != nil {
		t.Fatal(err)
	}
	index.meter = tight
	if _, err := index.point(0); err == nil || err != tight.Check() {
		t.Fatal("unfunded coordinate lookup accepted", err)
	}
}
