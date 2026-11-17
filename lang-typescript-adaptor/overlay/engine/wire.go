package engine

import (
	"math"
	"unicode/utf8"

	pb "github.com/microsoft/typescript-go/codeana/typescriptpb"
	"google.golang.org/protobuf/encoding/protowire"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/reflect/protoreflect"
)

const MaxRequestBytes = 256 << 20
const MaxWireValues = 1_000_000

// This is admission, not a second codec: generated protobuf performs decoding.
// Count and validate the original wire before constructing repeated objects.
func admitWire(data []byte, descriptor protoreflect.MessageDescriptor, depth uint32, remaining *uint64) error {
	if depth > 64 {
		return invalid("protobuf nesting limit")
	}
	var seen, oneofs uint64
	for len(data) > 0 {
		if *remaining == 0 {
			return invalid("protobuf value limit")
		}
		*remaining--
		number, kind, n := protowire.ConsumeTag(data)
		if n < 0 || number <= 0 || number > 63 {
			return invalid("invalid protobuf tag")
		}
		field := descriptor.Fields().ByNumber(number)
		if field == nil {
			return invalid("unknown protobuf field")
		}
		bit := uint64(1) << uint(number)
		if field.Cardinality() != protoreflect.Repeated {
			if seen&bit != 0 {
				return invalid("duplicate protobuf field")
			}
			seen |= bit
		}
		if choice := field.ContainingOneof(); choice != nil {
			choiceBit := uint64(1) << uint(choice.Index())
			if oneofs&choiceBit != 0 {
				return invalid("duplicate protobuf operation")
			}
			oneofs |= choiceBit
		}
		data = data[n:]
		if kind == protowire.BytesType {
			value, consumed := protowire.ConsumeBytes(data)
			if consumed < 0 {
				return invalid("truncated protobuf value")
			}
			switch field.Kind() {
			case protoreflect.MessageKind:
				if err := admitWire(value, field.Message(), depth+1, remaining); err != nil {
					return err
				}
			case protoreflect.StringKind:
				if !utf8.Valid(value) {
					return invalid("invalid protobuf UTF-8")
				}
			case protoreflect.BytesKind:
			case protoreflect.EnumKind:
				if !field.IsPacked() {
					return invalid("unexpected packed protobuf field")
				}
				for len(value) > 0 {
					v, count := protowire.ConsumeVarint(value)
					if count < 0 || *remaining == 0 || v > math.MaxInt32 || field.Enum().Values().ByNumber(protoreflect.EnumNumber(v)) == nil {
						return invalid("invalid packed protobuf enum")
					}
					*remaining--
					value = value[count:]
				}
			default:
				return invalid("unexpected protobuf length-delimited field")
			}
			data = data[consumed:]
		} else if kind == protowire.VarintType {
			v, consumed := protowire.ConsumeVarint(data)
			if consumed < 0 {
				return invalid("truncated protobuf integer")
			}
			switch field.Kind() {
			case protoreflect.Uint64Kind:
			case protoreflect.Uint32Kind:
				if v > math.MaxUint32 {
					return invalid("protobuf integer overflow")
				}
			case protoreflect.BoolKind:
				if v > 1 {
					return invalid("invalid protobuf boolean")
				}
			case protoreflect.EnumKind:
				if v > math.MaxInt32 || field.Enum().Values().ByNumber(protoreflect.EnumNumber(v)) == nil {
					return invalid("unknown protobuf enum")
				}
			default:
				return invalid("unexpected protobuf integer field")
			}
			data = data[consumed:]
		} else {
			return invalid("unexpected protobuf wire type")
		}
	}
	return nil
}

func DecodeRequest(data []byte) (*pb.Request, uint64, error) {
	if len(data) == 0 || len(data) > MaxRequestBytes {
		return nil, 0, invalid("request byte limit")
	}
	request := &pb.Request{}
	remaining := uint64(MaxWireValues)
	if err := admitWire(data, request.ProtoReflect().Descriptor(), 1, &remaining); err != nil {
		return nil, 0, err
	}
	if err := (proto.UnmarshalOptions{RecursionLimit: 64}).Unmarshal(data, request); err != nil {
		return nil, 0, invalid("invalid request protobuf")
	}
	if request.Version != RequestVersion || request.RequestId == "" || len(request.RequestId) > 256 ||
		request.DeadlineMs == nil || request.Operation == nil || request.Grant == nil || request.Seed == nil ||
		!proto.Equal(request.ExpectedRuntime, RuntimeIdentity()) {
		return nil, 0, invalid("request version, identity, operation and budget must be explicit")
	}
	return request, MaxWireValues - remaining, nil
}
