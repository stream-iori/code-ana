package engine

import (
	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/checker"
)

// A constructor parameter property has two compiler symbols with one syntax
// declaration. Keep its lexical parameter and instance member independently.
func (b *semanticBuilder) declarationIndex(symbol *ast.Symbol, node *ast.Node) (int, bool) {
	if symbol.Flags&ast.SymbolFlagsProperty != 0 {
		if index, ok := b.properties[node]; ok {
			return index, true
		}
	}
	index, ok := b.byNode[node]
	return index, ok
}
func (b *semanticBuilder) parameterProperty(node *ast.Node, parameter int) error {
	class := node.Parent.Parent
	owner, ok := b.byNode[class]
	if !ok {
		return invalid("parameter property class is not in declaration table")
	}
	parent := b.facts.Declarations[owner]
	d := b.facts.Declarations[parameter]
	if err := b.program.meter.Charge(1, 1, uint64(len(parent.QualifiedName)+len(d.Name))*8+8192); err != nil {
		return err
	}
	d.ID = semanticKey("ts-parameter-property", parent.ID, d.ID)
	d.Kind = "property"
	d.Parent = parent.ID
	d.QualifiedName = parent.QualifiedName + "::" + d.Name
	d.Depth = parent.Depth + 1
	d.Synthetic = true
	d.ParameterProperty = true
	d.Implementation = false
	d.Visibility = "public"
	flags := ast.GetCombinedModifierFlags(node)
	if flags&ast.ModifierFlagsPrivate != 0 {
		d.Visibility = "private"
	} else if flags&ast.ModifierFlagsProtected != 0 {
		d.Visibility = "protected"
	}
	b.properties[node] = len(b.facts.Declarations)
	b.facts.Declarations = append(b.facts.Declarations, d)
	return nil
}
func (b *semanticBuilder) bindParameterProperty(c *checker.Checker, node *ast.Node, index int) error {
	class := node.Parent.Parent
	symbol := class.Symbol()
	if class.Name() != nil {
		symbol = c.GetSymbolAtLocation(class.Name())
	}
	if symbol == nil {
		return nil
	}
	property := c.GetPropertyOfType(c.GetDeclaredTypeOfSymbol(symbol), node.Name().Text())
	group, flags, err := b.groupForSymbol(c, property)
	if err != nil {
		return err
	}
	d := &b.facts.Declarations[index]
	d.SymbolGroup = group
	d.Resolved = group != ""
	d.ValueSpace = flags&ast.SymbolFlagsValue != 0
	return nil
}
