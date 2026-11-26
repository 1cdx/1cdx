# TextMate (tm4e) mem-parsing grammar & tema lewat refleksi (Gson) dan memuat
# tabel encoding regex (joni/jcodings) secara dinamis; jangan di-obfuscate.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-keep class com.google.gson.** { *; }
-dontwarn org.eclipse.tm4e.**
-dontwarn org.joni.**
-dontwarn org.jcodings.**
-dontwarn org.snakeyaml.**
-dontwarn org.eclipse.jdt.annotation.**

# JSch memuat implementasi cipher/kex/signature lewat Class.forName dari tabel konfigurasi.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
# BouncyCastle dipakai langsung oleh com.jcraft.jsch.bc.*; sisanya boleh di-shrink.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**

# Profil server diserialisasi Gson berdasarkan nama field.
-keep class io.github.onecdx.app.domain.ServerProfile { *; }
-keep class io.github.onecdx.app.domain.AuthMethod { *; }
