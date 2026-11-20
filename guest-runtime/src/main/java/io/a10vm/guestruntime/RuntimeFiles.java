package io.a10vm.guestruntime;

import android.content.Context;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Map;

final class RuntimeFiles {
    private final Context context;
    private final StartupLog startupLog;
    private final String manifestName;
    private final int expectedSdk;
    private final String label;
    private String assetPrefix = "guest-runtime/";
    /** ROM 的 payload 里"数据根"的前缀；romRoot == null 时就是 assets 前缀。 */
    final File nativeDirectory, directory;
    /** 已解包的 ROM 目录；null = 走 APK 的 assets（旧路径，行为逐字不变）。 */
    private final File romRoot;
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> libPaths = new HashMap<>();
    /**
     * APK assets 里旧格式 payload 的 lib 目录（带结尾斜杠，例如 `guest-runtime-a15/lib/`）。
     *
     * <p>从 2026-11-11 起客体 .so 不再放进 APK 的 `lib/arm64-v8a`：新格式 ROM 把它们放在
     * `payload/lib/`（清单 `libPath`），旧格式（打进 APK 的那种）放在
     * `assets/<prefix>/lib/`，启动时**一次**解包到 `files/romlibs/<prefix>/`。
     * 只有连 `libPath` 都没有的旧清单（A9/A10）才继续用 APK 的 lib 目录。
     */
    private final String payloadLibPrefix;
    /** 旧格式 payload 的 lib 在宿主可写区的解包目录：一台手机上所有实例共用这一份。 */
    private final File libCache;
    /**
     * 本实例的 .so 落地目录（`<instance>/vm/libs/<name>`）：ROM 里的那份**硬链接**进来。
     *
     * <p>为什么必须落到实例里而不是直接用 ROM 的 `payload/lib`
     * （2026-11-11 真机踩过，别改回去）：
     * <ol>
     *   <li>客体自己的 `open()`/`execve()` 走 `GuestPaths::hostPath()`，它只认
     *       `A10VM_HOST_ROOT` 与 `A10VM_STATE_ROOT` 两个前缀；`files/roms/<key>/payload/lib`
     *       不在其中，会被当成**客体路径**再拼一次 rootfs → `error=2`；</li>
     *   <li>`RuntimeIdentity` 拿 `nativeFile()` 的结果和 `/proc/<pid>/maps` 对账，
     *       实际打开的路径必须就是它返回的路径。</li>
     * </ol>
     * 硬链接保证"实例里有真文件"但**不复制字节**（同卷 0 额外空间）；跨卷才退回复制。
     */
    private final File instanceLibs;
    /** .so 的物理根目录（ROM 的 payload/lib 或上面的解包目录）；linker/ld.config 要用。 */
    private final java.util.LinkedHashSet<File> libRoots = new java.util.LinkedHashSet<>();
    private final java.util.Set<String> libFallbackLogged = new java.util.HashSet<>();
    /** 只能从 APK 的 lib/ 里 execve 的条目（清单 files[].resident，见 rom/resident-native.txt）。 */
    private final java.util.Set<String> resident = new java.util.HashSet<>();
    private boolean libsReady;
    private JSONObject manifest;

    RuntimeFiles(Context context, File directory, StartupLog startupLog) {
        this(context, directory, startupLog, "guest-runtime-inputs.json", 29, "Android 10", null);
    }

    RuntimeFiles(Context context, File directory, StartupLog startupLog, String manifestName,
                 int expectedSdk, String label) {
        this(context, directory, startupLog, manifestName, expectedSdk, label, null);
    }

    RuntimeFiles(Context context, File directory, StartupLog startupLog, String manifestName,
                 int expectedSdk, String label, File romRoot) {
        this.context = context.getApplicationContext();
        this.startupLog = startupLog;
        this.manifestName = manifestName;
        this.expectedSdk = expectedSdk;
        this.label = label;
        this.romRoot = romRoot;
        nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        this.directory = directory;
        String manifestBase = manifestName.endsWith("-inputs.json")
                ? manifestName.substring(0, manifestName.length() - "-inputs.json".length())
                : manifestName;
        payloadLibPrefix = manifestBase + "/lib/";
        libCache = new File(this.context.getFilesDir(), "romlibs/" + manifestBase.replace('/', '_'));
        instanceLibs = new File(directory, "libs");
    }

