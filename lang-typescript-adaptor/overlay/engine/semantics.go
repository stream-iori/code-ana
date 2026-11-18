package engine

import (
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"math/bits"
	"path"
	"slices"
	"sort"
	"strconv"
	"strings"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/checker"
	"github.com/microsoft/typescript-go/internal/diagnostics"
	"github.com/microsoft/typescript-go/internal/scanner"
)

// These detached records contain neither compiler handles nor machine paths.
// Declaration identity and merged-symbol identity are separate: overload and
// declaration-merging provenance is never collapsed into a chosen first node.
type SemanticDeclaration struct {
	ID, SymbolGroup, Parent, File, Name, QualifiedName, Kind string
	Module, Scope, World, Language, Visibility               string
	Span, NameSpan                                           SourceSpan
	ValueSpace, TypeSpace, NamespaceSpace                    bool
	Implementation, DeclarationFile, Synthetic, Resolved     bool
	ParameterProperty                                        bool
	Depth                                                    int
}
type SemanticCall struct {
	ID, Caller, File, World, Language, SyntaxKind             string
	Span                                                      SourceSpan
	Target, SelectedDeclaration, TargetKind, Reason, External string
	Ordinal                                                   uint64
}
type SemanticFacts struct {
	Declarations []SemanticDeclaration
	Calls        []SemanticCall
	References   []SemanticReference
	Comments     []SemanticComment
	Diagnostics  []SemanticDiagnostic
}

type semanticFile struct {
	source   *ast.SourceFile
	identity *pb.FileIdentity
	logical  string
	index    *sourceIndex
}
type semanticBuilder struct {
	program    *Program
	facts      SemanticFacts
	files      []*semanticFile
	byNode     map[*ast.Node]int
	properties map[*ast.Node]int
	modules    map[*ast.SourceFile]int
	identities map[string]uint64
}

