package engine

import (
	"context"
	"strings"
	"testing"
)

func TestCommentPublicIdentityMatchesModelJSONUTF8(t *testing.T) {
	got := publicKey("comment", "<>&", "中文😀", "\u2028\u2029", "\b\f\n\r\t\x01", "\"\\")
	if got != "comment_19db4a85159ce9fdad2901b397d31a971c5275b1d873b2190100d09cdfc38b1c" {
		t.Fatal(got)
	}
}

func TestSemanticCommentsUseKernelTokensJSDocOwnershipAndRawMappings(t *testing.T) {
	source := "\ufeff" + `/** 中文😀 文档
 * 第二行 needle
 */
export function outer() {
 // inside line
 const regex = /[/*]never/;
 const string = "/* string fake */";
 const template = TEMPLATE;
 const view = <div>/* jsx fake */ // text</div>;
 const inner = () => { /* inner block */ return 1; };
 /** local variable doc */
 const local = 1;
 return local;
}
function empty() { /* empty block */ }
// ordinary leading
function undocumented() {}
/**/
// tail`
	source = strings.ReplaceAll(source, "TEMPLATE", "`/* template fake */ ${1 /* expression real */} // continuation fake`")
	source = strings.ReplaceAll(source, "\n", "\r\n")
	program, _ := semanticFixture(t, map[string]string{"entry.tsx": source}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	ids := map[string]SemanticDeclaration{}
	for _, d := range facts.Declarations {
		ids[d.ID] = d
	}
	seen := map[string]SemanticComment{}
	for _, comment := range facts.Comments {
		raw := source[comment.Span.Start.Byte:comment.Span.End.Byte]
		if strings.Contains(raw, "fake") || raw == "// text" {
			t.Fatal("non-comment lexical content", raw)
		}
		seen[raw] = comment
		for _, segment := range comment.BodyMap {
			if comment.Body[segment.BodyStart:segment.BodyEnd] != source[segment.SourceStart:segment.SourceEnd] {
				t.Fatal("body map", comment)
			}
		}
		if strings.Contains(raw, "中文") {
			if comment.Kind != "doc" || comment.Span.Start.Byte != 3 || comment.Span.Start.Line != 1 || comment.Span.Start.Column != 2 || len(comment.Documents) != 1 || ids[comment.Documents[0]].Name != "outer" || comment.EnclosingCallable != "" || strings.Contains(comment.Body, "* 第二行") {
				t.Fatal("JSDoc ownership or raw BOM/UTF16 range", comment)
			}
		} else if raw == "/** local variable doc */" {
			if len(comment.Documents) != 1 || ids[comment.Documents[0]].Name != "local" || ids[comment.EnclosingCallable].Name != "outer" {
				t.Fatal("statement JSDoc", comment)
			}
		} else if raw == "/* inner block */" {
			if ids[comment.EnclosingCallable].Kind != "function" || ids[comment.EnclosingCallable].Name != "<function>" {
				t.Fatal("nested callable", comment)
			}
		} else if raw == "// ordinary leading" || raw == "// tail" || raw == "/**/" {
			if len(comment.Documents) != 0 || comment.EnclosingCallable != "" {
				t.Fatal("fabricated nearest ownership", comment)
			}
		}
	}
	for _, raw := range []string{"// inside line", "/* expression real */", "/* inner block */", "/** local variable doc */", "/* empty block */", "// ordinary leading", "/**/", "// tail"} {
		if _, ok := seen[raw]; !ok {
			t.Fatal("missing lexical comment", raw, seen)
		}
	}
	if len(seen) != 9 {
		t.Fatal("missing/duplicate comments", seen)
	}
}
