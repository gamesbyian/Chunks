package io.github.gamesbyian.chunks.unrarprobe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Exercise real Binder descriptor transfer to an isolated UID. */
final class IsolatedInputWitness {
    private static void require(boolean b, String message) {
        if (!b) throw new AssertionError(message);
    }
    private static String digest(byte[] data) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
            text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    static void run(Context context, byte[] fixture, StringBuilder receipt) throws Exception {
        File sentinel = Files.createTempFile(context.getCacheDir().toPath(), "isolation-", ".bin").toFile();
        Files.write(sentinel.toPath(), new byte[]{12, 34, 56});
        final byte[] sentinelBefore = new byte[]{12, 34, 56};
        final StructStat sentinelIdentity = Os.lstat(sentinel.getAbsolutePath());
        verifySentinel(sentinel, sentinelIdentity, sentinelBefore);
        ArrayBlockingQueue<IBinder> ready = new ArrayBlockingQueue<>(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) { ready.offer(binder); }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        boolean bound = false;
        FileDescriptor writer = null, reader = null;
        try {
            bound = context.bindService(new Intent(context, IsolatedInputService.class),
                    connection, Context.BIND_AUTO_CREATE);
            require(bound, "isolated service bind refused");
            IBinder service = ready.poll(15, TimeUnit.SECONDS);
            require(service != null, "isolated service bind timeout");
            // The previous run proved this transport works. Now require the
            // full positive/negative contract on this exact carrier.
            writer = Os.open(context.getCacheDir().getAbsolutePath(),
                    0x410000 | OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0600);
            reader = readonly(writer, fixture, false);
            Os.close(writer);
            writer = null;
            verifyCarrier(service, sentinel, sentinelIdentity, sentinelBefore, reader, fixture, false, receipt);
            Os.close(reader);
            reader = null;
            receipt.append("CHUNKS_UNRAR_ISOLATED: tmpfile=verified\n");

            // Independently test the sealed memory carrier. Only a classified
            // parent read-only reopen failure AFTER sealing and byte proof is
            // a supported capability limitation. No read-write FD is sent.
            writer = Os.memfd_create("chunks-synthetic-rar", 3);
            try {
                reader = readonly(writer, fixture, true);
            } catch (ReadonlyReopenDenied denied) {
                receipt.append("CHUNKS_UNRAR_ISOLATED: memfd=parent_readonly_reopen_")
                    .append(denied.errnoClass).append(" seals_and_parent_bytes=verified\n");
            }
            Os.close(writer);
            writer = null;
            if (reader != null) {
                verifyCarrier(service, sentinel, sentinelIdentity, sentinelBefore, reader, fixture, true, receipt);
                receipt.append("CHUNKS_UNRAR_ISOLATED: memfd=verified\n");
            }
            verifySentinel(sentinel, sentinelIdentity, sentinelBefore);
            receipt.append("CHUNKS_UNRAR_ISOLATED: PASS\n");
        } finally {
            if (reader != null) Os.close(reader);
            if (writer != null) Os.close(writer);
            if (bound) context.unbindService(connection);
            // Exclusively created synthetic fixture only.
            Files.deleteIfExists(sentinel.toPath());
        }
    }