// Length-prefixed UTF-8 components give an unambiguous, language-independent
// identity encoding. No Go IDs, heap addresses, SDK path or scratch is included.
func semanticKey(namespace string, parts ...string) string {
	h := sha256.New()
	var size [8]byte
	for _, part := range parts {
		binary.BigEndian.PutUint64(size[:], uint64(len(part)))
		h.Write(size[:])
		h.Write([]byte(part))
	}
	return namespace + "_" + hex.EncodeToString(h.Sum(nil))
}
func languageName(language pb.SourceLanguage) string {
	if language == pb.SourceLanguage_JAVASCRIPT {
		return "javascript"
	}
	return "typescript"
}
func declarationKind(node *ast.Node) string {
	switch node.Kind {
	case ast.KindClassDeclaration, ast.KindClassExpression:
		return "class"
	case ast.KindInterfaceDeclaration:
		return "interface"
	case ast.KindFunctionDeclaration, ast.KindFunctionExpression, ast.KindArrowFunction:
		return "function"
	case ast.KindMethodDeclaration, ast.KindMethodSignature:
		return "method"
	case ast.KindConstructor:
		return "constructor"
	case ast.KindGetAccessor:
		return "get_accessor"
	case ast.KindSetAccessor:
		return "set_accessor"
	case ast.KindPropertyDeclaration, ast.KindPropertySignature, ast.KindPropertyAssignment, ast.KindShorthandPropertyAssignment:
		return "property"
	case ast.KindVariableDeclaration, ast.KindBindingElement:
		return "variable"
	case ast.KindParameter:
		return "parameter"
	case ast.KindTypeAliasDeclaration, ast.KindJSTypeAliasDeclaration:
		return "type_alias"
	case ast.KindTypeParameter:
		return "type_parameter"
	case ast.KindEnumDeclaration:
		return "enum"
	case ast.KindEnumMember:
		return "enum_member"
	case ast.KindModuleDeclaration:
		return "namespace"
	case ast.KindImportSpecifier, ast.KindImportClause, ast.KindNamespaceImport, ast.KindImportEqualsDeclaration:
		return "import"
	case ast.KindExportSpecifier:
		return "export"
	case ast.KindCallSignature, ast.KindFunctionType:
		return "call_signature"
	case ast.KindConstructSignature, ast.KindConstructorType:
		return "construct_signature"
	case ast.KindIndexSignature:
		return "index_signature"
	}
	return ""
}
func callable(node *ast.Node) bool { return node != nil && ast.IsFunctionLike(node) }
func implementation(node *ast.Node, file *ast.SourceFile) bool {
	return callable(node) && node.Body() != nil && !file.IsDeclarationFile && ast.GetCombinedModifierFlags(node)&ast.ModifierFlagsAmbient == 0 && node.Flags&ast.NodeFlagsAmbient == 0
}
func semanticVisibility(node *ast.Node, file *ast.SourceFile) string {
	flags := ast.GetCombinedModifierFlags(node)
	if flags&ast.ModifierFlagsPrivate != 0 || (node.Name() != nil && node.Name().Kind == ast.KindPrivateIdentifier) {
		return "private"
	}
	if flags&ast.ModifierFlagsProtected != 0 {
		return "protected"
	}
	if flags&(ast.ModifierFlagsPublic|ast.ModifierFlagsExport) != 0 {
		return "public"
	}
	if node.Kind == ast.KindExportSpecifier {
		return "public"
	}
	if node.Kind == ast.KindEnumMember {
		return "public"
	}
	if parent := node.Parent; parent != nil {
		switch parent.Kind {
		case ast.KindClassDeclaration, ast.KindClassExpression, ast.KindInterfaceDeclaration, ast.KindTypeLiteral, ast.KindObjectLiteralExpression:
			return "public"
		}
	}
	if node.Kind == ast.KindParameter || node.Kind == ast.KindTypeParameter {
		return "local"
	}
	for parent := node.Parent; parent != nil; parent = parent.Parent {
		if callable(parent) {
			return "local"
		}
		if parent.Kind == ast.KindModuleDeclaration {
			return "namespace"
		}
	}
	if ast.IsExternalOrCommonJSModule(file) {
		return "module"
	}
	return "public"
}
func (b *semanticBuilder) declaration(file *semanticFile, node *ast.Node, parent int) (int, error) {
	kind := declarationKind(node)
	if kind == "" {
		return parent, nil
	}
	nameNode := node.Name()
	// Binding patterns get individual BindingElement declarations; the pattern
	// itself is neither a local name nor a fabricated callable declaration.
	if nameNode != nil && ast.IsBindingPattern(nameNode) {
		return parent, nil
	}
	start, end := scanner.SkipTrivia(file.source.Text(), node.Pos()), node.End()
	if err := b.program.meter.Charge(1, 1, uint64(end-start)*2+uint64(len(b.facts.Declarations[parent].QualifiedName))*4+4096); err != nil {
		return 0, err
	}
	span, err := file.index.span(start, end)
	if err != nil {
		return 0, err
	}
	name := ""
	nameSpan := SourceSpan{span.Start, span.Start}
	synthetic := false
	if nameNode != nil {
		a, z := scanner.SkipTrivia(file.source.Text(), nameNode.Pos()), nameNode.End()
		nameSpan, err = file.index.span(a, z)
		if err != nil {
			return 0, err
		}
		name = nameNode.Text()
		if name == "" {
			name = file.source.Text()[a:z]
		}
	} else {
		if kind == "import" {
			return parent, nil
		} // `import {x}` has no default binding.
		name = "<" + kind + ">"
		if ast.GetCombinedModifierFlags(node)&ast.ModifierFlagsDefault != 0 {
			name = "default"
		}
		if kind == "constructor" {
			name = "constructor"
		}

		synthetic = true
		if kind == "constructor" {
			at := start
			if modifiers := node.Modifiers(); modifiers != nil {
				at = scanner.SkipTrivia(file.source.Text(), modifiers.End())
			}
			token := scanner.GetScannerForSourceFile(file.source, at)
			if token.Token() == ast.KindConstructorKeyword || (token.Token() == ast.KindStringLiteral && token.TokenValue() == "constructor") {
				nameSpan, err = file.index.span(token.TokenStart(), token.TokenEnd())
				if err != nil {
					return 0, err
				}
				synthetic = false
			}
		}
	}
	parentDecl := &b.facts.Declarations[parent]
	qualified := parentDecl.QualifiedName + "::" + name
	visibility := semanticVisibility(node, file.source)
	if kind == "parameter" {
		visibility = "local"
	}
	role := "declaration"
	if callable(node) {
		role = "signature"
		if implementation(node, file.source) {
			role = "implementation"
		}
	}
	base := semanticKey("ts-declaration-scope", b.program.context.Unit, file.identity.World, file.logical, parentDecl.ID, kind, name, role)
	ordinal := b.identities[base]
	b.identities[base] = ordinal + 1
	id := semanticKey("ts-declaration", base, strconv.FormatUint(ordinal, 10))

	declaration := SemanticDeclaration{ID: id, Parent: parentDecl.ID, File: file.logical, Name: name, QualifiedName: qualified, Kind: kind, Module: file.identity.Module, Scope: file.identity.Scope, World: file.identity.World, Language: languageName(file.identity.Language), Visibility: visibility, Span: span, NameSpan: nameSpan, Implementation: implementation(node, file.source), DeclarationFile: file.source.IsDeclarationFile, Synthetic: synthetic, Depth: parentDecl.Depth + 1}
	index := len(b.facts.Declarations)
	b.facts.Declarations = append(b.facts.Declarations, declaration)
	b.byNode[node] = index
	if ast.IsParameterPropertyDeclaration(node, node.Parent) {
		if err := b.parameterProperty(node, index); err != nil {
			return 0, err
		}
	}
	return index, nil
}
func (b *semanticBuilder) collect(file *semanticFile) error {
	if err := b.program.meter.Charge(1, 1, uint64(len(file.logical))*4+2048); err != nil {
		return err
	}
	span, err := file.index.span(0, len(file.source.Text()))
	if err != nil {
		return err
	}
	id := semanticKey("ts-declaration", b.program.context.Unit, file.identity.World, file.logical, "module")
	module := SemanticDeclaration{ID: id, SymbolGroup: id, File: file.logical, Name: file.logical, QualifiedName: file.logical, Kind: "module", Module: file.identity.Module, Scope: file.identity.Scope, World: file.identity.World, Language: languageName(file.identity.Language), Visibility: "module", Span: span, NameSpan: SourceSpan{span.Start, span.Start}, Synthetic: true, Resolved: true}
	parent := len(b.facts.Declarations)
	b.modules[file.source] = parent
	b.byNode[file.source.AsNode()] = parent
	b.facts.Declarations = append(b.facts.Declarations, module)
	var visit func(*ast.Node, int, uint32) error
	visit = func(node *ast.Node, parent int, depth uint32) error {
		if err := b.program.meter.Depth(depth); err != nil {
			return err
		}
		if err := b.program.meter.Charge(uint64(depth)*4+4, 1, 0); err != nil {
			return err
		}
		owner, err := b.declaration(file, node, parent)
		if err != nil {
			return err
		}
		var failure error
		node.ForEachChild(func(child *ast.Node) bool { failure = visit(child, owner, depth+1); return failure != nil })
		return failure
	}
	return visit(file.source.AsNode(), parent, 1)
}
func (b *semanticBuilder) symbolGroup(c *checker.Checker, node *ast.Node) (string, ast.SymbolFlags, error) {
	if _, ok := b.properties[node]; ok {
		return b.groupForSymbol(c, c.ResolveName(node.Name().Text(), node, ast.SymbolFlagsFunctionScopedVariable, true))
	}
	var symbol *ast.Symbol
	if node.Name() != nil && !ast.IsBindingPattern(node.Name()) {
		symbol = c.GetSymbolAtLocation(node.Name())
	}
	if symbol == nil {
		symbol = node.Symbol()
	}
	if symbol == nil {
		return "", 0, nil
	}
	return b.groupForSymbol(c, symbol)
}
func (b *semanticBuilder) groupForSymbol(c *checker.Checker, symbol *ast.Symbol) (string, ast.SymbolFlags, error) {
	if symbol == nil {
		return "", 0, nil
	}
	symbol = c.GetMergedSymbol(symbol)
	if symbol == nil {
		return "", 0, nil
	}
	if err := b.program.meter.Charge(uint64(len(symbol.Declarations))*(uint64(bits.Len(uint(len(symbol.Declarations))))+1)+1, 1, uint64(len(symbol.Declarations))*128+512); err != nil {
		return "", 0, err
	}
	ids := make([]string, 0, len(symbol.Declarations))
	for _, decl := range symbol.Declarations {
		if index, ok := b.declarationIndex(symbol, decl); ok {
			ids = append(ids, b.facts.Declarations[index].ID)
		}
	}
	sort.Strings(ids)
	ids = slices.Compact(ids)
	if len(ids) == 0 {
		return "", symbol.Flags, nil
	}
	return semanticKey("ts-symbol", append([]string{b.program.context.Unit}, ids...)...), symbol.Flags, nil
}
func (b *semanticBuilder) caller(node *ast.Node, file *semanticFile) string {
	for owner := node.Parent; owner != nil; owner = owner.Parent {
		if callable(owner) {
			if index, ok := b.byNode[owner]; ok {
				return b.facts.Declarations[index].ID
			}
		}
	}
	return b.facts.Declarations[b.modules[file.source]].ID
}
func (b *semanticBuilder) external(node *ast.Node) string {
	file := ast.GetSourceFileOfNode(node)
	if file == nil {
		return ""
	}
	name := "<anonymous>"
	if node.Name() != nil {
		name = node.Name().Text()
	}
	logical := ""
	if strings.HasPrefix(file.FileName(), bundled.LibPath()+"/") {
		logical = "typescript-lib/" + path.Base(file.FileName()) + "@" + RuntimeIdentity().StandardLibraryDigest
	} else if identity := b.program.files[b.program.frozen.resolve(file.FileName())]; identity != nil {
		logical = strings.TrimPrefix(identity.Path, b.program.context.Root+"/")
		if logical == identity.Path {
			logical = "dependency/" + identity.Sha256
		} else {
			logical += "@" + identity.Sha256
		}
	}
	if logical == "" {
		return ""
	}
	return logical + "#" + name + ":" + strconv.Itoa(scanner.SkipTrivia(file.Text(), node.Pos()))
}
func (b *semanticBuilder) call(c *checker.Checker, file *semanticFile, node *ast.Node, errors []*ast.Diagnostic, ordinal uint64) (SemanticCall, error) {
	start, end := scanner.SkipTrivia(file.source.Text(), node.Pos()), node.End()
	span, err := file.index.span(start, end)
	if err != nil {
		return SemanticCall{}, err
	}
	call := SemanticCall{ID: semanticKey("ts-call", b.program.context.Unit, file.identity.World, file.logical, strconv.Itoa(start), strconv.Itoa(end), strconv.FormatUint(ordinal, 10)), Caller: b.caller(node, file), File: file.logical, World: file.identity.World, Language: languageName(file.identity.Language), Span: span, TargetKind: "unknown", Reason: "unresolved_signature", Ordinal: ordinal}
	call.SyntaxKind = "call"
	if node.Kind == ast.KindNewExpression {
		call.SyntaxKind = "new"
	}
	for _, diagnostic := range errors {
		if diagnostic.Category() == diagnostics.CategoryError && diagnostic.Pos() <= end && diagnostic.End() >= start {
			call.Reason = "call_type_error"
			return call, nil
		}
	}
	expression := node.Expression()
	if expression == nil {
		return call, nil
	}
	if ast.IsElementAccessExpression(expression) {
		argument := expression.AsElementAccessExpression().ArgumentExpression
		if argument == nil || (argument.Kind != ast.KindStringLiteral && argument.Kind != ast.KindNumericLiteral) {
			call.Reason = "dynamic_property"
			return call, nil
		}
	}
	t := c.GetTypeAtLocation(expression)
	if t == nil || t.Flags()&(checker.TypeFlagsAnyOrUnknown|checker.TypeFlagsUnionOrIntersection) != 0 {
		call.Reason = "dynamic_callee_type"
		return call, nil
	}
	signature := c.GetResolvedSignature(node)
	if signature == nil {
		return call, nil
	}
	if signature.Declaration() == nil {
		if node.Kind == ast.KindNewExpression {
			return b.implicitConstructor(c, t, call)
		}
		return call, nil
	}
	selected := signature.Declaration()
	if index, ok := b.byNode[selected]; ok {
		call.Target = b.facts.Declarations[index].ID
		call.SelectedDeclaration = call.Target
		call.TargetKind = "declaration"
		call.Reason = "selected_signature"
		if node.Kind == ast.KindNewExpression && selected.Kind == ast.KindClassDeclaration {
			call.Reason = "implicit_constructor_declaration"
		}
	} else {
		call.External = b.external(selected)
		if call.External != "" {
			call.TargetKind = "external"
			call.Reason = "captured_external_declaration"
		}
		return call, nil
	}
	// The compiler may select a body for an inline or immutable local callable.
	// An arbitrary parameter, mutable variable or structural signature remains
	// declaration evidence; selecting its signature is not a runtime proof.
	if index, ok := b.byNode[selected]; ok && b.facts.Declarations[index].Implementation {
		if selected.Kind == ast.KindConstructor && node.Kind == ast.KindNewExpression {
			call.TargetKind, call.Reason = "implementation", "selected_constructor_body"
			return call, nil
		}
		if ast.SkipParentheses(expression) == selected {
			call.TargetKind, call.Reason = "implementation", "inline_callable_body"
			return call, nil
		}
	}
	symbol := c.GetSymbolAtLocation(expression)
	if ast.IsElementAccessExpression(expression) {
		symbol = c.GetSymbolAtLocation(expression.AsElementAccessExpression().ArgumentExpression)
	}
	if symbol != nil && symbol.Flags&ast.SymbolFlagsAlias != 0 {
		symbol = c.GetAliasedSymbol(symbol)
	}
	if symbol != nil && symbol.ValueDeclaration != nil {
		declaration := symbol.ValueDeclaration
		if declaration.Kind == ast.KindVariableDeclaration && ast.GetCombinedNodeFlags(declaration)&ast.NodeFlagsConst != 0 && declaration.Initializer() != nil && ast.SkipParentheses(declaration.Initializer()) == selected {
			if index, ok := b.byNode[selected]; ok && b.facts.Declarations[index].Implementation {
				call.TargetKind, call.Reason = "implementation", "immutable_callable_initializer"
				return call, nil
			}
		}
	}
	if symbol == nil || symbol.Flags&(ast.SymbolFlagsFunction|ast.SymbolFlagsMethod) == 0 {
		return call, nil
	}
	symbol = c.GetMergedSymbol(symbol)
	if symbol == nil || !slices.Contains(symbol.Declarations, selected) {
		return call, nil
	}
	implementationID := ""
	if err := b.program.meter.Charge(uint64(len(symbol.Declarations))+1, 0, 0); err != nil {
		return SemanticCall{}, err
	}
	for _, decl := range symbol.Declarations {
		if index, ok := b.byNode[decl]; ok && b.facts.Declarations[index].Implementation {
			if implementationID != "" {
				call.Reason = "multiple_implementations"
				return call, nil
			}
			implementationID = b.facts.Declarations[index].ID
		}
	}
	if implementationID != "" {
		call.Target = implementationID
		call.TargetKind = "implementation"
		call.Reason = "function_symbol_unique_body"
		if symbol.Flags&ast.SymbolFlagsMethod != 0 {
			call.Reason = "method_symbol_unique_body"
		}
	}
	return call, nil
}

