# OkHttp and kotlinx-serialization (tree API only) ship their own consumer rules.
# Keep accessibility/service components referenced from the manifest (R8 keeps them by default).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Vosk talks to its native library through JNA, which needs the classes by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**

# The model on the phone: the JNI bridge finds these by name.
-keep class ru.iskra.core.llm.local.LlamaNative { *; }
-keep interface ru.iskra.core.llm.local.LlamaNative$TokenSink { *; }
-keep class * implements ru.iskra.core.llm.local.LlamaNative$TokenSink { *; }
