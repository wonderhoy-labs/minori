# AVF's classes come from the phone's com.android.virt module, not the APK. The app compiles against stubs.
-dontwarn android.system.virtualmachine.**

# Shizuku starts ShellService by name, through its constructor, in its own process.
-keep class com.wonderhoylabs.minori.setup.ShellService { <init>(); }

# JNI looks these up by name.
-keep class com.wonderhoylabs.minori.core.Native { native <methods>; }
-keep interface com.wonderhoylabs.minori.core.NativeProgress { *; }

# JSch loads its algorithms by class name.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
