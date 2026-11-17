package engine

import (
	"context"
	"errors"
	"fmt"
	"math"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	w "github.com/microsoft/typescript-go/codeana/workerpb"
	"google.golang.org/protobuf/proto"
)

type Fault struct {
	Code    pb.FailureCode
	Message string
}

func (f *Fault) Error() string { return f.Message }

func invalid(message string) error {
	return &Fault{Code: pb.FailureCode_INVALID_REQUEST, Message: message}
}

// Meter belongs to the computation goroutine. Cancellation changes only its
// context; neither ABI polling nor close mutates this cumulative account.
type Meter struct {
	ctx     context.Context
	grant   *w.AnalysisBudgetGrant
	usage   *w.AnalysisBudgetUsage
	failure error
}

func NewMeter(ctx context.Context, grant *w.AnalysisBudgetGrant, seed *w.AnalysisBudgetUsage) (*Meter, error) {
	if grant == nil || grant.Id == "" || grant.Language != "typescript" || grant.Limits == nil || seed == nil || seed.Failure != nil {
		return nil, invalid("explicit TypeScript grant and healthy cumulative seed required")
	}
	limits := grant.Limits
	if limits.MaxWork == 0 || limits.MaxNodes == 0 || limits.MaxPayloadBytes == 0 || limits.MaxDepth == 0 ||
		limits.MaxWork > math.MaxInt64 || limits.MaxNodes > math.MaxInt64 || limits.MaxPayloadBytes > math.MaxInt64 || limits.MaxDepth > 4096 ||
		seed.Work > limits.MaxWork || seed.Nodes > limits.MaxNodes || seed.PayloadBytes > limits.MaxPayloadBytes || seed.MaxDepth > limits.MaxDepth {
		return nil, invalid("invalid grant limits or cumulative seed")
	}
	return &Meter{ctx: ctx, grant: grant, usage: proto.Clone(seed).(*w.AnalysisBudgetUsage)}, nil
}

func (m *Meter) Check() error {
	if m.failure != nil {
		return m.failure
	}
	if err := m.ctx.Err(); err != nil {
		code, wire := pb.FailureCode_CANCELLED, "CANCELLED"
		if errors.Is(err, context.DeadlineExceeded) {
			code, wire = pb.FailureCode_TIMEOUT, "DEADLINE_EXCEEDED"
		}
		m.usage.Failure = &w.AnalysisBudgetFailure{Code: wire}
		m.failure = &Fault{Code: code, Message: err.Error()}
		return m.failure
	}
	return nil
}

func (m *Meter) refuse(resource string, limit, used, requested uint64) error {
	if m.failure == nil {
		m.usage.Failure = &w.AnalysisBudgetFailure{Code: "ANALYSIS_RESOURCE_LIMIT", Resource: resource, Limit: limit, Used: used, Requested: requested}
		m.failure = &Fault{Code: pb.FailureCode_RESOURCE_LIMIT, Message: fmt.Sprintf("%s budget exhausted", resource)}
	}
	return m.failure
}

func (m *Meter) Charge(work, nodes, payload uint64) error {
	if err := m.Check(); err != nil {
		return err
	}
	limits := m.grant.Limits
	for _, value := range []struct {
		name                   string
		limit, used, requested uint64
	}{
		{"work", limits.MaxWork, m.usage.Work, work},
		{"nodes", limits.MaxNodes, m.usage.Nodes, nodes},
		{"payload_bytes", limits.MaxPayloadBytes, m.usage.PayloadBytes, payload},
	} {
		if value.requested > value.limit-value.used {
			return m.refuse(value.name, value.limit, value.used, value.requested)
		}
	}
	m.usage.Work += work
	m.usage.Nodes += nodes
	m.usage.PayloadBytes += payload
	return nil
}

func (m *Meter) Depth(depth uint32) error {
	if err := m.Check(); err != nil {
		return err
	}
	if depth > m.grant.Limits.MaxDepth {
		return m.refuse("depth", uint64(m.grant.Limits.MaxDepth), uint64(m.usage.MaxDepth), uint64(depth-m.usage.MaxDepth))
	}
	if depth > m.usage.MaxDepth {
		m.usage.MaxDepth = depth
	}
	return nil
}

func (m *Meter) Receipt(phase string, known bool) *w.AnalysisBudgetReceipt {
	return &w.AnalysisBudgetReceipt{GrantId: m.grant.Id, Usage: proto.Clone(m.usage).(*w.AnalysisBudgetUsage), Phase: phase, UsageKnown: &known}
}
