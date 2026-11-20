plugins { id("com.android.library") }

/*
 * 只做一件事：把 fork()/waitpid()/kill() 暴露给宿主进程。
 *
 * 为什么要 C++：Android 12 起的 phantom process 限制按"应用自己 fork 出来、绕过 zygote 的
 * 子进程"计数，公开 SDK 没有 fork()/waitpid()（android.system.Os 只公开 getpid/kill），
 * 只能走 JNI。子进程不 exec、不碰 ART，只在 native 里 sleep，保证它永远不会自己退出，
 * 于是"子进程死亡"几乎只可能来自系统清理。
 */
android {
    namespace = "io.a10vm.phantom"
    compileSdk = 37
    compileSdkMinor = 0
    ndkVersion = "28.1.13356709"

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // 子进程里不跑 C++ 运行时，去掉 libc++ 依赖，减小体积也少一层出错面。
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
