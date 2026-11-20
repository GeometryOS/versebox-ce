// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 VerseBox contributors
//
// Android 12 起，AMS 对"应用自己 fork 出来、绕过 zygote 的子进程"（phantom process）
// 有数量上限（AOSP 默认 32）。这个上限没有任何公开 API 可读，唯一可靠的判据是行为：
// fork 出明显多于上限的子进程，看系统会不会把它们杀掉。
//
// 这里只提供三个最小原语，判定逻辑全部在 Kotlin 侧（io.a10vm.runtime.limits）。
//
// 子进程不 exec、不返回 Java：只留在 native 的 sleep 循环里。这样
//   1) 它是纯 phantom process（不 exec 就不受 exec 白名单/W^X 限制）；
//   2) 它永远不会自己退出，于是"子进程死亡"几乎只可能来自系统清理。

#include <jni.h>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <errno.h>

extern "C" {

/*
 * fork 一个只 sleep 的子进程。
 * 父进程返回子进程 pid（>0）；fork 失败返回 -1。
 * 子进程永远不返回。
 */
JNIEXPORT jint JNICALL
Java_io_a10vm_runtime_limits_PhantomProcessNative_forkNullLoop(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    pid_t pid = fork();
    if (pid == 0) {
        // 父进程被系统杀死时子进程必须一起消失，否则会变成一堆没人管的孤儿。
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        if (getppid() == 1) _exit(0);
        for (;;) {
            sleep(100);
        }
    }
    return (jint) pid;
}

/*
 * 非阻塞地回收任意一个已退出的子进程。
 * 返回 >0：被回收的子进程 pid；返回 0：目前没有子进程退出；返回 -1：已经没有任何子进程。
 */
JNIEXPORT jint JNICALL
Java_io_a10vm_runtime_limits_PhantomProcessNative_waitAnyNoHang(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    int status = 0;
    pid_t r = waitpid(-1, &status, WNOHANG);
    if (r > 0) return (jint) r;
    if (r == 0) return 0;
    return (errno == ECHILD) ? -1 : 0;
}

/*
 * 非阻塞地回收**指定**子进程（只影响这一个 pid）。
 * 返回 >0：已回收；返回 0：还活着；返回 -1：已经不是我们的子进程（已经消失）。
 *
 * 检测路径必须用它而不是 waitAnyNoHang：waitpid(-1, …) 会把虚拟机自己的
 * 子进程一起收掉，那等于破坏正在运行的会话。
 */
JNIEXPORT jint JNICALL
Java_io_a10vm_runtime_limits_PhantomProcessNative_waitPidNoHang(JNIEnv *env, jclass clazz,
                                                               jint pid) {
    (void) env;
    (void) clazz;
    if (pid <= 0) return -1;
    int status = 0;
    pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
    if (r > 0) return (jint) r;
    if (r == 0) return 0;
    return (errno == ECHILD) ? -1 : 0;
}

JNIEXPORT void JNICALL
Java_io_a10vm_runtime_limits_PhantomProcessNative_killPid(JNIEnv *env, jclass clazz, jint pid) {
    (void) env;
    (void) clazz;
    if (pid > 0) kill((pid_t) pid, SIGKILL);
}

/*
 * 阻塞等待指定子进程结束，最多等 timeoutMillis。
 * 返回 pid：已回收；返回 0：超时；返回 -1：该 pid 不是我们的子进程（ECHILD）。
 */
JNIEXPORT jint JNICALL
Java_io_a10vm_runtime_limits_PhantomProcessNative_reapPid(JNIEnv *env, jclass clazz, jint pid,
                                                          jint timeoutMillis) {
    (void) env;
    (void) clazz;
    if (pid <= 0) return -1;
    int status = 0;
    int spent = 0;
    for (;;) {
        pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
        if (r == (pid_t) pid) return pid;
        if (r < 0) return -1;
        if (spent >= timeoutMillis) return 0;
        usleep(5 * 1000);
        spent += 5;
    }
}

} // extern "C"
