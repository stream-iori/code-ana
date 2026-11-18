package engine

import (
	"math/bits"
	"sort"
	"unicode/utf8"
)

// Compiler offsets are UTF-8 byte offsets. Public columns count UTF-16 code
// units, with CRLF treated as one newline. Only non-ASCII deltas are indexed.
type sourceIndex struct {
	text  string
	meter *Meter
	lines []int
	wide  []columnDelta
}
type columnDelta struct{ end, total int }
type SourcePoint struct {
	Byte   uint64
	Line   uint64
	Column uint64
}
type SourceSpan struct{ Start, End SourcePoint }

func newSourceIndex(text string, meter *Meter) (*sourceIndex, error) {
	if len(text) > MaxRequestBytes {
		return nil, invalid("source index byte limit")
	}
	if err := meter.Charge(uint64(len(text))*3+1, 1, 128); err != nil {
		return nil, err
	}
	if !utf8.ValidString(text) {
		return nil, invalid("source text is not UTF-8")
	}
	lines, wide := 1, 0
	for offset, r := range text {
		if r == '\n' || r == '\u2028' || r == '\u2029' || (r == '\r' && (offset+1 == len(text) || text[offset+1] != '\n')) {
			lines++
		}
		if r >= utf8.RuneSelf {
			wide++
		}
	}
	if err := meter.Charge(1, uint64(lines+wide), uint64(lines)*8+uint64(wide)*16); err != nil {
		return nil, err
	}
	index := &sourceIndex{text: text, meter: meter, lines: make([]int, 0, lines), wide: make([]columnDelta, 0, wide)}
	index.lines = append(index.lines, 0)
	total := 0
	for offset, r := range text {
		size := utf8.RuneLen(r)
		if r >= utf8.RuneSelf {
			units := 1
			if r > 0xffff {
				units = 2
			}
			total += size - units
			index.wide = append(index.wide, columnDelta{offset + size, total})
		}
		if r == '\n' || r == '\u2028' || r == '\u2029' || (r == '\r' && (offset+1 == len(text) || text[offset+1] != '\n')) {
			index.lines = append(index.lines, offset+size)
		}
	}
	return index, nil
}
func (s *sourceIndex) delta(offset int) int {
	i := sort.Search(len(s.wide), func(i int) bool { return s.wide[i].end > offset })
	if i == 0 {
		return 0
	}
	return s.wide[i-1].total
}
func (s *sourceIndex) point(offset int) (SourcePoint, error) {
	if err := s.meter.Charge(uint64(bits.Len(uint(len(s.lines)))+2*bits.Len(uint(len(s.wide)))+4), 0, 0); err != nil {
		return SourcePoint{}, err
	}
	if offset < 0 || offset > len(s.text) || (offset < len(s.text) && !utf8.RuneStart(s.text[offset])) {
		return SourcePoint{}, invalid("source offset is not a UTF-8 boundary")
	}
	line := sort.Search(len(s.lines), func(i int) bool { return s.lines[i] > offset }) - 1
	start := s.lines[line]
	return SourcePoint{uint64(offset), uint64(line + 1), uint64(offset - start - s.delta(offset) + s.delta(start) + 1)}, nil
}
func (s *sourceIndex) span(start, end int) (SourceSpan, error) {
	if end < start {
		return SourceSpan{}, invalid("source range is reversed")
	}
	a, err := s.point(start)
	if err != nil {
		return SourceSpan{}, err
	}
	b, err := s.point(end)
	if err != nil {
		return SourceSpan{}, err
	}
	return SourceSpan{a, b}, nil
}
