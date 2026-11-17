package engine

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io/fs"
	"path"
	"slices"
	"strings"
	"time"
	"unicode/utf8"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"github.com/microsoft/typescript-go/internal/bundled"
	"github.com/microsoft/typescript-go/internal/vfs"
)

// FrozenFS has no backing OS filesystem. A missing observation fails the
// computation instead of silently substituting an empty live directory.
type FrozenFS struct {
	files       map[string]string
	directories map[string]*pb.Directory
	links       map[string]*pb.SymbolicLink
	absent      map[string]uint8
	meter       *Meter
	failure     error
}

func absolute(name string) bool {
	return name != "" && len(name) <= 4096 && utf8.ValidString(name) && strings.HasPrefix(name, "/") && !strings.ContainsRune(name, 0) && path.Clean(name) == name
}

func beneath(name, root string) bool {
	return name == root || strings.HasPrefix(name, strings.TrimSuffix(root, "/")+"/")
}

func NewFrozenFS(project *pb.FrozenProject, meter *Meter) (*FrozenFS, error) {
	if project == nil || project.Context == nil || project.Context.Unit == "" || !absolute(project.Context.Root) {
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
	frozen := &FrozenFS{files: make(map[string]string), directories: make(map[string]*pb.Directory), links: make(map[string]*pb.SymbolicLink), absent: make(map[string]uint8), meter: meter}
	for _, file := range project.Files {
		if file == nil || file.File == nil || !absolute(file.File.Path) || beneath(file.File.Path, bundled.LibPath()) || file.File.Size != uint64(len(file.Content)) || !utf8.Valid(file.Content) {
			return nil, invalid("invalid frozen file identity or text")
		}
		identity := file.File
		if identity.Role < pb.FileRole_PUBLISH || identity.Role > pb.FileRole_CONTROL ||
			(identity.Role != pb.FileRole_CONTROL && identity.Language != pb.SourceLanguage_TYPESCRIPT && identity.Language != pb.SourceLanguage_JAVASCRIPT) ||
			(identity.Role == pb.FileRole_PUBLISH && (!languages[identity.Language] || !beneath(identity.Path, context.Root) || identity.Module == "" || identity.World == "" || (identity.Scope != "MAIN" && identity.Scope != "TEST"))) {
			return nil, invalid("invalid frozen source role or publication ownership")
		}
		if _, exists := frozen.files[file.File.Path]; exists {
			return nil, invalid("duplicate frozen file")
		}
		if err := meter.Charge(1+uint64(len(file.Content))/4096, 0, uint64(len(file.Content))); err != nil {
			return nil, err
		}
		digest := sha256.Sum256(file.Content)
		if hex.EncodeToString(digest[:]) != file.File.Sha256 {
			return nil, invalid("frozen content digest mismatch")
		}
		frozen.files[file.File.Path] = string(file.Content)
	}
	for _, directory := range context.Directories {
		if directory == nil || !absolute(directory.Path) || frozen.directories[directory.Path] != nil {
			return nil, invalid("invalid or duplicate frozen directory")
		}
		if _, file := frozen.files[directory.Path]; file {
			return nil, invalid("frozen file is also a directory")
		}
		seen := make(map[string]bool)
		for _, entries := range [][]string{directory.Files, directory.Directories} {
			if err := meter.Charge(uint64(len(entries)), 0, uint64(len(entries))*64); err != nil {
				return nil, err
			}
			for _, entry := range entries {
				if entry == "" || entry == "." || entry == ".." || strings.ContainsAny(entry, "/\x00") || !utf8.ValidString(entry) || seen[entry] {
					return nil, invalid("invalid or conflicting directory entry")
				}
				seen[entry] = true
			}
		}
		frozen.directories[directory.Path] = directory
	}
	for _, link := range context.Links {
		if link == nil || !absolute(link.Path) || !absolute(link.AllowedRoot) || link.Target == "" || strings.ContainsRune(link.Target, 0) || frozen.links[link.Path] != nil {
			return nil, invalid("invalid or duplicate frozen link")
		}
		target := link.Target
		if !strings.HasPrefix(target, "/") {
			target = path.Join(path.Dir(link.Path), target)
		}
		if !beneath(path.Clean(target), link.AllowedRoot) {
			return nil, invalid("frozen link escapes its declared root")
		}
		frozen.links[link.Path] = link
	}
	for _, absent := range context.Absent {
		if absent == nil || !absolute(absent.Path) || (absent.Kind != pb.LookupKind_FILE && absent.Kind != pb.LookupKind_DIRECTORY) {
			return nil, invalid("invalid missing lookup observation")
		}
		bit := uint8(1) << uint(absent.Kind)
		if frozen.absent[absent.Path]&bit != 0 {
			return nil, invalid("duplicate missing lookup observation")
		}
		if _, exists := frozen.files[absent.Path]; exists && absent.Kind == pb.LookupKind_FILE {
			return nil, invalid("positive file conflicts with missing lookup")
		}
		if frozen.directories[absent.Path] != nil && absent.Kind == pb.LookupKind_DIRECTORY {
			return nil, invalid("positive directory conflicts with missing lookup")
		}
		frozen.absent[absent.Path] |= bit
	}
	for name := range frozen.links {
		frozen.resolve(name)
		if frozen.failure != nil {
			return nil, frozen.failure
		}
	}
	return frozen, nil
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
	name = path.Clean(name)
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
				name = path.Clean(target + strings.TrimPrefix(name, prefix))
				if !beneath(name, link.AllowedRoot) {
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
		if listing := f.directories[parent]; listing != nil {
			file := slices.Contains(listing.Files, path.Base(current))
			dir := slices.Contains(listing.Directories, path.Base(current))
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

func (f *FrozenFS) UseCaseSensitiveFileNames() bool  { return true }
func (f *FrozenFS) FileExists(name string) bool      { return f.exists(name, false) }
func (f *FrozenFS) DirectoryExists(name string) bool { return f.exists(name, true) }
func (f *FrozenFS) Realpath(name string) string      { return f.resolve(name) }
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
	if directory := f.directories[name]; directory != nil {
		if err := f.meter.Charge(1, 0, uint64(len(directory.Files)+len(directory.Directories))*32); err != nil {
			f.fail(err)
			return vfs.Entries{}
		}
		files, dirs := slices.Clone(directory.Files), slices.Clone(directory.Directories)
		slices.Sort(files)
		slices.Sort(dirs)
		links := make(map[string]struct{})
		for _, entry := range append(slices.Clone(files), dirs...) {
			if f.links[path.Join(name, entry)] != nil {
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
}

func (i frozenInfo) Name() string { return path.Base(i.name) }
func (i frozenInfo) Size() int64  { return i.size }
func (i frozenInfo) Mode() fs.FileMode {
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
		info := f.Stat(name)
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
				return err
			}
		}
		return nil
	}
	err := walk(root, 1)
	if errors.Is(err, fs.SkipAll) {
		return nil
	}
	return err
}
func (*FrozenFS) WriteFile(string, string) error             { return fs.ErrPermission }
func (*FrozenFS) AppendFile(string, string) error            { return fs.ErrPermission }
func (*FrozenFS) Remove(string) error                        { return fs.ErrPermission }
func (*FrozenFS) Chtimes(string, time.Time, time.Time) error { return fs.ErrPermission }

var _ vfs.FS = (*FrozenFS)(nil)
