package engine

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"io/fs"
	"maps"
	"os"
	"path"
	"slices"
	"strings"
	"syscall"
	"time"
	"unicode/utf8"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/tspath"
	"github.com/microsoft/typescript-go/internal/vfs"
	"google.golang.org/protobuf/proto"
)

type spellingObservation struct {
	path string
	info fs.FileInfo
}

// ObservedFS is used only by explicit preparation. It records the actual
// compiler queries, not a recursive copy of node_modules. Analysis receives
// FrozenFS and can never fall back to this live view.
type ObservedFS struct {
	roots                   []string
	meter                   *Meter
	context                 *pb.ProjectContext
	files                   map[string]*pb.FrozenFile
	directories             map[string]*pb.Directory
	links                   map[string]*pb.SymbolicLink
	absent                  map[string]*pb.AbsentLookup
	spellings               map[string]spellingObservation
	physicalNames           map[string]string
	total, limit, fileLimit uint64
	failure                 error
}

func NewObservedFS(request *pb.Prepare, meter *Meter) (*ObservedFS, error) {
	if request == nil || !absolute(request.ProjectRoot) || !absolute(request.ConfigPath) ||
		request.Unit == "" || request.CaseSensitive == nil || request.Module == "" || request.World == "" ||
		(request.Scope != "MAIN" && request.Scope != "TEST") || request.FileLimit == 0 || request.FileLimit > 100000 ||
		request.InventoryBytes == 0 || request.InventoryBytes > MaxRequestBytes || len(request.AllowedRoots) == 0 {
		return nil, invalid("explicit preparation roots, context, case rules and limits required")
	}
	languages := make(map[pb.SourceLanguage]bool)
	for _, language := range request.PublishLanguages {
		if (language != pb.SourceLanguage_JAVASCRIPT && language != pb.SourceLanguage_TYPESCRIPT) || languages[language] {
			return nil, invalid("invalid preparation publication languages")
		}
		languages[language] = true
	}
	if len(languages) == 0 {
		return nil, invalid("explicit preparation publication languages required")
	}
	roots := slices.Clone(request.AllowedRoots)
	for _, root := range roots {
		if !absolute(root) {
			return nil, invalid("preparation root must be absolute")
		}
	}
	if err := meter.Charge(uint64(len(roots))+1, 1, uint64(len(roots))*512+1024); err != nil {
		return nil, err
	}
	result := &ObservedFS{roots: roots, meter: meter, limit: request.InventoryBytes, fileLimit: request.FileLimit,
		context: &pb.ProjectContext{Unit: request.Unit, Root: request.ProjectRoot, ConfigPath: request.ConfigPath, PublishLanguages: slices.Clone(request.PublishLanguages), CaseSensitive: boolValue(*request.CaseSensitive)},
		files:   make(map[string]*pb.FrozenFile), directories: make(map[string]*pb.Directory), links: make(map[string]*pb.SymbolicLink), absent: make(map[string]*pb.AbsentLookup), spellings: make(map[string]spellingObservation), physicalNames: make(map[string]string)}
	if !result.allowed(request.ProjectRoot) || !result.allowed(request.ConfigPath) {
		return nil, invalid("project/configuration is outside preparation roots")
	}
	return result, nil
}

func boolValue(value bool) *bool { return &value }
func (f *ObservedFS) key(name string) string {
	return tspath.GetCanonicalFileName(name, *f.context.CaseSensitive)
}
func (f *ObservedFS) allowed(name string) bool {
	for _, root := range f.roots {
		if beneath(f.key(name), f.key(root)) {
			return true
		}
	}
	return false
}
func (f *ObservedFS) fail(err error) {
	if f.failure == nil {
		f.failure = err
	}
}
func (f *ObservedFS) Check() error {
	if f.failure != nil {
		return f.failure
	}
	return f.meter.Check()
}
func (f *ObservedFS) reserve(bytes uint64) bool {
	if f.failure != nil {
		return false
	}
	if bytes > f.limit || f.total > f.limit-bytes {
		f.fail(&Fault{Code: pb.FailureCode_INPUT_LIMIT, Message: "preparation observation byte limit"})
		return false
	}
	if err := f.meter.Charge(1, 1, bytes); err != nil {
		f.fail(err)
		return false
	}
	f.total += bytes
	return true
}

// Ancestor package/type searches may witness actual absence, but never grant
// permission to read a positive global package or enumerate a global cache.
func (f *ObservedFS) ancestor(name string) bool {
	for _, root := range f.roots {
		if beneath(f.key(root), f.key(name)) {
			return true
		}
	}
	return false
}
func (f *ObservedFS) metadataProbe(name string) bool {
	if f.ancestor(name) {
		return true
	}
	for _, root := range f.roots {
		for parent := root; ; parent = path.Dir(parent) {
			if f.key(name) == f.key(path.Join(parent, "package.json")) || beneath(f.key(name), f.key(path.Join(parent, "node_modules"))) {
				return true
			}
			if parent == "/" {
				break
			}
		}
	}
	return false
}

