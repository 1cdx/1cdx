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
