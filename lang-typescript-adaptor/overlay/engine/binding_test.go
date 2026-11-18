package engine

import (
	"context"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/scanner"
	"strings"
)

// Detached binding evidence. No AST/Checker/Type pointer reaches the caller.
// Graph projection will consume this callback without retaining all call facts.
type CallBinding struct {
	File                   string
	Start, End             uint64
	Resolved               bool
	TargetFile, TargetName string
	TargetStart, TargetEnd uint64
}

func (p *Program) WalkCallBindings(ctx context.Context, emit func(CallBinding) error) error {
	for _, name := range p.config.ParsedConfig.FileNames {
		if err := p.meter.Check(); err != nil {
			return err
		}
		identity := p.files[p.frozen.resolve(name)]
		if identity == nil {
			return invalid("compiler root has no frozen identity")
		}
		if identity.Role != pb.FileRole_PUBLISH {
			continue
		}
		file := p.program.GetSourceFile(name)
		if file == nil {
			return invalid("compiler root source unavailable")
		}
		checker, done := p.program.GetTypeCheckerForFile(ctx, file)
		var failure error
		func() {
			defer done()
			depth := uint32(0)
			var walk func(*ast.Node) bool
			walk = func(node *ast.Node) bool {
				depth++
				defer func() { depth-- }()
				if failure = p.meter.Depth(depth); failure != nil {
					return true
				}
				if failure = p.meter.Charge(1, 1, 0); failure != nil {
					return true
				}
				if node.Kind == ast.KindCallExpression {
					start, end := scanner.SkipTrivia(file.Text(), node.Pos()), node.End()
					if start < 0 || end < start || end > len(file.Text()) {
						failure = invalid("invalid call source range")
						return true
					}
					binding := CallBinding{File: identity.Path, Start: uint64(start), End: uint64(end)}
					signature := checker.GetResolvedSignature(node)
					symbol := checker.GetSymbolAtLocation(node.AsCallExpression().Expression)
					if signature != nil && signature.Declaration() != nil && symbol != nil {
						declaration := signature.Declaration()
						if target := declaration.Name(); target != nil {
							source := ast.GetSourceFileOfNode(target)
							if source != nil {
								begin, end := scanner.SkipTrivia(source.Text(), target.Pos()), target.End()
								if begin >= 0 && end >= begin && end <= len(source.Text()) {
									binding.Resolved = true
									binding.TargetFile = source.FileName()
									if !strings.HasPrefix(source.FileName(), bundled.LibPath()+"/") {
										if targetIdentity := p.files[p.frozen.resolve(source.FileName())]; targetIdentity != nil {
											binding.TargetFile = targetIdentity.Path
										}
									}
									binding.TargetName = source.Text()[begin:end]
									binding.TargetStart = uint64(begin)
									binding.TargetEnd = uint64(end)
								}
							}
						}
					}
					if failure = emit(binding); failure != nil {
						return true
					}
				}
				return node.ForEachChild(walk)
			}
			file.ForEachChild(walk)
		}()
		if failure != nil {
			return failure
		}
		if err := p.frozen.Check(); err != nil {
			return err
		}
		if err := p.meter.Check(); err != nil {
			return err
		}
	}
	return nil
}
