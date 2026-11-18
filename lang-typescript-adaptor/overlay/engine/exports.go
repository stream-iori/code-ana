package engine

import (
	"math/bits"
	"slices"
	"sort"
	"strings"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/checker"
)

func (b *semanticBuilder) moduleExports(c *checker.Checker, file *semanticFile) error {
	module := c.GetSymbolAtLocation(file.source.AsNode())
	if module == nil {
		return nil
	}
	owner := b.facts.Declarations[b.modules[file.source]]
	values := map[string]SemanticReference{}
	var failure error
	add := func(symbol *ast.Symbol, name string) {
		if failure != nil {
			return
		}
		failure = b.program.meter.Charge(1, 1, uint64(len(name))*8+4096)
		if failure != nil {
			return
		}
		ref := SemanticReference{ID: semanticKey("ts-export", b.program.context.Unit, file.identity.World, file.logical, name), Source: owner.ID, Caller: owner.ID, File: file.logical, World: owner.World, Language: owner.Language, Role: "exports_symbol", Name: name, Span: owner.Span, Resolution: "unknown"}
		visited := map[*ast.Symbol]bool{}
		var targets func(*ast.Symbol, uint32) error
		targets = func(symbol *ast.Symbol, depth uint32) error {
			if err := b.program.meter.Depth(depth); err != nil {
				return err
			}
			if symbol == nil || visited[symbol] {
				return nil
			}
			if err := b.program.meter.Charge(uint64(len(symbol.Declarations))+1, 1, uint64(len(symbol.Declarations))*512+256); err != nil {
				return err
			}
			visited[symbol] = true
			if symbol.Flags&ast.SymbolFlagsAlias != 0 {
				return targets(c.GetAliasedSymbol(symbol), depth+1)
			}
			for _, declaration := range symbol.Declarations {
				if index, ok := b.declarationIndex(symbol, declaration); ok {
					ref.Targets = append(ref.Targets, b.facts.Declarations[index].ID)
				} else if declaration.Kind == ast.KindExportAssignment || (declaration.Kind == ast.KindBinaryExpression && declaration.AsBinaryExpression().OperatorToken.Kind == ast.KindEqualsToken) {
					var right *ast.Node
					if declaration.Kind == ast.KindBinaryExpression {
						right = declaration.AsBinaryExpression().Right
					} else {
						right = declaration.Expression()
					}
					right = ast.SkipParentheses(right)
					if index, ok := b.byNode[right]; ok {
						ref.Targets = append(ref.Targets, b.facts.Declarations[index].ID)
					} else if err := targets(c.GetSymbolAtLocation(right), depth+1); err != nil {
						return err
					}
				} else if source := ast.GetSourceFileOfNode(declaration); source != nil {
					var identity *pb.FileIdentity
					if !strings.HasPrefix(source.FileName(), bundled.LibPath()+"/") {
						identity = b.program.files[b.program.frozen.resolve(source.FileName())]
					}
					if identity == nil || identity.Role != pb.FileRole_PUBLISH {
						if external := b.external(declaration); external != "" {
							ref.External = append(ref.External, external)
						}
					}
				}
			}
			return nil
		}
		failure = targets(symbol, 1)
		if failure != nil {
			return
		}
		if err := b.program.meter.Charge(uint64(len(ref.Targets)+len(ref.External))*(uint64(bits.Len(uint(len(ref.Targets)+len(ref.External))))+1), 0, 0); err != nil {
			failure = err
			return
		}
		sort.Strings(ref.Targets)
		ref.Targets = slices.Compact(ref.Targets)
		sort.Strings(ref.External)
		ref.External = slices.Compact(ref.External)
		if len(ref.Targets)+len(ref.External) > 0 {
			ref.Resolution = "symbol"
		}
		if previous, ok := values[name]; ok {
			if err := b.program.meter.Charge(1, 0, uint64(len(previous.Targets)+len(previous.External)+len(ref.Targets)+len(ref.External))*128+256); err != nil {
				failure = err
				return
			}
			ref.Targets = append(previous.Targets, ref.Targets...)
			ref.External = append(previous.External, ref.External...)
		}
		values[name] = ref
	}
	c.ForEachExportAndPropertyOfModule(module, add)
	if resolved := c.ResolveExternalModuleSymbol(module); resolved != module {
		add(resolved, "export=")
	}
	if failure != nil {
		return failure
	}
	if err := b.program.meter.Charge(uint64(len(values))*(uint64(bits.Len(uint(len(values))))+1), 0, 0); err != nil {
		return err
	}
	if err := b.program.meter.Charge(1, 1, uint64(len(values))*32+128); err != nil {
		return err
	}
	names := make([]string, 0, len(values))
	for name := range values {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		ref := values[name]
		if err := b.program.meter.Charge(uint64(len(ref.Targets)+len(ref.External))*(uint64(bits.Len(uint(len(ref.Targets)+len(ref.External))))+1), 0, 0); err != nil {
			return err
		}
		sort.Strings(ref.Targets)
		ref.Targets = slices.Compact(ref.Targets)
		sort.Strings(ref.External)
		ref.External = slices.Compact(ref.External)
		if len(ref.Targets)+len(ref.External) > 0 {
			ref.Resolution = "symbol"
		}
		b.facts.References = append(b.facts.References, ref)
	}
	return nil
}
