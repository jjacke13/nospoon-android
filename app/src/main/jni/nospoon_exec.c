#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/socket.h>

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

    pid_t pid = fork();
    if (pid == 0) {
        // Child — close parent's socket end, keep child's
        close(sockfd[0]);
        // All fds inherited (including child_sock and any TUN fd passed later)
        execv(argv[0], argv);
        _exit(127);
    }

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
