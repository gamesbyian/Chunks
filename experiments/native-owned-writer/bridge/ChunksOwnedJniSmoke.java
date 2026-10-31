// Host integration proof for the exact libtorrent4j Java SessionParams method.
// This must instantiate a LIVE native session using the rebuilt JNI library;
// compiling SWIG bindings or the C++ adapter alone is insufficient.
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;

public final class ChunksOwnedJniSmoke {
    private static void check(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 1, "Expected one disposable test root");
        Path root = Paths.get(args[0]);
        Path receipts = Files.createDirectories(root.resolve("app-private-receipts"));
        Files.setPosixFilePermissions(receipts, PosixFilePermissions.fromString("rwx------"));
        SettingsPack settings = new SettingsPack().listenInterfaces("127.0.0.1:0");
        settings.setEnableDht(false);
        settings.setEnableLsd(false);
        SessionParams params = new SessionParams(settings);
        params.setOwnedDiskIO(receipts.toString());
        SessionManager manager = new SessionManager();
        try {
            manager.start(params);
            check(manager.swig() != null, "Native libtorrent session did not start");
        } finally {
            manager.stop();
        }
        check(Files.isDirectory(receipts), "Private native receipt directory missing");
        System.out.println("CHUNKS_OWNED_JNI_JAVA_SESSION=PASS");
    }
}
