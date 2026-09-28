# ---- No logging in release builds -------------------------------------------
# The app writes nothing to logcat itself, and this strips any call a library or
# a future change might add, so nothing about requests, responses, instruments
# or the ledger can reach a log that other tools on the device can read.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static int println(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(...);
    public void print(...);
}
-assumenosideeffects class java.lang.Throwable {
    public void printStackTrace();
}

# Obfuscate aggressively; keep only what the platform instantiates by name.
-repackageclasses 'o'
-allowaccessmodification
-keepattributes !SourceFile,!LineNumberTable

-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# JSch (static-IP relay): algorithms are loaded by class name from its config.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
