/* 自有单文件路径映射工具；不修改原厂文件/挂载/启动项。仅固定本次HMI与自测路径。 */
#include <stddef.h>
#include <string.h>
#include <unistd.h>
extern int pathmgr_symlink(const char *, const char *);
extern int pathmgr_unlink(const char *);
extern int open(const char *, int, ...);
extern int *__get_errno_ptr(void);
#define BASE "/otaupdate/galaxy-kanzi-field/"
#define OEM "/apps/cluster/FX11_HEV/bin/hmi/GLY_G733/ClusterHMI/cluster-hmi"
static void message(const char *text) { write(1, text, strlen(text)); }
static int error(void) {
    unsigned n=(unsigned)*__get_errno_ptr(); char out[16]; unsigned size=0;
    do { out[size++]=(char)('0'+n%10); n/=10; } while(n && size<15);
    message("PATH_GUARD_ERRNO="); while(size) write(1,&out[--size],1); message("\n"); return 3;
}
static int first(const char *path) {
    int fd=open(path,0); char c=0;
    if(fd<0)return -1;
    long n=read(fd,&c,1);close(fd);return n==1?(unsigned char)c:-1;
}
int main(int argc, char **argv) {
    if(argc!=2)return 2;
    if(!strcmp(argv[1],"map")) {
        if(pathmgr_symlink("/var/data/galaxy-kanzi-field/cluster-hmi-candidate",OEM))return error();
        message("HMI_MEMORY_ALIAS_CREATED\n");return 0;
    }
    if(!strcmp(argv[1],"unmap")) {
        if(pathmgr_unlink(OEM))return error();
        message("HMI_MEMORY_ALIAS_REMOVED\n");return 0;
    }
    if(!strcmp(argv[1],"self-map")) {
        if(first(BASE "path-test-existing")!='A'||first(BASE "path-test-candidate")!='B')return 4;
        if(pathmgr_symlink(BASE "path-test-candidate",BASE "path-test-existing"))return error();
        int value=first(BASE "path-test-existing");
        if(pathmgr_unlink(BASE "path-test-existing"))return error();
        if(value!='B'||first(BASE "path-test-existing")!='A')return 5;
        message("PATH_SELF_MAP_AND_RESTORE_PASS\n");return 0;
    }
    return 2;
}
