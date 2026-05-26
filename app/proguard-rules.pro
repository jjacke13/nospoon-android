# ProGuard rules for nospoon release builds.
# R8 + resource shrinker run in release; without these rules the JNI
# bridge and Code Scanner integration break in obfuscated builds.

# ============================================================
# JNI bridge — Native methods must keep their exact names so the
# C side (libhyperdht_jni.so) can find them at runtime.
# ============================================================
-keep class com.hyperdht.** { *; }
-keepclassmembers class com.hyperdht.** {
    native <methods>;
}

# Callback interfaces are invoked from native code by name.
-keep,allowobfuscation interface com.hyperdht.* {
    *;
}

# Any class with native methods: keep the methods + the class.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
# Google Code Scanner / Play Services — dynamic module loading
# requires the public API surface to remain unobfuscated.
# ============================================================
-keep class com.google.android.gms.** { *; }
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**

# ============================================================
# Kotlin coroutines — internal classes accessed reflectively by
# the debugger and stacktrace recovery.
# ============================================================
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepclassmembernames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ============================================================
# Application + ViewBinding — keep entry points.
# ============================================================
-keep class com.nospoon.vpn.NospoonApp
-keep class com.nospoon.vpn.MainActivity
-keep class com.nospoon.vpn.NospoonVpnService

# ViewBinding generated classes referenced by string lookup.
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(android.view.View);
}

# ============================================================
# Sourcefile attribute — useful for crash logs in Play Console.
# Mapping file is uploaded separately so symbolication still works.
# ============================================================
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
