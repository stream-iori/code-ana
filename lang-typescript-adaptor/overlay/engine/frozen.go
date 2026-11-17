package engine

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io/fs"
	"maps"
	"path"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/tspath"
	"github.com/microsoft/typescript-go/internal/vfs"
)

// FrozenFS has no backing OS filesystem. A missing observation fails the
// computation instead of silently substituting an empty live directory.
type FrozenFS struct {
	files         map[string]string
	directories   map[string]*pb.Directory
	links         map[string]*pb.SymbolicLink
	absent        map[string]uint8
	meter         *Meter
	failure       error
	caseSensitive bool
	spellings     map[string]string
	entries       map[string]map[string]uint8
}

func absolute(name string) bool {
	return name != "" && len(name) <= 4096 && utf8.ValidString(name) && strings.HasPrefix(name, "/") && !strings.ContainsRune(name, 0) && path.Clean(name) == name
}

func beneath(name, root string) bool {
	return name == root || strings.HasPrefix(name, strings.TrimSuffix(root, "/")+"/")
}

func NewFrozenFS(project *pb.FrozenProject, meter *Meter) (*FrozenFS, error) {
	if project == nil || project.Context == nil || project.Context.Unit == "" || project.Context.CaseSensitive == nil || !absolute(project.Context.Root) {
		return nil, invalid("frozen project context required")
	}
	context := project.Context
	languages := make(map[pb.SourceLanguage]bool)
	for _, language := range context.PublishLanguages {
		if (language != pb.SourceLanguage_JAVASCRIPT && language != pb.SourceLanguage_TYPESCRIPT) || languages[language] {
			return nil, invalid("invalid or duplicate publish language")
		}
		languages[language] = true
	}
	if len(languages) == 0 {
		return nil, invalid("explicit publication languages required")
	}
	count := uint64(len(project.Files)) + uint64(len(context.Directories)) + uint64(len(context.Links)) + uint64(len(context.Absent))
	if err := meter.Charge(count+1, count, count*256+512); err != nil {
		return nil, err
	}
	frozen := &FrozenFS{files: make(map[string]string), directories: make(map[string]*pb.Directory), links: make(map[string]*pb.SymbolicLink), absent: make(map[string]uint8), meter: meter, caseSensitive: *context.CaseSensitive, spellings: make(map[string]string), entries: make(map[string]map[string]uint8)}
	for _, file := range project.Files {
		if file == nil || file.File == nil || !absolute(file.File.Path) || beneath(file.File.Path, bundled.LibPath()) || file.File.Size != uint64(len(file.Content)) || !utf8.Valid(file.Content) {
			return nil, invalid("invalid frozen file identity or text")
		}
		identity := file.File
		if identity.Role < pb.FileRole_PUBLISH || identity.Role > pb.FileRole_CONTROL ||
			(identity.Role != pb.FileRole_CONTROL && identity.Language != pb.SourceLanguage_TYPESCRIPT && identity.Language != pb.SourceLanguage_JAVASCRIPT) ||
			(identity.Role == pb.FileRole_PUBLISH && (!languages[identity.Language] || !beneath(frozen.key(identity.Path), frozen.key(context.Root)) || identity.Module == "" || identity.World == "" || (identity.Scope != "MAIN" && identity.Scope != "TEST"))) {
			return nil, invalid("invalid frozen source role or publication ownership")
		}
		if _, exists := frozen.files[frozen.key(file.File.Path)]; exists {
			return nil, invalid("duplicate frozen file")
		}
		if err := meter.Charge(1+uint64(len(file.Content))/4096, 0, uint64(len(file.Content))); err != nil {
			return nil, err
		}
		digest := sha256.Sum256(file.Content)
		if hex.EncodeToString(digest[:]) != file.File.Sha256 {
			return nil, invalid("frozen content digest mismatch")
		}
		frozen.files[frozen.key(file.File.Path)] = string(file.Content)
		frozen.spellings[frozen.key(file.File.Path)] = file.File.Path
	}
	for _, directory := range context.Directories {
		if directory == nil || !absolute(directory.Path) || frozen.directories[frozen.key(directory.Path)] != nil {
			return nil, invalid("invalid or duplicate frozen directory")
		}
		if _, file := frozen.files[frozen.key(directory.Path)]; file {
			return nil, invalid("frozen file is also a directory")
		}
		if !directory.ListingComplete && (len(directory.Files) != 0 || len(directory.Directories) != 0) {
			return nil, invalid("incomplete directory observation has entries")
		}
		seen := make(map[string]bool)
		for _, entries := range [][]string{directory.Files, directory.Directories} {
			if err := meter.Charge(uint64(len(entries)), 0, uint64(len(entries))*64); err != nil {
				return nil, err
			}
			for _, entry := range entries {
				if entry == "" || entry == "." || entry == ".." || strings.ContainsAny(entry, "/\x00") || !utf8.ValidString(entry) || seen[frozen.key(entry)] {
					return nil, invalid("invalid or conflicting directory entry")
				}
				seen[frozen.key(entry)] = true
			}
		}
		// Copy metadata: callers cannot alter the sealed view afterwards.
		copy := &pb.Directory{ListingComplete: directory.ListingComplete, Path: directory.Path, Files: slices.Clone(directory.Files), Directories: slices.Clone(directory.Directories)}
		frozen.directories[frozen.key(directory.Path)] = copy
		frozen.spellings[frozen.key(directory.Path)] = directory.Path
		index := make(map[string]uint8, len(seen))
		for _, entry := range directory.Files {
			index[frozen.key(entry)] = 1
		}
		for _, entry := range directory.Directories {
			index[frozen.key(entry)] = 2
		}
		if directory.ListingComplete {
			frozen.entries[frozen.key(directory.Path)] = index
		}
	}
	for _, link := range context.Links {
		if link == nil || !absolute(link.Path) || !absolute(link.AllowedRoot) || link.Target == "" || strings.ContainsRune(link.Target, 0) || frozen.links[frozen.key(link.Path)] != nil {
			return nil, invalid("invalid or duplicate frozen link")
		}
		target := link.Target
		if !strings.HasPrefix(target, "/") {
			target = path.Join(path.Dir(link.Path), target)
		}
		if !beneath(frozen.key(path.Clean(target)), frozen.key(link.AllowedRoot)) {
			return nil, invalid("frozen link escapes its declared root")
		}
		key := frozen.key(link.Path)
		if _, exists := frozen.files[key]; exists || frozen.directories[key] != nil {
			return nil, invalid("link conflicts with a concrete frozen entry")
		}
		frozen.links[key] = &pb.SymbolicLink{Path: link.Path, Target: link.Target, AllowedRoot: link.AllowedRoot}
		frozen.spellings[key] = link.Path
	}
	for _, absent := range context.Absent {
		if absent == nil || !absolute(absent.Path) || (absent.Kind != pb.LookupKind_FILE && absent.Kind != pb.LookupKind_DIRECTORY) {
			return nil, invalid("invalid missing lookup observation")
		}
		bit := uint8(1) << uint(absent.Kind)
		if frozen.absent[frozen.key(absent.Path)]&bit != 0 {
			return nil, invalid("duplicate missing lookup observation")
		}
		if _, exists := frozen.files[frozen.key(absent.Path)]; exists && absent.Kind == pb.LookupKind_FILE {
			return nil, invalid("positive file conflicts with missing lookup")
		}
		if frozen.directories[frozen.key(absent.Path)] != nil && absent.Kind == pb.LookupKind_DIRECTORY {
			return nil, invalid("positive directory conflicts with missing lookup")
		}
		frozen.absent[frozen.key(absent.Path)] |= bit
	}
	// Every complete parent listing must agree with positive observations.
	for _, name := range slices.Sorted(maps.Keys(frozen.spellings)) {
		if err := frozen.validateAncestors(name); err != nil {
			return nil, err
		}
	}
	for _, name := range slices.Sorted(maps.Keys(frozen.absent)) {
		bits := frozen.absent[name]
		if listing := frozen.entries[path.Dir(name)]; listing != nil {
			kind := listing[path.Base(name)]
			if (kind == 1 && bits&(1<<uint(pb.LookupKind_FILE)) != 0) ||
				(kind == 2 && bits&(1<<uint(pb.LookupKind_DIRECTORY)) != 0) {
				return nil, invalid("directory entry conflicts with missing lookup")
			}
		}
	}
	for _, name := range slices.Sorted(maps.Keys(frozen.links)) {
		frozen.resolve(name)
		if frozen.failure != nil {
			return nil, frozen.failure
		}
	}
	return frozen, nil
}

