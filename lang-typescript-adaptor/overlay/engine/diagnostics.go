package engine

import (
	"strconv"
	"strings"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/diagnostics"
	"github.com/microsoft/typescript-go/internal/locale"
)

// Compiler diagnostics remain detached source observations. Ordinary type
// errors do not revoke unrelated declaration/reference/call bindings.
type SemanticDiagnostic struct {
	File, Module, Scope, World, Language, Severity, Code, Phase, Reason, Message string
	Span                                                                         SourceSpan
}

func (b *semanticBuilder) diagnostics(file *semanticFile, values []*ast.Diagnostic, phase string) error {
	if err := b.program.meter.Charge(uint64(len(values))+1, uint64(len(values))+1, uint64(len(values))*512+512); err != nil {
		return err
	}
	for _, value := range values {
		if file != nil && value.File() != nil && value.File() != file.source {
			continue
		}
		bytes := len(value.MessageText())
		for _, arg := range value.MessageArgs() {
			bytes += len(arg)
		}
		if err := b.program.meter.Charge(uint64(bytes)+1, 1, uint64(bytes)*8+16384); err != nil {
			return err
		}
		span := SourceSpan{}
		if file != nil {
			start, end := value.Pos(), value.End()
			if start < 0 || end < start || end > len(file.source.Text()) {
				start, end = 0, 0
			}
			var err error
			span, err = file.index.span(start, end)
			if err != nil {
				return err
			}
		}
		reason := "TYPESCRIPT_TYPE_ERROR"
		if phase == "syntax" {
			reason = "TYPESCRIPT_SYNTAX_ERROR"
		}
		if phase == "context" {
			reason = "TYPESCRIPT_CONTEXT_GAP"
		}
		switch value.Code() {
		case 2307, 2792, 7016, 2688, 6053, 6305:
			reason = "TYPESCRIPT_RESOLUTION_GAP"
		}
		severity := "info"
		if value.Category() == diagnostics.CategoryError {
			severity = "error"
		} else if value.Category() == diagnostics.CategoryWarning {
			severity = "warning"
		}
		message := value.Localize(locale.Default)
		// Compiler diagnostics can render an absolute module path. Preserve the
		// semantic relative spelling without leaking a task's capture root.
		message = strings.ReplaceAll(message, b.program.context.Root+"/", "project/")
		d := SemanticDiagnostic{Language: languageName(b.program.context.PublishLanguages[0]), Severity: severity, Code: "TS" + strconv.Itoa(int(value.Code())), Phase: phase, Reason: reason, Message: message, Span: span}
		if file != nil {
			d.File = file.logical
			d.Module = file.identity.Module
			d.Scope = file.identity.Scope
			d.World = file.identity.World
			d.Language = languageName(file.identity.Language)
		}
		b.facts.Diagnostics = append(b.facts.Diagnostics, d)
	}
	return nil
}
