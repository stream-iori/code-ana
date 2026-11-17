package engine

import (
	"bufio"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"hash"
	"io"
	"os"
	"path/filepath"

	g "github.com/microsoft/typescript-go/codeana/graphpb"
	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"google.golang.org/protobuf/proto"
)

const graphRecordLimit = 8 << 20

// Go is a graph producer. Rust's shared graph-io remains the archive verifier
// and IPC encoder; this writer emits the existing GRA1 protobuf row format.
type ArchiveWriter struct {
	root    *os.Root
	file    *os.File
	buffer  *bufio.Writer
	digest  hash.Hash
	meter   *Meter
	limits  *pb.OutputLimits
	bytes   uint64
	count   uint64
	sealed  bool
	failure error
}

func NewArchive(scratch string, limits *pb.OutputLimits, meter *Meter) (*ArchiveWriter, error) {
	if !filepath.IsAbs(scratch) || limits == nil || limits.ArchiveBytes < 4 || limits.Rows == 0 || limits.ScratchBytes < 4 {
		return nil, invalid("absolute private scratch and explicit output limits required")
	}
	if err := meter.Charge(1, 1, 65536+1024); err != nil {
		return nil, err
	}
	root, err := os.OpenRoot(scratch)
	if err != nil {
		return nil, err
	}
	closed := false
	defer func() {
		if !closed {
			root.Close()
		}
	}()
	directory, err := root.Open(".")
	if err != nil {
		return nil, err
	}
	entries, readErr := directory.ReadDir(1)
	closeErr := directory.Close()
	if readErr != nil && !errors.Is(readErr, io.EOF) {
		return nil, errors.Join(readErr, closeErr)
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if len(entries) != 0 {
		return nil, invalid("engine scratch must be empty and private")
	}
	file, err := root.OpenFile("graph.rows", os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return nil, err
	}
	writer := &ArchiveWriter{root: root, file: file, buffer: bufio.NewWriterSize(file, 65536), digest: sha256.New(), meter: meter, limits: limits, bytes: 4}
	if _, err := writer.buffer.WriteString("GRA1"); err != nil {
		file.Close()
		return nil, err
	}
	writer.digest.Write([]byte("GRA1"))
	closed = true
	return writer, nil
}

func (w *ArchiveWriter) Write(row *g.Row) (failure error) {
	defer func() {
		if w.failure == nil {
			w.failure = failure
		}
	}()
	if w.failure != nil {
		return w.failure
	}
	if w.sealed || w.file == nil || row == nil || row.Table == g.Table_TABLE_UNSPECIFIED {
		return invalid("invalid archive row or writer state")
	}
	size := uint64(proto.Size(row))
	if size == 0 || size > graphRecordLimit || w.count >= w.limits.Rows ||
		size+4 > w.limits.ArchiveBytes-w.bytes || w.bytes > w.limits.ScratchBytes || size+4 > w.limits.ScratchBytes-w.bytes {
		return &Fault{Code: pb.FailureCode_OUTPUT_LIMIT, Message: "graph output limit"}
	}
	if err := w.meter.Charge(1, 0, size+4); err != nil {
		return err
	}
	encoded, err := (proto.MarshalOptions{Deterministic: true}).Marshal(row)
	if err != nil {
		return err
	}
	var length [4]byte
	binary.BigEndian.PutUint32(length[:], uint32(len(encoded)))
	for _, part := range [][]byte{length[:], encoded} {
		if _, err := w.buffer.Write(part); err != nil {
			return err
		}
		w.digest.Write(part)
	}
	w.bytes += size + 4
	w.count++
	return nil
}

func (w *ArchiveWriter) Seal() (_ *pb.Archive, failure error) {
	defer func() {
		if w.failure == nil {
			w.failure = failure
		}
	}()
	if w.failure != nil {
		return nil, w.failure
	}
	if w.sealed || w.file == nil {
		return nil, invalid("archive already closed or sealed")
	}
	if err := w.meter.Charge(1, 1, 512); err != nil {
		return nil, err
	}
	if err := w.buffer.Flush(); err != nil {
		return nil, err
	}
	if err := w.file.Sync(); err != nil {
		return nil, err
	}
	if err := w.file.Close(); err != nil {
		return nil, err
	}
	w.file = nil
	w.sealed = true
	return &pb.Archive{Path: "graph.rows", Bytes: w.bytes, Count: w.count, Sha256: hex.EncodeToString(w.digest.Sum(nil))}, nil
}

func (w *ArchiveWriter) Close() error {
	var failure error
	if w.file != nil {
		failure = w.file.Close()
		w.file = nil
	}
	return errors.Join(failure, w.root.Close())
}
