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
    private static native byte[] decodeMulti(String root, int kind, int budget);
    private static final String[] KINDS = {"legacy4", "modern4", "modern5"};
    private static final String[][] NAMES = {
        {"rar3-old.rar", "rar3-old.r00", "rar3-old.r01"},
        {"rar3-vols.part1.rar", "rar3-vols.part2.rar", "rar3-vols.part3.rar"},
        {"rar5-vols.part1.rar", "rar5-vols.part2.rar", "rar5-vols.part3.rar"}
    };
    private static final String[][] BLOBS = {
        {"962d8b6f4b9d7e60396c1b5366f8962868c71fe1", "0ba628367eaae36c92a648217e9baf96125c2acb", "9bc19dde8cb2e46d3e064b83074c7fc4e7e5c3da"},
        {"483812fcb1c3630bdaa006dac709a4327862b8f6", "0fa8f87fae09ce30ac1e25b2b1f5610fdc706ea0", "5d32e160fded21ab5cd2a34a91d13305419c30dd"},
        {"0926f2f8f6d74bd1a7e8c4c1ebe74432c0b8d4e2", "d4f55a2f859d0c7b422b99e111db4fc5e8b7e34f", "ba6e9924a6db984d321364b8788301cda8c3bfba"}
    };
    private static final String MEMBER1 = "57613b4a0d18b31472c9abe90780dcaf834f4edf48e79008f027a99710cf3632";
    private static final String MEMBER2 = "6805973b24128edda3148235c77a7f7b5a7a9be6272bc882a6e030871b2b18d1";

    private static byte[] corpusAsset(Context context, int kind, int index) throws Exception {
        try (InputStream stream = context.getAssets().open(NAMES[kind][index])) {
            int expected = index != 2 ? 102400 : kind == 2 ? 11384 : 2572;
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = stream.read(buffer)) != -1;) {
                require(n > 0 && bytes.size() + n <= expected, "corpus asset bound");
                bytes.write(buffer, 0, n);
            }
            byte[] data = bytes.toByteArray();
            require(data.length == expected, "corpus asset length");
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            sha.update(("blob " + expected + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest(data)) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            require(hex.toString().equals(BLOBS[kind][index]), "corpus blob identity");
            return data;
        }
    }

    private static byte[] expectedMember(int count) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 1; i <= count; ++i) {
            String digits = Integer.toString(i);
            for (int s = digits.length(); s < 40; ++s) bytes.write(' ');
            for (int c = 0; c < digits.length(); ++c) bytes.write(digits.charAt(c));
            bytes.write('\n');
        }
        return bytes.toByteArray();
    }

    private static void corpusCase(Context context, int kind, String label, byte[][] inputs,
                                   int budget, boolean positive, StringBuilder receipt) throws Exception {
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "corpus-").toFile();
        try {
            HashSet<String> names = new HashSet<>();
            for (int i = 0; i < 3; ++i) if (inputs[i] != null) {
                Files.write(new File(root, NAMES[kind][i]).toPath(), inputs[i]);
                names.add(NAMES[kind][i]);
            }
            byte[] output = decodeMulti(root.getCanonicalPath(), kind, budget);
            require(names.equals(new HashSet<>(Arrays.asList(root.list()))), "unexpected corpus files");
            for (int i = 0; i < 3; ++i) if (inputs[i] != null)
                require(Arrays.equals(inputs[i], Files.readAllBytes(new File(root, NAMES[kind][i]).toPath())), "corpus source changed");
            String key = KINDS[kind] + "/" + label;
            if (positive) {
                byte[] first = expectedMember(5000), second = expectedMember(50);
                require(digest(first).equals(MEMBER1) && digest(second).equals(MEMBER2), "independent expectation");
                require(output != null && output.length == 207050, "corpus output length");
                require(Arrays.equals(first, Arrays.copyOfRange(output, 0, 205000)) &&
                    Arrays.equals(second, Arrays.copyOfRange(output, 205000, 207050)), "corpus output bytes");
                receipt.append("CHUNKS_UNRAR_JNI_CORPUS: ").append(key).append(" bytes=207050 member_sha256=")
                    .append(MEMBER1).append(',').append(MEMBER2).append('\n');
            } else {
                require(output == null, "unexpected decoded corpus data: " + key);
                receipt.append("CHUNKS_UNRAR_JNI_CORPUS: ").append(key).append("=refused\n");
            }
        } finally {
            for (int i = 0; i < 3; ++i) if (inputs[i] != null)
                Files.deleteIfExists(new File(root, NAMES[kind][i]).toPath());
            Files.deleteIfExists(root.toPath());
        }
    }

    private static void corpus(Context context, StringBuilder receipt) throws Exception {
        for (int kind = 0; kind < 3; ++kind) {
            byte[] a = corpusAsset(context, kind, 0), b = corpusAsset(context, kind, 1), c = corpusAsset(context, kind, 2);
            byte[] corrupt = b.clone();
            corrupt[corrupt.length / 2] ^= 0x40;
            byte[][] good = {a, b, c};
            corpusCase(context, kind, "positive", good, 207050, true, receipt);
            corpusCase(context, kind, "missing_middle", new byte[][]{a, null, c}, 207050, false, receipt);
            corpusCase(context, kind, "missing_last", new byte[][]{a, b, null}, 207050, false, receipt);
            corpusCase(context, kind, "corrupt_middle", new byte[][]{a, corrupt, c}, 207050, false, receipt);
            corpusCase(context, kind, "reversed_middle_last", new byte[][]{a, c, b}, 207050, false, receipt);
            corpusCase(context, kind, "aggregate_budget", good, 207049, false, receipt);
            corpusCase(context, kind, "positive_after_refusals", good, 207050, true, receipt);
        }
        receipt.append("CHUNKS_UNRAR_JNI_CORPUS: PASS\n");
    }

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
            corpus(context, receipt);
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