    String assetPrefix() throws Exception {
        inputs();
        return assetPrefix;
    }

    /**
     * 逻辑名 → 真实文件。
     *
     * <p>解析顺序（`docs/rom-package/04` §4）：
     * <ol>
     *   <li>清单有 `libPath` 且 `romRoot` 里真有那个文件 → ROM 的 `payload/lib/<name>`；</li>
     *   <li>否则清单有 `libPath` → 旧格式 payload 解包目录 `files/romlibs/<prefix>/<name>`
     *       （`libMode=apk` 的老 ROM 包、以及内置在 APK assets 里的旧格式，都走这一条）；</li>
     *   <li>清单连 `libPath` 都没有（A9/A10 的老清单）→ `nativeLibraryDir/<name>`，逐字不变。</li>
     * </ol>
     * 从 ROM 掉到旧格式时会记一行 `WARN`：不静默降级，日志里能查到是哪一条。
     */
    File nativeFile(String logical) throws Exception {
        inputs();
        String name = names.get(logical);
        if (name == null) throw new IllegalArgumentException("Missing runtime input: " + logical);
        File fromApk = new File(nativeDirectory, name);
        // APK 的 lib/ 里有的，一律先用它。原因只有一条、而且是平台事实：
        // App 进程的 SELinux 域（untrusted_app）对 app_data_file 有 execute 但没有
        // execute_no_trans，所以 **execve** 一个 /data/... 下的 ELF 会 EACCES（真机实测
        // error=13），而 dlopen()/mmap(PROT_EXEC) 是允许的。于是"能被 execve 的文件"
        // 只能来自 APK；其余 1000+ 个库不在 APK 里，自然走 ROM/payload。
        // 清单里的 files[].resident 是同一件事的**显式声明**（rom/resident-native.txt），
        // 缺了就直接报错，不再往下猜。字节一致性由 verify() 用清单 sha256 兜住：
        // 一旦 ROM 换了不同字节的 linker，会以「ROM 校验失败」停下，而不是静默跑旧的。
        if (fromApk.isFile()) return fromApk.getCanonicalFile();
        if (resident.contains(logical))
            throw new IllegalStateException("APK 不完整：" + name
                    + " 必须留在 APK 的 lib/ 目录（execve 只能从那里成功，见 docs/rom-package/04 §3.2）");
        if (!libPaths.containsKey(logical)) {
            // 老清单（`libMode=apk` 的 ROM 包 / A9/A10 内置 assets）：客体 .so 本来就在 APK 的 lib/ 里。
            //
            // **但不能无条件返回 fromApk**：APK 现在只带**当前那一版 ROM 的 resident 库**
            // （构建期 `apk-resident-libs-<id>.tar`），一旦用户降到/换到另一个版本的 ROM 包，
            // 这个逻辑名在 APK 里根本不存在，verify() 就会拿一个不存在的路径去比对，
            // 最后报成「ROM 校验失败：liba15vm-guest-libc.so」——把「APK 与 ROM 版本不匹配」
            // 说成了「ROM 坏了」（2026-11-15 真机：降级 ROM 后 A15 起不来）。
            //
            // 所以先看新格式的 payload/lib 有没有同名文件（有就能 dlopen，只是不能 execve），
            // 再看实例里已经硬链接好的那一份；都没有才返回 APK 路径，让 verify() 报出可解释的话。
            if (!fromApk.isFile()) {
                if (romRoot != null) {
                    File inPayload = new File(romRoot, "payload/lib/" + name);
                    if (inPayload.isFile()) return inPayload.getCanonicalFile();
                }
                File staged = new File(instanceLibs, name);
                if (staged.isFile()) return staged.getCanonicalFile();
            }
            return fromApk.getCanonicalFile();
        }
        ensureLibs();
        File staged = new File(instanceLibs, name);
        if (staged.isFile()) return staged.getCanonicalFile();
        if (fromApk.isFile()) return fromApk.getCanonicalFile();
        return staged.getCanonicalFile();
    }

    /** .so 的物理根目录（可能为空：A9/A10 的老清单仍然只有 APK 的 lib 目录）。 */
    java.util.Set<File> libRoots() throws Exception {
        inputs();
        ensureLibs();
        return libRoots;
    }