func (f *FrozenFS) key(name string) string {
	return tspath.GetCanonicalFileName(name, f.caseSensitive)
}

func (f *FrozenFS) validateAncestors(name string) error {
	current := name
	for current != "/" {
		if err := f.meter.Charge(1, 0, 0); err != nil {
			return err
		}
		parent := path.Dir(current)
		if listing := f.entries[parent]; listing != nil {
			kind := listing[path.Base(current)]
			if kind == 0 || (current != name && kind != 2) {
				return invalid("positive observation conflicts with complete directory listing")
			}
			if current == name && f.links[name] == nil {
				if _, file := f.files[name]; file && kind != 1 {
					return invalid("file conflicts with directory entry")
				}
				if f.directories[name] != nil && kind != 2 {
					return invalid("directory conflicts with file entry")
				}
			}
		}
		if f.absent[parent]&(1<<uint(pb.LookupKind_DIRECTORY)) != 0 {
			return invalid("positive observation has an absent parent")
		}
		current = parent
	}
	return nil
}

func (f *FrozenFS) fail(err error) {
	if f.failure == nil {
		f.failure = err
	}
}

func (f *FrozenFS) Check() error {
	if f.failure != nil {
		return f.failure
	}
	return f.meter.Check()
}

func (f *FrozenFS) resolve(name string) string {
	if err := f.meter.Charge(1, 0, 0); err != nil {
		f.fail(err)
		return ""
	}
	name = f.key(path.Clean(name))
	if !absolute(name) {
		f.fail(invalid("compiler requested a nonabsolute VFS path"))
		return ""
	}
	for hop := 0; hop < 32; hop++ {
		parts := uint64(strings.Count(name, "/") + 1)
		if err := f.meter.Charge(parts, 0, parts*(uint64(len(name))+64)); err != nil {
			f.fail(err)
			return ""
		}
		changed := false
		prefix := "/"
		for _, part := range strings.Split(strings.TrimPrefix(name, "/"), "/") {
			prefix = path.Join(prefix, part)
			if link := f.links[prefix]; link != nil {
				target := link.Target
				if !strings.HasPrefix(target, "/") {
					target = path.Join(path.Dir(prefix), target)
				}
				name = f.key(path.Clean(target + strings.TrimPrefix(name, prefix)))
				if !beneath(name, f.key(link.AllowedRoot)) {
					f.fail(invalid("resolved link escapes its declared root"))
					return ""
				}
				changed = true
				break
			}
		}
		if !changed {
			return name
		}
	}
	f.fail(invalid("frozen symlink cycle or hop limit"))
	return ""
}

