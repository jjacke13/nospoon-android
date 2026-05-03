#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <stdint.h>
#include <pthread.h>
#include <errno.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <android/log.h>

// Reader thread: pulls bytes from the read end of a pipe whose write end is
// dup2'd onto the child's stdout/stderr, and forwards each line to logcat
// under the tag "nospoon-child". Without this the child's fprintf(stderr,…)
// output disappears — Android does not route fork+exec'd children's stdio
// to logcat by default, so any startup error message is lost.
static void *child_log_reader(void *arg) {
    int fd = (int)(intptr_t)arg;
    char buf[2048];
    char line[2048];
    size_t line_len = 0;
    ssize_t n;

    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            char c = buf[i];
            if (c == '\n' || line_len >= sizeof(line) - 1) {
                line[line_len] = '\0';
                if (line_len > 0) {
                    __android_log_print(ANDROID_LOG_INFO, "nospoon-child", "%s", line);
                }
                line_len = 0;
                if (c != '\n' && c != '\r') line[line_len++] = c;
            } else if (c != '\r') {
                line[line_len++] = c;
            }
        }
    }
    if (line_len > 0) {
        line[line_len] = '\0';
        __android_log_print(ANDROID_LOG_INFO, "nospoon-child", "%s", line);
    }
    close(fd);
    return NULL;
}

// Build a pipe whose read end is consumed by a detached logger thread.
// Returns the write-end fd (to be dup2'd in the child) or -1 on failure.
static int spawn_log_pipe(void) {
    int p[2];
    if (pipe(p) < 0) return -1;
    pthread_t tid;
    if (pthread_create(&tid, NULL, child_log_reader, (void *)(intptr_t)p[0]) != 0) {
        close(p[0]);
        close(p[1]);
        return -1;
    }
    pthread_detach(tid);
    return p[1];
}

// Fork+exec preserving all fds. Creates a Unix socketpair for IPC
// (status messages from child + TUN fd passing via SCM_RIGHTS).
//
// Returns int[3] = { pid, ipc_parent_fd, child_ipc_fd_number }
// The child receives its socketpair end as an inherited fd.
// Kotlin passes it via --fd-socket=<N> argument.

JNIEXPORT jintArray JNICALL
Java_com_nospoon_vpn_NativeHelper_exec(JNIEnv *env, jclass cls, jobjectArray args) {
    int argc = (*env)->GetArrayLength(env, args);
    if (argc < 1) return NULL;

    char **argv = (char **) calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring jstr = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        const char *str = (*env)->GetStringUTFChars(env, jstr, NULL);
        argv[i] = strdup(str);
        (*env)->ReleaseStringUTFChars(env, jstr, str);
    }
    argv[argc] = NULL;

    // Unix socketpair for bidirectional IPC
    int sockfd[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, sockfd) < 0) {
        for (int i = 0; i < argc; i++) free(argv[i]);
        free(argv);
        return NULL;
    }

    int child_sock = sockfd[1];

    // Replace "CHILD_SOCK" placeholder in argv with actual fd number
    char fd_str[16];
    snprintf(fd_str, sizeof(fd_str), "%d", child_sock);
    for (int i = 0; i < argc; i++) {
        if (strstr(argv[i], "CHILD_SOCK")) {
            char *old = argv[i];
            // Replace --fd-socket=CHILD_SOCK with --fd-socket=<N>
            char newarg[256];
            snprintf(newarg, sizeof(newarg), "--fd-socket=%d", child_sock);
            argv[i] = strdup(newarg);
            free(old);
        }
    }

    // Set up a pipe to capture the child's stdout/stderr → logcat. Created
    // before fork so both ends are inherited and the dup2 in the child works.
    int log_write_fd = spawn_log_pipe();

    pid_t pid = fork();
    if (pid == 0) {
        // Child — close parent's socket end, keep child's
        close(sockfd[0]);
        if (log_write_fd >= 0) {
            dup2(log_write_fd, STDOUT_FILENO);
            dup2(log_write_fd, STDERR_FILENO);
            close(log_write_fd);
        }
        // All fds inherited (including child_sock and any TUN fd passed later)
        execv(argv[0], argv);
        // execv failed — write error directly via __android_log so we see it
        // even if stdio redirection didn't work.
        __android_log_print(ANDROID_LOG_ERROR, "nospoon-child",
                            "execv(%s) failed: errno=%d", argv[0], errno);
        _exit(127);
    }

    // Parent — close our copy of the pipe write end so the reader thread sees
    // EOF when the child exits. The child still has its dup2'd copy.
    if (log_write_fd >= 0) close(log_write_fd);

    // Parent — close child's socket end
    close(sockfd[1]);

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    jintArray result = (*env)->NewIntArray(env, 3);
    jint vals[3] = { (jint) pid, (jint) sockfd[0], (jint) child_sock };
    (*env)->SetIntArrayRegion(env, result, 0, 3, vals);
    return result;
}

// Send a file descriptor over a Unix socket using SCM_RIGHTS.
// Used to pass TUN fd to child after VPN is established.
JNIEXPORT jboolean JNICALL
Java_com_nospoon_vpn_NativeHelper_sendFd(JNIEnv *env, jclass cls, jint sockFd, jint fdToSend) {
    char dummy = 0;
    struct iovec iov = { .iov_base = &dummy, .iov_len = 1 };

    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    memset(cmsgbuf, 0, sizeof(cmsgbuf));

    struct msghdr msg = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = cmsgbuf,
        .msg_controllen = sizeof(cmsgbuf)
    };

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fdToSend, sizeof(int));

    return sendmsg(sockFd, &msg, 0) >= 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_nospoon_vpn_NativeHelper_kill(JNIEnv *env, jclass cls, jint pid) {
    if (pid > 0) kill((pid_t) pid, SIGTERM);
}

JNIEXPORT jint JNICALL
Java_com_nospoon_vpn_NativeHelper_waitpid(JNIEnv *env, jclass cls, jint pid) {
    int status = 0;
    pid_t result = waitpid((pid_t) pid, &status, WNOHANG);
    if (result == 0) return -2;
    if (result < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}
