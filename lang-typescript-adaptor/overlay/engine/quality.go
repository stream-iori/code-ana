package engine

import (
	"sort"
	"strings"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
)

var semanticCapabilities = []string{"SOURCE_DEFS", "REFERENCES", "CALLS", "COMMENTS", "AGGREGATE"}

type fileQuality struct {
	Analyzed bool
	Reasons  map[string]map[string]uint64
}

func (q *fileQuality) add(capability, code string) {
	if q.Reasons[capability] == nil {
		q.Reasons[capability] = map[string]uint64{}
	}
	q.Reasons[capability][code]++
}
func (q *fileQuality) coverage(capability string) (string, []string, map[string]uint64, uint64) {
	if !q.Analyzed {
		return "unavailable", []string{"TYPESCRIPT_SOURCE_NOT_ANALYZED"}, map[string]uint64{"TYPESCRIPT_SOURCE_NOT_ANALYZED": 1}, 1
	}
	counts := q.Reasons[capability]
	if counts == nil {
		return "complete", []string{}, map[string]uint64{}, 0
	}
	codes := make([]string, 0, len(counts))
	var count uint64
	for code, n := range counts {
		codes = append(codes, code)
		count += n
	}
	sort.Strings(codes)
	return "partial", codes, counts, count
}

func semanticQuality(facts *SemanticFacts, project *pb.FrozenProject, publicationRoot string, meter *Meter) (map[string]*fileQuality, error) {
	if err := meter.Charge(uint64(len(project.Files)+len(facts.Declarations)+len(facts.References)+len(facts.Calls)+len(facts.Diagnostics))+1, uint64(len(project.Files))*32+1, uint64(len(project.Files))*8192+uint64(len(facts.Declarations))*128+uint64(len(facts.Diagnostics))*1024+4096); err != nil {
		return nil, err
	}
	quality := map[string]*fileQuality{}
	for _, entry := range project.Files {
		if entry.File.Role == pb.FileRole_PUBLISH {
			quality[strings.TrimPrefix(entry.File.Path, publicationRoot+"/")] = &fileQuality{Reasons: map[string]map[string]uint64{}}
		}
	}
	for _, d := range facts.Declarations {
		if d.Kind == "module" && quality[d.File] != nil {
			quality[d.File].Analyzed = true
		}
		if d.ParameterProperty && !d.Resolved {
			if err := meter.Charge(3, 3, 1024); err != nil {
				return nil, err
			}
			if q := quality[d.File]; q != nil {
				for _, capability := range []string{"SOURCE_DEFS", "REFERENCES", "AGGREGATE"} {
					q.add(capability, "TYPESCRIPT_PARAMETER_PROPERTY_BINDING_GAP")
				}
			}
		}
	}
	add := func(file, code string, capabilities ...string) error {
		if err := meter.Charge(uint64(len(capabilities))+1, uint64(len(capabilities)), uint64(len(code)+128)*uint64(len(capabilities))); err != nil {
			return err
		}
		if q := quality[file]; q != nil {
			for _, capability := range capabilities {
				q.add(capability, code)
			}
		}
		return nil
	}
	for _, d := range facts.Diagnostics {
		if d.Severity != "error" && d.Phase != "extract" {
			continue
		}
		capabilities := []string{"AGGREGATE"}
		switch {
		case d.Phase == "syntax":
			capabilities = semanticCapabilities
		case d.Phase == "context" || d.Reason == "TYPESCRIPT_RESOLUTION_GAP":
			capabilities = []string{"REFERENCES", "CALLS", "AGGREGATE"}
		case d.Code == "TYPESCRIPT_TAGGED_TEMPLATE_UNSUPPORTED":
			capabilities = []string{"CALLS", "AGGREGATE"}
		}
		if d.File == "" {
			for file := range quality {
				if err := add(file, d.Reason, capabilities...); err != nil {
					return nil, err
				}
			}
		} else if err := add(d.File, d.Reason, capabilities...); err != nil {
			return nil, err
		}
	}
	for _, ref := range facts.References {
		if ref.Resolution == "unknown" {
			if err := add(ref.File, "TYPESCRIPT_UNRESOLVED_REFERENCE", "REFERENCES", "AGGREGATE"); err != nil {
				return nil, err
			}
		}
	}
	for _, call := range facts.Calls {
		code := ""
		if call.Reason == "call_type_error" || call.Reason == "unresolved_signature" {
			code = "TYPESCRIPT_UNRESOLVED_CALL"
		}
		if code != "" {
			if err := add(call.File, code, "CALLS", "AGGREGATE"); err != nil {
				return nil, err
			}
		}
	}
	return quality, nil
}