    /**
     * linker 的 `A10VM_HOST_ROOT`：**必须是 APK 的 `nativeLibraryDir`**，不能改。
     *
     * <p>2026-11-11 试过把它指到"客体 .so 的物理根"（payload/lib），结果整个客体起不来：
     * §BT§GuestPaths::hostPath()§BT§ 只认 `A10VM_HOST_ROOT` 与 `A10VM_STATE_ROOT` 两个前缀，
     * 而客体进程里的 `execve` 是被我们补过丁的 libc（`vendor/runtime/filesystem/GuestExec.cpp`）
     * 接管的：linker 的绝对路径（在 APK 的 lib/ 里）一旦不再被认成宿主路径，就会被当成客体路径
     * 重写成 `<rootfs>/data/app/…/lib/arm64/ld-android.so` → `error=2, No such file or directory`，
     * 于是 zygote 起不了任何应用进程（真机日志：`a15_zygote_launch_failed=…ld-android.so … error=2`）。
     *
     * <p>payload/lib 里的库不需要这条：它们由 linker 用原始 syscall 打开，不经过 libc 的路径层；
     * linker 自己的准入靠 `ld.config.txt` 的 permitted/search（见 `GuestRuntime.prepare()`）。
     */
    File hostRoot() {
        return nativeDirectory;
    }

