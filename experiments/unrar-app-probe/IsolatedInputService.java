package io.github.gamesbyian.chunks.unrarprobe;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.IOException;
import java.security.MessageDigest;

/** Disposable isolated-UID capability witness. NOT a decoder endpoint.
 * No original torrent paths, storage permissions, provider or writes. */
public final class IsolatedInputService extends Service {
    static final String INTERFACE = "chunks.synthetic.isolated.input.v1";
    static final int CHECK = IBinder.FIRST_CALL_TRANSACTION;
    static final int MAX_BYTES = 1024 * 1024;
    // Linux fcntl seal commands and all four basic seals, not Android API fields.
    static final int ADD_SEALS = 1033, GET_SEALS = 1034, ALL_SEALS = 15;

    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != CHECK) return false;
            data.enforceInterface(INTERFACE);
            // Only a test-owned synthetic private sentinel path, for denial
            // observation. Production protocol will have NO path field.
            String sentinel = data.readString();
            int expectedSize = data.readInt();
            boolean sealed = data.readInt() == 1;
            ParcelFileDescriptor input = data.readInt() == 1 ?
                ParcelFileDescriptor.CREATOR.createFromParcel(data) : null;
            String stage = "request";
            try {
                // Explicit user-0 fixture aliases only; isolated ApplicationInfo
                // need not report the same lexical dataDir as the parent.
                if (sentinel == null || !sentinel.matches(
                    "/data/(user/0|data)/io[.]github[.]gamesbyian[.]chunks[.]unrarprobe/cache/isolation-[0-9]+[.]bin") ||
                        expectedSize <= 0 || expectedSize > MAX_BYTES || input == null)
                    throw new IOException("invalid fixture request");
                stage = "private_path";
                boolean pathDenied = false;
                try {
                    java.io.FileDescriptor probe = Os.open(sentinel, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
                    Os.close(probe);
                } catch (ErrnoException denied) {
                    if (denied.errno != OsConstants.EACCES && denied.errno != OsConstants.EPERM) throw denied;
                    pathDenied = true;
                }
                if (!pathDenied) throw new IOException("parent private path accessible");
                stage = "stat";
                android.system.StructStat stat = Os.fstat(input.getFileDescriptor());
                stage = "identity";
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_nlink != 0 ||
                        stat.st_size != expectedSize)
                    throw new IOException("not exact anonymous regular input");
                stage = "flags";
                int access = Os.fcntlInt(input.getFileDescriptor(), OsConstants.F_GETFL, 0);
                boolean readOnly = (access & OsConstants.O_ACCMODE) == OsConstants.O_RDONLY;
                if (!readOnly) throw new IOException("writable input refused");
                stage = "seals";
                if (sealed && (Os.fcntlInt(input.getFileDescriptor(), GET_SEALS, 0) & ALL_SEALS) != ALL_SEALS)
                    throw new IOException("unsealed memory input");
                stage = "read";
                // pread avoids shared cursor assumptions across Binder dup.
                byte[] bytes = new byte[expectedSize];
                int offset = 0;
                while (offset < bytes.length) {
                    int count = Os.pread(input.getFileDescriptor(), bytes, offset,
                                         bytes.length - offset, offset);
                    if (count <= 0) throw new IOException("short anonymous input");
                    offset += count;
                }
                stage = "digest";
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
                // Only fixed stage and numeric access-denial classification.
                boolean denied = refused instanceof ErrnoException &&
                    (((ErrnoException) refused).errno == OsConstants.EACCES ||
                     ((ErrnoException) refused).errno == OsConstants.EPERM);
                reply.writeException(new IllegalArgumentException("isolated input refused:" + stage +
                    (denied ? ":access_denied" : ":other")));
            } finally {
                if (input != null) try { input.close(); } catch (IOException ignored) {}
            }
            return true;
        }
    };
    @Override public IBinder onBind(Intent intent) { return endpoint; }
}
