package io.github.gamesbyian.chunks.unrarprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Disposable isolated-UID capability witness. NOT a decoder endpoint.
 * No original torrent paths, storage permissions, provider or writes. */
public final class IsolatedInputService extends Service {
    static final String INTERFACE = "chunks.synthetic.isolated.input.v1";
    static final int CHECK = IBinder.FIRST_CALL_TRANSACTION;
    static final int MAX_BYTES = 1024 * 1024;

    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != CHECK) return false;
            data.enforceInterface(INTERFACE);
            // Only a test-owned synthetic private sentinel path, for denial
            // observation. Production protocol will have NO path field.
            String sentinel = data.readString();
            int expectedSize = data.readInt();
            ParcelFileDescriptor input = data.readInt() == 1 ?
                ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
            try {
                if (sentinel == null || !sentinel.startsWith(getApplicationInfo().dataDir + "/cache/isolation-") ||
                        expectedSize <= 0 || expectedSize > MAX_BYTES || input == null)
                    throw new IOException("invalid fixture request");
                boolean pathDenied;
                try (FileInputStream ignored = new FileInputStream(new File(sentinel))) {
                    pathDenied = false;
                } catch (IOException | SecurityException denied) {
                    pathDenied = true;
                }
                android.system.StructStat stat = Os.fstat(input.getFileDescriptor());
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 0 ||
                        stat.st_size != expectedSize)
                    throw new IOException("not exact anonymous regular input");
                int access = Os.fcntlInt(input.getFileDescriptor(), OsConstants.F_GETFL, 0);
                boolean readOnly = (access & OsConstants.O_ACCMODE) == OsConstants.O_RDONLY;
                if (!readOnly) throw new IOException("writable input refused");
                // pread avoids shared cursor assumptions across Binder dup.
                byte[] bytes = new byte[expectedSize];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = Os.pread(input.getFileDescriptor(), bytes, offset,
                                         bytes.length - offset, offset);
                    if (count <= 0) throw new IOException("short anonymous input");
                    offset += count;
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                    hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                reply.writeNoException();
                reply.writeInt(Process.myUid());
                reply.writeInt(Process.myPid());
                reply.writeInt(pathDenied ? 1 : 0);
                reply.writeString(hash.toString());
            } catch (Exception refused) {
                // Fixed reason; no exception paths or descriptors escape.
                reply.setDataPosition(0);
                reply.writeException(new IllegalArgumentException("isolated input refused"));
            } finally {
                if (input != null) try { input.close(); } catch (IOException ignored) {}
            }
            return true;
        }
    };
    @Override public IBinder onBind(Intent intent) { return endpoint; }
}
