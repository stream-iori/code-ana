package engine

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	g "github.com/microsoft/typescript-go/codeana/graphpb"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
)

const producer = "typescript-core"
const semanticsVersion = "29"

func optionalText(value string) *g.Cell {
	if value == "" {
		return nullCell()
	}
	return textCell(value)
}
func booleanCell(value bool) *g.Cell {
	if value {
		return intCell(1)
	}
	return intCell(0)
}
func location(file string, span SourceSpan) string {
	return fmt.Sprintf("%s:%d:%d:%d:%d", file, span.Start.Line, span.Start.Column, span.End.Line, span.End.Column)
}
func jsonText(value any) (string, error) { data, err := json.Marshal(value); return string(data), err }
func graphRow(table g.Table, world string, cells ...*g.Cell) *g.Row {
	return &g.Row{Table: table, World: world, Cells: cells}
}
func provider(language string) string { return producer + ":" + language }
func typeKind(kind string) string {
	switch kind {
	case "class", "interface", "enum":
		return kind
	}
	return ""
}

type projection struct {
	writer       *ArchiveWriter
	meter        *Meter
	declarations map[string]*SemanticDeclaration
}

func (p *projection) declaration(d *SemanticDeclaration) error {
	if err := p.meter.Charge(1, 64, uint64(len(d.File))*32+16384); err != nil {
		return err
	}
	metadata, err := jsonText(map[string]any{"ts_symbol_group": d.SymbolGroup, "value_space": d.ValueSpace, "type_space": d.TypeSpace, "namespace_space": d.NamespaceSpace, "implementation": d.Implementation, "declaration_file": d.DeclarationFile, "byte_start": d.Span.Start.Byte, "byte_end": d.Span.End.Byte, "name_byte_start": d.NameSpan.Start.Byte, "name_byte_end": d.NameSpan.End.Byte})
	if err != nil {
		return err
	}
	span := d.NameSpan
	if span.Start.Byte == span.End.Byte {
		span = d.Span
	}
	loc := location(d.File, span)
	node := graphRow(g.Table_NODE, d.World, textCell(d.ID), textCell(d.ID), textCell("language"), textCell(d.Language), textCell(provider(d.Language)), textCell("declaration"), textCell(d.Kind), textCell(d.Name), textCell(d.Kind), textCell(d.QualifiedName), nullCell(), nullCell(), textCell(d.File), textCell(loc), intCell(int64(span.Start.Line)), intCell(int64(span.Start.Column)), intCell(int64(span.End.Line)), intCell(int64(span.End.Column)), intCell(0), textCell(d.Module), textCell(d.Scope), textCell(metadata), nullCell(), textCell(producer))
	if err := p.writer.Write(node); err != nil {
		return err
	}
	declaringType := ""
	direct := false
	if parent := p.declarations[d.Parent]; parent != nil && typeKind(parent.Kind) != "" {
		declaringType = parent.ID
		direct = true
	}
	decl := graphRow(g.Table_DECLARATION, d.World, textCell(d.ID), textCell("language"), textCell(d.Language), textCell(provider(d.Language)), textCell("declaration"), textCell(d.Kind), textCell(d.QualifiedName), textCell(d.Name), textCell(d.Kind), textCell(d.Kind), optionalText(typeKind(d.Kind)), textCell(d.Visibility), textCell("[]"), textCell("[]"), textCell("[]"), optionalText(declaringType), nullCell(), textCell(d.File), textCell(location(d.File, d.Span)), intCell(int64(d.Span.Start.Line)), intCell(int64(d.Span.Start.Column)), intCell(int64(d.Span.End.Line)), intCell(int64(d.Span.End.Column)), textCell(d.Module), textCell(d.Scope), intCell(int64(d.Depth)), booleanCell(direct), booleanCell(d.Synthetic), booleanCell(d.Resolved), textCell(producer))
	return p.writer.Write(decl)
}
func (p *projection) edge(owner *SemanticDeclaration, target, external, relation, role, kind, resolution string, span SourceSpan, ordinal uint64, metadata string) error {
	if err := p.meter.Charge(1, 40, uint64(len(owner.File)+len(external))*16+8192); err != nil {
		return err
	}
	confidence := "EXTRACTED"
	if resolution != "exact" {
		confidence = "UNRESOLVED"
	}
	externalLanguage := ""
	if strings.HasPrefix(external, "typescript-lib/") {
		externalLanguage = "typescript"
	}
	row := graphRow(g.Table_EDGE, owner.World, textCell(owner.ID), optionalText(target), optionalText(external), optionalText(external), optionalText(externalLanguage), optionalText(func() string {
		if external != "" {
			return producer
		}
		return ""
	}()), textCell(relation), optionalText(role), textCell("typescript-checker"), textCell(owner.Language), textCell(provider(owner.Language)), optionalText(kind), textCell(confidence), textCell(resolution), nullCell(), booleanCell(target == ""), textCell(owner.File), textCell(location(owner.File, span)), intCell(int64(span.Start.Line)), intCell(int64(span.Start.Column)), intCell(int64(span.End.Line)), intCell(int64(span.End.Column)), intCell(int64(ordinal)), nullCell(), nullCell(), textCell(metadata), textCell(owner.Module), textCell(owner.Scope), intCell(1), intCell(1), textCell(owner.ID), optionalText(target), nullCell(), textCell(producer))
	return p.writer.Write(row)
}
func (p *projection) facts(facts *SemanticFacts) error {
	if err := p.meter.Charge(uint64(len(facts.Declarations))+1, 1, uint64(len(facts.Declarations))*128+512); err != nil {
		return err
	}
	p.declarations = make(map[string]*SemanticDeclaration, len(facts.Declarations))
	for i := range facts.Declarations {
		d := &facts.Declarations[i]
		p.declarations[d.ID] = d
	}
	for i := range facts.Declarations {
		if err := p.declaration(&facts.Declarations[i]); err != nil {
			return err
		}
	}
	for _, d := range facts.Declarations {
		if d.Parent != "" {
			if err := p.edge(p.declarations[d.Parent], d.ID, "", "CONTAINS", "lexical", "", "exact", d.NameSpan, 0, "{}"); err != nil {
				return err
			}
		}
	}
	for _, ref := range facts.References {
		owner := p.declarations[ref.Source]
		if owner == nil {
			return invalid("reference owner missing from declaration table")
		}
		if err := p.meter.Charge(1, 1, 8192); err != nil {
			return err
		}
		metadata, err := jsonText(map[string]any{"ts_reference_id": ref.ID, "role": ref.Role, "byte_start": ref.Span.Start.Byte, "byte_end": ref.Span.End.Byte, "enclosing_callable": ref.Caller})
		if err != nil {
			return err
		}
		relation := "REFERENCES"
		switch ref.Role {
		case "write":
			relation = "WRITES"
		case "read_write":
			relation = "READS_WRITES"
		case "read":
			relation = "READS"
		case "type":
			relation = "USES_TYPE"
		case "imports", "imports_type", "imports_module", "imports_module_type":
			relation = "IMPORTS"
		case "exports", "exports_type", "exports_module", "exports_module_type":
			relation = "EXPORTS"
		}
		for i, target := range ref.Targets {
			if err := p.edge(owner, target, "", relation, ref.Role, "", "exact", ref.Span, uint64(i), metadata); err != nil {
				return err
			}
		}
		for i, external := range ref.External {
			if err := p.edge(owner, "", external, relation, ref.Role, "", "external", ref.Span, uint64(i), metadata); err != nil {
				return err
			}
		}
	}
	for _, call := range facts.Calls {
		owner := p.declarations[call.Caller]
		if owner == nil {
			return invalid("call owner missing from declaration table")
		}
		if err := p.meter.Charge(1, 1, 8192); err != nil {
			return err
		}
		// A class declaration selected for an implicit constructor is type
		// evidence, not an invented source constructor implementation.
		target := call.Target
		targetKind, reason := call.TargetKind, call.Reason
		if declaration := p.declarations[target]; call.SyntaxKind == "new" && declaration != nil && declaration.Kind == "class" {
			target = ""
			targetKind = "unknown"
			reason = "implicit_constructor_pending"
		}
		metadata, err := jsonText(map[string]any{"ts_call_id": call.ID, "target_kind": targetKind, "selected_declaration": call.SelectedDeclaration, "binding_reason": reason, "syntax_kind": call.SyntaxKind, "byte_start": call.Span.Start.Byte, "byte_end": call.Span.End.Byte})
		if err != nil {
			return err
		}
		resolution := "unresolved"
		if target != "" {
			resolution = "exact"
		} else if call.External != "" {
			resolution = "external"
		}
		if err := p.edge(owner, target, call.External, "CALLS", targetKind, "STATIC", resolution, call.Span, call.Ordinal, metadata); err != nil {
			return err
		}
	}
	return nil
}

