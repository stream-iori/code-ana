package engine

import (
	"bytes"
	"errors"
	"syscall"
	"unsafe"
)

// F_GETPATH returns the dentry spelling, including for an O_SYMLINK descriptor.
// It does not enumerate siblings or follow the final symbolic link. The caller
// has already funded the fixed buffer, returned string and observation cache.
func physicalSpelling(name string) (_ string, failure error) {
	descriptor, err := syscall.Open(name, syscall.O_RDONLY|syscall.O_NONBLOCK|syscall.O_CLOEXEC|syscall.O_SYMLINK, 0)
	if err != nil {
		return "", err
	}
	defer func() { failure = errors.Join(failure, syscall.Close(descriptor)) }()
	var buffer [1024]byte // Darwin MAXPATHLEN, required by F_GETPATH.
	_, _, errno := syscall.Syscall(syscall.SYS_FCNTL, uintptr(descriptor), syscall.F_GETPATH, uintptr(unsafe.Pointer(&buffer[0])))
	if errno != 0 {
		return "", errno
	}
	end := bytes.IndexByte(buffer[:], 0)
	if end <= 0 {
		return "", invalid("physical pathname is not terminated")
	}
	return string(buffer[:end]), nil
}
