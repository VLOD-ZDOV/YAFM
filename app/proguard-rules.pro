# R8 removes unused code and optimizes the rest. Names are not obfuscated: stack traces in bug reports
# stay readable, and FileExplorerViewModel.describe() tells errors apart by exception class names.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# JSch creates ciphers, key exchanges and signatures from class names in JSch.config.
-keep class com.jcraft.jsch.** { *; }

# smbj finds message handlers by annotation (mbassador) and loads parts by reflection.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }

# commons-net picks FTP listing parsers by class name.
-keep class org.apache.commons.net.** { *; }

# commons-compress checks for the optional codecs by class name.
-keep class org.tukaani.xz.** { *; }
-keep class org.brotli.dec.** { *; }

# Called from native code.
-keep class com.github.luben.zstd.** { *; }
-keep class org.videolan.** { *; }

# Optional dependencies of the libraries above that are not in the app (logging, Kerberos,
# Unix sockets, desktop-only APIs, annotations).
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.naming.**
-dontwarn javax.el.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.checkerframework.**
