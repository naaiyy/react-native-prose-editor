# JNA includes desktop AWT helpers that are not available on Android. The editor
# bindings do not call those APIs, but R8 still validates the references.
-dontwarn java.awt.**

# UniFFI uses JNA reflection/proxies to bind Kotlin method and structure field
# names to the Rust library. Keep these names stable in consuming release builds.
-keep class com.sun.jna.** { *; }
-keep class uniffi.editor_core.** { *; }
