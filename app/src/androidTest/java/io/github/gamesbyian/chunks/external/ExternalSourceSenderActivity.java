package io.github.gamesbyian.chunks.external;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Sends a real URI grant from the separately installed test APK's own UID.
 * Standalone components use only platform APIs because this APK does not
 * contain the Kotlin runtime supplied by Chunks during instrumentation.
 */
public final class ExternalSourceSenderActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("fixture_mode");
        String target = getIntent().getStringExtra("fixture_target");
        boolean view = "view".equals(mode);
        if ((!view && !"share".equals(mode)) || target == null
                || !target.startsWith("io.github.gamesbyian.chunks") || target.endsWith(".test")) {
            finish();
            return;
        }

        String slot = view ? "first" : "second";
        byte[] bytes = ExternalTorrentTestProvider.torrent("external-" + slot + ".bin");
        try (FileOutputStream output = new FileOutputStream(
                ExternalTorrentTestProvider.file(this, slot))) {
            output.write(bytes);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write the test source", failure);
        }

        Uri uri = ExternalTorrentTestProvider.uri(this, slot);
        Intent handoff = new Intent(view ? Intent.ACTION_VIEW : Intent.ACTION_SEND)
                .setClassName(target, "io.github.gamesbyian.chunks.MainActivity")
                .setType(ExternalTorrentTestProvider.MIME_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (view) {
            // Start with a fresh Chunks task. Share must reenter that same task.
            handoff.setDataAndType(uri, ExternalTorrentTestProvider.MIME_TYPE);
            handoff.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else {
            handoff.putExtra(Intent.EXTRA_STREAM, uri);
        }
        handoff.setClipData(ClipData.newUri(getContentResolver(), "External torrent", uri));
        startActivity(handoff);
        finish();
    }
}