// Lstat each traversed component and record links before resolving them. The
// eventual content open is additionally confined by os.Root against path races.
func (f *ObservedFS) resolve(name string) string {
	if f.Check() != nil {
		return ""
	}
	if beneath(f.key(path.Clean(name)), f.key(path.Join(f.context.Root, ".code-ana"))) {
		f.fail(&Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "indexer-owned state is not a compiler input"})
		return ""
	}
	if !absolute(name) || (!f.allowed(name) && !f.metadataProbe(name)) {
		f.fail(&Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "compiler lookup outside explicit preparation roots"})
		return ""
	}
	name = path.Clean(name)
	for hop := 0; hop < 32; hop++ {
		changed := false
		prefix := "/"
		if !f.reserve(uint64(len(name))*2 + 128) {
			return ""
		}
		for _, part := range strings.Split(strings.TrimPrefix(name, "/"), "/") {
			prefix = path.Join(prefix, part)
			if err := f.meter.Charge(1, 0, 0); err != nil {
				f.fail(err)
				return ""
			}
			info, err := os.Lstat(prefix)
			if errors.Is(err, fs.ErrNotExist) || errors.Is(err, syscall.ENOTDIR) {
				return name
			}
			if err != nil {
				f.fail(err)
				return ""
			}
			if !*f.context.CaseSensitive && f.allowed(prefix) {
				query := prefix
				key := f.key(query)
				observed, known := f.spellings[query]
				spelling := observed.path
				if known && !os.SameFile(info, observed.info) {
					f.fail(&Fault{Code: pb.FailureCode_INPUT_STALE, Message: "physical path changed during preparation"})
					return ""
				}
				if !known {
					if !info.Mode().IsRegular() && !info.IsDir() && info.Mode()&fs.ModeSymlink == 0 {
						f.fail(invalid("preparation requires regular files/directories/links"))
						return ""
					}
					if !f.reserve(uint64(len(query))*2 + 2304) {
						return ""
					}
					var err error
					spelling, err = physicalSpelling(query)
					if err != nil {
						f.fail(err)
						return ""
					}
					if !absolute(spelling) || f.key(spelling) != key {
						f.fail(&Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "filesystem spelling differs from compiler canonical path rules"})
						return ""
					}
					if previous, exists := f.physicalNames[key]; exists && previous != spelling {
						f.fail(&Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "filesystem case aliases name distinct directory entries"})
						return ""
					}
					f.physicalNames[key] = spelling
					f.spellings[query] = spellingObservation{path: spelling, info: info}
				}
				name = spelling + strings.TrimPrefix(name, query)
				prefix = spelling
			}
			if info.Mode()&fs.ModeSymlink == 0 {
				continue
			}
			target, err := os.Readlink(prefix)
			if err != nil {
				f.fail(err)
				return ""
			}
			resolved := target
			if !path.IsAbs(resolved) {
				resolved = path.Join(path.Dir(prefix), target)
			}
			resolved = path.Clean(resolved)
			allowedRoot := ""
			for _, root := range f.roots {
				if beneath(f.key(resolved), f.key(root)) && len(root) > len(allowedRoot) {
					allowedRoot = root
				}
			}
			if allowedRoot == "" {
				f.fail(&Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "preparation link escapes explicit roots"})
				return ""
			}
			key := f.key(prefix)
			if old := f.links[key]; old != nil && (old.Target != target || old.AllowedRoot != allowedRoot) {
				f.fail(&Fault{Code: pb.FailureCode_INPUT_STALE, Message: "symbolic link changed during preparation"})
				return ""
			}
			if f.links[key] == nil {
				if !f.reserve(uint64(len(prefix)+len(target)+len(allowedRoot)) + 256) {
					return ""
				}
				f.links[key] = &pb.SymbolicLink{Path: prefix, Target: target, AllowedRoot: allowedRoot}
			}
			name = path.Clean(resolved + strings.TrimPrefix(name, prefix))
			if !f.allowed(name) {
				f.fail(invalid("resolved preparation path escaped roots"))
				return ""
			}
			changed = true
			break
		}
		if !changed {
			return name
		}
	}
	f.fail(invalid("preparation link cycle or hop limit"))
	return ""
}