// Semantics completes the declaration table before any endpoint is selected.
// A Checker and all of its Types live in one file callback and are released
// before acquiring the next Checker; detached facts retain only owned values.
func (p *Program) Semantics(ctx context.Context) (*SemanticFacts, error) {
	sources := p.program.SourceFiles()
	if err := p.meter.Charge(uint64(len(sources)+len(p.files))+1, uint64(len(p.files))+1, uint64(len(p.files))*512+1024); err != nil {
		return nil, err
	}
	b := &semanticBuilder{program: p, byNode: make(map[*ast.Node]int), properties: make(map[*ast.Node]int), modules: make(map[*ast.SourceFile]int), identities: make(map[string]uint64)}
	for _, source := range sources {
		if strings.HasPrefix(source.FileName(), bundled.LibPath()+"/") {
			continue
		}
		identity := p.files[p.frozen.resolve(source.FileName())]
		if identity == nil || identity.Role != pb.FileRole_PUBLISH {
			continue
		}
		logical := strings.TrimPrefix(identity.Path, p.context.Root+"/")
		if logical == identity.Path {
			return nil, invalid("published source outside context root")
		}
		index, err := newSourceIndex(source.Text(), p.meter)
		if err != nil {
			return nil, err
		}
		b.files = append(b.files, &semanticFile{source, identity, logical, index})
	}
	if err := p.meter.Charge(uint64(len(b.files))*(uint64(bits.Len(uint(len(b.files))))+1), 0, 0); err != nil {
		return nil, err
	}
	sort.Slice(b.files, func(i, j int) bool { return b.files[i].logical < b.files[j].logical })
	for _, file := range b.files {
		if err := b.collect(file); err != nil {
			return nil, err
		}
	}
	for _, file := range b.files {
		if err := b.comments(file); err != nil {
			return nil, err
		}
	}
	for _, file := range b.files {
		syntax := p.program.GetSyntacticDiagnostics(ctx, file.source)
		if err := b.diagnostics(file, syntax, "syntax"); err != nil {
			return nil, err
		}
		errors := p.program.GetSemanticDiagnostics(ctx, file.source)
		if err := b.diagnostics(file, errors, "check"); err != nil {
			return nil, err
		}
		if err := p.meter.Charge(uint64(len(syntax)+len(errors))+1, 1, uint64(len(syntax)+len(errors))*16+128); err != nil {
			return nil, err
		}
		callErrors := append(append(make([]*ast.Diagnostic, 0, len(syntax)+len(errors)), syntax...), errors...)
		c, done := p.program.GetTypeCheckerForFile(ctx, file.source)
		err := func() error {
			defer done()
			if err := b.moduleExports(c, file); err != nil {
				return err
			}
			var ordinal uint64
			var visit func(*ast.Node, uint32) error
			visit = func(node *ast.Node, depth uint32) error {
				if err := p.meter.Depth(depth); err != nil {
					return err
				}
				if err := p.meter.Charge(uint64(depth)*8+8, 1, 0); err != nil {
					return err
				}
				if index, ok := b.byNode[node]; ok && node.Kind != ast.KindSourceFile {
					group, flags, err := b.symbolGroup(c, node)
					if err != nil {
						return err
					}
					decl := &b.facts.Declarations[index]
					decl.SymbolGroup = group
					decl.Resolved = group != ""
					decl.ValueSpace = flags&ast.SymbolFlagsValue != 0
					decl.TypeSpace = flags&ast.SymbolFlagsType != 0
					decl.NamespaceSpace = flags&ast.SymbolFlagsNamespace != 0
					if property, ok := b.properties[node]; ok {
						if err := b.bindParameterProperty(c, node, property); err != nil {
							return err
						}
					}
				}
				gap := ""
				if node.Kind == ast.KindTaggedTemplateExpression {
					gap = "TYPESCRIPT_TAGGED_TEMPLATE_UNSUPPORTED"
				}
				if gap != "" {
					if err := p.meter.Charge(1, 1, 2048); err != nil {
						return err
					}
					span, err := file.index.span(scanner.SkipTrivia(file.source.Text(), node.Pos()), node.End())
					if err != nil {
						return err
					}
					d := file.identity
					b.facts.Diagnostics = append(b.facts.Diagnostics, SemanticDiagnostic{File: file.logical, Module: d.Module, Scope: d.Scope, World: d.World, Language: languageName(d.Language), Severity: "warning", Code: gap, Phase: "extract", Reason: gap, Message: "Source construct is retained with incomplete semantic extraction", Span: span})
				}
				reference, err := b.reference(c, file, node)
				if err != nil {
					return err
				}
				if reference != nil {
					b.facts.References = append(b.facts.References, *reference)
				}
				if node.Kind == ast.KindCallExpression || node.Kind == ast.KindNewExpression {
					if err := p.meter.Charge(uint64(len(callErrors))+1, 1, 2048); err != nil {
						return err
					}
					call, err := b.call(c, file, node, callErrors, ordinal)
					if err != nil {
						return err
					}
					ordinal++
					b.facts.Calls = append(b.facts.Calls, call)
				}
				var failure error
				node.ForEachChild(func(child *ast.Node) bool { failure = visit(child, depth+1); return failure != nil })
				return failure
			}
			return visit(file.source.AsNode(), 1)
		}()
		if err != nil {
			return nil, err
		}
		if err := p.frozen.Check(); err != nil {
			return nil, err
		}
	}
	if len(b.files) > 0 {
		if err := b.diagnostics(nil, p.program.GetGlobalDiagnostics(ctx), "context"); err != nil {
			return nil, err
		}
		if err := b.diagnostics(nil, p.program.GetProgramDiagnostics(), "context"); err != nil {
			return nil, err
		}
	}
	if err := p.frozen.Check(); err != nil {
		return nil, err
	}
	if err := p.meter.Check(); err != nil {
		return nil, err
	}
	return &b.facts, nil
}
