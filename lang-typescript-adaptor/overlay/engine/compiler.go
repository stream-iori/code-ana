package engine

import (
	"slices"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/compiler"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/tsoptions"
	"github.com/microsoft/typescript-go/internal/tspath"
	"github.com/microsoft/typescript-go/internal/vfs"
)

type configHost struct {
	filesystem vfs.FS
	root       string
}

func (h configHost) FS() vfs.FS                  { return h.filesystem }
func (h configHost) GetCurrentDirectory() string { return h.root }

// Compiler objects remain in Go and in the lifetime of this one task.
type Program struct {
	program         *compiler.Program
	config          *tsoptions.ParsedCommandLine
	frozen          *FrozenFS
	meter           *Meter
	files           map[string]*pb.FileIdentity
	context         *pb.ProjectContext
	publicationRoot string
}

func OpenProgram(project *pb.FrozenProject, meter *Meter) (*Program, error) {
	frozen, err := NewFrozenFS(project, meter)
	if err != nil {
		return nil, err
	}
	if !bundled.Embedded {
		return nil, &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "embedded standard library required"}
	}
	filesystem := bundled.WrapFS(frozen)
	context := project.Context
	if !absolute(context.ConfigPath) {
		return nil, invalid("explicit captured compiler configuration required")
	}
	// Reserve configuration decoding before asking the kernel to parse JSON.
	text, ok := frozen.ReadFile(context.ConfigPath)
	if !ok {
		if err := frozen.Check(); err != nil {
			return nil, err
		}
		return nil, invalid("missing captured compiler configuration")
	}
	if err := meter.Charge(uint64(len(text))/64+1, uint64(len(text))/2+1, uint64(len(text))*32+4096); err != nil {
		return nil, err
	}
	config, diagnostics := tsoptions.GetParsedCommandLineOfConfigFile(context.ConfigPath, nil, nil, configHost{filesystem: filesystem, root: context.Root}, nil)
	if err := frozen.Check(); err != nil {
		return nil, err
	}
	if config == nil || len(diagnostics) != 0 || len(config.Errors) != 0 || config.ParsedConfig == nil || config.ParsedConfig.CompilerOptions == nil {
		return nil, invalid("invalid captured compiler configuration")
	}
	if len(config.ParsedConfig.ContentMappers) != 0 {
		return nil, &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "configuration content mappers are disabled"}
	}
	if !slices.Equal(context.References, config.ResolvedProjectReferencePaths()) {
		return nil, invalid("captured project reference order changed")
	}
	if len(config.ParsedConfig.FileNames) == 0 && len(config.ProjectReferences()) == 0 {
		return nil, invalid("compiler context has no root files")
	}
	if len(context.RootFiles) != len(config.ParsedConfig.FileNames) {
		return nil, invalid("captured root files changed")
	}
	for i, name := range config.ParsedConfig.FileNames {
		if context.RootFiles[i] != name {
			return nil, invalid("captured root file order changed")
		}
	}
	// Reserve a fixed compiler baseline, then fund only files actually requested
	// by the kernel. An immutable embedded library string is cheap to inspect;
	// parser/checker workspace must be admitted before returning it to the host.
	if err := meter.Charge(1, 1, 4*1024*1024); err != nil {
		return nil, err
	}
	filesystem = &compilerBudgetFS{FS: filesystem, meter: meter, seen: make(map[string]bool)}
	program := compiler.NewProgram(compiler.ProgramOptions{Config: config, Host: compiler.NewCompilerHost(context.Root, filesystem, bundled.LibPath(), nil, nil, nil), SingleThreaded: core.TSTrue})
	if err := frozen.Check(); err != nil {
		return nil, err
	}
	if err := meter.Check(); err != nil {
		return nil, err
	}
	dependencies, err := referenceInputs(program, meter, frozen.key)
	if err != nil {
		return nil, err
	}
	files := make(map[string]*pb.FileIdentity, len(project.Files))
	for _, file := range project.Files {
		if dependencies[frozen.key(file.File.Path)] && file.File.Role == pb.FileRole_PUBLISH {
			return nil, invalid("referenced project input cannot be published by the consumer")
		}
		files[frozen.resolve(file.File.Path)] = file.File
	}
	return &Program{program: program, config: config, frozen: frozen, meter: meter, files: files, context: context}, nil
}

// Compiler-owned reference mapping distinguishes consumer sources from another
// project's source/declaration output. Missing outputs remain negative lookups;
// no source redirect, target build or synthetic declaration is enabled.
func referenceInputs(program *compiler.Program, meter *Meter, canonical func(string) string) (map[string]bool, error) {
	result := make(map[string]bool)
	var failure error
	program.RangeResolvedProjectReference(func(_ tspath.Path, config *tsoptions.ParsedCommandLine, _ *tsoptions.ParsedCommandLine, _ int) bool {
		if failure = meter.Charge(1, 1, 512); failure != nil {
			return false
		}
		if config == nil {
			return true
		}
		if len(config.Errors) != 0 || config.ParsedConfig == nil || config.ParsedConfig.CompilerOptions == nil {
			failure = invalid("invalid referenced project configuration")
			return false
		}
		if len(config.ParsedConfig.ContentMappers) != 0 {
			failure = &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "referenced project content mappers are disabled"}
			return false
		}
		for _, mapping := range config.SourceToProjectReference() {
			if failure = meter.Charge(1, 1, uint64(len(mapping.Source)+len(mapping.OutputDts))*4+256); failure != nil {
				return false
			}
			result[canonical(mapping.Source)] = true
			if mapping.OutputDts != "" {
				result[canonical(mapping.OutputDts)] = true
			}
		}
		return true
	})
	return result, failure
}

// Embedding preserves the frozen/bundled filesystem's exact lookup semantics.
// A refusal latches in Meter; NewProgram may recover with a missing-file
// diagnostic, but OpenProgram then returns that resource failure, never facts.
type compilerBudgetFS struct {
	vfs.FS
	meter *Meter
	seen  map[string]bool
}

func (f *compilerBudgetFS) ReadFile(name string) (string, bool) {
	if f.meter.Check() != nil {
		return "", false
	}
	text, ok := f.FS.ReadFile(name)
	if !ok || f.seen[name] {
		return text, ok
	}
	n := uint64(len(text))
	if n > (^uint64(0)-4096)/32 {
		return "", false
	}
	if f.meter.Charge(n/4096+1, 1, n*32+4096) != nil {
		return "", false
	}
	f.seen[name] = true
	return text, true
}