// Complete captured directory listings can witness a negative lookup too.
func (f *FrozenFS) known(name string, directory bool) (bool, bool) {
	if !absolute(name) {
		return false, false
	}
	if _, ok := f.files[name]; ok {
		return !directory, true
	}
	if f.directories[name] != nil {
		return directory, true
	}
	kind := pb.LookupKind_FILE
	if directory {
		kind = pb.LookupKind_DIRECTORY
	}
	if f.absent[name]&(uint8(1)<<uint(kind)) != 0 {
		return false, true
	}
	current := name
	for current != "/" {
		parent := path.Dir(current)
		if listing := f.directories[parent]; listing != nil && listing.ListingComplete {
			kind := f.entries[parent][path.Base(current)]
			file, dir := kind == 1, kind == 2
			if current == name {
				return (directory && dir) || (!directory && file), true
			}
			if !dir {
				return false, true
			}
			break
		}
		if f.absent[current]&(uint8(1)<<uint(pb.LookupKind_DIRECTORY)) != 0 {
			return false, true
		}
		current = parent
	}
	return false, false
}

func (f *FrozenFS) exists(name string, directory bool) bool {
	name = f.resolve(name)
	if name == "" {
		return false
	}
	result, known := f.known(name, directory)
	if !known {
		f.fail(invalid("compiler lookup is outside frozen observations"))
	}
	return result
}

func (f *FrozenFS) UseCaseSensitiveFileNames() bool  { return f.caseSensitive }
func (f *FrozenFS) FileExists(name string) bool      { return f.exists(name, false) }
func (f *FrozenFS) DirectoryExists(name string) bool { return f.exists(name, true) }
func (f *FrozenFS) Realpath(name string) string {
	key := f.resolve(name)
	if spelling, ok := f.spellings[key]; ok {
		return spelling
	}
	return key
}
func (f *FrozenFS) ReadFile(name string) (string, bool) {
	name = f.resolve(name)
	if value, ok := f.files[name]; ok {
		return value, true
	}
	if exists, known := f.known(name, false); !known || exists {
		f.fail(invalid("compiler read has no frozen content"))
	}
	return "", false
}

