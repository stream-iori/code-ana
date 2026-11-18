package engine

import (
	"context"
	"testing"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
)

func qualityProject(program *Program) *pb.FrozenProject {
	project := &pb.FrozenProject{Context: program.context}
	for _, identity := range program.files {
		project.Files = append(project.Files, &pb.FrozenFile{File: identity})
	}
	return project
}
func TestQualitySeparatesContextBindingAndCompleteCommentEvidence(t *testing.T) {
	program, meter := semanticFixture(t, map[string]string{"entry.ts": "import {missing} from './absent'; /** document */ export function stable(value: number) { return value; } missing();"}, nil)
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	contextGap := false
	for _, d := range facts.Diagnostics {
		if d.Phase == "context" && d.File == "" {
			contextGap = true
		}
	}
	quality, err := semanticQuality(facts, qualityProject(program), program.context.Root, meter)
	if err != nil {
		t.Fatal(err)
	}
	q := quality["entry.ts"]
	for _, capability := range []string{"SOURCE_DEFS", "COMMENTS"} {
		status, _, _, _ := q.coverage(capability)
		if status != "complete" {
			t.Fatal(capability, status)
		}
	}
	for _, capability := range []string{"REFERENCES", "CALLS", "AGGREGATE"} {
		status, codes, _, _ := q.coverage(capability)
		if status != "partial" || len(codes) == 0 {
			t.Fatal(capability, status, codes)
		}
	}
	if !contextGap {
		t.Fatal("missing global type diagnostics", facts.Diagnostics)
	}
}
func TestQualityCompleteRequiresActualProgramAndKnownAmbientTypes(t *testing.T) {
	globals := "interface Array<T> { length: number; [index: number]: T } interface Boolean {} interface Function {} interface CallableFunction {} interface NewableFunction {} interface IArguments {} interface Number {} interface Object {} interface RegExp {} interface String {}"
	program, meter := semanticFixture(t, map[string]string{"globals.d.ts": globals, "entry.ts": "/** document */ export function stable(value: number) { return value; } stable(1);"}, map[string]bool{"globals.d.ts": true})
	facts, err := program.Semantics(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(facts.Diagnostics) != 0 {
		t.Fatal("ambient fixture is incomplete", facts.Diagnostics)
	}
	project := qualityProject(program)
	// A declared publication source absent from the Program must not acquire
	// complete coverage merely because no diagnostics mention it.
	project.Files = append(project.Files, &pb.FrozenFile{File: &pb.FileIdentity{Path: program.context.Root + "/ignored.ts", Role: pb.FileRole_PUBLISH, Language: pb.SourceLanguage_TYPESCRIPT}})
	quality, err := semanticQuality(facts, project, program.context.Root, meter)
	if err != nil {
		t.Fatal(err)
	}
	for _, capability := range semanticCapabilities {
		status, _, _, _ := quality["entry.ts"].coverage(capability)
		if status != "complete" {
			t.Fatal(capability, status)
		}
		status, _, _, _ = quality["ignored.ts"].coverage(capability)
		if status != "unavailable" {
			t.Fatal("uncaptured Program source", capability, status)
		}
	}
}
