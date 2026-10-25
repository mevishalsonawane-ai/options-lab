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

# Jarvis: the llama.cpp bridge (src/jarvis/cpp) finds these by name.
-keepclasseswithmembernames class com.optionslab.app.ira.LlmNative { native <methods>; }

# Exception class NAMES only (12 Oct: the diagnostics said "a check failed in Jarvis words: ij1"): the diary names a failure
# by its class, never its message, so the class must read as itself. Code is still shrunk and obfuscated.
-keepnames class * extends java.lang.Throwable

# The app's own class and method NAMES (memory audit, 14 Oct: the main-thread stalls said only "o.a" - the screen watchdog,
# Speed.runningOnMain, looks for the app's first "com.optionslab." frame, which -repackageclasses had renamed away). Names only:
# the code is still shrunk and optimised (allowoptimization: R8 may still inline and merge; review, 14 Oct), and nothing
# secret is in a name (the repository is public).
-keepnames,allowoptimization class com.optionslab.** { <methods>; }

# The vault's write doors kept as real frames (17 Oct: Boss's diagnostics said only "BinderProxy.transactNative in
# Vault.encrypt" - no caller - because allowoptimization above let R8 fold the settings and vault write paths into the
# calling lambdas, which it renames). Without allowoptimization these few methods are never inlined, so the screen
# watchdog's stack shows "from SecurePrefs.put < ..." and the main-thread guard names the write. Names only.
-keepnames class com.optionslab.app.security.Vault { public <methods>; }
-keepnames class com.optionslab.app.security.Vault$LastWrite { public <methods>; }
-keepnames class com.optionslab.app.security.SecurePrefs { public <methods>; }
-keepnames class com.optionslab.app.security.SecureBlobs { public <methods>; }
-keepnames class com.optionslab.app.security.MainWrites { public <methods>; }
