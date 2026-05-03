package com.nospoon.vpn

object NativeHelper {
    init {
        System.loadLibrary("nospoon_exec")
    }

    // Fork+exec preserving all fds. Creates a Unix socketpair for IPC.
    // Returns int[3] = { pid, ipc_parent_fd, child_ipc_fd_number }
    @JvmStatic
    external fun exec(args: Array<String>): IntArray?

    // Send a file descriptor over a Unix socket (SCM_RIGHTS).
    // Used to pass TUN fd to the child process after VPN is established.
    @JvmStatic
    external fun sendFd(socketFd: Int, fdToSend: Int): Boolean

    // Send SIGTERM to child process.
    @JvmStatic
    external fun kill(pid: Int)

    // Non-blocking waitpid. Returns:
    //   -2 = still running
    //   -1 = error
    //   0+ = exit code
    @JvmStatic
    external fun waitpid(pid: Int): Int
}