    private static void verifyCarrier(IBinder service, File sentinel, StructStat identity, byte[] sentinelBytes,
                                      FileDescriptor reader, byte[] fixture, boolean sealed,
                                      StringBuilder receipt) throws Exception {
        String carrier = sealed ? "memfd" : "tmpfile";
        String expected = digest(fixture);
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.dup(reader)) {
            int[] actual = check(service, sentinel, identity, sentinelBytes, fixture.length, fd, expected, sealed);
            require(actual[0] != Process.myUid() && actual[1] != Process.myPid() && actual[2] == 1,
                "isolated input identity");
            receipt.append("CHUNKS_UNRAR_ISOLATED: private_path=")
                .append(IsolatedInputService.pathDisposition(actual[3]))
                .append(" parent_inode_and_bytes=verified accessible_control=verified negative_control=enotdir\n");
            receipt.append("CHUNKS_UNRAR_ISOLATED: ").append(carrier)
                .append(" readonly_input bytes=").append(fixture.length).append(" sha256=").append(expected)
                .append(" distinct_uid=true private_path_denied=true sealed=").append(sealed)
                .append(" write_refused=true\n");
            try {
                check(service, sentinel, identity, sentinelBytes, fixture.length - 1, fd, expected, sealed);
                throw new AssertionError("wrong length accepted");
            } catch (IllegalArgumentException refused) {
                require("isolated input refused:identity:other".equals(refused.getMessage()), "wrong-length refusal reason");
                receipt.append("CHUNKS_UNRAR_ISOLATED: ").append(carrier).append(" wrong_length=refused\n");
            }
            try (ParcelFileDescriptor named = ParcelFileDescriptor.open(sentinel, ParcelFileDescriptor.MODE_READ_ONLY)) {
                try {
                    check(service, sentinel, identity, sentinelBytes, sentinelBytes.length, named, digest(sentinelBytes), sealed);
                    throw new AssertionError("named inode accepted");
                } catch (IllegalArgumentException refused) {
                    require("isolated input refused:identity:other".equals(refused.getMessage()) ||
                        "isolated input refused:stat:access_denied".equals(refused.getMessage()), "named inode refusal reason");
                    receipt.append("CHUNKS_UNRAR_ISOLATED: ").append(carrier).append(" named_inode=refused\n");
                }
            }
            int[] again = check(service, sentinel, identity, sentinelBytes, fixture.length, fd, expected, sealed);
            require(java.util.Arrays.equals(actual, again), "isolated process or result changed");
            receipt.append("CHUNKS_UNRAR_ISOLATED: ").append(carrier).append(" repeat=verified\n");
        }
        verifyBytes(reader, fixture);
    }

    private static void verifyBytes(FileDescriptor descriptor, byte[] fixture) throws Exception {
        byte[] actual = new byte[fixture.length];
        int offset = 0;
        while (offset < actual.length) {
            int n = Os.pread(descriptor, actual, offset, actual.length - offset, offset);
            require(n > 0, "anonymous input short parent read");
            offset += n;
        }
        require(java.util.Arrays.equals(actual, fixture), "anonymous input bytes changed");
    }

    private static final class ReadonlyReopenDenied extends IOException {
        final String errnoClass;
        ReadonlyReopenDenied(String errnoClass) { this.errnoClass = errnoClass; }
    }

    private static FileDescriptor readonly(FileDescriptor writer, byte[] fixture, boolean sealed) throws Exception {
        int offset = 0;
        while (offset < fixture.length) {
            int n = Os.write(writer, fixture, offset, fixture.length - offset);
            require(n > 0, "anonymous write");
            offset += n;
        }
        Os.fsync(writer);
        if (sealed) {
            Os.fcntlInt(writer, IsolatedInputService.ADD_SEALS, IsolatedInputService.ALL_SEALS);
            require((Os.fcntlInt(writer, IsolatedInputService.GET_SEALS, 0) & IsolatedInputService.ALL_SEALS) ==
                IsolatedInputService.ALL_SEALS, "memfd seals absent");
            // Independently prove the kernel refuses writes before handoff.
            try {
                Os.pwrite(writer, new byte[]{0}, 0, 1, 0);
                throw new AssertionError("sealed input writable");
            } catch (android.system.ErrnoException denied) {
                require(denied.errno == OsConstants.EPERM, "unexpected seal failure");
            }
        }
        StructStat before = Os.fstat(writer);
        require(OsConstants.S_ISREG(before.st_mode) && before.st_nlink == 0 &&
                before.st_size == fixture.length, "anonymous input identity");
        verifyBytes(writer, fixture);
        try (ParcelFileDescriptor pin = ParcelFileDescriptor.dup(writer)) {
            FileDescriptor reader;
            try {
                reader = Os.open("/proc/self/fd/" + pin.getFd(),
                    OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
            } catch (android.system.ErrnoException denied) {
                if (sealed && (denied.errno == OsConstants.EACCES || denied.errno == OsConstants.EPERM))
                    throw new ReadonlyReopenDenied(denied.errno == OsConstants.EACCES ? "eacces" : "eperm");
                throw denied;
            }
            boolean retained = false;
            try {
                StructStat view = Os.fstat(reader);
                require(view.st_dev == before.st_dev && view.st_ino == before.st_ino &&
                    view.st_nlink == 0 && view.st_size == before.st_size, "descriptor readback");
                retained = true;
                return reader;
            } finally {
                if (!retained) Os.close(reader);
            }
        }
    }

    private static boolean sameSentinel(StructStat actual, StructStat original, int length) {
        return OsConstants.S_ISREG(actual.st_mode) && actual.st_dev == original.st_dev &&
            actual.st_ino == original.st_ino && actual.st_nlink == 1 && actual.st_size == length;
    }

    private static void verifySentinel(File file, StructStat identity, byte[] expected) throws Exception {
        String path = file.getAbsolutePath();
        require(sameSentinel(Os.lstat(path), identity, expected.length), "parent sentinel path identity");
        FileDescriptor fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC |
            OsConstants.O_NOFOLLOW, 0);
        try {
            require(sameSentinel(Os.fstat(fd), identity, expected.length), "parent sentinel descriptor identity");
            byte[] actual = new byte[expected.length];
            int offset = 0;
            while (offset < actual.length) {
                int n = Os.pread(fd, actual, offset, actual.length - offset, offset);
                require(n > 0, "parent sentinel short read");
                offset += n;
            }
            require(java.util.Arrays.equals(actual, expected), "parent sentinel bytes changed");
            require(sameSentinel(Os.fstat(fd), identity, expected.length) &&
                sameSentinel(Os.lstat(path), identity, expected.length), "parent sentinel changed during read");
        } finally { Os.close(fd); }
    }

    private static int[] check(IBinder service, File sentinel, StructStat identity, byte[] sentinelBytes,
                               int bytes, ParcelFileDescriptor descriptor, String expected, boolean sealed) throws Exception {
        verifySentinel(sentinel, identity, sentinelBytes);
        Parcel request = Parcel.obtain(), response = Parcel.obtain();
        try {
            request.writeInterfaceToken(IsolatedInputService.INTERFACE);
            request.writeString(sentinel.getAbsolutePath());
            request.writeInt(bytes);
            request.writeInt(sealed ? 1 : 0);
            request.writeInt(1);
            descriptor.writeToParcel(request, 0);
            require(service.transact(IsolatedInputService.CHECK, request, response, 0), "no Binder response");
            response.readException();
            int[] facts = {response.readInt(), response.readInt(), response.readInt(), response.readInt()};
            IsolatedInputService.pathDisposition(facts[3]);
            require(expected.equals(response.readString()), "isolated input digest");
            return facts;
        } finally {
            request.recycle();
            response.recycle();
            // Also check after every refusal, so a missing or substituted
            // sentinel can never be accepted as isolated denial.
            verifySentinel(sentinel, identity, sentinelBytes);
        }
    }
}
