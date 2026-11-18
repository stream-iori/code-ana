package engine

import (
	"math/bits"
	"slices"
	"sort"
	"strconv"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/checker"
	"github.com/microsoft/typescript-go/internal/scanner"
)

type SemanticReference struct {
	ID, Source, Caller, File, World, Language, Role, Resolution string
	Span                                                        SourceSpan
	Targets, External                                           []string
	Name                                                        string
}

func (b *semanticBuilder) referenceOwner(node *ast.Node, file *semanticFile) string {
	for parent := node.Parent; parent != nil; parent = parent.Parent {
		if index, ok := b.byNode[parent]; ok {
			return b.facts.Declarations[index].ID
		}
	}
	return b.facts.Declarations[b.modules[file.source]].ID
}
func callCallee(node *ast.Node) bool {
	container := node
	if parent := node.Parent; parent != nil && parent.Kind == ast.KindPropertyAccessExpression && parent.Name() == node {
		container = parent
	}
	for parent := container.Parent; parent != nil; parent = container.Parent {
		if (parent.Kind == ast.KindCallExpression || parent.Kind == ast.KindNewExpression) && parent.Expression() == container {
			return true
		}
		switch parent.Kind {
		case ast.KindParenthesizedExpression, ast.KindAsExpression, ast.KindTypeAssertionExpression, ast.KindNonNullExpression, ast.KindSatisfiesExpression:
			if parent.Expression() == container {
				container = parent
				continue
			}
		}
		break
	}
	return false
}
func jsxTagName(node *ast.Node) bool {
	container := node
	for parent := container.Parent; parent != nil; parent = container.Parent {
		switch parent.Kind {
		case ast.KindPropertyAccessExpression, ast.KindQualifiedName, ast.KindJsxNamespacedName:
			container = parent
			continue
		case ast.KindJsxOpeningElement, ast.KindJsxClosingElement, ast.KindJsxSelfClosingElement:
			return parent.TagName() == container
		}
		break
	}
	return false
}
func heritageName(node *ast.Node) bool {
	container := node
	for parent := container.Parent; parent != nil; parent = container.Parent {
		if parent.Kind == ast.KindPropertyAccessExpression || parent.Kind == ast.KindQualifiedName {
			container = parent
			continue
		}
		return parent.Parent != nil && parent.Parent.Kind == ast.KindHeritageClause &&
			((parent.Kind == ast.KindExpressionWithTypeArguments && parent.Expression() == container) ||
				(parent.Kind == ast.KindTypeReference && parent.AsTypeReferenceNode().TypeName == container))
	}
	return false
}
func referenceRole(node *ast.Node) string {
	if parent := node.Parent; parent != nil && parent.Kind == ast.KindQualifiedName {
		if parent.AsQualifiedName().Left == node || (parent.Parent != nil && parent.Parent.Kind == ast.KindQualifiedName && parent.Parent.AsQualifiedName().Left == parent) {
			return "namespace_qualifier"
		}
	}
	container := node
	if parent := node.Parent; parent != nil && parent.Kind == ast.KindPropertyAccessExpression && parent.Name() == node {
		container = parent
	}
	if assignment := ast.GetAssignmentTarget(container); assignment != nil {
		if assignment.Kind == ast.KindBinaryExpression && assignment.AsBinaryExpression().OperatorToken.Kind != ast.KindEqualsToken {
			return "read_write"
		}
		if assignment.Kind == ast.KindPrefixUnaryExpression || assignment.Kind == ast.KindPostfixUnaryExpression {
			return "read_write"
		}
		return "write"
	}
	for parent := node.Parent; parent != nil; parent = parent.Parent {
		if parent.Kind == ast.KindTypeQuery {
			return "value_type_query"
		}
		if ast.IsTypeNode(parent) {
			return "type"
		}
		if ast.IsExpressionNode(parent) || ast.IsStatement(parent) {
			break
		}
	}
	return "read"
}
func matchesReferenceSpace(kind, role string) bool {
	if role == "extends" || role == "implements" {
		return kind == "class" || kind == "interface"
	}
	if role == "imports" || role == "exports" || role == "imports_type" || role == "exports_type" || role == "exports_symbol" {
		return true
	}
	if role == "namespace_qualifier" {
		return kind == "namespace" || kind == "module" || kind == "enum" || kind == "class"
	}
	if role == "type" {
		return kind == "interface" || kind == "type_alias" || kind == "type_parameter" || kind == "class" || kind == "enum"
	}
	if kind == "get_accessor" {
		return role != "write"
	}
	if kind == "set_accessor" {
		return role == "write" || role == "read_write"
	}
	return kind != "interface" && kind != "type_alias" && kind != "type_parameter"

}
func (b *semanticBuilder) reference(c *checker.Checker, file *semanticFile, node *ast.Node) (*SemanticReference, error) {
	var symbol *ast.Symbol
	role := ""
	name := node
	kind := declarationKind(node)
	if node.Kind == ast.KindJsxOpeningElement || node.Kind == ast.KindJsxSelfClosingElement {
		name = node.TagName()
		if name == nil || ast.IsJsxNamespacedName(name) || (ast.IsIdentifier(name) && scanner.IsIntrinsicJsxName(name.Text())) {
			return nil, nil
		}
		role = "jsx_component"
		symbol = c.GetSymbolAtLocation(name)
	} else if ast.IsRequireCall(node, true) {
		// Local bindings named require are ordinary calls. Only the compiler's
		// unshadowed module form can contribute a static module import.
		if local := c.GetSymbolAtLocation(node.Expression()); local != nil {
			for _, declaration := range local.Declarations {
				if ast.GetSourceFileOfNode(declaration) == file.source {
					return nil, nil
				}
			}
		}
		name = node.Arguments()[0]
		role = "imports_module_commonjs"
		symbol = c.ResolveExternalModuleName(name)
	} else if (node.Kind == ast.KindExpressionWithTypeArguments || node.Kind == ast.KindTypeReference) && node.Parent != nil && node.Parent.Kind == ast.KindHeritageClause {
		if node.Kind == ast.KindTypeReference {
			name = node.AsTypeReferenceNode().TypeName
		} else {
			name = node.Expression()
		}
		role = "extends"
		if node.Parent.AsHeritageClause().Token == ast.KindImplementsKeyword {
			role = "implements"
		}
		symbol = c.GetSymbolAtLocation(name)
	} else if node.Kind == ast.KindImportDeclaration || node.Kind == ast.KindExportDeclaration {
		name = node.ModuleSpecifier()
		if name == nil {
			return nil, nil
		}
		role = "imports_module"
		if node.Kind == ast.KindExportDeclaration {
			role = "exports_module"
		}
		if ast.IsExclusivelyTypeOnlyImportOrExport(node) {
			role += "_type"
		}
		symbol = c.ResolveExternalModuleName(name)
	} else if kind == "import" || kind == "export" {
		name = node.Name()
		if name == nil {
			return nil, nil
		}
		if kind == "export" {
			role = "exports"
			symbol = c.GetExportSpecifierLocalTargetSymbol(node)
		} else {
			role = "imports"
			symbol = c.GetSymbolAtLocation(name)
		}
		if ast.IsTypeOnlyImportOrExportDeclaration(node) {
			role += "_type"
		}
	} else {
		if node.Kind != ast.KindIdentifier && node.Kind != ast.KindPrivateIdentifier {
			return nil, nil
		}
		parent := node.Parent
		if parent == nil {
			return nil, nil
		}
		if parent.Kind == ast.KindShorthandPropertyAssignment && parent.Name() == node {
			symbol = c.GetShorthandAssignmentValueSymbol(parent)
		} else {
			if ast.IsDeclarationNameOrImportPropertyName(node) || parent.Kind == ast.KindExportSpecifier || parent.Kind == ast.KindImportSpecifier || callCallee(node) || jsxTagName(node) || heritageName(node) {
				return nil, nil
			}
			// Labels and JSX tag/attribute names are separate syntactic roles;
			// they are not implicit reads or synthetic runtime calls.
			switch parent.Kind {
			case ast.KindLabeledStatement, ast.KindBreakStatement, ast.KindContinueStatement, ast.KindJsxOpeningElement, ast.KindJsxClosingElement, ast.KindJsxSelfClosingElement, ast.KindJsxAttribute:
				return nil, nil
			}
			symbol = c.GetSymbolAtLocation(node)
		}
		role = referenceRole(node)
	}
	if symbol != nil && symbol.Flags&ast.SymbolFlagsAlias != 0 {
		symbol = c.GetAliasedSymbol(symbol)
	}
	if symbol != nil {
		symbol = c.GetMergedSymbol(symbol)
	}
	count := 0
	if symbol != nil {
		count = len(symbol.Declarations)
	}
	if err := b.program.meter.Charge(uint64(count)*2*(uint64(bits.Len(uint(count)))+1)+1, uint64(count)+1, uint64(count)*512+4096); err != nil {
		return nil, err
	}
	start, end := scanner.SkipTrivia(file.source.Text(), name.Pos()), name.End()
	span, err := file.index.span(start, end)
	if err != nil {
		return nil, err
	}
	reference := &SemanticReference{ID: semanticKey("ts-reference", b.program.context.Unit, file.identity.World, file.logical, role, strconv.Itoa(start), strconv.Itoa(end)), Source: b.referenceOwner(node, file), Caller: b.caller(node, file), File: file.logical, World: file.identity.World, Language: languageName(file.identity.Language), Role: role, Resolution: "unknown", Span: span}
	if kind == "import" || kind == "export" {
		if index, ok := b.byNode[node]; ok {
			reference.Source = b.facts.Declarations[index].ID
		}
	}
	if symbol == nil {
		return reference, nil
	}
	if role == "type" && symbol.Flags&ast.SymbolFlagsType == 0 {
		return reference, nil
	}
	if role == "namespace_qualifier" && symbol.Flags&ast.SymbolFlagsNamespace == 0 {
		return reference, nil
	}
	if (role == "read" || role == "write" || role == "read_write" || role == "value_type_query" || role == "jsx_component") && symbol.Flags&ast.SymbolFlagsValue == 0 {
		return reference, nil
	}
	for _, declaration := range symbol.Declarations {
		if symbol.Flags&ast.SymbolFlagsProperty != 0 && declaration.Kind == ast.KindParameter && ast.GetCombinedModifierFlags(declaration)&ast.ModifierFlagsParameterPropertyModifier != 0 {
			continue
		}
		if index, ok := b.byNode[declaration]; ok {
			if matchesReferenceSpace(b.facts.Declarations[index].Kind, role) {
				reference.Targets = append(reference.Targets, b.facts.Declarations[index].ID)
			}
		} else if kind := declarationKind(declaration); kind != "" && !matchesReferenceSpace(kind, role) {
			continue
		} else if external := b.external(declaration); external != "" {
			reference.External = append(reference.External, external)
		}
	}
	sort.Strings(reference.Targets)
	reference.Targets = slices.Compact(reference.Targets)
	sort.Strings(reference.External)
	reference.External = slices.Compact(reference.External)
	if len(reference.Targets) > 0 || len(reference.External) > 0 {
		reference.Resolution = "symbol"
	}
	return reference, nil
}
