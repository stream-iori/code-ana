package engine

import (
	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/checker"
)

func (b *semanticBuilder) implicitConstructor(c *checker.Checker, t *checker.Type, call SemanticCall) (SemanticCall, error) {
	symbol := t.Symbol()
	if symbol == nil {
		return call, nil
	}
	symbol = c.GetMergedSymbol(symbol)
	if symbol == nil || symbol.Flags&ast.SymbolFlagsClass == 0 {
		return call, nil
	}
	if err := b.program.meter.Charge(uint64(len(symbol.Declarations))+1, 1, 512); err != nil {
		return SemanticCall{}, err
	}
	var selected *ast.Node
	for _, d := range symbol.Declarations {
		if d.Kind != ast.KindClassDeclaration && d.Kind != ast.KindClassExpression {
			continue
		}
		if selected != nil {
			return call, nil
		}
		selected = d
		if err := b.program.meter.Charge(uint64(len(d.Members()))+1, 0, 0); err != nil {
			return SemanticCall{}, err
		}
		for _, member := range d.Members() {
			if member.Kind == ast.KindConstructor {
				return call, nil
			}
		}
	}
	if selected == nil {
		return call, nil
	}
	if index, ok := b.byNode[selected]; ok {
		call.Target = b.facts.Declarations[index].ID
		call.SelectedDeclaration = call.Target
		call.TargetKind = "declaration"
		call.Reason = "implicit_constructor_declaration"
	} else if external := b.external(selected); external != "" {
		call.External = external
		call.TargetKind = "external"
		call.Reason = "implicit_constructor_external_declaration"
	}
	return call, nil
}
