package engine

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/compiler"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/tsoptions"
	"google.golang.org/protobuf/proto"
)

func sourceLanguage(name string) pb.SourceLanguage {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".js", ".jsx", ".mjs", ".cjs":
		return pb.SourceLanguage_JAVASCRIPT
	case ".ts", ".tsx", ".mts", ".cts":
		return pb.SourceLanguage_TYPESCRIPT
	default:
		return pb.SourceLanguage_SOURCE_LANGUAGE_UNSPECIFIED
	}
}

// DiscoverProject invokes the same pinned compiler as analysis, with no emit,
// plugin, content mapper, package manager, target script or global type cache.
func DiscoverProject(ctx context.Context, request *pb.Prepare, meter *Meter) (*pb.FrozenProject, error) {
	observed, err := NewObservedFS(request, meter)
	if err != nil {
		return nil, err
	}
	if !bundled.Embedded {
		return nil, &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "embedded standard library required"}
	}
	filesystem := bundled.WrapFS(observed)
	config, diagnostics := tsoptions.GetParsedCommandLineOfConfigFile(request.ConfigPath, nil, nil, configHost{filesystem: filesystem, root: request.ProjectRoot}, nil)
	if err := observed.Check(); err != nil {
		return nil, err
	}
	if config == nil || len(diagnostics) != 0 || len(config.Errors) != 0 || config.ParsedConfig == nil || config.ParsedConfig.CompilerOptions == nil {
		return nil, invalid("invalid preparation compiler configuration")
	}
	if len(config.ParsedConfig.ContentMappers) != 0 {
		return nil, &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "configuration content mappers are disabled"}
	}
	if len(config.ParsedConfig.FileNames) == 0 {
		return nil, invalid("preparation compiler context has no root files")
	}
	if err := meter.Charge(uint64(len(config.ParsedConfig.FileNames))+1, 1, uint64(len(config.ParsedConfig.FileNames))*256+4*1024*1024); err != nil {
		return nil, err
	}
	if config.ParsedConfig.CompilerOptions.NoLib != core.TSTrue {
		var bytes uint64
		for _, name := range bundled.LibNames {
			text, ok := filesystem.ReadFile(bundled.LibPath() + "/" + name)
			if !ok {
				return nil, invalid("missing embedded library")
			}
			bytes += uint64(len(text))
		}
		if err := meter.Charge(uint64(len(bundled.LibNames))+bytes/4096, 1, bytes*32); err != nil {
			return nil, err
		}
	}
	program := compiler.NewProgram(compiler.ProgramOptions{Config: config, Host: compiler.NewCompilerHost(request.ProjectRoot, filesystem, bundled.LibPath(), nil, nil, nil), SingleThreaded: core.TSTrue})
	if err := observed.Check(); err != nil {
		return nil, err
	}
	dependencies, err := referenceInputs(program, meter, observed.key)
	if err != nil {
		return nil, err
	}
	// Bind/check now so lazy module/package reads are part of the inventory.
	for _, source := range program.SourceFiles() {
		if err := meter.Check(); err != nil {
			return nil, err
		}
		if strings.HasPrefix(source.FileName(), bundled.LibPath()+"/") {
			continue
		}
		program.GetSemanticDiagnostics(ctx, source)
		if err := observed.Check(); err != nil {
			return nil, err
		}
	}
	if err := meter.Charge(uint64(len(config.ProjectReferences()))+1, 1, uint64(len(config.ProjectReferences()))*512+128); err != nil {
		return nil, err
	}
	observed.context.RootFiles = append([]string(nil), config.ParsedConfig.FileNames...)
	observed.context.References = append([]string(nil), config.ResolvedProjectReferencePaths()...)
	project, err := observed.Project()
	if err != nil {
		return nil, err
	}
	enabled := make(map[pb.SourceLanguage]bool)
	for _, language := range request.PublishLanguages {
		enabled[language] = true
	}
	sourceFiles := make(map[string]bool)
	for _, source := range program.SourceFiles() {
		sourceFiles[observed.key(source.FileName())] = true
	}
	for _, file := range project.Files {
		identity := file.File
		language := sourceLanguage(identity.Path)
		if (sourceFiles[observed.key(identity.Path)] || dependencies[observed.key(identity.Path)]) && language != pb.SourceLanguage_SOURCE_LANGUAGE_UNSPECIFIED {
			identity.Language = language
			identity.Role = pb.FileRole_AUXILIARY
			// Dependencies and disabled source languages remain auxiliary.
			relative := strings.TrimPrefix(observed.key(identity.Path), observed.key(request.ProjectRoot)+"/")
			dependency := dependencies[observed.key(identity.Path)] || strings.HasPrefix(relative, "node_modules/") || strings.Contains(relative, "/node_modules/")
			if enabled[language] && beneath(observed.key(identity.Path), observed.key(request.ProjectRoot)) && !dependency {
				identity.Role = pb.FileRole_PUBLISH
				identity.Module = request.Module
				identity.Scope = request.Scope
				identity.World = request.World
			}
		}
	}
	// Fail before writing an inventory if observations contradict one another.
	if _, err := NewFrozenFS(project, meter); err != nil {
		return nil, err
	}
	return project, nil
}

// sealInventory is a private protobuf file, not a graph format or graph IO
// implementation. The bounded result contains only its raw-byte descriptor.
func sealInventory(request *pb.Prepare, project *pb.FrozenProject, meter *Meter) (_ *pb.Archive, failure error) {
	inventory := &pb.PreparationInventory{Context: project.Context}
	for _, file := range project.Files {
		inventory.Files = append(inventory.Files, file.File)
	}
	size := uint64(proto.Size(inventory))
	if !filepath.IsAbs(request.Scratch) || size == 0 || size > request.InventoryBytes || size > request.ScratchBytes {
		return nil, &Fault{Code: pb.FailureCode_OUTPUT_LIMIT, Message: "preparation inventory/scratch limit"}
	}
	if err := meter.Charge(uint64(len(inventory.Files))+1, uint64(len(inventory.Files))+1, size*2+8192); err != nil {
		return nil, err
	}
	data, err := proto.MarshalOptions{Deterministic: true}.Marshal(inventory)
	if err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(request.Scratch)
	if err != nil {
		return nil, err
	}
	defer func() { failure = errors.Join(failure, root.Close()) }()
	directory, err := root.Open(".")
	if err != nil {
		return nil, err
	}
	entries, readErr := directory.ReadDir(1)
	closeErr := directory.Close()
	if readErr != nil && !errors.Is(readErr, io.EOF) {
		return nil, errors.Join(readErr, closeErr)
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if len(entries) != 0 {
		return nil, invalid("preparation scratch must be empty and private")
	}
	file, err := root.OpenFile("inventory.pb", os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return nil, err
	}
	written, writeErr := file.Write(data)
	if writeErr == nil && written != len(data) {
		writeErr = io.ErrShortWrite
	}
	if writeErr == nil {
		writeErr = file.Sync()
	}
	if err := errors.Join(writeErr, file.Close()); err != nil {
		return nil, err
	}
	digest := sha256.Sum256(data)
	return &pb.Archive{Path: "inventory.pb", Bytes: uint64(len(data)), Count: uint64(len(inventory.Files)), Sha256: hex.EncodeToString(digest[:])}, nil
}

func prepareInventory(ctx context.Context, request *pb.Prepare, meter *Meter) (*pb.Archive, error) {
	meter.phase = "discover"
	project, err := DiscoverProject(ctx, request, meter)
	if err != nil {
		return nil, err
	}
	meter.phase = "archive"
	return sealInventory(request, project, meter)
}
