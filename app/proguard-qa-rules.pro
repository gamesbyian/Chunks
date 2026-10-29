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

# The instrumentation runs in the app's process and resolves the libraries it
# shares with the app (tracing, Kotlin, coroutines, Compose, AndroidX core and
# lifecycle) from the shrunk app APK. R8 removes members the app never calls,
# so AndroidJUnitRunner failed with NoSuchMethodError on androidx.tracing.Trace.
# Keep those shared libraries whole in qaMinified only; Chunks' own code and
# the libtorrent4j JNI path stay shrunk and optimised. Release shrinking of
# these libraries relies on their published consumer rules.
-keep class androidx.tracing.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.annotation.** { *; }
-keep class androidx.collection.** { *; }
