# Chunks R8 rules. Audited 2026-10-29; exercised by the qaMinified API35 smoke
# workflow (minified-smoke.yml) before release shrinking is enabled.

# libtorrent4j is a SWIG binding: the native library looks classes, fields,
# constructors and director callbacks up by their exact Java names, and ships
# no consumer rules of its own. Keep the whole package unrenamed.
-keep class org.libtorrent4j.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-dontwarn org.libtorrent4j.**

# Readable stack traces in bug reports; mapping.txt is kept as a CI artifact.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