func (f *ObservedFS) missing(name string, kind pb.LookupKind) {
	key := f.key(name) + string(rune(kind))
	if f.absent[key] == nil && f.reserve(uint64(len(name))+128) {
		f.absent[key] = &pb.AbsentLookup{Path: name, Kind: kind}
	}
}
func (f *ObservedFS) stat(name string, kind pb.LookupKind) (string, fs.FileInfo) {
	name = f.resolve(name)
	if name == "" {
		return "", nil
	}
	info, err := os.Stat(name)
	if errors.Is(err, fs.ErrNotExist) || errors.Is(err, syscall.ENOTDIR) {
		f.missing(name, kind)
		return name, nil
	}
	if err != nil {
		f.fail(err)
		return name, nil
	}
	if !f.allowed(name) && !(info.IsDir() && f.ancestor(name)) {
		f.fail(&Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "positive package/type input outside explicit preparation roots"})
		return name, nil
	}
	if !info.IsDir() && !info.Mode().IsRegular() {
		f.fail(invalid("preparation requires regular files/directories"))
		return name, nil
	}
	if info.IsDir() {
		if f.directories[f.key(name)] == nil && f.reserve(uint64(len(name))+128) {
			f.directories[f.key(name)] = &pb.Directory{Path: name}
		}
	}
	return name, info
}
func (f *ObservedFS) UseCaseSensitiveFileNames() bool { return *f.context.CaseSensitive }
func (f *ObservedFS) FileExists(name string) bool {
	name, info := f.stat(name, pb.LookupKind_FILE)
	if info == nil || info.IsDir() {
		if info != nil {
			f.missing(name, pb.LookupKind_FILE)
		}
		return false
	}
	_, ok := f.ReadFile(name)
	return ok
}
func (f *ObservedFS) DirectoryExists(name string) bool {
	name, info := f.stat(name, pb.LookupKind_DIRECTORY)
	if info == nil || !info.IsDir() {
		if info != nil {
			f.missing(name, pb.LookupKind_DIRECTORY)
		}
		return false
	}
	return f.Check() == nil
}
func (f *ObservedFS) ReadFile(name string) (string, bool) {
	resolved, info := f.stat(name, pb.LookupKind_FILE)
	if info == nil || info.IsDir() {
		if info != nil {
			f.missing(resolved, pb.LookupKind_FILE)
		}
		return "", false
	}
	key := f.key(resolved)
	if old := f.files[key]; old != nil {
		return string(old.Content), true
	}
	if uint64(len(f.files)) >= f.fileLimit || info.Size() < 0 {
		f.fail(&Fault{Code: pb.FailureCode_INPUT_LIMIT, Message: "preparation file count limit"})
		return "", false
	}
	size := uint64(info.Size())
	// Fund text, protobuf/identity bookkeeping and parser/checker workspace
	// before any unbounded read or compiler source allocation.
	if size > f.limit {
		f.fail(&Fault{Code: pb.FailureCode_INPUT_LIMIT, Message: "preparation file byte limit"})
		return "", false
	}
	if !f.reserve(size + uint64(len(resolved))*4 + 1024) {
		return "", false
	}
	if err := f.meter.Charge(size/64+1, size/2+1, size*35); err != nil {
		f.fail(err)
		return "", false
	}
	rootName := ""
	for _, root := range f.roots {
		if beneath(f.key(resolved), f.key(root)) && len(root) > len(rootName) {
			rootName = root
		}
	}
	root, err := os.OpenRoot(rootName)
	if err != nil {
		f.fail(err)
		return "", false
	}
	relative := strings.TrimPrefix(strings.TrimPrefix(resolved, strings.TrimSuffix(rootName, "/")), "/")
	file, err := root.Open(relative)
	if err != nil {
		f.fail(errors.Join(err, root.Close()))
		return "", false
	}
	data, readErr := io.ReadAll(io.LimitReader(file, int64(size)+1))
	closeErr := errors.Join(file.Close(), root.Close())
	if readErr != nil || closeErr != nil {
		f.fail(errors.Join(readErr, closeErr))
		return "", false
	}
	if uint64(len(data)) != size || !utf8.Valid(data) {
		f.fail(&Fault{Code: pb.FailureCode_INPUT_STALE, Message: "preparation file changed size or is not UTF-8"})
		return "", false
	}
	digest := sha256.Sum256(data)
	f.files[key] = &pb.FrozenFile{File: &pb.FileIdentity{Path: resolved, Sha256: hex.EncodeToString(digest[:]), Size: size, Role: pb.FileRole_CONTROL}, Content: data}
	return string(data), true
}
func (f *ObservedFS) GetAccessibleEntries(name string) vfs.Entries {
	if !f.allowed(name) {
		f.fail(&Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "directory enumeration outside explicit preparation roots"})
		return vfs.Entries{}
	}
	name, info := f.stat(name, pb.LookupKind_DIRECTORY)
	if info == nil || !info.IsDir() {
		return vfs.Entries{}
	}
	if old := f.directories[f.key(name)]; old != nil && old.ListingComplete {
		return f.entries(old)
	}
	if !f.reserve(8192) {
		return vfs.Entries{}
	}
	root, err := os.OpenRoot(name)
	if err != nil {
		f.fail(err)
		return vfs.Entries{}
	}
	file, err := root.Open(".")
	if err != nil {
		f.fail(errors.Join(err, root.Close()))
		return vfs.Entries{}
	}
	directory := &pb.Directory{Path: name, ListingComplete: true}
	for {
		if !f.reserve(128 * 512) {
			break
		}
		batch, err := file.ReadDir(128)
		if err != nil && !errors.Is(err, io.EOF) {
			f.fail(err)
			break
		}
		for _, entry := range batch {
			if f.key(path.Join(name, entry.Name())) == f.key(path.Join(f.context.Root, ".code-ana")) {
				continue
			}
			if !f.reserve(uint64(len(entry.Name())) + 128) {
				break
			}
			isDirectory := entry.IsDir()
			if entry.Type()&fs.ModeSymlink != 0 {
				_, target := f.stat(path.Join(name, entry.Name()), pb.LookupKind_FILE)
				if target == nil {
					if f.Check() == nil {
						f.fail(invalid("dangling preparation link cannot be replayed"))
					}
					break
				}
				isDirectory = target.IsDir()
			} else if !entry.Type().IsRegular() && !entry.IsDir() {
				f.fail(invalid("unsupported preparation directory entry"))
				break
			}
			if isDirectory {
				directory.Directories = append(directory.Directories, entry.Name())
			} else {
				directory.Files = append(directory.Files, entry.Name())
			}
		}
		if err != nil || f.Check() != nil {
			break
		}
	}
	f.fail(errors.Join(file.Close(), root.Close()))
	if f.Check() != nil {
		return vfs.Entries{}
	}
	slices.Sort(directory.Files)
	slices.Sort(directory.Directories)
	f.directories[f.key(name)] = directory
	return f.entries(directory)
}
func (f *ObservedFS) entries(directory *pb.Directory) vfs.Entries {
	if !f.reserve(uint64(len(directory.Files)+len(directory.Directories))*64 + 128) {
		return vfs.Entries{}
	}
	links := make(map[string]struct{})
	for _, names := range [][]string{directory.Files, directory.Directories} {
		for _, name := range names {
			if f.links[f.key(path.Join(directory.Path, name))] != nil {
				links[name] = struct{}{}
			}
		}
	}
	return vfs.Entries{Files: slices.Clone(directory.Files), Directories: slices.Clone(directory.Directories), Symlinks: links}
}
func (f *ObservedFS) Realpath(name string) string { return f.resolve(name) }
func (f *ObservedFS) Stat(name string) vfs.FileInfo {
	name, info := f.stat(name, pb.LookupKind_FILE)
	if info == nil {
		return nil
	}
	if !info.IsDir() {
		if _, ok := f.ReadFile(name); !ok {
			return nil
		}
	}
	return info
}
func (f *ObservedFS) WalkDir(root string, callback vfs.WalkDirFunc) error {
	// All reads go through the recorder. Reuse the sealed walker only after
	// explicitly enumerating; direct OS traversal would bypass the inventory.
	err := &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "live preparation WalkDir is unsupported; compiler must use recorded enumeration"}
	f.fail(err)
	return err
}
func (f *ObservedFS) denyWrite() error {
	f.fail(invalid("compiler attempted to mutate live preparation input"))
	return fs.ErrPermission
}
func (f *ObservedFS) WriteFile(string, string) error             { return f.denyWrite() }
func (f *ObservedFS) AppendFile(string, string) error            { return f.denyWrite() }
func (f *ObservedFS) Remove(string) error                        { return f.denyWrite() }
func (f *ObservedFS) Chtimes(string, time.Time, time.Time) error { return f.denyWrite() }

func (f *ObservedFS) Project() (*pb.FrozenProject, error) {
	if err := f.Check(); err != nil {
		return nil, err
	}
	// Deterministic observation order; request generation/scratch is absent.
	if !f.reserve(uint64(len(f.files)+len(f.directories)+len(f.links)+len(f.absent))*128 + 1024) {
		return nil, f.Check()
	}
	context := proto.Clone(f.context).(*pb.ProjectContext)
	context.Directories = nil
	context.Links = nil
	context.Absent = nil
	for _, key := range slices.Sorted(maps.Keys(f.directories)) {
		context.Directories = append(context.Directories, f.directories[key])
	}
	for _, key := range slices.Sorted(maps.Keys(f.links)) {
		context.Links = append(context.Links, f.links[key])
	}
	for _, key := range slices.Sorted(maps.Keys(f.absent)) {
		context.Absent = append(context.Absent, f.absent[key])
	}
	project := &pb.FrozenProject{Context: context}
	for _, key := range slices.Sorted(maps.Keys(f.files)) {
		project.Files = append(project.Files, f.files[key])
	}
	return project, nil
}

var _ vfs.FS = (*ObservedFS)(nil)
