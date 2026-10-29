# qaMinified only (never release): keep the engine entry points that the
# instrumented smoke test calls directly, so R8 cannot remove or rewrite
# their signatures underneath a separately compiled test APK. Everything they
# call into (libtorrent4j JNI, alert callbacks, Compose UI) is still shrunk
# exactly as release would shrink it.
-keep class io.github.gamesbyian.chunks.engine.LocalTorrentEngine { public *; }
-keep class io.github.gamesbyian.chunks.engine.LocalEngineConfig { *; }
-keep class io.github.gamesbyian.chunks.engine.LocalAddResult** { *; }
-keep class io.github.gamesbyian.chunks.engine.LocalCommand** { *; }
-keep class io.github.gamesbyian.chunks.engine.LocalTransferStatus { *; }

# No renaming in qaMinified: the separately built test APK then needs no
# mapping. Shrinking and optimisation (the risky part for JNI) still run.
-dontobfuscate
