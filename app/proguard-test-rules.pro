# Rules for the instrumentation APK when it is shrunk against qaMinified.
# The runner discovers tests reflectively (manifest runner, @RunWith, @Test),
# so keep the runner, JUnit and every test class intact.
-dontobfuscate
-dontwarn **
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class io.github.gamesbyian.chunks.**Test { *; }
-keep class io.github.gamesbyian.chunks.**Test$* { *; }
