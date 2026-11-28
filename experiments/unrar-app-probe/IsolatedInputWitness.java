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
        final byte[] sentinelBefore = Files.readAllBytes(sentinel.toPath());
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
            // Observe the original disk-backed transport independently.
            writer = Os.open(context.getCacheDir().getAbsolutePath(),
                    0x410000 | OsConstants.O_RDWR | OsConstants.O_CLOEXEC, 0600);
            try {
                reader = readonly(writer, fixture, false);
                Os.close(writer);
                writer = null;
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.dup(reader)) {
                    int[] actual = check(service, sentinel, fixture.length, fd, digest(fixture), false);
                    require(actual[0] != Process.myUid() && actual[1] != Process.myPid() && actual[2] == 1,
                        "disk input isolation");
                    receipt.append("CHUNKS_UNRAR_ISOLATED: tmpfile=verified\n");
                }
            } catch (IllegalArgumentException refused) {
                String reason = refused.getMessage();
                if (!("isolated input refused:stat:access_denied".equals(reason) ||
                      "isolated input refused:flags:access_denied".equals(reason) ||
                      "isolated input refused:read:access_denied".equals(reason))) throw refused;
                receipt.append("CHUNKS_UNRAR_ISOLATED: tmpfile=").append(reason.substring("isolated input refused:".length())).append('\n');
            } finally {
                if (reader != null) { Os.close(reader); reader = null; }
                if (writer != null) { Os.close(writer); writer = null; }
            }
            // Mandatory positive: kernel-sealed anonymous memory, no writable
            // descriptor or mutable backing filesystem admitted to the worker.
            writer = Os.memfd_create("chunks-synthetic-rar", 3); // CLOEXEC | ALLOW_SEALING
            reader = readonly(writer, fixture, true);
            Os.close(writer);
            writer = null;
            String expected = digest(fixture);
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.dup(reader)) {
                int[] actual = check(service, sentinel, fixture.length, fd, expected, true);
                require(actual[0] != Process.myUid() && actual[1] != Process.myPid(),
                    "not a different UID and process");
                require(actual[2] == 1, "isolated worker opened app-private path");
                receipt.append("CHUNKS_UNRAR_ISOLATED: readonly_input bytes=").append(fixture.length)
                    .append(" sha256=").append(expected).append(" distinct_uid=true private_path_denied=true sealed=true\n");
                try {
                    check(service, sentinel, fixture.length - 1, fd, expected, true);
                    throw new AssertionError("wrong length accepted");
                } catch (IllegalArgumentException refused) {
                    require("isolated input refused:identity:other".equals(refused.getMessage()), "wrong-length refusal reason");
                    receipt.append("CHUNKS_UNRAR_ISOLATED: wrong_length=refused\n");
                }
                try (ParcelFileDescriptor named = ParcelFileDescriptor.open(sentinel, ParcelFileDescriptor.MODE_READ_ONLY)) {
                    try {
                        check(service, sentinel, 3, named, digest(sentinelBefore), true);
                        throw new AssertionError("named inode accepted");
                    } catch (IllegalArgumentException refused) {
                        require("isolated input refused:identity:other".equals(refused.getMessage()) ||
                            "isolated input refused:stat:access_denied".equals(refused.getMessage()),
                            "named inode refusal reason");
                        receipt.append("CHUNKS_UNRAR_ISOLATED: named_inode=refused\n");
                    }
                }
                int[] again = check(service, sentinel, fixture.length, fd, expected, true);
                require(java.util.Arrays.equals(actual, again), "isolated process or result changed");
            }
            require(java.util.Arrays.equals(sentinelBefore, Files.readAllBytes(sentinel.toPath())),
                "private sentinel changed");
            receipt.append("CHUNKS_UNRAR_ISOLATED: PASS\n");
        } finally {
            if (reader != null) Os.close(reader);
            if (writer != null) Os.close(writer);
            if (bound) context.unbindService(connection);
            // Exclusively created synthetic fixture only.
            Files.deleteIfExists(sentinel.toPath());
        }
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
        try (ParcelFileDescriptor pin = ParcelFileDescriptor.dup(writer)) {
            FileDescriptor reader = Os.open("/proc/self/fd/" + pin.getFd(),
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
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

    private static int[] check(IBinder service, File sentinel, int bytes,
                               ParcelFileDescriptor descriptor, String expected, boolean sealed) throws Exception {
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
            int[] facts = {response.readInt(), response.readInt(), response.readInt()};
            require(expected.equals(response.readString()), "isolated input digest");
            return facts;
        } finally {
            request.recycle();
            response.recycle();
        }
    }
}
