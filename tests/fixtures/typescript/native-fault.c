/* Isolated test SDK proxy. Compiler identity and ordinary operations come from
 * the actual pinned Go library. Faults happen only inside the supervised worker. */
#include "codeana_ts.h"
#include <dlfcn.h>
#include <fcntl.h>
#include <glob.h>
#include <signal.h>
#include <stdio.h>
#include <sys/mman.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef FAULT_MODE
#error explicit FAULT_MODE required
#endif
#ifndef ENGINE_LIBRARY
#error explicit ENGINE_LIBRARY required
#endif
#ifndef GRAPH_PATTERN
#error explicit GRAPH_PATTERN required
#endif
static void *engine;
static void *symbol(const char *name) {
    if (!engine) engine = dlopen(ENGINE_LIBRARY, RTLD_NOW | RTLD_LOCAL);
    if (!engine) _exit(87);
    void *result = dlsym(engine, name);
    if (!result) _exit(88);
    return result;
}
#define FORWARD(ret, name, args, values) \
    ret name args { typedef ret (*function) args; return ((function)symbol(#name)) values; }
FORWARD(uint32_t, codeana_ts_abi_version, (void), ())
FORWARD(int32_t, codeana_ts_runtime_identity, (uint64_t o,uint8_t *d,uint32_t c,uint32_t *w),(o,d,c,w))
FORWARD(int32_t, codeana_ts_session_create,(uint64_t i,uint64_t r,uint64_t *s),(i,r,s))
FORWARD(int32_t, codeana_ts_input_chunk,(uint64_t s,uint8_t *p,uint32_t n),(s,p,n))
FORWARD(int32_t, codeana_ts_seal_input,(uint64_t s),(s))
FORWARD(int32_t, codeana_ts_set_completion_fd,(uint64_t s,int32_t f),(s,f))
FORWARD(int32_t, codeana_ts_poll_event,(uint64_t s,uint32_t *p),(s,p))
FORWARD(int32_t, codeana_ts_cancel,(uint64_t s),(s))
int32_t codeana_ts_publish_ack(uint64_t session) {
    if (!strcmp(FAULT_MODE,"ack")) return CODEANA_TS_PANIC;
    typedef int32_t (*function)(uint64_t);
    return ((function)symbol("codeana_ts_publish_ack"))(session);
}
FORWARD(int32_t, codeana_ts_session_close,(uint64_t s,uint32_t t),(s,t))
static void entered(void) {
    int barrier = open(BARRIER_PATH,O_WRONLY | O_CREAT | O_TRUNC,0600);
    if (barrier >= 0) {
        int64_t pid = (int64_t)getpid();
        write(barrier,&pid,sizeof(pid));
        close(barrier);
    }
}
int32_t codeana_ts_analyze_start(uint64_t session) {
    if (strcmp(FAULT_MODE,"observe")) entered();
    if (!strcmp(FAULT_MODE,"crash")) { raise(SIGSEGV); _exit(89); }
    if (!strcmp(FAULT_MODE,"fatal")) _exit(86);
    if (!strcmp(FAULT_MODE,"hang")) return CODEANA_TS_OK;
    if (!strcmp(FAULT_MODE,"rss")) {
        size_t size = (size_t)256 << 20;
        volatile unsigned char *data = mmap(NULL,size,PROT_READ | PROT_WRITE,MAP_PRIVATE | MAP_ANON,-1,0);
        if (data == MAP_FAILED) _exit(90);
        for (size_t page=0;page<size;page+=4096) data[page]=1;
        for (;;) pause();
    }
    if (!strcmp(FAULT_MODE,"scratch")) {
        glob_t paths = {0};
        if (glob(SCRATCH_PATTERN,0,NULL,&paths) || !paths.gl_pathc) _exit(91);
        char file[4096];
        snprintf(file,sizeof(file),"%s/oversized.tmp",paths.gl_pathv[0]);
        int fd = open(file,O_WRONLY | O_CREAT | O_TRUNC,0600);
        if (fd<0) _exit(92);
        char data[4096]={0};
        for (int page=0;page<512;page++) if (write(fd,data,sizeof(data)) != (ssize_t)sizeof(data)) _exit(93);
        close(fd);globfree(&paths);
        for (;;) pause();
    }
    typedef int32_t (*function)(uint64_t);
    int32_t result = ((function)symbol("codeana_ts_analyze_start"))(session);
    if (!strcmp(FAULT_MODE,"observe")) entered();
    return result;
}
static int changed;
int32_t codeana_ts_read_result_chunk(uint64_t s,uint64_t o,uint8_t *d,uint32_t c,uint32_t *w) {
    if (!changed && o == 0) {
        glob_t paths = {0};
        if (!glob(GRAPH_PATTERN,0,NULL,&paths)) {
            for (size_t i=0;i<paths.gl_pathc;i++) {
                int fd = open(paths.gl_pathv[i],O_RDWR | O_NOFOLLOW);
                struct stat info;
                if (fd>=0 && !fstat(fd,&info) && S_ISREG(info.st_mode) && info.st_size>4) {
                    if (!strcmp(FAULT_MODE,"truncate")) ftruncate(fd,info.st_size-1);
                    if (!strcmp(FAULT_MODE,"append")) { lseek(fd,0,SEEK_END); write(fd,"!",1); }
                    if (!strcmp(FAULT_MODE,"corrupt")) { unsigned char byte=0; pread(fd,&byte,1,info.st_size-1); byte^=1; pwrite(fd,&byte,1,info.st_size-1); }
                    changed=1;
                }
                if (fd>=0) close(fd);
            }
        }
        globfree(&paths);
    }
    typedef int32_t (*function)(uint64_t,uint64_t,uint8_t *,uint32_t,uint32_t *);
    return ((function)symbol("codeana_ts_read_result_chunk"))(s,o,d,c,w);
}
