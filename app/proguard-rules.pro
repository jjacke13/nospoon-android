# ProGuard rules for nospoon release builds.
# R8 + resource shrinker run in release; without these rules the JNI
# bridge breaks in obfuscated builds.

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