// semanticGraph is the sole Analyze projection. All records retain the frozen
// source owner; partial coverage stays explicit until T5 completes its boundary.
func semanticGraph(ctx context.Context, analyze *pb.Analyze, meter *Meter) (_ *pb.Archive, failure error) {
	if analyze.SemanticProfile == "" {
		return nil, invalid("semantic profile required")
	}
	meter.phase = "backend"
	program, err := OpenProgram(analyze.Project, meter)
	if err != nil {
		return nil, err
	}
	meter.phase = "extract"
	facts, err := program.Semantics(ctx)
	if err != nil {
		return nil, err
	}
	meter.phase = "records"
	writer, err := NewArchive(analyze.Scratch, analyze.Output, meter)
	if err != nil {
		return nil, err
	}
	defer func() { failure = errors.Join(failure, writer.Close()) }()
	project := analyze.Project
	for _, tag := range project.Context.PublishLanguages {
		if err := meter.Charge(1, 8, 4096); err != nil {
			return nil, err
		}
		language := languageName(tag)
		if err := writer.Write(graphRow(g.Table_PROVIDER, "", textCell(provider(language)), textCell(language), textCell(RuntimeIdentity().CompilerVersion), textCell(`["declarations","references","calls","imports","exports"]`), textCell(`["TYPESCRIPT_T5_PENDING","runtime_dispatch_unsupported"]`), textCell(analyze.SemanticProfile))); err != nil {
			return nil, err
		}
	}
	if err := (&projection{writer: writer, meter: meter}).facts(facts); err != nil {
		return nil, err
	}
	for _, file := range project.Files {
		identity := file.File
		if identity.Role != pb.FileRole_PUBLISH {
			continue
		}
		name := strings.TrimPrefix(identity.Path, project.Context.Root+"/")
		language := languageName(identity.Language)
		for _, capability := range []string{"SOURCE_DEFS", "REFERENCES", "CALLS", "AGGREGATE"} {
			if err := meter.Charge(1, 16, 4096); err != nil {
				return nil, err
			}
			if err := writer.Write(graphRow(g.Table_COVERAGE, identity.World, textCell(name), textCell(identity.Module), textCell(identity.Scope), textCell(language), textCell(provider(language)), textCell(capability), textCell("partial"), intCell(1), intCell(1), textCell(`["TYPESCRIPT_T5_PENDING"]`), textCell(`{"TYPESCRIPT_T5_PENDING":1}`), intCell(0))); err != nil {
				return nil, err
			}
		}
		if err := meter.Charge(1, 16, 4096); err != nil {
			return nil, err
		}
		if err := writer.Write(graphRow(g.Table_DIAGNOSTIC, identity.World, textCell("warning"), textCell("TYPESCRIPT_T5_PENDING"), textCell("extract"), textCell(name), textCell(identity.Module), textCell(identity.Scope), nullCell(), textCell(language), textCell(provider(language)), textCell("first public semantic projection"), textCell("TYPESCRIPT_T5_PENDING"), nullCell(), intCell(1), textCell("Members, JSX, comments and full diagnostic coverage continue in T5"))); err != nil {
			return nil, err
		}
	}
	if err := meter.Charge(2, 8, 2048); err != nil {
		return nil, err
	}
	if err := writer.Write(graphRow(g.Table_PROJECT_META, "", textCell("graph_semantics_version"), textCell(semanticsVersion))); err != nil {
		return nil, err
	}
	if err := meter.Check(); err != nil {
		return nil, err
	}
	meter.phase = "archive"
	return writer.Seal()
}