    /**
     * 把清单里带 `libPath` 的条目解析成"物理根"，必要时把旧格式 payload 解包出来。
     *
     * <p>幂等判据与 `installData()` 完全一致（字节数 + SHA-256）：旧格式的 .so 从
     * `assets/<prefix>/lib/` 读到 `files/romlibs/<prefix>/`，一次解包、多实例共用，
     * 落地后 `chmod 0755`（linker 要 exec 它们，`docs/rom-package/02` §4.1）。
     * 清单里没有 `libPath`（A9/A10 老清单）时**什么都不做**，行为逐字不变。
     */
    private void ensureLibs() throws Exception {
        if (libsReady) return;
        libsReady = true;
        java.util.List<JSONObject> declared = declaredLibs(inputs());
        if (declared.isEmpty() && romRoot != null) {
            // 迁移路径：老 ROM 包（libMode=apk）的清单没有 libPath，而它的客体 .so 当年就在
            // APK 的 lib/ 里。现在 lib/ 里没有了，所以拿 APK 侧 payload 的那份补齐——
            // 否则这些实例会在下次启动时因为没有 .so 而开不了机，而"修好它们"不该让用户
            // 手动重新导入（_audit/09 §4：新机制必须自带自愈）。
            JSONObject apkManifest = assetManifest();
            if (apkManifest != null) declared = declaredLibs(apkManifest);
            if (!declared.isEmpty())
                startupLog.record("LIB", "ROM 包没有 libPath，改用 APK payload 的客体 .so");
        }
        if (declared.isEmpty()) return;
        Files.createDirectories(instanceLibs.toPath());
        // 先确保旧格式 payload 里那批已经解包（ROM 里有的条目不需要）。
        // 判据用**便宜的** size/缺失检查：真正的一致性校验在 libSource() 里按条做，
        // 只有实例里那份与清单不符的条目才会走到那里，正常启动是 0 次哈希。
        boolean needCache = false;
        for (JSONObject item : declared) {
            // resident 的条目永远从 APK 的 lib/ 取（execve 只能从那里成功），不进缓存，
            // 也不该参与“缓存是否过期”的判断——否则每次启动都因为“它不在”而重扫 478 MB。
            if (resident.contains(item.optString("logical", ""))) continue;
            if (romHasLib(item)) continue;
            File cached = new File(libCache, item.getString("name"));
            if (!cached.isFile() || cached.length() != item.getLong("bytes")) { needCache = true; break; }
        }
        if (needCache) {
            // resident 的条目本来就不在 payload 里（它们在 APK 的 lib/），
            // 传进去只会每条刷一颗误导性的 `payload-lib-missing`（2026-11-12 真机日志每条靴都有）。
            java.util.List<JSONObject> cacheable = new java.util.ArrayList<>();
            for (JSONObject item : declared)
                if (!resident.contains(item.optString("logical", ""))) cacheable.add(item);
            extractPayloadLibs(cacheable);
        }
        int staged = 0;
        for (JSONObject item : declared) {
            String name = item.getString("name");
            if (resident.contains(item.optString("logical", ""))) continue;   // 见上：resident 归 APK
            File target = new File(instanceLibs, name);
            if (matches(target, item)) continue;
            File source = libSource(item);
            if (source == null && romRoot == null) {
                // 旧格式：同尺寸不同字节这种“便宜检查”漏掉的条目，在这里按条补解包一次。
                // 只处理这一条，避免为一个文件把 478 MB 全部重算。
                extractPayloadLibs(java.util.Collections.singletonList(item));
                source = libSource(item);
            }
            if (source == null) {
                if (libFallbackLogged.add("source:" + name))
                    startupLog.record("WARN", "rom-lib-missing " + item.optString("libPath", name));
                continue;
            }
            Files.deleteIfExists(target.toPath());
            try {
                // 同卷硬链接：实例里有一份"真文件"，但不占第二份字节。
                Files.createLink(target.toPath(), source.toPath());
            } catch (Exception crossDevice) {
                Files.copy(source.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Os.chmod(target.getPath(), 0755);
            // 逐条哈希留给紧接着的 verify()：它读的就是这个落地文件，再算一次等于白跑 628 MB。
            staged++;
            if (staged == 1 || staged % 256 == 0 || staged == declared.size())
                startupLog.record("LIB", "instance libs " + staged + "/" + declared.size());
        }
        libRoots.add(instanceLibs.getCanonicalFile());
    }

    /**
     * 条目的源头：ROM 的 payload/lib 优先，其次旧格式 payload 的解包目录。
     *
     * <p>**缓存必须与清单逐条一致**（2026-11-12 真机踩过，别改回去）：旧格式的解包目录
     * （`files/romlibs/<prefix>/`）是**派生**目录，源头是 APK 的 assets。换一次 APK，
     * 里面某几个 `.so` 会变；如果这里只判 `isFile()`，就会把上一版 APK 的旧字节硬链接进实例，
     * `verify()` 也跟着拿旧字节对清单——结果是「补丁明明打进了 APK，设备上跑的却是旧库」。
     * 实测：`liba9vm-logwrap.so` 设备 5,984 B / 清单 7,488 B，连续两次启动都没刷新。
     */
    private File libSource(JSONObject item) throws Exception {
        if (romRoot != null) {
            String libPath = item.optString("libPath", "");
            if (!libPath.isEmpty()) {
                File fromRom = new File(romRoot, libPath);
                if (fromRom.isFile()) return fromRom;
            }
        }
        File cached = new File(libCache, item.getString("name"));
        return matches(cached, item) ? cached : null;
    }

    /** ROM 里有没有这一条的现成文件（不校验字节，只回答“要不要用旧格式缓存”）。 */
    private boolean romHasLib(JSONObject item) {
        if (romRoot == null) return false;
        String libPath = item.optString("libPath", "");
        if (libPath.isEmpty()) return false;
        return new File(romRoot, libPath).isFile();
    }

    /** 把旧格式 payload（APK 的 assets/<prefix>/lib/**）解包到 files/romlibs/<prefix>/**。 */
    private void extractPayloadLibs(java.util.List<JSONObject> declared) throws Exception {
        Files.createDirectories(libCache.toPath());
        int unpacked = 0;
        for (JSONObject item : declared) {
            String name = item.getString("name");
            File target = new File(libCache, name);
            if (matches(target, item)) continue;
            // 逐块流式拷贝 + 边写边算哈希：最大的那条是 110 MB，用 byte[] 一次性读会
            // 在低内存机型上直接把 prepare 打死（ByteArrayOutputStream 还要再翻倍）。
            File staging = new File(libCache, name + ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = context.getAssets().open(payloadLibPrefix + name);
                 java.io.OutputStream output = Files.newOutputStream(staging.toPath())) {
                byte[] chunk = new byte[1 << 16];
                int count;
                while ((count = input.read(chunk)) != -1) {
                    digest.update(chunk, 0, count);
                    output.write(chunk, 0, count);
                    total += count;
                }
            } catch (java.io.IOException missing) {
                Files.deleteIfExists(staging.toPath());
                if (libFallbackLogged.add("assets:" + name))
                    startupLog.record("WARN", "payload-lib-missing " + payloadLibPrefix + name);
                continue;
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format("%02x", value & 0xff));
            if (total != item.getLong("bytes") || !hash.toString().equals(item.getString("sha256"))) {
                Files.deleteIfExists(staging.toPath());
                throw new IllegalStateException("ROM 校验失败：" + name + "（APK 里的旧格式 payload 与清单不符）");
            }
            Files.deleteIfExists(target.toPath());
            Files.move(staging.toPath(), target.toPath());
            Os.chmod(target.getPath(), 0755);
            unpacked++;
            if (unpacked == 1 || unpacked % 128 == 0 || unpacked == declared.size())
                startupLog.record("LIB", "payload libs " + unpacked + "/" + declared.size());
        }
    }

    /** 清单里带 `libPath` 的那些条目（没有就返回空表 = 老清单）。 */
    private static java.util.List<JSONObject> declaredLibs(JSONObject manifest) {
        java.util.List<JSONObject> declared = new java.util.ArrayList<>();
        JSONArray files = manifest == null ? null : manifest.optJSONArray("files");
        for (int i = 0; files != null && i < files.length(); ++i) {
            JSONObject item = files.optJSONObject(i);
            if (item != null && !item.optString("libPath", "").isEmpty()) declared.add(item);
        }
        return declared;
    }

    /** APK 侧那份同名清单（旧格式 payload 的来源）；读不到就是 null。 */
    private JSONObject assetManifest() {
        try (InputStream input = context.getAssets().open(manifestName)) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1 << 16];
            int count;
            while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception missing) {
            return null;
        }
    }