func (f *FrozenFS) GetAccessibleEntries(name string) vfs.Entries {
	name = f.resolve(name)
	if directory := f.directories[name]; directory != nil && directory.ListingComplete {
		if err := f.meter.Charge(1+uint64(len(directory.Files)+len(directory.Directories))*16, 0, uint64(len(directory.Files)+len(directory.Directories))*128); err != nil {
			f.fail(err)
			return vfs.Entries{}
		}
		files, dirs := slices.Clone(directory.Files), slices.Clone(directory.Directories)
		slices.Sort(files)
		slices.Sort(dirs)
		links := make(map[string]struct{})
		for _, entry := range append(slices.Clone(files), dirs...) {
			if f.links[f.key(path.Join(name, entry))] != nil {
				links[entry] = struct{}{}
			}
		}
		return vfs.Entries{Files: files, Directories: dirs, Symlinks: links}
	}
	if exists, known := f.known(name, true); !known || exists {
		f.fail(invalid("compiler directory enumeration has no frozen listing"))
	}
	return vfs.Entries{}
}

type frozenInfo struct {
	name string
	size int64
	dir  bool
	link bool
}

func (i frozenInfo) Name() string { return path.Base(i.name) }
func (i frozenInfo) Size() int64  { return i.size }
func (i frozenInfo) Mode() fs.FileMode {
	if i.link {
		return fs.ModeSymlink | 0400
	}
	if i.dir {
		return fs.ModeDir | 0500
	}
	return 0400
}
func (i frozenInfo) ModTime() time.Time { return time.Time{} }
func (i frozenInfo) IsDir() bool        { return i.dir }
func (i frozenInfo) Sys() any           { return nil }

func (f *FrozenFS) Stat(name string) vfs.FileInfo {
	name = f.resolve(name)
	if text, ok := f.files[name]; ok {
		return frozenInfo{name: name, size: int64(len(text))}
	}
	if f.directories[name] != nil {
		return frozenInfo{name: name, dir: true}
	}
	if exists, known := f.known(name, false); !known || exists {
		f.fail(invalid("compiler stat has no frozen identity"))
	}
	return nil
}
func (f *FrozenFS) WalkDir(root string, callback vfs.WalkDirFunc) error {
	var walk func(string, uint32) error
	walk = func(name string, depth uint32) error {
		if err := f.meter.Depth(depth); err != nil {
			return err
		}
		var info vfs.FileInfo
		if link := f.links[f.key(path.Clean(name))]; link != nil {
			info = frozenInfo{name: name, link: true}
		} else {
			info = f.Stat(name)
		}
		if err := f.Check(); err != nil {
			return err
		}
		if info == nil {
			return callback(name, nil, fs.ErrNotExist)
		}
		if err := callback(name, fs.FileInfoToDirEntry(info), nil); err != nil {
			if errors.Is(err, fs.SkipDir) && info.IsDir() {
				return nil
			}
			return err
		}
		if !info.IsDir() {
			return nil
		}
		entries := f.GetAccessibleEntries(name)
		if err := f.Check(); err != nil {
			return err
		}
		children := append(entries.Files, entries.Directories...)
		slices.Sort(children)
		for _, child := range children {
			if err := walk(path.Join(name, child), depth+1); err != nil {
				if errors.Is(err, fs.SkipDir) {
					break
				}
				return err
			}
		}
		return nil
	}
	err := walk(root, 1)
	if errors.Is(err, fs.SkipAll) || errors.Is(err, fs.SkipDir) {
		return nil
	}
	return err
}
func (f *FrozenFS) denyWrite() error {
	f.fail(invalid("compiler attempted to mutate the frozen view"))
	return fs.ErrPermission
}
func (f *FrozenFS) WriteFile(string, string) error             { return f.denyWrite() }
func (f *FrozenFS) AppendFile(string, string) error            { return f.denyWrite() }
func (f *FrozenFS) Remove(string) error                        { return f.denyWrite() }
func (f *FrozenFS) Chtimes(string, time.Time, time.Time) error { return f.denyWrite() }

var _ vfs.FS = (*FrozenFS)(nil)
