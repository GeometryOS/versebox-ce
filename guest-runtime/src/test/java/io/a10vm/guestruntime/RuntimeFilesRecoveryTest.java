package io.a10vm.guestruntime;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class RuntimeFilesRecoveryTest {
    @Test
    public void installDataRestoresMissingAndChangedFilesFromRomPayload() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File testRoot = Files.createTempDirectory(context.getCacheDir().toPath(), "instance-repair-").toFile();
        try {
            File romRoot = new File(testRoot, "rom");
            File source = new File(romRoot, "payload/rootfs/system/build.prop");
            source.getParentFile().mkdirs();
            byte[] original = "ro.build.version.release=15".getBytes(StandardCharsets.UTF_8);
            Files.write(source.toPath(), original);
            String sha = sha256(original);
            JSONObject item = new JSONObject()
                    .put("name", "payload/rootfs/system/build.prop")
                    .put("bytes", original.length)
                    .put("sha256", sha);
            JSONObject manifest = new JSONObject()
                    .put("guestSdk", 29)
                    .put("payloadRoot", "payload/")
                    .put("data", new JSONArray().put(item))
                    .put("files", new JSONArray());
            Files.write(new File(romRoot, "versebox_inputs.json").toPath(),
                    manifest.toString().getBytes(StandardCharsets.UTF_8));

            File instanceRoot = new File(testRoot, "instance/vm");
            context.getApplicationInfo().nativeLibraryDir = new File(testRoot, "native").getAbsolutePath();
            new File(context.getApplicationInfo().nativeLibraryDir).mkdirs();
            RuntimeFiles files = new RuntimeFiles(
                    context,
                    instanceRoot,
                    new StartupLog(new File(testRoot, "logs"), "CI fixture"),
                    "versebox_inputs.json",
                    29,
                    "Android 10",
                    romRoot);
            files.installData();

            File restored = new File(instanceRoot, "rootfs/system/build.prop");
            assertEquals(sha, sha256(Files.readAllBytes(restored.toPath())));
            Files.write(restored.toPath(), "corrupt".getBytes(StandardCharsets.UTF_8));
            files.installData();
            assertEquals(sha, sha256(Files.readAllBytes(restored.toPath())));
        } finally {
            deleteTree(testRoot);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void deleteTree(File root) throws Exception {
        if (!root.exists()) return;
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(root.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
        }
    }
}
