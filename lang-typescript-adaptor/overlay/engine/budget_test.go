package engine

import (
	"context"
	"errors"
	"math"
	"testing"

	w "github.com/microsoft/typescript-go/codeana/workerpb"
)

func fixtureMeter(ctx context.Context, seed *w.AnalysisBudgetUsage) (*Meter, error) {
	return NewMeter(ctx, &w.AnalysisBudgetGrant{Id: "task/typescript/unit-a", Language: "typescript",
		Limits: &w.AnalysisBudgetLimits{MaxWork: 8, MaxNodes: 16, MaxPayloadBytes: 128, MaxDepth: 4}}, seed)
}

func TestSeedContinuesWithoutRefillAndFirstFailureSurvivesCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	meter, err := fixtureMeter(ctx, &w.AnalysisBudgetUsage{Work: 6, Nodes: 2, PayloadBytes: 32, MaxDepth: 2})
	if err != nil {
		t.Fatal(err)
	}
	if err := meter.Charge(1, 1, 8); err != nil {
		t.Fatal(err)
	}
	first := meter.Charge(2, 0, 0)
	if first == nil {
		t.Fatal("cumulative work was refilled")
	}
	cancel()
	if meter.Check() != first || meter.Charge(0, 0, 0) != first {
		t.Fatal("cancellation replaced the first quota refusal")
	}
	receipt := meter.Receipt("backend", true)
	if receipt.Usage.Work != 7 || receipt.Usage.Nodes != 3 || receipt.Usage.PayloadBytes != 40 ||
		receipt.Usage.Failure.Resource != "work" || receipt.Usage.Failure.Used != 7 || receipt.Usage.Failure.Requested != 2 {
		t.Fatal(receipt)
	}
	receipt.Usage.Work = 0
	if meter.Receipt("backend", true).Usage.Work != 7 {
		t.Fatal("a receipt aliases the live account")
	}
}

func TestInstancesAreIndependentAndDepthUsesTheVerifiedHighWatermark(t *testing.T) {
	first, _ := fixtureMeter(context.Background(), &w.AnalysisBudgetUsage{MaxDepth: 2})
	second, _ := fixtureMeter(context.Background(), &w.AnalysisBudgetUsage{})
	if first.Depth(5) == nil {
		t.Fatal("depth admitted beyond grant")
	}
	if err := second.Charge(8, 16, 128); err != nil {
		t.Fatal(err)
	}
	raw := first.Receipt("backend", true).Usage
	if raw.MaxDepth != 2 || raw.Failure.Used != 2 || raw.Failure.Requested != 3 || raw.Failure.Limit != 4 {
		t.Fatal(raw)
	}
	if second.Receipt("archive", true).Usage.Failure != nil {
		t.Fatal("sibling failure escaped its account")
	}
}

func TestMalformedSeedAndMissingGrantCannotStartAnAccount(t *testing.T) {
	for _, seed := range []*w.AnalysisBudgetUsage{nil, {Work: 9}, {Nodes: 17}, {PayloadBytes: 129}, {MaxDepth: 5}, {Failure: &w.AnalysisBudgetFailure{Code: "CANCELLED"}}} {
		if _, err := fixtureMeter(context.Background(), seed); err == nil {
			t.Fatal("invalid cumulative seed accepted", seed)
		}
	}
	if _, err := NewMeter(context.Background(), nil, &w.AnalysisBudgetUsage{}); err == nil {
		t.Fatal("missing grant accepted")
	}
	for _, edit := range []func(*w.AnalysisBudgetGrant){
		func(g *w.AnalysisBudgetGrant) { g.Language = "rust" },
		func(g *w.AnalysisBudgetGrant) { g.Id = "" },
		func(g *w.AnalysisBudgetGrant) { g.Limits = nil },
		func(g *w.AnalysisBudgetGrant) { g.Limits.MaxWork = 0 },
		func(g *w.AnalysisBudgetGrant) { g.Limits.MaxWork = math.MaxUint64 },
		func(g *w.AnalysisBudgetGrant) { g.Limits.MaxNodes = math.MaxUint64 },
		func(g *w.AnalysisBudgetGrant) { g.Limits.MaxPayloadBytes = math.MaxUint64 },
		func(g *w.AnalysisBudgetGrant) { g.Limits.MaxDepth = 4097 },
	} {
		grant := &w.AnalysisBudgetGrant{Id: "grant", Language: "typescript", Limits: &w.AnalysisBudgetLimits{MaxWork: 1, MaxNodes: 1, MaxPayloadBytes: 1, MaxDepth: 1}}
		edit(grant)
		if _, err := NewMeter(context.Background(), grant, &w.AnalysisBudgetUsage{}); err == nil {
			t.Fatal("malformed or overflowing grant accepted", grant)
		}
	}
}

func TestCancellationAndDeadlineHaveDifferentWireFailures(t *testing.T) {
	for _, deadline := range []bool{false, true} {
		ctx, cancel := context.WithCancel(context.Background())
		var meter *Meter
		if deadline {
			expired, stop := context.WithTimeout(context.Background(), 0)
			defer stop()
			meter, _ = fixtureMeter(expired, &w.AnalysisBudgetUsage{})
		} else {
			meter, _ = fixtureMeter(ctx, &w.AnalysisBudgetUsage{})
		}
		cancel()
		var fault *Fault
		if !errors.As(meter.Check(), &fault) {
			t.Fatal("missing typed cancellation")
		}
		expected := "CANCELLED"
		if deadline {
			expected = "DEADLINE_EXCEEDED"
		}
		if actual := meter.Receipt("backend", true).Usage.Failure.Code; actual != expected {
			t.Fatal(actual, expected)
		}
	}
}