    /** 清单条目 → 真实文件；有 logical 就走 {@link #nativeFile(String)}，否则沿用旧拼法。 */
    private File nativeOf(JSONObject item) throws Exception {
        String logical = item.optString("logical", "");
        if (!logical.isEmpty()) return nativeFile(logical);
        return new File(nativeDirectory, item.getString("name")).getCanonicalFile();
    }

    /** Resolves a platform role (propertyClient, binderDevices, ...) through the manifest. */
    File nativeRole(String role) throws Exception {
        return nativeFile(platformLogical(role));
    }

    String platformLogical(String role) throws Exception {
        JSONObject roles = inputs().optJSONObject("platformLibraries");
        String logical = roles == null ? null : roles.optString(role, null);
        return logical == null ? legacyRole(role) : logical;
    }

    int frameworkSdk() throws Exception {
        return inputs().optInt("frameworkSdk", 29);
    }

    private static String legacyRole(String role) {
        switch (role) {
            case "propertyClient": return "liba10vm-property-client.so";
            case "guestDevices": return "liba10vm-guest-devices.so";
            case "binderDevices": return "liba10vm-binder-devices.so";
            case "guestLog": return "liba10vm-guest-log.so";
            case "managerPolicy": return "liba10vm-manager-policy.so";
            case "binderCore": return "liba10binder.so";
            case "halModules": return "liba10vm-hal-modules.so";
            default: throw new IllegalArgumentException("Unknown platform role: " + role);
        }
    }

    JSONObject inputs() throws Exception {
        if (manifest == null) {
            manifest = new JSONObject(new String(readManifestBytes(), StandardCharsets.UTF_8));
            if (romRoot != null) {
                // ROM 包里的清单没有 assetPrefix：数据根就是 payload/（docs/rom-package/03 §1.2）。
                assetPrefix = manifest.optString("payloadRoot", "payload/");
                if (!assetPrefix.endsWith("/")) assetPrefix = assetPrefix + "/";
            } else {
                assetPrefix = manifest.optString("assetPrefix", "guest-runtime/");
            }
            JSONArray libraries = manifest.getJSONArray("files");
            for (int i = 0; i < libraries.length(); ++i) {
                JSONObject item = libraries.getJSONObject(i);
                String logical = item.getString("logical");
                names.put(logical, item.getString("name"));
                String libPath = item.optString("libPath", "");
                if (!libPath.isEmpty()) libPaths.put(logical, libPath);
                if (item.optBoolean("resident", false)) resident.add(logical);
            }
        }
        return manifest;
    }

    /** 清单来源：ROM 目录优先，否则 APK 的 assets（旧路径）。 */
    private byte[] readManifestBytes() throws Exception {
        if (romRoot != null) {
            File file = new File(romRoot, manifestName);
            if (!file.isFile())
                throw new IllegalStateException("ROM 不完整：缺少 " + manifestName + "（" + romRoot.getName() + "）");
            return Files.readAllBytes(file.toPath());
        }
        try (InputStream input = context.getAssets().open(manifestName)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toByteArray();
        }
    }

