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
    static final int PATH_EACCES = 1, PATH_EPERM = 2, PATH_ENOENT = 3, PATH_ENOTDIR = 4;

    static String pathDisposition(int code) {
        switch (code) {
            case PATH_EACCES: return "eacces";
            case PATH_EPERM: return "eperm";
            case PATH_ENOENT: return "enoent";
            case PATH_ENOTDIR: return "enotdir";
            default: throw new IllegalArgumentException("unknown path disposition");
        }
    }

    // Fixed control paths only; do not let a caller choose a probe target.
    // The isolated UID must be capable of opening a known existing path,
    // and distinguish a non-directory child from an accessible regular file.
    private static void pathControls() throws Exception {
        java.io.FileDescriptor control = Os.open("/proc/self/status",
            OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        try {
            byte[] prefix = new byte[5];
            int offset = 0;
            while (offset < prefix.length) {
                int n = Os.read(control, prefix, offset, prefix.length - offset);
                if (n <= 0) throw new IOException("short positive control");
                offset += n;
            }
            if (!"Name:".equals(new String(prefix, java.nio.charset.StandardCharsets.US_ASCII)))
                throw new IOException("wrong positive control");
        } finally { Os.close(control); }
        try {
            java.io.FileDescriptor unexpected = Os.open("/proc/self/status/chunks-synthetic-absent",
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
            Os.close(unexpected);
            throw new IOException("negative control opened");
        } catch (ErrnoException denied) {
            if (denied.errno != OsConstants.ENOTDIR) throw denied;
        }
    }

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
                stage = "path_controls";
                pathControls();
                stage = "private_path";
                int disposition = 0;
                try {
                    java.io.FileDescriptor probe = Os.open(sentinel, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
                    // Successful open is a distinct failure even if close fails.
                    stage = "unexpected_access";
                    Os.close(probe);
                    throw new IOException("parent private path accessible");
                } catch (ErrnoException denied) {
                    if (!"private_path".equals(stage)) throw denied;
                    if (denied.errno == OsConstants.EACCES) disposition = PATH_EACCES;
                    else if (denied.errno == OsConstants.EPERM) disposition = PATH_EPERM;
                    else if (denied.errno == OsConstants.ENOENT) disposition = PATH_ENOENT;
                    else if (denied.errno == OsConstants.ENOTDIR) disposition = PATH_ENOTDIR;
                    else throw denied;
                }
                if (disposition == 0) throw new IOException("unclassified private path");
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
                stage = "write_refusal";
                // Test only the synthetic input. An ordinary read-only FD
                // must reject direct writes and not permit a writable reopen.
                try {
                    Os.pwrite(input.getFileDescriptor(), new byte[]{0}, 0, 1, 0);
                    throw new IOException("input write unexpectedly succeeded");
                } catch (ErrnoException denied) {
                    if (denied.errno != OsConstants.EBADF && denied.errno != OsConstants.EACCES &&
                        denied.errno != OsConstants.EPERM) throw denied;
                }
                if (!sealed) {
                    stage = "writable_reopen";
                    try {
                        java.io.FileDescriptor reopened = Os.open("/proc/self/fd/" + input.getFd(),
                            OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0);
                        Os.close(reopened);
                        throw new IOException("disk input writable reopen succeeded");
                    } catch (ErrnoException denied) {
                        if (denied.errno != OsConstants.EACCES && denied.errno != OsConstants.EPERM)
                            throw denied;
                    }
                }
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
                reply.writeInt(1);
                reply.writeInt(disposition);
                reply.writeString(hash.toString());
            } catch (Exception refused) {
                // Fixed reason; no exception paths or descriptors escape.
                reply.setDataPosition(0);
                // Fixed stages and errno classes only. Keep the established
                // disk-carrier access_denied disposition for stat/flags/read.
                String reason = "other";
                if (refused instanceof ErrnoException) {
                    int errno = ((ErrnoException) refused).errno;
                    if (errno == OsConstants.EACCES || errno == OsConstants.EPERM) {
                        reason = ("private_path".equals(stage) || "path_controls".equals(stage))
                            ? (errno == OsConstants.EACCES ? "eacces" : "eperm") : "access_denied";
                    } else if (errno == OsConstants.ENOENT) reason = "enoent";
                    else if (errno == OsConstants.ENOTDIR) reason = "enotdir";
                }
                reply.writeException(new IllegalArgumentException("isolated input refused:" + stage + ":" + reason));
            } finally {
                if (input != null) try { input.close(); } catch (IOException ignored) {}
            }
            return true;
        }
    };
    @Override public IBinder onBind(Intent intent) { return endpoint; }
}
