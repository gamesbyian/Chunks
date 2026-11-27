package io.github.gamesbyian.chunks.external;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import java.security.MessageDigest;

/** Actual test APK UID, no target-app code or privileged file access. */
public final class ExternalPayloadReaderActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent input = getIntent();
        new Thread(() -> {
            String result = "refused";
            try {
                Uri uri = Uri.parse(input.getStringExtra("fixture_uri"));
                try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (fd == null) throw new IllegalStateException();
                    byte[] bytes = new byte[32];
                    int count = Os.pread(fd.getFileDescriptor(), bytes, 0, bytes.length, 8193L);
                    if (count != bytes.length) throw new IllegalStateException();
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                    StringBuilder text = new StringBuilder();
                    for (byte b : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                    result = text.toString();
                }
            } catch (Exception ignored) { }
            // Only a fixed outcome or digest of the disposable fixture range.
            Intent receipt = new Intent(input.getStringExtra("fixture_action"))
                    .setPackage(input.getStringExtra("fixture_target"))
                    .putExtra("result", result).putExtra("uid", Process.myUid());
            sendBroadcast(receipt);
            runOnUiThread(this::finish);
        }).start();
    }
}
