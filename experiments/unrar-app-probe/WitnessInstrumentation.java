package io.github.gamesbyian.chunks.unrarprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;

/** Synthetic fixed-fixture test only. No Chunks engine, shared storage, network,
 * user paths, production authority, or publication. */
public final class WitnessInstrumentation extends Instrumentation {
    private static final String OUTPUT = "05abc6df769ec4637bcb82ddc1b5514d910d62502b40c1336aa15436405e2696";
    private static final String[] HASH = {
        "4cc78dae7289d222afae0c28a1e186c59dba7298cf49e7cfa25ad57230a054f7",
        "a5cd28ebb8b985e7c43f99d510e7cdf244b813f2b67e4702761e016eee8c216c"
    };
    private static native byte[] decode(String root, int budget);

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String digest(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
            result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static String name(int i) { return "Verified.part" + (i + 1) + ".rar"; }

    private static byte[] asset(Context context, int i) throws Exception {
        try (InputStream stream = context.getAssets().open(name(i))) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = stream.read(buffer)) != -1;) {
                require(n > 0 && bytes.size() + n <= 1024 * 1024, "asset bound");
                bytes.write(buffer, 0, n);
            }
            byte[] data = bytes.toByteArray();
            require(data.length > 0 && digest(data).equals(HASH[i]), "asset hash");
            return data;
        }
    }

    private static void runCase(Context context, String label, byte[][] inputs,
                                int budget, boolean positive, StringBuilder receipt) throws Exception {
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "fixture-").toFile();
        try {
            HashSet<String> names = new HashSet<>();
            for (int i = 0; i < inputs.length; ++i) {
                Files.write(new File(root, name(i)).toPath(), inputs[i]);
                names.add(name(i));
            }
            byte[] output = decode(root.getCanonicalPath(), budget);
            require(names.equals(new HashSet<>(Arrays.asList(root.list()))), "unexpected files");
            for (int i = 0; i < inputs.length; ++i)
                require(Arrays.equals(inputs[i], Files.readAllBytes(new File(root, name(i)).toPath())), "source changed");
            if (positive) {
                require(output != null && output.length == 1048 && digest(output).equals(OUTPUT), "wrong decoded bytes");
                receipt.append("CHUNKS_UNRAR_JNI: ").append(label).append(" bytes=1048 sha256=").append(OUTPUT).append('\n');
            } else {
                require(output == null, "unexpected decoded data: " + label);
                receipt.append("CHUNKS_UNRAR_JNI: ").append(label).append("=refused\n");
            }
        } finally {
            // Only known synthetic files in the exclusively created private root.
            for (int i = 0; i < inputs.length; ++i) Files.deleteIfExists(new File(root, name(i)).toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        StringBuilder receipt = new StringBuilder();
        try {
            Context context = getTargetContext();
            require(context.getPackageName().equals("io.github.gamesbyian.chunks.unrarprobe"), "wrong package");
            require(Process.myUid() == context.getApplicationInfo().uid && Process.myUid() >= 10000, "not app UID");
            System.loadLibrary("chunks_unrar_probe");
            byte[] a = asset(context, 0), b = asset(context, 1);
            byte[] changed = b.clone();
            changed[changed.length / 2] ^= 0x40;
            byte[][] good = {a, b};
            runCase(context, "positive", good, 1048, true, receipt);
            runCase(context, "missing_last", new byte[][]{a}, 1048, false, receipt);
            runCase(context, "reversed", new byte[][]{b, a}, 1048, false, receipt);
            runCase(context, "duplicate", new byte[][]{a, a}, 1048, false, receipt);
            runCase(context, "corrupt", new byte[][]{a, changed}, 1048, false, receipt);
            runCase(context, "truncated", new byte[][]{a, Arrays.copyOf(b, b.length - 64)}, 1048, false, receipt);
            runCase(context, "budget", good, 1047, false, receipt);
            runCase(context, "positive_after_refusals", good, 1048, true, receipt);
            receipt.append("CHUNKS_UNRAR_JNI: PASS app_uid=").append(Process.myUid()).append('\n');
            result.putString("stream", receipt.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            receipt.append("CHUNKS_UNRAR_JNI: FAIL ").append(failure.getClass().getName())
                .append(": ").append(failure.getMessage()).append('\n');
            result.putString("stream", receipt.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
