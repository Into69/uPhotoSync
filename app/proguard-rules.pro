# AndroidX, Compose, Coil, WorkManager and coroutines ship their own consumer rules.
# These cover the SMB stack, which doesn't.

# smbj: protocol packets, enums and auth mechanisms are partly created reflectively.
# The library is small, so keep it whole rather than chase individual cases.
-keep class com.hierynomus.** { *; }

# mbassador (smbj's internal event bus) finds @Handler methods by reflection.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler <methods>;
}

# dcerpc (share listing): RPC request/response types are marshalled generically.
-keep class com.rapid7.client.dcerpc.** { *; }
# It throws java.rmi.(Un)MarshalException on malformed replies; Android has no java.rmi,
# so that path surfaces as a LinkageError, which NasClient.listShares catches.
-dontwarn java.rmi.**
# Its Guava dependency references compile-time-only annotation and JDK classes.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**
-dontwarn sun.misc.**

# BouncyCastle: smbj's BCSecurityProvider calls the lightweight API (MD4, RC4, AES-CMAC...)
# directly, so R8 can trace what's used; everything else in bcprov is dropped.

# Not present on Android and never reached at runtime: Kerberos/GSSAPI auth paths in smbj,
# optional mbassador EL support, and SLF4J's binding lookup (it falls back to no-op logging).
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.security.auth.callback.**
-dontwarn javax.el.**
-dontwarn org.slf4j.impl.**
-dontwarn javax.naming.**

# Keep line numbers so crash stack traces from release builds can be decoded.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
