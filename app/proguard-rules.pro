# OkHttp and kotlinx-serialization (tree API only) ship their own consumer rules.
# Keep accessibility/service components referenced from the manifest (R8 keeps them by default).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
