#define _GNU_SOURCE
#include <fcntl.h>

/* Darwin arm64 passes variadic arguments on the stack. Export a fixed-signature
 * call for Graal CFunction rather than describing fcntl as a fixed 3-arg call. */
int codeana_dup_completion(int fd) {
    return fcntl(fd, F_DUPFD_CLOEXEC, 3);
}
