// Diagnostic ABI only. This is not the production TypeScript worker contract.
package main

/*
#include <stdint.h>
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf16"
	"unicode/utf8"
	"unsafe"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/compiler"
	"github.com/microsoft/typescript-go/internal/core"
	"github.com/microsoft/typescript-go/internal/scanner"
	"github.com/microsoft/typescript-go/internal/tsoptions"
	"github.com/microsoft/typescript-go/internal/vfs"
	"github.com/microsoft/typescript-go/internal/vfs/vfstest"
)

const limit = 1 << 20

var results = struct {
	sync.Mutex
	next uint64
	data map[uint64][]byte
}{data: make(map[uint64][]byte)}

type request struct {
	Files map[string]string `json:"files"`
}
type position struct {
	Offset int `json:"offset"`
	Line   int `json:"line"`
	Column int `json:"column"`
}
type location struct {
	File  string   `json:"file"`
	Start position `json:"start"`
	End   position `json:"end"`
}
type declaration struct {
	Name     string   `json:"name"`
	Location location `json:"location"`
}
type call struct {
	Location  location    `json:"location"`
	Symbol    string      `json:"symbol"`
	Signature string      `json:"signature"`
	Target    declaration `json:"target"`
}
type response struct {
	Declarations []declaration `json:"declarations"`
	Calls        []call        `json:"calls"`
}

// Override every mutating FS method: the compiler sees captured bytes only.
type readonly struct{ vfs.FS }

func (readonly) WriteFile(string, string) error             { return errors.New("read-only capture") }
func (readonly) AppendFile(string, string) error            { return errors.New("read-only capture") }
func (readonly) Remove(string) error                        { return errors.New("read-only capture") }
func (readonly) Chtimes(string, time.Time, time.Time) error { return errors.New("read-only capture") }

func point(text string, offset int) position {
	p := position{Offset: offset, Line: 1, Column: 1}
	for _, r := range text[:offset] {
		if r == '\n' {
			p.Line++
			p.Column = 1
		} else {
			p.Column += utf16.RuneLen(r)
		}
	}
	return p
}
func locate(n *ast.Node) location {
	f := ast.GetSourceFileOfNode(n)
	start := scanner.SkipTrivia(f.Text(), n.Pos())
	return location{File: f.FileName(), Start: point(f.Text(), start), End: point(f.Text(), n.End())}
}
func decl(n *ast.Node) declaration {
	name := n.Name()
	if name == nil {
		panic("probe expected named declaration")
	}
	f := ast.GetSourceFileOfNode(name)
	l := locate(name)
	return declaration{Name: f.Text()[l.Start.Offset:l.End.Offset], Location: l}
}
func analyze(data []byte) ([]byte, error) {
	if !utf8.Valid(data) {
		return nil, errors.New("invalid UTF-8")
	}
	var input request
	if err := json.Unmarshal(data, &input); err != nil {
		return nil, err
	}
	if len(input.Files) == 0 || len(input.Files) > 16 {
		return nil, errors.New("file count")
	}
	paths := make([]string, 0, len(input.Files))
	for path, text := range input.Files {
		if !strings.HasPrefix(path, "/src/") || strings.Contains(path, "..") || !strings.HasSuffix(path, ".ts") || !utf8.ValidString(text) {
			return nil, errors.New("invalid input")
		}
		paths = append(paths, path)
	}
	sort.Strings(paths)
	fs := readonly{bundled.WrapFS(vfstest.FromMap(input.Files, true))}
	config := &tsoptions.ParsedCommandLine{ParsedConfig: &tsoptions.ParsedOptions{
		FileNames: paths, CompilerOptions: &core.CompilerOptions{SkipLibCheck: core.TSTrue, Module: core.ModuleKindESNext, ModuleResolution: core.ModuleResolutionKindBundler},
	}}
	program := compiler.NewProgram(compiler.ProgramOptions{Config: config, Host: compiler.NewCompilerHost("/src", fs, bundled.LibPath(), nil, nil, nil), SingleThreaded: core.TSTrue})
	out := response{Declarations: []declaration{}, Calls: []call{}}
	for _, path := range paths {
		file := program.GetSourceFile(path)
		if file == nil {
			return nil, errors.New("missing source")
		}
		checker, done := program.GetTypeCheckerForFile(context.Background(), file)
		func() {
			defer done()
			var walk func(*ast.Node) bool
			visited, depth := 0, 0
			walk = func(n *ast.Node) bool {
				visited++
				depth++
				defer func() { depth-- }()
				if visited > 20000 || depth > 128 {
					panic("probe traversal limit")
				}
				if n.Kind == ast.KindFunctionDeclaration && n.Name() != nil {
					out.Declarations = append(out.Declarations, decl(n))
				}
				if n.Kind == ast.KindCallExpression {
					sig := checker.GetResolvedSignature(n)
					symbol := checker.GetSymbolAtLocation(n.AsCallExpression().Expression)
					if sig == nil || sig.Declaration() == nil || symbol == nil {
						panic("probe binding unavailable")
					}
					out.Calls = append(out.Calls, call{Location: locate(n), Symbol: symbol.Name, Signature: checker.SignatureToStringEx(sig, n, 0, nil), Target: decl(sig.Declaration())})
				}
				n.ForEachChild(walk)
				return false
			}
			file.ForEachChild(walk)
		}()
	}
	encoded, err := json.Marshal(out)
	if len(encoded) > limit {
		return nil, errors.New("result limit")
	}
	return encoded, err
}

//export codeana_ts_probe_abi_version
func codeana_ts_probe_abi_version() C.uint32_t { return 1 }

//export codeana_ts_probe_analyze
func codeana_ts_probe_analyze(input *C.uint8_t, size C.uint64_t) (handle C.uint64_t) {
	defer func() {
		if recover() != nil {
			handle = 0
		}
	}()
	if input == nil || size == 0 || size > limit {
		return 0
	}
	// Copy before parsing; no Rust pointer survives this call.
	data := C.GoBytes(unsafe.Pointer(input), C.int(size))
	encoded, err := analyze(data)
	if err != nil {
		return 0
	}
	results.Lock()
	defer results.Unlock()
	if len(results.data) >= 16 || results.next == ^uint64(0) {
		return 0
	}
	results.next++
	results.data[results.next] = encoded
	return C.uint64_t(results.next)
}

//export codeana_ts_probe_read
func codeana_ts_probe_read(handle C.uint64_t, offset C.uint64_t, output *C.uint8_t, capacity C.uint64_t) (count C.int64_t) {
	defer func() {
		if recover() != nil {
			count = -1
		}
	}()
	results.Lock()
	defer results.Unlock()
	data, ok := results.data[uint64(handle)]
	if !ok || uint64(offset) > uint64(len(data)) || capacity > limit || capacity == 0 || output == nil {
		return -1
	}
	n := min(uint64(capacity), uint64(len(data))-uint64(offset))
	copy(unsafe.Slice((*byte)(unsafe.Pointer(output)), int(n)), data[int(offset):int(offset)+int(n)])
	return C.int64_t(n)
}

//export codeana_ts_probe_close
func codeana_ts_probe_close(handle C.uint64_t) (status C.int32_t) {
	defer func() {
		if recover() != nil {
			status = -1
		}
	}()
	results.Lock()
	defer results.Unlock()
	if _, ok := results.data[uint64(handle)]; !ok {
		return -1
	}
	delete(results.data, uint64(handle))
	return 0
}

func main() {}
