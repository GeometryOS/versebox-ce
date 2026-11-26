# Wall of Shame 01 — CI shipped an APK without the window bridge

## Incident

On 2026-11-26 at 21:15:59 (UTC+8), opening a newly created VM on a OnePlus GM1910 crashed the host app on its main thread. The device reported API 31. The first failure was in `WindowBridge.<clinit>` while the startup Activity queried the refresh rate. The Activity caught that initial failure, but the class remained unusable in the process. When Compose constructed `NativeSurfaceView`, the second access terminated the process.

```text
java.lang.NoClassDefFoundError: io.a10vm.nativedisplay.WindowBridge
    at io.a10vm.nativedisplay.NativeSurfaceView.<init>(NativeSurfaceView.java:15)
Caused by: java.lang.UnsatisfiedLinkError: dlopen failed: library "liba10vm-desktop-window.so" not found
    at io.a10vm.nativedisplay.WindowBridge.<clinit>(WindowBridge.java:16)
```

The installed CI APK contained the A10 and A15 desktop GPU host libraries and the Rust logo library. Both window renderer library names and the native display manifest were absent. The locally built APK contained all three entries. This crash occurred after package installation, during host JNI initialization. The separately reported signature rejection on other hosts needs its own package-manager evidence.

## Build failure

The app CI checked out only the app repository. Neither the independent renderer source checkout nor the ignored local renderer output directories existed on a clean runner. The APK assembly commands passed `-PciSkipNativeVerify=true`, which disabled both the module's source-build discovery and renderer validation. ROM staging supplied guest resident libraries. The old audit accepted any nonempty ARM64 library set with a logo library, so the incomplete APK reached the artifact store.

## Correction and release rule

The Debug and Release workflows share a [renderer build action](../../.github/actions/build-host-renderer/action.yml). It checks out the independent A15 repository at the exact commit corresponding to `v1.0.1` and invokes its renderer source build with NDK 29. Both workflows pass the generated directory to Gradle as `nativeDisplayPrepared`. APK assembly runs the existing renderer validation. ROM staging and JVM tests may still use the skip flag because they produce no installable APK.

The [APK verifier](../../tools/verify-app-apk.py) runs before Debug upload, after artifact download, and on both Release profiles before publication. It checks the required members, the ARM64 ELF headers, the manifest sizes and hashes, the renderer alias, and the codec against the packaged A15 GPU host when that peer is present.

**Every installable CI APK must pass the native dependency check on the final ZIP.** Compilation success and a nonempty native library directory do not establish that the startup JNI library is present. Retain the renderer source revision and check its protocol compatibility whenever the paired ROM inputs change.
