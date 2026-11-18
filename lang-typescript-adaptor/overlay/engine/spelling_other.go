//go:build !darwin

package engine

import pb "github.com/microsoft/typescript-go/codeana/typescriptpb"

// Other platforms require their own verified case-insensitive dentry probe.
// Case-sensitive preparation never calls this function.
func physicalSpelling(string) (string, error) {
	return "", &Fault{Code: pb.FailureCode_UNSUPPORTED_CONTEXT, Message: "case-insensitive physical path capture is unsupported on this platform"}
}
