package io.a10vm.nativedisplay;

import android.content.Context;
import android.system.Os;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class GuestLibraryFiles {
    private final Context context;
    private final File nativeDirectory, workingDirectory;
    public GuestLibraryFiles(Context context, File workingDirectory) {
        this.context = context.getApplicationContext();
        this.nativeDirectory = new File(context.getApplicationInfo().nativeLibraryDir);
        this.workingDirectory = workingDirectory;
    }
    public File nativeFile(String name) { return new File(nativeDirectory, name); }
    public JSONObject readInputs(String name) throws Exception {
        try (InputStream stream = context.getAssets().open(name)) {
            byte[] buffer = new byte[4096];
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    public Set<String> verifyInputs(JSONArray files) throws Exception {
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < files.length(); ++i) {
            JSONObject expected = files.getJSONObject(i);
            File file = nativeFile(expected.getString("name"));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte item : digest.digest()) actual.append(String.format("%02x", item & 0xff));
            if (file.length() != expected.getLong("bytes") || !actual.toString().equals(expected.getString("sha256")))
                throw new IllegalStateException("Packaged input mismatch: " + file.getName());
            paths.add(file.getCanonicalPath());
        }
        return paths;
    }
    /**
     * 只收集「安装包自带」的库与清单的差异，不抛异常。
     *
     * <p>显示清单里的每一条都住在安装包的 lib/ 里，由安装包决定，不由 ROM 决定；换了 ROM 版本
     * 或换了安装包，它们必然对不上。用严格比对当启动闸门，等于让一份旧 ROM 因为安装包带了新
     * 渲染器而完全用不了（2026-11-20 真机：换成旧 ROM 后直接校验失败）。所以这里只回答
     * 「哪几条不一样」，由调用方记成警告并继续。
     */
    public java.util.List<String> verifyPackagedInputs(JSONArray files) throws Exception {
        java.util.List<String> mismatches = new java.util.ArrayList<>();
        for (int i = 0; i < files.length(); ++i) {
            JSONObject expected = files.getJSONObject(i);
            File file = nativeFile(expected.getString("name"));
            if (!file.isFile()) {
                mismatches.add(file.getName() + "（安装包缺这个文件，清单 " + expected.getLong("bytes") + " B）");
                continue;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream stream = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte item : digest.digest()) actual.append(String.format("%02x", item & 0xff));
            if (file.length() != expected.getLong("bytes") || !actual.toString().equals(expected.getString("sha256")))
                mismatches.add(file.getName() + "（安装包 " + file.length() + " B / 清单 "
                        + expected.getLong("bytes") + " B）");
        }
        return mismatches;
    }

    public File prepareRuntime(String name, boolean missingLibc) throws Exception {
        File runtime = new File(workingDirectory, name), libraries = new File(runtime, "lib");
        Files.createDirectories(libraries.toPath());
        for (String library : Arrays.asList("libc", "libm", "libdl")) {
            File link = new File(libraries, library + ".so");
            Files.deleteIfExists(link.toPath());
            if (!missingLibc || !library.equals("libc"))
                Os.symlink(nativeFile("liba10vm-a10-" + library + ".so").getCanonicalPath(), link.getAbsolutePath());
        }
        String config = "dir.probe = " + nativeDirectory.getCanonicalPath() + "\n[probe]\n"
                + "namespace.default.isolated = true\nnamespace.default.search.paths = " + libraries.getCanonicalPath()
                + "\nnamespace.default.permitted.paths = " + nativeDirectory.getCanonicalPath()
                + ":" + libraries.getCanonicalPath() + "\n";
        File destination = new File(runtime, "ld.config.txt");
        Files.write(destination.toPath(), config.getBytes(StandardCharsets.UTF_8));
        return destination;
    }
}
