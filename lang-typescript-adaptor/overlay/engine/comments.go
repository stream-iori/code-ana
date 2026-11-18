package engine

import (
	"crypto/sha256"
	"encoding/hex"
	"math/bits"
	"sort"
	"strconv"
	"strings"

	"github.com/microsoft/typescript-go/internal/ast"
	"github.com/microsoft/typescript-go/internal/parser"
	"github.com/microsoft/typescript-go/internal/scanner"
)

const commentRule = "typescript-go-comments-v1"

type CommentSegment struct {
	BodyStart   int `json:"body_start"`
	BodyEnd     int `json:"body_end"`
	SourceStart int `json:"source_start"`
	SourceEnd   int `json:"source_end"`
}
type SemanticComment struct {
	ID, File, Hash, Module, Scope, World, Language, Kind, Body string
	Span                                                       SourceSpan
	BodyMap                                                    []CommentSegment
	Documents                                                  []string
	EnclosingCallable                                          string
}
type lexicalRange struct{ start, end int }

// Comment IDs use model::key's JSON string-array digest, not the independent
// length-prefixed TS declaration key. Encode UTF-8 exactly as serde_json does,
// including raw non-ASCII and HTML characters and escaped ASCII controls.
func publicKey(namespace string, parts ...string) string {
	var value strings.Builder
	value.WriteByte('[')
	for i, part := range parts {
		if i != 0 {
			value.WriteByte(',')
		}
		value.WriteByte('"')
		for _, c := range part {
			switch c {
			case '\\', '"':
				value.WriteByte('\\')
				value.WriteRune(c)
			case '\b':
				value.WriteString(`\b`)
			case '\f':
				value.WriteString(`\f`)
			case '\n':
				value.WriteString(`\n`)
			case '\r':
				value.WriteString(`\r`)
			case '\t':
				value.WriteString(`\t`)
			default:
				if c < 32 {
					value.WriteString(`\u00`)
					value.WriteByte("0123456789abcdef"[c>>4])
					value.WriteByte("0123456789abcdef"[c&15])
				} else {
					value.WriteRune(c)
				}
			}
		}
		value.WriteByte('"')
	}
	value.WriteByte(']')
	digest := sha256.Sum256([]byte(value.String()))
	return namespace + "_" + hex.EncodeToString(digest[:])
}

func commentBody(text string, start, end int, kind string) (string, []CommentSegment) {
	a, z := start+2, end
	if kind != "line" && strings.HasSuffix(text[start:end], "*/") {
		z -= 2
	}
	if kind == "doc" {
		a++
	}
	if a > z {
		a = z
	}
	var body strings.Builder
	segments := []CommentSegment{}
	for a < z {
		next := a
		for next < z && text[next] != '\n' && text[next] != '\r' {
			next++
		}
		lineEnd := next
		if next < z {
			next++
			if text[lineEnd] == '\r' && next < z && text[next] == '\n' {
				next++
			}
		}
		content := a
		if kind == "doc" {
			for content < lineEnd && (text[content] == ' ' || text[content] == '\t') {
				content++
			}
			if content < lineEnd && text[content] == '*' {
				content++
				if content < lineEnd && text[content] == ' ' {
					content++
				}
			} else {
				content = a
			}
		}
		if content < next {
			begin := body.Len()
			body.WriteString(text[content:next])
			segments = append(segments, CommentSegment{begin, body.Len(), content, next})
		}
		a = next
	}
	return body.String(), segments
}