    private boolean matches(File file, JSONObject item) throws Exception {
        if (!file.isFile() || file.length() != item.getLong("bytes")) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        } catch (java.io.IOException unreadable) {
            // A previous pass may have applied the image's 000 mode before a later
            // failure; make the caller reinstall the bytes instead of failing here.
            return false;
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format("%02x", value & 0xff));
        return hash.toString().equals(item.getString("sha256"));
    }

    /**
     * 把「这条对不上」说成人能照着做的一句话。
     *
     * 三种情况必须分开（以前全说成「ROM 校验失败」，害得维护者以为是 ROM 坏了）：
     *  1. 文件根本不在 → **APK 与 ROM 版本不匹配**（老格式 ROM 指望 APK 的 lib/，
     *     而安装包只带 resident 库）；
     *  2. 文件在但字节不符 → 真的内容不一致（解包不完整 / 换过库）；
     *  3. 老清单 + 没有 romRoot（旧 assets 实例）→ 沿用原来的字面。
     */
    private String describeMismatch(JSONObject item, File actual) throws Exception {
        String name = item.getString("name");
        boolean legacyWord = romRoot == null;
        if (!actual.isFile()) {
            if (legacyWord) return "Runtime input mismatch: " + name;
            String logical = item.optString("logical", "");
            boolean inApk = !logical.isEmpty() && new File(nativeDirectory, name).isFile();
            return "ROM 与安装包不匹配：缺少 " + name
                    + (inApk ? "" : "（APK 的 lib/ 里没有它：老格式 ROM 依赖 APK 携带客体 .so，"
                        + "而当前安装包只带当前版本的 resident 库）")
                    + "。请到 ROM 管理里换成与安装包匹配的版本，或导入这个版本的新格式 ROM 包。";
        }
        return (legacyWord ? "Runtime input mismatch: " : "ROM 校验失败：") + name
                + "（字节与清单不符：实际 " + actual.length() + " B / 清单 " + item.getLong("bytes") + " B）";
    }

    void verify() throws Exception {
        JSONObject inputs = inputs();
        ensureLibs();
        if (inputs.getInt("guestSdk") != expectedSdk || !inputs.getBoolean("loaderFromSource") ||
                !inputs.getBoolean("frameworkIntegrated") || inputs.getBoolean("rootRequired") ||
                !inputs.getBoolean("desktopImplemented"))
            throw new IllegalStateException("Unexpected " + label + " runtime manifest");
        JSONArray files = inputs.getJSONArray("files");
        String apkLibRoot = nativeDirectory.getCanonicalPath() + File.separator;
        int apkWarnings = 0;
        for (int i = 0; i < files.length(); ++i) {
            JSONObject item = files.getJSONObject(i);
            File actual = nativeOf(item);
            if (!matches(actual, item)) {
                // APK 的 lib/ 里那份由安装包提供，不由 ROM 提供，所以换 ROM 版本或换安装包必然不一致。
                // 这一条只警告，否则一份旧 ROM 会因为安装包带了新库而根本起不来；ROM 自己提供的
                // 文件仍然严格，字节不符就是要停下。
                if (actual.getCanonicalPath().startsWith(apkLibRoot)) {
                    ++apkWarnings;
                    if (apkWarnings <= 32)
                        startupLog.record("WARN", "apk-lib-mismatch " + item.getString("name")
                                + "（安装包的 lib/ " + (actual.isFile() ? actual.length() + " B" : "缺这个文件")
                                + " / 清单 " + item.getLong("bytes") + " B），按警告继续");
                    continue;
                }
                throw new IllegalStateException(describeMismatch(item, actual));
            }
            if ((i + 1) % 128 == 0 || i + 1 == files.length())
                startupLog.record("VERIFY", "runtime files " + (i + 1) + "/" + files.length());
        }
        if (apkWarnings > 0)
            startupLog.record("WARN", "apk-lib-mismatch 共 " + apkWarnings + " 条，来自安装包而不是 ROM，已按警告继续");
    }

    String dataHash(String name) throws Exception {
        JSONArray data = inputs().getJSONArray("data");
        for (int i = 0; i < data.length(); ++i) {
            JSONObject item = data.getJSONObject(i);
            String asset = item.getString("name");
            String installed = item.optString("installPath",
                    asset.startsWith(assetPrefix) ? asset.substring(assetPrefix.length()) : asset);
            if (name.equals(asset) || name.equals(installed) || name.equals(assetPrefix + installed))
                return item.getString("sha256");
        }
        throw new IllegalArgumentException("Missing runtime data: " + name);
    }

    void installData() throws Exception {
        JSONObject inputs = inputs();
        JSONArray obsolete = inputs.optJSONArray("obsoleteSystemAssets");
        for (int i = 0; obsolete != null && i < obsolete.length(); i++) {
            String path = obsolete.getString(i);
            if ((!path.startsWith("rootfs/system/") && !path.startsWith("lib/")) ||
                    java.util.Arrays.asList(path.split("/")).contains(".."))
                throw new IllegalArgumentException("Unexpected obsolete system asset: " + path);
            Files.deleteIfExists(new File(directory, path).toPath());
        }
        JSONArray data = inputs.getJSONArray("data");
        for (int i = 0; i < data.length(); ++i) {
            JSONObject item = data.getJSONObject(i);
            String asset = item.getString("name");
            File file = new File(directory, item.optString("installPath", asset.substring(assetPrefix.length())));
            if (!matches(file, item)) {
                Files.createDirectories(file.getParentFile().toPath());
                Files.deleteIfExists(file.toPath());
                try (InputStream input = openData(asset)) {
                    Files.copy(input, file.toPath());
                }
                if (!matches(file, item)) throw new IllegalStateException("Runtime data mismatch: " + asset);
            }
            if (item.has("mode")) {
                int mode = item.getInt("mode");
                // Redroid's layer carries some zero-mode metadata files. They must
                // stay readable by the guest (fs_config_* are read by libcutils).
                if (mode == 0) mode = 0644;
                Os.chmod(file.getPath(), mode);
            }
            if ((i + 1) % 256 == 0 || i + 1 == data.length())
                startupLog.record("DATA", "image files " + (i + 1) + "/" + data.length());
        }
        // Image paths that shared an inode were stored once; restore them as hard links so the
        // guest sees every path it expects without the payload carrying the bytes twice.
        JSONArray links = inputs.optJSONArray("links");
        for (int i = 0; links != null && i < links.length(); ++i) {
            JSONObject item = links.getJSONObject(i);
            File file = new File(directory, item.getString("path"));
            File target = new File(directory, item.getString("target"));
            if (target.isFile() && !file.exists()) {
                Files.createDirectories(file.getParentFile().toPath());
                try {
                    Files.createLink(file.toPath(), target.toPath());
                } catch (java.io.IOException failure) {
                    Files.copy(target.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        // Image symlinks are recreated literally: their targets are guest-absolute or relative to
        // a location that only exists inside the mapped rootfs.
        JSONArray symlinks = inputs.optJSONArray("symlinks");
        for (int i = 0; symlinks != null && i < symlinks.length(); ++i) {
            JSONObject item = symlinks.getJSONObject(i);
            File file = new File(directory, item.getString("path"));
            if (Files.isSymbolicLink(file.toPath()) &&
                    Files.readSymbolicLink(file.toPath()).toString().equals(item.getString("target"))) continue;
            Files.createDirectories(file.getParentFile().toPath());
            if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) clearDirectory(file);
            Files.deleteIfExists(file.toPath());
            Os.symlink(item.getString("target"), file.getPath());
        }
    }

    /** 数据文件来源：ROM 目录优先，否则 APK 的 assets（旧路径）。 */
    private InputStream openData(String asset) throws Exception {
        if (romRoot != null) {
            File source = new File(romRoot, asset);
            if (!source.isFile())
                throw new IllegalStateException("ROM 缺文件：" + asset + "（该 ROM 可能解包不完整，请重新安装）");
            return Files.newInputStream(source.toPath());
        }
        return context.getAssets().open(asset);
    }

    void clearDirectory(File directory) throws Exception {
        if (!directory.exists()) return;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    void link(File path, String target) throws Exception {
        Files.createDirectories(path.getParentFile().toPath());
        Files.deleteIfExists(path.toPath());
        Os.symlink(target, path.getPath());
    }
}
