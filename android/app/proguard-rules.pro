# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Chaquopy classes
-keep class com.chaquo.python.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }

# Keep JGit classes
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# Keep SLF4J classes used by JGit
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# Keep Apache SSH classes used by JGit
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**

# Keep BC classes used by JGit
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Keep JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Optimize enum usage
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}