func (b *semanticBuilder) comments(file *semanticFile) error {
	text := file.source.Text()
	// Fund scanner traversal, protected lexical ranges, comment bodies/maps and
	// lazy JSDoc parsing before allocation. Compiler internals remain RSS bounded.
	if err := b.program.meter.Charge(uint64(len(text))+1, 1, uint64(len(text))*64+4096); err != nil {
		return err
	}
	protected := []lexicalRange{}
	documents := map[int][]string{}
	callables := []struct {
		span lexicalRange
		id   string
	}{}
	factory := ast.NewNodeFactory(ast.NodeFactoryHooks{})
	var visit func(*ast.Node, uint32) error
	visit = func(node *ast.Node, depth uint32) error {
		if err := b.program.meter.Depth(depth); err != nil {
			return err
		}
		if err := b.program.meter.Charge(2, 1, 128); err != nil {
			return err
		}
		switch node.Kind {
		case ast.KindRegularExpressionLiteral, ast.KindStringLiteral, ast.KindNoSubstitutionTemplateLiteral, ast.KindTemplateHead, ast.KindTemplateMiddle, ast.KindTemplateTail, ast.KindJsxText, ast.KindJsxTextAllWhiteSpaces:
			// AST token bounds disambiguate regex, template continuation and JSX
			// text; a standalone scanner cannot infer their lexical context.
			start := node.Pos()
			if node.Kind != ast.KindJsxText && node.Kind != ast.KindJsxTextAllWhiteSpaces {
				start = scanner.SkipTrivia(text, start)
			}
			protected = append(protected, lexicalRange{start, node.End()})
		}
		if index, ok := b.byNode[node]; ok && callable(node) && node.Body() != nil {
			callables = append(callables, struct {
				span lexicalRange
				id   string
			}{lexicalRange{scanner.SkipTrivia(text, node.Pos()), node.End()}, b.facts.Declarations[index].ID})
		}
		if node.Flags&ast.NodeFlagsHasJSDoc != 0 {
			ranges := parser.GetJSDocCommentRanges(factory, nil, node, text)
			if node.Flags&ast.NodeFlagsReparsed != 0 {
				// A JS typedef/callback is reparsed inside its own comment. Its
				// leading trivia cannot rediscover that enclosing token; use the
				// compiler's attached JSDoc object and match the exact token end.
				for _, doc := range node.JSDoc(file.source) {
					if err := b.program.meter.Charge(uint64(doc.End()-doc.Pos())+1, 1, 1024); err != nil {
						return err
					}
					add := func(token ast.CommentRange) {
						if token.End() != doc.End() {
							return
						}
						for _, existing := range ranges {
							if existing.Pos() == token.Pos() {
								return
							}
						}
						ranges = append(ranges, token)
					}
					for token := range scanner.GetLeadingCommentRanges(factory, text, doc.Pos()) {
						add(token)
					}
					for token := range scanner.GetTrailingCommentRanges(factory, text, doc.Pos()) {
						add(token)
					}
				}
			}
			for _, doc := range ranges {
				if err := b.program.meter.Charge(1, 1, 512); err != nil {
					return err
				}
				if index, ok := b.byNode[node]; ok {
					documents[doc.Pos()] = append(documents[doc.Pos()], b.facts.Declarations[index].ID)
				} else if node.Kind == ast.KindVariableStatement {
					// JSDoc belongs to the statement's declared bindings by language
					// syntax, never to a nearby same-name callable.
					for _, binding := range node.AsVariableStatement().DeclarationList.AsVariableDeclarationList().Declarations.Nodes {
						if index, ok := b.byNode[binding]; ok {
							documents[doc.Pos()] = append(documents[doc.Pos()], b.facts.Declarations[index].ID)
						}
					}
				}
			}
		}
		var failure error
		node.ForEachChild(func(child *ast.Node) bool { failure = visit(child, depth+1); return failure != nil })
		return failure
	}
	if err := visit(file.source.AsNode(), 1); err != nil {
		return err
	}
	if err := b.program.meter.Charge(uint64(len(protected))*(uint64(bits.Len(uint(len(protected))))+1), 0, 0); err != nil {
		return err
	}
	sort.Slice(protected, func(i, j int) bool { return protected[i].start < protected[j].start })
	s := scanner.NewScanner()
	s.SetText(text)
	s.SetLanguageVariant(file.source.LanguageVariant)
	s.SetSkipTrivia(false)
	nextProtected := 0
	for {
		if err := b.program.meter.Check(); err != nil {
			return err
		}
		kind := s.Scan()
		if kind == ast.KindEndOfFile {
			break
		}
		start, end := s.TokenStart(), s.TokenEnd()
		for nextProtected < len(protected) && protected[nextProtected].end <= start {
			nextProtected++
		}
		if nextProtected < len(protected) && protected[nextProtected].start <= start {
			s.ResetPos(protected[nextProtected].end)
			nextProtected++
			continue
		}
		if kind != ast.KindSingleLineCommentTrivia && kind != ast.KindMultiLineCommentTrivia {
			continue
		}
		if err := b.program.meter.Charge(uint64(len(callables))+1, 1, uint64(end-start)*8+2048); err != nil {
			return err
		}
		span, err := file.index.span(start, end)
		if err != nil {
			return err
		}
		name := "line"
		if kind == ast.KindMultiLineCommentTrivia {
			name = "block"
			if strings.HasPrefix(text[start:end], "/**") && !strings.HasPrefix(text[start:end], "/**/") {
				name = "doc"
			}
		}
		body, bodyMap := commentBody(text, start, end, name)
		d := file.identity
		comment := SemanticComment{File: file.logical, Hash: d.Sha256, Module: d.Module, Scope: d.Scope, World: d.World, Language: languageName(d.Language), Kind: name, Span: span, Body: body, BodyMap: bodyMap, Documents: documents[start]}
		comment.ID = publicKey("comment", producer, comment.Language, comment.Module, comment.Scope, comment.World, comment.File, comment.Hash, strconv.Itoa(start), strconv.Itoa(end), name)
		size := len(text) + 1
		for _, owner := range callables {
			if owner.span.start <= start && end <= owner.span.end && owner.span.end-owner.span.start < size {
				comment.EnclosingCallable = owner.id
				size = owner.span.end - owner.span.start
			}
		}
		b.facts.Comments = append(b.facts.Comments, comment)
	}
	return b.program.meter.Check()
}
